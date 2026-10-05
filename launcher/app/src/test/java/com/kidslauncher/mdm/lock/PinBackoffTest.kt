package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.timedWindowActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handy step 10 (design §5, QA 10 #3): the wrong-PIN wait never gets shorter. */
class PinBackoffTest {
    private val day = 24 * 60 * 60_000L
    private val t0 = LockClocks(wallMs = 1_759_650_000_000L, elapsedMs = 50_000L, bootCount = 7)

    private fun later(ms: Long, wallShift: Long = 0L, boot: Int = t0.bootCount, elapsed: Long? = null) =
        LockClocks(t0.wallMs + ms + wallShift, elapsed ?: (t0.elapsedMs + ms), boot)

    private fun failed(times: Int, now: LockClocks = t0): BackoffState {
        var state = BackoffState(hashFingerprint = "a")
        repeat(times) { state = beginAttempt(state, now) }
        return state
    }

    @Test
    fun `four free tries, then 30 s, 1, 2, 5 and 15 minutes, capped`() {
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), (0..4).map(::backoffDurationMs))
        assertEquals(30_000L, backoffDurationMs(5))
        assertEquals(60_000L, backoffDurationMs(6))
        assertEquals(120_000L, backoffDurationMs(7))
        assertEquals(300_000L, backoffDurationMs(8))
        assertEquals(900_000L, backoffDurationMs(9))
        assertEquals(900_000L, backoffDurationMs(50))
        assertEquals(0L, backoffRemaining(failed(4), t0))
        assertEquals(30_000L, backoffRemaining(failed(5), t0))
        assertEquals(10_000L, backoffRemaining(failed(5), later(20_000L)))
        assertEquals(0L, backoffRemaining(failed(5), later(30_000L)))
    }

    @Test
    fun `a failure is counted before the check - a kill mid-verify can't skip it`() {
        // What PinLockRuntime.checkPin commits before PBKDF2 runs.
        val counted = beginAttempt(failed(4), t0)
        assertEquals(5, counted.failures)
        // The process dies here: the stored state already has the wait.
        val afterRestart = refreshBackoff(counted, later(1_000L))
        assertEquals(29_000L, backoffRemaining(afterRestart, later(1_000L)))
        // A correct PIN undoes it.
        assertEquals(BackoffState(hashFingerprint = "a"), attemptSucceeded(counted))
    }

    @Test
    fun `a reboot restarts the full wait from now`() {
        val state = failed(9)
        val rebooted = LockClocks(t0.wallMs + 600_000L, elapsedMs = 20_000L, bootCount = 8)
        val refreshed = refreshBackoff(state, rebooted)
        assertEquals(900_000L, backoffRemaining(refreshed, rebooted))
        assertEquals(8, refreshed.window!!.bootCount)
        // And it then runs down normally in the new boot.
        val later = LockClocks(rebooted.wallMs + 100_000L, rebooted.elapsedMs + 100_000L, 8)
        assertEquals(800_000L, backoffRemaining(refreshBackoff(refreshed, later), later))
    }

    @Test
    fun `setting the clock a day forward or back doesn't shorten it`() {
        val state = failed(6) // 60 s
        assertEquals(50_000L, backoffRemaining(state, later(10_000L, wallShift = day)))
        assertEquals(50_000L, backoffRemaining(state, later(10_000L, wallShift = -day)))
        assertEquals(0L, backoffRemaining(state, later(60_000L, wallShift = -day)))
        // An elapsed clock that went back (can't happen within a boot) means the full wait.
        val weird = later(10_000L, elapsed = t0.elapsedMs - 1)
        assertEquals(60_000L, backoffRemainingMs(state.window!!, weird))
    }

    @Test
    fun `either clock keeps the wait going, never beyond its length`() {
        val window = backoffWindow(t0, 60_000L)
        // Elapsed says done, the wall clock says 30 s left (e.g. a sleep the elapsed clock missed
        // can't happen, but the max rule must hold).
        assertEquals(30_000L, backoffRemainingMs(window, LockClocks(t0.wallMs + 30_000L, t0.elapsedMs + 60_000L, 7)))
        assertEquals(60_000L, backoffRemainingMs(window, LockClocks(t0.wallMs + 1, t0.elapsedMs, 7)))
    }

    @Test
    fun `a new PIN from the parent resets the count`() {
        val state = failed(7)
        assertEquals(state, backoffForHash(state, "a"))
        assertEquals(BackoffState(hashFingerprint = "b"), backoffForHash(state, "b"))
        assertEquals(BackoffState(hashFingerprint = null), backoffForHash(state, null))
    }

    @Test
    fun `wait text`() {
        assertEquals("0:28", backoffText(27_500L))
        assertEquals("15:00", backoffText(900_000L))
        assertEquals("0:01", backoffText(1L))
    }

    /** The polarity QA 10 #3 warned about: RestrictionsPause's window *ends* on a reboot or a
     * forward clock jump - right for an override, wrong for a wait. Pinned here so nobody reuses it. */
    @Test
    fun `timedWindowActive has the opposite polarity`() {
        val start = WindowStart(t0.wallMs + 60_000L, t0.elapsedMs, t0.bootCount)
        assertTrue(timedWindowActive(start, t0.wallMs + 10_000L, t0.elapsedMs + 10_000L, t0.bootCount, 60_000L))
        assertFalse("reboot ends it", timedWindowActive(start, t0.wallMs + 10_000L, 5_000L, t0.bootCount + 1, 60_000L))
        assertFalse("clock forward ends it", timedWindowActive(start, t0.wallMs + day, t0.elapsedMs + 10_000L, t0.bootCount, 60_000L))
        val wait = failed(6)
        assertEquals("our wait survives the reboot", 60_000L, backoffRemaining(refreshBackoff(wait, later(10_000L, boot = 8, elapsed = 5_000L)), later(10_000L, boot = 8, elapsed = 5_000L)))
        assertEquals("and the clock jump", 50_000L, backoffRemaining(wait, later(10_000L, wallShift = day)))
    }
}
