package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fix round 2026-10-06: one header on every full page, at the same place, and no screen adds more
 * than [KidInsets.MAX_TOP_EXTRA_DP] above its content on top of the status-bar inset (which
 * [KidInsets] applies once, instead of `fitsSystemWindows`).
 */
class KidHeaderLayoutTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun parse(name: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(listOf("src/main/res/layout/$name.xml", "app/src/main/res/layout/$name.xml").map(::File).first { it.exists() })
        .documentElement

    private fun Element.dp(attr: String): Float =
        getAttributeNS(ns, attr).removeSuffix("dp").toFloatOrNull() ?: 0f

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.all(): List<Element> = listOf(this) + children().flatMap { it.all() }

    private fun Element.ancestors(): List<Element> =
        generateSequence(parentNode) { it.parentNode }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }.toList()

    private val fullPages = listOf("activity_phone_book", "activity_kid_settings", "activity_wifi_networks", "activity_bluetooth_devices")
    private val allScreens = fullPages + listOf("activity_home", "activity_pin_lock", "activity_lock", "activity_in_call")

    private fun header(page: String): Element =
        parse(page).all().single { it.tagName == "include" && it.getAttribute("layout") == "@layout/include_kid_header" }

    /** Where the header starts inside the window's content area: top and start offsets (dp). */
    private fun headerOffset(page: String): Pair<Float, Float> {
        val include = header(page)
        val chain = include.ancestors()
        val top = chain.sumOf { (it.dp("paddingTop") + it.dp("padding")).toDouble() }.toFloat() + include.dp("layout_marginTop")
        val start = chain.sumOf { (it.dp("paddingStart") + it.dp("padding")).toDouble() }.toFloat() + include.dp("layout_marginStart")
        return top to start
    }

    @Test
    fun `every full page uses the shared header at exactly the same place`() {
        val reference = headerOffset("activity_kid_settings")
        for (page in fullPages) assertEquals(page, reference, headerOffset(page))
        assertEquals(4f to 16f, reference)
    }

    @Test
    fun `the header is a compact row with a 48 dp back button and a centred title`() {
        val root = parse("include_kid_header")
        assertEquals("56dp", root.getAttributeNS(ns, "layout_height"))
        assertEquals("center_vertical", root.getAttributeNS(ns, "gravity"))
        val back = root.all().single { it.getAttributeNS(ns, "id") == "@+id/kid_header_back" }
        assertEquals("48dp", back.getAttributeNS(ns, "layout_width"))
        assertEquals("48dp", back.getAttributeNS(ns, "layout_height"))
    }

    @Test
    fun `no screen pads the top beyond the inset plus 8 dp, and none uses fitsSystemWindows`() {
        for (screen in allScreens) {
            val root = parse(screen)
            assertTrue(screen, root.all().none { it.getAttributeNS(ns, "fitsSystemWindows") == "true" })
            // The root's top padding, then down the first children to the first real content.
            var top = root.dp("paddingTop") + root.dp("padding")
            var node: Element? = root.children().firstOrNull()
            while (node != null) {
                top += node.dp("layout_marginTop")
                val isContainer = node.tagName in setOf("LinearLayout", "FrameLayout", "ScrollView") && node.getAttributeNS(ns, "id").isEmpty()
                if (!isContainer) break
                top += node.dp("paddingTop") + node.dp("padding")
                node = node.children().firstOrNull()
            }
            assertTrue("$screen: $top dp above its first content", top <= KidInsets.MAX_TOP_EXTRA_DP)
        }
    }

    @Test
    fun `content starts right below the header`() {
        for (page in fullPages) {
            val include = header(page)
            val siblings = (include.parentNode as Element).children()
            val next = siblings.getOrNull(siblings.indexOf(include) + 1) ?: continue
            assertTrue("$page: ${next.dp("layout_marginTop")} dp", next.dp("layout_marginTop") <= 8f)
        }
    }
}
