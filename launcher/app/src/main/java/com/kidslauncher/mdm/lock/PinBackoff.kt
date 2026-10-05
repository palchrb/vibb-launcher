package com.kidslauncher.mdm.lock

/*
 * The wait after wrong kid PINs (design §5, QA 10 #3). Pure, tested in PinBackoffTest; stored by
 * PinLockStore in CE prefs `pin_lock_state` with commit().
 *
 * Tries 1-4 are free; after the 5th wrong PIN the phone waits 30 s, then 1, 2, 5 and 15 minutes
 * (cap) - about 100 tries a day, so about 50 days on average for one 4-digit PIN (accepted, QA 10
 * #12). It never wipes. The parent code on the lock has its own counter (OfflineOverride) and
 * works during the wait.
 *
 * The clock only extends a wait, unlike timedWindowActive (RestrictionsPause.kt), whose windows
 * end on a reboot or a forward clock jump: here a different boot count restarts the full wait
 * from now, and the wait goes on while either clock says time remains (a wall clock that went
 * back is ignored - elapsed realtime decides then).
 */

const val PIN_FREE_ATTEMPTS = 4
private val BACKOFF_STEPS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L, 900_000L)

/** The wait after [failures] wrong PINs in a row (0 while they are free). */
fun backoffDurationMs(failures: Int): Long {
    if (failures <= PIN_FREE_ATTEMPTS) return 0L
    val index = (failures - PIN_FREE_ATTEMPTS - 1).coerceAtMost(BACKOFF_STEPS_MS.size - 1)
    return BACKOFF_STEPS_MS[index]
}

/** The three clocks at one moment: wall (`currentTimeMillis`), elapsed realtime, boot count
 * (`Settings.Global.BOOT_COUNT`, -1 unknown). */
data class LockClocks(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

/** A running wait, by every clock. */
data class BackoffWindow(
    val wallStartMs: Long,
    val elapsedStartMs: Long,
    val bootCount: Int,
    val durationMs: Long,
)

fun backoffWindow(now: LockClocks, durationMs: Long) =
    BackoffWindow(now.wallMs, now.elapsedMs, now.bootCount, durationMs)

/**
 * The time left of [window] (0 = over). A new boot (or an elapsed clock that went back) means the
 * full wait - the caller restarts the window from now ([refreshBackoff]). Within a boot the wait
 * lasts while elapsed realtime **or** the wall clock says time remains; a wall clock set back
 * past the start is ignored, and nothing is ever longer than the duration.
 */
fun backoffRemainingMs(window: BackoffWindow, now: LockClocks): Long {
    if (window.durationMs <= 0L) return 0L
    if (window.bootCount != now.bootCount) return window.durationMs
    val sinceElapsed = now.elapsedMs - window.elapsedStartMs
    if (sinceElapsed < 0L) return window.durationMs
    val remainingElapsed = window.durationMs - sinceElapsed
    val sinceWall = now.wallMs - window.wallStartMs
    val remainingWall = if (sinceWall < 0L) remainingElapsed else window.durationMs - sinceWall
    return maxOf(remainingElapsed, remainingWall).coerceIn(0L, window.durationMs)
}

/** What PinLockStore persists. [hashFingerprint] identifies the kid PIN the failures were for. */
data class BackoffState(
    val failures: Int = 0,
    val window: BackoffWindow? = null,
    val hashFingerprint: String? = null,
)

/** A new PIN from the parent resets the count and the wait (design §5). */
fun backoffForHash(state: BackoffState, fingerprint: String?): BackoffState =
    if (state.hashFingerprint == fingerprint) state else BackoffState(hashFingerprint = fingerprint)

/**
 * The state to use (and persist, when it changed) at [now]: a window from another boot restarts
 * the full wait from now - a reboot never shortens it.
 */
fun refreshBackoff(state: BackoffState, now: LockClocks): BackoffState {
    val window = state.window ?: return state
    val restart = window.bootCount != now.bootCount || now.elapsedMs < window.elapsedStartMs
    return if (restart) state.copy(window = backoffWindow(now, window.durationMs)) else state
}

/** Time left before the next try may be checked (0 = go). Call on a [refreshBackoff]ed state. */
fun backoffRemaining(state: BackoffState, now: LockClocks): Long =
    state.window?.let { backoffRemainingMs(it, now) } ?: 0L

/**
 * Right before the (slow) PBKDF2 check: the failure is counted - and its wait started - first,
 * and persisted with commit(), so killing the process or rebooting mid-check can't skip it.
 * [attemptSucceeded] undoes it.
 */
fun beginAttempt(state: BackoffState, now: LockClocks): BackoffState {
    val failures = state.failures + 1
    val duration = backoffDurationMs(failures)
    return state.copy(failures = failures, window = if (duration > 0L) backoffWindow(now, duration) else null)
}

fun attemptSucceeded(state: BackoffState): BackoffState = state.copy(failures = 0, window = null)

/** "0:28" for the lock screen's "Try again in 0:28". */
fun backoffText(remainingMs: Long): String {
    val seconds = (remainingMs + 999L) / 1000L
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
