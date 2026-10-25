package me.vibb.music.logic

/*
 * The launch splash's hand-over (PLAN "Splash with the vibb logo", the user's video of 2026-10-25: the icon faded about
 * 0.9 s before the first content frame, leaving a plain dark screen). The system's splash (the theme's
 * `windowSplashScreen*`) stays until the first frame of content - the library (or the player it opens on) with the
 * first snapshot in and the carousel already on its start tile - and never longer than [SPLASH_CAP_MS], so a slow
 * start never looks hung. Pure; SplashTest.
 */

/**
 * The longest the splash is held for the content (ms since the activity's onCreate). Below the 5 s a window that never
 * draws may wait before an input ANR ("no focused window") and below the shell's ~5 s transition timeout
 * (qa-music-r13-code Q-4); `SplashTest` pins the range.
 */
const val SPLASH_CAP_MS = 3_000L

/** Whether the splash stays for another frame: no content composed yet and the cap not reached. */
fun keepSplash(contentShown: Boolean, sinceCreateMs: Long, capMs: Long = SPLASH_CAP_MS): Boolean =
    !contentShown && sinceCreateMs < capMs

/**
 * Whether the screens' first frame is the content the kid should see (qa-music-r13-code Q-10): the first [snapshot]
 * in, the stored now-playing row read ([rowKnown]; "no row" is an answer too), and - with a stored row ([rowStored]) -
 * the carousel's one start on the last played tile done ([startSettled], `MusicViewModel.startOn`), so the library
 * never opens on tile 0 and then jumps. Before that `MusicRoot` composes nothing over the night ground (the splash still
 * holds it); [capped] - the splash's cap ran out - lets it compose whatever it has.
 */
fun contentReady(snapshot: Boolean, rowKnown: Boolean, rowStored: Boolean, startSettled: Boolean, capped: Boolean): Boolean =
    snapshot && (capped || (rowKnown && (!rowStored || startSettled)))
