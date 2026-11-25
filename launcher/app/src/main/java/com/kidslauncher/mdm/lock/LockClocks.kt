package com.kidslauncher.mdm.lock

/*
 * The clocks of a wait that a reboot or a clock change never shortens (design §5, QA 10 #3) - the Parent PIN's lockout
 * (`server/ParentPinLockout.kt`, design 24) reads them. They were PinBackoff.kt's, whose kid-PIN wait went with our own
 * keypad (design 132). Pure, tested in ParentPinLockoutTest.
 *
 * The clock only extends a wait, unlike timedWindowActive (RestrictionsPause.kt), whose windows end on a reboot or a
 * forward clock jump: here a different boot count restarts the full wait from now, and the wait goes on while either
 * clock says time remains (a wall clock that went back is ignored - elapsed realtime decides then).
 */

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
 * The time left of [window] (0 = over). A new boot (or an elapsed clock that went back) means the full wait - the
 * caller restarts the window from now. Within a boot the wait lasts while elapsed realtime **or** the wall clock says
 * time remains; a wall clock set back past the start is ignored, and nothing is ever longer than the duration.
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
