package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.Motion
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.abs

/*
 * Design 134 (`docs/design/134-still-place-fingerprint.md`) - the pure rules, no Android imports, tested in
 * StillPlanTest: which scan results answer our request and when a scan is inconclusive (D1, D2), the second scan (D3),
 * the withheld try after a move (D4); the fingerprint - what it holds and when it is taken (D7, D8), the strict verdict
 * in shadow (D9), the events, outcomes and the sealed ring's format (D10-D13); revision 3's mismatch (D15-D19).
 */

// --- D1, D2: the scan's answer ---

/** With the cache taken without asking (`cache`), a result answers when at most this old (D1). */
const val CACHE_ANSWER_MS = 30_000L

/** D3: the second scan comes at least this long after the first request. */
const val SECOND_SCAN_GAP_MS = 10_000L

/** D3: the table could give a fix from a fresh scan with at least this many trusted networks. */
const val SECOND_SCAN_NETWORKS_MIN = 2

/** D4: at most this many inconclusive tries in a row are withheld; the next one is an ordinary failed try. */
const val INCONCLUSIVE_TRIES_MAX = 3

/** How a scan was asked for (D1): the cache taken without asking, a `startScan` answered by the callback, refused
 * (`false`: throttled, or no permission), not answered within the wait, or refused while Wi-Fi is off. */
enum class ScanAsk(val wire: String) {
    CACHE("cache"),
    ANSWERED("answered"),
    REFUSED("refused"),
    UNANSWERED("unanswered"),
    WIFI_OFF("wifioff"),
}

/** A scan's request: when (elapsed realtime) and how it went. */
data class ScanRequest(val atElapsedMs: Long, val ask: ScanAsk)

/** Why a scan says nothing about the place (D2) - the log's `reason=`. */
enum class Inconclusive(val wire: String) {
    REFUSED("refused"),
    OLD("old"),
    UNANSWERED("unanswered"),
    NO_CONNECTED("noconnected"),
}

/** The results that answer the request ([answering]) and, when the scan says nothing, why ([inconclusive]). */
data class ScanAnswer(val answering: List<ScanAp>, val inconclusive: Inconclusive?) {
    val conclusive: Boolean get() = inconclusive == null
}

/**
 * D1, D2: which of [results] answer [request] - a result heard after the request (its `timestamp` against the
 * request's elapsed time), or with [ScanAsk.CACHE] at most [CACHE_ANSWER_MS] old at [nowElapsedMs]; both also after
 * the last fire ([movedAtElapsedMs], design 125 D9). Inconclusive: refused ([Inconclusive.REFUSED]); answered but no
 * result answers it while the cache holds some ([Inconclusive.OLD] - an empty cache after an answered scan is an honest
 * "nothing heard"); not answered in time and no result answers it ([Inconclusive.UNANSWERED]); the phone connected
 * ([connectedBssid], a live BSSID) to an AP none of the answering results contains ([Inconclusive.NO_CONNECTED]: the
 * AP the phone hears best is on every full-band scan). Refused with Wi-Fi off is conclusive: nothing can be heard, and
 * nothing would be by asking again. The count of results never makes a scan inconclusive.
 */
fun scanAnswer(results: List<ScanAp>, request: ScanRequest, connectedBssid: String?, movedAtElapsedMs: Long, nowElapsedMs: Long): ScanAnswer {
    fun answers(r: ScanAp): Boolean {
        val heardAfterAsk = if (request.ask == ScanAsk.CACHE) nowElapsedMs - r.elapsedMs in 0..CACHE_ANSWER_MS else r.elapsedMs > request.atElapsedMs
        return heardAfterAsk && r.elapsedMs > movedAtElapsedMs
    }
    val answering = results.filter(::answers)
    val reason = when (request.ask) {
        ScanAsk.REFUSED -> Inconclusive.REFUSED
        ScanAsk.WIFI_OFF -> return ScanAnswer(answering, null)
        ScanAsk.UNANSWERED -> if (answering.isEmpty()) Inconclusive.UNANSWERED else null
        ScanAsk.ANSWERED -> if (answering.isEmpty() && results.isNotEmpty()) Inconclusive.OLD else null
        ScanAsk.CACHE -> if (answering.isEmpty()) Inconclusive.OLD else null
    }
    if (reason != null) return ScanAnswer(answering, reason)
    val connected = connectedBssid?.let(::normalBssid)
    if (connected != null && answering.none { normalBssid(it.bssid) == connected }) return ScanAnswer(answering, Inconclusive.NO_CONNECTED)
    return ScanAnswer(answering, null)
}

// --- D3: one more scan, only when it would decide ---

/** The distinct networks of the table's trusted own Wi-Fi rows (Apple's don't count: D3 asks whether the table could
 * give a fix from a fresh scan). */
fun trustedNetworkCount(rows: List<PlaceRow>, nowMin: Int): Int =
    rows.asSequence().filter { it.kind == RowKind.WIFI && it.trusted(nowMin) }.map { it.network }.distinct().count()

/**
 * D3: after a move ([afterMove]: the heartbeat's `FIX` while `MOVED`), the first scan [first] inconclusive, the attempt
 * about to end with no fresh fix ([freshFix]), and a fresh scan could give one - [trustedNetworks] >= 2 or the Apple
 * lookup may ask ([appleMayAsk]) - one more scan. Not after a refusal: the throttle that refused it refuses another
 * within its 2-min window (and a refusal with Wi-Fi off is conclusive). One per attempt (the caller asks once).
 */
fun secondScanWorth(afterMove: Boolean, first: ScanAnswer?, freshFix: Boolean, trustedNetworks: Int, appleMayAsk: Boolean): Boolean {
    val reason = first?.inconclusive ?: return false
    return afterMove && !freshFix && reason != Inconclusive.REFUSED && (trustedNetworks >= SECOND_SCAN_NETWORKS_MIN || appleMayAsk)
}

/** D3: how long the second scan waits so it comes [SECOND_SCAN_GAP_MS] after the first request ([firstAtElapsedMs]). */
fun secondScanWaitMs(firstAtElapsedMs: Long, nowElapsedMs: Long): Long = (firstAtElapsedMs + SECOND_SCAN_GAP_MS - nowElapsedMs).coerceAtLeast(0)

// --- D4: the withheld try ---

/** D4: this attempt may be withheld when its scan is inconclusive - the heartbeat's own `FIX` ([heartbeatFix]) after a
 * move ([motion] `MOVED`), fewer than [INCONCLUSIVE_TRIES_MAX] withheld in a row before it ([tries]). While `STILL` an
 * inconclusive scan is (A)'s cap fail (D5); a crossing's, the low-battery and `locate`'s fixes are unchanged. */
fun mayWithhold(heartbeatFix: Boolean, motion: Motion, tries: Int): Boolean =
    heartbeatFix && motion == Motion.MOVED && tries < INCONCLUSIVE_TRIES_MAX

/** D4: the attempt is withheld - it may be ([mayWithhold]), its last scan was inconclusive ([inconclusive]) and it
 * brought no genuinely fresh fix ([freshFix]): nothing is reported, the flags stay as they were. */
fun withheld(mayWithhold: Boolean, inconclusive: Inconclusive?, freshFix: Boolean): Boolean = mayWithhold && inconclusive != null && !freshFix

/** D4: the withheld tries in a row after the heartbeat's `FIX` after a move: one more when [withheld]; else none (a
 * conclusive try, a fresh fix, or the 4th - an ordinary failed try ends the run). */
fun inconclusiveTriesAfter(tries: Int, withheld: Boolean): Int = if (withheld) tries + 1 else 0

// --- D7, D8: the fingerprint ---

/** D7: a print keeps at most this many settled devices (the strongest). */
const val PRINT_DEVICES_MAX = 12

/** Revision 3 (D7): ... and at most this many other cells beside the serving one. */
const val PRINT_CELLS_MAX = 12

/** D8: a print is taken only with a fix this accurate or better. */
const val PRINT_ACCURACY_MAX_M = 50f

/** D8: ... and only from results heard within this of the fix. */
const val PRINT_PAIR_MS = 30_000L

/** D14 (QA L4): a print lives this long from its take. */
const val PRINT_LIFE_MS = 72 * 60 * 60_000L

/** D7 (QA H3): a device is settled when one of its APs has an own row seen on this many local days, never moved. */
const val SETTLED_DAYS_MIN = 2

/** D9.1, D17: a print of fewer settled devices is `thin` and never decides a mismatch. */
const val PRINT_DEVICES_MIN = 3

/** D9.2, D9.3: a device heard now within this many dB of its print level. */
const val PRINT_LEVEL_DB = 15

/** D9.3, D9.4: a device this strong or stronger in the print is near. */
const val NEAR_DBM = -70

/** D9.3: at least this many near devices agree. */
const val AGREE_MIN = 2

/** D9.4: a print device not heard now counts this many dB in the mean difference. */
const val ABSENT_DB = 30

/** D9.4: the mean difference over the near devices is at most this. */
const val SIMILAR_DB = 12.0

/** D17: a `wifi` mismatch needs at least this many usable APs in the fresh answer. */
const val MISMATCH_USABLE_MIN = 3

/** D8: the providers of a Wi-Fi fix a print may be taken with, when it is checked (design 131). */
val PRINT_WIFI_PROVIDERS = setOf(PROVIDER_WIFI, PROVIDER_APPLE)

/** One settled device of a print or of a scan now: its device and network keys (HMACs), its strongest level, how many
 * of its APs were heard. */
data class PrintDevice(val device: String, val network: String, val dbm: Int, val aps: Int)

/** Revision 3 (D7): the cells read with a print or a scan - the registered (serving) cell's key and radio, the other
 * cells' keys. */
data class PrintCells(val serving: String? = null, val radio: String? = null, val others: List<String> = emptyList())

/**
 * D7: the fingerprint of one vouch - the vouch's time ([fixTimeMs]), when it was taken (elapsed realtime and minutes),
 * the boot, up to [PRINT_DEVICES_MAX] settled devices and (revision 3) the cells. No SSID, BSSID or position.
 */
data class StillPrint(
    val fixTimeMs: Long,
    val takenElapsedMs: Long,
    val takenMin: Int,
    val bootCount: Int,
    val devices: List<PrintDevice>,
    val cells: PrintCells = PrintCells(),
) {
    val fixMin: Int get() = (fixTimeMs / 60_000).toInt()
}

/** D7 (QA H3): the keys of the own Wi-Fi rows that make a device settled - seen on [SETTLED_DAYS_MIN] local days,
 * never moved, not blocked. Apple rows never do. */
fun settledKeys(rows: List<PlaceRow>, nowMin: Int): Set<String> =
    rows.asSequence().filter { it.kind == RowKind.WIFI && it.days >= SETTLED_DAYS_MIN && it.moves == 0 && !it.blocked(nowMin) }.map { it.key }.toSet()

/** D7: the settled devices of [heard] - a device with one AP of a [settled] key - by device, the strongest first; its
 * level its strongest AP's, its APs those heard. */
fun settledDevices(heard: List<Heard>, settled: Set<String>): List<PrintDevice> {
    val wifi = heard.filter { it.kind == RowKind.WIFI }
    val devices = wifi.filter { it.key in settled }.map { it.device }.toSet()
    return wifi.filter { it.device in devices }.groupBy { it.device }
        .map { (device, aps) -> aps.maxBy { it.rssi }.let { PrintDevice(device, it.network, it.rssi, aps.size) } }
        .sortedWith(compareByDescending<PrintDevice> { it.dbm }.thenBy { it.device })
}

/** Revision 3: the cells of a read (registered first, design 125b C2) - the first registered one serves, its radio
 * from the wire form; the rest by key, at most [PRINT_CELLS_MAX]. */
fun printCells(cells: List<Heard>): PrintCells {
    val all = cells.filter { it.kind == RowKind.CELL }
    val serving = all.firstOrNull { it.registered }
    val others = all.filter { it !== serving }.map { it.key }.distinct().take(PRINT_CELLS_MAX)
    return PrintCells(serving?.key, serving?.let { parseCellWire(it.wire)?.radio?.word }, others)
}

/** Why a fresh fix that becomes the vouch takes no print (D8) - the old one is dropped either way. */
enum class PrintRefusal(val wire: String) { PROVIDER("provider"), ACCURACY("accuracy"), MOVING("moving"), MOCK("mock"), SCAN("scan"), PAIR("pair") }

/**
 * D8: a print may be taken with a fix that becomes the vouch when it is `gps` or a checked Wi-Fi fix ([check] not
 * `null`), of accuracy <= [PRINT_ACCURACY_MAX_M], not moving, not mock in a release build, and the attempt's scan
 * answered its request ([conclusive]); `null` = it may. Never `fused` or an unchecked Wi-Fi fix.
 */
fun printRefusal(provider: String?, check: String?, accuracyM: Float?, moving: Boolean, mock: Boolean, release: Boolean, conclusive: Boolean): PrintRefusal? = when {
    provider != "gps" && !(provider in PRINT_WIFI_PROVIDERS && check != null) -> PrintRefusal.PROVIDER
    accuracyM == null || !accuracyM.isFinite() || accuracyM < 0f || accuracyM > PRINT_ACCURACY_MAX_M -> PrintRefusal.ACCURACY
    moving -> PrintRefusal.MOVING
    mock && release -> PrintRefusal.MOCK
    !conclusive -> PrintRefusal.SCAN
    else -> null
}

/** D8: the print of a vouch at [fixTimeMs] (its elapsed time [fixElapsedMs]) from the answering results [heard] within
 * [PRINT_PAIR_MS] of it: their settled devices, the strongest [PRINT_DEVICES_MAX]; none - no print ([PrintRefusal.PAIR]). */
fun takePrint(
    fixTimeMs: Long,
    fixElapsedMs: Long,
    bootCount: Int,
    nowElapsedMs: Long,
    nowMin: Int,
    heard: List<Heard>,
    settled: Set<String>,
    cells: List<Heard>,
): StillPrint? {
    val paired = heard.filter { abs(it.elapsedMs - fixElapsedMs) <= PRINT_PAIR_MS }
    val devices = settledDevices(paired, settled).take(PRINT_DEVICES_MAX)
    if (devices.isEmpty()) return null
    return StillPrint(fixTimeMs, nowElapsedMs, nowMin, bootCount, devices, printCells(cells))
}

/** D8 (QA M5): at a renewal while still whose scan was conclusive and where the strict rule held, the print gains the
 * settled devices it lacks ([now]), the strongest first, up to [PRINT_DEVICES_MAX]; it never drops a device, changes a
 * level or moves its anchor. */
fun grownPrint(print: StillPrint, now: List<PrintDevice>): StillPrint {
    val have = print.devices.map { it.device }.toSet()
    val room = PRINT_DEVICES_MAX - print.devices.size
    if (room <= 0) return print
    val added = now.filter { it.device !in have }.sortedByDescending { it.dbm }.take(room)
    return if (added.isEmpty()) print else print.copy(devices = print.devices + added)
}

/** Why a print went (D14): logged `print dropped (<wire>)` and counted for the dump. */
enum class DropReason(val code: Int, val wire: String) {
    BOOT(1, "boot"),
    GLA(2, "gla"),
    RULE(3, "rule"),
    DISTRUST(4, "distrust"),
    REPLACED(5, "replaced"),
    NOT_FRESH(6, "notfresh"),
    EXPIRED(7, "expired"),
    NO_SENSOR(8, "nosensor"),
    ;

    companion object {
        fun of(code: Int): DropReason? = entries.firstOrNull { it.code == code }
    }
}

/** D14: the print still describes the place - this boot ([nowBoot]) and under [PRINT_LIFE_MS] old. `null` = it does;
 * else the drop's reason. */
fun printStale(print: StillPrint, nowBoot: Int, nowElapsedMs: Long): DropReason? = when {
    print.bootCount != nowBoot -> DropReason.BOOT
    nowElapsedMs - print.takenElapsedMs !in 0 until PRINT_LIFE_MS -> DropReason.EXPIRED
    else -> null
}

// --- D9: the strict verdict (shadow) ---

/** What a conclusive scan heard, as the print sees it: the settled devices now ([devices], D7), every usable device
 * heard now ([heardDevices]), the usable APs' count, the device of the strongest usable result ([strongestDevice],
 * settled or not - QA H1) and the cells. */
data class PrintScan(
    val devices: List<PrintDevice>,
    val heardDevices: Set<String>,
    val usable: Int,
    val strongestDevice: String?,
    val cells: PrintCells = PrintCells(),
)

/** D9: a conclusive answer's usable Wi-Fi results ([heard]) against the table's [settled] keys, with the cells read. */
fun printScan(heard: List<Heard>, settled: Set<String>, cells: List<Heard>): PrintScan {
    val wifi = heard.filter { it.kind == RowKind.WIFI }
    return PrintScan(
        devices = settledDevices(wifi, settled),
        heardDevices = wifi.map { it.device }.toSet(),
        usable = wifi.size,
        strongestDevice = wifi.maxWithOrNull(compareBy<Heard> { it.rssi }.thenByDescending { it.key })?.device,
        cells = printCells(cells),
    )
}

/** The first rule of D9 that failed, or [NONE] when it would renew. */
enum class PrintReason(val code: Int, val wire: String) {
    NONE(0, "-"),
    THIN(1, "thin"),
    ANCHOR(2, "anchor"),
    AGREE(3, "agree"),
    SIMILAR(4, "similar"),
    VETO(5, "veto"),
    ;

    companion object {
        fun of(code: Int): PrintReason = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** D9's verdict and what the tuning reads: the weighted Jaccard [j], the mean difference over the near devices
 * ([meanDiffDb], `null` without one), the device counts and the near counts on both sides, the agreeing count. */
data class PrintVerdict(
    val would: Boolean,
    val reason: PrintReason,
    val j: Double,
    val meanDiffDb: Double?,
    val printDevices: Int,
    val nowDevices: Int,
    val printNear: Int,
    val nowNear: Int,
    val agree: Int,
)

/** D9's linear weight (no clamp): -100 dBm and weaker weigh nothing. */
private fun weight(dbm: Int): Double = maxOf(0, dbm + 100).toDouble()

/**
 * D9: whether the strict rule *would* renew the vouch on a conclusive scan after a move - shadow only, it never acts
 * (D6). In order: `thin` (the print has [PRINT_DEVICES_MIN] settled devices), `anchor` (the print's strongest device
 * heard now within [PRINT_LEVEL_DB] of its level, and the strongest usable result now one of the print's two strongest
 * devices), `agree` ([AGREE_MIN] near devices heard within [PRINT_LEVEL_DB]), `similar` (the near devices' mean
 * difference, an absent one [ABSENT_DB], at most [SIMILAR_DB]), `veto` (nothing in the attempt says elsewhere: [veto]).
 */
fun shadowVerdict(print: StillPrint, now: PrintScan, veto: Boolean): PrintVerdict {
    val nowBy = now.devices.associateBy { it.device }
    val printBy = print.devices.associateBy { it.device }
    val near = print.devices.filter { it.dbm >= NEAR_DBM }
    fun diff(p: PrintDevice): Int? = nowBy[p.device]?.let { abs(it.dbm - p.dbm) }
    val agree = near.count { p -> diff(p)?.let { it <= PRINT_LEVEL_DB } == true }
    val mean = near.takeIf { it.isNotEmpty() }?.map { (diff(it) ?: ABSENT_DB).toDouble() }?.average()
    var lo = 0.0
    var hi = 0.0
    for (k in printBy.keys + nowBy.keys) {
        val a = printBy[k]?.let { weight(it.dbm) } ?: 0.0
        val b = nowBy[k]?.let { weight(it.dbm) } ?: 0.0
        lo += minOf(a, b)
        hi += maxOf(a, b)
    }
    val j = if (hi > 0) lo / hi else 0.0
    val ranked = print.devices.sortedWith(compareByDescending<PrintDevice> { it.dbm }.thenBy { it.device })
    val strongest = ranked.firstOrNull()
    val topTwo = ranked.take(2).map { it.device }.toSet()
    val reason = when {
        print.devices.size < PRINT_DEVICES_MIN -> PrintReason.THIN
        strongest == null || diff(strongest)?.let { it <= PRINT_LEVEL_DB } != true || now.strongestDevice !in topTwo -> PrintReason.ANCHOR
        agree < AGREE_MIN -> PrintReason.AGREE
        mean == null || mean > SIMILAR_DB -> PrintReason.SIMILAR
        veto -> PrintReason.VETO
        else -> PrintReason.NONE
    }
    return PrintVerdict(reason == PrintReason.NONE, reason, j, mean, print.devices.size, now.devices.size, near.size, now.devices.count { it.dbm >= NEAR_DBM }, agree)
}

// --- D15-D17 (revision 3): a scan mismatch ends STILL ---

/** Which clause of D15 found the scan clearly not the place. */
enum class MismatchClause(val code: Int, val wire: String) {
    WIFI(1, "wifi"),
    CELL(2, "cell"),
    ;

    companion object {
        fun of(code: Int): MismatchClause? = entries.firstOrNull { it.code == code }
    }
}

/**
 * D15, D17: a conclusive scan of a cap try or retry while `STILL` clearly isn't the place of [print] - `null` when it
 * may be. Never with a print of fewer than [PRINT_DEVICES_MIN] settled devices. [MismatchClause.WIFI]: none of the
 * print's devices heard at all, the answer holding at least [MISMATCH_USABLE_MIN] usable APs; [MismatchClause.CELL]:
 * the print and the read both have a registered cell of the same radio, and no cell read now (serving or other) is
 * any cell of the print's. The caller passes only a fresh, conclusive answer (D2): an inconclusive one never counts.
 */
fun printMismatch(print: StillPrint, now: PrintScan): MismatchClause? {
    if (print.devices.size < PRINT_DEVICES_MIN) return null
    if (now.usable >= MISMATCH_USABLE_MIN && print.devices.none { it.device in now.heardDevices }) return MismatchClause.WIFI
    val before = print.cells.serving ?: return null
    val serving = now.cells.serving ?: return null
    if (print.cells.radio == null || print.cells.radio != now.cells.radio) return null
    val printCells = setOf(before) + print.cells.others
    return if ((listOf(serving) + now.cells.others).none { it in printCells }) MismatchClause.CELL else null
}

// --- D10-D12, D19: events, outcomes and the ring ---

/** An event's kind (D10, D19). */
enum class EventKind(val code: Int, val wire: String) {
    VERDICT(1, "verdict"),
    INCONCLUSIVE(2, "inconclusive"),
    MISMATCH(3, "mismatch"),
    ;

    companion object {
        fun of(code: Int): EventKind = entries.first { it.code == code }
    }
}

/** An event's outcome (D11, D19): [PENDING] until the next genuinely fresh fix; [NOTED] an inconclusive event's (its
 * distance kept, nothing judged). For a verdict [CONFIRMED]/[CONTRADICTED] judge a would-renew; for a mismatch they say
 * whether it was right (the phone had moved) or wrong. */
enum class OutcomeClass(val code: Int, val wire: String) {
    PENDING(0, "pending"),
    CONFIRMED(1, "confirmed"),
    CONTRADICTED(2, "contradicted"),
    WOULD_NOT(3, "would-not"),
    UNJUDGED(4, "unjudged"),
    NOTED(5, "noted"),
    ;

    companion object {
        fun of(code: Int): OutcomeClass = entries.first { it.code == code }
    }
}

/** D11: only a fix this accurate or better is an outcome. */
const val OUTCOME_ACCURACY_MAX_M = 100f

/** D11: confirmed within both accuracies and this. */
const val OUTCOME_SLACK_M = 50.0

/** D11: an event with no outcome within this is unjudged. */
const val OUTCOME_WAIT_MIN = 24 * 60

/** D12: the ring keeps the last this many events ... */
const val RING_MAX = 500

/** ... and none older than this (60 days). */
const val RING_KEEP_MIN = 60 * 24 * 60

/**
 * One event of the ring (D12) - no BSSID, SSID, key or coordinate. [atMin] wall minutes, [atElapsedS] elapsed seconds
 * and [boot] (for "a fire or a boot between"), [printMin] the print's vouch (minutes); [reason] a [PrintReason] for a
 * verdict, an [Inconclusive] (its ordinal + 1) for an inconclusive event, a [MismatchClause] for a mismatch; `J` and the
 * mean difference x 100 and x 10 (-1 none); the counts (for a mismatch [nowDevices] is the print's devices heard now);
 * the outcome: the distance from the vouch in tens of metres, the minutes after, the fix's accuracy class (0: <= 10 m,
 * 1: <= 25, 2: <= 50, 3: <= 100), whether a fire or a boot came between ([movedAfter]) and whether it lay within both
 * accuracies + 50 m ([within]).
 */
data class PrintEvent(
    val atMin: Int,
    val atElapsedS: Int,
    val boot: Int,
    val printMin: Int,
    val kind: EventKind,
    val would: Boolean = false,
    val reason: Int = 0,
    val j100: Int = 0,
    val diff10: Int = -1,
    val printDevices: Int = 0,
    val nowDevices: Int = 0,
    val printNear: Int = 0,
    val nowNear: Int = 0,
    val agree: Int = 0,
    val outcome: OutcomeClass = OutcomeClass.PENDING,
    val distance10: Int = -1,
    val afterMin: Int = -1,
    val accClass: Int = -1,
    val movedAfter: Boolean = false,
    val within: Boolean = false,
) {
    /** The reason's word for the log and the dump. */
    val reasonWire: String get() = when (kind) {
        EventKind.VERDICT -> PrintReason.of(reason).wire
        EventKind.INCONCLUSIVE -> Inconclusive.entries.getOrNull(reason - 1)?.wire ?: "-"
        EventKind.MISMATCH -> MismatchClause.of(reason)?.wire ?: "-"
    }
}

/** When an event happened: wall minutes, elapsed realtime, the boot. */
data class EventAt(val atMin: Int, val atElapsedMs: Long, val boot: Int)

/** D10: a verdict's event. */
fun verdictEvent(print: StillPrint, v: PrintVerdict, at: EventAt): PrintEvent = PrintEvent(
    at.atMin, (at.atElapsedMs / 1000).toInt(), at.boot, print.fixMin, EventKind.VERDICT, v.would, v.reason.code,
    j100 = Math.round(v.j * 100).toInt(), diff10 = v.meanDiffDb?.let { Math.round(it * 10).toInt() } ?: -1,
    printDevices = v.printDevices, nowDevices = v.nowDevices, printNear = v.printNear, nowNear = v.nowNear, agree = v.agree,
)

/** D10: an inconclusive try's event. */
fun inconclusiveEvent(print: StillPrint, reason: Inconclusive, at: EventAt): PrintEvent = PrintEvent(
    at.atMin, (at.atElapsedMs / 1000).toInt(), at.boot, print.fixMin, EventKind.INCONCLUSIVE, reason = reason.ordinal + 1,
    printDevices = print.devices.size,
)

/** D19: a mismatch's event - its clause, the print's devices and how many of them were heard. */
fun mismatchEvent(print: StillPrint, clause: MismatchClause, now: PrintScan, at: EventAt): PrintEvent = PrintEvent(
    at.atMin, (at.atElapsedMs / 1000).toInt(), at.boot, print.fixMin, EventKind.MISMATCH, reason = clause.code,
    printDevices = print.devices.size, nowDevices = print.devices.count { it.device in now.heardDevices },
    printNear = print.devices.count { it.dbm >= NEAR_DBM },
)

/** The fix that may be an outcome (D11): its position (never stored), accuracy, time, boot and the motion gate's last
 * fire or mark ([movedAtElapsedMs], read after the attempt). */
data class OutcomeFix(val lat: Double, val lng: Double, val accuracyM: Float, val atMin: Int, val boot: Int, val movedAtElapsedMs: Long)

/** The vouch the events' distances are measured from: its time in minutes, position and accuracy (never stored). */
data class VouchPoint(val timeMin: Int, val lat: Double, val lng: Double, val accuracyM: Float)

/** D11: a fix's accuracy class. */
fun accuracyClass(accuracyM: Float): Int = when {
    accuracyM <= 10f -> 0
    accuracyM <= 25f -> 1
    accuracyM <= 50f -> 2
    else -> 3
}

/**
 * D11, D19: [fix] - a genuinely fresh fix of accuracy <= [OUTCOME_ACCURACY_MAX_M] - is the outcome of a pending event.
 * The distance from [vouch] when it is the event's print's vouch (else none); unjudged when a fire or a boot came
 * between, the event is over [OUTCOME_WAIT_MIN] old, its print is gone ([printMin], the print in force; `null` none) or
 * the distance is unknown; an inconclusive event is noted; a mismatch confirmed (right) when the fix lies further than
 * both accuracies + [OUTCOME_SLACK_M] from the vouch, else contradicted (wrong); a would-renew confirmed within that,
 * else contradicted; a would-not keeps its distance and whether it lay within.
 */
fun judgedEvent(e: PrintEvent, fix: OutcomeFix, vouch: VouchPoint?, printMin: Int?): PrintEvent {
    if (e.outcome != OutcomeClass.PENDING || !fix.accuracyM.isFinite() || fix.accuracyM > OUTCOME_ACCURACY_MAX_M) return e
    val moved = e.boot != fix.boot || fix.movedAtElapsedMs > e.atElapsedS * 1000L
    val late = fix.atMin - e.atMin > OUTCOME_WAIT_MIN
    val d = vouch?.takeIf { it.timeMin == e.printMin }?.let { com.kidslauncher.mdm.zones.distanceM(it.lat, it.lng, fix.lat, fix.lng) }
    val within = d != null && d <= fix.accuracyM + (vouch?.accuracyM ?: 0f) + OUTCOME_SLACK_M
    val cls = when {
        moved || late || printMin != e.printMin || d == null -> OutcomeClass.UNJUDGED
        e.kind == EventKind.INCONCLUSIVE -> OutcomeClass.NOTED
        e.kind == EventKind.MISMATCH -> if (within) OutcomeClass.CONTRADICTED else OutcomeClass.CONFIRMED
        !e.would -> OutcomeClass.WOULD_NOT
        within -> OutcomeClass.CONFIRMED
        else -> OutcomeClass.CONTRADICTED
    }
    return e.copy(
        outcome = cls,
        distance10 = d?.let { Math.round(it / 10).toInt() } ?: -1,
        afterMin = fix.atMin - e.atMin,
        accClass = accuracyClass(fix.accuracyM),
        movedAfter = moved,
        within = within,
    )
}

/** The ring and its counts (D12, D13): the events, oldest first; the prints taken and dropped by reason. */
data class PrintRing(val events: List<PrintEvent> = emptyList(), val taken: Int = 0, val drops: Map<DropReason, Int> = emptyMap())

/** D11, D12: events older than [RING_KEEP_MIN] go; a pending one past [OUTCOME_WAIT_MIN] is unjudged. */
fun ringAged(ring: PrintRing, nowMin: Int): PrintRing = ring.copy(
    events = ring.events.filter { nowMin - it.atMin <= RING_KEEP_MIN }.map {
        if (it.outcome == OutcomeClass.PENDING && nowMin - it.atMin > OUTCOME_WAIT_MIN) it.copy(outcome = OutcomeClass.UNJUDGED) else it
    },
)

/** D12: [e] recorded - the ring aged, [e] appended, the newest [RING_MAX] kept. */
fun ringWith(ring: PrintRing, e: PrintEvent, nowMin: Int): PrintRing = ringAged(ring, nowMin).let { it.copy(events = (it.events + e).takeLast(RING_MAX)) }

/** D11: every pending event judged by [fix] ([judgedEvent]). */
fun ringAfterFix(ring: PrintRing, fix: OutcomeFix, vouch: VouchPoint?, printMin: Int?): PrintRing =
    ringAged(ring, fix.atMin).let { r -> r.copy(events = r.events.map { judgedEvent(it, fix, vouch, printMin) }) }

/** D13: a print taken ... */
fun ringTaken(ring: PrintRing): PrintRing = ring.copy(taken = ring.taken + 1)

/** ... and one dropped, by reason. */
fun ringDropped(ring: PrintRing, reason: DropReason): PrintRing = ring.copy(drops = ring.drops + (reason to (ring.drops[reason] ?: 0) + 1))

// --- P1: the sealed files' formats ---

/** P1: another format version drops the old file (the no-compat rule). */
const val PRINT_FORMAT_VERSION = 1
private val PRINT_MAGIC = "VSP".toByteArray(Charsets.US_ASCII)
private val RING_MAGIC = "VSR".toByteArray(Charsets.US_ASCII)

private fun DataOutputStream.text(s: String?) {
    val b = s.orEmpty().toByteArray(Charsets.UTF_8)
    require(b.size < 256)
    writeByte(b.size)
    write(b)
}

private fun DataInputStream.text(): String = String(ByteArray(readUnsignedByte()).also { readFully(it) }, Charsets.UTF_8)

private fun encoded(magic: ByteArray, body: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use {
        it.write(magic)
        it.writeByte(PRINT_FORMAT_VERSION)
        it.body()
    }
    return out.toByteArray()
}

/** A file of another magic or version, or a broken one: `null` (dropped). */
private fun <T> decoded(bytes: ByteArray, magic: ByteArray, body: DataInputStream.() -> T): T? = try {
    DataInputStream(bytes.inputStream()).use { input ->
        val head = ByteArray(magic.size).also { input.readFully(it) }
        if (!head.contentEquals(magic) || input.readUnsignedByte() != PRINT_FORMAT_VERSION) null else input.body()
    }
} catch (e: IOException) {
    null
} catch (e: RuntimeException) {
    null
}

/** P1: the print (< 1 KB): the times, the boot, the cells and the devices - keys and levels only. */
fun encodePrint(p: StillPrint): ByteArray = encoded(PRINT_MAGIC) {
    writeLong(p.fixTimeMs)
    writeLong(p.takenElapsedMs)
    writeInt(p.takenMin)
    writeInt(p.bootCount)
    text(p.cells.serving)
    text(p.cells.radio)
    writeByte(p.cells.others.size)
    p.cells.others.forEach { text(it) }
    writeByte(p.devices.size)
    for (d in p.devices) {
        text(d.device)
        text(d.network)
        writeShort(d.dbm)
        writeByte(d.aps.coerceIn(0, 255))
    }
}

fun decodePrint(bytes: ByteArray): StillPrint? = decoded(bytes, PRINT_MAGIC) {
    val fix = readLong()
    val taken = readLong()
    val takenMin = readInt()
    val boot = readInt()
    val serving = text().ifEmpty { null }
    val radio = text().ifEmpty { null }
    val others = List(readUnsignedByte()) { text() }
    val devices = List(readUnsignedByte()) { PrintDevice(text(), text(), readShort().toInt(), readUnsignedByte()) }
    StillPrint(fix, taken, takenMin, boot, devices, PrintCells(serving, radio, others))
}

/** P1: the ring (~37 bytes an event, ~19 KB at 500) with the taken and dropped counts. */
fun encodeRing(r: PrintRing): ByteArray = encoded(RING_MAGIC) {
    writeInt(r.taken)
    writeByte(r.drops.size)
    for ((reason, n) in r.drops) {
        writeByte(reason.code)
        writeInt(n)
    }
    writeShort(r.events.size)
    for (e in r.events) {
        writeInt(e.atMin)
        writeInt(e.atElapsedS)
        writeInt(e.boot)
        writeInt(e.printMin)
        writeByte(e.kind.code)
        writeByte((if (e.would) 1 else 0) or (if (e.movedAfter) 2 else 0) or (if (e.within) 4 else 0))
        writeByte(e.reason)
        writeByte(e.j100.coerceIn(0, 100))
        writeShort(e.diff10.coerceIn(-1, Short.MAX_VALUE.toInt()))
        for (n in listOf(e.printDevices, e.nowDevices, e.printNear, e.nowNear, e.agree)) writeByte(n.coerceIn(0, 255))
        writeByte(e.outcome.code)
        writeInt(e.distance10)
        writeInt(e.afterMin)
        writeByte(e.accClass + 1)
    }
}

fun decodeRing(bytes: ByteArray): PrintRing? = decoded(bytes, RING_MAGIC) {
    val taken = readInt()
    val drops = (0 until readUnsignedByte()).associate { DropReason.of(readUnsignedByte())!! to readInt() }
    val events = List(readUnsignedShort()) {
        val atMin = readInt()
        val atElapsedS = readInt()
        val boot = readInt()
        val printMin = readInt()
        val kind = EventKind.of(readUnsignedByte())
        val flags = readUnsignedByte()
        val reason = readUnsignedByte()
        val j100 = readUnsignedByte()
        val diff10 = readShort().toInt()
        val counts = List(5) { readUnsignedByte() }
        val outcome = OutcomeClass.of(readUnsignedByte())
        PrintEvent(
            atMin, atElapsedS, boot, printMin, kind, flags and 1 != 0, reason, j100, diff10,
            counts[0], counts[1], counts[2], counts[3], counts[4], outcome,
            distance10 = readInt(), afterMin = readInt(), accClass = readUnsignedByte() - 1,
            movedAfter = flags and 2 != 0, within = flags and 4 != 0,
        )
    }
    PrintRing(events, taken, drops)
}

// --- D12, D13: the log's and the dump's lines (counts, levels, J, rounded distances and times only) ---

private fun oneDecimal(tenths: Int): String = "${tenths / 10}.${tenths % 10}"

/** An event as the log (`StillPrint: ...`) and the dump's ring say it, without its time. */
fun eventText(e: PrintEvent): String = when (e.kind) {
    EventKind.VERDICT -> "verdict would=${if (e.would) "yes" else "no reason=${e.reasonWire}"} j=${"%d.%02d".format(e.j100 / 100, e.j100 % 100)}" +
        " diff=${if (e.diff10 < 0) "-" else oneDecimal(e.diff10)} devices=${e.printDevices}/${e.nowDevices} near=${e.printNear}/${e.nowNear} agree=${e.agree}"
    EventKind.INCONCLUSIVE -> "inconclusive reason=${e.reasonWire} devices=${e.printDevices}"
    EventKind.MISMATCH -> "mismatch clause=${e.reasonWire} devices=${e.nowDevices}/${e.printDevices}"
}

/** An event's outcome as the log (`StillPrint: outcome ...`) and the dump say it. */
fun outcomeText(e: PrintEvent): String {
    if (e.outcome == OutcomeClass.PENDING) return "pending"
    val d = if (e.distance10 < 0) "-" else (e.distance10 * 10).toString()
    val acc = listOf("10", "25", "50", "100").getOrNull(e.accClass) ?: "-"
    val extra = (if (e.movedAfter) " moved" else "") + (if (e.outcome == OutcomeClass.WOULD_NOT && e.within) " within" else "")
    return "${e.outcome.wire} d=$d after=${e.afterMin} acc<=$acc$extra"
}

/**
 * D13: the dump with `still-print` - the print in force (counts and its age only), the prints taken and dropped by
 * reason, the verdicts by class (would-not by reason), the inconclusive tries by reason, the mismatches by clause and
 * outcome (revision 3), then the ring, newest first. No key, BSSID, SSID or coordinate.
 */
fun dumpLines(print: StillPrint?, ring: PrintRing, nowMin: Int, nowElapsedMs: Long): List<String> {
    val out = mutableListOf<String>()
    out += "still-print (design 134): " + if (print == null) {
        "no print"
    } else {
        "print devices=${print.devices.size} cells=${(if (print.cells.serving != null) 1 else 0) + print.cells.others.size} " +
            "age=${(nowElapsedMs - print.takenElapsedMs) / 60_000} min"
    }
    out += "prints taken=${ring.taken} dropped " + DropReason.entries.joinToString(" ") { "${it.wire}=${ring.drops[it] ?: 0}" }
    val events = ringAged(ring, nowMin).events
    fun count(kind: EventKind, cls: OutcomeClass) = events.count { it.kind == kind && it.outcome == cls }
    val verdicts = events.filter { it.kind == EventKind.VERDICT }
    val would = verdicts.filter { it.would }
    val wouldNot = verdicts.filter { !it.would }
    out += "verdicts=${verdicts.size} would=yes " + listOf(OutcomeClass.CONFIRMED, OutcomeClass.CONTRADICTED, OutcomeClass.UNJUDGED, OutcomeClass.PENDING)
        .joinToString(" ") { c -> "${c.wire}=${would.count { it.outcome == c }}" }
    out += "would=no " + PrintReason.entries.filter { it != PrintReason.NONE }.joinToString(" ") { r -> "${r.wire}=${wouldNot.count { it.reason == r.code }}" } +
        " judged=${count(EventKind.VERDICT, OutcomeClass.WOULD_NOT)} within=${wouldNot.count { it.outcome == OutcomeClass.WOULD_NOT && it.within }}" +
        " unjudged=${wouldNot.count { it.outcome == OutcomeClass.UNJUDGED }} pending=${wouldNot.count { it.outcome == OutcomeClass.PENDING }}"
    val inconclusive = events.filter { it.kind == EventKind.INCONCLUSIVE }
    out += "inconclusive=${inconclusive.size} " + Inconclusive.entries.joinToString(" ") { r -> "${r.wire}=${inconclusive.count { it.reason == r.ordinal + 1 }}" }
    val mismatches = events.filter { it.kind == EventKind.MISMATCH }
    out += "mismatches=${mismatches.size} " + MismatchClause.entries.joinToString(" ") { c -> "${c.wire}=${mismatches.count { it.reason == c.code }}" } + " " +
        listOf(OutcomeClass.CONFIRMED, OutcomeClass.CONTRADICTED, OutcomeClass.UNJUDGED, OutcomeClass.PENDING)
            .joinToString(" ") { c -> "${c.wire}=${count(EventKind.MISMATCH, c)}" }
    out += "ring (newest first, ${events.size} of $RING_MAX):"
    for (e in events.asReversed()) {
        out += "${java.time.Instant.ofEpochSecond(e.atMin * 60L)} ${eventText(e)} -> ${outcomeText(e)}"
    }
    return out
}
