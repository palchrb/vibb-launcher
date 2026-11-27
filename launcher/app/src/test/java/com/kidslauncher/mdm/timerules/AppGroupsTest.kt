package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ClockTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Design 139 §4.1 (revision 5): the fold of the usage events, by §4.1's sequence table and §7's rows. */
class AppGroupsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Oslo")
    private val x = "org.example.game"
    private val y = "org.example.other"
    private val home = "me.vibb.launcher"
    private val android = "android"
    private val g = FoldGroup(1, setOf(x))
    private val gy = FoldGroup(2, setOf(y))
    private val gxy = FoldGroup(3, setOf(x, y))

    private fun ev(t: Long, pkg: String, kind: UsageKind, cls: String? = "$pkg.A") =
        UsageEv(t, pkg, if (pkg == android) null else cls, kind)

    private fun dev(t: Long, kind: UsageKind) = UsageEv(t, android, null, kind)

    private val unlocked = FoldCarry(screenOn = true, keyguardHidden = true)

    private fun carryOn(vararg pkgs: String) = unlocked.copy(on = pkgs.map { OnPackage(it) })

    /** A plain read of `[from, to)` in one boot, no cap. */
    private fun plan(from: Long, to: Long, rebooted: Boolean = false) =
        ReadPlan(from, from, to, to, first = false, stepBackMs = 0, rebooted = rebooted, capMs = Long.MAX_VALUE)

    private fun fold(
        events: List<UsageEv>,
        carry: FoldCarry,
        from: Long = 0,
        to: Long = 100_000,
        groups: List<FoldGroup> = listOf(g),
        live: Boolean = false,
        count: Boolean = true,
        rebooted: Boolean = false,
    ) = groupScreenMs(events, plan(from, to, rebooted), carry, groups, live, count)

    private fun FoldOut.ms(id: Long = 1): Long = spans[id].orEmpty().sumOf { it.last - it.first + 1 }

    private fun FoldOut.onPkgs(): Set<String> = carry.on.map { it.pkg }.toSet()

    // ---- §4.1's sequence table --------------------------------------------------------------------------------------

    @Test
    fun `open and close - on from the RESUMED, on-paused 0,4 s, off at the STOPPED`() {
        val out = fold(
            listOf(
                ev(1_000, home, UsageKind.PAUSED), ev(1_001, x, UsageKind.RESUMED),
                ev(61_001, x, UsageKind.PAUSED), ev(61_002, home, UsageKind.RESUMED), ev(61_401, x, UsageKind.STOPPED),
            ),
            carryOn(home),
        )
        assertEquals(60_400, out.ms())
        assertEquals(setOf(home), out.onPkgs())
    }

    @Test
    fun `a same-class relaunch stays on`() {
        for (gap in listOf(1L, 13L)) {
            val out = fold(
                listOf(ev(10_000, x, UsageKind.PAUSED), ev(10_000 + gap, x, UsageKind.RESUMED), ev(10_000 + gap + 550, x, UsageKind.STOPPED)),
                carryOn(x),
            )
            assertEquals("gap $gap", 100_000, out.ms())
            assertEquals(setOf(x), out.onPkgs())
        }
    }

    @Test
    fun `a kill with Vibb One or another app below ends it at the STOPPED`() {
        for ((below, gap) in listOf(home to 0L, home to 5L, y to 0L)) {
            val out = fold(listOf(ev(20_000, x, UsageKind.STOPPED), ev(20_000 + gap, below, UsageKind.RESUMED)), carryOn(x))
            assertEquals("$below +$gap", 20_000, out.ms())
            assertFalse(x in out.onPkgs())
        }
    }

    @Test
    fun `am crash over another app counts on-paused until its late STOPPED - accepted`() {
        val out = fold(
            listOf(ev(20_000, x, UsageKind.PAUSED), ev(20_001, y, UsageKind.RESUMED), ev(30_600, x, UsageKind.STOPPED)),
            carryOn(x),
        )
        assertEquals(30_600, out.ms())
        assertEquals(setOf(y), out.onPkgs())
    }

    @Test
    fun `split screen counts once for one group and both end at their STOPPEDs`() {
        val stops = listOf(
            ev(60_000, x, UsageKind.PAUSED), ev(60_000, y, UsageKind.PAUSED), ev(60_001, home, UsageKind.RESUMED),
            ev(60_400, x, UsageKind.STOPPED), ev(60_410, y, UsageKind.STOPPED),
        )
        // 16: both RESUMED in the same ms.
        val on16 = fold(listOf(ev(10_000, x, UsageKind.RESUMED), ev(10_000, y, UsageKind.RESUMED)) + stops, carryOn(home), groups = listOf(gxy))
        assertEquals(50_410, on16.ms(3))
        // 14: no event for the one already on.
        val on14 = fold(listOf(ev(10_000, x, UsageKind.RESUMED)) + stops, carryOn(y), groups = listOf(gxy, gy))
        assertEquals(60_410, on14.ms(3))
        assertEquals(60_410, on14.ms(2))
        assertEquals(setOf(home), on14.onPkgs())
    }

    @Test
    fun `PiP across screen off and on - 14's order`() {
        val out = fold(
            listOf(
                ev(5_000, x, UsageKind.PAUSED, "$x.B"),
                dev(20_000, UsageKind.SCREEN_NON_INTERACTIVE), dev(20_001, UsageKind.KEYGUARD_SHOWN), ev(20_002, x, UsageKind.STOPPED, "$x.B"),
                dev(40_000, UsageKind.SCREEN_INTERACTIVE), dev(41_000, UsageKind.KEYGUARD_HIDDEN), ev(41_022, x, UsageKind.PAUSED, "$x.B"),
            ),
            carryOn(x),
        )
        assertEquals(20_000 + (100_000 - 41_022), out.ms())
        assertTrue(out.carry.on.single().paused)
    }

    @Test
    fun `PiP across screen off and on - 16's order counts from KEYGUARD_HIDDEN`() {
        val out = fold(
            listOf(
                ev(1_000, home, UsageKind.RESUMED), ev(1_001, x, UsageKind.PAUSED, "$x.B"),
                dev(20_000, UsageKind.SCREEN_NON_INTERACTIVE), ev(20_001, x, UsageKind.STOPPED, "$x.B"), dev(20_002, UsageKind.KEYGUARD_SHOWN),
                dev(40_000, UsageKind.SCREEN_INTERACTIVE), ev(40_500, home, UsageKind.RESUMED), ev(40_501, x, UsageKind.PAUSED, "$x.B"),
                dev(41_000, UsageKind.KEYGUARD_HIDDEN),
            ),
            carryOn(x),
        )
        assertEquals(20_000 + (100_000 - 41_000), out.ms())
    }

    @Test
    fun `suspended in front with the kiosk off - on-paused, then off`() {
        val out = fold(
            listOf(ev(30_000, x, UsageKind.PAUSED), ev(30_000, y, UsageKind.RESUMED), ev(30_350, x, UsageKind.STOPPED)),
            carryOn(x),
        )
        assertEquals(30_350, out.ms())
    }

    @Test
    fun `a reboot - off at the shutdown, counts again from KEYGUARD_HIDDEN`() {
        val out = fold(
            listOf(
                ev(10_000, home, UsageKind.PAUSED), dev(10_020, UsageKind.DEVICE_SHUTDOWN),
                dev(50_000, UsageKind.SCREEN_INTERACTIVE), dev(50_001, UsageKind.KEYGUARD_SHOWN), dev(50_100, UsageKind.DEVICE_STARTUP),
                dev(60_000, UsageKind.KEYGUARD_HIDDEN), ev(60_100, x, UsageKind.RESUMED),
            ),
            carryOn(x),
            rebooted = true,
        )
        assertEquals(10_020 + (100_000 - 60_100), out.ms())
    }

    @Test
    fun `activity A to B then PiP from B - A's late STOPPED is ignored, B's ends it (QA M1)`() {
        val out = fold(
            listOf(
                ev(1_000, x, UsageKind.RESUMED, "$x.A"),
                ev(10_000, x, UsageKind.PAUSED, "$x.A"), ev(10_005, x, UsageKind.RESUMED, "$x.B"),
                ev(10_300, home, UsageKind.RESUMED), ev(10_301, x, UsageKind.PAUSED, "$x.B"),
                ev(10_700, x, UsageKind.STOPPED, "$x.A"),
                ev(70_000, x, UsageKind.STOPPED, "$x.B"),
            ),
            carryOn(home),
        )
        assertEquals(69_000, out.ms())
    }

    @Test
    fun `a reboot with a Child PIN - DEVICE_STARTUP after the RESUMED changes nothing (QA H2)`() {
        val out = fold(
            listOf(
                dev(5_000, UsageKind.DEVICE_SHUTDOWN),
                dev(20_000, UsageKind.SCREEN_INTERACTIVE), dev(20_001, UsageKind.KEYGUARD_SHOWN),
                dev(30_000, UsageKind.KEYGUARD_HIDDEN), ev(30_010, home, UsageKind.RESUMED), ev(31_000, x, UsageKind.RESUMED),
                dev(32_000, UsageKind.DEVICE_STARTUP),
            ),
            unlocked,
            rebooted = true,
        )
        assertEquals(100_000 - 31_000, out.ms())
    }

    @Test
    fun `a wake within the lock delay counts from the RESUMED (QA M2)`() {
        val out = fold(
            listOf(dev(20_000, UsageKind.SCREEN_NON_INTERACTIVE), dev(25_000, UsageKind.SCREEN_INTERACTIVE), ev(25_050, x, UsageKind.RESUMED)),
            carryOn(x),
        )
        assertEquals(20_000 + (100_000 - 25_050), out.ms())
    }

    @Test
    fun `a new boot count without a shutdown in the read resets at from, with one the old boot counts up to it (QA L3)`() {
        val after = listOf(dev(40_000, UsageKind.SCREEN_INTERACTIVE), dev(41_000, UsageKind.KEYGUARD_HIDDEN), ev(42_000, x, UsageKind.RESUMED))
        val crash = fold(after, carryOn(x), rebooted = true)
        assertEquals(100_000 - 42_000, crash.ms())
        val normal = fold(listOf(dev(3_000, UsageKind.DEVICE_SHUTDOWN)) + after, carryOn(x), rebooted = true)
        assertEquals(3_000 + (100_000 - 42_000), normal.ms())
        // Same boot, no reset: the carried app counts throughout.
        assertEquals(100_000, fold(emptyList(), carryOn(x)).ms())
    }

    // ---- The kill rule's edges (QA L1) ------------------------------------------------------------------------------

    @Test
    fun `the kill rule's partner window`() {
        val stop = ev(20_000, x, UsageKind.STOPPED)
        val cases = listOf(
            "same ms after" to listOf(stop, ev(20_000, y, UsageKind.RESUMED)),
            "same ms before" to listOf(ev(20_000, y, UsageKind.RESUMED), stop),
            "+50 ms" to listOf(stop, ev(20_050, y, UsageKind.RESUMED)),
        )
        for ((name, events) in cases) assertEquals(name, 20_000, fold(events, carryOn(x)).ms())
        val ignored = listOf(
            "+51 ms" to listOf(stop, ev(20_051, y, UsageKind.RESUMED)),
            "-1 ms" to listOf(ev(19_999, y, UsageKind.RESUMED), stop),
            "no partner (a relaunch's late STOPPED)" to listOf(stop),
            "its own RESUMED" to listOf(stop, ev(20_010, x, UsageKind.RESUMED, "$x.B")),
        )
        for ((name, events) in ignored) assertEquals(name, 100_000, fold(events, carryOn(x)).ms())
    }

    @Test
    fun `an on-paused package ends only at the STOPPED of its PAUSED's class (QA M1)`() {
        val paused = unlocked.copy(on = listOf(OnPackage(x, paused = true, pausedClass = "$x.B")))
        assertEquals(100_000, fold(listOf(ev(20_000, x, UsageKind.STOPPED, "$x.A")), paused).ms())
        assertEquals(20_000, fold(listOf(ev(20_000, x, UsageKind.STOPPED, "$x.B")), paused).ms())
    }

    @Test
    fun `the order queryEvents gave is kept, never re-sorted (QA L5)`() {
        val relaunch = listOf(ev(10_000, x, UsageKind.PAUSED), ev(10_000, x, UsageKind.RESUMED), ev(10_550, x, UsageKind.STOPPED))
        assertEquals(100_000, fold(relaunch, carryOn(x)).ms())
        // Swapped, the app is left on-paused and its old STOPPED ends it.
        val swapped = listOf(relaunch[1], relaunch[0], relaunch[2])
        assertEquals(10_550, fold(swapped, carryOn(x)).ms())
    }

    // ---- A pending STOPPED across reads (QA L6) ---------------------------------------------------------------------

    @Test
    fun `a STOPPED at a read's end stays pending until a fold's to passes its window`() {
        for (partner in listOf(true, false)) {
            val one = fold(listOf(ev(980, x, UsageKind.STOPPED)), carryOn(x), from = 0, to = 1_000)
            assertEquals(1_000, one.ms())
            assertEquals(listOf(PendingStop(x, 980)), one.carry.pending)
            // 30 ms later: the window (to 1030, inclusive) still reaches `to`.
            val two = fold(emptyList(), one.carry, from = 1_000, to = 1_030)
            assertEquals(30, two.ms())
            assertEquals(one.carry.pending, two.carry.pending)
            val three = fold(if (partner) listOf(ev(1_030, y, UsageKind.RESUMED)) else emptyList(), two.carry, from = 1_030, to = 5_000)
            assertTrue(three.carry.pending.isEmpty())
            assertEquals("partner $partner", if (partner) 0L else 3_970L, three.ms())
            assertEquals(!partner, x in three.onPkgs())
        }
    }

    // ---- The read window (QA H1) ------------------------------------------------------------------------------------

    @Test
    fun `the read ends SETTLE_MS before now, begin inclusive and end exclusive`() {
        val point = FoldPoint(wallMs = 10_000, elapsedMs = 1_000, bootCount = 3, offsetMs = 9_000)
        val p = planRead(point, nowWall = 20_000, nowElapsed = 11_000, bootCount = 3, checkedClock = true)!!
        assertEquals(10_000, p.queryFrom)
        assertEquals(18_000, p.to)
        assertEquals(9_000, p.toElapsed)
        assertEquals(8_000, p.capMs)
        // `to` not after `from`: nothing to read.
        assertNull(planRead(point, nowWall = 12_000, nowElapsed = 3_000, bootCount = 3, checkedClock = true))

        val atFrom = ev(10_000, x, UsageKind.RESUMED)
        val atTo = ev(18_000, x, UsageKind.STOPPED, "$x.Z")
        val first = groupScreenMs(listOf(atFrom, atTo), p, unlocked, listOf(g), live = false, count = true)
        assertEquals(8_000, first.ms())
        assertEquals(listOf(10_000L), first.appliedKeys.map { it.first })
        val next = groupScreenMs(listOf(atTo, ev(18_000, y, UsageKind.RESUMED)), plan(18_000, 20_000), first.carry, listOf(g), live = false, count = true)
        assertEquals(0, next.ms())
    }

    // ---- The device state (QA H2, M2) -------------------------------------------------------------------------------

    @Test
    fun `DEVICE_STARTUP changes nothing, and the live check turns the count on at to`() {
        assertEquals(100_000, fold(listOf(dev(50_000, UsageKind.DEVICE_STARTUP)), carryOn(x)).ms())

        // 16 orders a PiP's PAUSED before KEYGUARD_HIDDEN: a state that missed the unlock counts nothing...
        val missed = FoldCarry(on = listOf(OnPackage(x)), screenOn = true, keyguardHidden = false)
        val one = fold(emptyList(), missed, from = 0, to = 1_000, live = true)
        assertEquals(0, one.ms())
        assertTrue(one.carry.screenOn && one.carry.keyguardHidden)
        // ...until `to`, from where it counts.
        assertEquals(1_000, fold(emptyList(), one.carry, from = 1_000, to = 2_000).ms())
        // The screen-off fold (outside the gate) changes nothing.
        assertFalse(fold(emptyList(), missed, live = false).carry.keyguardHidden)
    }

    // ---- The first fold and the setup hold (QA M3, D10) -------------------------------------------------------------

    @Test
    fun `the first fold fills carry from 3 h back and adds nothing`() {
        val now = 50_000_000L
        val p = planRead(null, now, 40_000_000, 4, checkedClock = true)!!
        assertTrue(p.first)
        assertEquals(now - SETTLE_MS - FIRST_FOLD_LOOKBACK_MS, p.queryFrom)
        assertEquals(0, p.capMs)
        val events = listOf(
            dev(p.queryFrom + 1_000, UsageKind.SCREEN_INTERACTIVE), dev(p.queryFrom + 2_000, UsageKind.KEYGUARD_HIDDEN),
            ev(p.queryFrom + 3_000, x, UsageKind.RESUMED), ev(p.queryFrom + 3_000, y, UsageKind.RESUMED),
        )
        // The carry handed in is ignored on a first fold.
        val out = groupScreenMs(events, p, carryOn("org.example.stale"), listOf(g), live = true, count = false)
        assertTrue(out.spans.isEmpty())
        assertEquals(setOf(x, y), out.onPkgs())
        val ledger = applyFold(ledgerAt(p.to), p, out, clocksAt(now, 40_000_000), zone, 4, count = false)
        assertTrue(ledger.groupUsedMs.isEmpty())
        assertEquals(p.to, ledger.foldPoint?.wallMs)
        // A group added later reads its app's state from the carry at once.
        val later = fold(emptyList(), out.carry, from = p.to, to = p.to + 60_000, groups = listOf(gy))
        assertEquals(60_000, later.ms(2))
    }

    @Test
    fun `the setup hold moves the state and adds nothing`() {
        val out = fold(listOf(ev(10_000, x, UsageKind.RESUMED)), unlocked, count = false)
        assertTrue(out.spans.isEmpty())
        assertEquals(setOf(x), out.onPkgs())
        val p = plan(0, 100_000).copy(toElapsed = 100_000, capMs = 100_000)
        val ledger = applyFold(ledgerAt(100_000), p, out, clocksAt(102_000, 102_000), zone, 1, count = false)
        assertTrue(ledger.groupUsedMs.isEmpty())
        assertEquals(out.carry, ledger.foldPoint?.carry)
    }

    // ---- A backward clock step (QA L4) ------------------------------------------------------------------------------

    @Test
    fun `a backward step reads the overlap and skips what it applied`() {
        // The point: wall 1_000_000 at elapsed 100_000. 12 s later the clock stepped back 60 s.
        val point = FoldPoint(1_000_000, 100_000, 1, 900_000, carryOn(x))
        val p = planRead(point, nowWall = 952_000, nowElapsed = 112_000, bootCount = 1, checkedClock = true)!!
        assertEquals(60_000, p.stepBackMs)
        assertEquals(940_000, p.spanFrom)
        assertEquals(938_000, p.queryFrom)
        assertEquals(950_000, p.to)
        assertEquals(10_000, p.capMs)
        // Applied before the step, stamped in the old timeline: X closed and opened again.
        val old = listOf(
            ev(941_000, x, UsageKind.PAUSED), ev(941_001, home, UsageKind.RESUMED), ev(941_400, x, UsageKind.STOPPED),
            ev(943_000, x, UsageKind.RESUMED),
        )
        // New since the step: X closed at 945 s.
        val fresh = listOf(ev(945_000, x, UsageKind.PAUSED), ev(945_001, home, UsageKind.RESUMED), ev(945_400, x, UsageKind.STOPPED))
        val events = (old + fresh).sortedBy { it.timeMs }
        val skipped = groupScreenMs(events, p, point.carry, listOf(g), live = true, count = true, skip = old.map { it.key }.toSet())
        assertEquals(5_400, skipped.ms())
        assertEquals(fresh.map { it.key }, skipped.appliedKeys.map { it.second })
        // Without the keys (a restart) the old events apply again; the elapsed cap bounds what one fold adds.
        val unskipped = groupScreenMs(events, p, point.carry, listOf(g), live = true, count = true)
        val ledger = applyFold(ledgerAt(950_000), p, unskipped, clocksAt(952_000, 112_000), zone, 1, count = true)
        assertTrue((ledger.groupUsedMs[1] ?: 0) <= p.capMs)
    }

    @Test
    fun `a forward step is capped at the elapsed time`() {
        val point = FoldPoint(1_000_000, 100_000, 1, 900_000, carryOn(x))
        val p = planRead(point, nowWall = 1_000_000 + 12_000 + 3_600_000, nowElapsed = 112_000, bootCount = 1, checkedClock = true)!!
        assertEquals(0, p.stepBackMs)
        val out = groupScreenMs(emptyList(), p, point.carry, listOf(g), live = true, count = true)
        assertEquals(p.to - p.spanFrom, out.ms())
        val ledger = applyFold(ledgerAt(p.to), p, out, clocksAt(p.to + SETTLE_MS, 112_000), zone, 1, count = true)
        assertEquals(10_000L, ledger.groupUsedMs[1])
    }

    // ---- Carry, cap, midnight, a dropped read -----------------------------------------------------------------------

    @Test
    fun `an app on at a read's start counts from it`() {
        val one = fold(listOf(ev(90_000, x, UsageKind.RESUMED)), unlocked)
        assertEquals(10_000, one.ms())
        assertEquals(50_000, fold(emptyList(), one.carry, from = 100_000, to = 150_000).ms())
    }

    @Test
    fun `the elapsed cap and the midnight split onto the trusted day`() {
        val midnight = LocalDate.of(2026, 10, 6).atStartOfDay(zone).toInstant().toEpochMilli()
        val bootWall = midnight - 3_600_000
        // Spans 60 s before to 30 s after midnight; the trusted clock is the wall clock.
        val p = ReadPlan(midnight - 60_000, midnight - 60_000, midnight + 30_000, midnight + 30_000 - bootWall, false, 0, false, 90_000)
        val out = fold(emptyList(), carryOn(x), from = p.spanFrom, to = p.to)
        val clocks = clocksAt(p.to + SETTLE_MS, p.toElapsed + SETTLE_MS)
        val newDay = applyFold(ledgerAt(p.to), p, out, clocks, zone, 1, count = true)
        assertEquals("2026-10-06", newDay.day)
        assertEquals(30_000L, newDay.groupUsedMs[1])
        // The same read on the old day counts it all, and the cap bounds it.
        val oldDay = ledgerAt(midnight - 1).copy(groupUsedMs = mapOf(1L to 5L))
        assertEquals(90_005L, applyFold(oldDay, p, out, clocks, zone, 1, count = true).groupUsedMs[1])
        assertEquals(40_005L, applyFold(oldDay, p.copy(capMs = 40_000), out, clocks, zone, 1, count = true).groupUsedMs[1])
        // The point moves to `to` with its offset and the carry.
        assertEquals(FoldPoint(p.to, p.toElapsed, 1, p.to - p.toElapsed, out.carry), newDay.foldPoint)
    }

    @Test
    fun `a read not applied leaves the point and its carry`() {
        val point = FoldPoint(10_000, 1_000, 3, 9_000, carryOn(x))
        val ledger = ledgerAt(10_000).copy(foldPoint = point)
        val p = planRead(ledger.foldPoint, 30_000, 21_000, 3, true)!!
        groupScreenMs(listOf(ev(15_000, x, UsageKind.STOPPED), ev(15_000, y, UsageKind.RESUMED)), p, point.carry, listOf(g), live = true, count = true)
        // Dropped (a failed read, or one that came too late): the next fold reads from the same point.
        assertEquals(point, ledger.foldPoint)
        assertEquals(p.queryFrom, planRead(ledger.foldPoint, 31_000, 22_000, 3, true)!!.queryFrom)
    }

    // ---- The timer (QA H1) ------------------------------------------------------------------------------------------

    @Test
    fun `the cut comes no later than SETTLE_MS + 1 s after the limit, never before`() {
        val limitMs = 5 * 60_000L
        val start = LocalDate.of(2026, 10, 5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val bootWall = start - 3_600_000
        // Folds come at the checkpoint, at other events (our screens, a re-check) and at the timer.
        for (seed in 1..20) {
            val random = java.util.Random(seed.toLong())
            val opened = start + 5_000 + random.nextInt(60_000)
            var ledger = observe(ScreenTimeLedger(), clocksAt(start, start - bootWall), zone).ledger
                .copy(foldPoint = FoldPoint(start, start - bootWall, 1, bootWall, unlocked))
            var now = start
            var cut: Long? = null
            while (cut == null) {
                val p = planRead(ledger.foldPoint, now, now - bootWall, 1, checkedClock = true)
                if (p != null) {
                    val out = groupScreenMs(listOf(ev(opened, x, UsageKind.RESUMED)), p, ledger.foldPoint?.carry ?: unlocked, listOf(g), live = true, count = true)
                    val clocks = clocksAt(now, now - bootWall)
                    ledger = applyFold(observe(ledger, clocks, zone).ledger, p, out, clocks, zone, 1, count = true)
                }
                val used = ledger.groupUsedMs[1] ?: 0L
                if (used >= limitMs) {
                    cut = now
                } else {
                    val timer = now + (limitMs - used) + 1_000
                    val other = now + 1 + random.nextInt(60_000)
                    now = minOf(timer, other)
                }
            }
            val at = checkNotNull(cut) - (opened + limitMs)
            assertTrue("seed $seed: cut $at ms after the limit", at in 0..(SETTLE_MS + 1_000))
        }
    }

    private fun clocksAt(wall: Long, elapsed: Long) = Clocks(wall, elapsed, 1, ClockTrust.SERVER)

    private fun ledgerAt(wall: Long): ScreenTimeLedger = observe(ScreenTimeLedger(), clocksAt(wall, 1_000), zone).ledger
}
