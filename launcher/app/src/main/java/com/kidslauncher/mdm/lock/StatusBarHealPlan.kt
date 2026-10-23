package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_SYSTEM_INFO
import com.kidslauncher.mdm.server.LockTaskSetting

/*
 * Design 16d, decision 1 (experiment 2 §2): after a boot SystemUI can miss lock task's status-bar
 * disable - `dumpsys statusbar` had the LOCKED flags (mDisabled1=0x7260000 mDisabled2=0x15) but
 * SystemUI's TaskbarDelegate had 0 - and over the PIN lock the shade opened with Quick Settings and
 * the hold opened the Overview, until the next change of the disable flags.
 *
 * `setStatusBarDisabled` can't force that change in lock task: DPMS only records it there
 * (LockTaskController owns the status bar during lock task) and a same-value call is a no-op. The
 * heal changes the lock-task features for a moment instead: SYSTEM_INFO flipped changes only the
 * clock and the system icons (DISABLE_CLOCK, DISABLE2_SYSTEM_ICONS) - never the shade, Quick
 * Settings, Home, Recents or the power menu - so StatusBarManagerService's net flags change and
 * SystemUI is sent the whole set, twice (flipped, then back). Pure - StatusBarHealTest.
 *
 * An app can't verify the result: SystemUI's copy is only in its dumpsys (DUMP) and
 * `StatusBarManager.getDisableInfo` is a system API (STATUS_BAR). smoke-test.sh REBOOT=1 checks it.
 */

/**
 * How long the flipped features stay before a normal pass writes them back, with the screen on (the
 * clock and status icons blink for this long). LockTaskController posts its status-bar update and
 * reads the features when it runs: two writes before it runs would leave the net flags unchanged
 * and send SystemUI nothing.
 */
const val STATUS_BAR_HEAL_FLIP_MS = 250L

/** The flip at screen-off, which nobody sees: long enough for a posted update delayed by load
 * (qa-16d-code #2). */
const val STATUS_BAR_HEAL_FLIP_SCREEN_OFF_MS = 1_000L

/** At most one [HealTrigger.LOCK_RESUMED] heal per this long (the lock resumes at every screen-on). */
const val STATUS_BAR_HEAL_LOCK_RESUMED_MIN_GAP_MS = 60_000L

/**
 * The heals after each process start, in ms after `PinLockRuntime.init` (the boot's user unlock,
 * or our restart after a self-update or a crash). In experiment 2 quickstep's TIS init - the
 * likely moment SystemUI loses the flags - came 2.4-5.2 s after the unlock.
 */
val STATUS_BAR_HEALS_AFTER_START_MS: List<Long> = listOf(1_000L, 3_000L, 6_000L, 10_000L, 20_000L, 40_000L)

/** Why a heal runs (logged), and how long its flip stays ([flipMs]). */
enum class HealTrigger(val label: String, val flipMs: Long = STATUS_BAR_HEAL_FLIP_MS) {
    /** One of [STATUS_BAR_HEALS_AFTER_START_MS] after the process start. */
    PROCESS_START("process start"),
    BOOT_COMPLETED("boot completed"),
    /** The first screen-on of this process. */
    FIRST_SCREEN_ON("first screen-on"),
    /** Every screen-off (invisible): a quickstep restart (or anything else that desyncs SystemUI)
     * can't be detected by an app, so this puts the flags back before the next screen-on. */
    SCREEN_OFF("screen off", STATUS_BAR_HEAL_FLIP_SCREEN_OFF_MS),
    /** The lock resumed (qa-16d-code #2): a desync that began with the screen on - a quickstep
     * restart, the lock back after a call, an alarm, a yield or a re-front - at most once a minute
     * ([lockResumedHealDue]). */
    LOCK_RESUMED("lock resumed"),

    /** Design 59d §8.9 (a): the system started Home while LOCKED ([systemHomeHealDue]) - the home gesture got
     * through, so SystemUI's copy of lock task's flags lacks the Home disable: heal at once. */
    SYSTEM_HOME("the system's Home while locked"),
}

/** At most one [HealTrigger.SYSTEM_HOME] heal per this long (a swipe that gets through again within it waits). */
const val SYSTEM_HOME_HEAL_MIN_GAP_MS = 5_000L

/**
 * Design 59d §8.9, decision (a) (`docs/testing/2026-10-23-edge-swipe-capture.md`): with lock task's flags in sync,
 * Quickstep refuses the bottom-edge swipe over the lock (`home_disabled`: `TYPE_RESET_GESTURE`); only with the Home
 * disable missing from SystemUI's copy (16d's desync) does it pick `DeviceLockedInputConsumer`, which starts Home - the
 * user's recording: the glance slides up, SystemUI's face, a dark frame, the lock back from Home's locked resume. So a
 * Home start that isn't ours ([broughtByUs]: `HomeFront.EXTRA_BROUGHT`) while our lock is [locked] and the screen is
 * [interactive] is the desync's proof: heal now - at most every [SYSTEM_HOME_HEAL_MIN_GAP_MS] ([lastHealElapsedMs]) -
 * so the next swipe is refused again. The first swipe's flash stays (an app can't see SystemUI's flags before it).
 */
fun systemHomeHealDue(locked: Boolean, interactive: Boolean, broughtByUs: Boolean, lastHealElapsedMs: Long?, nowElapsedMs: Long): Boolean =
    locked && interactive && !broughtByUs &&
        (lastHealElapsedMs == null || nowElapsedMs - lastHealElapsedMs !in 0 until SYSTEM_HOME_HEAL_MIN_GAP_MS)

/** Design 59d §8.16: a notification, a call's start or a screen-on this recent makes a system Home start no gesture of
 * the kid's (no ask). */
const val GESTURE_ASK_QUIET_MS = 1_000L

/** §8.16: at most one gesture ask per this long (a swipe loop can't hammer SystemUI's pad). */
const val GESTURE_ASK_MIN_GAP_MS = 2_000L

/** §8.16: the re-fronted lock takes a gesture ask only this long after the system's Home start. */
const val GESTURE_ASK_WINDOW_MS = 3_000L

/**
 * Design 59d §8.16, §8.9's option (b): the system's Home while our lock is [locked] with the screen [interactive] and not
 * [broughtByUs] is a home gesture that got through (the same proof as [systemHomeHealDue]). Where SystemUI never tells
 * Quickstep that Home is disabled - the user's AVD has no navigation bar of SystemUI's, so lock task's `DISABLE_HOME`
 * reaches no `home_disabled` and no heal can help - every bottom-edge swipe goes through. So the kid's swipe becomes
 * "a flash, then the unlock": the lock the system's Home brings back asks for it at once (as the handle's swipe does -
 * Android's PIN pad as the face, our keypad in own mode), once per gesture: never with a call live ([callLive]), never
 * within [GESTURE_ASK_QUIET_MS] of a notice ([lastNoticeElapsedMs]) or a screen-on ([lastScreenOnElapsedMs]), and at
 * most every [GESTURE_ASK_MIN_GAP_MS] ([lastAskElapsedMs]). Independent of the heal's 5 s gap: swipes 2.5 s apart all ask.
 */
fun gestureAskDue(
    locked: Boolean,
    interactive: Boolean,
    broughtByUs: Boolean,
    callLive: Boolean,
    lastScreenOnElapsedMs: Long?,
    lastNoticeElapsedMs: Long?,
    lastAskElapsedMs: Long?,
    nowElapsedMs: Long,
): Boolean {
    fun within(at: Long?, ms: Long) = at != null && nowElapsedMs - at in 0 until ms
    return locked && interactive && !broughtByUs && !callLive &&
        !within(lastScreenOnElapsedMs, GESTURE_ASK_QUIET_MS) && !within(lastNoticeElapsedMs, GESTURE_ASK_QUIET_MS) &&
        !within(lastAskElapsedMs, GESTURE_ASK_MIN_GAP_MS)
}

/** §8.16: a gesture ask cancelled this soon is the keyguard's own re-occlusion, not the kid ([gestureAskRetryDue]). */
const val GESTURE_ASK_SETTLE_MS = 1_500L

/** §8.16: the one re-ask waits this long - the re-occlusion's transition is over by then (~0.5 s on the emulator). */
const val GESTURE_ASK_RETRY_DELAY_MS = 700L

/**
 * Design 59d §8.16: the gesture's ask goes out while SystemUI is still putting the keyguard back under the lock (the
 * swipe un-occluded it), and that re-occlusion cancels the pad it was showing (`PRIMARY_BOUNCER -> OCCLUDED`,
 * `onDismissCancelled` ~0.25 s after the ask on `jelly36`). So a cancel of a gesture ask ([gestureAsk]) within
 * [GESTURE_ASK_SETTLE_MS] of it ([askedAtElapsedMs], [nowElapsedMs]), with no screen-off since ([sameScreenOn]), asks
 * once more after [GESTURE_ASK_RETRY_DELAY_MS] - never twice ([retried]): a kid's own Back that fast gets the pad once more.
 */
fun gestureAskRetryDue(gestureAsk: Boolean, retried: Boolean, sameScreenOn: Boolean, askedAtElapsedMs: Long?, nowElapsedMs: Long): Boolean =
    gestureAsk && !retried && sameScreenOn && askedAtElapsedMs != null && nowElapsedMs - askedAtElapsedMs in 0 until GESTURE_ASK_SETTLE_MS

/** §8.16: whether the lock's resume takes the gesture ask marked at [markedElapsedMs] (`null` = none): only within
 * [GESTURE_ASK_WINDOW_MS] - a mark the lock never came back for dies. Taken once (the runtime clears it). */
fun gestureAskTaken(markedElapsedMs: Long?, nowElapsedMs: Long): Boolean =
    markedElapsedMs != null && nowElapsedMs - markedElapsedMs in 0 until GESTURE_ASK_WINDOW_MS

/**
 * Whether the lock's resume heals now: never before, or the last one [STATUS_BAR_HEAL_LOCK_RESUMED_MIN_GAP_MS]
 * ago - and not on the first resume after a screen-off heal ([healedSinceScreenOff], bug 25b-C): that
 * heal re-sent lock task's flags while the screen was dark, and a heal at the lock-up after power-on
 * blanked the status line visibly at every power press (the launcher seemed to restart).
 */
fun lockResumedHealDue(lastHealElapsedMs: Long?, nowElapsedMs: Long, healedSinceScreenOff: Boolean = false): Boolean =
    !healedSinceScreenOff &&
        (lastHealElapsedMs == null || nowElapsedMs - lastHealElapsedMs !in 0 until STATUS_BAR_HEAL_LOCK_RESUMED_MIN_GAP_MS)

/**
 * The lock-task features of a heal's first write: [setting]'s with SYSTEM_INFO flipped. `null` when
 * nothing is pinned ([LockTaskSetting.packages] `null`: kiosk off and not LOCKED - no lock task, so
 * lock task's status-bar flags aren't in force and there is nothing to heal).
 */
fun statusBarHealFeatures(setting: LockTaskSetting): Int? =
    if (setting.packages == null) null else setting.features xor LOCK_TASK_FEATURE_SYSTEM_INFO

/** A heal's first write ([flipped], from [original]) that no pass has written over yet. */
data class HealFlip(val original: Int, val flipped: Int)

/**
 * The base features a pass without a plan reads back from the platform (LockTaskChrome's
 * fallback): while a heal's flip is up the platform shows [HealFlip.flipped], which counts as
 * [HealFlip.original] - so that pass writes the original back, never keeps the flip.
 */
fun healBase(platformFeatures: Int, flip: HealFlip?): Int =
    if (flip != null && platformFeatures == flip.flipped) flip.original else platformFeatures
