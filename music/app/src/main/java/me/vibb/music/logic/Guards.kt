package me.vibb.music.logic

import android.content.Intent

/*
 * How the music app honours the launcher's time rules and Home card (design 21 step 4, the fix round
 * after qa-21-step4-code). Pure; GuardsTest.
 */

/**
 * Nothing starts playing while the launcher has suspended this app - a time rule without it as an
 * exempt app (qa-21-step4-code M1). Every way in is gated: any controller (the launcher's card and
 * lock view, the headset's and Bluetooth buttons, the app's own screens) through `KidPlayer`, the
 * app's own play commands, and playback resumption. A pause is always allowed.
 */
fun playAllowed(suspended: Boolean): Boolean = !suspended

/**
 * Whether an intent asks to continue playing (`EXTRA_RESUME`, Home's snapshot card): once, when
 * it is delivered - never again from Recents (the task's base intent comes back with
 * `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, also after a reboot) or in a restored instance
 * (qa-21-step4-code L3). Nothing ever starts playback by itself.
 */
fun resumeRequested(extra: Boolean, flags: Int, restored: Boolean): Boolean =
    extra && !restored && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
