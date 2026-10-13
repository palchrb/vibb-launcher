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
 * [coverLocked]: design 32 step 2's keyguard cover is in force (debug switch) **and** its keyguard is
 * locked at this sleep (read at the screen-off, qa-32-s2-code L4) - the lock alone, no Home under it:
 * while the keyguard is locked nothing that isn't showWhenLocked can be on screen, so Home's stand-in
 * adds nothing, and its start (a binder call, a transition) before the lock's would only widen the
 * window in which SystemUI's lockscreen shows un-occluded at a quick wake. Not locked (an app's
 * `KeyguardLock`, T briefly above the setting, a SystemUI restart): step 1's Home, the safe direction.
 */
fun lockUpStarts(showLock: Boolean, lockOnTop: Boolean, callLive: Boolean, interactive: Boolean, coverLocked: Boolean = false): LockUpStart = when {
    !showLock -> LockUpStart.NONE
    interactive -> LockUpStart.LOCK
    callLive -> LockUpStart.LOCK
    lockOnTop -> LockUpStart.NONE
    coverLocked -> LockUpStart.LOCK
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

    /**
     * The lock got a new intent ([PinLockRuntime.onLockNewIntent]). With `sameLockUp` the pause just
     * before it was only that delivery's ([newIntentPauseKept]) - the lock never left the top.
     */
    NEW_INTENT,

    /**
     * A screen-on (or `USER_PRESENT`) that finds the lock not resumed and starts nothing - a call
     * the system dialer shows, a VoIP call (qa-32-s1-code M1). Something came over the lock while
     * it was already sleep-stopped, which the lock never hears of, and no start puts it back on top.
     */
    SCREEN_ON_UNSHOWN,
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
 * - Cleared by a screen-on that finds the lock not resumed and starts nothing
 *   ([LockTopEvent.SCREEN_ON_UNSHOWN]): whatever is in front came over the sleep-stopped lock
 *   without a callback, and a stale mark would make the next screen-off start nothing at all.
 */
fun lockOnTopAfter(current: Boolean, event: LockTopEvent, interactive: Boolean = false, sameLockUp: Boolean = false): Boolean = when (event) {
    LockTopEvent.LOCK_UP -> true
    LockTopEvent.START -> current && sameLockUp
    LockTopEvent.PAUSE, LockTopEvent.STOP -> current && !interactive
    LockTopEvent.DESTROY, LockTopEvent.UNLOCKED, LockTopEvent.SCREEN_ON_UNSHOWN -> false
    LockTopEvent.NEW_INTENT -> current || sameLockUp
}

/** How soon after the ScreenOn backstop's start onto the lock on top its new-intent pause comes. */
const val BACKSTOP_NEW_INTENT_MS = 1_000L

/**
 * The ScreenOn backstop races the lock's own resume at the wake. When it loses, its start lands on a
 * lock that is resumed and the top task already (`START_DELIVERED_TO_TOP`), and Android pauses the lock
 * to deliver the new intent (pause, onNewIntent, resume) - an interactive pause that cleared
 * [lockOnTopAfter]'s mark, so the next screen-off started Home and the lock again while dark (the
 * user's mash log, 2026-10-13: `wm_new_intent PinLock` 15-35 ms after each such wake, then
 * HOME_THEN_LOCK with the lock on top). Such a pause - the mark was set ([wasOnTop]), a same-lock-up
 * start onto it less than [BACKSTOP_NEW_INTENT_MS] before ([backstopAtMs], elapsed) - is held, and
 * the new intent that follows puts the mark back ([LockTopEvent.NEW_INTENT]); a stop or a resume
 * without the new intent drops it.
 */
fun newIntentPauseKept(wasOnTop: Boolean, backstopAtMs: Long?, nowMs: Long): Boolean =
    wasOnTop && backstopAtMs != null && nowMs - backstopAtMs in 0..BACKSTOP_NEW_INTENT_MS

/** qa-32-s1-code M1: the screen-on [event] found the lock not resumed and [showLock] started nothing. */
fun screenOnUnshown(event: LockEvent, showLock: Boolean): Boolean =
    event is LockEvent.ScreenOn && !event.lockShowing && !showLock

/**
 * Design 32 step 1 item 3 (QA M1): Home brought under the lock while dark only shows its night
 * ground - no lock task (qa-32-s1-code L1: the lock's task is focused by then), no update fence, no
 * time-rule screen over the lock, no lock start and no boot-mark calls before the lock's own
 * creation. [darkLockUp]: a HOME_THEN_LOCK
 * lock-up since the last interactive resume of the lock or Home.
 */
fun homeDarkResume(darkLockUp: Boolean, interactive: Boolean, locked: Boolean): Boolean =
    darkLockUp && !interactive && locked
