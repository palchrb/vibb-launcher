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
        assertEquals(at, rememberAlarm(null, at, clock, setOf(clock), at - 5))
        assertNull("an allowlisted app's setAlarmClock", rememberAlarm(null, at, "com.example.reminders", setOf(clock), at - 5))
        assertNull("no system clock app known", rememberAlarm(null, at, clock, emptySet(), at - 5))
        // Remembered while it rings, even when the next alarm (snooze) is already scheduled.
        assertEquals(at, rememberAlarm(at, at + 9 * 60_000L, clock, setOf(clock), at + 60_000L))
        assertEquals(at + 15 * 60_000L, rememberAlarm(at, at + 15 * 60_000L, clock, setOf(clock), at + ALARM_RING_MS))
        assertNull(rememberAlarm(null, null, null, setOf(clock), at))
        assertNull(rememberAlarm(at, null, null, setOf(clock), at + ALARM_RING_MS))
        // Design 42 §1.2: Fossify Clock's alarm opens the exemption only with its pinned signer.
        assertEquals(at, rememberAlarm(null, at, ClockApp.PACKAGE, lockClockPackages(clock, clockAppVerified = true), at - 5))
        assertNull("a wrong signer", rememberAlarm(null, at, ClockApp.PACKAGE, lockClockPackages(clock, clockAppVerified = false), at - 5))
        assertEquals("the system clock's stays", at, rememberAlarm(null, at, clock, lockClockPackages(clock, clockAppVerified = false), at - 5))
        // The lock back in front after yielding to the alarm ends the window.
        assertNull(alarmAfterResume(at, "alarm"))
        assertEquals(at, alarmAfterResume(at, null))
        assertEquals(at, alarmAfterResume(at, "call"))
    }

    /** Bug 2026-11-02 (a2): the alarm's yield lasts while a clock's ringing notification is up, at most 30 minutes. */
    @Test
    fun `a ringing alarm notification holds the yield while posted, bounded, spent once the lock is back`() {
        assertTrue(ringingAlarmShape(ALARM_CATEGORY, ongoing = true, fullScreen = true))
        // A snoozed, upcoming or missed alarm (no full-screen intent), a clearable one, another category: not ringing.
        assertFalse(ringingAlarmShape(ALARM_CATEGORY, ongoing = true, fullScreen = false))
        assertFalse(ringingAlarmShape(ALARM_CATEGORY, ongoing = false, fullScreen = true))
        assertFalse(ringingAlarmShape("call", ongoing = true, fullScreen = true))
        assertFalse(ringingAlarmShape(null, ongoing = true, fullScreen = true))

        val clocks = setOf("com.android.deskclock", "org.fossify.clock")
        val t = 1_000_000L
        val ringing = AlarmNotices().posted("k", "com.android.deskclock", ringingShape = true, nowMs = t)
        assertTrue(ringing.ringingNow(clocks, t))
        // Past the fixed 3-minute window - the bug: the lock came back over a ringing alarm.
        assertTrue(ringing.ringingNow(clocks, t + ALARM_RING_MS + 1))
        assertTrue(ringing.ringingNow(clocks, t + ALARM_NOTICE_MAX_MS - 1))
        assertFalse("a stuck notification holds no lock open", ringing.ringingNow(clocks, t + ALARM_NOTICE_MAX_MS))
        // An update of the same ringing notification keeps its first-seen time.
        val updated = ringing.posted("k", "com.android.deskclock", ringingShape = true, nowMs = t + ALARM_NOTICE_MAX_MS - 1)
        assertFalse(updated.ringingNow(clocks, t + ALARM_NOTICE_MAX_MS))
        // Only the lock's clock apps: an allowlisted app's alarm-shaped notification never opens it.
        assertFalse(AlarmNotices().posted("g", "com.example.game", true, t).ringingNow(clocks, t))
        // Removed, or posted again without ringing (a snooze): gone at once.
        assertFalse(ringing.removed("k").ringingNow(clocks, t + 1))
        val snoozed = ringing.posted("k", "com.android.deskclock", ringingShape = false, nowMs = t + 1)
        assertFalse(snoozed.ringingNow(clocks, t + 1))
        assertEquals(AlarmNotices(), snoozed)
        // The lock back in front after yielding to the alarm spends it (qa-10-code #4) - another yield doesn't.
        assertTrue(ringing.afterResume("call").ringingNow(clocks, t + 1))
        assertTrue(ringing.afterResume(null).ringingNow(clocks, t + 1))
        val spent = ringing.afterResume("alarm")
        assertFalse(spent.ringingNow(clocks, t + 1))
        assertFalse("an update stays spent", spent.posted("k", "com.android.deskclock", true, t + 2).ringingNow(clocks, t + 2))
        // ... until it stops ringing: the snoozed alarm ringing again yields again, with a fresh bound.
        val again = spent.posted("k", "com.android.deskclock", false, t + 3).posted("k", "com.android.deskclock", true, t + 4)
        assertTrue(again.ringingNow(clocks, t + 4 + ALARM_NOTICE_MAX_MS - 1))
        // A second alarm ringing after the first was spent yields.
        assertTrue(spent.posted("k2", "org.fossify.clock", true, t + 5).ringingNow(clocks, t + 5))
        // The connect sweep: exactly the active ringing ones, keeping first-seen times and spent keys.
        val swept = spent.swept(listOf(Triple("k", "com.android.deskclock", true), Triple("k3", "org.fossify.clock", false)), t + 6)
        assertEquals(setOf("k"), swept.ringing.keys)
        assertEquals(t, swept.ringing.getValue("k").sinceMs)
        assertEquals(setOf("k"), swept.consumed)
        assertEquals(AlarmNotices(), spent.swept(emptyList(), t + 6))
        // The yield: the notification, or as before the window after the alarm's time (no listener, another clock).
        assertTrue(alarmRingingNow(noticeRinging = true, rememberedTriggerMs = null, nowWallMs = t))
        assertTrue(alarmRingingNow(noticeRinging = false, rememberedTriggerMs = t, nowWallMs = t + 1))
        assertFalse(alarmRingingNow(noticeRinging = false, rememberedTriggerMs = t, nowWallMs = t + ALARM_RING_MS))
        assertFalse(alarmRingingNow(noticeRinging = false, rememberedTriggerMs = null, nowWallMs = t))
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
