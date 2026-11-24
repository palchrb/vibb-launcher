package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PLAN (4i), the charging glimpse: a plug-in's wake is no SOS gesture, and the connect's look is a fresh loss. */
class PowerGlanceTest {
    // The owner's log (elapsed ms from the battery update): battery 0, screen-on 104, the glance paused 490, stopped 874.
    private val power = 10_000L
    private val screenOn = 10_104L
    private val paused = 10_490L

    @Test
    fun `a screen-on near the power connect is the plug-in's, either order`() {
        assertTrue(powerWake(power, screenOn))
        assertTrue("the broadcast after the screen-on", powerWake(power, power - POWER_WAKE_MS))
        assertTrue(powerWake(power, power + POWER_WAKE_MS))
        assertFalse(powerWake(power, power + POWER_WAKE_MS + 1))
        assertFalse(powerWake(null, screenOn))
        assertFalse(powerWake(power, null))
    }

    @Test
    fun `the owner's log is no longer a probable SOS`() {
        assertTrue("before: the mark", sosGestureLikely(screenOn, paused, sosAppPresent = true))
        assertNull(sosScreenOn(screenOn, power))
        assertFalse(sosGestureLikely(sosScreenOn(screenOn, power), paused, sosAppPresent = true))
        assertFalse(sosMarkAtPause(androidFace = true, ours = false, sosScreenOn(screenOn, power), paused, sosAppPresent = true))
        assertEquals(PAUSE_REFRONT_MS, pauseRefrontDelayMs(sosScreenOn(screenOn, power), paused))
    }

    @Test
    fun `the power button's own wake keeps the SOS rule`() {
        assertEquals(screenOn, sosScreenOn(screenOn, null))
        assertEquals("charging since long ago", screenOn, sosScreenOn(screenOn, screenOn - 60_000L))
        assertTrue(sosGestureLikely(sosScreenOn(screenOn, screenOn - 60_000L), paused, sosAppPresent = true))
    }

    @Test
    fun `the connect's look - a fresh loss only while LOCKED, lost around the plug-in`() {
        assertEquals(PowerRecheck(clearSosMark = true, freshLoss = true), powerRecheck(true, false, power, screenOn, paused))
        assertEquals(
            "the screen was already on: a fresh loss, no mark of the plug-in's to end",
            PowerRecheck(clearSosMark = false, freshLoss = true),
            powerRecheck(true, false, power, screenOn - 60_000L, paused),
        )
        val none = PowerRecheck(clearSosMark = false, freshLoss = false)
        assertEquals("the glance in front", none, powerRecheck(true, true, power, screenOn, paused))
        assertEquals("not LOCKED", none, powerRecheck(false, false, power, screenOn, paused))
        assertEquals("never lost in this process", none, powerRecheck(true, false, power, screenOn, null))
        assertEquals("lost before the plug-in (the kid's PIN pad, a yield's end, the cap)", none, powerRecheck(true, false, power, screenOn, power - POWER_WAKE_MS - 1))
        assertEquals(PowerRecheck(true, true), powerRecheck(true, false, power, screenOn, power - POWER_WAKE_MS))
    }

    @Test
    fun `the fresh check goes through the face's rule - ours, the exemptions and the cap hold`() {
        val lost = RefrontInputs(
            locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
            emergencyFlow = false, alarmRinging = false, android = AndroidLoss.LOCKED, androidFreshLoss = true,
        )
        assertEquals(RefrontAction.Refront(2_000L, noUserAction = true), refrontAction(lost, 0))
        assertEquals("the kid's PIN pad", RefrontAction.Stop, refrontAction(lost.copy(androidOurs = true), 0))
        assertEquals("Android opened by the kid", RefrontAction.AndroidLeave, refrontAction(lost.copy(android = AndroidLoss.OPEN), 0))
        assertEquals(RefrontAction.Yield("system_call"), refrontAction(lost.copy(telecomInCall = true), 0))
        assertEquals(RefrontAction.Yield("voip"), refrontAction(lost.copy(voipCall = true), 0))
        assertEquals("the cap", RefrontAction.Stop, refrontAction(lost.copy(androidRefronts = ANDROID_REFRONT_CAP), 0))
        assertEquals("the screen off", RefrontAction.Stop, refrontAction(lost.copy(interactive = false), 0))
    }

    @Test
    fun `the glue - a runtime receiver, one look per connect, through the one check`() {
        val runtime = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }
            .first { it.exists() }.readText()
        assertTrue(runtime.contains("powerReceiver,\n            IntentFilter(Intent.ACTION_POWER_CONNECTED),\n            ContextCompat.RECEIVER_NOT_EXPORTED,"))
        val receiver = runtime.substringAfter("private val powerReceiver").substringBefore("\n    }\n")
        assertTrue(receiver.contains("handler.removeCallbacks(powerCheck)"))
        assertTrue(receiver.contains("handler.postDelayed(powerCheck, POWER_RECHECK_MS)"))
        val check = runtime.substringAfter("private val powerCheck").substringBefore("\n    }\n")
        assertTrue(check.contains("powerRecheck(chromeLocked, lockResumed, powerAt, lastScreenOnElapsed, lockPausedAt)"))
        assertTrue(check.indexOf("markAndroidLoss()") in 0 until check.indexOf("runRefrontCheck()"))
    }
}
