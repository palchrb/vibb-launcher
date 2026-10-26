package me.vibb.music.logic

/*
 * The launch splash (PLAN "Round M1"). The system's splash (the theme's `windowSplashScreen*`) shows the rings alone -
 * Android pins a branding image to the screen's foot - and goes at the app's first frame, which is the app's own splash
 * (`ui/LaunchSplash.kt`): the same night ground, the rings at exactly the system splash's place and size, and "vibb
 * music" [SPLASH_WORDMARK_GAP_DP] under them. The system splash's fade (MainActivity's exit listener, [SPLASH_FADE_MS])
 * then only reveals the wordmark. The app's splash stays until the first frame of content - the library (or the player
 * it opens on) with the first snapshot in and the carousel already on its start tile ([contentReady]) - and then fades
 * over it, once "vibb music" has stood whole for [SPLASH_HOLD_MS] ([splashFadeDue]). Pure; SplashTest.
 */

/**
 * The longest the content waits for the stored row and the carousel's start (ms since the screens first composed):
 * then it shows whatever it has ([contentReady]'s `capped`), so a slow start never looks hung. `SplashTest` pins the
 * range.
 */
const val SPLASH_CAP_MS = 3_000L

/** Both fades of the hand-over (ms): the system splash over the app's, and the app's over the content. */
const val SPLASH_FADE_MS = 250L

/**
 * How long after the app splash's first frame a system splash may still be handed over (ms): the hand-over (our exit
 * listener) comes a few frames after that frame, through the shell; none by then means the start had no system splash.
 */
const val SPLASH_HANDOVER_WAIT_MS = 500L

/** The system splash, as the app sees it: not handed over (yet, or ever), fading (our exit listener), gone. */
enum class SystemSplash { UNKNOWN, FADING, GONE }

/**
 * The least the app splash stands whole before its fade (ms; PLAN "Round M1b", the user, 2026-10-26: "vibb music" seen
 * at least 400 ms) - counted from the system splash's exit fade's end, or from the app splash's first frame when there
 * was none. The E1 emulator run measured 50-70 ms at full strength without it when the content was ready at once.
 */
const val SPLASH_HOLD_MS = 400L

/**
 * When the app splash's hold is over (ms since its first frame), or null while the system splash still fades (its
 * animator always ends, [systemSplashExit]). Gone at [goneAtMs]: [SPLASH_HOLD_MS] after it, but never past
 * [SPLASH_CAP_MS] (the cap counts from the first frame and wins; gone with no instant known, the cap alone). Never handed over: the hold and the hand-over wait
 * ([SPLASH_HANDOVER_WAIT_MS]) both count from the first frame and run **concurrently, not in sequence** - the later of
 * the two, so 500 ms today; before the wait is over a hand-over may still come, and the hold is long since run.
 */
fun splashHoldEnd(system: SystemSplash, goneAtMs: Long?): Long? = when (system) {
    SystemSplash.FADING -> null
    SystemSplash.GONE -> minOf((goneAtMs ?: SPLASH_CAP_MS) + SPLASH_HOLD_MS, SPLASH_CAP_MS)
    SystemSplash.UNKNOWN -> maxOf(SPLASH_HANDOVER_WAIT_MS, SPLASH_HOLD_MS)
}

/**
 * Whether the app splash fades at [nowMs] (ms since its first frame; qa-music-splash-b Q-3, round M1b): the content is
 * shown, and its hold is over ([splashHoldEnd]) - so "vibb music" is seen whole and still, never as a faint flash.
 */
fun splashFadeDue(contentShown: Boolean, system: SystemSplash, goneAtMs: Long?, nowMs: Long): Boolean =
    contentShown && splashHoldEnd(system, goneAtMs)?.let { nowMs >= it } == true

/**
 * The system splash's exit, Android aside (MainActivity's listener; qa-music-splash-b Q-5): [state] FADING, [fade], and
 * at its end - a cancel's too - always [remove] (it frees the copied view's icon bitmap), then GONE whatever remove did.
 */
fun systemSplashExit(fade: (onEnd: () -> Unit) -> Unit, remove: () -> Unit, state: (SystemSplash) -> Unit) {
    state(SystemSplash.FADING)
    fade {
        try {
            remove()
        } finally {
            state(SystemSplash.GONE)
        }
    }
}

/**
 * The system splash's icon box (dp), centred on the window: an icon with no icon background (WMShell's
 * `SplashscreenContentDrawer`) gets the 160 dp `starting_surface_icon_size` x 1.2 (`splash_icon_no_background_scale_factor`)
 * = a 192 dp view, whose drawable is laid out 1.5 x that (`AdaptiveForegroundDrawable`'s 1/4 extra inset a side) and
 * clipped to the device's icon mask - so splash_vibb_mark.xml's 288 dp canvas is the geometry. The pixels are softer:
 * the shell rasterises the icon into a 108 dp bitmap (`starting_surface_default_icon_size`) and draws it scaled 192/108
 * = 1.78 x, so the hand-over's cross-fade sharpens the rings - not a scale step. The app's splash draws the same
 * drawable at this size as a vector, centred on the same full window.
 */
const val SPLASH_MARK_DP = 160f * 1.2f * 1.5f

/**
 * The rings' outer radius from the mark's centre (dp): splash_vibb_mark.xml's outer ring (radius up to 25.46) x 3.3645
 * x 0.94, plus half its 2.6 stroke - inside every AOSP icon mask (the smallest inner radius, pebble's, is 92 dp), so the
 * unclipped drawing is the splash's. `SplashTest` reads it from the drawable.
 */
const val SPLASH_RINGS_RADIUS_DP = 84.6f

/**
 * The rings' lowest ink below the mark's centre (dp; qa-music-splash-b Q-9): the hand-drawn outer ring's lowest point
 * (y 25.02) plus half the stroke, x 0.94 x 3.3645 - what "under the rings" is measured from. `SplashTest` reads it too.
 */
const val SPLASH_RINGS_BOTTOM_DP = 83.24f

/** From the rings' lowest ink to the wordmark's ink (dp; the user: "vibb music" right under the logo). */
const val SPLASH_WORDMARK_GAP_DP = 24f

/** The wordmark's ink height (dp), centred in splash_wordmark.xml's box by scripts/music-splash.py. */
const val SPLASH_WORDMARK_INK_DP = 22.29f

/** How far below the window's centre the wordmark's box is centred (dp). */
const val SPLASH_WORDMARK_BELOW_DP = SPLASH_RINGS_BOTTOM_DP + SPLASH_WORDMARK_GAP_DP + SPLASH_WORDMARK_INK_DP / 2

/**
 * Whether the screens' first frame is the content the kid should see (qa-music-r13-code Q-10): the first [snapshot]
 * in, the stored now-playing row read ([rowKnown]; "no row" is an answer too), and - with a stored row ([rowStored]) -
 * the carousel's one start on the last played tile done ([startSettled], `MusicViewModel.startOn`), so the library
 * never opens on tile 0 and then jumps. A snapshot with no [entries] has no carousel to jump: its empty state is the
 * content at once (the emulator run of music-r13). Before that `MusicRoot` composes nothing over the night ground (the
 * app's splash covers it); [capped] - [SPLASH_CAP_MS] ran out, the backstop only - lets it compose whatever it has.
 */
fun contentReady(snapshot: Boolean, entries: Boolean, rowKnown: Boolean, rowStored: Boolean, startSettled: Boolean, capped: Boolean): Boolean =
    snapshot && (capped || !entries || (rowKnown && (!rowStored || startSettled)))
