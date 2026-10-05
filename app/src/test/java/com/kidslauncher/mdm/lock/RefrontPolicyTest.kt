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
    fun `the alarm counts as ringing from its time for 10 minutes, a snooze doesn't hide it`() {
        val at = 1_000_000L
        assertFalse(alarmLikelyRinging(null, at))
        assertFalse(alarmLikelyRinging(at, at - 1))
        assertTrue(alarmLikelyRinging(at, at))
        assertTrue(alarmLikelyRinging(at, at + ALARM_RING_MS - 1))
        assertFalse(alarmLikelyRinging(at, at + ALARM_RING_MS))
        // Remembered while it rings, even when the next alarm (snooze) is already scheduled.
        assertEquals(at, rememberAlarm(at, at + 9 * 60_000L, at + 60_000L))
        assertEquals(at + 15 * 60_000L, rememberAlarm(at, at + 15 * 60_000L, at + ALARM_RING_MS))
        assertEquals(at, rememberAlarm(null, at, at - 5))
        assertNull(rememberAlarm(null, null, at))
        assertNull(rememberAlarm(at, null, at + ALARM_RING_MS))
    }

    @Test
    fun `crash guard - three uncleared starts in two minutes trip it, a sync re-arms after 10 minutes`() {
        var guard = CrashGuard()
        val t = 5_000_000L
        guard = guardOnCreate(guard, t)
        guard = guardOnCreate(guard, t + 10_000L)
        guard = guardOnCreate(guard, t + 20_000L)
        assertNull("three crashes so far, this is the fourth start", guard.trippedAtMs)
        guard = guardOnCreate(guard, t + 30_000L)
        assertEquals(t + 30_000L, guard.trippedAtMs)
        assertEquals("stays tripped", guard, guardOnCreate(guard, t + 40_000L))
        assertEquals(guard, guardRearm(guard, t + 30_000L + GUARD_REARM_MS - 1))
        assertEquals(CrashGuard(), guardRearm(guard, t + 30_000L + GUARD_REARM_MS))
    }

    @Test
    fun `cleared starts and old ones don't count`() {
        var guard = CrashGuard()
        val t = 5_000_000L
        repeat(10) { i ->
            guard = guardCleared(guardOnCreate(guard, t + i * 1_000L))
        }
        assertNull(guard.trippedAtMs)
        guard = guardOnCreate(guardOnCreate(guardOnCreate(CrashGuard(), t), t + 1), t + 2)
        guard = guardOnCreate(guard, t + GUARD_WINDOW_MS + 10)
        assertNull("the three are older than two minutes", guard.trippedAtMs)
        assertEquals(1, guard.pendingStarts.size)
    }
}
