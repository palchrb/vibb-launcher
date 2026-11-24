package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.distanceM
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/*
 * The Apple Wi-Fi lookup through the family's own server (design 125 step 2, §4a) - the pure rules: what the phone
 * asks (A3), what it does with each answer (A4, the shared `wifi_lookup/contract.json`'s `phone_answers`), the Apple
 * rows in the sealed table (A9: kind 4, keyed by the same HMAC, Apple's accuracy, live until the server's row stops
 * being fresh), the phone's negatives (what Apple doesn't know, until the server asks again), own against Apple by
 * trust and agreement, the tombstones, and still means still (H2). Tested in PlacesTest and WifiLookupContractTest.
 */

/** At most this many BSSIDs in one ask (GrapheneOS's 40). */
const val APPLE_ASK_MAX = 40

/** A tombstone - an Apple row an own row or an own teach contradicts - blocks this long (A9). */
const val APPLE_ROW_MIN = 90 * 24 * 60

/** The server's cache months (contract `rules`): a row fresh this many calendar months from the month Apple
 * answered; a negative until the month it was asked in ends. */
const val APPLE_FRESH_MONTHS = 3
const val APPLE_NEGATIVE_MONTHS = 1

/** A stale row (0 months left: served while the server couldn't ask Apple) is kept this long (code QA M1). */
const val APPLE_STALE_GRACE_MIN = 24 * 60

/** An Apple row ending further ahead than this is a clock set back: not live (the server's 3 months, and a day). */
const val APPLE_LIVE_MAX_MIN = (APPLE_FRESH_MONTHS * 31 + 1) * 24 * 60

/** At most this many Apple rows, the least recently seen out first (A9). */
const val APPLE_ROWS_MAX = 3000

/** An Apple row counts as a trusted row of this many occasions, weight 1 (A9). */
const val APPLE_OCCASIONS = 3

/** The wait after a refusal or a transport failure, and the longest `retry_after_s` taken (A4, M4). */
const val APPLE_WAIT_MS = 15 * 60_000L
const val APPLE_WAIT_MAX_MS = 24 * 60 * 60_000L

/** One ask's whole call (A3). */
const val APPLE_TIMEOUT_MS = 15_000L

const val PROVIDER_APPLE = "vibb_apple"

/** Where a learned fix's positions came from (A9, D16's classes). */
enum class PlaceSource { OWN, APPLE, MIXED }

/** The policy's `wifi_lookup` (A2): the parent's switch. Missing = off. */
@Serializable
data class WifiLookupPolicy(val on: Boolean = false)

fun wifiLookupOn(policy: PolicyResponse?): Boolean = policy?.wifiLookup?.on == true

/** `POST /api/devices/wifi-lookup`'s body. */
@Serializable
data class WifiLookupRequest(val bssids: List<String>)

/** One row of the 200's `aps`: Apple's position, its accuracy, the whole calendar months (UTC) the server's row stays
 * fresh (0 = stale, served while Apple can't be asked) - never the month Apple answered (code QA M3). */
@Serializable
data class AppleAp(val bssid: String, val lat: Double, val lng: Double, val accM: Int, val freshMonthsLeft: Int)

/** The 200: the asked BSSIDs Apple placed, those it doesn't know, and a wait when the server couldn't ask for the
 * rest. */
@Serializable
data class WifiLookupAnswer(val aps: List<AppleAp>, val unknown: List<String> = emptyList(), val retryAfterS: Long? = null)

/** What the phone does with an answer (A4). */
sealed interface AppleStep {
    /** Store the rows and the negatives; no ask for [waitMs] (0: none). */
    data class Use(val aps: List<AppleAp>, val unknown: List<String> = emptyList(), val waitMs: Long = 0) : AppleStep

    /** No ask for [ms]. */
    data class Wait(val ms: Long) : AppleStep

    /** No ask until a policy says on (409). */
    data object Off : AppleStep
}

/** A wait the server named, at most [APPLE_WAIT_MAX_MS]; [orElse] when none or not positive. */
private fun waitOf(seconds: Long?, orElse: Long): Long = seconds?.takeIf { it > 0 }?.let { minOf(it * 1000L, APPLE_WAIT_MAX_MS) } ?: orElse

@Serializable
private data class Refusal(val retryAfterS: Long? = null)

/** A4: the phone's action for [status] (0 = no answer: a transport failure or the timeout) and [body]. */
fun appleStep(status: Int, body: String?): AppleStep = when (status) {
    200 -> runCatching { ServerJson.decodeFromString(WifiLookupAnswer.serializer(), body!!) }
        .map { AppleStep.Use(it.aps, it.unknown, waitOf(it.retryAfterS, 0)) as AppleStep }
        .getOrDefault(AppleStep.Wait(APPLE_WAIT_MS))
    409 -> AppleStep.Off
    503 -> AppleStep.Wait(waitOf(runCatching { ServerJson.decodeFromString(Refusal.serializer(), body!!).retryAfterS }.getOrNull(), APPLE_WAIT_MS))
    else -> AppleStep.Wait(APPLE_WAIT_MS)
}

/** Whether an ask may go at [nowMs]: not off by a 409, the wait over (a wait past [APPLE_WAIT_MAX_MS] is a clock set
 * back: over). */
fun appleMayAsk(nowMs: Long, waitUntilMs: Long, off: Boolean): Boolean =
    !off && (nowMs >= waitUntilMs || waitUntilMs - nowMs > APPLE_WAIT_MAX_MS)

/** A9: an Apple row with a position (not a tombstone or a negative) until its end ([PlaceRow.lastTeachMin] holds it for
 * kind 4). */
fun PlaceRow.appleLive(nowMin: Int): Boolean =
    kind == RowKind.APPLE && blockedUntilMin == 0 && lastTeachMin > nowMin && lastTeachMin - nowMin <= APPLE_LIVE_MAX_MIN

/** The epoch minute the UTC calendar month [months] after [nowMin]'s begins. */
fun monthStartMin(nowMin: Int, months: Int): Int {
    val date = Instant.ofEpochSecond(nowMin * 60L).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).plusMonths(months.toLong())
    return (date.atStartOfDay().toEpochSecond(ZoneOffset.UTC) / 60).toInt()
}

/** Until when the phone keeps a row with [freshMonthsLeft] (code QA M1): the server's own month rule - the start of
 * the month it asks Apple again -, a stale one [APPLE_STALE_GRACE_MIN]; `null` for a count the rule doesn't know. */
fun appleUntilMin(nowMin: Int, freshMonthsLeft: Int): Int? = when (freshMonthsLeft) {
    0 -> nowMin + APPLE_STALE_GRACE_MIN
    in 1..APPLE_FRESH_MONTHS -> monthStartMin(nowMin, freshMonthsLeft)
    else -> null
}

/**
 * A3: the BSSIDs to ask for from [heard] (a scan's usable Wi-Fi results): strongest first, at most [APPLE_ASK_MAX],
 * leaving out every one with a trusted own row, a live Apple row, a negative or a tombstone. An untrusted own row
 * doesn't stop the ask.
 */
fun appleAsk(heard: List<Heard>, rows: List<PlaceRow>, nowMin: Int): List<String> {
    val own = rows.filter { it.kind == RowKind.WIFI }.associateBy { it.key }
    val apple = rows.filter { it.kind == RowKind.APPLE }.associateBy { it.key }
    return heard.asSequence()
        .filter { it.kind == RowKind.WIFI && it.bssid.isNotEmpty() }
        .filter { own[it.key]?.trusted(nowMin) != true }
        .filter { h -> apple[h.key]?.let { it.appleLive(nowMin) || it.blocked(nowMin) } != true }
        .sortedByDescending { it.rssi }
        .map { it.bssid }
        .distinct()
        .take(APPLE_ASK_MAX)
        .toList()
}

/**
 * A9: the answer into the table at [nowMin]. Each row a kind-4 row keyed by [keys], live until [appleUntilMin] (a
 * BSSID that isn't a usable unicast one, a position out of range or an unknown month count is left out); each
 * unknown BSSID a negative - a positionless row blocked until the server's negative ends (code QA M2). Neither replaces
 * a tombstone or a negative still blocking. Then [reconcileApple] and [prune].
 */
fun storeApple(rows: List<PlaceRow>, aps: List<AppleAp>, unknown: List<String>, keys: PlaceKeys, nowMin: Int): List<PlaceRow> {
    val byId = LinkedHashMap<String, PlaceRow>()
    rows.forEach { byId[it.id] = it }
    fun put(row: PlaceRow) {
        if (byId[row.id]?.blocked(nowMin) != true) byId[row.id] = row
    }
    for (ap in aps) {
        val bssid = usableBssid(ap.bssid) ?: continue
        val until = appleUntilMin(nowMin, ap.freshMonthsLeft) ?: continue
        if (ap.lat !in -90.0..90.0 || ap.lng !in -180.0..180.0 || ap.accM !in 1..5000) continue
        put(PlaceRow(kind = RowKind.APPLE, key = keys.ap(bssid), lat = ap.lat, lng = ap.lng, rangeM = ap.accM.toFloat(), lastTeachMin = until, lastSeenMin = nowMin))
    }
    val negativeUntil = monthStartMin(nowMin, APPLE_NEGATIVE_MONTHS)
    for (raw in unknown) {
        val bssid = usableBssid(raw) ?: continue
        put(PlaceRow(kind = RowKind.APPLE, key = keys.ap(bssid), lastSeenMin = nowMin, blockedUntilMin = negativeUntil))
    }
    return prune(reconcileApple(byId.values.toList(), nowMin), nowMin)
}

/** An Apple row's range for a band: at least the band's floor (A9). */
private fun appleRange(row: PlaceRow, band: Band): Float = maxOf(band.floorM, row.rangeM)

/** A9's tombstone: no position, blocked for [APPLE_ROW_MIN]. */
private fun tombstone(row: PlaceRow, nowMin: Int) = row.copy(lat = 0.0, lng = 0.0, blockedUntilMin = nowMin + APPLE_ROW_MIN)

/** A9: a live Apple row that disagrees with a trusted own row of its key (centres further apart than both ranges)
 * becomes a tombstone - the own row is newer ground truth. An agreeing own row leaves it be. */
fun reconcileApple(rows: List<PlaceRow>, nowMin: Int): List<PlaceRow> {
    val own = rows.filter { it.kind == RowKind.WIFI && it.trusted(nowMin) }.associateBy { it.key }
    return rows.map { a ->
        val o = own[a.key]
        if (o == null || !a.appleLive(nowMin)) {
            a
        } else if (distanceM(o.lat, o.lng, a.lat, a.lng) > o.rangeM + appleRange(a, o.band)) {
            tombstone(a, nowMin)
        } else {
            a
        }
    }
}

/** A9: an own GPS teach that puts a BSSID beyond its live Apple row's band reach (D7) makes that row a tombstone. */
fun appleTaught(rows: List<PlaceRow>, sightings: List<Sighting>, nowMin: Int): List<PlaceRow> {
    val beyond = sightings.filter { it.heard.kind == RowKind.WIFI }.associateBy { it.heard.key }
    if (beyond.isEmpty()) return rows
    return rows.map { a ->
        val s = beyond[a.key]
        if (s != null && a.appleLive(nowMin) && distanceM(a.lat, a.lng, s.lat, s.lng) - s.accEff > s.heard.band.reachM) tombstone(a, nowMin) else a
    }
}

/** The switch off (A9, design 125b C7): every Apple row goes, the cells' too. */
fun dropApple(rows: List<PlaceRow>): List<PlaceRow> = rows.filter { it.kind != RowKind.APPLE && it.kind != RowKind.APPLE_CELL }

/**
 * A9: which row gives a heard result's position - a trusted own row, a live Apple row (as a trusted row of
 * [APPLE_OCCASIONS] occasions, weight 1, the band's floor at least), or `null`. Both: the smaller range when they agree
 * (a tie: the own row), else the own row. The flag: the position is Apple's.
 */
fun chooseRow(h: Heard, own: PlaceRow?, apple: PlaceRow?, nowMin: Int): Pair<PlaceRow, Boolean>? {
    val o = own?.takeIf { it.kind == RowKind.WIFI && it.trusted(nowMin) }
    val a = apple?.takeIf { it.appleLive(nowMin) }?.let {
        it.copy(band = h.band, weight = 1f, occasions = APPLE_OCCASIONS, rangeM = appleRange(it, h.band))
    }
    return when {
        o != null && a != null ->
            if (a.rangeM < o.rangeM && distanceM(o.lat, o.lng, a.lat, a.lng) <= o.rangeM + a.rangeM) a to true else o to false
        o != null -> o to false
        a != null -> a to true
        else -> null
    }
}

/**
 * A9 (H2): still means `STILL` alone - the sensor hasn't fired since the last fresh fix, so that fix is where the phone
 * is. `STILL_SINCE_TRY` is not still here (2026-11-22, the owner's phone indoors at a new place): the phone moved since
 * that fix and only a failed try came after, so the vouch is the old place - every Apple fix there contradicted it or
 * was withheld, and the position stayed hours old. Apple may move it as after any move (`MOVED`).
 */
fun appleStillMotion(motion: Motion): Boolean = motion == Motion.STILL

// Still means still for every Wi-Fi fix since design 131: `wifiVerdict` (PlacesPlan.kt) - an Apple fix within the vouch
// renews it, one outside is refused and counted, and with no vouch GPS runs (never withheld).
