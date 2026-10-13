package com.kidslauncher.mdm.lock

/*
 * The lock-up at a screen-off (design 32 step 1, docs/design/32-lock-flash.md §8). Pure, tested in
 * LockUpPlanTest.
 *
 * Android keeps every activity without turn-screen-on invisible while the display sleeps, and the
 * screen-on doesn't wait for the lock: it is drawn only after the display is lit. Until its first
 * frame, WM/Shell shows what lies under the lock - the task right under it, by its stored snapshot,
 * then the lock's own plain stand-in. So while dark the lock-up puts Home (whose snapshot is plain
 * black: no recents screenshot, a black task description) under the lock, and the lock's stand-in
 * is black. This narrows the gap; it doesn't close it (step 2, the keyguard cover).
 */

/** What [PinLockRuntime.lockUp] starts at a screen-off, in this order. */
enum class LockUpStart {
    /** Nothing: the lock isn't shown (a call the system dialer shows, a VoIP call), or it is the top
     * task already - a start then is only `START_DELIVERED_TO_TOP` (§1, §5 H2). */
    NONE,

    /** The lock alone: the screen is on again, or a call is live (Home must never cover a call). */
    LOCK,

    /** Home (typed HOME, no animation), then the lock: what lies under the lock is Home, not an app. */
    HOME_THEN_LOCK,
}

/**
 * [showLock]: the state machine shows the lock for this screen-off ([step]); [lockOnTop]: the lock is
 * the top task since the last lock-up (best effort, [lockOnTopAfter]); [callLive]: our call,
 * Telecom's or a VoIP phase other than NONE; [interactive]: the screen is on - a `SCREEN_OFF` that
 * waited in the broadcast queue past the next wake (QA L1) starts no visible Home.
 */
fun lockUpStarts(showLock: Boolean, lockOnTop: Boolean, callLive: Boolean, interactive: Boolean): LockUpStart = when {
    !showLock -> LockUpStart.NONE
    interactive -> LockUpStart.LOCK
    callLive -> LockUpStart.LOCK
    lockOnTop -> LockUpStart.NONE
    else -> LockUpStart.HOME_THEN_LOCK
}

/** What can change whether the lock is still the top task since its lock-up ([lockOnTopAfter]). */
enum class LockTopEvent {
    /** [PinLockRuntime.lockUp] started the lock (LOCK or HOME_THEN_LOCK). */
    LOCK_UP,

    /** Any other start of the lock ([PinLockRuntime.show]): a re-front, `TimeRuleShown`,
     * `CallsEnded`, VoIP, a process start - or the `ScreenOn` backstop ([sameLockUp]). */
    START,
    PAUSE,
    STOP,
    DESTROY,

    /** The UNLOCKED (or DISABLED) edge. */
    UNLOCKED,
}

/**
 * Design 32 step 1 item 4 (QA M1): whether the lock is still the top task after [event] - best
 * effort, its only job is to skip the needless re-start at the next screen-off.
 * - Set by a lock-up.
 * - Kept by a pause or stop while dark ([interactive] false): the sleep's own, the lock stays on top.
 * - Kept by a start of the same lock-up ([sameLockUp]: the `ScreenOn` backstop racing the lock's
 *   resume at the wake). An interactive pause before it has cleared the mark already.
 * - Cleared by an interactive pause or stop (something came over the lock, or it left), the lock's
 *   destroy, the unlock, and every other start - something was over the lock, or is about to be.
 */
fun lockOnTopAfter(current: Boolean, event: LockTopEvent, interactive: Boolean = false, sameLockUp: Boolean = false): Boolean = when (event) {
    LockTopEvent.LOCK_UP -> true
    LockTopEvent.START -> current && sameLockUp
    LockTopEvent.PAUSE, LockTopEvent.STOP -> current && !interactive
    LockTopEvent.DESTROY, LockTopEvent.UNLOCKED -> false
}

/**
 * Design 32 step 1 item 3 (QA M1): Home brought under the lock while dark only shows its night
 * ground and roots lock task (kiosk on) - no update fence, no time-rule screen over the lock, no
 * lock start and no boot-mark calls before the lock's own creation. [darkLockUp]: a HOME_THEN_LOCK
 * lock-up since the last interactive resume of the lock or Home.
 */
fun homeDarkResume(darkLockUp: Boolean, interactive: Boolean, locked: Boolean): Boolean =
    darkLockUp && !interactive && locked
