package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_ALL
import com.kidslauncher.mdm.lock.INTERRUPTION_FILTER_PRIORITY
import com.kidslauncher.mdm.lock.RINGER_MODE_NORMAL
import com.kidslauncher.mdm.lock.RINGER_MODE_SILENT
import com.kidslauncher.mdm.lock.RINGER_MODE_VIBRATE
import com.kidslauncher.mdm.server.SoundChoice
import com.kidslauncher.mdm.server.soundRowState
import com.kidslauncher.mdm.server.soundTapMode
import com.kidslauncher.mdm.ui.home.contrastRatio
import com.kidslauncher.mdm.ui.home.relativeLuminance
import com.kidslauncher.mdm.ui.wallpaper.INK_DARK
import com.kidslauncher.mdm.ui.wallpaper.INK_LIGHT
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.inkForColours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Design 34 P9 (mockup 06, QA L7): Kid Settings' sound choice as one even segmented control. The
 * two halves are equal, >= 48 dp, their labels wrap and grow the control at font scale 1.0/1.3/2.0
 * on 320/360/411 dp; the selection is the phone's ringer mode (design 18 unchanged); TalkBack reads
 * two radio buttons in a group with the selected state; both states reach 4.5:1.
 */
class SoundSegmentsTest {

    // The layout's values, mirrored here and checked against the XML below (qa-34-s10-code L3:
    // the model is the test's, as PinLockLayoutTest's is).
    private companion object {
        const val SEGMENT_TRACK_ARGB: Int = 0x14FFFFFF
        const val SEGMENT_TRACK_RADIUS_DP = 14f
        const val SEGMENT_TRACK_PADDING_DP = 3f
        const val SEGMENT_MIN_HEIGHT_DP = 48f
        const val SEGMENT_TEXT_SP = 14f
        const val SEGMENT_PADDING_H_DP = 8f
        const val SEGMENT_PADDING_V_DP = 6f
    }

    /**
     * How the control lays out in [rowContentDp] at [fontScale]: each half's width (equal; the
     * track's padding off both ends), and per label the lines and the half's height. An upper
     * bound: [charEm] a generous average character width for Nunito's bold weights, the scale
     * linear, the line 1.4 x the text size. Both halves take the taller height (`match_parent`).
     */
    private data class SegmentLayout(val segmentWidthDp: Float, val lines: List<Int>, val segmentHeightDp: Float) {
        val trackHeightDp: Float get() = segmentHeightDp + 2 * SEGMENT_TRACK_PADDING_DP
    }

    private fun segmentLayout(rowContentDp: Float, labels: List<String>, fontScale: Float, charEm: Float = 0.6f): SegmentLayout {
        val width = (rowContentDp - 2 * SEGMENT_TRACK_PADDING_DP) / labels.size
        val textDp = SEGMENT_TEXT_SP * fontScale
        val textWidth = width - 2 * SEGMENT_PADDING_H_DP
        val lines = labels.map { label -> linesFor(label, textWidth, textDp * charEm) }
        val height = lines.maxOf { max(SEGMENT_MIN_HEIGHT_DP, it * textDp * 1.4f + 2 * SEGMENT_PADDING_V_DP) }
        return SegmentLayout(width, lines, height)
    }

    /** Lines at [charDp] a character, breaking at spaces; a word longer than a line breaks inside
     * it (with `hyphenationFrequency="normal"` Android may hyphenate it there). */
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
                if (used > 0) lines++
                val more = ceil(len / perLine.toDouble()).toInt() - 1
                lines += more
                used = len - more * perLine
            }
        }
        return lines
    }

    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file(path)).documentElement

    private val layout = xml("res/layout/activity_kid_settings.xml")
    private fun Element.attr(name: String): String = getAttributeNS(ns, name)
    private fun Element.dp(name: String): Float = attr(name).removeSuffix("dp").removeSuffix("sp").toFloat()
    private fun byId(id: String): Element = layout.getElementsByTagName("*").let { all ->
        (0 until all.length).map { all.item(it) as Element }.single { it.attr("id") == "@+id/$id" }
    }
    private fun kids(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private val group = byId("kid_settings_sound_group")
    private val halves = kids(group)

    private fun strings(dir: String): Map<String, String> {
        val root = xml("res/$dir/strings.xml")
        val all = root.getElementsByTagName("string")
        return (0 until all.length).map { all.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    /** The labels in both languages, in the halves' order. */
    private val labels = listOf("values", "values-nb").map { dir ->
        val s = strings(dir)
        listOf(s.getValue("kid_settings_sound_on"), s.getValue("kid_settings_sound_vibrate"))
    }

    @Test
    fun `two equal halves, at least a touch target, in the order of SOUND_SEGMENTS`() {
        assertEquals("RadioGroup", group.tagName)
        assertEquals("horizontal", group.attr("orientation"))
        assertEquals(listOf("RadioButton", "RadioButton"), halves.map { it.tagName })
        assertEquals(listOf("@+id/kid_settings_sound_on", "@+id/kid_settings_sound_vibrate"), halves.map { it.attr("id") })
        assertEquals(listOf(SoundChoice.SOUND, SoundChoice.SILENT_VIBRATE), SOUND_SEGMENTS)
        for (half in halves) {
            assertEquals("0dp", half.attr("layout_width"))
            assertEquals("1", half.attr("layout_weight"))
            // match_parent in a wrap_content row: both halves take the taller one's height.
            assertEquals("match_parent", half.attr("layout_height"))
            assertEquals("", half.attr("layout_marginStart"))
            assertEquals("", half.attr("layout_marginEnd"))
            assertEquals(SEGMENT_MIN_HEIGHT_DP, half.dp("minHeight"), 0f)
            assertEquals(SEGMENT_TEXT_SP, half.dp("textSize"), 0f)
            assertEquals(SEGMENT_PADDING_H_DP, half.dp("paddingStart"), 0f)
            assertEquals(SEGMENT_PADDING_H_DP, half.dp("paddingEnd"), 0f)
            assertEquals(SEGMENT_PADDING_V_DP, half.dp("paddingTop"), 0f)
            assertEquals(SEGMENT_PADDING_V_DP, half.dp("paddingBottom"), 0f)
            // Labels wrap and the control grows: never a line limit or an ellipsis.
            for (limit in listOf("maxLines", "lines", "singleLine", "ellipsize", "maxHeight", "autoSizeTextType")) {
                assertEquals("$limit on ${half.attr("id")}", "", half.attr(limit))
            }
            assertEquals("@null", half.attr("button"))
            // At 2.0 on 320 dp "(vibrerer)" is wider than a line: hyphenate, don't cut (N1).
            assertEquals("normal", half.attr("hyphenationFrequency"))
        }
        assertEquals(SEGMENT_TRACK_PADDING_DP, group.dp("padding"), 0f)
        assertEquals("@drawable/bg_kid_segment_track", group.attr("background"))
        assertTrue(SEGMENT_MIN_HEIGHT_DP >= 48f)
    }

    @Test
    fun `the track is white 8 percent with the mockup's radius`() {
        val shape = xml("res/drawable/bg_kid_segment_track.xml")
        val solid = shape.getElementsByTagName("solid").item(0) as Element
        assertEquals(SEGMENT_TRACK_ARGB, solid.attr("color").removePrefix("#").toLong(16).toInt())
        assertEquals(0x14, (SEGMENT_TRACK_ARGB ushr 24) and 0xFF)
        val corners = shape.getElementsByTagName("corners").item(0) as Element
        assertEquals(SEGMENT_TRACK_RADIUS_DP, corners.dp("radius"), 0f)
        // The half's radius is the track's less its padding, so the chosen half sits concentric.
        assertEquals(SEGMENT_TRACK_RADIUS_DP - SEGMENT_TRACK_PADDING_DP, SEGMENT_RADIUS_DP, 0f)
    }

    /** The row's content on a [screenDp] wide phone: the page's 16 dp and the row's 14 dp each side. */
    private fun rowContentDp(screenDp: Float): Float {
        val row = group.parentNode as Element
        assertEquals(14f, row.dp("paddingStart"), 0f)
        assertEquals(14f, row.dp("paddingEnd"), 0f)
        return screenDp - 2 * 16f - row.dp("paddingStart") - row.dp("paddingEnd")
    }

    @Test
    fun `at font scale 1_0, 1_3 and 2_0 the labels wrap and the halves grow, never clipped`() {
        for (screen in listOf(320f, 360f, 411f)) {
            val content = rowContentDp(screen)
            for (pair in labels) {
                var lastHeight = 0f
                for (scale in listOf(1.0f, 1.3f, 2.0f)) {
                    val l = segmentLayout(content, pair, scale)
                    // Even: both halves the same width, the track's padding off both ends.
                    assertEquals(content, l.segmentWidthDp * 2 + 2 * SEGMENT_TRACK_PADDING_DP, 0.01f)
                    assertTrue(l.segmentHeightDp >= SEGMENT_MIN_HEIGHT_DP)
                    // Every label's lines fit inside the half's height, with its padding.
                    for (lines in l.lines) {
                        assertTrue(lines >= 1)
                        val needed = lines * SEGMENT_TEXT_SP * scale * 1.4f + 2 * SEGMENT_PADDING_V_DP
                        assertTrue("$pair at $scale on $screen dp: $needed > ${l.segmentHeightDp}", needed <= l.segmentHeightDp + 0.01f)
                    }
                    // The control only ever grows with the scale.
                    assertTrue(l.segmentHeightDp >= lastHeight)
                    lastHeight = l.segmentHeightDp
                    assertEquals(l.segmentHeightDp + 2 * SEGMENT_TRACK_PADDING_DP, l.trackHeightDp, 0f)
                }
            }
        }
        // On the Jelly Star at 2.0 the long label wraps (an upper bound: the model's 0.6 em is
        // generous) and the control grows past the minimum instead of clipping.
        val star = segmentLayout(rowContentDp(320f), labels[1], 2.0f)
        assertTrue(star.lines[1] >= 2)
        assertTrue(star.segmentHeightDp > SEGMENT_MIN_HEIGHT_DP)
        // At 1.0 "Lyd" is one line and the half is the 48 dp minimum where "Lydløs (vibrerer)" fits on one.
        val wide = segmentLayout(rowContentDp(411f), labels[1], 1.0f)
        assertEquals(listOf(1, 1), wide.lines)
        assertEquals(SEGMENT_MIN_HEIGHT_DP, wide.segmentHeightDp, 0f)
    }

    @Test
    fun `a word longer than a line breaks inside it`() {
        // 20 characters in a half that holds 10 a line: two lines, as a TextView breaks it.
        val l = segmentLayout(2 * (10 * 0.6f * SEGMENT_TEXT_SP + 2 * SEGMENT_PADDING_H_DP) + 2 * SEGMENT_TRACK_PADDING_DP + 0.5f, listOf("a", "x".repeat(20)), 1.0f)
        assertEquals(listOf(1, 2), l.lines)
        // Exactly two full lines, then a word: it starts a third line.
        val full = segmentLayout(2 * (10 * 0.6f * SEGMENT_TEXT_SP + 2 * SEGMENT_PADDING_H_DP) + 2 * SEGMENT_TRACK_PADDING_DP + 0.5f, listOf("a", "x".repeat(20) + " ab"), 1.0f)
        assertEquals(listOf(1, 3), full.lines)
    }

    @Test
    fun `the checked half is the phone's ringer mode, and a tap sets the other`() {
        // The checked half is `state.selected` and both halves take `state.enabled` (renderSound);
        // SoundModeTest pins the state. Here: the halves' order and design 18's taps, unchanged.
        val sound = soundRowState(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true)!!
        val vibrate = soundRowState(RINGER_MODE_VIBRATE, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true)!!
        val silent = soundRowState(RINGER_MODE_SILENT, INTERRUPTION_FILTER_ALL, hasVibrator = true, policyAccess = true)!!
        val dnd = soundRowState(RINGER_MODE_VIBRATE, INTERRUPTION_FILTER_PRIORITY, hasVibrator = true, policyAccess = true)!!
        assertEquals(SoundChoice.SOUND, sound.selected)
        assertEquals(SoundChoice.SILENT_VIBRATE, vibrate.selected)
        assertNull(silent.selected)
        assertTrue(silent.enabled)
        assertEquals(SoundChoice.SILENT_VIBRATE, dnd.selected)
        assertFalse(dnd.enabled)
        // A tap on the unchecked half sets its mode, never Silent; the checked half does nothing.
        assertNull(soundTapMode(sound, SOUND_SEGMENTS[0]))
        assertEquals(RINGER_MODE_VIBRATE, soundTapMode(sound, SOUND_SEGMENTS[1]))
        assertEquals(RINGER_MODE_NORMAL, soundTapMode(vibrate, SOUND_SEGMENTS[0]))
        assertNull(soundTapMode(vibrate, SOUND_SEGMENTS[1]))
        assertEquals(RINGER_MODE_NORMAL, soundTapMode(silent, SOUND_SEGMENTS[0]))
        assertEquals(RINGER_MODE_VIBRATE, soundTapMode(silent, SOUND_SEGMENTS[1]))
        for (choice in SOUND_SEGMENTS) assertNull(soundTapMode(dnd, choice))
    }

    private val activity = file("java/com/kidslauncher/mdm/ui/kidsettings/KidSettingsActivity.kt").readText()

    @Test
    fun `the activity maps each half to its choice and taps go to QuickControls only`() {
        val pairs = Regex("""binding\.(\w+) to SoundChoice\.(\w+)""").findAll(activity).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("kidSettingsSoundOn" to "SOUND", "kidSettingsSoundVibrate" to "SILENT_VIBRATE"), pairs)
        assertTrue(activity.contains("R.id.kid_settings_sound_on") && activity.contains("SoundChoice.SOUND -> binding.kidSettingsSoundGroup.check(R.id.kid_settings_sound_on)"))
        assertTrue(activity.contains("SoundChoice.SILENT_VIBRATE -> binding.kidSettingsSoundGroup.check(R.id.kid_settings_sound_vibrate)"))
        assertTrue(activity.contains("QuickControls.setSound(this, choice)"))
        assertTrue(activity.contains("when (state.selected) {"))
        assertTrue(activity.contains("button.isEnabled = state.enabled"))
    }

    @Test
    fun `TalkBack reads two radio buttons in a named group with the selected state`() {
        // RadioGroup gives the collection (single selection, two items); RadioButton the role
        // and its checked state as "selected". Nothing may replace that role or hide a half.
        assertEquals("@string/kid_settings_sound", group.attr("contentDescription"))
        for (half in halves) {
            assertEquals("", half.attr("importantForAccessibility"))
            assertEquals("", half.attr("contentDescription"))
            assertTrue(half.attr("text").startsWith("@string/kid_settings_sound_"))
        }
        // The visible label is the group's name: read once, not twice.
        assertEquals("no", byId("kid_settings_sound_label").attr("importantForAccessibility"))
        // Nowhere in the activity may a delegate, a role or a class name be set (qa-34-s10-code L2:
        // `button.accessibilityDelegate = ButtonRole` in styleSegment survived the line filter).
        for (banned in listOf("accessibilityDelegate", "AccessibilityDelegate", "ButtonRole", "accessibilityClassName", "AccessibilityClassName", "AccessibilityNodeInfo")) {
            assertFalse(banned, activity.contains(banned))
        }
    }

    // Colours, composited as Android draws them (sRGB values, 8-bit).
    private fun over(argb: Int, ground: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        fun ch(shift: Int) = (a * ((argb shr shift) and 0xFF) + (1 - a) * ((ground shr shift) and 0xFF)).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun colour(name: String): Int {
        val text = file("res/values/colors_kid.xml").readText()
        val hex = Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""").find(text)!!.groupValues[1]
        return (0xFF shl 24) or hex.toInt(16)
    }

    private val card: Int by lazy {
        val shape = xml("res/drawable/bg_kid_card.xml")
        val solid = shape.getElementsByTagName("solid").item(0) as Element
        solid.attr("color").removePrefix("#").toLong(16).toInt()
    }

    @Test
    fun `the chosen half is the accent with night text, at least 4_5 to 1`() {
        val ratio = contrastRatio(relativeLuminance(colour("kid_ground")), relativeLuminance(colour("kid_accent")))
        assertTrue("$ratio", ratio >= 4.5)
        assertEquals(9.87, ratio, 0.01)
        for (ink in listOf(InkChoice(INK_LIGHT, 0.0, false), InkChoice(INK_DARK, 0.0, false))) {
            val c = segmentColours(ink, colour("kid_accent"), colour("kid_ground"))
            assertEquals(colour("kid_accent"), c.checkedFill)
            assertEquals(colour("kid_ground"), c.checkedText)
            assertEquals(ink.ink, c.uncheckedText)
        }
        // The activity applies them to the checked state, and the checked items come first
        // (a StateListDrawable and a ColorStateList take the first match).
        val style = activity.substringAfter("private fun styleSegment(").substringBefore("\n    }\n")
        assertTrue(style.contains("segmentColours(ink, getColor(R.color.kid_accent), getColor(R.color.kid_ground))"))
        assertTrue(style.contains("val checked = intArrayOf(android.R.attr.state_checked)"))
        assertTrue(style.contains("ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(colours.checkedText, colours.uncheckedText))"))
        val fills = style.substringAfter("val fills = StateListDrawable()")
        val checkedAt = fills.indexOf("addState(checked,")
        val defaultAt = fills.indexOf("addState(intArrayOf(),")
        assertTrue(checkedAt in 0 until defaultAt)
        assertTrue(fills.substring(checkedAt, defaultAt).contains("setColor(colours.checkedFill)"))
        assertFalse(style.contains("state_selected"))
        assertFalse(style.contains("state_activated"))
    }

    @Test
    fun `on a light ground the chosen half is outlined in the ink, at least 3 to 1`() {
        val accent = colour("kid_accent")
        val night = colour("kid_ground")
        for (ground in listOf(0xFFFFFFFF.toInt(), 0xFFFFF3B0.toInt(), 0xFFF4F1EA.toInt())) {
            val ink = inkForColours(listOf(ground))
            assertTrue(ink.darkInk)
            val c = segmentColours(ink, accent, night)
            val track = over(SEGMENT_TRACK_ARGB, over(card, over(ink.scrimArgb, ground)))
            // The fill alone is under 3:1 against the track; the outline carries the state.
            assertTrue(contrastRatio(relativeLuminance(accent), relativeLuminance(track)) < 3.0)
            val stroke = c.checkedStroke!!
            assertEquals(INK_DARK, stroke)
            assertTrue(contrastRatio(relativeLuminance(stroke), relativeLuminance(track)) >= 3.0)
            assertTrue(contrastRatio(relativeLuminance(stroke), relativeLuminance(accent)) >= 3.0)
        }
        // On the night the fill is ~9:1 to the track and needs no outline.
        val dark = segmentColours(inkForColours(listOf(night)), accent, night)
        assertNull(dark.checkedStroke)
        val nightTrack = over(SEGMENT_TRACK_ARGB, over(card, night))
        assertTrue(contrastRatio(relativeLuminance(accent), relativeLuminance(nightTrack)) >= 3.0)
        val style = activity.substringAfter("private fun styleSegment(").substringBefore("\n    }\n")
        assertTrue(style.contains("colours.checkedStroke?.let { setStroke(KidAvatars.dp(this@KidSettingsActivity, SEGMENT_STROKE_DP), it) }"))
        assertEquals(1f, SEGMENT_STROKE_DP, 0f)
    }

    @Test
    fun `a focused half shows a 2 dp ring that stands out`() {
        val accent = colour("kid_accent")
        val night = colour("kid_ground")
        for (ground in listOf(night, 0xFFFFFFFF.toInt())) {
            val ink = inkForColours(listOf(ground))
            val c = segmentColours(ink, accent, night)
            val track = over(SEGMENT_TRACK_ARGB, over(card, over(ink.scrimArgb, ground)))
            // On the peach half and on the track, >= 3:1.
            assertTrue(contrastRatio(relativeLuminance(c.focusRingChecked), relativeLuminance(accent)) >= 3.0)
            assertTrue(contrastRatio(relativeLuminance(c.focusRingUnchecked), relativeLuminance(track)) >= 3.0)
        }
        val style = activity.substringAfter("private fun styleSegment(").substringBefore("\n    }\n")
        val fg = style.substringAfter("button.foreground = StateListDrawable()")
        val both = fg.indexOf("addState(focusedChecked,")
        val focused = fg.indexOf("addState(focused,")
        assertTrue(both in 0 until focused)
        assertTrue(style.contains("val focusedChecked = intArrayOf(android.R.attr.state_focused, android.R.attr.state_checked)"))
        assertTrue(style.contains("val focused = intArrayOf(android.R.attr.state_focused)"))
        assertEquals(2f, SEGMENT_FOCUS_RING_DP, 0f)
    }

    @Test
    fun `the other half's ink reaches 4_5 to 1 on the track over every built-in wallpaper`() {
        // The server's built-ins (WallpaperInkTest's list) and two light grounds (dark ink, which
        // the white track only lightens further). The text sits on wallpaper + scrim + the card
        // (white 8 %) + the track (white 8 %), at the worst stop for its ink.
        val grounds = listOf(
            listOf(0xFF0C0C14), listOf(0xFF14213D), listOf(0xFF1E4D3A), listOf(0xFF4A2545),
            listOf(0xFF2A4A4F, 0xFF101820), listOf(0xFF34406A, 0xFF14141F), listOf(0xFF5A3A4A, 0xFF1A1420),
            listOf(0xFFFFFFFF), listOf(0xFFFFF3B0),
        ).map { stops -> stops.map { it.toInt() } }
        // Since qa-34-s10-code M1 the shared ink rule judges white ink on card + track, so mid-tone
        // grounds a parent may choose get there too (a light scrim), not only the built-ins.
        val midTones = listOf(listOf(0xFF1C7ED6), listOf(0xFF7048E8), listOf(0xFFC92A2A), listOf(0xFF777777), listOf(0xFFF76707, 0xFF862E9C))
            .map { stops -> stops.map { it.toInt() } }
        for (stops in midTones) {
            val ink = inkForColours(stops)
            assertFalse(ink.labelShadow)
            val worst = if (ink.darkInk) stops.minBy(::relativeLuminance) else stops.maxBy(::relativeLuminance)
            val under = over(SEGMENT_TRACK_ARGB, over(card, over(ink.scrimArgb, worst)))
            val inkLum = if (ink.darkInk) relativeLuminance(INK_DARK) else 1.0
            val ratio = contrastRatio(inkLum, relativeLuminance(under))
            assertTrue("${stops.map { Integer.toHexString(it) }}: $ratio", ratio >= 4.5)
        }
        var lowest = Double.MAX_VALUE
        for (stops in grounds) {
            val ink = inkForColours(stops)
            assertFalse(ink.labelShadow)
            val worst = if (ink.darkInk) stops.minBy(::relativeLuminance) else stops.maxBy(::relativeLuminance)
            val under = over(SEGMENT_TRACK_ARGB, over(card, over(ink.scrimArgb, worst)))
            val inkLum = if (ink.darkInk) relativeLuminance(INK_DARK) else 1.0
            val ratio = contrastRatio(inkLum, relativeLuminance(under))
            assertTrue("${stops.map { Integer.toHexString(it) }}: $ratio", ratio >= 4.5)
            lowest = minOf(lowest, ratio)
        }
        // Fjord's lighter stop is the lowest, still well above (6.2:1).
        assertTrue("$lowest", lowest >= 6.0)
    }

    @Test
    fun `the card's manage links take the wallpaper's ink, not peach`() {
        val renderInk = activity.substringAfter("private fun renderInk(").substringBefore("\n    }\n")
        assertTrue(renderInk.contains("binding.kidSettingsWifiManage"))
        assertTrue(renderInk.contains("binding.kidSettingsBluetoothManage"))
        assertTrue(renderInk.contains("KidInk.label(view, ink)"))
    }
}
