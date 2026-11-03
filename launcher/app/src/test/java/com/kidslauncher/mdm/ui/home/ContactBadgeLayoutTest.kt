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

    /** Mockup A: the white ring is Home's alone - gone in the layout, shown by Home, never by the phone book. */
    @Test
    fun `the white ring is gone by default and only Home shows it`() {
        val ring = byId("contact_ring")
        assertEquals("gone", ring.getAttributeNS(ns, "visibility"))
        assertEquals(byId("contact_avatar"), ring.parentNode)
        val main = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val home = File(main, "com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        assert(home.contains("KidAvatars.bindHomeRing(item.findViewById(R.id.contact_ring), emergency)"))
        assert(home.contains("PressFeedback.HOME_AVATAR_ELEVATION_DP"))
        main.walkTopDown().filter { it.extension == "kt" && it.name != "HomeActivity.kt" && it.name != "KidAvatars.kt" }
            .forEach { assert(!it.readText().contains("bindHomeRing")) { it.name } }
        // An emergency avatar keeps its own red ring: the white one is hidden for it.
        val avatars = File(main, "com/kidslauncher/mdm/ui/home/KidAvatars.kt").readText()
        assert(avatars.contains("ring.visibility = if (isEmergency) View.GONE else View.VISIBLE"))
    }
}
