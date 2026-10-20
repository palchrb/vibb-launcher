package com.kidslauncher.mdm.lock

/*
 * Launcher round 7 §3 (docs/design/r7-launcher-small-items.md): the per-phone switch "Wake the
 * screen for a notification". Pure, tested in LockWakeTest. The wake reuses the VoIP path
 * ([PinLockRuntime.show] with [WakeKind.NOTICE]: the lock's start, then VoipWakeActivity on top of
 * it - never the wake window alone); the lock itself never gets turn-screen-on (design 32 §7).
 */

/** Why [PinLockRuntime.show] turns the screen on: a VoIP ring (design 17) or a notice (round 7 §3). */
enum class WakeKind { VOIP, NOTICE }

/** A woken screen goes off after this long untouched ([noticeWakeOffDue]). */
const val NOTICE_WAKE_ON_MS = 15_000L

/** At least this long between two notice wakes (in memory: a new process starts over). */
const val WAKE_GAP_MS = 30_000L

/** At most this many notice wakes in an hour (in memory). */
const val WAKES_PER_HOUR = 20

/** No notice wake this soon after a lock-up (the screen just went off - the kid put it away). */
const val NOTICE_AFTER_LOCK_UP_MS = 2_000L

/** No notice wake in the first minutes of a process (the gap and hour counters start over then). */
const val NOTICE_AFTER_START_MS = 120_000L

/** The proximity read's budget: no answer in time counts as "not near" (wake). */
const val PROXIMITY_BUDGET_MS = 300L

/** A proximity reading below this many cm (and below the sensor's range) is "near". */
const val PROXIMITY_NEAR_CM = 5f

/**
 * What keeps the screen dark for a notice no matter what came ([noticeWakeAllowed]'s `busy`): our
 * call, a call the system shows, a VoIP ring or call, the emergency flow, a ringing alarm, the boot's
 * mark still holding the lock, or the first [NOTICE_AFTER_START_MS] of the process.
 */
data class NoticeBusy(
    val ourCall: Boolean = false,
    val systemCall: Boolean = false,
    val voip: Boolean = false,
    val emergencyFlow: Boolean = false,
    val alarm: Boolean = false,
    val bootMark: Boolean = false,
    val sinceProcessStartMs: Long = Long.MAX_VALUE,
) {
    val any: Boolean
        get() = ourCall || systemCall || voip || emergencyFlow || alarm || bootMark || sinceProcessStartMs < NOTICE_AFTER_START_MS
}

/**
 * Whether a notice that came ([PinLockRuntime.noticeCame]) turns the screen on: the parent's
 * [switchOn], the lock LOCKED ([mode]), the display not [interactive], nothing [busy], no lock-up in
 * the last [NOTICE_AFTER_LOCK_UP_MS] ([sinceLastLockUpMs], `null` = none in this process), at least
 * [WAKE_GAP_MS] since the last notice wake ([sinceLastWakeMs], `null` = none), fewer than
 * [WAKES_PER_HOUR] in the last hour ([wakesThisHour]) and the phone not in a pocket ([near]: the
 * proximity read - `true` near, `false` far, `null` no sensor or no answer in time = wake).
 */
fun noticeWakeAllowed(
    switchOn: Boolean,
    mode: LockMode,
    interactive: Boolean,
    busy: NoticeBusy,
    sinceLastLockUpMs: Long?,
    sinceLastWakeMs: Long?,
    wakesThisHour: Int,
    near: Boolean?,
): Boolean =
    switchOn && mode == LockMode.LOCKED && !interactive && !busy.any &&
        (sinceLastLockUpMs == null || sinceLastLockUpMs >= NOTICE_AFTER_LOCK_UP_MS) &&
        (sinceLastWakeMs == null || sinceLastWakeMs >= WAKE_GAP_MS) &&
        wakesThisHour < WAKES_PER_HOUR &&
        near != true

/** The notice wakes of the last hour, kept in memory: [wakes] after one more at [now]. */
fun wakesAfter(wakes: List<Long>, now: Long): List<Long> = wakesInHour(wakes, now) + now

/** [wakes] of the last hour before [now]. */
fun wakesInHour(wakes: List<Long>, now: Long): List<Long> = wakes.filter { now - it < 3_600_000L }

/** A proximity [value] (cm) from a sensor whose range is [maxRange]: near (the phone in a pocket). */
fun proximityNear(value: Float, maxRange: Float): Boolean = value < minOf(maxRange, PROXIMITY_NEAR_CM)

/** A screen-on this long after a notice wake is not the wake's own: the kid pressed power ([noticeWakeEndsAtScreenOn]). */
const val NOTICE_WAKE_SCREEN_ON_GRACE_MS = 2_000L

/** A screen-on at [nowMs] ends the notice wake of [wokeAtMs]: it came well after the wake's own screen-on, so the
 * screen is the kid's now (qa-l7-b-code 3). */
fun noticeWakeEndsAtScreenOn(wokeAtMs: Long?, nowMs: Long): Boolean =
    wokeAtMs != null && nowMs - wokeAtMs > NOTICE_WAKE_SCREEN_ON_GRACE_MS

/**
 * A notice-woken screen goes off now: the wake at [wokeAtMs] (elapsed) is [NOTICE_WAKE_ON_MS] old,
 * nothing [touched] the lock since, the screen is still on ([interactive]), the lock still LOCKED
 * and in front ([lockResumed]), nothing [busy] came meanwhile (a call's screen is never turned
 * off), none of the lock's own dialogs is open ([dialogOpen]: Foreldrekode, the emergency confirm - their touches
 * never reach the activity's onUserInteraction) and touch exploration is off ([touchExploration]: TalkBack's hovers
 * and clicks don't either) - qa-l7-b-code 2. A touch hands the screen back to the phone's own timeout.
 */
fun noticeWakeOffDue(
    wokeAtMs: Long?,
    touched: Boolean,
    interactive: Boolean,
    locked: Boolean,
    lockResumed: Boolean,
    busy: Boolean,
    dialogOpen: Boolean,
    touchExploration: Boolean,
    nowMs: Long,
): Boolean =
    wokeAtMs != null && !touched && interactive && locked && lockResumed && !busy && !dialogOpen && !touchExploration &&
        nowMs - wokeAtMs >= NOTICE_WAKE_ON_MS
