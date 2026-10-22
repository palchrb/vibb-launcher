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
 * stops it itself; since design 32 the kid lands on Home there too (decision 2: Home lies under the
 * lock as defence in depth), unless a call is live - Home must never cover a call (QA L2).
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
 * [voipCall]: a VoIP call rings or lives ([VoipPhase] not NONE, design 17) - with the kiosk off the
 * lock then starts no lock task: starting it removes pinned (PiP) tasks, and Element hangs up when
 * its PiP goes (QA #10). Only then: not with the kiosk on, and not while the package is merely
 * still pinned after the exemption ended (qa-16-17-code #6).
 */
fun lockTaskEntry(
    running: Boolean,
    permitted: Boolean,
    kioskOn: Boolean,
    sinceHomeAskedMs: Long?,
    fallbackDue: Boolean,
    voipCall: Boolean = false,
    /** Design 59d step A (qa-59b-code B-1): as Android's face with its secure keyguard locked - a Home start lands
     * behind the keyguard (and un-occludes it), so the glance roots lock task itself; the leave re-roots through Home
     * (`lockLeave`'s kiosk root, `startedLockTask`). */
    androidLocked: Boolean = false,
): LockTaskEntry = when {
    running || !permitted || (voipCall && !kioskOn) -> LockTaskEntry.NONE
    !kioskOn || fallbackDue || androidLocked -> LockTaskEntry.START_SELF
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
 * - Kiosk off: as in step 10, any running lock task is the lock's own and is stopped first. Then
 *   through Home (design 32 decision 2) unless [callLive] - our call, Telecom's or a VoIP call: the
 *   call's screen stays in front, and Element hangs up when its PiP goes (QA L2).
 * - Design 59b step 0 (Q59b-1): [glanceInFront] false - an unlock on Android's own face or pad while the glance
 *   was stopped under the app the kid unlocked into ([leaveInFront]) - no Home: the glance finishes under that app,
 *   which Home would only hide. The lock task is stopped first exactly as in the rows above (so `stopOwnLockTask`
 *   still agrees). Kept: kiosk on with the glance the lock-task root - stopping its lock task drops the kiosk, and
 *   Home re-roots it.
 */
fun lockLeave(kioskOn: Boolean, startedLockTask: Boolean, lockTaskRunning: Boolean, callLive: Boolean, glanceInFront: Boolean = true): LockLeave =
    if (kioskOn) {
        LockLeave(stopLockTaskFirst = startedLockTask, homeFirst = glanceInFront || startedLockTask)
    } else {
        LockLeave(stopLockTaskFirst = startedLockTask || lockTaskRunning, homeFirst = glanceInFront && !callLive)
    }

/**
 * Design 59b step 0: whether the leave counts the glance as in front - always in own mode (every own path is
 * unchanged); as Android's face ([androidFace]) only when it is resumed at the leave ("Lås opp", a row via the pad, a
 * fingerprint from dark that resumed it). Stopped under an app (`USER_PRESENT` after SystemUI's face or pad): not.
 * qa-59b-code B-4: the pad's success ([fromAsk]) is always the glance's own ask - in front whatever the lifecycle reads
 * while the bouncer or the keyguard's going-away covers it, so a row's target is kept.
 */
fun leaveInFront(androidFace: Boolean, resumed: Boolean, fromAsk: Boolean = false): Boolean = !androidFace || resumed || fromAsk

/** The finish was refused (`isFinishing` still false) with the kiosk on: the lock is the root. */
val rootLeave = LockLeave(stopLockTaskFirst = true, homeFirst = true)
