package me.vibb.music.logic

/*
 * The launch splash's hand-over (PLAN "Splash with the vibb logo", the user's video of 2026-10-25: the icon faded about
 * 0.9 s before the first content frame, leaving a plain dark screen). The system's splash (the theme's
 * `windowSplashScreen*`) stays until the first frame of content - the library (or the player it opens on) with the
 * first snapshot in - and never longer than [SPLASH_CAP_MS], so a slow start never looks hung. Pure; SplashTest.
 */

/** The longest the splash is held for the content (ms since the activity's onCreate). */
const val SPLASH_CAP_MS = 3_000L

/** Whether the splash stays for another frame: no content composed yet and the cap not reached. */
fun keepSplash(contentShown: Boolean, sinceCreateMs: Long, capMs: Long = SPLASH_CAP_MS): Boolean =
    !contentShown && sinceCreateMs < capMs
