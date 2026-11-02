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

    /** Bug 2026-11-02 (a2): the alarm's yield lasts while a clock's ringing notification is up, at most 15 minutes. */
    @Test
    fun `a ringing alarm notification holds the yield while posted, bounded, spent once the lock is back`() {
        // Code QA H1: Fossify's ringing alarm (startForeground, no setOngoing: not ongoing on API 34+) and a Google/AOSP
        // clock's (ongoing) are both category alarm with a full-screen intent - ongoing doesn't matter.
        assertTrue("Fossify's and Google's alike", ringingAlarmShape(ALARM_CATEGORY, fullScreen = true))
        // A snoozed, upcoming or missed alarm (no full-screen intent), another category: not ringing.
        assertFalse(ringingAlarmShape(ALARM_CATEGORY, fullScreen = false))
        assertFalse(ringingAlarmShape("call", fullScreen = true))
        assertFalse("Fossify's ended timer is CATEGORY_EVENT (the tile's Stopp)", ringingAlarmShape("event", fullScreen = false))
        assertFalse(ringingAlarmShape(null, fullScreen = true))

        val clocks = setOf("com.android.deskclock", "org.fossify.clock")
        val t = 1_000_000L
        fun post(key: String = "k", pkg: String = "com.android.deskclock", rings: Boolean = true, at: Long = 77L) = AlarmPost(key, pkg, rings, at)
        val ringing = AlarmNotices().posted(post(), t)
        assertTrue(ringing.ringingNow(clocks, t))
        // Past the fixed 3-minute window - the bug: the lock came back over a ringing alarm.
        assertTrue(ringing.ringingNow(clocks, t + ALARM_RING_MS + 1))
        assertTrue(ringing.ringingNow(clocks, t + ALARM_NOTICE_MAX_MS - 1))
        assertFalse("a stuck notification holds no lock open", ringing.ringingNow(clocks, t + ALARM_NOTICE_MAX_MS))
        assertEquals(15 * 60_000L, ALARM_NOTICE_MAX_MS)
        // An update of the same ring (same `when`) keeps its first-seen time.
        val updated = ringing.posted(post(), t + ALARM_NOTICE_MAX_MS - 1)
        assertEquals(ringing, updated)
        assertFalse(updated.ringingNow(clocks, t + ALARM_NOTICE_MAX_MS))
        // Only the lock's clock apps: an allowlisted app's alarm-shaped notification never opens it.
        assertFalse(AlarmNotices().posted(post("g", "com.example.game"), t).ringingNow(clocks, t))
        // Removed, or posted again without ringing (a snooze): gone at once.
        assertFalse(ringing.removed("k").ringingNow(clocks, t + 1))
        val snoozed = ringing.posted(post(rings = false), t + 1)
        assertFalse(snoozed.ringingNow(clocks, t + 1))
        assertEquals(AlarmNotices(), snoozed)
        // The lock back in front after yielding to the alarm spends it (qa-10-code #4) - another yield doesn't.
        assertTrue(ringing.afterResume("call").ringingNow(clocks, t + 1))
        assertTrue(ringing.afterResume(null).ringingNow(clocks, t + 1))
        val spent = ringing.afterResume("alarm")
        assertFalse(spent.ringingNow(clocks, t + 1))
        assertFalse("an update of the same ring stays spent", spent.posted(post(), t + 2).ringingNow(clocks, t + 2))
        // ... until it stops ringing: the snoozed alarm ringing again yields again, with a fresh bound.
        val again = spent.posted(post(rings = false), t + 3).posted(post(), t + 4)
        assertTrue(again.ringingNow(clocks, t + 4 + ALARM_NOTICE_MAX_MS - 1))
        // Code QA L1: Fossify rings every alarm under one key - a new `when` is a new ring: fresh bound, not spent.
        val fossify = AlarmNotices().posted(post("f", "org.fossify.clock", at = 1L), t).afterResume("alarm")
        assertFalse(fossify.ringingNow(clocks, t + 1))
        val nextAlarm = fossify.posted(post("f", "org.fossify.clock", at = 2L), t + 10 * 60_000L)
        assertTrue(nextAlarm.ringingNow(clocks, t + 10 * 60_000L + ALARM_NOTICE_MAX_MS - 1))
        // A second alarm ringing after the first was spent yields.
        assertTrue(spent.posted(post("k2", "org.fossify.clock"), t + 5).ringingNow(clocks, t + 5))
        // The connect sweep: exactly the active ringing ones, keeping first-seen times and spent keys of the same rings.
        val swept = spent.swept(listOf(post(), post("k3", "org.fossify.clock", rings = false)), t + 6)
        assertEquals(setOf("k"), swept.ringing.keys)
        assertEquals(t, swept.ringing.getValue("k").sinceMs)
        assertEquals(setOf("k"), swept.consumed)
        val sweptNewRing = spent.swept(listOf(post(at = 78L)), t + 6)
        assertEquals(t + 6, sweptNewRing.ringing.getValue("k").sinceMs)
        assertTrue(sweptNewRing.consumed.isEmpty())
        assertEquals(AlarmNotices(), spent.swept(emptyList(), t + 6))
        // The yield: the notification, or as before the window after the alarm's time (no listener, another clock).
        assertTrue(alarmRingingNow(noticeRinging = true, rememberedTriggerMs = null, nowWallMs = t))
        assertTrue(alarmRingingNow(noticeRinging = false, rememberedTriggerMs = t, nowWallMs = t + 1))
        assertFalse(alarmRingingNow(noticeRinging = false, rememberedTriggerMs = t, nowWallMs = t + ALARM_RING_MS))
        assertFalse(alarmRingingNow(noticeRinging = false, rememberedTriggerMs = null, nowWallMs = t))
    }

    /** The gap closed 2026-11-02: power pressed while the alarm rings - the lock-up covers it; the resumed lock sends
     * the still-ringing alarm's screen again, capped, and the screen that comes up is yielded to. */
    @Test
    fun `the resumed lock brings a still-ringing alarm's screen back, capped, only while posted`() {
        val clocks = setOf("com.android.deskclock")
        val t = 2_000_000L
        fun post(key: String = "k", pkg: String = "com.android.deskclock", rings: Boolean = true, at: Long = 5L) = AlarmPost(key, pkg, rings, at)
        val ringing = AlarmNotices().posted(post(), t)
        assertEquals("k", ringing.screenKey(clocks, t + 1))
        // Spent by the lock's resume after the yield - still the one to bring back (the settle's lookup tells Stop apart).
        val spent = ringing.afterResume("alarm")
        assertEquals("k", spent.screenKey(clocks, t + 1))
        // Stop: the listener no longer has it - nothing to send.
        assertNull(spent.reconciled(emptySet()).screenKey(clocks, t + 1))
        assertEquals(AlarmNotices(), spent.reconciled(emptySet()))
        assertEquals(spent, spent.reconciled(setOf("k")))
        // Never another app's, never past the bound.
        assertNull(AlarmNotices().posted(post("g", "com.example.game"), t).screenKey(clocks, t))
        assertNull(ringing.screenKey(clocks, t + ALARM_NOTICE_MAX_MS))
        // A sent screen opens the yield again for it (the re-front check steps aside); a failed one doesn't.
        val sent = spent.resent("k", sent = true)
        assertTrue(sent.ringingNow(clocks, t + 1))
        assertFalse(spent.resent("k", sent = false).ringingNow(clocks, t + 1))
        // Code QA L2: the lock still in front after the check - nothing came up, the yield closes again.
        assertFalse(sent.spent("k").ringingNow(clocks, t + 1))
        assertEquals(sent.sends, sent.spent("k").sends)
        assertEquals(sent, sent.spent("gone"))
        // The cap: a clock screen that closes at once while its notification stays can't loop.
        var notices = ringing
        repeat(ALARM_SCREEN_MAX_SENDS) {
            assertEquals("k", notices.screenKey(clocks, t + 1))
            notices = notices.resent("k", sent = true)
        }
        assertNull(notices.screenKey(clocks, t + 1))
        // The newest of two; a snooze's re-post or a new ring clears the count, so the alarm is brought back again.
        assertEquals("k2", notices.posted(post("k2"), t + 10).screenKey(clocks, t + 11))
        val rangAgain = notices.posted(post(rings = false), t + 20).posted(post(), t + 30)
        assertEquals("k", rangAgain.screenKey(clocks, t + 31))
        assertEquals("k", notices.posted(post(at = 6L), t + 30).screenKey(clocks, t + 31))
        assertNull("an update of the same ring keeps the count", notices.posted(post(), t + 30).screenKey(clocks, t + 31))
        // The sweep keeps the counts of the same rings still active.
        assertEquals(ALARM_SCREEN_MAX_SENDS, notices.swept(listOf(post()), t + 40).sends["k"])

        // Revision R2: a re-send outstanding at a resume without an "alarm" yield (came up and closed at once) is spent.
        assertEquals("k", alarmSendSpentAtResume("k", null))
        assertEquals("k", alarmSendSpentAtResume("k", "call"))
        assertNull("the alarm yield's resume spends every ring itself", alarmSendSpentAtResume("k", "alarm"))
        assertNull(alarmSendSpentAtResume(null, null))
        // Revision R3: a ring up before the clock set was known - its key is asked for again, once the set names it.
        val early = AlarmNotices().posted(post(), t).posted(post("g", "com.example.game"), t)
        assertEquals(setOf("k"), early.screensMissing(have = emptySet(), clockPackages = clocks))
        assertEquals(emptySet<String>(), early.screensMissing(have = setOf("k"), clockPackages = clocks))
        assertEquals(emptySet<String>(), early.screensMissing(have = emptySet(), clockPackages = emptySet()))

        // The step: only LOCKED, screen on, no call or emergency flow; dark waits for the screen-on; typing the PIN
        // (code QA N4) and the cover's keyguard (N3) hold it for later.
        assertEquals(AlarmScreenStep.SEND, alarmScreenStep("k", locked = true, interactive = true, otherScreen = false))
        assertEquals(AlarmScreenStep.SCREEN_ON, alarmScreenStep("k", locked = true, interactive = false, otherScreen = false))
        assertEquals(AlarmScreenStep.LATER, alarmScreenStep("k", locked = true, interactive = true, otherScreen = false, typing = true))
        assertEquals(AlarmScreenStep.LATER, alarmScreenStep("k", locked = true, interactive = true, otherScreen = false, coverWaits = true))
        assertEquals(AlarmScreenStep.NONE, alarmScreenStep(null, locked = true, interactive = true, otherScreen = false, typing = true))
        assertEquals(AlarmScreenStep.NONE, alarmScreenStep("k", locked = false, interactive = true, otherScreen = false))
        assertEquals(AlarmScreenStep.NONE, alarmScreenStep("k", locked = true, interactive = true, otherScreen = true))
        assertEquals(AlarmScreenStep.NONE, alarmScreenStep("k", locked = true, interactive = false, otherScreen = true))
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
