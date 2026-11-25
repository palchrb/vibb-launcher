package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.Heartbeat
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.MotionFlags
import com.kidslauncher.mdm.zones.heartbeatDue
import com.kidslauncher.mdm.zones.stillCapFailsAfter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 134 on the phone: which results answer a scan and when it is inconclusive (D1, D2), the second scan (D3), the
 * withheld try after a move (D4) and while still (D5); the glue by source.
 */
class StillPlanTest {
    private fun mac(i: Int) = "00:00:5e:00:53:%02x".format(i)
    private fun ap(i: Int, at: Long, dbm: Int = -60) = ScanAp(mac(i), "Net$i", null, 5180, dbm, at)
    private val minute = 60_000L

    // --- D1, D2 ---

    @Test
    fun `scanAnswer - which results answer the request, and when the scan says nothing`() {
        val now = 10_000_000L
        val asked = now - 4_000
        val moved = now - 600_000
        fun answer(results: List<ScanAp>, ask: ScanAsk, connected: String? = null, at: Long = asked) =
            scanAnswer(results, ScanRequest(at, ask), connected, moved, now)
        // Results heard after the request answer it; every result older: OLD.
        val after = listOf(ap(1, asked + 1_000), ap(2, asked + 1_500), ap(3, asked - 1))
        assertEquals(listOf(mac(1), mac(2)), answer(after, ScanAsk.ANSWERED).answering.map { it.bssid })
        assertTrue(answer(after, ScanAsk.ANSWERED).conclusive)
        assertEquals(Inconclusive.OLD, answer(listOf(ap(1, asked - 1), ap(2, asked - 600_000)), ScanAsk.ANSWERED).inconclusive)
        // The cache path: within 30 s, past it.
        val cache = listOf(ap(1, now - 30_000), ap(2, now - 30_001))
        assertEquals(listOf(mac(1)), answer(cache, ScanAsk.CACHE, at = now).answering.map { it.bssid })
        assertEquals(Inconclusive.OLD, answer(listOf(ap(2, now - 30_001)), ScanAsk.CACHE, at = now).inconclusive)
        // Refused (throttled) with or without fresh results; unanswered with nothing new, and with an answer after all.
        assertEquals(Inconclusive.REFUSED, answer(after, ScanAsk.REFUSED).inconclusive)
        assertEquals(Inconclusive.REFUSED, answer(emptyList(), ScanAsk.REFUSED).inconclusive)
        assertEquals(Inconclusive.UNANSWERED, answer(listOf(ap(1, asked - 1)), ScanAsk.UNANSWERED).inconclusive)
        assertEquals(Inconclusive.UNANSWERED, answer(emptyList(), ScanAsk.UNANSWERED).inconclusive)
        assertTrue(answer(after, ScanAsk.UNANSWERED).conclusive)
        // Wi-Fi off: nothing can be heard and nothing would be by asking again - conclusive.
        assertTrue(answer(emptyList(), ScanAsk.WIFI_OFF).conclusive)
        // Connected: its AP in the answer; missing from it (a list that isn't the place); not connected.
        assertTrue(answer(after, ScanAsk.ANSWERED, connected = mac(2).uppercase()).conclusive)
        assertEquals(Inconclusive.NO_CONNECTED, answer(after, ScanAsk.ANSWERED, connected = mac(3)).inconclusive)
        assertEquals(Inconclusive.NO_CONNECTED, answer(after, ScanAsk.ANSWERED, connected = mac(9)).inconclusive)
        assertTrue(answer(after, ScanAsk.ANSWERED, connected = null).conclusive)
        // One fresh AP, or none at all after an answered scan (the cabin): conclusive - the count never decides.
        assertTrue(answer(listOf(ap(1, asked + 10)), ScanAsk.ANSWERED).conclusive)
        assertTrue(answer(emptyList(), ScanAsk.ANSWERED).conclusive)
        // Results from before the last fire never answer (design 125 D9), even heard after the request.
        assertEquals(Inconclusive.OLD, scanAnswer(listOf(ap(1, asked + 10)), ScanRequest(asked, ScanAsk.ANSWERED), null, asked + 20, now).inconclusive)
        // The wire words of the log line.
        assertEquals(listOf("refused", "old", "unanswered", "noconnected"), Inconclusive.entries.map { it.wire })
    }

    // --- D3 ---

    @Test
    fun `a second scan only when the first said nothing, no fresh fix came and a fresh scan could decide`() {
        val old = ScanAnswer(emptyList(), Inconclusive.OLD)
        val unanswered = ScanAnswer(emptyList(), Inconclusive.UNANSWERED)
        val refused = ScanAnswer(emptyList(), Inconclusive.REFUSED)
        val good = ScanAnswer(listOf(ap(1, 0)), null)
        assertTrue(secondScanWorth(true, old, freshFix = false, trustedNetworks = 2, appleMayAsk = false))
        assertTrue(secondScanWorth(true, unanswered, freshFix = false, trustedNetworks = 0, appleMayAsk = true))
        assertTrue(secondScanWorth(true, ScanAnswer(emptyList(), Inconclusive.NO_CONNECTED), freshFix = false, trustedNetworks = 3, appleMayAsk = false))
        // Conclusive, a fresh fix in hand, nothing that could decide, not after a move, no scan at all.
        assertFalse(secondScanWorth(true, good, freshFix = false, trustedNetworks = 5, appleMayAsk = true))
        assertFalse(secondScanWorth(true, old, freshFix = true, trustedNetworks = 5, appleMayAsk = true))
        assertFalse(secondScanWorth(true, old, freshFix = false, trustedNetworks = 1, appleMayAsk = false))
        assertFalse(secondScanWorth(false, old, freshFix = false, trustedNetworks = 5, appleMayAsk = true))
        assertFalse(secondScanWorth(true, null, freshFix = false, trustedNetworks = 5, appleMayAsk = true))
        // Refused: the throttle refuses another within its window; Wi-Fi off is conclusive (no second either).
        assertFalse(secondScanWorth(true, refused, freshFix = false, trustedNetworks = 5, appleMayAsk = true))
        assertFalse(secondScanWorth(true, scanAnswer(emptyList(), ScanRequest(0, ScanAsk.WIFI_OFF), null, 0, 0), freshFix = false, trustedNetworks = 5, appleMayAsk = true))
        // Not before 10 s after the first request.
        assertEquals(6_000L, secondScanWaitMs(firstAtElapsedMs = 100_000, nowElapsedMs = 104_000))
        assertEquals(0L, secondScanWaitMs(firstAtElapsedMs = 100_000, nowElapsedMs = 115_000))
        // The table's trusted networks: own Wi-Fi rows, two occasions, not blocked, by network.
        val rows = listOf(
            PlaceRow(RowKind.WIFI, "a", network = "n1", occasions = 2),
            PlaceRow(RowKind.WIFI, "b", network = "n1", occasions = 5),
            PlaceRow(RowKind.WIFI, "c", network = "n2", occasions = 1),
            PlaceRow(RowKind.WIFI, "d", network = "n3", occasions = 3, blockedUntilMin = 100),
            PlaceRow(RowKind.APPLE, "e", network = "n4", occasions = 3),
            PlaceRow(RowKind.WIFI, "f", network = "n5", occasions = 2),
        )
        assertEquals(2, trustedNetworkCount(rows, nowMin = 50))
        assertEquals(3, trustedNetworkCount(rows, nowMin = 100))
    }

    // --- D4, D5 ---

    /**
     * D4 beat by beat: a fire, then every heartbeat try after it inconclusive with no fresh fix. Each is withheld (the
     * flags kept, so the next beat is `FIX` again), up to three in a row; the fourth is an ordinary failed try (the
     * flags clear `movedSinceTry`, design 124's `QUIET` back-off follows). [freshAt]: the try (1-based) that brings a
     * fresh fix.
     */
    private fun afterMove(tries: Int, freshAt: Int? = null): List<Pair<Heartbeat, Boolean>> {
        val interval = 30 * minute
        var t = 1_800_000_000_000L
        var flags = MotionFlags().afterTry(0, t, heartbeat = true, nowMs = t).moved(1)
        var lastBeat = t
        var lastTry = t
        var lastFresh = t
        var count = 0
        val out = mutableListOf<Pair<Heartbeat, Boolean>>()
        repeat(tries) { i ->
            t += interval + 1_000
            val motion = flags.motion(sensor = true, anchor = true)
            val beat = heartbeatDue(t, lastBeat, interval, motion, lastFresh, null, lastTry, flags.stillSinceMs)
            lastBeat = t
            if (beat != Heartbeat.FIX) {
                out += beat to false
                return@repeat
            }
            lastTry = t
            val fresh = freshAt == i + 1
            val may = mayWithhold(heartbeatFix = true, motion = motion, tries = count)
            val w = withheld(may, Inconclusive.OLD, freshFix = fresh)
            flags = flags.afterTry(flags.fires, if (fresh) t else null, heartbeat = true, nowMs = t, withheld = w)
            if (fresh) lastFresh = t
            if (motion == Motion.MOVED) count = inconclusiveTriesAfter(count, w)
            out += beat to w
        }
        return out
    }

    @Test
    fun `an inconclusive try after a move reports nothing, keeps the flags, and the next beat tries again - three times`() {
        val run = afterMove(7)
        // Three withheld tries (FIX at every beat), the fourth an ordinary failure, then design 124's back-off (the waits
        // grow as the phone stays still and failing: a try, a try, then QUIET).
        assertEquals(listOf(Heartbeat.FIX to true, Heartbeat.FIX to true, Heartbeat.FIX to true, Heartbeat.FIX to false), run.take(4))
        assertEquals(Heartbeat.FIX, run[4].first)
        assertFalse(run[4].second)
        assertEquals(Heartbeat.FIX, run[5].first)
        assertEquals(Heartbeat.QUIET, run[6].first)
        // A fresh fix at the second try ends the run: the count starts over.
        assertEquals(0, inconclusiveTriesAfter(2, withheld = false))
        val fresh = afterMove(3, freshAt = 2)
        assertEquals(listOf(true, false), fresh.take(2).map { it.second })
        // Withheld only after a move, by the heartbeat's own FIX, fewer than three before, the scan inconclusive and no
        // fresh fix: a cell fix, an unchecked or fallback Wi-Fi fix and a last-known fix are no fresh fix (all withheld).
        assertTrue(mayWithhold(true, Motion.MOVED, 2))
        assertFalse(mayWithhold(true, Motion.MOVED, 3))
        assertFalse(mayWithhold(false, Motion.MOVED, 0))
        assertFalse(mayWithhold(true, Motion.NO_SENSOR, 0))
        assertFalse(mayWithhold(true, Motion.STILL_SINCE_TRY, 0))
        assertFalse(withheld(true, null, freshFix = false))
        assertFalse(withheld(true, Inconclusive.REFUSED, freshFix = true))
        assertFalse(withheld(false, Inconclusive.REFUSED, freshFix = false))
        // D4 keeps the flags: movedSinceTry stays set, so MOVED.
        val moved = MotionFlags().moved(5)
        assertEquals(moved, moved.afterTry(moved.fires, null, heartbeat = true, nowMs = 9, withheld = true))
        assertEquals(Motion.MOVED, moved.afterTry(moved.fires, null, heartbeat = true, nowMs = 9, withheld = true).motion(sensor = true, anchor = true))
    }

    @Test
    fun `while still an inconclusive cap try is part A's cap fail - no withholding, no retry of this design`() {
        // D5: the heartbeat's FIX while STILL is never withheld; it counts as the cap's failed try.
        assertFalse(mayWithhold(true, Motion.STILL, 0))
        assertEquals(1, stillCapFailsAfter(0, Heartbeat.FIX, Motion.STILL, fresh = false, renewed = false, held = true))
    }

    // --- The glue, by source ---

    private fun source(path: String) = File("src/main/java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `source - the request's answer, the second scan and the withheld try`() {
        val glue = source("places/WifiPlaces.kt")
        assertTrue(glue.contains("return Scan(cached(context), ScanRequest(requestAt, ask))"))
        assertTrue(glue.contains("if (fresh >= SCAN_RESULTS_MIN) return Scan(before, ScanRequest(now, ScanAsk.CACHE))"))
        assertTrue(glue.contains("val answer = scanAnswer(scan.results, scan.request, connectedBssid(connected?.bssid), movedAtElapsedMs, SystemClock.elapsedRealtime())"))
        // D3 only in the plan after a move, once.
        assertEquals(1, Regex("""rescan\(app, """).findAll(glue).count())
        assertTrue(glue.contains("val afterMove = ask.heartbeat && ask.motion == Motion.MOVED"))
        // D4: withheld before any cell fix, and LocateCommands falls back to nothing.
        val withhold = glue.indexOf("if (withheld(ask.mayWithhold, ask.inconclusive, kept != null && !ask.notFresh && !ask.renewed)) {")
        assertTrue(withhold > 0 && withhold < glue.indexOf("cellOnly(app, store, snap.await(), ask, plan == AttemptPlan.LOCATE, accept)"))
        assertTrue(Regex("""if \(places\?\.withheld == true\) \{\s+TrustedClock\.readGnss\(context\)\s+return null\s+\}\s+if \(capTryAtMs != null\)""").containsMatchIn(source("server/LocateCommands.kt")))
        val sync = source("server/MdmSyncWorker.kt")
        assertTrue(sync.contains("mayWithhold = motionNow != null && com.kidslauncher.mdm.places.mayWithhold(beat == Heartbeat.FIX, motion, inconclusiveTries),"))
        assertTrue(sync.contains("heartbeat = beat == Heartbeat.FIX, nowMs = now, moving = moving, withheld = places.withheld)"))
        assertTrue(source("zones/ZoneWatch.kt").contains(".remove(KEY_CAP_FAILS).remove(KEY_INCONCLUSIVE).commit()"))
    }
}
