package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.server.SoundChoice
import com.kidslauncher.mdm.ui.wallpaper.InkChoice

/*
 * Kid Settings' sound choice as one even segmented control (design 34 P9, mockup 06) - pure, no
 * Android imports, tested in SoundSegmentsTest. Only the look: the two choices and what a tap does
 * stay design 18's (`server/SoundMode.kt`: Sound = ringer Normal, "Silent (vibrate)" = ringer
 * Vibrate, read-only while Do Not Disturb is on). KidSettingsActivity draws the halves with
 * [segmentColours]; they stay `RadioButton`s in the `RadioGroup` of activity_kid_settings.xml, so
 * TalkBack reads them as radio buttons in a group of two with the chosen one selected (QA L7). The
 * layout itself (track, equal halves, minimums) lives in the XML; SoundSegmentsTest models it.
 */

/** The halves' corner radius: the track's 14 dp less its 3 dp padding, so the chosen half is concentric. */
const val SEGMENT_RADIUS_DP = 11f

/** The chosen half's outline on a light ground (qa-34-s10-code L1) and the focus ring (N2). */
const val SEGMENT_STROKE_DP = 1f
const val SEGMENT_FOCUS_RING_DP = 2f

/** The order of the halves, start to end (the layout's `kid_settings_sound_on`, `_vibrate`). */
val SOUND_SEGMENTS: List<SoundChoice> = listOf(SoundChoice.SOUND, SoundChoice.SILENT_VIBRATE)

/**
 * What each state of a half looks like on a ground with [ink]: the chosen half [checkedFill] (the
 * accent) with [checkedText] (night), outlined in [checkedStroke] when there is one; the other no
 * fill with [uncheckedText] (the ink); [press] the ripple; a focused half a ring in
 * [focusRingChecked] (night on the peach) / [focusRingUnchecked] (peach, the ink on a light ground).
 */
data class SegmentColours(
    val checkedFill: Int,
    val checkedText: Int,
    val checkedStroke: Int?,
    val uncheckedText: Int,
    val press: Int,
    val focusRingChecked: Int,
    val focusRingUnchecked: Int,
)

/**
 * The colours for [ink], with the palette's [accent] (`kid_accent`) and [night] (`kid_ground`).
 * On a light ground (dark ink) the peach fill is only ~2:1 against the white track, and night text
 * against dark ink 1.1:1 - so the chosen half also gets a 1 dp outline in the ink (17:1 on white,
 * WCAG 1.4.11's 3:1 for a state). On a dark ground the fill alone is ~9:1 to the track and needs none.
 */
fun segmentColours(ink: InkChoice, accent: Int, night: Int): SegmentColours = SegmentColours(
    checkedFill = accent,
    checkedText = night,
    checkedStroke = if (ink.darkInk) ink.ink else null,
    uncheckedText = ink.ink,
    press = (0x1F shl 24) or (ink.ink and 0xFFFFFF),
    focusRingChecked = night,
    focusRingUnchecked = if (ink.darkInk) ink.ink else accent,
)
