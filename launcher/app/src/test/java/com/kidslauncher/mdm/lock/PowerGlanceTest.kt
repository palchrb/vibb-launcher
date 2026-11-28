package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PLAN (4i), the charging glimpse: a power change's own wake is no SOS gesture, and the change's look is a fresh loss
 * - while a real press-SOS keeps its mark. */
class PowerGlanceTest {
    // The owner's log (elapsed ms from the battery update): battery 0, screen-on 104, the glance paused 490, stopped 874.
    private val power = 10_000L
    private val screenOn = 10_104L
    private val paused = 10_490L
    private val dark = 5_000L // the screen-off before the plug-in

    private val none = PowerRecheck(clearSosMark = false, freshLoss = false)

    /** The SOS rule's screen-on with one stamp before or after it ([powerWakeTag] from a fresh screen-on). */
    private fun sosOn(screenOnAt: Long?, powerAt: Long?, screenOffAt: Long?) =
        sosScreenOn(screenOnAt, powerWakeTag(false, powerAt, screenOnAt, screenOffAt))

    @Test
    fun `the power change's own wake - the stamp's window, a long dark spell before it`() {
        assertTrue(powerWokeScreen(power, screenOn, dark))
        assertTrue("never a screen-off in this process", powerWokeScreen(power, screenOn, null))
        assertTrue("the broadcast after the screen-on", powerWokeScreen(power, power - POWER_WAKE_BEFORE_MS, dark))
        assertFalse(powerWokeScreen(power, power - POWER_WAKE_BEFORE_MS - 1, dark))
        assertTrue(powerWokeScreen(power, power + POWER_WAKE_AFTER_MS, dark))
        assertFalse(powerWokeScreen(power, power + POWER_WAKE_AFTER_MS + 1, dark))
        assertFalse(powerWokeScreen(null, screenOn, dark))
        assertFalse(powerWokeScreen(power, null, dark))
        assertFalse("the tag ends at the next screen-off", powerWokeScreen(power, screenOn, screenOn + 3_000L))
    }

    @Test
    fun `the owner's log is no longer a probable SOS`() {
        assertTrue("before: the mark", sosGestureLikely(screenOn, paused, sosAppPresent = true))
        assertNull(sosOn(screenOn, power, dark))
        assertFalse(sosGestureLikely(sosOn(screenOn, power, dark), paused, sosAppPresent = true))
        assertFalse(sosMarkAtPause(ours = false, sosOn(screenOn, power, dark), paused, sosAppPresent = true))
        assertEquals(PAUSE_REFRONT_MS, pauseRefrontDelayMs(sosOn(screenOn, power, dark), paused))
    }

    @Test
    fun `a real SOS overlapping a power change keeps its mark`() {
        // The plug-in's wake, then the kid's presses: the first turns the screen off, the next turns it on within 2 s.
        val off = power + 600L
        val pressOn = power + 1_000L
        val stop = power + 1_300L
        assertEquals(pressOn, sosOn(pressOn, power, off))
        assertTrue(sosGestureLikely(sosOn(pressOn, power, off), stop, sosAppPresent = true))
        assertEquals("SOS wins at the look", none, powerRecheck(true, false, power, stop, powerWake = powerWokeScreen(power, pressOn, off), sosMarked = true))
        // The screen already on at the connect (lit 5 s ago by a press): the connect exempts nothing.
        val litBefore = power - 5_000L
        assertEquals(litBefore, sosOn(litBefore, power, dark))
        assertFalse(powerWokeScreen(power, litBefore, dark))
    }

    @Test
    fun `a screen-on 2-3 s before the plug-in - the SOS rule wins, as documented`() {
        val pressOn = power - 2_500L
        val lost = power + 100L
        assertFalse(powerWokeScreen(power, pressOn, dark))
        assertTrue("still in the SOS window", sosGestureLikely(sosOn(pressOn, power, dark), lost, sosAppPresent = true))
        assertEquals(none, powerRecheck(true, false, power, lost, powerWake = false, sosMarked = true))
        assertEquals(
            "no SOS app (no mark): the look is a fresh loss",
            PowerRecheck(clearSosMark = false, freshLoss = true),
            powerRecheck(true, false, power, lost, powerWake = false, sosMarked = false),
        )
    }

    @Test
    fun `the look - a fresh loss only while LOCKED, lost around the change`() {
        val wake = powerWokeScreen(power, screenOn, dark)
        assertEquals(
            "the broadcast came after the pause's mark: the plug-in's own mark ends",
            PowerRecheck(clearSosMark = true, freshLoss = true),
            powerRecheck(true, false, power, paused, powerWake = wake, sosMarked = true),
        )
        assertEquals(PowerRecheck(false, true), powerRecheck(true, false, power, paused, powerWake = wake, sosMarked = false))
        assertEquals("the glance in front - the stop's check already re-fronted", none, powerRecheck(true, true, power, paused, wake, false))
        assertEquals("not LOCKED", none, powerRecheck(false, false, power, paused, wake, false))
        assertEquals("never lost in this process", none, powerRecheck(true, false, power, null, wake, false))
        assertEquals("lost before (the kid's PIN pad, a yield's end, the cap)", none, powerRecheck(true, false, power, power - POWER_LOSS_BEFORE_MS - 1, wake, false))
        assertEquals(PowerRecheck(false, true), powerRecheck(true, false, power, power - POWER_LOSS_BEFORE_MS, wake, false))
        assertEquals(PowerRecheck(false, true), powerRecheck(true, false, power, power + POWER_LOSS_AFTER_MS, wake, false))
        assertEquals(none, powerRecheck(true, false, power, power + POWER_LOSS_AFTER_MS + 1, wake, false))
    }

    @Test
    fun `the fresh check goes through the face's rule - ours, the exemptions and the cap hold`() {
        val lost = RefrontInputs(
            locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
            emergencyFlow = false, alarmRinging = false, android = AndroidLoss.LOCKED, androidFreshLoss = true,
        )
        assertEquals(RefrontAction.Refront(2_000L, noUserAction = true), refrontAction(lost, 0))
        // The stop's check re-fronted first and the glance isn't resumed yet: the look's re-front is the second, the cap's last.
        assertEquals(RefrontAction.Refront(2_000L, noUserAction = true), refrontAction(lost.copy(androidRefronts = 1), 0))
        assertEquals("the cap", RefrontAction.Stop, refrontAction(lost.copy(androidRefronts = ANDROID_REFRONT_CAP), 0))
        assertEquals("the kid's PIN pad", RefrontAction.Stop, refrontAction(lost.copy(androidOurs = true), 0))
        assertEquals("Android opened by the kid", RefrontAction.AndroidLeave, refrontAction(lost.copy(android = AndroidLoss.OPEN), 0))
        // A yield running: the look yields again (its recheck reposted once - the glue below).
        assertEquals(RefrontAction.Yield("system_call"), refrontAction(lost.copy(telecomInCall = true), 0))
        assertEquals(RefrontAction.Yield("voip"), refrontAction(lost.copy(voipCall = true), 0))
        assertEquals("the screen off", RefrontAction.Stop, refrontAction(lost.copy(interactive = false), 0))
    }

    @Test
    fun `the glue - both broadcasts, one look per change, the one check`() {
        val runtime = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }
            .first { it.exists() }.readText()
        assertTrue(runtime.contains("addAction(Intent.ACTION_POWER_CONNECTED)\n                addAction(Intent.ACTION_POWER_DISCONNECTED)\n            },\n            ContextCompat.RECEIVER_NOT_EXPORTED,"))
        assertTrue(runtime.contains("lastScreenOffElapsed = SystemClock.elapsedRealtime()"))
        val receiver = runtime.substringAfter("private val powerReceiver").substringBefore("\n    }\n")
        // PLAN 1o's code QA L2: each change its own look with its own stamp - a re-plug cancels no earlier look.
        assertFalse(receiver.contains("removeCallbacks"))
        assertTrue(receiver.contains("handler.postDelayed({ powerLook(now) }, POWER_RECHECK_MS)"))
        val check = runtime.substringAfter("private fun powerLook(powerAt: Long) {").substringBefore("\n    }\n")
        assertTrue(check.contains("powerRecheck(chromeLocked, lockResumed, powerAt, lockPausedAt, powerWake = wake, sosMarked = marked)"))
        assertTrue(check.contains("refrontAttempt = 0"))
        // A yield's pending recheck is replaced, not doubled: removed, then the check (which reposts one).
        assertTrue(check.indexOf("handler.removeCallbacks(refrontCheck)") in 0 until check.indexOf("markAndroidLoss()"))
        assertTrue(check.indexOf("markAndroidLoss()") < check.indexOf("runRefrontCheck()"))
    }


    // ---- glance-replug: the owner's log of 2026-11-26 (three plug-ins on one screen-on, the third met the cap) ----

    /** The face's loss, LOCKED under the glance, the screen on, a fresh pause or stop. */
    private val faceLoss = RefrontInputs(
        locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
        emergencyFlow = false, alarmRinging = false, android = AndroidLoss.LOCKED, androidFreshLoss = true,
    )

    /** One check through the runtime's own step ([faceRefrontStep]): the action, the counts after it. */
    private fun check(refronts: FaceRefronts, lostAt: Long?, inputs: RefrontInputs = faceLoss) = faceRefrontStep(inputs, refronts, lostAt, attempt = 0)

    private fun RefrontAction.refronted() = this is RefrontAction.Refront && noUserAction

    @Test
    fun `the wake - a screen-on after a long dark spell, not the stamp's order`() {
        // The stamp ~0.5 s late (the queue's deferral): the plug-in's own wake 400-600 ms before the stamp is still its own.
        val stamp = 20_000L
        assertTrue(powerWokeScreen(stamp, stamp - 400L, dark))
        assertTrue(powerWokeScreen(stamp, stamp - 600L, dark))
        // The dark spell's edge.
        assertTrue(powerWokeScreen(stamp, stamp + 100L, stamp + 100L - POWER_WAKE_DARK_MS))
        assertFalse(powerWokeScreen(stamp, stamp + 100L, stamp + 100L - POWER_WAKE_DARK_MS + 1))
    }

    @Test
    fun `a press-SOS right after a plug-in keeps its mark although the stamp comes late`() {
        // The code QA's case: the plug-in at T wakes the screen at T+0.1; press 1 turns it off at T+0.4, the stamp lands at
        // T+0.5, press 2 turns it on at T+0.7 - after the stamp, with no screen-off between the two.
        val t = 30_000L
        val off = t + 400L
        val stamp = t + 500L
        val pressOn = t + 700L
        assertFalse(powerWokeScreen(stamp, pressOn, off))
        assertEquals("the SOS rule reads the press's screen-on", pressOn, sosOn(pressOn, stamp, off))
        val sosStop = pressOn + 900L
        assertTrue(sosGestureLikely(sosOn(pressOn, stamp, off), sosStop, sosAppPresent = true))
        assertEquals("the look leaves the mark", none, powerRecheck(true, false, stamp, sosStop, powerWake = powerWokeScreen(stamp, pressOn, off), sosMarked = true))
        // The plug-in's own wake at T+0.1 with the same late stamp: still exempt (a long dark spell before it).
        assertTrue(powerWokeScreen(stamp, t + 100L, dark))
        assertNull(sosOn(t + 100L, stamp, dark))
    }

    @Test
    fun `the look and a mark - no look for a loss outside the change, or a mark the change's wake didn't set`() {
        val stamp = 40_000L
        for (lost in listOf(stamp - POWER_LOSS_BEFORE_MS, stamp - 600L, stamp - 301L)) {
            assertEquals("a mark, no wake: no look ($lost)", none, powerRecheck(true, false, stamp, lost, powerWake = false, sosMarked = true))
        }
        val stale = stamp - POWER_LOSS_BEFORE_MS - 1
        assertEquals("the wake's own mark, but an earlier pause: no look", none, powerRecheck(true, false, stamp, stale, powerWake = true, sosMarked = true))
    }

    @Test
    fun `three plug-ins within 10 s on one screen-on each get the glance back`() {
        // Before: one count per screen-on - the third plug-in's loss met the cap.
        var before = FaceRefronts()
        val beforeOutcomes = listOf(1_000L, 5_000L, 9_000L).map { lost ->
            val (action, after) = check(before, lost)
            before = after
            action.refronted()
        }
        assertEquals(listOf(true, true, false), beforeOutcomes)

        // Now: the owner's cycles - the plug-in's stamp, the pause 300 ms later, its check; an unplug between with no loss.
        var face = FaceRefronts()
        val back = listOf(1_000L, 5_000L, 9_000L).map { stamp ->
            face = face.powerChanged(stamp)
            val (action, after) = check(face, stamp + 300L)
            face = after
            face = face.powerChanged(stamp + 2_000L) // the unplug: no loss, no budget spent
            action.refronted()
        }
        assertEquals(listOf(true, true, true), back)
        assertEquals("the screen-on's count untouched", 0, face.screenOn)
        assertEquals(3, face.powerSpent)
    }

    @Test
    fun `the loss before the stamp - the pause's check meets the screen-on's cap, the look gets the glance back`() {
        var face = FaceRefronts(screenOn = ANDROID_REFRONT_CAP)
        // The emulator run: the pause 107 ms before Android sent the broadcast, which reached us 501 ms later.
        val lost = 20_000L
        assertFalse(check(face, lost).first.refronted())
        val stamp = lost + 608L
        face = face.powerChanged(stamp)
        assertEquals(PowerRecheck(false, true), powerRecheck(true, false, stamp, lost, powerWake = false, sosMarked = false))
        val (look, after) = check(face, lost)
        assertTrue("the look, 1 s after the stamp", look.refronted())
        assertEquals(1, after.powerUsed)
        assertEquals(ANDROID_REFRONT_CAP, after.screenOn)
    }

    @Test
    fun `the change's budget - the pause, the stop and the look, then nothing`() {
        val stamp = 30_000L
        var face = FaceRefronts().powerChanged(stamp)
        val lost = stamp + 300L
        val outcomes = (1..4).map {
            val (action, after) = check(face, lost)
            face = after
            action.refronted()
        }
        assertEquals(listOf(true, true, true, false), outcomes)
        assertEquals(POWER_REFRONT_BUDGET, face.powerUsed)
        assertFalse("a re-front's follow-up is no fresh loss", check(face, lost, faceLoss.copy(androidFreshLoss = false)).first.refronted())
    }

    @Test
    fun `a loss outside the change's window spends the screen-on's count - also one 30 s after a plug-in`() {
        val stamp = 50_000L
        var face = FaceRefronts().powerChanged(stamp)
        val late = stamp + 30_000L
        assertFalse(face.powerCovers(late))
        assertFalse(face.powerCovers(stamp - POWER_LOSS_BEFORE_MS - 1))
        assertFalse(face.powerCovers(null))
        val outcomes = (1..3).map {
            val (action, after) = check(face, late)
            face = after
            action.refronted()
        }
        assertEquals(listOf(true, true, false), outcomes)
        assertEquals(0, face.powerUsed)
    }

    @Test
    fun `no rate - a cable flapping every second spends at most the budgets per screen-on`() {
        var face = FaceRefronts()
        var refronts = 0
        for (i in 0 until 60) {
            val stamp = 100_000L + i * 1_000L
            face = face.powerChanged(stamp)
            // Each change takes the glance twice (pause and stop) - the worst a blocked start does.
            repeat(2) {
                val (action, after) = check(face, stamp + 200L)
                face = after
                if (action.refronted()) refronts++
            }
        }
        assertEquals(POWER_BUDGETS_PER_SCREEN_ON * 2 + ANDROID_REFRONT_CAP, refronts)
        assertTrue(refronts <= POWER_BUDGETS_PER_SCREEN_ON * POWER_REFRONT_BUDGET + ANDROID_REFRONT_CAP)
        assertEquals(POWER_BUDGETS_PER_SCREEN_ON, face.powerSpent)
        assertNull("after them a change opens no budget", face.powerAtMs)
    }

    @Test
    fun `an SOS mark and the other exemptions win - and a yielding look takes nothing from the budget`() {
        val stamp = 60_000L
        val face = FaceRefronts().powerChanged(stamp)
        val lost = stamp + 100L
        val (sos, afterSos) = check(face, lost, faceLoss.copy(sosFlow = true))
        assertEquals(RefrontAction.Yield("sos"), sos)
        assertEquals(face, afterSos)
        val (call, afterCall) = check(face, lost, faceLoss.copy(telecomInCall = true))
        assertEquals(RefrontAction.Yield("system_call"), call)
        assertEquals(face, afterCall)
        // The yield's rechecks are no fresh loss: nothing re-fronts after it ends (qa-59b-code B-3).
        assertFalse(check(face, lost, faceLoss.copy(androidFreshLoss = false)).first.refronted())
    }

    @Test
    fun `the glue - the receiver opens the budget, the look moves no count`() {
        val runtime = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }
            .first { it.exists() }.readText().replace(Regex("\\s+"), " ")
        val receiver = runtime.substringAfter("private val powerReceiver").substringBefore("private fun powerLook(")
        val open = receiver.indexOf("faceRefronts = faceRefronts.powerChanged(now)")
        assertTrue(open in receiver.indexOf("powerChangedAt = now") until receiver.indexOf("handler.postDelayed({ powerLook(now) }, POWER_RECHECK_MS)"))
        val look = runtime.substringAfter("private fun powerLook(powerAt: Long) {").substringBefore("fun onLockResumed(")
        assertFalse(look.contains("faceRefronts"))
    }

    // ---- PLAN 1o: the owner's Jelly, 2026-11-28 - an unplug's wake, a re-plug 1.3 s later read as a probable SOS ----

    /**
     * The runtime's power and SOS bookkeeping (PinLockRuntime's screen receiver, power receiver, the pause's and the
     * stop's marks, the face's check) over the pure steps: the screen-on's tag ([powerWakeTag]), the SOS rule's screen-on
     * ([sosScreenOn]), the counts ([faceRefrontStep]). An SOS app on the phone. The code QA's L3: the screen-on before
     * [screenOff] is there too (5 s before it), as on a phone.
     */
    private inner class Phone(var screenOff: Long) {
        var screenOn: Long? = screenOff - 5_000L
        var tag = false
        var face = FaceRefronts()
        var lostAt: Long? = null
        var marked = false
        private var powerAt: Long? = null

        fun on(at: Long) {
            screenOn = at
            tag = powerWakeTag(false, powerAt, at, screenOff)
        }

        fun off(at: Long) {
            screenOff = at
            tag = false
            face = FaceRefronts()
        }

        fun power(at: Long) {
            powerAt = at
            tag = powerWakeTag(tag, at, screenOn, screenOff)
            face = face.powerChanged(at)
        }

        /** The glance paused (the pause's mark; the loss's time) or stopped (the stop's mark). */
        fun lost(at: Long, pause: Boolean = true) {
            if (pause) lostAt = at
            if (sosGestureLikely(sosScreenOn(screenOn, tag), at, sosAppPresent = true)) marked = true
        }

        /** The re-front check that follows. */
        fun check(): RefrontAction {
            val (action, after) = this@PowerGlanceTest.check(face, lostAt, faceLoss.copy(sosFlow = marked))
            face = after
            if (!sosMarkKept(action)) marked = false
            return action
        }
    }

    @Test
    fun `the owner's log - an unplug's wake, a re-plug 1_3 s later, the charging dialog's loss - re-fronted, no SOS`() {
        // The log (ms): screen off 31 220, unplug 32 450, the glance resumed 32 580, re-plug 33 890, the glance paused
        // 34 220 (BlockedAppActivity after Unihertz's ChargingDialog), the stop ~0.35 s later. Each stamp at its event or
        // ~0.5 s late (the broadcast queue's deferral).
        val screenOff = 31_220L
        val screenOn = 32_550L
        val paused = 34_220L
        for (unplugLate in listOf(0L, 500L)) for (replugLate in listOf(0L, 500L)) {
            val unplug = 32_450L + unplugLate
            val replug = 33_890L + replugLate
            val why = "unplug +$unplugLate, re-plug +$replugLate"
            // Before: the latest stamp alone - the re-plug's - and the screen-on 1.3 s+ before it read as no wake.
            assertFalse(why, powerWokeScreen(replug, screenOn, screenOff))
            assertTrue(why, sosGestureLikely(screenOn, paused, sosAppPresent = true))

            val phone = Phone(screenOff)
            listOf<Pair<Long, () -> Unit>>(
                unplug to { phone.power(unplug) },
                screenOn to { phone.on(screenOn) },
                replug to { phone.power(replug) },
                paused to { phone.lost(paused) },
            ).sortedBy { it.first }.forEach { it.second() }
            assertTrue("the screen-on is the unplug's wake ($why)", phone.tag)
            assertFalse("no mark at the pause ($why)", phone.marked)
            assertEquals(why, PAUSE_REFRONT_MS, pauseRefrontDelayMs(sosScreenOn(phone.screenOn, phone.tag), paused))
            phone.lost(paused + 350L, pause = false)
            assertFalse("no mark at the stop ($why)", phone.marked)
            val action = phone.check()
            assertTrue("re-fronted ($why): $action", action.refronted())
            assertTrue("with the re-plug's budget ($why)", phone.face.powerCovers(paused))
            assertEquals(why, 1, phone.face.powerUsed)
            assertEquals(why, 0, phone.face.screenOn)
            // The re-plug's look reads the tag, not the latest stamp alone: a fresh loss, no mark to clear.
            assertEquals(why, PowerRecheck(false, true), powerRecheck(true, false, replug, paused, powerWake = phone.tag, sosMarked = phone.marked))
        }
    }

    @Test
    fun `a re-plug 0_3-2 s after an unplug's wake - the tag holds, the loss is re-fronted`() {
        for (gap in listOf(300L, 500L, 900L, 1_300L, 2_000L)) for (lossAfter in listOf(-200L, 300L)) {
            val phone = Phone(screenOff = 1_000L)
            phone.power(5_000L) // the unplug
            phone.on(5_100L)
            val replug = 5_100L + gap
            phone.power(replug)
            assertTrue("gap $gap", phone.tag)
            phone.lost(replug + lossAfter)
            phone.lost(replug + lossAfter + 400L, pause = false)
            assertFalse("gap $gap, loss $lossAfter", phone.marked)
            val action = phone.check()
            assertTrue("gap $gap, loss $lossAfter: $action", action.refronted())
            assertTrue(phone.face.powerCovers(replug + lossAfter))
        }
    }

    @Test
    fun `re-plugs on one screen-on - each loss gets the glance back`() {
        val phone = Phone(screenOff = 1_000L)
        phone.power(5_000L) // the unplug
        phone.on(5_100L)
        for (stamp in listOf(6_400L, 7_500L, 8_600L)) { // re-plug, unplug, re-plug
            phone.power(stamp)
            assertTrue(phone.tag)
            phone.lost(stamp + 300L)
            phone.lost(stamp + 650L, pause = false)
            assertFalse("no mark ($stamp)", phone.marked)
            assertTrue("re-fronted ($stamp)", phone.check().refronted())
        }
        assertEquals(3, phone.face.powerSpent)
        assertEquals("the screen-on's count untouched", 0, phone.face.screenOn)
    }

    @Test
    fun `a press-SOS after a plug-in keeps its mark - the press's screen-off ends the tag, no later stamp sets it`() {
        val t = 30_000L
        // The stamp at the plug-in, or ~0.5 s late - between the press's off and on (the 1i code QA's order).
        for (late in listOf(false, true)) {
            val phone = Phone(screenOff = 20_000L)
            if (!late) phone.power(t)
            phone.on(t + 100L) // the plug-in's wake
            assertEquals("late $late", !late, phone.tag)
            phone.off(t + 400L) // press 1
            if (late) phone.power(t + 500L)
            phone.on(t + 700L) // press 2; the rest swallowed
            assertFalse("late $late", phone.tag)
            phone.power(t + 1_000L) // an unplug during the gesture: the press's short dark spell, no tag
            assertFalse("late $late: a later change tags no press", phone.tag)
            phone.lost(t + 1_600L)
            assertTrue("late $late", phone.marked)
            assertEquals("late $late", RefrontAction.Yield("sos"), phone.check())
            assertEquals(none, powerRecheck(true, false, t + 1_000L, t + 1_600L, powerWake = phone.tag, sosMarked = true))
        }
    }

    @Test
    fun `a press-SOS with no power change - unchanged`() {
        for (stamp in listOf(null, 10_000L)) { // none in this process, or one 20 s before
            val phone = Phone(screenOff = 25_000L)
            stamp?.let { phone.power(it) }
            phone.on(30_000L) // the first press, from the dark
            assertFalse(phone.tag)
            phone.lost(31_200L)
            assertTrue("stamp $stamp", phone.marked)
            assertEquals(RefrontAction.Yield("sos"), phone.check())
        }
    }

    @Test
    fun `a stamp in the dark tags nothing - not the screen-on before the screen-off`() {
        val phone = Phone(screenOff = 10_000L)
        phone.power(10_200L)
        assertFalse("long before, then dark", phone.tag)
        // A short lit spell: on, off 0.3 s later, a stamp 0.5 s after the screen-on - inside its window, but in the dark.
        phone.on(20_000L)
        phone.off(20_300L)
        phone.power(20_500L)
        assertFalse(phone.tag)
        phone.on(25_000L)
        assertFalse("the stamp 4.5 s before the next screen-on", phone.tag)
        phone.lost(25_500L)
        assertTrue(phone.marked)
    }

    @Test
    fun `a press-SOS from the dark with a power change around press 1 - read as the change's wake, no mark (open, the code QA's M)`() {
        // Today's outcome, documented (powerWakeTag's KDoc): press 1 ends a long dark spell, so a stamp from
        // POWER_WAKE_AFTER_MS before it to POWER_WAKE_BEFORE_MS after it tags the screen-on. Narrowing it is
        // POWER_WAKE_BEFORE_MS alone - the owner's choice.
        val press1 = 30_000L
        for (stamp in listOf(press1 + 300L, press1 + POWER_WAKE_BEFORE_MS, press1 - POWER_WAKE_AFTER_MS)) {
            val phone = Phone(screenOff = 20_000L)
            if (stamp < press1) phone.power(stamp)
            phone.on(press1)
            if (stamp > press1) phone.power(stamp)
            assertTrue("stamp ${stamp - press1}", phone.tag)
            phone.lost(press1 + 1_200L) // the SOS screen
            assertFalse("stamp ${stamp - press1}: no mark", phone.marked)
            assertTrue("stamp ${stamp - press1}: re-fronted over it", phone.check().refronted())
        }
        // Outside the window the mark holds.
        for (stamp in listOf(press1 + POWER_WAKE_BEFORE_MS + 1, press1 - POWER_WAKE_AFTER_MS - 1)) {
            val phone = Phone(screenOff = 20_000L)
            if (stamp < press1) phone.power(stamp)
            phone.on(press1)
            if (stamp > press1) phone.power(stamp)
            phone.lost(press1 + 1_200L)
            assertTrue("stamp ${stamp - press1}", phone.marked)
        }
    }

    @Test
    fun `a re-plug keeps the unplug's budget and look for the unplug's loss - within the bound`() {
        // The code QA's L2: the unplug at 10 000 and its loss at 10 300, the re-plug's stamp at 11 400 before the
        // unplug's look (11 000) ran.
        val unplug = 10_000L
        val replug = 11_400L
        val lost = 10_300L
        assertFalse("before: the re-plug's budget alone - its window misses the loss", FaceRefronts(powerAtMs = replug).powerCovers(lost))
        val face = FaceRefronts().powerChanged(unplug).powerChanged(replug)
        assertTrue(face.powerCovers(lost))
        val (action, after) = check(face, lost)
        assertTrue(action.refronted())
        assertEquals(1, after.prevPowerUsed)
        assertEquals(0, after.powerUsed)
        assertEquals(1, after.powerSpent)
        assertEquals(0, after.screenOn)
        // Each look its own stamp: the unplug's look is a fresh loss, the re-plug's stamp alone would not be.
        assertEquals(PowerRecheck(false, true), powerRecheck(true, false, unplug, lost, powerWake = true, sosMarked = false))
        assertEquals(none, powerRecheck(true, false, replug, lost, powerWake = true, sosMarked = false))

        // The bound: two budgets open, one left to spend - the second budget's first re-front falls to the screen-on's count.
        var two = FaceRefronts(powerSpent = POWER_BUDGETS_PER_SCREEN_ON - 1).powerChanged(10_000L).powerChanged(12_000L)
        val (first, afterFirst) = check(two, 10_300L)
        assertTrue(first.refronted())
        two = afterFirst
        assertEquals(POWER_BUDGETS_PER_SCREEN_ON, two.powerSpent)
        assertFalse(two.powerCovers(12_300L))
        assertEquals(two.screenOn to ANDROID_REFRONT_CAP, two.forLoss(12_300L))
        // The one before is dropped with the open one once the budgets are spent.
        assertNull(two.powerChanged(14_000L).prevPowerAtMs)
    }

    @Test
    fun `the glue - the tag set at the screen-on, kept by the power receiver, ended by the screen-off, read by the SOS rule and the look`() {
        val runtime = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }
            .first { it.exists() }.readText().replace(Regex("\\s+"), " ")
        assertTrue(runtime.contains("lastScreenOffElapsed = SystemClock.elapsedRealtime() screenOnPowerWake = false"))
        val on = runtime.indexOf("lastScreenOnElapsed = SystemClock.elapsedRealtime()")
        assertTrue(on > 0)
        assertTrue(runtime.indexOf("screenOnPowerWake = powerWakeTag(false, powerChangedAt, lastScreenOnElapsed, lastScreenOffElapsed)") in on until on + 300)
        val receiver = runtime.substringAfter("private val powerReceiver").substringBefore("private fun powerLook(")
        val tag = receiver.indexOf("screenOnPowerWake = powerWakeTag(screenOnPowerWake, now, lastScreenOnElapsed, lastScreenOffElapsed)")
        assertTrue(tag in receiver.indexOf("powerChangedAt = now") until receiver.indexOf("handler.postDelayed({ powerLook(now) }, POWER_RECHECK_MS)"))
        assertTrue(runtime.substringAfter("private fun powerLook(powerAt: Long) {").substringBefore("fun onLockResumed(").contains("val wake = screenOnPowerWake"))
        assertEquals("the pause's mark and delay, the stop's mark", 3, Regex("sosScreenOn\\(lastScreenOnElapsed, screenOnPowerWake\\)").findAll(runtime).count())
        assertFalse("the latest stamp alone is read nowhere", runtime.contains("powerWokeScreen("))
    }
}
