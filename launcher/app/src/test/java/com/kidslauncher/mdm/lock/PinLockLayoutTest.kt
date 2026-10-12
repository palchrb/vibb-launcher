package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Emulator run 2026-10-06: the PIN keypad must fit 480x854 px at 240 dpi (320x569 dp) with all
 * of 1-9, 0 and backspace at 48 dp or more, and Emergency call and "Parent code" visible. The
 * fixed parts' heights come from activity_pin_lock.xml (text at 1.4 x its size - Nunito's line
 * is about 1.36 em without font padding), so a layout change that breaks the budget fails here.
 * Since design 25 the keypad stage has no clock or date (the glance page has them), the back arrow
 * is always there and the prompt takes two lines between its columns; since 25b-G (user decision
 * 2026-10-12) it has no music row either - the keypad stage is plain, the music is on the glance page.
 */
class PinLockLayoutTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private val root: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("res/layout/activity_pin_lock.xml")).documentElement

    private fun Element.dim(name: String): Float =
        getAttributeNS(ns, name).removeSuffix("dp").removeSuffix("sp").toFloatOrNull() ?: 0f

    private fun children(): List<Element> =
        (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()

    private fun id(e: Element) = e.getAttributeNS(ns, "id").substringAfter("/")

    /** Height of everything but the keypad (dp), margins and the root's padding included. */
    private fun fixedHeightDp(): Float {
        var total = root.dim("paddingTop") + root.dim("paddingBottom")
        for (child in children().filter { it.getAttributeNS(ns, "visibility") != "gone" }) {
            total += child.dim("layout_marginTop") + child.dim("layout_marginBottom")
            if (id(child) == "pin_keypad") continue
            val explicit = child.getAttributeNS(ns, "layout_height").takeIf { it.endsWith("dp") }
            val text = child.dim("textSize") * 1.4f
            // The prompt always counts two lines (it wraps between the arrow's columns, QA Low 5).
            val inner = (0 until child.childNodes.length).map { child.childNodes.item(it) }.filterIsInstance<Element>()
                .maxOfOrNull { maxOf(it.dim("textSize") * 1.4f * (if (id(it) == "pin_prompt") 2 else 1), it.dim("layout_height")) } ?: 0f
            total += explicit?.removeSuffix("dp")?.toFloat() ?: maxOf(text, inner, child.dim("minHeight"))
        }
        return total
    }

    private fun keySizeDp(screenHeightDp: Float, screenWidthDp: Float, statusBarDp: Float, navBarDp: Float): Float {
        val keypad = screenHeightDp - statusBarDp - navBarDp - fixedHeightDp()
        val width = screenWidthDp - root.dim("paddingStart") - root.dim("paddingEnd")
        return PinKeypadLayout.keySizePx(keypad.toInt(), width.toInt(), 1f).toFloat()
    }

    @Test
    fun `the keypad fits 320x569 dp with keys of at least 56 dp - no music row any more`() {
        // Gesture navigation (24 dp) and 3-button navigation (48 dp) - qa-fixround-2026-10-06 #4.
        // Design 21 step 4 QA #11 kept >= 52 dp with the compact row; without it (25b-G) the keys
        // get its 64 dp back.
        for (navBar in listOf(24f, 48f)) {
            val size = keySizeDp(569f, 320f, statusBarDp = 48f, navBarDp = navBar)
            assertTrue("nav $navBar: key size $size dp", size >= 56f)
            assertTrue(PinKeypadLayout.fits(size.toInt(), 1f))
            assertTrue(size <= PinKeypadLayout.MAX_DP)
        }
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
        val prompt = (0 until root.getElementsByTagName("TextView").length).map { root.getElementsByTagName("TextView").item(it) as Element }
            .single { id(it) == "pin_prompt" }
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
