package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handy step 10, QA 10 #2/#11/#14: re-front never gives up except for the named exempt screens;
 * the crash guard. */
class RefrontPolicyTest {
    private val lostFront = RefrontInputs(
        locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
        emergencyFlow = false, alarmRinging = false,
    )

    @Test
    fun `any other front task is re-fronted at 0,5 s, 2 s, then every 5 s - forever`() {
        assertEquals(500L, refrontDelayMs(0))
        assertEquals(RefrontAction.Refront(2_000L), refrontAction(lostFront, 0))
        assertEquals(RefrontAction.Refront(5_000L), refrontAction(lostFront, 1))
        assertEquals(RefrontAction.Refront(5_000L), refrontAction(lostFront, 2))
        assertEquals("never stops", RefrontAction.Refront(5_000L), refrontAction(lostFront, 10_000))
    }

    @Test
    fun `only our call, the system dialer, the emergency flow and the alarm are exempt`() {
        assertEquals(RefrontAction.Yield("call"), refrontAction(lostFront.copy(ourCall = true), 3))
        assertEquals(RefrontAction.Yield("system_call"), refrontAction(lostFront.copy(telecomInCall = true), 0))
        assertEquals(RefrontAction.Yield("emergency"), refrontAction(lostFront.copy(emergencyFlow = true), 0))
        assertEquals(RefrontAction.Yield("alarm"), refrontAction(lostFront.copy(alarmRinging = true), 0))
        assertEquals("design 17: a VoIP call rings or lives", RefrontAction.Yield("voip"), refrontAction(lostFront.copy(voipCall = true), 0))
        // qa-16-17 #1: a phone call, the emergency flow and the alarm win over the VoIP state - the
        // alarm's exemption only ends after a yield named "alarm".
        assertEquals(RefrontAction.Yield("system_call"), refrontAction(lostFront.copy(voipCall = true, telecomInCall = true), 0))
        assertEquals(RefrontAction.Yield("call"), refrontAction(lostFront.copy(voipCall = true, ourCall = true), 0))
        assertEquals(RefrontAction.Yield("emergency"), refrontAction(lostFront.copy(voipCall = true, emergencyFlow = true), 0))
        assertEquals(RefrontAction.Yield("alarm"), refrontAction(lostFront.copy(voipCall = true, alarmRinging = true), 0))
        assertEquals(null, alarmAfterResume(1_000L, "alarm"))
        assertEquals(RefrontAction.Stop, refrontAction(lostFront.copy(voipCall = true, locked = false), 0))
    }

    @Test
    fun `nothing to do when unlocked, in front or with the screen off`() {
        assertEquals(RefrontAction.Stop, refrontAction(lostFront.copy(locked = false), 0))
        assertEquals(RefrontAction.Stop, refrontAction(lostFront.copy(lockResumed = true), 0))
        assertEquals(RefrontAction.Stop, refrontAction(lostFront.copy(interactive = false), 0))
    }

    @Test
    fun `a configuration change or the finish after the PIN isn't losing the front`() {
        assertTrue(stopStartsRefront(locked = true, changingConfigurations = false, finishing = false))
        assertFalse(stopStartsRefront(locked = true, changingConfigurations = true, finishing = false))
        assertFalse(stopStartsRefront(locked = true, changingConfigurations = false, finishing = true))
        assertFalse(stopStartsRefront(locked = false, changingConfigurations = false, finishing = false))
    }

    @Test
    fun `only the system clock app's alarm opens the exemption, for a short window (qa-10-code 4)`() {
        val at = 1_000_000L
        val clock = "com.google.android.deskclock"
        assertFalse(alarmLikelyRinging(null, at))
        assertFalse(alarmLikelyRinging(at, at - 1))
        assertTrue(alarmLikelyRinging(at, at))
        assertTrue(alarmLikelyRinging(at, at + ALARM_RING_MS - 1))
        assertFalse(alarmLikelyRinging(at, at + ALARM_RING_MS))
        assertTrue(ALARM_RING_MS <= 5 * 60_000L)
        assertEquals(at, rememberAlarm(null, at, clock, clock, at - 5))
        assertNull("an allowlisted app's setAlarmClock", rememberAlarm(null, at, "com.example.reminders", clock, at - 5))
        assertNull("no system clock app known", rememberAlarm(null, at, clock, null, at - 5))
        // Remembered while it rings, even when the next alarm (snooze) is already scheduled.
        assertEquals(at, rememberAlarm(at, at + 9 * 60_000L, clock, clock, at + 60_000L))
        assertEquals(at + 15 * 60_000L, rememberAlarm(at, at + 15 * 60_000L, clock, clock, at + ALARM_RING_MS))
        assertNull(rememberAlarm(null, null, null, clock, at))
        assertNull(rememberAlarm(at, null, null, clock, at + ALARM_RING_MS))
        // The lock back in front after yielding to the alarm ends the window.
        assertNull(alarmAfterResume(at, "alarm"))
        assertEquals(at, alarmAfterResume(at, null))
        assertEquals(at, alarmAfterResume(at, "call"))
    }

    @Test
    fun `crash guard - three crashes in two minutes trip it at the next start, a sync re-arms after 10 minutes`() {
        val t = 5_000_000L
        var guard = CrashGuard()
        repeat(5) { guard = guardOnCreate(guard, t + it) }
        assertNull("starts alone - recreations, kills - never count (qa-10-code 6)", guard.trippedAtMs)
        guard = guardOnCrash(guardOnCrash(guard, t + 10_000L), t + 20_000L)
        assertNull(guardOnCreate(guard, t + 25_000L).trippedAtMs)
        guard = guardOnCrash(guard, t + 30_000L)
        guard = guardOnCreate(guard, t + 31_000L)
        assertEquals(t + 31_000L, guard.trippedAtMs)
        assertEquals("stays tripped", guard, guardOnCreate(guard, t + 40_000L))
        assertEquals(guard, guardRearm(guard, t + 31_000L + GUARD_REARM_MS - 1))
        assertEquals(CrashGuard(), guardRearm(guard, t + 31_000L + GUARD_REARM_MS))
    }

    @Test
    fun `old crashes don't count`() {
        val t = 5_000_000L
        var guard = guardOnCrash(guardOnCrash(guardOnCrash(CrashGuard(), t), t + 1), t + 2)
        guard = guardOnCreate(guard, t + GUARD_WINDOW_MS + 10)
        assertNull(guard.trippedAtMs)
        assertEquals(emptyList<Long>(), guard.crashes)
    }
}
