package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.BackoffWindow
import com.kidslauncher.mdm.lock.LockClocks
import com.kidslauncher.mdm.lock.backoffRemainingMs
import com.kidslauncher.mdm.lock.backoffWindow
import com.kidslauncher.mdm.server.dto.ParentPinEvent

/*
 * The parent PIN's escalating lockout (design 24 §2.1). Pure, tested in ParentPinLockoutTest;
 * ParentPin runs it under one lock and ParentPinStore persists it.
 *
 * 5 wrong tries in a row lock the parent PIN everywhere for 15 min, then 30 min, 1 h, ... up to
 * 24 h. A correct PIN clears the tries but keeps the level; the level drops one step for every
 * 24 h without a wrong try, and a new PIN from the server (another fingerprint) resets it all.
 * Both windows follow the kid backoff's clock rules within a boot (lock/PinBackoff.kt) and carry
 * over by the wall clock across a reboot - never longer than their duration.
 */

const val PARENT_FREE_TRIES = 5
const val PARENT_MIN_PIN_LENGTH = 6
const val PARENT_LOCKOUT_BASE_MS = 15 * 60_000L
const val PARENT_LOCKOUT_CAP_MS = 24 * 60 * 60_000L
const val PARENT_LEVEL_DECAY_MS = 24 * 60 * 60_000L

/** parentLockoutMs(8) is already the cap. */
const val PARENT_LEVEL_MAX = 8

/** Where the parent PIN was typed (`entry` on the wire, design 24 §1). */
enum class ParentPinEntry(val wire: String) {
    /** LockActivity's "Enter unlock code": the 2-hour offline override. */
    OVERRIDE("override"),

    /** The PIN lock's "Parent code": opens that lock only. */
    PIN_LOCK("pin_lock"),

    /** The launcher's Settings gate. */
    SETTINGS("settings"),

    /** Settings' "Pause all restrictions". */
    PAUSE("pause"),

    /** Settings' "Install from Play". */
    INSTALL_MODE("install_mode"),
}

sealed interface ParentPinResult {
    /** For OVERRIDE, PAUSE and INSTALL_MODE the window has started already. */
    data object Ok : ParentPinResult

    data class Wrong(val triesLeft: Int, val nextLockoutMs: Long) : ParentPinResult

    data class LockedOut(val remainingMs: Long) : ParentPinResult

    /** Fewer than [PARENT_MIN_PIN_LENGTH] characters: not counted (QA #14). */
    data object TooShort : ParentPinResult

    data object NotConfigured : ParentPinResult

    /** The try couldn't be counted (a failed commit, QA #3) - nothing was recorded. */
    /** A failed commit ([storageFull] when the disk is), or - design 24b - the vault's key failed:
     * [untilSync], nothing can be checked until the phone reaches the server. */
    data class Unavailable(val storageFull: Boolean, val untilSync: Boolean = false) : ParentPinResult

    /** An app outside the allowlist is a device admin or has an accessibility service on
     * ([ForeignAccess], QA 24b High 1): no PIN is taken - nothing counted, nothing verified. */
    data object ForeignAccessOn : ParentPinResult
}

const val PIN_EVENT_UNLOCKED = "unlocked"
const val PIN_EVENT_WRONG = "wrong"
const val PIN_EVENT_LOCKOUT = "lockout"

data class ParentLockoutState(
    /** Wrong tries since the last lockout began (0..4); a correct PIN clears them. */
    val failures: Int = 0,
    /** The running (or last) lockout. */
    val lockout: BackoffWindow? = null,
    /** From the last wrong try, (level then) x 24 h long: what is left of it is the level now
     * ([levelNow]) - the level is never stored on its own (qa-24-code S1). */
    val decay: BackoffWindow? = null,
    /** Which parent PIN this is about ([parentPinFingerprint]). */
    val pinFingerprint: String? = null,
)

/** 15m, 30m, 1h, 2h, 4h, 8h, 16h, 24h for levels 1..8 (level 0 and anything above 8 stay in range). */
fun parentLockoutMs(level: Int): Long =
    minOf(PARENT_LOCKOUT_BASE_MS shl (level - 1).coerceIn(0, 7), PARENT_LOCKOUT_CAP_MS)

/** The level at [now]: the whole or partial days left of the decay window. Call on a
 * [refreshParentLockout]ed state. */
fun levelNow(state: ParentLockoutState, now: LockClocks): Int {
    val left = state.decay?.let { backoffRemainingMs(it, now) } ?: return 0
    return ((left + PARENT_LEVEL_DECAY_MS - 1) / PARENT_LEVEL_DECAY_MS).toInt().coerceIn(0, PARENT_LEVEL_MAX)
}

/** The lockout time left at [now] (0 = none). Call on a [refreshParentLockout]ed state. */
fun parentLockoutRemaining(state: ParentLockoutState, now: LockClocks): Long =
    state.lockout?.let { backoffRemainingMs(it, now) } ?: 0L

/** A new parent PIN from the server resets the tries, the level and both windows. */
fun forParentPin(state: ParentLockoutState, fingerprint: String): ParentLockoutState =
    if (state.pinFingerprint == fingerprint) state else ParentLockoutState(pinFingerprint = fingerprint)

/**
 * After a reboot (another boot count, or elapsed realtime went back) each window is re-anchored to
 * this boot with what the wall clock says is left of it - all of it if the wall clock is now
 * before its start, never more than its duration. Unlike the kid backoff (a full restart), so
 * daily reboots can't keep the parent out at 24 h for good. Within a boot nothing changes.
 */
fun refreshParentLockout(state: ParentLockoutState, now: LockClocks): ParentLockoutState =
    state.copy(lockout = reanchor(state.lockout, now), decay = reanchor(state.decay, now))

private fun reanchor(window: BackoffWindow?, now: LockClocks): BackoffWindow? {
    window ?: return null
    val newBoot = window.bootCount != now.bootCount || now.elapsedMs < window.elapsedStartMs
    if (!newBoot) return window
    val remaining = if (now.wallMs < window.wallStartMs) {
        window.durationMs
    } else {
        (window.wallStartMs + window.durationMs - now.wallMs).coerceIn(0L, window.durationMs)
    }
    return if (remaining > 0L) backoffWindow(now, remaining) else null
}

/** [beginParentAttempt]'s result: the counted state, its `wrong` or `lockout` event, and the
 * lockout this try started (0 = none). */
data class ParentAttempt(val state: ParentLockoutState, val event: ParentPinEvent, val lockoutMs: Long)

/**
 * Right before PBKDF2: the try is counted as wrong - a 5th starts the next lockout - and carries
 * its event, so both are committed together before the hash runs (QA #6). On a match the caller
 * goes back to the pre-attempt state with no failures and swaps the event for `unlocked`.
 */
fun beginParentAttempt(state: ParentLockoutState, now: LockClocks, entry: ParentPinEntry, eventId: String): ParentAttempt {
    val level = levelNow(state, now)
    val failures = state.failures + 1
    if (failures >= PARENT_FREE_TRIES) {
        val next = minOf(level + 1, PARENT_LEVEL_MAX)
        val duration = parentLockoutMs(next)
        val counted = state.copy(
            failures = 0,
            lockout = backoffWindow(now, duration),
            decay = backoffWindow(now, next * PARENT_LEVEL_DECAY_MS),
        )
        val event = ParentPinEvent(eventId, PIN_EVENT_LOCKOUT, entry.wire, now.wallMs, now.wallMs + duration, null, next)
        return ParentAttempt(counted, event, duration)
    }
    val counted = state.copy(
        failures = failures,
        decay = if (level > 0) backoffWindow(now, level * PARENT_LEVEL_DECAY_MS) else null,
    )
    return ParentAttempt(counted, ParentPinEvent(eventId, PIN_EVENT_WRONG, entry.wire, now.wallMs, null, failures, null), 0L)
}

/** What a wrong (not 5th) try tells the parent: tries left, and how long the next lockout is. */
fun wrongResult(counted: ParentLockoutState, now: LockClocks): ParentPinResult.Wrong =
    ParentPinResult.Wrong(PARENT_FREE_TRIES - counted.failures, parentLockoutMs(levelNow(counted, now) + 1))

/**
 * The state that replaces an unreadable one (QA #2): fail closed with a level-1, 15-minute lockout
 * starting now, then fresh tries - never a dead end.
 */
fun unreadableParentState(now: LockClocks, fingerprint: String) = ParentLockoutState(
    lockout = backoffWindow(now, PARENT_LOCKOUT_BASE_MS),
    decay = backoffWindow(now, PARENT_LEVEL_DECAY_MS),
    pinFingerprint = fingerprint,
)

/**
 * The once-only migration from the old counter (`offline_override_failed_attempts`,
 * `offline_override_locked_until`, wall clock): its count carries over; a running lockout becomes
 * level 1 with what is left of it, at most 15 min (a far-future value from a bad clock is
 * capped), and 24 h of decay.
 */
fun migrateParentLockout(failedAttempts: Int, lockedUntilWallMs: Long, now: LockClocks, fingerprint: String): ParentLockoutState {
    val failures = failedAttempts.coerceIn(0, PARENT_FREE_TRIES - 1)
    if (lockedUntilWallMs <= now.wallMs) return ParentLockoutState(failures = failures, pinFingerprint = fingerprint)
    return ParentLockoutState(
        failures = failures,
        lockout = backoffWindow(now, minOf(lockedUntilWallMs - now.wallMs, PARENT_LOCKOUT_BASE_MS)),
        decay = backoffWindow(now, PARENT_LEVEL_DECAY_MS),
        pinFingerprint = fingerprint,
    )
}

/** "15 min", "1 h 45 min", "24 h" - rounded up to the minute; the units come from the strings. */
fun lockoutText(remainingMs: Long, hourUnit: String = "h", minuteUnit: String = "min"): String {
    val minutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest $minuteUnit"
        rest == 0L -> "$hours $hourUnit"
        else -> "$hours $hourUnit $rest $minuteUnit"
    }
}
