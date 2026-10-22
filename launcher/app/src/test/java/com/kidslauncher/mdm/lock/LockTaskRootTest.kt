package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 16 (QA #2/#5(a)): with the kiosk on Home roots lock task, and the lock leaves through Home -
 * since design 32 with the kiosk off too, unless a call is live (QA L2). */
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
    fun `kiosk off - no lock task while a VoIP call rings or lives (design 17 QA 10 - PiP hang-up)`() {
        for (fallback in listOf(true, false)) {
            assertEquals(LockTaskEntry.NONE, lockTaskEntry(false, true, kioskOn = false, sinceHomeAskedMs = null, fallbackDue = fallback, voipCall = true))
        }
        // After the exemption (only pinned, or no call) the lock roots its lock task again (qa-16-17 6).
        assertEquals(LockTaskEntry.START_SELF, lockTaskEntry(false, true, false, null, false, voipCall = false))
        // Kiosk on: Home roots it as always - a VoIP call changes nothing there (qa-16-17 6).
        assertEquals(LockTaskEntry.START_HOME, lockTaskEntry(false, true, kioskOn = true, sinceHomeAskedMs = null, fallbackDue = false, voipCall = true))
        assertEquals(LockTaskEntry.START_SELF, lockTaskEntry(false, true, kioskOn = true, sinceHomeAskedMs = 500L, fallbackDue = true, voipCall = true))
    }

    @Test
    fun `kiosk on - the lock always leaves through Home, stopping lock task only as the root`() {
        for (call in listOf(false, true)) {
            assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = true), lockLeave(kioskOn = true, startedLockTask = false, lockTaskRunning = true, callLive = call))
            assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = true), lockLeave(kioskOn = true, startedLockTask = true, lockTaskRunning = true, callLive = call))
            assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = true), lockLeave(kioskOn = true, startedLockTask = false, lockTaskRunning = false, callLive = call))
        }
        assertEquals("a refused finish: the root after a process restart", LockLeave(stopLockTaskFirst = true, homeFirst = true), rootLeave)
    }

    @Test
    fun `kiosk off - any running lock task is the lock's own, and the kid lands on Home unless a call is live`() {
        // Design 32 decision 2: Home under the lock is the defence in depth, so unlocking lands there.
        assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = true), lockLeave(kioskOn = false, startedLockTask = false, lockTaskRunning = true, callLive = false))
        assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = true), lockLeave(kioskOn = false, startedLockTask = true, lockTaskRunning = true, callLive = false))
        assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = true), lockLeave(kioskOn = false, startedLockTask = false, lockTaskRunning = false, callLive = false))
        // QA L2: never Home over a live call (ours, Telecom's, VoIP - Element hangs up when its PiP goes).
        assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = false), lockLeave(kioskOn = false, startedLockTask = true, lockTaskRunning = true, callLive = true))
        assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = false), lockLeave(kioskOn = false, startedLockTask = false, lockTaskRunning = false, callLive = true))
    }

    @Test
    fun `59b step 0 - the face stopped under an app leaves without Home, but a kiosk root still re-roots through it`() {
        for (call in listOf(false, true)) {
            // Kiosk off: no Home; the lock task stopped first only when it is ours, as before.
            assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = false), lockLeave(false, startedLockTask = true, lockTaskRunning = true, callLive = call, glanceInFront = false))
            assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = false), lockLeave(false, startedLockTask = false, lockTaskRunning = true, callLive = call, glanceInFront = false))
            assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = false), lockLeave(false, startedLockTask = false, lockTaskRunning = false, callLive = call, glanceInFront = false))
            // Kiosk on, not the root: nothing to stop, no Home.
            assertEquals(LockLeave(stopLockTaskFirst = false, homeFirst = false), lockLeave(true, startedLockTask = false, lockTaskRunning = true, callLive = call, glanceInFront = false))
            // Kiosk on, the lock-task root: stopping drops the kiosk - Home re-roots it.
            assertEquals(LockLeave(stopLockTaskFirst = true, homeFirst = true), lockLeave(true, startedLockTask = true, lockTaskRunning = true, callLive = call, glanceInFront = false))
        }
        // stopLockTaskFirst never depends on glanceInFront (stopOwnLockTask reads the default and must agree).
        for (kiosk in listOf(true, false)) for (started in listOf(true, false)) for (running in listOf(true, false)) for (call in listOf(true, false)) {
            assertEquals(
                lockLeave(kiosk, started, running, call).stopLockTaskFirst,
                lockLeave(kiosk, started, running, call, glanceInFront = false).stopLockTaskFirst,
            )
            assertEquals("in front: today's rows", lockLeave(kiosk, started, running, call), lockLeave(kiosk, started, running, call, glanceInFront = true))
        }
        // Own mode always in front; the face only when resumed at the leave.
        assertTrue(leaveInFront(androidFace = false, resumed = false))
        assertTrue(leaveInFront(androidFace = false, resumed = true))
        assertTrue(leaveInFront(androidFace = true, resumed = true))
        assertFalse(leaveInFront(androidFace = true, resumed = false))
    }

    @Test
    fun `59b step 0 - the glance reads its leave from the face and its resume, and drops a row target only then`() {
        val activity = listOf("src/main/java/com/kidslauncher/mdm/lock/PinLockActivity.kt", "app/src/main/java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val leave = activity.substringAfter("private fun leave() {").substringBefore("\n    }\n")
        assertTrue(leave.contains("val inFront = leaveInFront(AndroidLock.faceOn, resumed)"))
        assertTrue(leave.contains("if (!inFront) pendingOpen = null"))
        assertTrue(leave.contains("PinLockRuntime.callLiveNow(this), glanceInFront = inFront))"))
        assertTrue("the root's retry stays", leave.contains("go(rootLeave)"))
        // stopOwnLockTask keeps the default (in front): its stop agrees with every row.
        assertTrue(activity.substringAfter("private fun stopOwnLockTask() {").substringBefore("\n    }\n").contains("callLive = false).stopLockTaskFirst"))
    }
}
