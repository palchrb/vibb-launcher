package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Emulator run 2026-10-06: the green call badge on Home contacts was clipped by the avatar. */
class ContactBadgeLayoutTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val root: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(listOf("src/main/res/layout/item_kid_contact.xml", "app/src/main/res/layout/item_kid_contact.xml").map(::File).first { it.exists() })
        .documentElement

    private fun byId(id: String): Element {
        val all = root.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as Element }.first { it.getAttributeNS(ns, "id") == "@+id/$id" }
    }

    @Test
    fun `the badge sits on the frame, inside it, with nothing clipping it`() {
        val badge = byId("contact_call_badge")
        val frame = byId("contact_frame")
        assertEquals(frame, badge.parentNode)
        assertEquals("false", frame.getAttributeNS(ns, "clipChildren"))
        assertEquals("false", root.getAttributeNS(ns, "clipChildren"))
        // No negative margins: the badge stays within the frame's bounds.
        for (margin in listOf("layout_marginEnd", "layout_marginBottom", "layout_marginTop", "layout_marginStart")) {
            val value = badge.getAttributeNS(ns, margin)
            assert(!value.startsWith("-")) { "$margin = $value" }
        }
    }
}
