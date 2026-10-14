package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/**
 * Emulator run 2026-10-06: the PIN keypad must fit 480x854 px at 240 dpi (320x569 dp) with all
 * of 1-9, 0 and backspace at 48 dp or more, and Emergency call and "Parent code" visible. The
 * fixed parts' heights come from activity_pin_lock.xml (text at 1.4 x its size - Nunito's line
 * is about 1.36 em without font padding), so a layout change that breaks the budget fails here.
 * Since design 25 the keypad stage has no clock or date (the glance page has them), the back arrow
 * is always there and the prompt takes two lines between its columns; since 25b-G (user decision
 * 2026-10-12) it has no music row either - the keypad stage is plain, the music is on the glance page.
 * Design 34 P7 (QA M6, N1): the text is modelled at font scale 1.0, 1.3 and 2.0 - keys >= 56 dp at 1.0,
 * >= 48 dp at 1.3, at 2.0 down to the 32 dp floor but never overlapping; Emergency call >= 48 dp and
 * >= 16 dp below the keypad, Parent code >= 44 dp.
 */
class PinLockLayoutTest {
    private companion object {
        /** Width of a character in em - a generous average for Nunito's bold weights. */
        const val CHAR_EM = 0.6f
        const val STATUS_BAR_DP = 48f
    }

    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private val root: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("res/layout/activity_pin_lock.xml")).documentElement

    private fun Element.dim(name: String): Float =
        getAttributeNS(ns, name).removeSuffix("dp").removeSuffix("sp").toFloatOrNull() ?: 0f

    private fun kids(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun children(): List<Element> = kids(root)

    private fun id(e: Element) = e.getAttributeNS(ns, "id").substringAfter("/")

    private fun byId(name: String): Element = root.getElementsByTagName("*").let { all ->
        (0 until all.length).map { all.item(it) as Element }.single { id(it) == name }
    }

    /** The longest translation (nb, en) of a `@string/` text, in characters. */
    private fun longestText(e: Element): Int {
        val name = e.getAttributeNS(ns, "text").removePrefix("@string/")
        return listOf("res/values/strings.xml", "res/values-nb/strings.xml").maxOf { path ->
            Regex("<string name=\"$name\">([^<]*)</string>").find(file(path).readText())?.groupValues?.get(1)?.length ?: 0
        }
    }

    /**
     * Lines of a text at [scale] in [widthDp]: [CHAR_EM] em a character (Nunito is narrower); the
     * prompt always two (it wraps between the arrow's columns, QA Low 5).
     */
    private fun lines(e: Element, scale: Float, widthDp: Float): Int {
        if (id(e) == "pin_prompt") return 2
        val wide = longestText(e) * CHAR_EM * e.dim("textSize") * scale
        return maxOf(1, ceil(wide / widthDp).toInt())
    }

    /**
     * An element's height (dp) at font scale [scale] in [widthDp], margins not included. Text grows
     * linearly with the scale (Android 14's non-linear scale grows large text less, so this is the
     * upper bound): `max(minHeight, lines x sp x scale x 1.4 + padding)` (QA M6).
     */
    private fun height(e: Element, scale: Float, widthDp: Float): Float {
        e.getAttributeNS(ns, "layout_height").takeIf { it.endsWith("dp") }?.let { return it.removeSuffix("dp").toFloat() }
        val padding = e.dim("paddingTop") + e.dim("paddingBottom")
        val inner = widthDp - e.dim("paddingStart") - e.dim("paddingEnd")
        val content = when (e.tagName) {
            "TextView" -> lines(e, scale, inner) * e.dim("textSize") * scale * 1.4f
            "LinearLayout" -> if (e.getAttributeNS(ns, "orientation") == "horizontal") {
                // A wrapping child gets what its fixed-width siblings and the margins leave.
                val fixed = kids(e).sumOf { (it.dim("layout_marginStart") + it.dim("layout_marginEnd") + it.dim("layout_width")).toDouble() }
                kids(e).maxOfOrNull { height(it, scale, inner - fixed.toFloat()) } ?: 0f
            } else {
                kids(e).sumOf { (it.dim("layout_marginTop") + it.dim("layout_marginBottom") + height(it, scale, inner)).toDouble() }.toFloat()
            }
            "FrameLayout" -> kids(e).maxOfOrNull {
                height(it, scale, inner - it.dim("layout_marginStart") - it.dim("layout_marginEnd"))
            } ?: 0f
            else -> 0f
        }
        return maxOf(padding + content, e.dim("minHeight"))
    }

    private fun contentWidthDp(screenWidthDp: Float) = screenWidthDp - root.dim("paddingStart") - root.dim("paddingEnd")

    /** Height of everything but the keypad (dp) at [scale], margins and the root's padding included. */
    private fun fixedHeightDp(scale: Float = 1f, screenWidthDp: Float = 320f): Float {
        var total = root.dim("paddingTop") + root.dim("paddingBottom")
        for (child in children()) {
            total += child.dim("layout_marginTop") + child.dim("layout_marginBottom")
            if (id(child) != "pin_keypad") total += height(child, scale, contentWidthDp(screenWidthDp))
        }
        return total
    }

    private fun keypadHeightDp(screenHeightDp: Float, screenWidthDp: Float, statusBarDp: Float, navBarDp: Float, scale: Float) =
        screenHeightDp - statusBarDp - navBarDp - fixedHeightDp(scale, screenWidthDp)

    private fun keySizeDp(screenHeightDp: Float, screenWidthDp: Float, statusBarDp: Float, navBarDp: Float, scale: Float = 1f): Float {
        val keypad = keypadHeightDp(screenHeightDp, screenWidthDp, statusBarDp, navBarDp, scale)
        return PinKeypadLayout.keySizePx(keypad.toInt(), contentWidthDp(screenWidthDp).toInt(), 1f).toFloat()
    }

    /** The keys at [scale] on the Jelly Star (320x569 dp) with gesture (24 dp) and 3-button (48 dp) navigation. */
    private fun jellyStarKeys(scale: Float): List<Float> = listOf(24f, 48f).map { nav ->
        val size = keySizeDp(569f, 320f, STATUS_BAR_DP, nav, scale)
        // Never an overlap: four rows of these keys and their gaps fit in the keypad's height.
        val keypad = keypadHeightDp(569f, 320f, STATUS_BAR_DP, nav, scale)
        assertTrue("scale $scale nav $nav: $size dp keys overflow $keypad dp", 4 * size + 3 * PinKeypadLayout.GAP_DP <= keypad)
        assertTrue("scale $scale nav $nav: $size dp is below the floor", size >= PinKeypadLayout.FLOOR_DP)
        assertEquals("the floor never has to step in", size.toInt(), PinKeypadLayout.finalKeySizePx(size.toInt(), 1f))
        size
    }

    @Test
    fun `the keypad fits 320x569 dp with keys of at least 56 dp at font scale 1_0`() {
        // Gesture navigation (24 dp) and 3-button navigation (48 dp) - qa-fixround-2026-10-06 #4.
        // Design 21 step 4 QA #11 kept >= 52 dp with the compact row; without it (25b-G) the keys
        // got its 64 dp back. Design 34 P7's 16 dp gap and the taller button and link cost 14 dp:
        // the 56 dp floor holds (QA N1; measured 65 dp with 3-button navigation, 71 with gestures).
        val sizes = jellyStarKeys(1f)
        assertEquals(listOf(71f, 65f), sizes)
        for (size in sizes) {
            assertTrue("key size $size dp", size >= 56f)
            assertTrue(PinKeypadLayout.fits(size.toInt(), 1f))
            assertTrue(size <= PinKeypadLayout.MAX_DP)
        }
    }

    @Test
    fun `at font scale 1_3 the keys stay at least 48 dp (QA M6, N1)`() {
        for (size in jellyStarKeys(1.3f)) {
            assertTrue("key size $size dp", size >= 48f)
            assertTrue(PinKeypadLayout.fits(size.toInt(), 1f))
        }
    }

    @Test
    fun `at font scale 2_0 the keys give way - above the floor, never overlapping (QA M6, N1)`() {
        val at1 = jellyStarKeys(1f)
        val at2 = jellyStarKeys(2f)
        // Accepted at Android 14's maximum: keys may go below 48 dp touch size, down to the 32 dp floor.
        for ((big, small) in at1.zip(at2)) assertTrue("the keys give way: $big -> $small", small < big)
        // The text around them grew instead: the prompt, the button (its English label wraps) and the link.
        assertTrue(fixedHeightDp(2f) > fixedHeightDp(1f) + 60f)
        val width = contentWidthDp(320f)
        val emergency = byId("pin_emergency")
        assertTrue("Emergency call grows with the text", height(emergency, 2f, width) > height(emergency, 1f, width))
        assertEquals("the English label takes two lines at 2.0", 2, lines(byId("pin_emergency_label"), 2f, width - 40f - 26f))
    }

    @Test
    fun `one column, nothing overlaid - the spacing rule (design 34 P7)`() {
        assertEquals("LinearLayout", root.tagName)
        assertEquals("vertical", root.getAttributeNS(ns, "orientation"))
        assertEquals(listOf("pin_prompt_row", "pin_dots", "pin_keypad", "pin_emergency", "pin_parent_code"), children().map(::id))
        // >= 16 dp between the keys and Emergency call: the keypad has no bottom margin and the keys
        // never overflow it (jellyStarKeys), so the button's top margin is the least gap there is.
        assertEquals(0f, byId("pin_keypad").dim("layout_marginBottom"))
        assertTrue(byId("pin_emergency").dim("layout_marginTop") >= 16f)
        assertEquals("wrap_content", byId("pin_emergency").getAttributeNS(ns, "layout_height"))
        assertEquals("wrap_content", byId("pin_parent_code").getAttributeNS(ns, "layout_height"))
        assertTrue(byId("pin_emergency").dim("minHeight") >= 48f)
        assertTrue(byId("pin_parent_code").dim("minHeight") >= 44f)
        for (scale in listOf(1f, 1.3f, 2f)) {
            val width = contentWidthDp(320f)
            assertTrue("Emergency call >= 48 dp at $scale", height(byId("pin_emergency"), scale, width) >= 48f)
            assertTrue("Parent code >= 44 dp at $scale", height(byId("pin_parent_code"), scale, width) >= 44f)
        }
        // The label wraps rather than being cut: no line limit, no single line, no ellipsis.
        val label = byId("pin_emergency_label")
        for (attr in listOf("maxLines", "singleLine", "lines", "ellipsize")) assertTrue(attr, label.getAttributeNS(ns, attr).isEmpty())
    }

    @Test
    fun `the emergency button is the tonal pill, its label and glyph the emergency red (design 34 P7)`() {
        val emergency = byId("pin_emergency")
        assertEquals("@drawable/bg_pin_emergency_tonal", emergency.getAttributeNS(ns, "background"))
        assertTrue(file("res/drawable/bg_pin_emergency_tonal.xml").readText().contains("@color/kid_emergency_tonal"))
        assertEquals("@color/kid_emergency_text", byId("pin_emergency_icon").getAttributeNS(ns, "tint"))
        assertEquals("@color/kid_emergency_text", byId("pin_emergency_label").getAttributeNS(ns, "textColor"))
        // The flow is untouched: the same clickable view, which the glance page's link clicks too.
        assertEquals("true", emergency.getAttributeNS(ns, "clickable"))
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("binding.pinEmergency.performClick()"))
        assertTrue(activity.contains("binding.pinEmergency.setOnClickListener"))
    }

    @Test
    fun `the VoIP ring card's Nødsamtale follows P7 too (qa-34-s8-code L2)`() {
        val ring = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file("res/layout/view_voip_ring.xml")).documentElement
        val all = ring.getElementsByTagName("*").let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun ringId(name: String) = all.single { id(it) == name }
        val pill = ringId("voip_ring_emergency")
        assertEquals("@drawable/bg_pin_emergency_tonal", pill.getAttributeNS(ns, "background"))
        assertTrue(pill.dim("minHeight") >= 48f)
        assertEquals("wrap_content", pill.getAttributeNS(ns, "layout_height"))
        assertEquals("@color/kid_emergency_text", ringId("voip_ring_emergency_icon").getAttributeNS(ns, "tint"))
        val label = ringId("voip_ring_emergency_label")
        assertEquals("@color/kid_emergency_text", label.getAttributeNS(ns, "textColor"))
        for (attr in listOf("maxLines", "singleLine", "lines", "ellipsize")) assertTrue(attr, label.getAttributeNS(ns, attr).isEmpty())
        // Same size as the keypad's pill: it grows with the text the same way.
        val width = contentWidthDp(320f)
        for (scale in listOf(1f, 1.3f, 2f)) assertEquals(height(byId("pin_emergency"), scale, width), height(pill, scale, width), 0.01f)
        // Its click path is unchanged.
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("voipRing.voipRingEmergency.setOnClickListener {\n            trackDialog(EmergencyCall.confirm(this) { PinLockRuntime.emergencyFlowStarted() })"))
    }

    @Test
    fun `both Nødsamtale pills announce as a button (qa-34-s8-code L3)`() {
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("binding.pinEmergency.accessibilityDelegate = ButtonRole"))
        assertTrue(activity.contains("voipRing.voipRingEmergency.accessibilityDelegate = ButtonRole"))
        val role = file("java/com/kidslauncher/mdm/lock/ButtonRole.kt").readText()
        assertTrue(role.contains("super.onInitializeAccessibilityNodeInfo(host, info)"))
        assertTrue(role.contains("info.className = Button::class.java.name"))
        // Only the role: no click, action or state is touched.
        assertFalse(role.contains("performAccessibilityAction") || role.contains("addAction") || role.contains("isClickable"))
    }

    @Test
    fun `the keys carry a 1 dp top highlight at 6 percent (design 34 P7, qa-34-s8-code N3)`() {
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("LayerDrawable(arrayOf(getDrawable(R.drawable.bg_pin_key), PinKeyHighlight(resources.displayMetrics.density)))"))
        assertEquals(0x0F, PinKeypadLayout.HIGHLIGHT_ARGB ushr 24)
        assertEquals(0xFFFFFF, PinKeypadLayout.HIGHLIGHT_ARGB and 0xFFFFFF)
        assertEquals(1f, PinKeypadLayout.HIGHLIGHT_STROKE_DP, 0f)
        assertTrue(file("java/com/kidslauncher/mdm/lock/PinKeyHighlight.kt").readText()
            .contains("PinKeypadLayout.HIGHLIGHT_STROKE_DP * density"))
        // The arc is centred on the top of the oval (270 degrees).
        assertEquals(270f, PinKeypadLayout.HIGHLIGHT_START_DEG + PinKeypadLayout.HIGHLIGHT_SWEEP_DEG / 2, 0f)
    }

    @Test
    fun `a digit never outgrows its key, and at 2_0 it is no smaller than at 1_0 (qa-34-s8-code N2)`() {
        // 30 sp on a large key stays 30 sp; 60 sp (font scale 2.0, linear) on a 40 dp key is 28 dp.
        assertEquals(30f, PinKeypadLayout.digitTextPx(65, 30f), 0f)
        assertEquals(28f, PinKeypadLayout.digitTextPx(40, 60f), 0.001f)
        // The line box (1.364 em, no font padding) stays inside the key.
        assertTrue(PinKeypadLayout.DIGIT_SHARE * 1.364f <= 1f)
        // On the Jelly Star: 30 dp at 1.0, and at 2.0 (60 sp linear) the capped digit is still >= 30 dp.
        for (size in jellyStarKeys(1f)) assertEquals(30f, PinKeypadLayout.digitTextPx(size.toInt(), 30f), 0f)
        for (size in jellyStarKeys(2f)) assertTrue("$size dp keys", PinKeypadLayout.digitTextPx(size.toInt(), 60f) >= 30f)
    }

    @Test
    fun `the digit cap applies on every layout, also when the keys keep their first 48 dp (qa-34-s8-code L1)`() {
        val density = 1.5f
        // A keypad that gives exactly the keys' initial size: 4 x 72 px + 3 x 9 px.
        val keypad = 4 * 72 + 3 * 9
        val style = PinKeypadLayout.keyStyle(keypad, 1000, density, scaledDigitPx = 90f)
        assertEquals((PinKeypadLayout.MIN_DP * density).toInt(), style.sizePx)
        assertEquals(72 * PinKeypadLayout.DIGIT_SHARE, style.digitPx, 0.001f)
        // The key keeps its size but still shows the font scale's 90 px digit: stale.
        assertTrue(PinKeypadLayout.keyStale(72, 90f, style))
        // Capped: nothing to do. The backspace (no text) of the right size: nothing to do.
        assertFalse(PinKeypadLayout.keyStale(72, style.digitPx, style))
        assertFalse(PinKeypadLayout.keyStale(72, null, style))
        assertTrue(PinKeypadLayout.keyStale(80, null, style))
        // The floor still holds, and the digit follows the floored key.
        val tiny = PinKeypadLayout.keyStyle(40, 1000, 1f, 60f)
        assertEquals(32, tiny.sizePx)
        assertEquals(32 * PinKeypadLayout.DIGIT_SHARE, tiny.digitPx, 0.001f)
        // The activity applies it: stale keys from keyStale, the digit set on every stale key, outside the size branch.
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("PinKeypadLayout.keyStyle(keypadHeight, binding.pinKeypad.width, density,"))
        assertTrue(activity.contains("keys.filter { PinKeypadLayout.keyStale(it.layoutParams.width, (it as? TextView)?.textSize, style) }"))
        val lines = activity.lines()
        val sizeBranch = lines.single { it.trim() == "if (key.layoutParams.width != style.sizePx) {" }
        val digit = lines.single { it.trim() == "if (key is TextView) key.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.digitPx)" }
        assertEquals("the digit is set beside the size branch, not inside it",
            sizeBranch.takeWhile { it == ' ' }.length, digit.takeWhile { it == ' ' }.length)
        assertTrue(lines.indexOf(digit) > lines.indexOf(sizeBranch))
    }

    @Test
    fun `the keypad stage has no music - the glance page has it (25b-G)`() {
        val layout = file("res/layout/activity_pin_lock.xml").readText()
        assertFalse(layout.contains("pin_media_row"))
        assertFalse(layout.contains("view_lock_media_row"))
        assertFalse(listOf("src/main", "app/src/main").any { File(it, "res/layout/view_lock_media_row.xml").exists() })
        for (path in listOf("java/com/kidslauncher/mdm/music/MusicLockPanel.kt", "java/com/kidslauncher/mdm/lock/GlancePanel.kt",
            "java/com/kidslauncher/mdm/lock/PinLockActivity.kt")) {
            val code = file(path).readText()
            assertFalse(path, code.contains("ViewLockMediaRowBinding") || code.contains("pinMediaRow") || code.contains("lockMediaRow"))
        }
    }

    @Test
    fun `no clock or date on the keypad stage - the glance page has them`() {
        val ids = children().map(::id)
        assertFalse(ids.contains("pin_clock") || ids.contains("pin_date"))
        assertTrue(root.getElementsByTagName("TextClock").length == 0)
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        for (gone in listOf("pinClock", "pinDate", "setMediaKeypad", "compactStep", "mediaKeypad")) {
            assertFalse(gone, activity.contains(gone))
        }
    }

    @Test
    fun `the back arrow is always by the prompt, which keeps two lines between its columns`() {
        val back = root.getElementsByTagName("ImageButton").item(0) as Element
        assertEquals("pin_back", id(back))
        assertEquals("the arrow sits in the prompt's row", "pin_prompt_row", id(back.parentNode as Element))
        assertTrue("always shown", back.getAttributeNS(ns, "visibility").isEmpty())
        assertEquals("48dp", back.getAttributeNS(ns, "layout_width"))
        val prompt = byId("pin_prompt")
        // Static side margins of the arrow's width (qa-21-step4-code L5) and two lines at most (QA Low 5).
        assertEquals("48dp", prompt.getAttributeNS(ns, "layout_marginStart"))
        assertEquals("48dp", prompt.getAttributeNS(ns, "layout_marginEnd"))
        assertEquals("2", prompt.getAttributeNS(ns, "maxLines"))
        assertEquals("end", prompt.getAttributeNS(ns, "ellipsize"))
        val ids = children().map(::id)
        assertEquals("the parent code is the last thing on the keypad stage", "pin_parent_code", ids.last())
    }

    @Test
    fun `keys shrink rather than clip - down to the 32 dp floor`() {
        assertEquals(40, PinKeypadLayout.finalKeySizePx(40, 1f))
        assertEquals(32, PinKeypadLayout.finalKeySizePx(20, 1f))
        assertEquals(60, PinKeypadLayout.finalKeySizePx(60, 1f))
        assertEquals(48, PinKeypadLayout.finalKeySizePx(10, 1.5f))
    }

    @Test
    fun `a roomy screen gets the mockup's 72 dp keys`() {
        assertEquals(PinKeypadLayout.MAX_DP, keySizeDp(800f, 400f, 24f, 24f))
    }

    @Test
    fun `emergency call and parent code sit outside the weighted keypad`() {
        val ids = children().map(::id)
        assertTrue(ids.indexOf("pin_emergency") > ids.indexOf("pin_keypad"))
        assertTrue(ids.indexOf("pin_parent_code") > ids.indexOf("pin_emergency"))
        val weighted = children().filter { it.getAttributeNS(ns, "layout_weight").isNotEmpty() }.map(::id)
        assertEquals(listOf("pin_keypad"), weighted)
        // Nothing starts hidden (the music row went, 25b-G).
        assertEquals(emptyList<String>(), children().filter { it.getAttributeNS(ns, "visibility") == "gone" }.map(::id))
    }

    @Test
    fun `key sizing clamps and reports when the minimum doesn't fit`() {
        // 4 rows of 48 dp and 3 gaps of 6 dp.
        assertEquals(210, PinKeypadLayout.minHeightPx(1f))
        assertTrue(PinKeypadLayout.fits(PinKeypadLayout.keySizePx(210, 300, 1f), 1f))
        assertFalse(PinKeypadLayout.fits(PinKeypadLayout.keySizePx(200, 300, 1f), 1f))
        // Width limits too: 3 columns with 8 dp slack.
        assertEquals(42, PinKeypadLayout.keySizePx(400, 150, 1f))
        // Density scales: 240 dpi = 1.5.
        assertEquals(108, PinKeypadLayout.keySizePx(1000, 1000, 1.5f))
    }
}
