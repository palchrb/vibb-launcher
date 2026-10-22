package com.kidslauncher.mdm.lock

/*
 * When the lock screen comes back to the front, and the crash guard (design §2, QA 10 #2/#11/#14).
 * Pure, tested in RefrontPolicyTest.
 */

/** What is going on when the lock screen isn't in front while LOCKED. */
data class RefrontInputs(
    val locked: Boolean,
    /** PinLockActivity is resumed (in front). */
    val lockResumed: Boolean,
    /** `PowerManager.isInteractive`: with the screen off nothing is re-fronted - screen-on does it. */
    val interactive: Boolean,
    /** One of our calls exists (our in-call screen is the call UI). */
    val ourCall: Boolean,
    /** The system dialer shows a call (emergency calls): Telecom's `isInManagedCall` - never a
     * self-managed app's call ([managedCallActive], design 17 QA #7). */
    val telecomInCall: Boolean,
    /** "Emergency call" was tapped on the lock less than [EMERGENCY_FLOW_MS] ago: the
     * emergency dialer / Telecom confirmation is in front. */
    val emergencyFlow: Boolean,
    /** The default clock app's alarm is probably ringing ([alarmLikelyRinging]). */
    val alarmRinging: Boolean,
    /** An allowed app's VoIP call rings or is on ([VoipPhase] not NONE, design 17): its ring or
     * call screen may be in front. */
    val voipCall: Boolean = false,
    /** Probably Emergency SOS (five power presses) is in front ([sosGestureLikely], bug 25b-H). */
    val sosFlow: Boolean = false,
    /** Design 32 step 2: the keyguard cover is on ([KeyguardCover.coverOn], debug switch only). */
    val coverOn: Boolean = false,
    /** Design 32: the lock is the top task since its lock-up ([lockOnTopAfter], best effort). */
    val lockOnTop: Boolean = false,
    /** Design 59b §3: the glance is Android's face and [androidLoss] applies (`null`: own mode's rows - also the face
     * with Android open and no `USER_PRESENT`, the RELOCK backstop's re-front). */
    val android: AndroidLoss? = null,
    /** Design 59b §3 step 3: the loss is ours ([androidLossOurs]): the PIN pad's ask, one of our own starts, one of our
     * own screens resumed. */
    val androidOurs: Boolean = false,
    /** Design 59b §3 step 6: the face's re-fronts since the last screen-off or `USER_PRESENT` ([ANDROID_REFRONT_CAP]). */
    val androidRefronts: Int = 0,
)

/** Design 59b §3: what Android's keyguard is while the face lost the front. */
enum class AndroidLoss {
    /** Android's secure keyguard is locked under the face: a re-front can only occlude it again, never open it. */
    LOCKED,

    /** Android is open with a `USER_PRESENT` since our lock last went LOCKED: the kid unlocked it - the leave without
     * Home (design 59b §2), never a re-front. */
    OPEN,
}

/**
 * Design 59b §3/§2 (Q59b-6): [androidLoss]'s input from the face's live reads - `null` without the face or a
 * credential, and with Android open and no `USER_PRESENT` (own mode's re-front then stays the backstop: the glance's
 * resume WAITs and RELOCKs an unexpectedly open Android).
 */
fun androidLoss(face: Boolean, deviceSecure: Boolean, androidLocked: Boolean, presentSinceLocked: Boolean): AndroidLoss? = when {
    !face || !deviceSecure -> null
    androidLocked -> AndroidLoss.LOCKED
    presentSinceLocked -> AndroidLoss.OPEN
    else -> null
}

/** Design 59b §3 step 6 (Q59b-3): at most this many re-fronts per screen-on - then SystemUI's face (with our ground)
 * until the screen-off. Own mode never gives up (QA 10 #2); here a cap leaves only Android's locked keyguard. */
const val ANDROID_REFRONT_CAP = 2

/** Design 59b §3 step 3 (Q59b-8): one of our own starts ([PinLockRuntime.noteOwnStart]) counts as ours this long. */
const val OWN_START_MS = 2_000L

/**
 * Design 59b §3 step 3: an open ask for Android's PIN pad counts as ours at most this long - a stamp, so a lost
 * callback expires; the pad's Emergency dialer stops the glance while it is open.
 */
const val ANDROID_ASK_OPEN_MS = 2 * 60_000L

/**
 * Design 59b §3 step 3: the face lost the front to something of ours - the ask for Android's PIN pad ([askedAtMs],
 * elapsed, `null` = none open, [ANDROID_ASK_OPEN_MS]) or its answer less than [ANDROID_ASK_SETTLE_MS] ago
 * ([answeredAtMs]), one of our own starts less than [OWN_START_MS] ago ([ownStartAtMs]), or another of our screens
 * resumed ([ownOthersResumed]).
 */
fun androidLossOurs(askedAtMs: Long?, answeredAtMs: Long?, ownStartAtMs: Long?, ownOthersResumed: Boolean, nowMs: Long): Boolean =
    (askedAtMs != null && nowMs - askedAtMs in 0 until ANDROID_ASK_OPEN_MS) ||
        (answeredAtMs != null && nowMs - answeredAtMs in 0 until ANDROID_ASK_SETTLE_MS) ||
        (ownStartAtMs != null && nowMs - ownStartAtMs in 0 until OWN_START_MS) ||
        ownOthersResumed

/** How long after "Emergency call" the lock stays out of the way even without a call. */
const val EMERGENCY_FLOW_MS = 2 * 60_000L

/** Exempt screens are looked at again this often - the lock comes back when they close. */
const val EXEMPT_RECHECK_MS = 2_000L

sealed interface RefrontAction {
    /** Nothing to do (unlocked, in front, or the screen is off). */
    data object Stop : RefrontAction

    /** An exempt screen is in front (our call, the system dialer, Telecom, a VoIP call, the alarm): don't
     * fight it; look again in [recheckMs]. Each such episode is counted and reported. */
    data class Yield(val reason: String, val recheckMs: Long = EXEMPT_RECHECK_MS) : RefrontAction

    /** Bring the lock back now and look again in [nextCheckMs]. [noUserAction]: the face's re-front (design 59b §3
     * step 5) - `FLAG_ACTIVITY_NO_USER_ACTION`, so the covered app gets no user-leave hint (no PiP, no Element
     * hang-up, design 57). */
    data class Refront(val nextCheckMs: Long, val noUserAction: Boolean = false) : RefrontAction

    /** Design 59b §2 (Q59b-6): as the face, Android is open with a `USER_PRESENT` since the LOCKED edge - the leave
     * without Home (the glance's `USER_PRESENT` path), never a re-front. */
    data object AndroidLeave : RefrontAction

    /**
     * Design 32 step 2 (M5): while dark under the keyguard cover, with the lock not on top and nothing
     * exempt any more (a system-dialer call ended in the dark): the lock-up once (the lock alone, as every
     * lock-up under the cover - [lockUpStarts]' coverOn),
     * so the cover's keyguard is occluded by the lock at the wake, not shown un-occluded. No next check.
     */
    data object DarkLockUp : RefrontAction
}

/** 0.5 s, 2 s, then every 5 s - never giving up (QA 10 #2: a cap would fail open). */
fun refrontDelayMs(attempt: Int): Long = when {
    attempt <= 0 -> 500L
    attempt == 1 -> 2_000L
    else -> 5_000L
}

/**
 * The check that runs [refrontDelayMs] after the lock left the front (and again after each
 * re-front). Only the named exempt set is left alone; every other front task - an allowlisted
 * app relaunching itself, a full-screen intent, the camera gesture - loses, forever.
 */
fun refrontAction(inputs: RefrontInputs, attempt: Int): RefrontAction = when {
    !inputs.locked || inputs.lockResumed -> RefrontAction.Stop
    inputs.android != null -> androidLossStep(inputs, attempt)
    // The screen off: nothing - except under the keyguard cover with the lock not on top (design 32
    // step 2, M5): the exempt screens are still yielded to (the system_call yield keeps looking every
    // 2 s while dark), and once none is left the lock-up runs while dark ([RefrontAction.DarkLockUp]).
    !inputs.interactive && !(inputs.coverOn && !inputs.lockOnTop) -> RefrontAction.Stop
    else -> exemptYield(inputs) ?: if (!inputs.interactive) RefrontAction.DarkLockUp else RefrontAction.Refront(refrontDelayMs(attempt + 1))
}

/** The named exempt screens, in their order - own mode's and the face's alike. */
private fun exemptYield(inputs: RefrontInputs): RefrontAction.Yield? = when {
    inputs.ourCall -> RefrontAction.Yield("call")
    inputs.telecomInCall -> RefrontAction.Yield("system_call")
    inputs.emergencyFlow -> RefrontAction.Yield("emergency")
    inputs.alarmRinging -> RefrontAction.Yield("alarm")
    // A phone call, the emergency flow and the alarm always win over a VoIP call
    // (qa-16-17-code #1 - and the alarm's exemption ends only on a yield named "alarm").
    inputs.voipCall -> RefrontAction.Yield("voip")
    // Last (qa-25b-25f M2): a probable SOS never hides a woken alarm, a call or a VoIP call - their
    // yields end their own exemptions (the alarm's on a yield named "alarm").
    inputs.sosFlow -> RefrontAction.Yield("sos")
    else -> null
}

/**
 * Design 59b §3, the face's loss rule (replacing design 59's blanket stop): SystemUI's face over the glance (an app that
 * isn't showWhenLocked un-occluded the keyguard) is covered again fast - only for an unexpected loss, at most
 * [ANDROID_REFRONT_CAP] times per screen-on. Android's keyguard stays locked under it either way: the glance is
 * showWhenLocked with no dismiss, so a re-front only occludes it again - a look, never a path past the PIN.
 * 1. The screen off: nothing (the screen-off's lock-up and the sleep check own the dark) - before the exemptions only
 *    here; own mode's dark cover row ([RefrontAction.DarkLockUp]) is unchanged.
 * 2. Android open with a `USER_PRESENT` since the LOCKED edge: the leave without Home, never a re-front (Q59b-6) - before
 *    the exemptions, so a pending yield's recheck never keeps our lock LOCKED over an Android the kid unlocked.
 * 3. The exemptions, as own mode's (a VoIP call ringing **or** live yields - no re-front over a call screen, Q59b-2).
 * 4. Ours (the PIN pad's ask, our own start, our own screen): nothing.
 * 5. The cap reached: nothing - SystemUI's face until the screen-off.
 * 6. Else the re-front, with no user-leave hint.
 */
fun androidLossStep(inputs: RefrontInputs, attempt: Int): RefrontAction = when {
    !inputs.interactive -> RefrontAction.Stop
    inputs.android == AndroidLoss.OPEN -> RefrontAction.AndroidLeave
    else -> exemptYield(inputs) ?: when {
        inputs.androidOurs || inputs.androidRefronts >= ANDROID_REFRONT_CAP -> RefrontAction.Stop
        else -> RefrontAction.Refront(refrontDelayMs(attempt + 1), noUserAction = true)
    }
}

/**
 * The lock was only *paused* while LOCKED (bug 25b-A): Recents (the Overview button, a keyboard's
 * Recents key) runs as a transient launch that pauses the lock without stopping it - its live tile
 * keeps it visible - so the stop-only re-front never ran and the other apps' thumbnails stayed up.
 * A pause schedules the same check after this short delay: a transient pause of our own (a new
 * intent, the time-rule screen handing back) is over by then and the check stops; the exempt
 * screens (a call, the emergency flow, an alarm, a VoIP ring) yield as always.
 */
const val PAUSE_REFRONT_MS = 200L

/** Whether a pause of the lock starts the re-front check: only while LOCKED and not finishing. */
fun pauseStartsRefront(locked: Boolean, finishing: Boolean): Boolean = locked && !finishing

/** An `onStop` that isn't the lock losing the front: a configuration change (we are portrait
 * and handle the rest, but still), or finishing after the right PIN. */
fun stopStartsRefront(locked: Boolean, changingConfigurations: Boolean, finishing: Boolean): Boolean =
    locked && !changingConfigurations && !finishing

/** How long an alarm is assumed to ring after its time - short (qa-10-code #4); the window also
 * ends as soon as the lock is back in front after it ([alarmAfterResume]). */
const val ALARM_RING_MS = 3 * 60_000L

/**
 * The decision after QA review: the default clock app's alarm screen shows over the lock so it
 * can be snoozed or dismissed without the PIN. We can't see other apps' tasks, so "the alarm is
 * ringing" = the last alarm clock **the system clock app** scheduled ([alarmTriggerMs], from
 * `AlarmManager.getNextAlarmClock`) is due and less than [ALARM_RING_MS] old.
 */
fun alarmLikelyRinging(alarmTriggerMs: Long?, nowMs: Long): Boolean =
    alarmTriggerMs != null && nowMs >= alarmTriggerMs && nowMs < alarmTriggerMs + ALARM_RING_MS

/**
 * The alarm to remember: only one set by one of [clockPackages] (the system clock app, and since
 * design 42 Fossify Clock with its pinned signer - [lockClockPackages]; an allowlisted app's
 * `setAlarmClock` never opens the exemption, qa-10-code #4). A newly scheduled future one replaces
 * an old one only once the old one is past its ring time (a snooze doesn't hide the ringing one).
 */
fun rememberAlarm(remembered: Long?, next: Long?, nextCreator: String?, clockPackages: Set<String>, nowMs: Long): Long? {
    val usable = next?.takeIf { nextCreator != null && nextCreator in clockPackages }
    return when {
        remembered != null && alarmLikelyRinging(remembered, nowMs) -> remembered
        usable != null && usable > nowMs -> usable
        remembered != null && remembered > nowMs -> remembered
        else -> null
    }
}

/** The lock came back after stepping aside for the alarm: the alarm was dismissed or snoozed, the
 * exemption ends now rather than after the window. */
fun alarmAfterResume(remembered: Long?, yieldedTo: String?): Long? = if (yieldedTo == "alarm") null else remembered

/*
 * Crash guard (design §2(b), QA 10 #14, qa-10-code #6): only real crashes of the lock count -
 * recorded (commit) by the process's uncaught-exception handler while PinLockActivity exists. A
 * configuration-change recreation or a plain process kill isn't one. 3 crashes within 2 minutes
 * switch the lock off at the next PinLockActivity start (reported as `crash_guard`); a sync re-arms
 * it at most once every 10 minutes.
 */
const val GUARD_WINDOW_MS = 2 * 60_000L
const val GUARD_CRASHES = 3
const val GUARD_REARM_MS = 10 * 60_000L

data class CrashGuard(
    /** Wall-clock times of crashes while the lock screen existed. */
    val crashes: List<Long> = emptyList(),
    /** When the guard tripped (`null` = armed). */
    val trippedAtMs: Long? = null,
)

/** A crash while the lock screen existed. */
fun guardOnCrash(guard: CrashGuard, nowMs: Long): CrashGuard =
    guard.copy(crashes = guard.crashes.filter { nowMs - it in 0 until GUARD_WINDOW_MS } + nowMs)

/** A PinLockActivity.onCreate: trips when [GUARD_CRASHES] crashes happened within the window. */
fun guardOnCreate(guard: CrashGuard, nowMs: Long): CrashGuard {
    if (guard.trippedAtMs != null) return guard
    val recent = guard.crashes.filter { nowMs - it in 0 until GUARD_WINDOW_MS }
    return if (recent.size >= GUARD_CRASHES) CrashGuard(trippedAtMs = nowMs) else guard.copy(crashes = recent)
}

/** At a sync: a guard tripped 10+ minutes ago is re-armed (a clock set back re-arms too - the
 * worst case is one more round of crashes). */
fun guardRearm(guard: CrashGuard, nowMs: Long): CrashGuard {
    val tripped = guard.trippedAtMs ?: return guard
    return if (nowMs - tripped >= GUARD_REARM_MS || nowMs < tripped) CrashGuard() else guard
}

/**
 * Bug 25b-H: Emergency SOS. Five quick power presses make SystemUI start the SOS activity (Personal
 * Safety's countdown, AOSP's Emergency information) as a task of its own - pinned - and the
 * re-front would cover it within [PAUSE_REFRONT_MS]. No app can see which activity is in front
 * (usage events need a permission a device owner can't grant itself), so the timing tells: AOSP
 * 16's GestureLauncherService counts presses at most 500 ms apart and, once two came quickly,
 * swallows the rest while the screen is on - the screen turns on at the first or second press and
 * stays on, and the fifth comes at most about 2 s later. A *stop* of the lock within
 * [SOS_GESTURE_WINDOW_MS] of a screen-on, with an SOS app on the phone, marks a probable SOS.
 *
 * qa-25b-25f H1: that mark alone let whatever stopped the lock in the window (Recents to a
 * thumbnail, a call or an app's full-screen intent that woke the screen) stay up. So while the
 * "sos" yield runs the lock-task list is narrowed to our package, the lock's helpers and the SOS app
 * ([sosNarrowedPackages], the kiosk-off LOCKED list): Android removes every other locked task at
 * once, so only the SOS app (or an emergency call) can stay in front. The yield lasts at most
 * [SOS_FLOW_MS] - the countdown and the dial; a placed call is held as "system_call" - and ends
 * the moment the lock is in front again or anything else is yielded to ([sosMarkKept]); a woken
 * alarm, a call or a VoIP call is never an SOS (they come first in [refrontAction]).
 */
const val SOS_GESTURE_WINDOW_MS = 3_000L

/** How long a probable SOS is left alone without the lock coming back to the front. */
const val SOS_FLOW_MS = 15_000L

/**
 * Only a *stop* marks a probable SOS (a full-screen task of its own covers the lock): Recents - the
 * Overview button right after waking - only pauses it and is re-fronted as always. A pause inside
 * the window waits [SOS_PAUSE_GRACE_MS] for that stop before its check, else [PAUSE_REFRONT_MS].
 */
const val SOS_PAUSE_GRACE_MS = 700L

fun pauseRefrontDelayMs(lastScreenOnAtMs: Long?, pausedAtMs: Long): Long =
    if (sosGestureLikely(lastScreenOnAtMs, pausedAtMs, sosAppPresent = true)) SOS_PAUSE_GRACE_MS else PAUSE_REFRONT_MS

/** A probable SOS is left alone until [untilMs] - never while one of our own screens is in front. */
fun sosFlowNow(untilMs: Long, nowMs: Long, ownScreenInFront: Boolean): Boolean = nowMs < untilMs && !ownScreenInFront

/** The lock left the front [leftFrontAtMs] (elapsed) probably because of the SOS gesture. */
fun sosGestureLikely(lastScreenOnAtMs: Long?, leftFrontAtMs: Long, sosAppPresent: Boolean): Boolean =
    sosAppPresent && lastScreenOnAtMs != null && leftFrontAtMs - lastScreenOnAtMs in 0..SOS_GESTURE_WINDOW_MS

/**
 * Bug 25b-B: at a screen-off the lock is started first; the rest of the screen-off's work (the
 * update fence and self-update wait, the cached policy's re-check, the status-bar heal) runs this
 * much later, so the lock's creation - which waits for the main thread - comes first and nothing of
 * ours delays the lock at a quick screen-on (it draws only after the screen-on, design 32).
 */
const val SCREEN_OFF_WORK_DELAY_MS = 400L

/** The deferred screen-off work runs only while the screen is still off (a heal with the screen on
 * would be visible, the self-update wait would start with the phone in use). */
fun screenOffWorkDue(interactive: Boolean): Boolean = !interactive

/** The probable-SOS mark survives a re-front check only when that check yields to it: any other
 * outcome (the lock in front, the screen off, a call, an alarm, a VoIP call) ends it. */
fun sosMarkKept(action: RefrontAction): Boolean = action == RefrontAction.Yield("sos")
