package me.vibb.music.logic

/*
 * The launch splash (PLAN "Round M1"). The system's splash (the theme's `windowSplashScreen*`) shows the rings alone -
 * Android pins a branding image to the screen's foot - and goes at the app's first frame, which is the app's own splash
 * (`ui/LaunchSplash.kt`): the same night ground, the rings at exactly the system splash's place and size, and "vibb
 * music" [SPLASH_WORDMARK_GAP_DP] under them. The system splash's fade (MainActivity's exit listener, [SPLASH_FADE_MS])
 * then only reveals the wordmark. The app's splash stays until the first frame of content - the library (or the player
 * it opens on) with the first snapshot in and the carousel already on its start tile ([contentReady]) - and then fades
 * over it. Pure; SplashTest.
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
 * The system splash's icon box (dp), centred on the window: an icon with no icon background (WMShell's
 * `SplashscreenContentDrawer`) gets the 160 dp `starting_surface_icon_size` x 1.2 (`splash_icon_no_background_scale_factor`)
 * = a 192 dp view, whose drawable is drawn 1.5 x that (`AdaptiveForegroundDrawable`'s 1/4 extra inset a side) and clipped
 * to the view's circle - so splash_vibb_mark.xml's 288 dp canvas, unscaled. The app's splash draws the same drawable at
 * this size, centred on the same full window.
 */
const val SPLASH_MARK_DP = 160f * 1.2f * 1.5f

/**
 * The rings' outer edge from the mark's centre (dp): splash_vibb_mark.xml's outer ring (radius up to 25.45) x 3.3645 x
 * 0.94, plus half its 2.6 stroke - inside the 96 dp clip circle, so the unclipped drawing is the splash's. `SplashTest`
 * reads it from the drawable.
 */
const val SPLASH_RINGS_RADIUS_DP = 84.6f

/** From the rings' outer edge to the wordmark's ink (dp; the user: "vibb music" right under the logo). */
const val SPLASH_WORDMARK_GAP_DP = 24f

/** The wordmark's ink height (dp), centred in splash_wordmark.xml's box by scripts/music-splash.py. */
const val SPLASH_WORDMARK_INK_DP = 22.29f

/** How far below the window's centre the wordmark's box is centred (dp). */
const val SPLASH_WORDMARK_BELOW_DP = SPLASH_RINGS_RADIUS_DP + SPLASH_WORDMARK_GAP_DP + SPLASH_WORDMARK_INK_DP / 2

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
