package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.Heartbeat
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.MotionFlags
import com.kidslauncher.mdm.zones.QueuedFix
import com.kidslauncher.mdm.zones.d7Applies
import com.kidslauncher.mdm.zones.heartbeatDue
import com.kidslauncher.mdm.zones.motionContradiction
import com.kidslauncher.mdm.zones.reportStill
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 134's fingerprint, pure: the take (D7, D8), the strict verdict in shadow (D9), the drops (D14), the events,
 * outcomes and the sealed ring (D10-D13), and revision 3's mismatch (D15-D19) - with the note's binding rows 1-12. The
 * levels are a synthetic house's (log-distance, walls in dB, as design 125's town), the positions made up near 0/0.
 */
class StillPrintTest {
    private val minute = 60_000L

    /** Log-distance: -38 dBm at 1 m, exponent 3, less the walls between. */
    private fun level(m: Double, wallsDb: Int = 0): Int = Math.round(-38 - 30 * log10(m)).toInt() - wallsDb

    private fun heard(device: String, dbm: Int, at: Long = 0L, key: String = "$device-a", network: String = "net-$device") =
        Heard(key, device, network, Band.G5, dbm, at)

    private fun cell(key: String, registered: Boolean, radio: String = "lte") =
        Heard(key, key, key, Band.CELL, 0, 0L, kind = RowKind.CELL, wire = "$radio:001:01:100:${(key.hashCode() and 0xffff) + 1}", registered = registered)

    /** Home: our router in the living room, a mesh node, two neighbours' routers we hear every day. */
    private val settled = setOf("router-a", "mesh-a", "nb1-a", "nb2-a")
    private val homeCells = listOf(cell("c-home", true), cell("c-n1", false), cell("c-n2", false))

    private fun homeScan(at: Long = 0L) = listOf(
        heard("router", level(2.5), at),
        heard("mesh", level(6.0, 7), at),
        heard("nb1", level(14.0, 15), at),
        heard("nb2", level(20.0, 22), at),
    )

    private val boot = 7
    private val fixElapsed = 50_000_000L
    private val fixTime = 1_800_000_000_000L - 1_800_000_000_000L % (24 * 60 * minute) + (7 * 60 + 30) * minute

    private fun homePrint(): StillPrint =
        takePrint(fixTime, fixElapsed, boot, fixElapsed + 1_000, (fixTime / minute).toInt(), homeScan(fixElapsed), settled, homeCells)!!

    private fun scan(list: List<Heard>, cells: List<Heard> = homeCells) = printScan(list, settled, cells)

    private fun at(m: Int) = EventAt((fixTime / minute).toInt() + m, fixElapsed + m * minute, boot)

    // 0/0 is no place anyone lives: the vouch and the outcome fixes are metres north of it.
    private fun north(m: Double) = m / 111_195.0
    private val vouch = VouchPoint((fixTime / minute).toInt(), north(0.0), 0.0, 10f)

    private fun fixAt(m: Int, metres: Double, acc: Float = 15f, fixBoot: Int = boot, movedAt: Long = 0L) =
        OutcomeFix(north(metres), 0.0, acc, at(m).atMin, fixBoot, movedAt)

    // --- D7, D8: the take ---

    @Test
    fun `the take - gps or a checked Wi-Fi fix within 50 m, at rest, paired within 30 s, settled devices only`() {
        assertNull(printRefusal("gps", null, 50f, false, false, release = true, conclusive = true))
        assertEquals(PrintRefusal.ACCURACY, printRefusal("gps", null, 51f, false, false, true, true))
        assertEquals(PrintRefusal.ACCURACY, printRefusal("gps", null, null, false, false, true, true))
        assertEquals(PrintRefusal.PROVIDER, printRefusal("fused", null, 5f, false, false, true, true))
        assertEquals(PrintRefusal.PROVIDER, printRefusal(PROVIDER_WIFI, null, 30f, false, false, true, true))
        assertNull(printRefusal(PROVIDER_WIFI, "link", 30f, false, false, true, true))
        assertNull(printRefusal(PROVIDER_APPLE, "gnss", 40f, false, false, true, true))
        assertEquals(PrintRefusal.MOVING, printRefusal("gps", null, 8f, true, false, true, true))
        assertEquals(PrintRefusal.MOCK, printRefusal("gps", null, 8f, false, true, release = true, conclusive = true))
        assertNull(printRefusal("gps", null, 8f, false, true, release = false, conclusive = true))
        assertEquals(PrintRefusal.SCAN, printRefusal("gps", null, 8f, false, false, true, conclusive = false))
        // Paired within +-30 s of the fix: 30 s in, 31 s out.
        val list = listOf(heard("router", -45, fixElapsed + 30_000), heard("mesh", -55, fixElapsed - 31_000))
        val p = takePrint(fixTime, fixElapsed, boot, fixElapsed, 0, list, settled, emptyList())!!
        assertEquals(listOf("router"), p.devices.map { it.device })
        assertNull(takePrint(fixTime, fixElapsed, boot, fixElapsed, 0, listOf(heard("router", -45, fixElapsed + 31_000)), settled, emptyList()))
        // Settled: days >= 2, never moved, not blocked, own Wi-Fi rows only.
        val rows = listOf(
            PlaceRow(RowKind.WIFI, "a", days = 2),
            PlaceRow(RowKind.WIFI, "b", days = 1),
            PlaceRow(RowKind.WIFI, "c", days = 5, moves = 1),
            PlaceRow(RowKind.WIFI, "d", days = 5, blockedUntilMin = 100),
            PlaceRow(RowKind.APPLE, "e", days = 5),
        )
        assertEquals(setOf("a"), settledKeys(rows, nowMin = 50))
        // A device is settled by one AP; its level its strongest AP's, its APs counted; at most 12, the strongest.
        val bands = listOf(heard("router", -50, key = "router-a"), heard("router", -44, key = "router-b"), heard("other", -30, key = "other-a"))
        assertEquals(listOf(PrintDevice("router", "net-router", -44, 2)), settledDevices(bands, setOf("router-a")))
        val many = (1..15).map { heard("d$it", -40 - it) }
        val twelve = takePrint(fixTime, 0, boot, 0, 0, many, many.map { it.key }.toSet(), emptyList())!!
        assertEquals((1..12).map { "d$it" }, twelve.devices.map { it.device })
        // The home print: four devices, the serving cell and its neighbours.
        val home = homePrint()
        assertEquals(listOf("router", "mesh", "nb1", "nb2"), home.devices.map { it.device })
        assertEquals(PrintCells("c-home", "lte", listOf("c-n1", "c-n2")), home.cells)
    }

    @Test
    fun `additions only add - never a device dropped, a level changed or the anchor moved`() {
        val p = homePrint().copy(devices = homePrint().devices.take(3))
        val grown = grownPrint(p, listOf(PrintDevice("router", "n", -90, 1), PrintDevice("new", "n", -60, 1)))
        assertEquals(p.devices + PrintDevice("new", "n", -60, 1), grown.devices)
        assertEquals(p.fixTimeMs, grown.fixTimeMs)
        val full = p.copy(devices = (1..12).map { PrintDevice("d$it", "n", -50, 1) })
        assertEquals(full, grownPrint(full, listOf(PrintDevice("x", "n", -40, 1))))
    }

    // --- D9: the verdict ---

    @Test
    fun `the verdict - each reason in order and its edges`() {
        val p = StillPrint(fixTime, 0, 0, boot, listOf(PrintDevice("a", "n", -45, 1), PrintDevice("b", "n", -58, 1), PrintDevice("c", "n", -70, 1), PrintDevice("d", "n", -80, 1)))
        fun now(vararg d: Pair<String, Int>) = PrintScan(d.map { PrintDevice(it.first, "n", it.second, 1) }, d.map { it.first }.toSet(), d.size, d.maxByOrNull { it.second }?.first)
        assertEquals(PrintReason.NONE, shadowVerdict(p, now("a" to -50, "b" to -60, "c" to -72, "d" to -81), veto = false).reason)
        // thin: two devices.
        assertEquals(PrintReason.THIN, shadowVerdict(p.copy(devices = p.devices.take(2)), now("a" to -45), false).reason)
        // anchor: 15 dB in, 16 out; the strongest now not one of the print's two strongest; the strongest absent.
        assertEquals(PrintReason.NONE, shadowVerdict(p, now("a" to -60, "b" to -58, "c" to -70), false).reason)
        assertEquals(PrintReason.ANCHOR, shadowVerdict(p, now("a" to -61, "b" to -58, "c" to -70), false).reason)
        assertEquals(PrintReason.ANCHOR, shadowVerdict(p, now("a" to -50, "b" to -58, "c" to -40), false).reason)
        assertEquals(PrintReason.ANCHOR, shadowVerdict(p, now("b" to -58, "c" to -70), false).reason)
        // agree: near is -70 or stronger; a -71 device never agrees.
        assertEquals(3, shadowVerdict(p, now("a" to -45, "b" to -58, "c" to -70), false).agree)
        val faint = p.copy(devices = listOf(PrintDevice("a", "n", -45, 1), PrintDevice("b", "n", -71, 1), PrintDevice("c", "n", -75, 1)))
        assertEquals(PrintReason.AGREE, shadowVerdict(faint, now("a" to -45, "b" to -71, "c" to -75), false).reason)
        // similar: the mean difference over the near devices, an absent one 30 dB (5, 2, 30 -> 12.3 > 12).
        val s = shadowVerdict(p.copy(devices = listOf(PrintDevice("a", "n", -45, 1), PrintDevice("b", "n", -58, 1), PrintDevice("x", "n", -65, 1))), now("a" to -50, "b" to -60), false)
        assertEquals(PrintReason.SIMILAR, s.reason)
        assertEquals(37.0 / 3, s.meanDiffDb!!, 1e-9)
        // veto last.
        assertEquals(PrintReason.VETO, shadowVerdict(p, now("a" to -50, "b" to -60, "c" to -70), veto = true).reason)
        // J: the linear weight max(0, dBm + 100), no clamp - {a:50, b:30} against {a:40, c:20}: 40 / 100.
        val j = shadowVerdict(StillPrint(0, 0, 0, 0, listOf(PrintDevice("a", "n", -50, 1), PrintDevice("b", "n", -70, 1))), now("a" to -60, "c" to -80), false).j
        assertEquals(0.4, j, 1e-9)
    }

    // --- Binding rows ---

    @Test
    fun `row 1 - the Jelly Star's day`() {
        val p = homePrint()
        // 15:31: the cap try while STILL with a list older than the request - inconclusive: part A's cap fail, no
        // shadow event (the still tier records nothing) and no mismatch (row 11).
        val stale = scanAnswer(listOf(ScanAp("00:00:5e:00:53:01", "x", null, 5180, -80, 100)), ScanRequest(200, ScanAsk.ANSWERED), null, 0, 300)
        assertEquals(Inconclusive.OLD, stale.inconclusive)
        // 16:01 after the walk to the kitchen, the request refused: an inconclusive event.
        var ring = ringWith(PrintRing(), inconclusiveEvent(p, Inconclusive.REFUSED, at(511)), at(511).atMin)
        // 16:31 a fresh answer in the kitchen (the router and the mesh node 4 m off): would renew.
        val kitchen = scan(listOf(heard("router", level(4.0)), heard("mesh", level(4.0)), heard("nb1", level(12.0, 15)), heard("nb2", level(22.0, 22))))
        val v = shadowVerdict(p, kitchen, veto = false)
        assertTrue(v.toString(), v.would)
        ring = ringWith(ring, verdictEvent(p, v, at(541)), at(541).atMin)
        assertEquals("verdict would=yes j=0.81 diff=9.0 devices=4/4 near=2/2 agree=2", eventText(ring.events.last()))
        // 18:24 a fresh fix at the same place: confirmed; the inconclusive event noted.
        ring = ringAfterFix(ring, fixAt(654, 20.0), vouch, p.fixMin)
        assertEquals(listOf(OutcomeClass.NOTED, OutcomeClass.CONFIRMED), ring.events.map { it.outcome })
        assertEquals("confirmed d=20 after=113 acc<=25", outcomeText(ring.events.last()))
    }

    @Test
    fun `rows 2 and 3 - the neighbour's house and the adjacent flat`() {
        val p = homePrint()
        // QA H1: next door, 25 m off - our router 18 dB weaker, their own (unsettled) router strongest.
        val router = p.devices.first { it.device == "router" }.dbm
        val nextDoor = listOf(heard("theirs", level(3.0)), heard("router", router - 18), heard("mesh", level(20.0, 22)), heard("nb1", level(10.0, 7)))
        assertEquals(PrintReason.ANCHOR, shadowVerdict(p, scan(nextDoor), false).reason)
        // Their router off, ours still 18 dB weaker: anchor (the 15 dB on the print's strongest).
        assertEquals(PrintReason.ANCHOR, shadowVerdict(p, scan(nextDoor.drop(1)), false).reason)
        // An adjacent flat through one concrete wall, 8 m off, with its own router: anchor.
        val flat = listOf(heard("flat", level(2.0)), heard("router", router - 15), heard("mesh", level(9.0, 20)), heard("nb1", level(12.0, 15)))
        assertEquals(PrintReason.ANCHOR, shadowVerdict(p, scan(flat), false).reason)
        // Without a router of its own, ours 16 dB down: would not.
        assertFalse(shadowVerdict(p, scan(listOf(heard("router", router - 16), heard("mesh", level(9.0, 20)))), false).would)
        // A light wall within 15 dB: a recorded would-renew (the known limit under 15 m) - the data judges it.
        val light = scan(listOf(heard("router", router - 10), heard("mesh", p.devices[1].dbm - 6), heard("nb1", level(14.0, 15))))
        assertTrue(shadowVerdict(p, light, false).would)
    }

    @Test
    fun `row 4 - a car router is never settled, a print of it is thin`() {
        // Seen at home and at the school: it moved, so its row has a move and never makes a device settled.
        val rows = listOf(PlaceRow(RowKind.WIFI, "car-a", days = 9, moves = 1), PlaceRow(RowKind.WIFI, "router-a", days = 9))
        val keys = settledKeys(rows, 0)
        val p = takePrint(fixTime, 0, boot, 0, 0, listOf(heard("car", -35), heard("router", -50)), keys, emptyList())!!
        assertEquals(listOf("router"), p.devices.map { it.device })
        // In the car 2 km on: would not (thin).
        assertEquals(PrintReason.THIN, shadowVerdict(p, printScan(listOf(heard("car", -35)), keys, emptyList()), false).reason)
        assertNull(printMismatch(p, printScan(listOf(heard("x", -60), heard("y", -62), heard("z", -70)), keys, emptyList())))
    }

    @Test
    fun `rows 5 and 8 - a boot drops the print, the ring's pending events survive and are unjudged`() {
        val p = homePrint()
        assertEquals(DropReason.BOOT, printStale(p, boot + 1, p.takenElapsedMs + minute))
        assertNull(printStale(p, boot, p.takenElapsedMs + PRINT_LIFE_MS - 1))
        assertEquals(DropReason.EXPIRED, printStale(p, boot, p.takenElapsedMs + PRINT_LIFE_MS))
        // Power off for an hour at home after an event: the outcome after the boot is unjudged, its distance kept.
        val ring = ringWith(PrintRing(), verdictEvent(p, shadowVerdict(p, scan(homeScan()), false), at(60)), at(60).atMin)
        val after = ringAfterFix(ring, fixAt(150, 15.0, fixBoot = boot + 1), vouch, p.fixMin)
        val e = after.events.single()
        assertEquals(OutcomeClass.UNJUDGED, e.outcome)
        assertTrue(e.movedAfter)
        assertEquals(2, e.distance10)
    }

    @Test
    fun `rows 6 and 9 - the cabin and the friend's house with no saved network`() {
        // The cabin: one AP, a fresh answer - conclusive, no second scan; a print there is thin.
        val one = scanAnswer(listOf(ScanAp("00:00:5e:00:53:01", "Hytta", null, 2437, -60, 1_500)), ScanRequest(1_000, ScanAsk.ANSWERED), null, 0, 2_000)
        assertTrue(one.conclusive)
        assertFalse(secondScanWorth(true, one, false, 5, true))
        val cabin = takePrint(fixTime, 0, boot, 0, 0, listOf(heard("cabin", -60)), setOf("cabin-a"), emptyList())!!
        assertEquals(PrintReason.THIN, shadowVerdict(cabin, printScan(listOf(heard("cabin", -60)), setOf("cabin-a"), emptyList()), false).reason)
        // A friend's house, not connected: five unknown APs, a fresh answer - conclusive, no second scan.
        val five = (1..5).map { ScanAp("00:00:5e:00:54:%02x".format(it), "F$it", null, 5180, -60 - it, 1_500) }
        assertTrue(scanAnswer(five, ScanRequest(1_000, ScanAsk.ANSWERED), null, 0, 2_000).conclusive)
        // Connected to a metered phone hotspot (locally administered, never usable): its BSSID must be in the answer.
        val hotspot = "06:00:5e:00:55:01"
        assertEquals(Inconclusive.NO_CONNECTED, scanAnswer(five, ScanRequest(1_000, ScanAsk.ANSWERED), hotspot, 0, 2_000).inconclusive)
        assertTrue(scanAnswer(five + ScanAp(hotspot, "Phone", null, 2437, -40, 1_600), ScanRequest(1_000, ScanAsk.ANSWERED), hotspot, 0, 2_000).conclusive)
    }

    @Test
    fun `row 7 - a would-renew followed by failed re-checks stays unjudged and never vouches`() {
        val p = homePrint()
        val v = shadowVerdict(p, scan(homeScan()), false)
        assertTrue(v.would)
        val ring = ringWith(PrintRing(), verdictEvent(p, v, at(60)), at(60).atMin)
        // No fresh fix within 24 h: unjudged when the ring is next touched.
        assertEquals(OutcomeClass.PENDING, ringAged(ring, at(60 + OUTCOME_WAIT_MIN).atMin).events.single().outcome)
        assertEquals(OutcomeClass.UNJUDGED, ringAged(ring, at(61 + OUTCOME_WAIT_MIN).atMin).events.single().outcome)
        // A fix later than that judges nothing either.
        assertEquals(OutcomeClass.UNJUDGED, ringAfterFix(ring, fixAt(61 + OUTCOME_WAIT_MIN, 10.0), vouch, p.fixMin).events.single().outcome)
        // The verdict is shadow-only: no still from it - after a move the motion is MOVED, and reportStill says no.
        val moved = MotionFlags().moved(1).motion(sensor = true, anchor = true)
        assertFalse(reportStill(true, com.kidslauncher.mdm.server.LocationAction.CACHED, moved, fixTime + minute, fixTime))
    }

    @Test
    fun `rows 10 to 12 - a scan mismatch ends STILL, the Jelly's poor scan and an AP reboot don't`() {
        val p = homePrint()
        // Row 10: the friend's house 3 km off after a car ride the sensor missed - five unknown APs: a wifi mismatch.
        val friends = scan((1..5).map { heard("f$it", -55 - 3 * it) }, cells = listOf(cell("c-far", true), cell("c-far2", false)))
        assertEquals(MismatchClause.WIFI, printMismatch(p, friends))
        val e = mismatchEvent(p, MismatchClause.WIFI, friends, at(241))
        assertEquals("mismatch clause=wifi devices=0/4", eventText(e))
        // STILL ends: moved since the fresh fix, not since the try - no still, and the next try runs as after a fire.
        val interval = 30 * minute
        val tryAt = fixTime + 241 * minute
        val still = MotionFlags().afterTry(0, fixTime, heartbeat = true, nowMs = fixTime)
        assertEquals(Motion.STILL, still.motion(true, true))
        val ended = still.scanMismatch(still.fires, tryAt)
        assertEquals(Motion.STILL_SINCE_TRY, ended.motion(true, true))
        assertFalse(reportStill(true, com.kidslauncher.mdm.server.LocationAction.CACHED, ended.motion(true, true), tryAt + interval, fixTime))
        assertEquals(Heartbeat.FIX, heartbeatDue(tryAt + interval, tryAt, interval, ended.motion(true, true), fixTime, null, tryAt, ended.stillSinceMs))
        assertEquals(Heartbeat.QUIET, heartbeatDue(tryAt + interval / 2 + interval, tryAt + interval / 2, interval, Motion.STILL_SINCE_TRY, fixTime, null, tryAt + interval, ended.stillSinceMs))
        // A fire during the attempt: nothing (the fire already says moved).
        assertEquals(still.moved(1), still.moved(1).scanMismatch(still.fires, tryAt))
        // D18: not a contradiction itself, but the next fresh fix 3 km off with no fire since is D7's evidence.
        assertTrue(d7Applies(ended.motion(true, true), ended.mismatchSinceFresh))
        assertFalse(d7Applies(Motion.STILL_SINCE_TRY, ended.moved(2).mismatchSinceFresh))
        assertFalse(ended.afterTry(ended.fires, tryAt + interval, true, tryAt + interval).mismatchSinceFresh)
        val vouchFix = QueuedFix(north(0.0), 0.0, 10f, fixTime, "gps")
        assertTrue(motionContradiction(vouchFix, QueuedFix(north(3_000.0), 0.0, 12f, tryAt + interval, "gps")))
        // Its outcome: the GPS fix 3 km off - the mismatch was right (confirmed); within reach it was wrong.
        val ring = ringWith(PrintRing(), e, e.atMin)
        assertEquals(OutcomeClass.CONFIRMED, ringAfterFix(ring, fixAt(272, 3_000.0, 12f), vouch, p.fixMin).events.single().outcome)
        assertEquals(OutcomeClass.CONTRADICTED, ringAfterFix(ring, fixAt(272, 30.0, 12f), vouch, p.fixMin).events.single().outcome)
        assertEquals(OutcomeClass.UNJUDGED, ringAfterFix(ring, fixAt(272, 3_000.0, movedAt = (fixElapsed + 250 * minute)), vouch, p.fixMin).events.single().outcome)
        // Row 11: the Jelly Star's day - a poor scan older than the request is inconclusive (no mismatch is asked);
        // a fresh answer of one AP that isn't the print's is too thin to say elsewhere; four home devices: none.
        assertNull(printMismatch(p, scan(listOf(heard("x", -80)))))
        assertNull(printMismatch(p, scan(homeScan())))
        // Row 12: one device rebooting, the others heard - no mismatch; all four gone, three neighbours heard - wifi.
        assertNull(printMismatch(p, scan(homeScan().filter { it.device != "router" })))
        assertEquals(MismatchClause.WIFI, printMismatch(p, scan(listOf(heard("x", -60), heard("y", -70), heard("z", -75)))))
        // Guards: a print of fewer than 3 devices never decides; the cell clause only on the same radio, none shared.
        assertNull(printMismatch(p.copy(devices = p.devices.take(2)), friends))
        val homeWifi = homeScan()
        assertEquals(MismatchClause.CELL, printMismatch(p, scan(homeWifi, listOf(cell("c-x", true), cell("c-y", false)))))
        assertNull(printMismatch(p, scan(homeWifi, listOf(cell("c-x", true, radio = "nr"), cell("c-y", false)))))
        assertNull(printMismatch(p, scan(homeWifi, listOf(cell("c-x", true), cell("c-n2", false)))))
        assertNull(printMismatch(p, scan(homeWifi, listOf(cell("c-x", false)))))
        assertNull(printMismatch(p.copy(cells = PrintCells()), scan(homeWifi, listOf(cell("c-x", true)))))
    }

    // --- D11, D12: outcomes and the ring ---

    @Test
    fun `outcomes - would-not keeps its distance, only fixes within 100 m judge`() {
        val p = homePrint()
        val no = verdictEvent(p, shadowVerdict(p, scan(listOf(heard("theirs", -40))), false), at(30))
        val ring = ringWith(PrintRing(), no, no.atMin)
        assertEquals(OutcomeClass.PENDING, ringAfterFix(ring, fixAt(40, 10.0, acc = 101f), vouch, p.fixMin).events.single().outcome)
        val judged = ringAfterFix(ring, fixAt(40, 10.0), vouch, p.fixMin).events.single()
        assertEquals(OutcomeClass.WOULD_NOT, judged.outcome)
        assertTrue(judged.within)
        assertEquals("would-not d=10 after=10 acc<=25 within", outcomeText(judged))
        // Another vouch than the event's print's: no distance - unjudged; the print gone first: unjudged.
        assertEquals(OutcomeClass.UNJUDGED, ringAfterFix(ring, fixAt(40, 10.0), vouch.copy(timeMin = 1), p.fixMin).events.single().outcome)
        assertEquals(OutcomeClass.UNJUDGED, ringAfterFix(ring, fixAt(40, 10.0), vouch, null).events.single().outcome)
    }

    @Test
    fun `the ring - 500 at most, 60 days, the codecs round trip and another version is dropped`() {
        val p = homePrint()
        val base = verdictEvent(p, shadowVerdict(p, scan(homeScan()), false), at(0))
        var ring = PrintRing()
        for (i in 0..500) ring = ringWith(ring, base.copy(atMin = base.atMin + i), base.atMin + i)
        assertEquals(RING_MAX, ring.events.size)
        assertEquals(base.atMin + 1, ring.events.first().atMin)
        assertEquals(0, ringWith(PrintRing(listOf(base)), base.copy(atMin = base.atMin + RING_KEEP_MIN + 1), base.atMin + RING_KEEP_MIN + 1).events.count { it.atMin == base.atMin })
        // The codecs.
        val full = ringDropped(ringDropped(ringTaken(ring), DropReason.BOOT), DropReason.BOOT).copy(
            events = ring.events.dropLast(2) + mismatchEvent(p, MismatchClause.CELL, scan(homeScan()), at(5)) +
                ringAfterFix(PrintRing(listOf(inconclusiveEvent(p, Inconclusive.NO_CONNECTED, at(6)))), fixAt(9, 40.0), vouch, p.fixMin).events,
        )
        assertEquals(full, decodeRing(encodeRing(full)))
        assertTrue(encodeRing(full).size < 20_000)
        assertEquals(p, decodePrint(encodePrint(p)))
        assertTrue(encodePrint(p.copy(devices = (1..12).map { PrintDevice("%016x".format(it), "%016x".format(it), -50, 2) })).size < 1024)
        val bumped = encodePrint(p).also { it[3] = 2 }
        assertNull(decodePrint(bumped))
        assertNull(decodeRing(encodePrint(p)))
        assertNull(decodePrint(ByteArray(5)))
        assertEquals(2, full.drops[DropReason.BOOT])
    }

    @Test
    fun `the dump - the summary and the ring newest first, never a key`() {
        val p = homePrint()
        var ring = ringTaken(PrintRing())
        ring = ringWith(ring, inconclusiveEvent(p, Inconclusive.OLD, at(10)), at(10).atMin)
        ring = ringWith(ring, verdictEvent(p, shadowVerdict(p, scan(homeScan()), false), at(20)), at(20).atMin)
        ring = ringWith(ring, mismatchEvent(p, MismatchClause.WIFI, scan(listOf(heard("x", -60))), at(30)), at(30).atMin)
        ring = ringDropped(ring, DropReason.REPLACED)
        val lines = dumpLines(p, ring, at(40).atMin, fixElapsed + 41 * minute)
        assertEquals("still-print (design 134): print devices=4 cells=3 age=40 min", lines[0])
        assertTrue(lines[1], lines[1].startsWith("prints taken=1 dropped boot=0 gla=0 rule=0 distrust=0 replaced=1"))
        assertEquals("verdicts=1 would=yes confirmed=0 contradicted=0 unjudged=0 pending=1", lines[2])
        assertTrue(lines[4], lines[4].startsWith("inconclusive=1 refused=0 old=1"))
        assertTrue(lines[5], lines[5].startsWith("mismatches=1 wifi=1 cell=0"))
        assertTrue(lines[7], lines[7].contains(" mismatch clause=wifi devices=0/4 -> pending"))
        assertTrue(lines.last(), lines.last().contains(" inconclusive reason=old"))
        val text = lines.joinToString("\n")
        for (secret in p.devices.flatMap { listOf(it.device, it.network) } + listOf("c-home", "c-n1")) assertFalse(secret, text.contains(secret))
        assertEquals("still-print (design 134): no print", dumpLines(null, PrintRing(), 0, 0)[0])
        assertNotNull(lines)
    }
}
