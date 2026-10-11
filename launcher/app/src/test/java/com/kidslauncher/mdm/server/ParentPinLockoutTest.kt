package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.BackoffWindow
import com.kidslauncher.mdm.lock.LockClocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 24 §2.1: the parent PIN's escalating lockout, its decay and its clocks. */
class ParentPinLockoutTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val fp = "fp-1"

    private fun at(wall: Long, elapsed: Long = wall, boot: Int = 7) = LockClocks(wall, elapsed, boot)

    /** [n] wrong tries at [now], each committed like ParentPin does. */
    private fun wrong(state: ParentLockoutState, now: LockClocks, n: Int = 1): ParentLockoutState =
        (1..n).fold(state) { s, _ -> beginParentAttempt(refreshParentLockout(s, now), now, ParentPinEntry.SETTINGS, "id").state }

    @Test
    fun `lockout durations double from 15 minutes to the 24 hour cap`() {
        val expected = listOf(15 * min, 30 * min, hour, 2 * hour, 4 * hour, 8 * hour, 16 * hour, 24 * hour)
        assertEquals(expected, (1..8).map(::parentLockoutMs))
        // Level 0 (a shift of -1 would use only its low bits) and anything above 8 stay in range.
        assertEquals(15 * min, parentLockoutMs(0))
        for (level in listOf(-3, 9, 31, 64, Int.MAX_VALUE)) {
            assertTrue("$level", parentLockoutMs(level) in 15 * min..24 * hour)
        }
        assertEquals(24 * hour, parentLockoutMs(PARENT_LEVEL_MAX))
    }

    @Test
    fun `the fifth wrong try locks - the count and its event come first`() {
        val now = at(1_000_000L)
        var state = ParentLockoutState(pinFingerprint = fp)
        for (n in 1..4) {
            val attempt = beginParentAttempt(state, now, ParentPinEntry.PAUSE, "w$n")
            // Count first (QA #6): the counted state already carries the failure and its event.
            assertEquals(n, attempt.state.failures)
            assertEquals(0L, attempt.lockoutMs)
            assertEquals(PIN_EVENT_WRONG, attempt.event.kind)
            assertEquals("pause", attempt.event.entry)
            assertEquals(n, attempt.event.failures)
            assertNull(attempt.event.level)
            assertNull(attempt.event.untilMs)
            assertEquals(ParentPinResult.Wrong(5 - n, 15 * min), wrongResult(attempt.state, now))
            state = attempt.state
        }
        val fifth = beginParentAttempt(state, now, ParentPinEntry.PAUSE, "w5")
        assertEquals(15 * min, fifth.lockoutMs)
        assertEquals(0, fifth.state.failures)
        assertEquals(15 * min, parentLockoutRemaining(fifth.state, now))
        assertEquals(1, levelNow(fifth.state, now))
        assertEquals(PIN_EVENT_LOCKOUT, fifth.event.kind)
        assertEquals(1, fifth.event.level)
        assertEquals(now.wallMs + 15 * min, fifth.event.untilMs)
        assertNull(fifth.event.failures)
    }

    @Test
    fun `the next five wrong tries give level 2 - and after them the last try names 1 hour`() {
        var now = at(1_000_000L)
        var state = wrong(ParentLockoutState(pinFingerprint = fp), now, 5)
        assertEquals(1, levelNow(state, now))
        now = at(now.wallMs + 15 * min)
        assertEquals(0L, parentLockoutRemaining(state, now))
        state = wrong(state, now, 4)
        // The level is kept for the tries in between, so the last-try warning names the next one.
        assertEquals(ParentPinResult.Wrong(1, 30 * min), wrongResult(state, now))
        state = wrong(state, now, 1)
        assertEquals(2, levelNow(state, now))
        assertEquals(30 * min, parentLockoutRemaining(state, now))
        now = at(now.wallMs + 30 * min)
        state = wrong(state, now, 5)
        assertEquals(3, levelNow(state, now))
        assertEquals(hour, parentLockoutRemaining(state, now))
    }

    @Test
    fun `the level stops at 8 and the lockout at 24 hours`() {
        var now = at(0L)
        var state = ParentLockoutState(pinFingerprint = fp)
        var level = 0
        repeat(12) {
            state = wrong(state, now, 5)
            level = levelNow(state, now)
            now = at(now.wallMs + parentLockoutRemaining(state, now))
        }
        assertEquals(PARENT_LEVEL_MAX, level)
        assertEquals(24 * hour, state.lockout!!.durationMs)
        assertEquals(PARENT_LEVEL_MAX * day, state.decay!!.durationMs)
    }

    @Test
    fun `a new fingerprint resets everything`() {
        val now = at(5 * day)
        val locked = wrong(ParentLockoutState(pinFingerprint = fp), now, 5 + 2)
        assertEquals(locked, forParentPin(locked, fp))
        assertEquals(ParentLockoutState(pinFingerprint = "fp-2"), forParentPin(locked, "fp-2"))
    }

    @Test
    fun `the level decays one step per quiet 24 hours, and a wrong try restarts the decay`() {
        val start = at(10 * day)
        val level3 = ParentLockoutState(
            decay = BackoffWindow(start.wallMs, start.elapsedMs, start.bootCount, 3 * day), pinFingerprint = fp,
        )
        assertEquals(3, levelNow(level3, at(start.wallMs + 1)))
        assertEquals(3, levelNow(level3, at(start.wallMs + day - 1)))
        assertEquals(2, levelNow(level3, at(start.wallMs + day + 1)))
        assertEquals(1, levelNow(level3, at(start.wallMs + 2 * day + 1)))
        assertEquals(0, levelNow(level3, at(start.wallMs + 3 * day)))

        // A wrong try after 25 h: level 2 now, and its decay starts again (2 days).
        val later = at(start.wallMs + 25 * hour)
        val counted = beginParentAttempt(level3, later, ParentPinEntry.OVERRIDE, "id").state
        assertEquals(2, levelNow(counted, later))
        assertEquals(2 * day, counted.decay!!.durationMs)
        assertEquals(2, levelNow(counted, at(later.wallMs + day - 1)))
        // Its next lockout is level 3's.
        assertEquals(hour, wrongResult(counted, later).nextLockoutMs)

        // No level: no decay window at all.
        val fresh = beginParentAttempt(ParentLockoutState(pinFingerprint = fp), later, ParentPinEntry.OVERRIDE, "id").state
        assertEquals(0, levelNow(fresh, later))
        assertNull(fresh.decay)
    }

    @Test
    fun `clock changes within a boot neither speed up the decay nor stretch a window`() {
        val start = at(wall = 100 * day, elapsed = 50 * hour)
        val state = ParentLockoutState(
            lockout = BackoffWindow(start.wallMs, start.elapsedMs, start.bootCount, 30 * min),
            decay = BackoffWindow(start.wallMs, start.elapsedMs, start.bootCount, 2 * day),
            pinFingerprint = fp,
        )
        // The wall clock jumps 3 days forward after 1 minute: elapsed realtime keeps both.
        val forward = at(wall = start.wallMs + 3 * day, elapsed = start.elapsedMs + min)
        assertEquals(state, refreshParentLockout(state, forward))
        assertEquals(29 * min, parentLockoutRemaining(state, forward))
        assertEquals(2, levelNow(state, forward))
        // Moved back: the decay doesn't speed up, and nothing is longer than its duration.
        val back = at(wall = start.wallMs - 10 * day, elapsed = start.elapsedMs + 25 * hour)
        assertEquals(1, levelNow(state, back))
        assertEquals(0L, parentLockoutRemaining(state, back))
        val slightlyBack = at(wall = start.wallMs + 5 * min, elapsed = start.elapsedMs + 20 * min)
        assertEquals(25 * min, parentLockoutRemaining(state, slightlyBack))
        assertTrue(parentLockoutRemaining(state, at(wall = start.wallMs - day, elapsed = start.elapsedMs)) <= 30 * min)
    }

    @Test
    fun `a reboot carries each window over by the wall clock and re-anchors it`() {
        val start = at(wall = 100 * day, elapsed = 50 * hour, boot = 7)
        val state = ParentLockoutState(
            lockout = BackoffWindow(start.wallMs, start.elapsedMs, start.bootCount, 4 * hour),
            decay = BackoffWindow(start.wallMs, start.elapsedMs, start.bootCount, 5 * day),
            pinFingerprint = fp,
        )
        // An hour later, a new boot: 3 h of the lockout and 4 days 23 h of decay are left.
        val reboot = at(wall = start.wallMs + hour, elapsed = 2 * min, boot = 8)
        val refreshed = refreshParentLockout(state, reboot)
        assertEquals(BackoffWindow(reboot.wallMs, reboot.elapsedMs, 8, 3 * hour), refreshed.lockout)
        assertEquals(BackoffWindow(reboot.wallMs, reboot.elapsedMs, 8, 5 * day - hour), refreshed.decay)
        assertEquals(3 * hour, parentLockoutRemaining(refreshed, reboot))
        assertEquals(5, levelNow(refreshed, reboot))
        // Within the new boot the usual rules hold again (refresh changes nothing).
        assertEquals(refreshed, refreshParentLockout(refreshed, at(reboot.wallMs + min, reboot.elapsedMs + min, 8)))

        // The wall clock went back across the reboot: the full duration, never more.
        val back = refreshParentLockout(state, at(wall = start.wallMs - day, elapsed = min, boot = 8))
        assertEquals(4 * hour, back.lockout!!.durationMs)
        assertEquals(5 * day, back.decay!!.durationMs)
        // Past the end: no window; the same boot count with elapsed realtime gone back is a reboot too.
        val over = refreshParentLockout(state, at(wall = start.wallMs + 6 * day, elapsed = min, boot = 7))
        assertNull(over.lockout)
        assertNull(over.decay)
        assertEquals(0, levelNow(over, at(start.wallMs + 6 * day, min, 7)))
    }

    @Test
    fun `migration - a running lockout becomes level 1 for at most 15 minutes, and attempts carry over`() {
        val now = at(1_000 * day, elapsed = hour)
        val running = migrateParentLockout(failedAttempts = 0, lockedUntilWallMs = now.wallMs + 10 * min, now = now, fingerprint = fp)
        assertEquals(1, levelNow(running, now))
        assertEquals(10 * min, parentLockoutRemaining(running, now))
        assertEquals(day, running.decay!!.durationMs)
        assertEquals(fp, running.pinFingerprint)
        // A far-future value from a bad clock is capped at 15 minutes.
        val far = migrateParentLockout(0, now.wallMs + 365 * day, now, fp)
        assertEquals(15 * min, parentLockoutRemaining(far, now))
        // Over: no window, no level; the old count carries over (at most 4).
        assertEquals(ParentLockoutState(failures = 3, pinFingerprint = fp), migrateParentLockout(3, now.wallMs - 1, now, fp))
        assertEquals(4, migrateParentLockout(9, 0L, now, fp).failures)
        assertEquals(0, migrateParentLockout(-2, 0L, now, fp).failures)
    }

    @Test
    fun `an unreadable state becomes a 15-minute lockout with fresh tries after it`() {
        val now = at(3 * day)
        val state = unreadableParentState(now, fp)
        assertEquals(15 * min, parentLockoutRemaining(state, now))
        assertEquals(1, levelNow(state, now))
        assertEquals(0, state.failures)
        assertEquals(0L, parentLockoutRemaining(state, at(now.wallMs + 15 * min)))
    }

    @Test
    fun `lockout text is rounded up to the minute`() {
        assertEquals("15 min", lockoutText(15 * min))
        assertEquals("15 min", lockoutText(14 * min + 1))
        assertEquals("1 min", lockoutText(1L))
        assertEquals("1 h 45 min", lockoutText(hour + 45 * min))
        assertEquals("1 h", lockoutText(hour))
        assertEquals("24 h", lockoutText(24 * hour))
        assertEquals("2 t 1 min", lockoutText(2 * hour + 1, hourUnit = "t"))
    }
}
