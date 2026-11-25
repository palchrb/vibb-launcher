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
        assertFalse(sosMarkAtPause(ours = false, sosScreenOn(screenOn, power, dark), paused, sosAppPresent = true))
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
        assertTrue(check.contains("refrontAttempt = 0"))
        // A yield's pending recheck is replaced, not doubled: removed, then the check (which reposts one).
        assertTrue(check.indexOf("handler.removeCallbacks(refrontCheck)") in 0 until check.indexOf("markAndroidLoss()"))
        assertTrue(check.indexOf("markAndroidLoss()") < check.indexOf("runRefrontCheck()"))
    }
}
