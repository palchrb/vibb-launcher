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
        assertEquals(listOf("refused", "old", "unanswered", "noconnected", "timebase"), Inconclusive.entries.map { it.wire })
        // Code QA L1: the cache path counts its 30 s from the request (the instant scanNow judged the cache), not now.
        assertTrue(scanAnswer(listOf(ap(1, asked - 30_000), ap(2, asked - 29_000), ap(3, asked - 1)), ScanRequest(asked, ScanAsk.CACHE), null, moved, asked + 20_000).conclusive)
        assertEquals(Inconclusive.OLD, scanAnswer(listOf(ap(1, asked - 30_001)), ScanRequest(asked, ScanAsk.CACHE), null, moved, asked).inconclusive)
        // Code QA L2: a result heard more than a minute in the future - the time base isn't boottime: inconclusive.
        assertEquals(Inconclusive.TIME_BASE, answer(listOf(ap(1, now + TIME_BASE_SLACK_MS + 1)), ScanAsk.ANSWERED).inconclusive)
        assertTrue(answer(listOf(ap(1, now + TIME_BASE_SLACK_MS)), ScanAsk.ANSWERED).conclusive)
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
        // Code QA L6: a run belongs to one move - any other motion ends it, a fire after it starts a new one.
        assertEquals(2, inconclusiveTriesNow(2, Motion.MOVED))
        for (m in listOf(Motion.STILL, Motion.STILL_SINCE_TRY, Motion.NO_SENSOR)) assertEquals(m.name, 0, inconclusiveTriesNow(2, m))
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

    @Test
    fun `source - the fingerprint's glue - shadow, the mismatch, the drops, the clears and the dump`() {
        val glue = source("places/WifiPlaces.kt")
        val sync = source("server/MdmSyncWorker.kt")
        // D10, D15: the shadow runs at the attempt's end, the withheld path too; a mismatch cancels a renewal.
        assertEquals(2, Regex("""shadow\(app, store, print, ask, ended, """).findAll(glue).count())
        // Code QA M1: a renewal vetoes the mismatch (recorded, flagged); nothing un-renews.
        assertTrue(glue.contains("if (!ask.renewed) ask.mismatch = clause"))
        assertTrue(glue.contains("StillPrints.record(context, mismatchEvent(print, clause, scan, at, vetoed = ask.renewed))"))
        assertFalse(glue.contains("ask.renewed = false"))
        // Code QA M2: a callback whose results are all older than the request isn't the answer - wait on within 8 s.
        assertTrue(glue.contains("answered = heard.isEmpty() || heard.any { it.elapsedMs > requestAt }"))
        // Code QA L2: the answer's log line.
        assertTrue(glue.contains("\"scan answer \${answer.answering.size}/\${scan.results.size} ask=\${scan.request.ask.wire}\""))
        // Code QA L6: the run reset when the motion isn't MOVED.
        assertTrue(source("server/MdmSyncWorker.kt").contains("val inconclusiveTries = com.kidslauncher.mdm.places.inconclusiveTriesNow(storedTries, motion)"))
        assertTrue(glue.contains("val clause = if (fresh) null else printMismatch(print, scan, cellAway)"))
        // The owner's refinement of H1: the cell witness by the position the table already holds - no lookup for it.
        assertTrue(glue.contains("val cellAway = !fresh && cellElsewhere(material.cells, open(context)?.rows ?: store.rows, now, vouch)"))
        // D6: the shadow never touches the report, the flags or the disagreement count - its only outputs are the ring,
        // the print and ask.mismatch (which only stops a vouch).
        val shadow = Regex("""private fun shadow\([\s\S]*?\n    }\n""").find(glue)!!.value
        for (banned in listOf("notFresh =", "renewed = true", "stillVouch", "disagreement", "ZoneWatch.", "MotionGate.", "countWifiDisagreed")) {
            assertFalse(banned, shadow.contains(banned))
        }
        // D16: the sync ends STILL only on a cap try with no fresh fix; no re-send; the count cleared; D18's D7.
        assertTrue(sync.contains("val mismatch = places.mismatch?.takeIf { capTry && fresh == null }"))
        assertTrue(Regex("""if \(mismatch != null\) \{\s+MotionGate\.scanMismatch\(context, motionNow\.fires, now\)\s+ZoneWatch\.stampStillCapFails\(context, 0\)\s+Log\.i\(ZONE_LOG_TAG, "Location heartbeat: STILL ended by a scan mismatch clause=\$\{mismatch\.wire\}"\)""").containsMatchIn(sync))
        assertTrue(sync.contains("capResend = mismatch == null &&"))
        assertTrue(sync.contains("val vouched = lastFreshQueued?.takeIf { d7Applies(motion, motionNow?.mismatched == true) && it.provider in VOUCHING_PROVIDERS }"))
        assertFalse(sync.contains("mismatch") && Regex("""mismatch[\s\S]{0,80}noteContradiction""").containsMatchIn(sync))
        assertTrue(sync.contains("if (fresh != null) WifiPlaces.afterFreshFix(context, places, fresh.toQueued(LocateCommands.providerOf(fresh)), moving)"))
        // D14: the drops - GLA on, the rule off, distrust, something not fresh reported; P4: the clears with the table.
        assertTrue(source("server/LocateCommands.kt").contains("if (places != null && !WifiPlaces.networkOff(lm)) com.kidslauncher.mdm.places.StillPrints.drop(context, com.kidslauncher.mdm.places.DropReason.GLA)"))
        assertTrue(source("zones/ZoneWatch.kt").contains("com.kidslauncher.mdm.places.StillPrints.drop(app, com.kidslauncher.mdm.places.DropReason.RULE)"))
        assertTrue(source("zones/ZoneWatch.kt").contains("com.kidslauncher.mdm.places.StillPrints.drop(context, com.kidslauncher.mdm.places.DropReason.DISTRUST)"))
        assertTrue(sync.contains("com.kidslauncher.mdm.places.StillPrints.drop(context, com.kidslauncher.mdm.places.DropReason.NOT_FRESH)"))
        assertTrue(Regex("""lastLearned = null\s+// Design 134 P4[^\n]*\n\s+StillPrints\.clear\(context, reason\)""").containsMatchIn(glue))
        val prints = source("places/StillPrints.kt")
        assertTrue(Regex("""fun clear\(context: Context, reason: String\): Unit = synchronized\(lock\) \{[\s\S]{0,200}file\(context, FILE_PRINT\)\.delete\(\)\s+file\(context, FILE_RING\)\.delete\(\)""").containsMatchIn(prints))
        // P1: sealed whole under the secrets key, each file its own AAD.
        assertTrue(prints.contains("Secrets.box.open(name, f.readFully())"))
        assertTrue(prints.contains("Secrets.box.seal(name, bytes)"))
        assertTrue(prints.contains("private const val FILE_PRINT = \"still_print_v1\""))
        assertTrue(prints.contains("private const val FILE_RING = \"still_print_ring_v1\""))
        assertTrue(prints.contains("AtomicFile(File(context.applicationContext.noBackupFilesDir, name))"))
        // D13: the anchor's dump - the record only with the argument; one line without it.
        val anchor = source("server/CommandListenerService.kt")
        assertTrue(anchor.contains("override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {"))
        assertTrue(anchor.contains("com.kidslauncher.mdm.places.StillPrints.dump(applicationContext, writer, args)"))
        assertTrue(Regex("""if \(args\?\.contains\("still-print"\) != true\) \{\s+writer\.println\([^\n]*\)\s+return\s+\}""").containsMatchIn(prints))
        assertEquals(1, Regex("""writer""").findAll(prints.substringAfter("val lines = synchronized(lock) {")).count())
        // P2: no Log line or dump line of the fingerprint takes a BSSID, SSID, key or coordinate.
        val text = prints + source("places/StillPlan.kt")
        var calls = 0
        for (m in Regex("""Log\.\w\(""").findAll(text)) {
            var depth = 1
            var i = m.range.last + 1
            while (depth > 0) depth += when (text[i++]) { '(' -> 1; ')' -> -1; else -> 0 }
            val call = text.substring(m.range.first, i)
            calls++
            assertFalse(call, Regex("""bssid|ssid|keys\.|\.key\b|\.device\b|\.network\b|serving|others|secret|\blat|\blng|latitude|longitude""", RegexOption.IGNORE_CASE).containsMatchIn(call))
        }
        assertTrue(calls >= 8)
        val dumpFn = Regex("""fun dumpLines\([\s\S]*?\n}\n""").find(source("places/StillPlan.kt"))!!.value + Regex("""fun eventText\([\s\S]*?\n}\n""").find(source("places/StillPlan.kt"))!!.value
        assertFalse(dumpFn, Regex("""\.device\b|\.network\b|\.serving\b|\.others\b|\blat\b|\blng\b""").containsMatchIn(dumpFn))
    }
}
