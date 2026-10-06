package com.kidslauncher.mdm.lock

/*
 * Who roots lock task, and how the lock leaves (design 16, QA #2/#5(a)). Pure, tested in
 * LockTaskRootTest.
 *
 * With the kiosk on, the PIN lock must never be the lock-task root: a root can't finish
 * (`activityBlockedFromFinish`), and stopping it clears every locked task - Home included - which
 * surfaced the stock launcher's latent Recents task as "App is not available" (BlockedAppActivity,
 * `lockTaskMode="always"`) at the unlock. So the lock asks Home to root lock task (Home's resume
 * starts it and shows the lock again) and only starts it itself as a fallback; and it always
 * leaves through Home. With the kiosk off the lock-task is the lock's own (step 10): it starts and
 * stops it itself, and the kid returns to whatever was open.
 */

/** How often the lock may ask Home to root lock task; in between it waits for the fallback. */
const val HOME_ROOT_RETRY_MS = 3_000L

enum class LockTaskEntry {
    /** Running already, or not permitted (then the lock is a re-front only). */
    NONE,

    /** Start Home (typed HOME): its resume roots lock task and shows the lock again. */
    START_HOME,

    /** Home was asked less than [HOME_ROOT_RETRY_MS] ago: wait for the [LOCK_FALLBACK_MS] fallback. */
    WAIT_FOR_HOME,

    /** The lock starts lock task itself: the kiosk is off, or Home didn't (fallback). */
    START_SELF,
}

/**
 * At the lock's resume ([fallbackDue] = false) and [LOCK_FALLBACK_MS] after asking Home (true).
 * [sinceHomeAskedMs]: elapsed time since the lock last started Home for this (`null` = never).
 */
fun lockTaskEntry(
    running: Boolean,
    permitted: Boolean,
    kioskOn: Boolean,
    sinceHomeAskedMs: Long?,
    fallbackDue: Boolean,
): LockTaskEntry = when {
    running || !permitted -> LockTaskEntry.NONE
    !kioskOn || fallbackDue -> LockTaskEntry.START_SELF
    sinceHomeAskedMs != null && sinceHomeAskedMs in 0 until HOME_ROOT_RETRY_MS -> LockTaskEntry.WAIT_FOR_HOME
    else -> LockTaskEntry.START_HOME
}

/** What the lock does when it leaves (unlocked, or switched off). */
data class LockLeave(
    /** Stop lock task before finishing. */
    val stopLockTaskFirst: Boolean,
    /** Start Home before finishing, so Home is above any latent task and never finished. */
    val homeFirst: Boolean,
)

/**
 * - Kiosk on: always through Home (QA #5(a)); lock task is stopped first only when this lock
 *   instance started it ([startedLockTask]: it is the root - then stopping clears every locked
 *   task, and Home, started right after, roots it again). A root the lock doesn't know about (an
 *   earlier instance before a process restart) shows as a blocked finish; the caller retries with
 *   [rootLeave].
 * - Kiosk off: as in step 10 - any running lock task is the lock's own; no Home.
 */
fun lockLeave(kioskOn: Boolean, startedLockTask: Boolean, lockTaskRunning: Boolean): LockLeave =
    if (kioskOn) {
        LockLeave(stopLockTaskFirst = startedLockTask, homeFirst = true)
    } else {
        LockLeave(stopLockTaskFirst = startedLockTask || lockTaskRunning, homeFirst = false)
    }

/** The finish was refused (`isFinishing` still false) with the kiosk on: the lock is the root. */
val rootLeave = LockLeave(stopLockTaskFirst = true, homeFirst = true)
