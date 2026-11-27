package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ClockTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Design 139 §4.1 (revision 6): the fold of the usage events, by §4.1's sequence table and §7's rows. */
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

    /** A plain read of `[from, to)` in one boot, no cap; [overlap]: with revision 6's overlap behind `from`. */
    private fun plan(from: Long, to: Long, rebooted: Boolean = false, overlap: Boolean = false) =
        ReadPlan(if (overlap) from - LATE_OVERLAP_MS else from, from, to, to, first = false, stepBackMs = 0, rebooted = rebooted, capMs = Long.MAX_VALUE)

    /** A fold as the phone runs it (revision 6): every event so far, read with the overlap over the last carry. */
    private fun next(events: List<UsageEv>, carry: FoldCarry, from: Long, to: Long, groups: List<FoldGroup> = listOf(g)) =
        groupScreenMs(events, plan(from, to, overlap = true), carry, groups, live = false, count = true)

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
        // +51 ms pairs only by the fallback (below): with Y on (split screen) it doesn't.
        assertEquals(100_000, fold(listOf(stop, ev(20_051, y, UsageKind.RESUMED)), carryOn(x, y)).ms())
        val ignored = listOf(
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

    @Test
    fun `the kill rule's fallback - a partner that must cold-start (gate row 13)`() {
        val stop = ev(20_000, x, UsageKind.STOPPED)
        // 14: 299-815 ms, 16: 309-352 ms; the window ends at +2 s, inclusive.
        for (gap in listOf(299L, 815L, 352L, KILL_FALLBACK_MS)) {
            val out = fold(listOf(stop, ev(20_000 + gap, y, UsageKind.RESUMED)), carryOn(x))
            assertEquals("+$gap", 20_000, out.ms())
            assertEquals(setOf(y), out.onPkgs())
        }
        val ignored = listOf(
            "+2001 ms" to listOf(stop, ev(22_001, y, UsageKind.RESUMED)),
            "an event of X between" to listOf(stop, ev(20_200, x, UsageKind.PAUSED, "$x.B"), ev(20_300, y, UsageKind.RESUMED)),
            "the partner moved before" to listOf(stop, ev(20_100, y, UsageKind.PAUSED), ev(20_300, y, UsageKind.RESUMED)),
        )
        for ((name, events) in ignored) assertTrue(name, x in fold(events, carryOn(x)).onPkgs())
    }

    @Test
    fun `split screen and a relaunch aren't cut by the fallback`() {
        // Split X|Y; X moves inside itself (its old activity's STOPPED 0.55 s later), then Y moves within 2 s.
        val xMoves = listOf(ev(10_000, x, UsageKind.PAUSED), ev(10_005, x, UsageKind.RESUMED, "$x.B"), ev(10_550, x, UsageKind.STOPPED))
        val cases = listOf(
            "Y's next activity" to listOf(ev(11_000, y, UsageKind.PAUSED), ev(11_005, y, UsageKind.RESUMED, "$y.B")),
            "Y's RESUMED alone" to listOf(ev(10_900, y, UsageKind.RESUMED)),
            "Y's half replaced by a new app" to listOf(ev(11_000, y, UsageKind.PAUSED), ev(11_005, "org.example.third", UsageKind.RESUMED)),
        )
        for ((name, more) in cases) {
            val out = fold(xMoves + more, carryOn(x, y))
            assertEquals(name, 100_000, out.ms())
            assertTrue(name, x in out.onPkgs())
        }
        // A same-class relaunch, then Home within 2 s of the old STOPPED: X's PAUSED comes first, so X goes off at its
        // own STOPPED, not at the relaunch's.
        val relaunch = listOf(
            ev(10_000, x, UsageKind.PAUSED), ev(10_005, x, UsageKind.RESUMED), ev(10_555, x, UsageKind.STOPPED),
            ev(11_000, x, UsageKind.PAUSED), ev(11_001, home, UsageKind.RESUMED), ev(11_400, x, UsageKind.STOPPED),
        )
        assertEquals(11_400, fold(relaunch, carryOn(x)).ms())
    }

    @Test
    fun `the fallback needs X alone on screen at its STOPPED (code QA L1)`() {
        val z = "org.example.third"
        val w = "org.example.fourth"
        // X|Z split; X moves inside itself, then Z dies and another app cold-starts in its half 650 ms later.
        val split = listOf(
            ev(10_000, x, UsageKind.PAUSED), ev(10_005, x, UsageKind.RESUMED, "$x.B"), ev(10_550, x, UsageKind.STOPPED),
            ev(10_600, z, UsageKind.STOPPED), ev(11_200, w, UsageKind.RESUMED),
        )
        assertTrue(x in fold(split, carryOn(x, z)).onPkgs())
        // A PiP window (on-paused) beside a killed X: not alone either - X counts until its next event (accepted).
        val pip = unlocked.copy(on = listOf(OnPackage(x), OnPackage(z, paused = true, pausedClass = "$z.A")))
        assertTrue(x in fold(listOf(ev(20_000, x, UsageKind.STOPPED), ev(20_400, y, UsageKind.RESUMED)), pip).onPkgs())
        // Alone: the same kill pairs.
        assertFalse(x in fold(listOf(ev(20_000, x, UsageKind.STOPPED), ev(20_400, y, UsageKind.RESUMED)), carryOn(x)).onPkgs())
    }

    // ---- A pending STOPPED across reads (QA L6) ---------------------------------------------------------------------

    @Test
    fun `a pending STOPPED rides across folds closer than the fallback's 2 s`() {
        val stop = ev(980, x, UsageKind.STOPPED)
        for (partner in listOf(null, y, home)) {
            // Home is on at the STOPPED (split beside it): X isn't alone, so no fallback.
            val start = if (partner == home) carryOn(x, home) else carryOn(x)
            val one = next(listOf(stop), start, 0, 1_000)
            assertEquals(listOf(PendingStop(x, 980, alone = partner != home)), one.carry.pending)
            val two = next(listOf(stop), one.carry, 1_000, 1_600)
            assertEquals(one.carry.pending, two.carry.pending)
            // Fold three's read holds the STOPPED again (applied, skipped) and the partner 720 ms after it.
            val all = listOf(stop) + listOfNotNull(partner?.let { ev(1_700, it, UsageKind.RESUMED) })
            val three = next(all, two.carry, 1_600, 2_500)
            val four = next(all, three.carry, 2_500, 3_000)
            assertTrue(four.carry.pending.isEmpty())
            val ended = partner == y
            assertEquals("partner $partner", ended, x !in four.onPkgs())
            // Off at the STOPPED: the 620 ms already applied stay, nothing after.
            assertEquals("partner $partner", if (ended) 0L else 900L + 500L, three.ms() + four.ms())
            assertEquals(1_000L + 600L, one.ms() + two.ms())
        }
    }

    @Test
    fun `a STOPPED at a read's end stays pending until a fold's to passes its window`() {
        for (partner in listOf(true, false)) {
            val one = fold(listOf(ev(980, x, UsageKind.STOPPED)), carryOn(x), from = 0, to = 1_000)
            assertEquals(1_000, one.ms())
            assertEquals(listOf(PendingStop(x, 980, alone = true)), one.carry.pending)
            // 30 ms later: the window (to 2980, inclusive) still reaches `to`.
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
        assertEquals(10_000 - LATE_OVERLAP_MS, p.queryFrom)
        assertEquals(10_000, p.spanFrom)
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

    @Test
    fun `the first fold after the setup hold counts from its end (gate F2)`() {
        val p = plan(0, 60_000)
        assertEquals(43_000, groupScreenMs(emptyList(), countFromHoldEnd(p, 17_000), carryOn(x), listOf(g), live = false, count = true).ms())
        // The events before the end move the state: X closed and opened again at 20 s.
        val moves = listOf(
            ev(10_000, x, UsageKind.PAUSED), ev(10_001, home, UsageKind.RESUMED), ev(10_400, x, UsageKind.STOPPED),
            ev(20_000, x, UsageKind.RESUMED),
        )
        assertEquals(40_000, groupScreenMs(moves, countFromHoldEnd(p, 17_000), carryOn(x), listOf(g), live = false, count = true).ms())
        // An end at or before `from` changes nothing; one past `to` counts nothing and still moves the state.
        assertEquals(p, countFromHoldEnd(p, 0))
        assertEquals(p, countFromHoldEnd(p, Long.MIN_VALUE))
        val after = groupScreenMs(moves, countFromHoldEnd(p, 70_000), unlocked, listOf(g), live = false, count = true)
        assertEquals(0, after.ms())
        assertEquals(setOf(home, x), after.onPkgs())
    }

    // ---- Late events: the overlap (revision 6, gate row 18) ---------------------------------------------------------

    @Test
    fun `an event stamped before from and stored after the last read applies at from`() {
        // Fold one read [0, 10 s) before Android had stored X's RESUMED at 8 s; fold two's overlap reads it.
        val seen = listOf(ev(1_000, home, UsageKind.PAUSED))
        val late = ev(8_000, x, UsageKind.RESUMED)
        val one = next(seen, unlocked, 0, 10_000)
        assertEquals(0, one.ms())
        val two = next(seen + late, one.carry, 10_000, 20_000)
        assertEquals(10_000, two.ms())
        assertEquals(listOf(late.key), two.appliedKeys.map { it.second })
        // Re-derivable: the state is a single fold's over every event.
        assertEquals(fold(seen + late, unlocked, 0, 20_000).carry.on, two.carry.on)

        // A late PAUSED/STOPPED: X off at `from`, not on all session.
        val closed = listOf(ev(8_000, x, UsageKind.PAUSED), ev(8_001, home, UsageKind.RESUMED), ev(8_400, x, UsageKind.STOPPED))
        val a = next(emptyList(), carryOn(x), 0, 10_000)
        assertEquals(10_000, a.ms())
        val b = next(closed, a.carry, 10_000, 20_000)
        assertEquals(0, b.ms())
        assertEquals(setOf(home), b.onPkgs())
        assertEquals(fold(closed, carryOn(x), 0, 20_000).carry.on, b.carry.on)
        assertEquals(0, next(closed, b.carry, 20_000, 30_000).ms())
    }

    @Test
    fun `the overlap skips what was applied, by key, across a restart too`() {
        val events = listOf(
            ev(2_000, x, UsageKind.RESUMED), ev(6_000, x, UsageKind.PAUSED), ev(6_001, home, UsageKind.RESUMED),
            ev(6_400, x, UsageKind.STOPPED), ev(7_000, x, UsageKind.RESUMED),
        )
        val one = next(events, unlocked, 0, 10_000)
        assertEquals(4_400 + 3_000, one.ms())
        // The keys ride in the carry (saved with the point), so the next read applies nothing again; nothing counted twice.
        assertEquals(events.map { it.key }, one.carry.applied)
        val two = next(events, one.carry, 10_000, 20_000)
        assertTrue(two.appliedKeys.isEmpty())
        assertEquals(10_000, two.ms())
        assertEquals(one.carry.on, two.carry.on)
        // Only the last LATE_OVERLAP_MS's keys ride on.
        val later = next(events, two.carry, 20_000, 40_000)
        assertTrue(later.carry.applied.isEmpty())
        // An event older than the overlap isn't read.
        val point = FoldPoint(100_000, 100_000, 1, 0, unlocked)
        val p = planRead(point, nowWall = 112_000, nowElapsed = 112_000, bootCount = 1, checkedClock = true)!!
        val old = ev(100_000 - LATE_OVERLAP_MS - 1, x, UsageKind.RESUMED)
        assertTrue(groupScreenMs(listOf(old), p, unlocked, listOf(g), live = false, count = true).onPkgs().isEmpty())
        // After a reboot there is no overlap: the old boot's events never apply again.
        val rebooted = planRead(point, nowWall = 112_000, nowElapsed = 5_000, bootCount = 2, checkedClock = true)!!
        assertEquals(rebooted.spanFrom, rebooted.queryFrom)
    }

    @Test
    fun `a late package event before an applied screen off is superseded (code QA L4)`() {
        for (off in listOf(UsageKind.SCREEN_NON_INTERACTIVE, UsageKind.KEYGUARD_SHOWN, UsageKind.DEVICE_SHUTDOWN)) {
            val seen = listOf(dev(8_500, off))
            val one = next(seen, carryOn(x), 0, 10_000)
            assertTrue(one.onPkgs().isEmpty())
            val two = next(listOf(ev(8_000, x, UsageKind.RESUMED)) + seen, one.carry, 10_000, 20_000)
            assertTrue("$off", two.onPkgs().isEmpty())
        }
    }

    @Test
    fun `past 500 keys a floor stands for the oldest (code QA L3)`() {
        val z = "org.example.third"
        val flood = listOf(dev(500, UsageKind.KEYGUARD_SHOWN), dev(600, UsageKind.KEYGUARD_HIDDEN), ev(700, x, UsageKind.RESUMED)) +
            (0 until 300).flatMap { k -> listOf(ev(1_000L + 20 * k, y, UsageKind.RESUMED), ev(1_010L + 20 * k, y, UsageKind.PAUSED)) } +
            dev(7_500, UsageKind.SCREEN_INTERACTIVE)
        val one = next(flood, unlocked, 0, 10_000)
        assertTrue(one.carry.applied.size in APPLIED_CARRY_MAX - 1..APPLIED_CARRY_MAX)
        assertTrue(one.carry.appliedFloor >= 700)
        assertEquals(10_000 - 700, one.ms())
        // Nothing of the flood applies again; a late event after the floor still does.
        val late = ev(9_500, z, UsageKind.RESUMED)
        val two = next(flood + late, one.carry, 10_000, 20_000)
        assertEquals(listOf(late.key), two.appliedKeys.map { it.second })
        assertEquals(10_000, two.ms())
        assertEquals(one.onPkgs() + z, two.onPkgs())
    }

    @Test
    fun `the keys skipped after a backward step ride on, so the next fold skips them too (code QA L2)`() {
        val point = FoldPoint(1_000_000, 100_000, 1, 900_000, carryOn(x))
        val p = planRead(point, nowWall = 952_000, nowElapsed = 112_000, bootCount = 1, checkedClock = true)!!
        val old = listOf(ev(941_000, x, UsageKind.PAUSED), ev(941_001, home, UsageKind.RESUMED), ev(941_400, x, UsageKind.STOPPED))
        val fresh = listOf(ev(945_000, x, UsageKind.RESUMED))
        val events = (old + fresh).sortedBy { it.timeMs }
        val one = groupScreenMs(events, p, point.carry, listOf(g), live = true, count = true, skip = old.map { it.key }.toSet())
        assertTrue(one.carry.applied.containsAll(old.map { it.key }))
        // The next fold, with no in-memory keys (a restart): the old events stay skipped.
        val two = next(events, one.carry, p.to, p.to + 10_000)
        assertTrue(two.appliedKeys.isEmpty())
        assertEquals(10_000, two.ms())
    }

    @Test
    fun `the hold's end is elapsed realtime, mapped onto the read across a backward step (code QA L5)`() {
        // A 60 s step back after the point: the read is [940 s, 950 s) at elapsed 110 s.
        val point = FoldPoint(1_000_000, 100_000, 1, 900_000, carryOn(x))
        val p = planRead(point, nowWall = 952_000, nowElapsed = 112_000, bootCount = 1, checkedClock = true)!!
        // The hold ended at elapsed 105 s: 945 s on the read's clock (its old wall time, 1 005 s, would count nothing).
        val held = countFromHoldEnd(p, 105_000)
        assertEquals(945_000, held.spanFrom)
        assertEquals(5_000, groupScreenMs(emptyList(), held, carryOn(x), listOf(g), live = true, count = true).ms())
        // Before the point: unchanged; none recorded: unchanged.
        assertEquals(p, countFromHoldEnd(p, 99_000))
        assertEquals(p, countFromHoldEnd(p, Long.MIN_VALUE))
    }

    @Test
    fun `a late event that an applied event of its package follows is superseded`() {
        // Out of Android's order by a few ms: X's RESUMED stored after its PAUSED and STOPPED were read.
        val seen = listOf(ev(8_500, x, UsageKind.PAUSED), ev(8_501, home, UsageKind.RESUMED), ev(8_900, x, UsageKind.STOPPED))
        val one = next(seen, carryOn(x), 0, 10_000)
        assertEquals(setOf(home), one.onPkgs())
        val two = next((listOf(ev(8_400, x, UsageKind.RESUMED, "$x.B")) + seen), one.carry, 10_000, 20_000)
        assertEquals(setOf(home), two.onPkgs())
        assertEquals(0, two.ms())
    }

    // ---- A backward clock step (QA L4) ------------------------------------------------------------------------------

    @Test
    fun `a backward step reads the overlap and skips what it applied`() {
        // The point: wall 1_000_000 at elapsed 100_000. 12 s later the clock stepped back 60 s.
        val point = FoldPoint(1_000_000, 100_000, 1, 900_000, carryOn(x))
        val p = planRead(point, nowWall = 952_000, nowElapsed = 112_000, bootCount = 1, checkedClock = true)!!
        assertEquals(60_000, p.stepBackMs)
        assertEquals(940_000, p.spanFrom)
        assertEquals(940_000 - LATE_OVERLAP_MS, p.queryFrom)
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

    /** Code QA L7: the compare-and-set - a read applies only onto the point it started from. */
    @Test
    fun `a read applies only onto the point it started from`() {
        val point = FoldPoint(10_000, 1_000, 3, 9_000, carryOn(x))
        val ledger = ledgerAt(10_000).copy(foldPoint = point)
        val p = planRead(point, 30_000, 21_000, 3, true)!!
        val out = groupScreenMs(listOf(ev(15_000, x, UsageKind.PAUSED)), p, point.carry, listOf(g), live = true, count = true)
        val clocks = clocksAt(30_000, 21_000)
        val applied = applyFoldIfCurrent(ledger, point, p, out, clocks, zone, 3, count = true)!!
        assertEquals(p.to, applied.foldPoint!!.wallMs)
        // Another fold moved the point meanwhile, or the point was cleared: dropped, nothing changes.
        assertNull(applyFoldIfCurrent(applied, point, p, out, clocks, zone, 3, count = true))
        assertNull(applyFoldIfCurrent(ledger.copy(foldPoint = null), point, p, out, clocks, zone, 3, count = true))
        // A first fold applies onto no point only.
        val first = planRead(null, 30_000, 21_000, 3, true)!!
        val firstOut = groupScreenMs(emptyList(), first, FoldCarry(), listOf(g), live = true, count = false)
        assertEquals(first.to, applyFoldIfCurrent(ledger.copy(foldPoint = null), null, first, firstOut, clocks, zone, 3, count = false)!!.foldPoint!!.wallMs)
        assertNull(applyFoldIfCurrent(ledger, null, first, firstOut, clocks, zone, 3, count = false))
    }

    /** Code QA L1, L2: a point over a day old, or one the clock is behind after a reboot, starts a first fold. */
    @Test
    fun `a stale point or one ahead of the clock after a reboot starts a first fold`() {
        val day = STALE_POINT_MS
        val old = FoldPoint(1_000_000, 1_000, 3, 999_000, carryOn(x))
        val stale = planRead(old, 1_000_000 + day + SETTLE_MS + 1, 2_000, 3, true)!!
        assertTrue(stale.first)
        assertEquals(stale.to - FIRST_FOLD_LOOKBACK_MS, stale.queryFrom)
        assertFalse(planRead(old, 1_000_000 + day, 2_000, 3, true)!!.first)
        // After a reboot the wall clock is behind the point: a first fold, never a read that can't reach past it.
        val behind = planRead(old, 900_000, 5_000, 4, true)!!
        assertTrue(behind.first)
        // In the same boot it is a backward clock step, read from before the point.
        val step = planRead(old, 900_000, 5_000, 3, true)!!
        assertFalse(step.first)
        assertTrue(step.stepBackMs > 0)
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
