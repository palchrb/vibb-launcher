package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.server.SoundChoice
import com.kidslauncher.mdm.server.SoundRowState
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/*
 * Kid Settings' sound choice as one even segmented control (design 34 P9, mockup 06) - pure, no
 * Android imports, tested in SoundSegmentsTest. Only the look: the two choices and what a tap does
 * stay design 18's (`server/SoundMode.kt`: Sound = ringer Normal, "Silent (vibrate)" = ringer
 * Vibrate, read-only while Do Not Disturb is on). KidSettingsActivity renders it; the two halves
 * stay `RadioButton`s in the `RadioGroup` of activity_kid_settings.xml, so TalkBack reads them as
 * radio buttons in a group of two with the chosen one selected (QA L7).
 *
 * The track: white 8 % (#14FFFFFF, as the card under it), radius 14 dp, 3 dp inside it. The halves:
 * equal widths (weight 1, no gap), >= 48 dp tall, radius 11 dp; the chosen one the accent with
 * night text, the other no fill with the wallpaper's ink. The labels wrap and the control grows
 * with the font scale - nothing clips or ellipsizes.
 */

/** White 8 %: the track, as the mockup's `rgba(255,255,255,.07)` rounded to the card's alpha. */
const val SEGMENT_TRACK_ARGB: Int = 0x14FFFFFF
const val SEGMENT_TRACK_RADIUS_DP = 14f
const val SEGMENT_TRACK_PADDING_DP = 3f
const val SEGMENT_RADIUS_DP = 11f

/** Every half is at least a touch target, at any font scale. */
const val SEGMENT_MIN_HEIGHT_DP = 48f
const val SEGMENT_TEXT_SP = 14f
const val SEGMENT_PADDING_H_DP = 8f
const val SEGMENT_PADDING_V_DP = 6f

/** The order of the halves, start to end. */
val SOUND_SEGMENTS: List<SoundChoice> = listOf(SoundChoice.SOUND, SoundChoice.SILENT_VIBRATE)

/** One half: which choice, whether it is the selected one, whether a tap may change the mode. */
data class SoundSegment(val choice: SoundChoice, val checked: Boolean, val enabled: Boolean)

/**
 * The halves for a row state: the phone's mode is the checked one (none while fully silent or
 * unknown), both read-only together. What a tap sets stays [com.kidslauncher.mdm.server.soundTapMode].
 */
fun soundSegments(state: SoundRowState): List<SoundSegment> =
    SOUND_SEGMENTS.map { SoundSegment(it, checked = state.selected == it, enabled = state.enabled) }

/**
 * How the control lays out in [rowContentDp] (the row's width inside its padding) at [fontScale]:
 * each half's width (equal; the track's padding off both ends), and per label the lines and the
 * half's height. A model, an upper bound: [charEm] is a generous average character width for
 * Nunito's bold weights, the scale linear (Android 14 grows large text less), the line 1.4 x the
 * text size. Both halves take the taller height (the row's `match_parent` halves).
 */
data class SegmentLayout(val segmentWidthDp: Float, val lines: List<Int>, val segmentHeightDp: Float) {
    val trackHeightDp: Float get() = segmentHeightDp + 2 * SEGMENT_TRACK_PADDING_DP
}

fun segmentLayout(rowContentDp: Float, labels: List<String>, fontScale: Float, charEm: Float = 0.6f): SegmentLayout {
    val width = (rowContentDp - 2 * SEGMENT_TRACK_PADDING_DP) / labels.size
    val textDp = SEGMENT_TEXT_SP * fontScale
    val textWidth = width - 2 * SEGMENT_PADDING_H_DP
    val lines = labels.map { label -> linesFor(label, textWidth, textDp * charEm) }
    val height = lines.maxOf { max(SEGMENT_MIN_HEIGHT_DP, it * textDp * 1.4f + 2 * SEGMENT_PADDING_V_DP) }
    return SegmentLayout(width, lines, height)
}

/** Lines a label takes at [charDp] a character, breaking at spaces (a word longer than a line
 * breaks inside it, as a TextView does). */
private fun linesFor(label: String, widthDp: Float, charDp: Float): Int {
    val perLine = max(1, floor(widthDp / charDp).toInt())
    var lines = 1
    var used = 0
    for (word in label.split(' ')) {
        val len = word.length
        val needed = if (used == 0) len else used + 1 + len
        if (needed <= perLine) {
            used = needed
        } else if (len <= perLine) {
            lines++
            used = len
        } else {
            // The long word starts on a line of its own and breaks inside it.
            if (used > 0) lines++
            val more = ceil(len / perLine.toDouble()).toInt() - 1
            lines += more
            used = len - more * perLine
        }
    }
    return lines
}
