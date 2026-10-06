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
        for (child in children()) {
            total += child.dim("layout_marginTop") + child.dim("layout_marginBottom")
            if (id(child) == "pin_keypad") continue
            val explicit = child.getAttributeNS(ns, "layout_height").takeIf { it.endsWith("dp") }
            val text = child.dim("textSize") * 1.4f
            val inner = (0 until child.childNodes.length).map { child.childNodes.item(it) }.filterIsInstance<Element>()
                .maxOfOrNull { maxOf(it.dim("textSize") * 1.4f, it.dim("layout_height")) } ?: 0f
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
    fun `the keypad fits 320x569 dp with keys of at least 48 dp and a margin`() {
        // Gesture navigation (24 dp) and 3-button navigation (48 dp) - qa-fixround-2026-10-06 #4.
        for (navBar in listOf(24f, 48f)) {
            val size = keySizeDp(569f, 320f, statusBarDp = 48f, navBarDp = navBar)
            assertTrue("nav $navBar: key size $size dp", size >= 52f)
            assertTrue(PinKeypadLayout.fits(size.toInt(), 1f))
            assertTrue(size <= PinKeypadLayout.MAX_DP)
        }
    }

    @Test
    fun `with every compact step used, keys shrink rather than clip`() {
        // Compact steps left: never below 48 dp (the next step frees room).
        assertEquals(48, PinKeypadLayout.finalKeySizePx(40, 1f, compactExhausted = false))
        // None left: the computed size, so all four rows fit - down to the 32 dp floor.
        assertEquals(40, PinKeypadLayout.finalKeySizePx(40, 1f, compactExhausted = true))
        assertEquals(32, PinKeypadLayout.finalKeySizePx(20, 1f, compactExhausted = true))
        assertEquals(60, PinKeypadLayout.finalKeySizePx(60, 1f, compactExhausted = true))
        assertEquals(72, PinKeypadLayout.finalKeySizePx(48, 1.5f, compactExhausted = false))
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
        assertTrue(children().none { it.getAttributeNS(ns, "visibility") == "gone" })
    }

    @Test
    fun `the clock in the layout matches PinKeypadLayout`() {
        val clock = children().first { id(it) == "pin_clock" }
        assertEquals(PinKeypadLayout.CLOCK_SP, clock.dim("textSize"))
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
