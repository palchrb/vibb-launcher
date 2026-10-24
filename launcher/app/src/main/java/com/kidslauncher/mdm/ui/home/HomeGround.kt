package com.kidslauncher.mdm.ui.home

/*
 * The black behind Home after the Home button (PLAN "Fix backlog", the user's videos 2026-10-16): from an app, the Home
 * button showed Home's icons on solid black for ~150 ms while the app slid away, then the wallpaper; Back was clean.
 * Home's window is transparent over Android's wallpaper (`windowShowWallpaper`), and that wallpaper isn't shown under it
 * while the app-close transition runs (measured on `jelly36` 2026-10-24: 2-3 black frames at 30 fps before, 0 with the
 * copy - `docs/testing/2026-10-24-home-round-run.md`). So Home draws its own copy of the wallpaper under the grid - `WallpaperGround`'s FULL ground, the store's colour, gradient or
 * the one decoded photo with the ink's scrim, placed as Android's (centre crop over the whole window, as the bitmap
 * `WallpaperApplier` sets is drawn) - for the time a transition into Home can run, then goes transparent again.
 *
 * Only for a HOME intent that brings Home from behind (the Home button or gesture, our own `HomeFront` starts: the lock
 * leaving, the boot, an update; leaving Quickstep's fallback Recents for Home sends one too, harmlessly): never for Back
 * (Home is revealed, no intent - its predictive animation is clean and may move Home's window, which a copy would show
 * as a seam), never for a press on Home itself. Never at a lock-up: the copy
 * is the content's background, and the content isn't on the window while the lock is LOCKED or at a dark lock-up
 * (design 16c/32 - the night ground is), and Home's snapshot stays the black task description (no screenshot).
 */

/** The copy stays at least this long after the HOME intent, even when the enter animation ended sooner. */
const val HOME_GROUND_MIN_MS = 700L

/** At most this long without the window's enter animation ending (`onEnterAnimationComplete` may not come). */
const val HOME_GROUND_MAX_MS = 2_500L

/** What Home's own ground depends on ([homeGroundDue]). */
data class HomeGroundState(
    /** When a HOME intent brought Home from behind (elapsed); null - none since Home last stopped. */
    val arrivedAtMs: Long? = null,
    /** The window's enter animation ended (`onEnterAnimationComplete`) and a frame passed since. */
    val enterDone: Boolean = false,
    /** Home left the front (paused) since that intent: an app opening over Home sees the system wallpaper. */
    val leftFront: Boolean = false,
    /** Design 59d step B: the face's unanimated leave brought Home ([com.kidslauncher.mdm.lock.HomeFront.takeOwnGroundDue]) -
     * until Home stops, whatever else (Android's wallpaper window isn't shown while the keyguard goes away). */
    val faceLeave: Boolean = false,
)

/**
 * Whether Home draws its own wallpaper copy now: after the face's leave until the stop (design 59d step B); after a HOME
 * intent from behind for at least [HOME_GROUND_MIN_MS], then until the enter animation ended and a frame passed, at
 * most [HOME_GROUND_MAX_MS] - and no longer once Home left the front.
 */
fun homeGroundDue(state: HomeGroundState, nowMs: Long): Boolean {
    if (state.faceLeave) return true
    val at = state.arrivedAtMs ?: return false
    if (state.leftFront) return false
    val since = nowMs - at
    return since < HOME_GROUND_MIN_MS || (!state.enterDone && since < HOME_GROUND_MAX_MS)
}

/** When Home must look again by time alone (ms from [nowMs]): the end of the copy's floor, or of its cap while the
 * enter animation hasn't ended; null when only an event (the animation's end, a pause, the stop) changes it. */
fun homeGroundRecheckInMs(state: HomeGroundState, nowMs: Long): Long? {
    if (state.faceLeave || state.leftFront) return null
    val at = state.arrivedAtMs ?: return null
    val since = nowMs - at
    return when {
        since < HOME_GROUND_MIN_MS -> HOME_GROUND_MIN_MS - since
        !state.enterDone && since < HOME_GROUND_MAX_MS -> HOME_GROUND_MAX_MS - since
        else -> null
    }
}
