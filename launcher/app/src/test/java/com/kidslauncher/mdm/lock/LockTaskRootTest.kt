package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 16 (QA #2/#5(a)): with the kiosk on Home roots lock task, and the lock leaves through Home. */
class LockTaskRootTest {

    @Test
    fun `kiosk on - the lock asks Home to root lock task, at most every 3 s, then roots it itself`() {
        assertEquals(LockTaskEntry.START_HOME, lockTaskEntry(running = false, permitted = true, kioskOn = true, sinceHomeAskedMs = null, fallbackDue = false))
        assertEquals(LockTaskEntry.WAIT_FOR_HOME, lockTaskEntry(false, true, true, sinceHomeAskedMs = 500L, fallbackDue = false))
        assertEquals(LockTaskEntry.START_HOME, lockTaskEntry(false, true, true, sinceHomeAskedMs = HOME_ROOT_RETRY_MS, fallbackDue = false))
        assertEquals("a clock that went backwards asks again", LockTaskEntry.START_HOME, lockTaskEntry(false, true, true, -5L, false))
        assertEquals("the 1 s fallback", LockTaskEntry.START_SELF, lockTaskEntry(false, true, true, 1_000L, fallbackDue = true))
    }

    @Test
    fun `kiosk off - the lock roots its own lock task at once, as in step 10`() {
        assertEquals(LockTaskEntry.START_SELF, lockTaskEntry(running = false, permitted = true, kioskOn = false, sinceHomeAskedMs = null, fallbackDue = false))
    }

    @Test
    fun `nothing when lock task runs or isn't permitted (never screen pinning)`() {
        for (kiosk in listOf(true, false)) {
            for (fallback in listOf(true, false)) {
                assertEquals(LockTaskEntry.NONE, lockTaskEntry(running = true, permitted = true, kioskOn = kiosk, sinceHomeAskedMs = null, fallbackDue = fallback))
                assertEquals(LockTaskEntry.NONE, lockTaskEntry(running = false, permitted = false, kioskOn = kiosk, sinceHomeAskedMs = null, fallbackDue = fallback))
            }
        }
    }

    @Test
    fun `no lock task at all while a VoIP call's app is pinned (design 17 QA 10 - PiP hang-up)`() {
        for (kiosk in listOf(true, false)) {
            for (fallback in listOf(true, false)) {
                assertEquals(LockTaskEntry.NONE, lockTaskEntry(false, true, kiosk, null, fallback, voipPinned = true))
            }
        }
        assertEquals(LockTaskEntry.START_SELF, lockTaskEntry(false, true, false, null, false, voipPinned = false))
    }

    @Test
    fun `kiosk on - the lock always leaves through Home, stopping lock task only as the root`() {
        assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = true), lockLeave(kioskOn = true, startedLockTask = false, lockTaskRunning = true))
        assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = true), lockLeave(kioskOn = true, startedLockTask = true, lockTaskRunning = true))
        assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = true), lockLeave(kioskOn = true, startedLockTask = false, lockTaskRunning = false))
        assertEquals("a refused finish: the root after a process restart", LockLeave(stopLockTaskFirst = true, homeFirst = true), rootLeave)
    }

    @Test
    fun `kiosk off - any running lock task is the lock's own, and the kid returns to his app`() {
        assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = false), lockLeave(kioskOn = false, startedLockTask = false, lockTaskRunning = true))
        assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = false), lockLeave(kioskOn = false, startedLockTask = true, lockTaskRunning = true))
        assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = false), lockLeave(kioskOn = false, startedLockTask = false, lockTaskRunning = false))
    }
}
