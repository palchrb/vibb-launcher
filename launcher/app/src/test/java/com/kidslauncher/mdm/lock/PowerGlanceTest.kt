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

    @Test
    fun `the power change's own wake - first screen-on, -300 ms to +2 s, no screen-off between`() {
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
        assertNull(sosScreenOn(screenOn, power, dark))
        assertFalse(sosGestureLikely(sosScreenOn(screenOn, power, dark), paused, sosAppPresent = true))
        assertFalse(sosMarkAtPause(androidFace = true, ours = false, sosScreenOn(screenOn, power, dark), paused, sosAppPresent = true))
        assertEquals(PAUSE_REFRONT_MS, pauseRefrontDelayMs(sosScreenOn(screenOn, power, dark), paused))
    }

    @Test
    fun `a real SOS overlapping a power change keeps its mark`() {
        // The plug-in's wake, then the kid's presses: the first turns the screen off, the next turns it on within 2 s.
        val off = power + 600L
        val pressOn = power + 1_000L
        val stop = power + 1_300L
        assertEquals(pressOn, sosScreenOn(pressOn, power, off))
        assertTrue(sosGestureLikely(sosScreenOn(pressOn, power, off), stop, sosAppPresent = true))
        assertEquals("SOS wins at the look", none, powerRecheck(true, false, power, stop, powerWake = powerWokeScreen(power, pressOn, off), sosMarked = true))
        // The screen already on at the connect (lit 5 s ago by a press): the connect exempts nothing.
        val litBefore = power - 5_000L
        assertEquals(litBefore, sosScreenOn(litBefore, power, dark))
        assertFalse(powerWokeScreen(power, litBefore, dark))
    }

    @Test
    fun `a screen-on 2-3 s before the plug-in - the SOS rule wins, as documented`() {
        val pressOn = power - 2_500L
        val lost = power + 100L
        assertFalse(powerWokeScreen(power, pressOn, dark))
        assertTrue("still in the SOS window", sosGestureLikely(sosScreenOn(pressOn, power, dark), lost, sosAppPresent = true))
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
        assertTrue(receiver.contains("handler.removeCallbacks(powerCheck)"))
        assertTrue(receiver.contains("handler.postDelayed(powerCheck, POWER_RECHECK_MS)"))
        val check = runtime.substringAfter("private val powerCheck").substringBefore("\n    }\n")
        assertTrue(check.contains("powerRecheck(chromeLocked, lockResumed, powerAt, lockPausedAt, powerWake = wake, sosMarked = marked)"))
        assertTrue("own mode keeps its back-off", check.contains("if (AndroidLock.faceOn) refrontAttempt = 0"))
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

    /** The runtime's count and its checks, as `PinLockRuntime` runs them (the glue test below pins the calls). */
    private class Face(val inputs: RefrontInputs, var count: Int = 0) {
        val refronts = mutableListOf<RefrontAction>()

        fun event(event: RefrontCount) {
            count = androidRefrontsAfter(count, event)
        }

        /** A check: true when it re-fronted the glance. */
        fun check(fresh: Boolean = true): Boolean {
            val action = refrontAction(inputs.copy(androidRefronts = count, androidFreshLoss = fresh), attempt = 0)
            refronts += action
            if (action is RefrontAction.Refront && action.noUserAction) {
                event(RefrontCount.REFRONT)
                return true
            }
            return false
        }

        /** The power change's look ([powerRecheck]) after a loss at [lostAt]; the glance not in front. */
        fun look(powerAt: Long, lostAt: Long, powerWake: Boolean, sosMarked: Boolean): Boolean {
            if (!powerRecheck(true, false, powerAt, lostAt, powerWake, sosMarked).freshLoss) return false
            event(RefrontCount.POWER_LOOK)
            return check()
        }
    }

    @Test
    fun `the count - zeroed at a screen-off, USER_PRESENT and a power change, the look keeps one`() {
        assertEquals(0, androidRefrontsAfter(2, RefrontCount.SCREEN_OFF))
        assertEquals(0, androidRefrontsAfter(2, RefrontCount.USER_PRESENT))
        assertEquals(0, androidRefrontsAfter(ANDROID_REFRONT_CAP, RefrontCount.POWER_CHANGE))
        assertEquals(1, androidRefrontsAfter(0, RefrontCount.REFRONT))
        assertEquals(ANDROID_REFRONT_CAP - 1, androidRefrontsAfter(ANDROID_REFRONT_CAP, RefrontCount.POWER_LOOK))
        assertEquals(ANDROID_REFRONT_CAP - 1, androidRefrontsAfter(9, RefrontCount.POWER_LOOK))
        assertEquals("the look never raises it", 0, androidRefrontsAfter(0, RefrontCount.POWER_LOOK))
    }

    @Test
    fun `three plug-ins within 10 s on one screen-on each get the glance back`() {
        // Before: the count ran over the whole screen-on - the third plug-in's pause met the cap.
        val before = Face(faceLoss)
        val beforeOutcomes = (1..3).map { before.check() }
        assertEquals(listOf(true, true, false), beforeOutcomes)

        // Now: each change starts a count of its own. The owner's third cycle: broadcast, paused +300 ms, the pause's
        // check at +500 ms - and the look at +1 s finds the glance in front (nothing to do).
        val face = Face(faceLoss)
        val back = (1..3).map {
            face.event(RefrontCount.POWER_CHANGE)
            face.check()
        }
        assertEquals(listOf(true, true, true), back)
        assertEquals("one re-front each", 1, face.count)
    }

    @Test
    fun `the broadcast after the loss - the pause's check meets the cap, the look gets the glance back`() {
        val face = Face(faceLoss)
        assertTrue(face.check())
        assertTrue(face.check())
        // The third plug-in's pause comes before its broadcast (the emulator run: 107 ms before Android sent it, and the
        // queue delivered it to us 501 ms later - the glance had lost the front): the pause's check meets the cap.
        val lost = 20_000L
        assertFalse(face.check())
        face.event(RefrontCount.POWER_CHANGE)
        assertTrue("the look, 1 s after the stamp", face.look(powerAt = lost + 608L, lostAt = lost, powerWake = false, sosMarked = false))    }

    @Test
    fun `the pause's and the stop's checks use the change's count - the look still re-fronts once`() {
        val face = Face(faceLoss)
        val power = 30_000L
        face.event(RefrontCount.POWER_CHANGE)
        assertTrue("the pause", face.check())
        assertTrue("the stop", face.check())
        assertTrue("the look", face.look(power, lostAt = power + 660L, powerWake = false, sosMarked = false))
        assertFalse("then the cap until the next change", face.check())
        assertFalse("a re-front's follow-up is no fresh loss", face.check(fresh = false))
    }

    @Test
    fun `no loop - a blocked start that takes the front after every re-front gets at most the cap plus one per change`() {
        val face = Face(faceLoss)
        val power = 40_000L
        face.event(RefrontCount.POWER_CHANGE)
        val beforeLook = (1..10).count { face.check() }
        val atLook = if (face.look(power, lostAt = power + 300L, powerWake = false, sosMarked = false)) 1 else 0
        val after = (1..10).count { face.check() }
        assertEquals(ANDROID_REFRONT_CAP + 1, beforeLook + atLook + after)
        // Only a new power change - a plug or an unplug - opens a new count.
        face.event(RefrontCount.POWER_CHANGE)
        assertEquals(ANDROID_REFRONT_CAP, (1..10).count { face.check() })
    }

    @Test
    fun `an SOS mark still wins - the change's count never re-fronts over it`() {
        val sos = Face(faceLoss.copy(sosFlow = true))
        sos.event(RefrontCount.POWER_CHANGE)
        assertFalse(sos.check())
        assertEquals(RefrontAction.Yield("sos"), sos.refronts.last())
        // The screen already on (a press 2.5 s before the plug-in): no power wake - the look leaves the mark alone.
        val power = 50_000L
        assertFalse(sos.look(power, lostAt = power + 100L, powerWake = false, sosMarked = true))
        assertEquals("the look applied nothing", 0, sos.count)
        assertEquals(RefrontAction.Yield("sos"), sos.refronts.last())
        // The calls, the emergency flow, the alarm and VoIP too.
        val call = Face(faceLoss.copy(telecomInCall = true))
        call.event(RefrontCount.POWER_CHANGE)
        assertFalse(call.check())
        assertEquals(RefrontAction.Yield("system_call"), call.refronts.last())
    }

    @Test
    fun `the glue - the broadcast and the look move the count, the look before its check`() {
        val runtime = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }
            .first { it.exists() }.readText()
        val receiver = runtime.substringAfter("private val powerReceiver").substringBefore("\n    }\n")
        val reset = receiver.indexOf("androidRefronts = androidRefrontsAfter(androidRefronts, RefrontCount.POWER_CHANGE)")
        assertTrue(reset in receiver.indexOf("powerChangedAt = SystemClock.elapsedRealtime()") until receiver.indexOf("handler.postDelayed(powerCheck, POWER_RECHECK_MS)"))
        val check = runtime.substringAfter("private val powerCheck").substringBefore("\n    }\n")
        val look = check.indexOf("androidRefronts = androidRefrontsAfter(androidRefronts, RefrontCount.POWER_LOOK)")
        assertTrue("only after a fresh loss", look > check.indexOf("if (!step.freshLoss) return@Runnable"))
        assertTrue(look < check.indexOf("runRefrontCheck()"))
    }
}
