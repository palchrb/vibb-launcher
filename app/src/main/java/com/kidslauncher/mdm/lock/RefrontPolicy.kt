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
    /** `TelecomManager.isInCall`: the system dialer shows a call (emergency calls). */
    val telecomInCall: Boolean,
    /** "Emergency call" was tapped on the lock less than [EMERGENCY_FLOW_MS] ago: the
     * emergency dialer / Telecom confirmation is in front. */
    val emergencyFlow: Boolean,
    /** The default clock app's alarm is probably ringing ([alarmLikelyRinging]). */
    val alarmRinging: Boolean,
)

/** How long after "Emergency call" the lock stays out of the way even without a call. */
const val EMERGENCY_FLOW_MS = 2 * 60_000L

/** Exempt screens are looked at again this often - the lock comes back when they close. */
const val EXEMPT_RECHECK_MS = 2_000L

sealed interface RefrontAction {
    /** Nothing to do (unlocked, in front, or the screen is off). */
    data object Stop : RefrontAction

    /** An exempt screen is in front (our call, the system dialer, Telecom, the alarm): don't
     * fight it; look again in [recheckMs]. Each such episode is counted and reported. */
    data class Yield(val reason: String, val recheckMs: Long = EXEMPT_RECHECK_MS) : RefrontAction

    /** Bring the lock back now and look again in [nextCheckMs]. */
    data class Refront(val nextCheckMs: Long) : RefrontAction
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
    !inputs.locked || inputs.lockResumed || !inputs.interactive -> RefrontAction.Stop
    inputs.ourCall -> RefrontAction.Yield("call")
    inputs.telecomInCall -> RefrontAction.Yield("system_call")
    inputs.emergencyFlow -> RefrontAction.Yield("emergency")
    inputs.alarmRinging -> RefrontAction.Yield("alarm")
    else -> RefrontAction.Refront(refrontDelayMs(attempt + 1))
}

/** An `onStop` that isn't the lock losing the front: a configuration change (we are portrait
 * and handle the rest, but still), or finishing after the right PIN. */
fun stopStartsRefront(locked: Boolean, changingConfigurations: Boolean, finishing: Boolean): Boolean =
    locked && !changingConfigurations && !finishing

/** How long an alarm is assumed to ring after its time (alarm apps time out after ~10 min). */
const val ALARM_RING_MS = 10 * 60_000L

/**
 * The decision after QA review: the default clock app's alarm screen shows over the lock so it
 * can be snoozed or dismissed without the PIN. We can't see other apps' tasks, so "the alarm is
 * ringing" = the last alarm clock we saw scheduled ([alarmTriggerMs], from
 * `AlarmManager.getNextAlarmClock`) is due and less than [ALARM_RING_MS] old.
 */
fun alarmLikelyRinging(alarmTriggerMs: Long?, nowMs: Long): Boolean =
    alarmTriggerMs != null && nowMs >= alarmTriggerMs && nowMs < alarmTriggerMs + ALARM_RING_MS

/** The alarm to remember: a newly scheduled future one replaces an old one only once the old one
 * is past its ring time (so a snooze doesn't hide the ringing one). */
fun rememberAlarm(remembered: Long?, next: Long?, nowMs: Long): Long? = when {
    remembered != null && alarmLikelyRinging(remembered, nowMs) -> remembered
    next != null && next > nowMs -> next
    remembered != null && remembered > nowMs -> remembered
    else -> null
}

/*
 * Crash guard (design §2(b), QA 10 #14): PinLockActivity persists a start (commit) in onCreate
 * before any work and clears it after 30 s resumed or a normal finish. A start that was never
 * cleared is a crash (or a kill in the first 30 s). 3 of them within 2 minutes switch the lock off
 * (reported as `crash_guard`); a sync re-arms it at most once every 10 minutes.
 */
const val GUARD_WINDOW_MS = 2 * 60_000L
const val GUARD_CRASHES = 3
const val GUARD_REARM_MS = 10 * 60_000L
const val GUARD_CLEAR_AFTER_MS = 30_000L

data class CrashGuard(
    /** Wall-clock times of starts not cleared yet. */
    val pendingStarts: List<Long> = emptyList(),
    /** When the guard tripped (`null` = armed). */
    val trippedAtMs: Long? = null,
)

/** A PinLockActivity.onCreate: trips when [GUARD_CRASHES] earlier starts within the window were
 * never cleared; otherwise records this start. */
fun guardOnCreate(guard: CrashGuard, nowMs: Long): CrashGuard {
    if (guard.trippedAtMs != null) return guard
    val recent = guard.pendingStarts.filter { nowMs - it in 0 until GUARD_WINDOW_MS }
    return if (recent.size >= GUARD_CRASHES) {
        CrashGuard(trippedAtMs = nowMs)
    } else {
        guard.copy(pendingStarts = recent + nowMs)
    }
}

fun guardCleared(guard: CrashGuard): CrashGuard = guard.copy(pendingStarts = emptyList())

/** At a sync: a guard tripped 10+ minutes ago is re-armed (a clock set back re-arms too - the
 * worst case is one more round of crashes). */
fun guardRearm(guard: CrashGuard, nowMs: Long): CrashGuard {
    val tripped = guard.trippedAtMs ?: return guard
    return if (nowMs - tripped >= GUARD_REARM_MS || nowMs < tripped) CrashGuard() else guard
}
