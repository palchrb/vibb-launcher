package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.dto.PolicyZone
import com.kidslauncher.mdm.zones.distanceM
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Wi-Fi places, learned on the phone (design 125 step 1, `docs/design/125-wifi-places-on-the-phone.md`) - the pure
 * rules, no Android imports, tested in PlacesTest over `wifi_places.json` and a synthetic town. When the phone's own
 * satellite fix is good, the Wi-Fi access points (and the serving cell) heard at that moment are taught where they must
 * be; later a scan matched against the table gives a fix computed on the phone (`vibb_wifi`, `vibb_cell`). Nothing of
 * the table leaves the phone: rows are keyed by an HMAC under a per-install key, never a BSSID, SSID or cell id.
 *
 * Ideas borrowed (design 125 §10): the moving-SSID list (the asset `places/moving_ssids.json`, from Local NLP Backend's
 * `RfEmitter.kt`, GPL-3.0, and microG's `MovingWifiHelper.kt`, Apache-2.0), the stale-scan guard, complete-link
 * grouping and stream pairing (Local NLP Backend), the connected AP always present (microG), the oldest result's time
 * (GrapheneOS NetworkLocation), the weighted-centroid row (Déjà Vu). The code here is our own.
 */

// --- D1, D2: who teaches and how a scan is paired with the teacher ---

/** A teaching fix is at least this accurate (D1). */
const val TEACH_ACCURACY_MAX_M = 25f

/** ... no faster than this (a car's GPS lags the scan). */
const val TEACH_SPEED_MAX_MPS = 3f

/** ... from at least this many satellites (the fix's `satellites` extra; missing = no teach, design 125 §8 check 1). */
const val TEACH_SATELLITES_MIN = 5

/** The GPS try's log names the signal of at most this many of its last fixes (2026-11-23). */
const val SIGNAL_LOG_FIXES = 10

/** A stream fix pairs with a scan result this close in elapsed time (D2, Local NLP Backend's way). */
const val PAIR_STREAM_MS = 1_000L

/** Any other teacher pairs results within this (D2). */
const val PAIR_SINGLE_MS = 30_000L

/** The speed a pairing assumes when the fix has none (D2). */
const val UNKNOWN_SPEED_MPS = 1.5f

/** A pairing worse than this teaches nothing (D2). */
const val ACC_EFF_MAX_M = 40.0

/** At most this many APs per teach, the strongest (D2). */
const val TEACH_APS_MAX = 60

// --- D5-D8: rows ---

/** Teaches this far apart count as separate occasions (D5). */
const val OCCASION_GAP_MIN = 30

/** A row's sighting weight is capped here: the newest ~20 sightings dominate (D6). */
const val WEIGHT_CAP = 20f

/** A row with two moves within this is blocked as long; a zone link with two outside sightings too (D7, D12). */
const val BLOCK_MIN = 60 * 24 * 60

/** A row unseen this long goes (D8). */
const val ROW_UNSEEN_MIN = 180 * 24 * 60

/** At most this many rows; the least recently seen go first (D8). */
const val ROWS_MAX = 4000

/** `days` counts distinct local days up to this. */
const val DAYS_CAP = 1000

/** Cells' range lies within these (D10). */
const val CELL_RANGE_MIN_M = 500f
const val CELL_RANGE_MAX_M = 10_000f

/** A band: a row's range floor (what a phone hears through walls) and its move reach (D6, D7). */
enum class Band(val code: Int, val floorM: Float, val reachM: Double) {
    NONE(0, 0f, 0.0),
    G24(1, 100f, 300.0),
    G5(2, 60f, 150.0),
    G6(3, 60f, 150.0),
    CELL(4, CELL_RANGE_MIN_M, 20_000.0),
    ;

    companion object {
        fun of(code: Int): Band = entries.firstOrNull { it.code == code } ?: NONE
    }
}

fun bandOf(frequencyMhz: Int): Band = when {
    frequencyMhz < 3000 -> Band.G24
    frequencyMhz < 5925 -> Band.G5
    else -> Band.G6
}

// --- D9: the lookup ---

/** A fresh scan's results are at most this old (D9). */
const val SCAN_FRESH_MS = 30_000L

/** The fallback takes results at most this old - reported, never fresh (D9, M2). */
const val SCAN_FALLBACK_MS = 120_000L

/** A lookup needs at least this many usable results that fresh (D9, L8). */
const val SCAN_RESULTS_MIN = 3

/** A scan is stale only when at least this many results repeat their level ... */
const val STALE_REPEATS_MIN = 5

/** ... and they are at least this share of the results the previous scan heard too (code QA H1: a still phone repeats
 * whole-number levels often - a few repeats are noise, only a whole repeated list is an old one). */
const val STALE_SHARE = 0.8

/** A learned fix is never better than this ... */
const val LEARNED_ACCURACY_FLOOR_M = 30.0

/** ... and over this there is none (D9). */
const val LEARNED_ACCURACY_MAX_M = 200.0

/** The share of the cluster's median range in the accuracy (D9's 0.5). The synthetic town: taught from every street,
 * 0.5 gives a median accuracy of 106 m against a median error of 15 m (every fix within); taught from one street only,
 * 70 m against 36 m (79 % within), where 0.25 held only 54 % (code QA H2). Kept until the Jelly Star's leave-one-out
 * counts (D16) say otherwise. */
const val RANGE_SHARE = 0.5

/** A cell fix over this is none (D10). */
const val CELL_FIX_MAX_M = 5_000.0

/** A learned serving cell this much further than its range from a Wi-Fi fix vetoes it (D10). */
const val CELL_VETO_SLACK_M = 2_000.0

// --- D11, D12, D14-D16 ---

/** The teaching fix's budget, a local day (D11). */
const val TEACH_PER_DAY = 6

/** A zone link needs this many inside occasions on [LINK_DAYS_MIN] local days (D12). */
const val LINK_OCCASIONS_MIN = 3
const val LINK_DAYS_MIN = 2

/** A network heard this far outside a zone's edge revokes its link (D12). */
const val LINK_OUTSIDE_SLACK_M = 200.0

/** Without a GPS try of this design for this long, the next heartbeat runs GPS too (D14, M3). */
const val REFRESH_MS = 24 * 60 * 60_000L

/** A GPS fix this far from a learned fix of >= 3 networks taken within [CONTRADICTION_WINDOW_MS] contradicts it (D15). */
const val LEARNED_CONTRADICTION_M = 500.0
const val CONTRADICTION_WINDOW_MS = 10 * 60_000L

/** The counts' window (D15, D16). */
const val COUNT_DAYS = 30

/** `locate` waits this long instead of a minute when a learned fix of >= 3 networks within 60 m is in hand (L5). */
const val LOCATE_SHORT_MS = 20_000L
const val LOCATE_SHORT_ACCURACY_M = 60.0

// --- D4: which APs are usable ---

/** The moving-SSID list as data (`assets/places/moving_ssids.json`): every section's lists together. */
@Serializable
data class MovingSection(
    val words: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val exact: List<String> = emptyList(),
    val contains: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("first_words") val firstWords: List<String> = emptyList(),
)

class MovingSsids(sections: Collection<MovingSection>) {
    private val words = sections.flatMap { it.words }.map { it.lowercase() }.toHashSet()
    private val prefixes = sections.flatMap { it.prefixes }.map { it.lowercase() }
    private val suffixes = sections.flatMap { it.suffixes }.map { it.lowercase() }
    private val exact = sections.flatMap { it.exact }.map { it.lowercase() }.toHashSet()
    private val contains = sections.flatMap { it.contains }.map { it.lowercase() }
    private val firstWords = sections.flatMap { it.firstWords }.map { it.lowercase() }.toHashSet()

    /** A phone, a car, a coach, a train, a dashcam or an aircraft by its SSID - or a car's default SSID, the BSSID's
     * last three octets ([bssid] normalised). */
    fun moving(ssid: String, bssid: String): Boolean {
        val lc = ssid.lowercase()
        val split = lc.split(NOT_LETTER).filter { it.isNotEmpty() }
        return split.any { it in words } ||
            prefixes.any { lc.startsWith(it) } ||
            suffixes.any { lc.endsWith(it) } ||
            lc in exact ||
            contains.any { lc.contains(it) } ||
            split.firstOrNull() in firstWords ||
            lc == bssid.takeLast(8).replace(":", "")
    }

    companion object {
        private val NOT_LETTER = Regex("[^a-z]")
        private val json = Json { ignoreUnknownKeys = true }

        /** The asset: `{"sections": {"<name>": {words, prefixes, suffixes, exact, contains, first_words}}}`. */
        fun parse(text: String): MovingSsids = MovingSsids(json.decodeFromString<MovingFile>(text).sections.values)
    }
}

@Serializable
private data class MovingFile(val sections: Map<String, MovingSection>)

/** A BSSID as `xx:xx:xx:xx:xx:xx` lowercase, or `null` when it doesn't parse. */
fun normalBssid(raw: String?): String? {
    val parts = raw?.trim()?.lowercase()?.split(':') ?: return null
    if (parts.size != 6 || parts.any { it.length != 2 || it.toIntOrNull(16) == null }) return null
    return parts.joinToString(":")
}

/** `WifiInfo`'s BSSID when connected: `null` and `02:00:00:00:00:00` (no location access) mean not (microG's guard). */
fun connectedBssid(raw: String?): String? = normalBssid(raw)?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }

/**
 * D4: a parseable unicast BSSID with the locally administered bit clear (hotspots, Wi-Fi Direct, most virtual BSSIDs),
 * an SSID without `_nomap` or `_optout` (any case), and not a moving SSID. A hidden SSID is kept.
 */
fun usableAp(bssid: String?, ssid: String?, moving: MovingSsids): Boolean {
    val b = usableBssid(bssid) ?: return false
    val s = ssid.orEmpty()
    val lc = s.lowercase()
    if ("_nomap" in lc || "_optout" in lc) return false
    return s.isEmpty() || !moving.moving(s, b)
}

/** D4's BSSID half: normalised, unicast, the locally administered bit clear, not all zero - or `null`. */
fun usableBssid(bssid: String?): String? {
    val b = normalBssid(bssid) ?: return null
    val first = b.substring(0, 2).toInt(16)
    return b.takeUnless { first and 0x03 != 0 || it == "00:00:00:00:00:00" }
}

// --- D5: keys ---

/** The rows' keys (D5): HMAC-SHA256 under the install's 32-byte key, cut short. No BSSID, SSID or cell id is kept. */
class PlaceKeys(secret: ByteArray) {
    private val spec = SecretKeySpec(secret.copyOf(), "HmacSHA256")

    private fun hmac(text: String, bytes: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(spec)
        return mac.doFinal(text.toByteArray(Charsets.UTF_8)).copyOf(bytes).toHex()
    }

    fun ap(bssid: String): String = hmac("ap|$bssid", KEY_BYTES)

    /** One router's bands and links: the MLO address when set, else the BSSID's first five octets. */
    fun device(bssid: String, mld: String?): String = hmac("dev|" + (normalBssid(mld) ?: bssid.substring(0, 14)), SHORT_BYTES)

    /** A mesh's nodes and a router's bands: the SSID, or the device for a hidden one. */
    fun network(ssid: String?, device: String): String = if (ssid.isNullOrEmpty()) device else hmac("net|$ssid", SHORT_BYTES)

    fun cell(id: String): String = hmac("cell|$id", KEY_BYTES)

    /** A policy zone as a link sees it: id, centre and radius - a change drops its links (D12). */
    fun zone(z: PolicyZone): String = hmac("zone|${z.id}|${z.latitude}|${z.longitude}|${z.radiusM}", KEY_BYTES)

    companion object {
        const val KEY_BYTES = 12
        const val SHORT_BYTES = 8
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun String.hexBytes(bytes: Int): ByteArray = ByteArray(bytes) { i -> if (2 * i + 2 <= length) substring(2 * i, 2 * i + 2).toInt(16).toByte() else 0 }

// --- What a scan heard ---

/** A scan result as the platform gave it; [elapsedMs] since boot. Never stored. */
data class ScanAp(val bssid: String, val ssid: String?, val mld: String?, val frequencyMhz: Int, val rssi: Int, val elapsedMs: Long)

/** The connected network (`WifiInfo` with the active network's `NOT_METERED`); never stored. */
data class Connected(val bssid: String?, val ssid: String?, val rssi: Int, val frequencyMhz: Int, val unmetered: Boolean)

/** A usable result by its keys; [suspect]: its level repeats the previous scan's (D9's stale guard). [bssid] is held in
 * memory for design 125 step 2's ask alone - never stored or logged. */
data class Heard(
    val key: String,
    val device: String,
    val network: String,
    val band: Band,
    val rssi: Int,
    val elapsedMs: Long,
    val suspect: Boolean = false,
    val connected: Boolean = false,
    val kind: RowKind = RowKind.WIFI,
    val bssid: String = "",
)

/** A result's level and time as the previous scan heard it - memory only, by key. */
data class Reading(val elapsedMs: Long, val rssi: Int)

/** [heard] with the scan's stale verdict and the readings the next scan compares with. */
data class Hearing(val heard: List<Heard>, val stale: Boolean, val readings: Map<String, Reading>)

/**
 * The scan's usable results by key (D4, D9): the connected AP only when unmetered (a metered one's results go - a
 * hotspot), and added when the results lack it (at [nowElapsedMs]); a result whose level equals the previous reading
 * of a newer result is suspect (never taught, a quarter weight); the scan is stale only when at least
 * [STALE_REPEATS_MIN] repeat and they are [STALE_SHARE] of the results the previous scan heard too.
 */
fun hear(results: List<ScanAp>, connected: Connected?, keys: PlaceKeys, moving: MovingSsids, previous: Map<String, Reading>, nowElapsedMs: Long): Hearing {
    val connectedB = connectedBssid(connected?.bssid)
    val all = results.toMutableList()
    if (connected != null && connectedB != null) {
        if (!connected.unmetered) all.removeAll { normalBssid(it.bssid) == connectedB }
        else if (all.none { normalBssid(it.bssid) == connectedB }) {
            all += ScanAp(connectedB, connected.ssid, null, connected.frequencyMhz, connected.rssi, nowElapsedMs)
        }
    }
    val heard = all.filter { usableAp(it.bssid, it.ssid, moving) }.distinctBy { normalBssid(it.bssid) }.map { r ->
        val b = normalBssid(r.bssid)!!
        val key = keys.ap(b)
        val device = keys.device(b, r.mld)
        val before = previous[key]
        Heard(
            key = key,
            device = device,
            network = keys.network(r.ssid, device),
            band = bandOf(r.frequencyMhz),
            rssi = r.rssi,
            elapsedMs = r.elapsedMs,
            suspect = before != null && r.elapsedMs > before.elapsedMs && r.rssi == before.rssi,
            connected = b == connectedB && connected?.unmetered == true,
            bssid = b,
        )
    }
    val again = heard.count { h -> previous[h.key]?.let { h.elapsedMs > it.elapsedMs } == true }
    val repeats = heard.count { it.suspect }
    val stale = repeats >= STALE_REPEATS_MIN && repeats >= STALE_SHARE * again
    return Hearing(heard, stale, heard.associate { it.key to Reading(it.elapsedMs, it.rssi) })
}

// --- D1, D2: the teacher and the pairing ---

/** A fix as a teacher sees it; [elapsedMs] since boot. */
data class TeachFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val elapsedMs: Long,
    val provider: String?,
    val satellites: Int?,
    val mock: Boolean,
)

/**
 * D1: only our own and passive `gps` fixes teach - `satellites` >= 5, not mock in a release build ([release]),
 * accuracy <= 25 m, speed unknown or <= 3 m/s. `fused` only after the Jelly Star's check (§8 step 2), so never yet;
 * never `network`, a balanced fix or our own `vibb_*` fixes.
 */
fun teaches(fix: TeachFix, release: Boolean): Boolean = teachRefusal(fix, release) == null

/** Why [fix] doesn't teach (D1, code QA M4's counts) - `provider`, `mock`, `coarse`, `nosat`, `fast` - or `null`. */
fun teachRefusal(fix: TeachFix, release: Boolean): String? {
    val acc = fix.accuracyM?.takeIf { it.isFinite() && it >= 0f }
    val speed = fix.speedMps?.takeIf { it.isFinite() }
    return when {
        fix.provider != "gps" -> "provider"
        fix.mock && release -> "mock"
        acc == null || acc > TEACH_ACCURACY_MAX_M -> "coarse"
        (fix.satellites ?: 0) < TEACH_SATELLITES_MIN -> "nosat"
        speed != null && speed > TEACH_SPEED_MAX_MPS -> "fast"
        else -> null
    }
}

/** A fix this fast ends a teaching wait early (code QA L1): it won't stop in the next seconds. */
fun tooFastToTeach(fix: TeachFix): Boolean = fix.speedMps?.let { it.isFinite() && it > TEACH_SPEED_MAX_MPS } == true

/**
 * Code QA M1: the results a teach may use beyond its sightings - the LOO count, the zone links' strikes, D15's
 * contradiction: heard after the last motion fire ([movedAtElapsedMs]) and within [windowMs] of the [best] teacher.
 */
fun pairedWith(heard: List<Heard>, best: TeachFix, windowMs: Long, movedAtElapsedMs: Long): List<Heard> =
    heard.filter { it.elapsedMs > movedAtElapsedMs && abs(it.elapsedMs - best.elapsedMs) <= windowMs }

/** Where a result was heard: the teacher's position and the pairing's error ([accEff], D2). */
data class Sighting(val heard: Heard, val lat: Double, val lng: Double, val accEff: Double)

/**
 * D2: each result paired with the teacher nearest in elapsed time within [windowMs] ([PAIR_STREAM_MS] for a GPS
 * stream, [PAIR_SINGLE_MS] otherwise), `accEff = acc + speed x |dt|` (speed unknown: 1.5 m/s) and at most
 * [ACC_EFF_MAX_M]; suspect results never teach, a stale scan nothing; the [TEACH_APS_MAX] strongest Wi-Fi results.
 * The serving cell pairs within [PAIR_SINGLE_MS] whatever the teacher, with no cap: its range is kilometres (D10).
 * [teachers] are the fixes that passed [teaches].
 */
fun pairings(hearing: Hearing, teachers: List<TeachFix>, windowMs: Long): List<Sighting> {
    if (hearing.stale || teachers.isEmpty()) return emptyList()
    val sightings = hearing.heard.filter { !it.suspect }.mapNotNull { h ->
        val cell = h.kind == RowKind.CELL
        val t = teachers.minBy { abs(it.elapsedMs - h.elapsedMs) }
        val dt = abs(t.elapsedMs - h.elapsedMs)
        if (dt > (if (cell) maxOf(windowMs, PAIR_SINGLE_MS) else windowMs)) return@mapNotNull null
        val speed = t.speedMps?.takeIf { it.isFinite() } ?: UNKNOWN_SPEED_MPS
        val accEff = (t.accuracyM ?: return@mapNotNull null) + speed * dt / 1000.0
        if (accEff > ACC_EFF_MAX_M && !cell) null else Sighting(h, t.lat, t.lng, accEff)
    }
    val (cells, wifi) = sightings.partition { it.heard.kind == RowKind.CELL }
    return wifi.sortedByDescending { it.heard.rssi }.take(TEACH_APS_MAX) + cells
}

// --- D5-D8: the rows ---

/** A row's kind (D18): an AP, a cell, a zone link, an AP's position from Apple (design 125 A9). */
enum class RowKind(val code: Int) {
    WIFI(1),
    CELL(2),
    LINK(3),
    APPLE(4),
    ;

    companion object {
        fun of(code: Int): RowKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One row of the table (D5, D18). Times in minutes since the epoch, [lastDay] a local epoch day. For a [RowKind.LINK]
 * [key] is the zone's key, [network] the linked network, [occasions]/[days] the inside occasions and days, [moves]
 * the outside strikes. [lastTeachMin] is the last counted occasion.
 */
data class PlaceRow(
    val kind: RowKind,
    val key: String,
    val device: String = "",
    val network: String = "",
    val band: Band = Band.NONE,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val weight: Float = 0f,
    val rangeM: Float = 0f,
    val occasions: Int = 0,
    val days: Int = 0,
    val lastDay: Int = 0,
    val lastTeachMin: Int = 0,
    val lastSeenMin: Int = 0,
    val moves: Int = 0,
    val lastMoveMin: Int = 0,
    val blockedUntilMin: Int = 0,
) {
    fun blocked(nowMin: Int): Boolean = nowMin < blockedUntilMin

    /** D9: a trusted row - not blocked, taught on two occasions. */
    fun trusted(nowMin: Int): Boolean = !blocked(nowMin) && occasions >= 2

    val id: String get() = when (kind) {
        RowKind.LINK -> "$key/$network"
        RowKind.APPLE -> "a/$key"
        else -> key
    }
}

/** D6: a sighting's weight - the signal and the pairing's error. */
fun sightingWeight(rssi: Int, accEff: Double, kind: RowKind = RowKind.WIFI): Float {
    val q = if (kind == RowKind.CELL) 1.0 else ((rssi + 100) / 40.0).coerceIn(0.1, 1.0)
    val a = (10.0 / accEff.coerceAtLeast(0.1)).coerceIn(0.25, 1.0)
    return (q * a).toFloat()
}

private fun clampRange(kind: RowKind, band: Band, rangeM: Double): Float {
    val floored = maxOf(rangeM, band.floorM.toDouble())
    return (if (kind == RowKind.CELL) floored.coerceAtMost(CELL_RANGE_MAX_M.toDouble()) else floored).toFloat()
}

private fun startRow(s: Sighting, nowMin: Int, today: Int, moves: Int, lastMoveMin: Int, blockedUntilMin: Int): PlaceRow = PlaceRow(
    kind = s.heard.kind,
    key = s.heard.key,
    device = s.heard.device,
    network = s.heard.network,
    band = s.heard.band,
    lat = s.lat,
    lng = s.lng,
    weight = sightingWeight(s.heard.rssi, s.accEff, s.heard.kind),
    rangeM = clampRange(s.heard.kind, s.heard.band, s.accEff),
    occasions = 1,
    days = 1,
    lastDay = today,
    lastTeachMin = nowMin,
    lastSeenMin = nowMin,
    moves = moves,
    lastMoveMin = lastMoveMin,
    blockedUntilMin = blockedUntilMin,
)

/** What one row did at a teach. */
enum class RowChange { NEW, TAUGHT, MOVED, BLOCKED, SKIPPED }

/**
 * D6, D7: one sighting into its row. A new row starts there; a blocked row is a tombstone (skipped); a block that ran
 * out restarts the row. A sighting further than the band's reach beyond its error is a move: the row restarts there
 * with one more move, and a second move within [BLOCK_MIN] blocks it as long. Otherwise the weighted centroid moves
 * towards it (the weight capped at [WEIGHT_CAP]), the range grows to cover it (floored by band, never capped for
 * Wi-Fi), an occasion counts when [OCCASION_GAP_MIN] passed, a day when the local day changed.
 */
fun teachRow(row: PlaceRow?, s: Sighting, nowMin: Int, today: Int): Pair<PlaceRow, RowChange> {
    if (row == null) return startRow(s, nowMin, today, 0, 0, 0) to RowChange.NEW
    if (row.blocked(nowMin)) return row to RowChange.SKIPPED
    if (row.blockedUntilMin != 0) return startRow(s, nowMin, today, 0, 0, 0) to RowChange.NEW
    val d = distanceM(row.lat, row.lng, s.lat, s.lng)
    if (d - s.accEff > row.band.reachM) {
        val recent = row.moves > 0 && nowMin - row.lastMoveMin in 0 until BLOCK_MIN
        val moves = if (recent) row.moves + 1 else 1
        if (moves >= 2) {
            return row.copy(moves = moves, lastMoveMin = nowMin, blockedUntilMin = nowMin + BLOCK_MIN, lastSeenMin = nowMin) to RowChange.BLOCKED
        }
        return startRow(s, nowMin, today, moves, nowMin, 0) to RowChange.MOVED
    }
    val w = sightingWeight(s.heard.rssi, s.accEff, row.kind)
    val total = row.weight + w
    val lat = (row.lat * row.weight + s.lat * w) / total
    val lng = (row.lng * row.weight + s.lng * w) / total
    val reach = distanceM(lat, lng, s.lat, s.lng) + s.accEff
    val occasion = nowMin - row.lastTeachMin !in 0 until OCCASION_GAP_MIN
    return row.copy(
        lat = lat,
        lng = lng,
        weight = minOf(total, WEIGHT_CAP),
        rangeM = clampRange(row.kind, row.band, maxOf(row.rangeM.toDouble(), reach)),
        occasions = if (occasion) row.occasions + 1 else row.occasions,
        days = if (today != row.lastDay) (row.days + 1).coerceAtMost(DAYS_CAP) else row.days,
        lastDay = today,
        lastTeachMin = if (occasion) nowMin else row.lastTeachMin,
        lastSeenMin = nowMin,
    ) to RowChange.TAUGHT
}

/** The table after a teach, and what changed - counts only, for the log. */
data class TeachOutcome(val rows: List<PlaceRow>, val taught: Int, val new: Int, val moved: Int)

/** Every sighting into its row ([teachRow]), then [prune]. */
fun teach(rows: List<PlaceRow>, sightings: List<Sighting>, nowMin: Int, today: Int): TeachOutcome {
    if (sightings.isEmpty()) return TeachOutcome(rows, 0, 0, 0)
    val byId = LinkedHashMap<String, PlaceRow>()
    rows.forEach { byId[it.id] = it }
    var new = 0
    var moved = 0
    var taught = 0
    sightings.distinctBy { it.heard.key }.forEach { s ->
        val (row, change) = teachRow(byId[s.heard.key], s, nowMin, today)
        byId[row.id] = row
        when (change) {
            RowChange.NEW -> new++
            RowChange.MOVED, RowChange.BLOCKED -> moved++
            RowChange.TAUGHT -> taught++
            RowChange.SKIPPED -> {}
        }
    }
    return TeachOutcome(prune(byId.values.toList(), nowMin), taught + new + moved, new, moved)
}

/** D8: rows unseen for [ROW_UNSEEN_MIN] go (a blocked row stays while blocked); above [ROWS_MAX] the least recently
 * seen go first, tombstones included. Apple rows (A9) on their own: live ones and tombstones while they block, at most
 * [APPLE_ROWS_MAX]. */
fun prune(rows: List<PlaceRow>, nowMin: Int): List<PlaceRow> {
    val (apple, own) = rows.partition { it.kind == RowKind.APPLE }
    fun capped(kept: List<PlaceRow>, max: Int) = if (kept.size <= max) kept else kept.sortedByDescending { it.lastSeenMin }.take(max)
    return capped(own.filter { it.blocked(nowMin) || nowMin - it.lastSeenMin < ROW_UNSEEN_MIN }, ROWS_MAX) +
        capped(apple.filter { it.blocked(nowMin) || it.appleLive(nowMin) }, APPLE_ROWS_MAX)
}

/** The lookup changes nothing but `lastSeen` (D9, D8), at most once a day per row (code QA L4: no write for nothing);
 * the same list when nothing changed. */
fun touchSeen(rows: List<PlaceRow>, keys: Set<String>, nowMin: Int): List<PlaceRow> {
    fun stale(r: PlaceRow) = r.kind != RowKind.LINK && r.key in keys && nowMin - r.lastSeenMin !in 0 until SEEN_STEP_MIN
    return if (rows.none(::stale)) rows else rows.map { if (stale(it)) it.copy(lastSeenMin = nowMin) else it }
}

/** `lastSeen` moves at most this often (a day): the 180-day ageing needs no finer step. */
const val SEEN_STEP_MIN = 24 * 60

// --- D9: the lookup ---

/** The results a lookup may use and whether they make a fresh fix. */
data class ScanView(val heard: List<Heard>, val fresh: Boolean)

/**
 * D9's scan rule: results newer than the last motion fire ([movedAtElapsedMs]) and at most [SCAN_FRESH_MS] old - at
 * least [SCAN_RESULTS_MIN] of them - make a fresh lookup; else, or when the scan is stale, those at most
 * [SCAN_FALLBACK_MS] old make the fallback (never fresh); fewer is no lookup.
 */
fun scanView(hearing: Hearing, nowElapsedMs: Long, movedAtElapsedMs: Long): ScanView? {
    val wifi = hearing.heard.filter { it.kind == RowKind.WIFI && it.elapsedMs > movedAtElapsedMs }
    val fresh = wifi.filter { nowElapsedMs - it.elapsedMs in 0..SCAN_FRESH_MS }
    if (!hearing.stale && fresh.size >= SCAN_RESULTS_MIN) return ScanView(fresh, true)
    val fallback = wifi.filter { nowElapsedMs - it.elapsedMs in 0..SCAN_FALLBACK_MS }
    return if (fallback.size >= SCAN_RESULTS_MIN) ScanView(fallback, false) else null
}

/** A learned fix: [elapsedMs] the oldest used result's, [networks] the distinct networks in it, [keys] the rows used,
 * [source] whose positions (design 125 A9: any Apple position makes it `vibb_apple`). */
data class LearnedFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val networks: Int,
    val elapsedMs: Long,
    val fresh: Boolean,
    val keys: Set<String>,
    val provider: String = PROVIDER_WIFI,
    val source: PlaceSource = PlaceSource.OWN,
)

const val PROVIDER_WIFI = "vibb_wifi"
const val PROVIDER_CELL = "vibb_cell"

/** A device heard now: its trusted rows' position and range, its best signal. */
private data class Device(
    val device: String,
    val network: String,
    val lat: Double,
    val lng: Double,
    val rangeM: Double,
    val rssi: Int,
    val occasions: Int,
    val band: Band,
    val weight: Double,
    val oldestMs: Long,
    val keys: Set<String>,
    val apple: Boolean,
)

/**
 * D9: the learned fix from [view] against [rows]. Trusted rows group into devices (weight-weighted), devices into
 * networks. Complete link: the device with the most others within `rangeA + rangeB` seeds the cluster (tie: the
 * stronger signal), and a device joins only within that of every member; the cluster must hold >= 2 networks and more
 * than half of the trusted networks heard. Position and accuracy by [learnedAccuracy]. `null` = no fix. Design 125 A9:
 * a result's row is a trusted own row or a live Apple row by [chooseRow].
 */
fun learnedFix(view: ScanView, rows: List<PlaceRow>, nowMin: Int): LearnedFix? {
    val table = rows.asSequence().filter { it.kind == RowKind.WIFI }.associateBy { it.key }
    val apple = rows.asSequence().filter { it.kind == RowKind.APPLE }.associateBy { it.key }
    val chosen = view.heard.filter { it.kind == RowKind.WIFI }.mapNotNull { h -> chooseRow(h, table[h.key], apple[h.key], nowMin)?.let { h to it } }
    val trustedHeard = chosen.map { (h, row) -> h to row.first }
    val fromApple = chosen.filter { it.second.second }.map { it.first.key }.toSet()
    val connectedNetworks = view.heard.filter { it.connected }.map { it.network }.toSet()
    val devices = trustedHeard.groupBy { it.first.device }.map { (device, pairs) ->
        val w = pairs.sumOf { it.second.weight.toDouble() }.coerceAtLeast(1e-6)
        val best = pairs.maxBy { it.first.rssi }
        val occasions = pairs.maxOf { it.second.occasions }
        val q = ((best.first.rssi + 100) / 40.0).coerceIn(0.1, 1.0)
        val suspect = if (pairs.all { it.first.suspect }) 0.25 else 1.0
        val network = best.first.network
        Device(
            device = device,
            network = network,
            lat = pairs.sumOf { it.second.lat * it.second.weight } / w,
            lng = pairs.sumOf { it.second.lng * it.second.weight } / w,
            rangeM = pairs.maxOf { it.second.rangeM.toDouble() },
            rssi = best.first.rssi,
            occasions = occasions,
            band = best.second.band,
            weight = q * minOf(occasions, 5) / 5.0 * suspect * (if (network in connectedNetworks) 2.0 else 1.0),
            oldestMs = pairs.minOf { it.first.elapsedMs },
            keys = pairs.map { it.first.key }.toSet(),
            apple = pairs.any { it.first.key in fromApple },
        )
    }
    if (devices.isEmpty()) return null
    fun near(a: Device, b: Device) = distanceM(a.lat, a.lng, b.lat, b.lng) <= a.rangeM + b.rangeM
    val seed = devices.maxWith(compareBy<Device>({ d -> devices.count { it !== d && near(d, it) } }, { it.rssi }))
    val cluster = mutableListOf(seed)
    devices.filter { it !== seed }.sortedByDescending { it.rssi }.forEach { d -> if (cluster.all { near(d, it) }) cluster += d }
    val networksHeard = devices.map { it.network }.toSet().size
    val networks = cluster.map { it.network }.toSet().size
    if (networks < 2 || networks * 2 <= networksHeard) return null
    // The network is the witness: a mesh's nodes share one network's weight.
    val perNetwork = cluster.groupingBy { it.network }.eachCount()
    val weighted = cluster.map { it to it.weight / perNetwork.getValue(it.network) }
    val w = weighted.sumOf { it.second }.coerceAtLeast(1e-9)
    val lat = weighted.sumOf { (d, dw) -> d.lat * dw } / w
    val lng = weighted.sumOf { (d, dw) -> d.lng * dw } / w
    val spread = sqrt(weighted.sumOf { (d, dw) -> dw * distanceM(lat, lng, d.lat, d.lng).let { it * it } } / w)
    val rangeMed = weightedMedian(weighted.map { (d, dw) -> d.rangeM to dw })
    val occMed = cluster.map { it.occasions }.sorted().let { it[it.size / 2] }
    val strongest = cluster.maxBy { it.rssi }
    // Usable networks with no trusted row at all, heard stronger than every cluster member (L-M1's inflation).
    val trustedNetworks = devices.map { it.network }.toSet()
    val strongerUnknown = view.heard.filter { it.network !in trustedNetworks && it.rssi > strongest.rssi }.map { it.network }.toSet().size
    val acc = learnedAccuracy(spread, rangeMed, networks, occMed, strongest.rssi, strongest.band, strongerUnknown) ?: return null
    val source = when {
        cluster.none { it.apple } -> PlaceSource.OWN
        cluster.all { it.apple } -> PlaceSource.APPLE
        else -> PlaceSource.MIXED
    }
    val provider = if (source == PlaceSource.OWN) PROVIDER_WIFI else PROVIDER_APPLE
    return LearnedFix(lat, lng, acc, networks, cluster.minOf { it.oldestMs }, view.fresh, cluster.flatMap { it.keys }.toSet(), provider, source)
}

private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
    val sorted = values.sortedBy { it.first }
    val half = sorted.sumOf { it.second } / 2
    var run = 0.0
    for ((v, w) in sorted) {
        run += w
        if (run >= half) return v
    }
    return sorted.last().first
}

/**
 * D9's 68 % estimate (M1), not a bound: `sqrt(spread^2 + (RANGE_SHARE x rangeMed)^2)`, x 1.5 with only two networks, x 1.3
 * when the median occasions are under 4; at least the weak-signal floor of the strongest member (>= -60 dBm none,
 * -60..-75 its band floor, -75..-85 1.5 x that, weaker 150 m); x 2 when two or more unknown networks are heard
 * stronger than every member; at least [LEARNED_ACCURACY_FLOOR_M]; over [LEARNED_ACCURACY_MAX_M] `null`.
 */
fun learnedAccuracy(spread: Double, rangeMed: Double, networks: Int, medianOccasions: Int, strongestRssi: Int, strongestBand: Band, strongerUnknown: Int): Double? {
    var acc = sqrt(spread * spread + (RANGE_SHARE * rangeMed) * (RANGE_SHARE * rangeMed))
    if (networks == 2) acc *= 1.5
    if (medianOccasions < 4) acc *= 1.3
    val floor = strongestBand.floorM.toDouble()
    acc = maxOf(
        acc,
        when {
            strongestRssi >= -60 -> 0.0
            strongestRssi >= -75 -> floor
            strongestRssi >= -85 -> 1.5 * floor
            else -> 150.0
        },
    )
    if (strongerUnknown >= 2) acc *= 2
    acc = maxOf(acc, LEARNED_ACCURACY_FLOOR_M)
    return acc.takeIf { it <= LEARNED_ACCURACY_MAX_M }
}

/** Which fresh-fix attempt (D14): the heartbeat's and a crossing's first fix and the low-battery fix; a crossing's
 * confirm; the parent's `locate`. */
enum class AttemptPath { HEARTBEAT, CONFIRM, LOCATE }

/** How an attempt runs (D14). */
enum class AttemptPlan {
    /** Scan and GPS together, the better kept (the learned fix only when it stands alone). */
    TOGETHER,

    /** The learned fix when it stands alone, else the zone link's renewal, else GPS. */
    LEARNED_FIRST,

    /** GPS, then the learned fix. */
    GPS_FIRST,

    /** GPS for 60 s, 20 s with a good learned fix in hand; the better kept. */
    LOCATE,
}

/** D14's table (code QA L7): the heartbeat's path after a move (`MOVED`, no sensor) or with the daily refresh due runs
 * scan and GPS together, otherwise the learned fix first; a crossing's confirm GPS first; `locate` its own. */
fun attemptPlan(path: AttemptPath, motion: com.kidslauncher.mdm.zones.Motion, refreshDue: Boolean): AttemptPlan = when (path) {
    AttemptPath.CONFIRM -> AttemptPlan.GPS_FIRST
    AttemptPath.LOCATE -> AttemptPlan.LOCATE
    AttemptPath.HEARTBEAT -> if (refreshDue || motion == com.kidslauncher.mdm.zones.Motion.MOVED || motion == com.kidslauncher.mdm.zones.Motion.NO_SENSOR) {
        AttemptPlan.TOGETHER
    } else {
        AttemptPlan.LEARNED_FIRST
    }
}

/** H2: after a motion fire ([movedSinceFresh]) a learned fix from fewer than three networks never stands alone - GPS
 * runs in the same attempt. One network never gives a fix at all ([learnedFix]). */
fun standsAlone(fix: LearnedFix, movedSinceFresh: Boolean): Boolean = fix.networks >= 3 || !movedSinceFresh

// --- D10: cells ---

/** D10: the serving cell's own fix - trusted on three occasions, accuracy its range, none over [CELL_FIX_MAX_M]. */
fun cellFix(cell: Heard?, rows: List<PlaceRow>, nowMin: Int): LearnedFix? {
    val row = rows.firstOrNull { it.kind == RowKind.CELL && it.key == cell?.key } ?: return null
    if (row.blocked(nowMin) || row.occasions < 3 || row.rangeM > CELL_FIX_MAX_M) return null
    return LearnedFix(row.lat, row.lng, row.rangeM.toDouble(), 1, cell!!.elapsedMs, true, setOf(row.key), PROVIDER_CELL)
}

/** D10's veto: a learned serving cell (two occasions) whose estimate lies more than its range + 2 km from [fix]. */
fun cellVetoes(cell: Heard?, rows: List<PlaceRow>, fix: LearnedFix, nowMin: Int): Boolean {
    val row = rows.firstOrNull { it.kind == RowKind.CELL && it.key == cell?.key }?.takeIf { it.trusted(nowMin) } ?: return false
    return distanceM(row.lat, row.lng, fix.lat, fix.lng) > row.rangeM + CELL_VETO_SLACK_M
}

// --- D12: the zone link ---

/** A zone as the links see it: its policy row and its key. */
data class LinkZone(val zone: PolicyZone, val key: String)

/** A link row counts once it has [LINK_OCCASIONS_MIN] inside occasions on [LINK_DAYS_MIN] days and isn't blocked. */
fun PlaceRow.linked(nowMin: Int): Boolean = kind == RowKind.LINK && !blocked(nowMin) && occasions >= LINK_OCCASIONS_MIN && days >= LINK_DAYS_MIN

/**
 * D12 at a teaching fix ([lat], [lng], [accuracyM]): a link row whose zone is gone or changed goes. Surely inside a zone
 * (`d + a <= r`) the connected unmetered network ([connected], `null` = none) gains an inside occasion; surely outside by
 * [LINK_OUTSIDE_SLACK_M] (`d - a >= r + 200 m`) every heard network ([heard]) with a row of that zone gets a strike:
 * the first revokes it (counts reset), a second within [BLOCK_MIN] blocks it as long; after a block it starts over.
 */
fun teachLinks(rows: List<PlaceRow>, zones: List<LinkZone>, lat: Double, lng: Double, accuracyM: Double, connected: String?, heard: Set<String>, nowMin: Int, today: Int): List<PlaceRow> {
    val zoneKeys = zones.map { it.key }.toSet()
    val out = rows.filter { it.kind != RowKind.LINK || it.key in zoneKeys }.associateByTo(LinkedHashMap()) { it.id }
    for (z in zones) {
        val d = distanceM(lat, lng, z.zone.latitude, z.zone.longitude)
        val r = z.zone.radiusM.toDouble()
        if (d + accuracyM <= r && connected != null) {
            val id = "${z.key}/$connected"
            val row = out[id]?.let { if (it.blockedUntilMin != 0 && !it.blocked(nowMin)) null else it }
            if (row?.blocked(nowMin) == true) continue
            val base = row ?: PlaceRow(RowKind.LINK, z.key, network = connected, lastTeachMin = nowMin - OCCASION_GAP_MIN)
            val occasion = nowMin - base.lastTeachMin !in 0 until OCCASION_GAP_MIN
            out[id] = base.copy(
                occasions = base.occasions + if (occasion) 1 else 0,
                days = if (base.days == 0 || today != base.lastDay) base.days + 1 else base.days,
                lastDay = today,
                lastTeachMin = if (occasion) nowMin else base.lastTeachMin,
                lastSeenMin = nowMin,
            )
        } else if (d - accuracyM >= r + LINK_OUTSIDE_SLACK_M) {
            for (network in heard) {
                val id = "${z.key}/$network"
                val row = out[id]?.takeIf { !it.blocked(nowMin) } ?: continue
                val second = row.moves > 0 && nowMin - row.lastMoveMin in 0 until BLOCK_MIN
                out[id] = if (second) {
                    row.copy(occasions = 0, days = 0, moves = 2, lastMoveMin = nowMin, blockedUntilMin = nowMin + BLOCK_MIN, lastSeenMin = nowMin)
                } else {
                    row.copy(occasions = 0, days = 0, moves = 1, lastMoveMin = nowMin, blockedUntilMin = 0, lastSeenMin = nowMin)
                }
            }
        }
    }
    return out.values.toList()
}

/** A fix as the renewal reads it (the vouched one). */
data class Vouched(val lat: Double, val lng: Double, val accuracyM: Double)

/**
 * D12 (Q5 = b): the 4-h cap try re-sends the vouched fix with `still: true` instead of a new position only when the
 * phone is still since it ([still]: `motion()` says `STILL`), the daily refresh isn't due, [vouched] lies surely inside
 * a zone and the connected unmetered network ([connected]) is linked to that zone. It never establishes a position.
 */
fun linkRenews(rows: List<PlaceRow>, zones: List<LinkZone>, vouched: Vouched?, connected: String?, still: Boolean, refreshDue: Boolean, nowMin: Int): Boolean {
    if (!still || refreshDue || vouched == null || connected == null) return false
    return zones.any { z ->
        distanceM(vouched.lat, vouched.lng, z.zone.latitude, z.zone.longitude) + vouched.accuracyM <= z.zone.radiusM &&
            rows.any { it.key == z.key && it.network == connected && it.linked(nowMin) }
    }
}

// --- D14: the daily refresh; D11: the budget ---

/** D14 (M3): 24 h without a GPS try of this design - the clock set back counts as due. */
fun refreshDue(lastGpsTryMs: Long, nowMs: Long): Boolean = nowMs - lastGpsTryMs !in 0 until REFRESH_MS

/** The teaching fixes used on [day] (a local date). */
@Serializable
data class TeachBudget(val day: String, val used: Int)

fun teachesLeft(budget: TeachBudget?, today: String): Int = if (budget?.day == today) (TEACH_PER_DAY - budget.used).coerceAtLeast(0) else TEACH_PER_DAY

fun budgetAfterTeach(budget: TeachBudget?, today: String): TeachBudget =
    if (budget?.day == today) budget.copy(used = budget.used + 1) else TeachBudget(today, 1)

// --- D15, D16: the counts ---

/** The leave-one-out classes (D16). */
enum class LooClass { THREE, TWO, ZONE }

/** One local day's counts (D15, D16): hits of n per class, and the learned contradictions. */
@Serializable
data class DayCounts(
    val day: Int,
    val hit3: Int = 0,
    val n3: Int = 0,
    val hit2: Int = 0,
    val n2: Int = 0,
    val hitZone: Int = 0,
    val nZone: Int = 0,
    val contradictions: Int = 0,
    // Design 125 D16: by the source of the positions, with the summed error in metres.
    val hitOwn: Int = 0,
    val nOwn: Int = 0,
    val errOwn: Long = 0,
    val hitApple: Int = 0,
    val nApple: Int = 0,
    val errApple: Long = 0,
    val hitMixed: Int = 0,
    val nMixed: Int = 0,
    val errMixed: Long = 0,
    /** A9: a still phone's Apple fix outside its vouch. */
    val appleContradictions: Int = 0,
)

/** The counts of the last [COUNT_DAYS] local days with one more observation on [today]. */
fun countsWith(days: List<DayCounts>, today: Int, change: (DayCounts) -> DayCounts): List<DayCounts> {
    val kept = days.filter { today - it.day in 0 until COUNT_DAYS }
    val current = kept.firstOrNull { it.day == today } ?: DayCounts(today)
    return kept.filter { it.day != today } + change(current)
}

fun DayCounts.loo(cls: LooClass, hit: Boolean): DayCounts {
    val h = if (hit) 1 else 0
    return when (cls) {
        LooClass.THREE -> copy(hit3 = hit3 + h, n3 = n3 + 1)
        LooClass.TWO -> copy(hit2 = hit2 + h, n2 = n2 + 1)
        LooClass.ZONE -> copy(hitZone = hitZone + h, nZone = nZone + 1)
    }
}

/** D16 by source (design 125): whether the GPS fix lay inside, and how far off the learned one was. */
fun DayCounts.looSource(source: PlaceSource, hit: Boolean, errorM: Double): DayCounts {
    val h = if (hit) 1 else 0
    val e = errorM.toLong().coerceAtLeast(0)
    return when (source) {
        PlaceSource.OWN -> copy(hitOwn = hitOwn + h, nOwn = nOwn + 1, errOwn = errOwn + e)
        PlaceSource.APPLE -> copy(hitApple = hitApple + h, nApple = nApple + 1, errApple = errApple + e)
        PlaceSource.MIXED -> copy(hitMixed = hitMixed + h, nMixed = nMixed + 1, errMixed = errMixed + e)
    }
}

/** The log line's counts (D16): `loo 3+=41/52 2=9/14 zone=3/3 own=38/47 apple=7/11 mixed=5/8 err own=12 apple=40
 * mixed=20 contradictions=0 apple_contradictions=0` - the mean error per source in metres. */
fun countsLine(days: List<DayCounts>): String {
    fun mean(err: Long, n: Int) = if (n == 0) "-" else (err / n).toString()
    val nOwn = days.sumOf { it.nOwn }
    val nApple = days.sumOf { it.nApple }
    val nMixed = days.sumOf { it.nMixed }
    return "loo 3+=${days.sumOf { it.hit3 }}/${days.sumOf { it.n3 }} 2=${days.sumOf { it.hit2 }}/${days.sumOf { it.n2 }} " +
        "zone=${days.sumOf { it.hitZone }}/${days.sumOf { it.nZone }} " +
        "own=${days.sumOf { it.hitOwn }}/$nOwn apple=${days.sumOf { it.hitApple }}/$nApple mixed=${days.sumOf { it.hitMixed }}/$nMixed " +
        "err own=${mean(days.sumOf { it.errOwn }, nOwn)} apple=${mean(days.sumOf { it.errApple }, nApple)} " +
        "mixed=${mean(days.sumOf { it.errMixed }, nMixed)} contradictions=${days.sumOf { it.contradictions }} " +
        "apple_contradictions=${days.sumOf { it.appleContradictions }}"
}

/** D16: before a GPS fix ([gpsLat], [gpsLng]) teaches, the learned fix the same scan gave against the table as it was:
 * its class and whether the GPS fix lies inside its reported accuracy; `null` when there was none. */
fun leaveOneOut(learned: LearnedFix?, gpsLat: Double, gpsLng: Double): Pair<LooClass, Boolean>? {
    learned ?: return null
    val cls = if (learned.networks >= 3) LooClass.THREE else LooClass.TWO
    return cls to (distanceM(learned.lat, learned.lng, gpsLat, gpsLng) <= learned.accuracyM)
}

/** D15: a GPS fix more than [LEARNED_CONTRADICTION_M] from a learned fix of >= 3 networks taken within
 * [CONTRADICTION_WINDOW_MS] (elapsed). */
fun learnedContradiction(learned: LearnedFix?, gpsLat: Double, gpsLng: Double, gpsElapsedMs: Long): Boolean =
    learned != null && learned.networks >= 3 && abs(gpsElapsedMs - learned.elapsedMs) <= CONTRADICTION_WINDOW_MS &&
        distanceM(learned.lat, learned.lng, gpsLat, gpsLng) > LEARNED_CONTRADICTION_M

// --- D14's keep-the-better ---

/** D14: GPS wins at equal or better accuracy; a missing accuracy is the worst. */
fun gpsWins(learnedM: Double?, gpsM: Float?): Boolean =
    gpsM != null && gpsM.isFinite() && (learnedM == null || gpsM <= learnedM)

// --- D18: the store's format ---

private val MAGIC = "VWP".toByteArray(Charsets.US_ASCII)
const val TABLE_VERSION = 1
private const val ROW_BYTES = 1 + 12 + 8 + 8 + 1 + 4 + 4 + 4 + 4 + 2 + 2 + 4 + 4 + 4 + 1 + 4 + 4

/** D18: magic `VWP`, a version byte, the row count, then fixed-width rows (positions as 1e-7 degree ints, times in
 * minutes). Sealed whole by the caller. */
fun encodeTable(rows: List<PlaceRow>): ByteArray {
    val buf = ByteBuffer.allocate(MAGIC.size + 1 + 4 + rows.size * ROW_BYTES)
    buf.put(MAGIC).put(TABLE_VERSION.toByte()).putInt(rows.size)
    for (r in rows) {
        buf.put(r.kind.code.toByte()).put(r.key.hexBytes(12)).put(r.device.hexBytes(8)).put(r.network.hexBytes(8))
        buf.put(r.band.code.toByte())
        buf.putInt(Math.round(r.lat * 1e7).toInt()).putInt(Math.round(r.lng * 1e7).toInt())
        buf.putFloat(r.weight).putFloat(r.rangeM)
        buf.putShort(r.occasions.coerceIn(0, 65535).toShort()).putShort(r.days.coerceIn(0, 65535).toShort())
        buf.putInt(r.lastDay).putInt(r.lastTeachMin).putInt(r.lastSeenMin)
        buf.put(r.moves.coerceIn(0, 255).toByte()).putInt(r.lastMoveMin).putInt(r.blockedUntilMin)
    }
    return buf.array()
}

/** The rows, or `null` for another magic, another version (the no-compat rule: dropped) or a wrong length. */
fun decodeTable(bytes: ByteArray): List<PlaceRow>? {
    if (bytes.size < MAGIC.size + 5 || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
    val buf = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
    if (buf.get().toInt() != TABLE_VERSION) return null
    val count = buf.int
    if (count < 0 || buf.remaining().toLong() != count.toLong() * ROW_BYTES) return null
    fun hex(n: Int) = ByteArray(n).also { buf.get(it) }.toHex()
    return List(count) {
        val kind = RowKind.of(buf.get().toInt()) ?: return null
        val key = hex(12)
        val device = hex(8)
        val network = hex(8)
        PlaceRow(
            kind = kind,
            key = key,
            device = if (kind == RowKind.LINK || kind == RowKind.APPLE) "" else device,
            network = if (kind == RowKind.APPLE) "" else network,
            band = Band.of(buf.get().toInt()),
            lat = buf.int / 1e7,
            lng = buf.int / 1e7,
            weight = buf.float,
            rangeM = buf.float,
            occasions = buf.short.toInt() and 0xffff,
            days = buf.short.toInt() and 0xffff,
            lastDay = buf.int,
            lastTeachMin = buf.int,
            lastSeenMin = buf.int,
            moves = buf.get().toInt() and 0xff,
            lastMoveMin = buf.int,
            blockedUntilMin = buf.int,
        )
    }
}
