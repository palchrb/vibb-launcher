package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.zones.distanceM
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The Apple Wi-Fi lookup through the family's own server (design 125 step 2, §4a) - the pure rules: what the phone
 * asks (A3), what it does with each answer (A4, the shared `wifi_lookup/contract.json`'s `phone_answers`), the Apple
 * rows in the sealed table (A9: kind 4, keyed by the same HMAC, Apple's accuracy and fetch month), own against Apple
 * by trust and agreement, the tombstones, and still means still (H2). Tested in PlacesTest and WifiLookupContractTest.
 */

/** At most this many BSSIDs in one ask (GrapheneOS's 40). */
const val APPLE_ASK_MAX = 40

/** An Apple row lives this long from Apple's fetch month, and a tombstone blocks as long (A9). */
const val APPLE_ROW_MIN = 90 * 24 * 60

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

/** One row of the 200's `aps`: Apple's position, its accuracy, the month Apple answered (`YYYY-MM`). */
@Serializable
data class AppleAp(val bssid: String, val lat: Double, val lng: Double, val accM: Int, val month: String)

@Serializable
data class WifiLookupAnswer(val aps: List<AppleAp>)

/** What the phone does with an answer (A4). */
sealed interface AppleStep {
    /** Store the rows. */
    data class Use(val aps: List<AppleAp>) : AppleStep

    /** No ask for [ms]. */
    data class Wait(val ms: Long) : AppleStep

    /** No ask until a policy says on (409). */
    data object Off : AppleStep
}

/** A4: the phone's action for [status] (0 = no answer: a transport failure or the timeout) and [body]. */
fun appleStep(status: Int, body: String?): AppleStep = when (status) {
    200 -> runCatching { ServerJson.decodeFromString(WifiLookupAnswer.serializer(), body!!) }
        .map { AppleStep.Use(it.aps) as AppleStep }
        .getOrDefault(AppleStep.Wait(APPLE_WAIT_MS))
    409 -> AppleStep.Off
    503 -> AppleStep.Wait(
        runCatching { ServerJson.parseToJsonElement(body!!).jsonObject["retry_after_s"]!!.jsonPrimitive.int }
            .getOrNull()?.takeIf { it > 0 }?.let { minOf(it * 1000L, APPLE_WAIT_MAX_MS) } ?: APPLE_WAIT_MS,
    )
    else -> AppleStep.Wait(APPLE_WAIT_MS)
}

/** Whether an ask may go at [nowMs]: not off by a 409, the wait over (a wait past [APPLE_WAIT_MAX_MS] is a clock set
 * back: over). */
fun appleMayAsk(nowMs: Long, waitUntilMs: Long, off: Boolean): Boolean =
    !off && (nowMs >= waitUntilMs || waitUntilMs - nowMs > APPLE_WAIT_MAX_MS)

/** A9: an Apple row with a position (not a tombstone) fetched less than [APPLE_ROW_MIN] ago. */
fun PlaceRow.appleLive(nowMin: Int): Boolean =
    kind == RowKind.APPLE && blockedUntilMin == 0 && nowMin - lastTeachMin in 0 until APPLE_ROW_MIN

/** The epoch minute Apple's month began (UTC), or `null` for anything but `YYYY-MM`. */
fun appleMonthMin(month: String): Int? {
    val m = Regex("""(\d{4})-(\d{2})""").matchEntire(month) ?: return null
    val (year, mon) = m.destructured
    if (mon.toInt() !in 1..12) return null
    return (LocalDate.of(year.toInt(), mon.toInt(), 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC) / 60).toInt()
}

/**
 * A3: the BSSIDs to ask for from [heard] (a scan's usable Wi-Fi results): strongest first, at most [APPLE_ASK_MAX],
 * leaving out every one with a trusted own row, a live Apple row or an Apple tombstone. An untrusted own row doesn't
 * stop the ask.
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
 * A9: the answer's rows into the table at [nowMin]: each a kind-4 row keyed by [keys] (a BSSID that isn't a usable
 * unicast one, a position out of range or a month older than [APPLE_ROW_MIN] is left out; a month ahead counts as now),
 * replacing a live Apple row of its key, never a tombstone. Then [reconcileApple] and [prune].
 */
fun storeApple(rows: List<PlaceRow>, aps: List<AppleAp>, keys: PlaceKeys, nowMin: Int): List<PlaceRow> {
    val byId = LinkedHashMap<String, PlaceRow>()
    rows.forEach { byId[it.id] = it }
    for (ap in aps) {
        val bssid = usableBssid(ap.bssid) ?: continue
        val fetched = appleMonthMin(ap.month)?.coerceAtMost(nowMin) ?: continue
        if (nowMin - fetched >= APPLE_ROW_MIN) continue
        if (ap.lat !in -90.0..90.0 || ap.lng !in -180.0..180.0 || ap.accM !in 1..5000) continue
        val row = PlaceRow(
            kind = RowKind.APPLE,
            key = keys.ap(bssid),
            lat = ap.lat,
            lng = ap.lng,
            rangeM = ap.accM.toFloat(),
            lastTeachMin = fetched,
            lastSeenMin = nowMin,
        )
        if (byId[row.id]?.blocked(nowMin) == true) continue
        byId[row.id] = row
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

/** The switch off (A9): every Apple row goes. */
fun dropApple(rows: List<PlaceRow>): List<PlaceRow> = rows.filter { it.kind != RowKind.APPLE }

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

/** H2 for Apple fixes: what a `vibb_apple` fix may do while the phone is still. */
enum class StillVerdict { USE, RENEW, CONTRADICTS }

/**
 * A9 (H2): while `motion()` is still ([still]) a fix using Apple's positions only renews the [vouch] - re-sent with
 * `still: true` - when it lies within `accOld + accNew` of it; outside it is an Apple contradiction, never reported.
 * An own fix, a moving phone or no vouch: used as it is.
 */
fun appleStill(fix: LearnedFix, vouch: Vouched?, still: Boolean): StillVerdict {
    if (!still || vouch == null || fix.source == PlaceSource.OWN) return StillVerdict.USE
    return if (distanceM(fix.lat, fix.lng, vouch.lat, vouch.lng) <= vouch.accuracyM + fix.accuracyM) StillVerdict.RENEW else StillVerdict.CONTRADICTS
}
