package com.kidslauncher.mdm.server

/*
 * Touch sounds and vibration on the kid's Settings page (design r9 §4, docs/design/r9-launcher-glance-small-items.md
 * at the monorepo root) - pure, no Android imports, tested in TouchFeedbackTest. QuickControls reads the phone and
 * writes; KidSettingsActivity renders.
 *
 * Android's own two switches for taps: `Settings.System.SOUND_EFFECTS_ENABLED` (every view's click sound; our PIN
 * keys and phone book follow it) and `HAPTIC_FEEDBACK_ENABLED`. `DTMF_TONE_WHEN_DIALING` is left alone (a dial pad's
 * tones; Vibb has no dial pad). Both need the "Modify system settings" app-op design 34 P12 already asks for; without
 * it the row shows the phone's state, read-only. Not a restriction: the override and the pause change nothing, and
 * the parent's bit going off leaves the settings as they are.
 */

/** What the row shows: the two switches' states ([vibration] `null` = no switch, the phone has no vibrator -
 * design 18) and whether a tap may change them. */
data class TouchRowState(val sounds: Boolean, val vibration: Boolean?, val writable: Boolean)

/** The row from the phone's state: each key's value (`null` = missing, which Android reads as on), the vibrator
 * and `Settings.System.canWrite`. */
fun touchRowState(sounds: Int?, haptic: Int?, hasVibrator: Boolean, canWrite: Boolean): TouchRowState =
    TouchRowState(sounds = sounds != 0, vibration = if (hasVibrator) haptic != 0 else null, writable = canWrite)
