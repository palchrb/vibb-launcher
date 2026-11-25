package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.Motion

/*
 * Design 134 (`docs/design/134-still-place-fingerprint.md`) - the pure rules, no Android imports, tested in
 * StillPlanTest: which scan results answer our request and when a scan is inconclusive (D1, D2), the second scan (D3),
 * the withheld try after a move (D4).
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
