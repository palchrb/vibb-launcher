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

    /** Mockup A: the white ring is Home's alone - gone in the layout, drawn last, shown by Home, never by the phone book. */
    @Test
    fun `the white ring is gone by default, drawn last and only Home shows it`() {
        val ring = byId("contact_ring")
        assertEquals("gone", ring.getAttributeNS(ns, "visibility"))
        val avatar = byId("contact_avatar")
        assertEquals(avatar, ring.parentNode)
        // Last child: it draws over the photo and the initial (and the avatar lets the light-ground hairline overhang).
        val children = (0 until avatar.childNodes.length).map { avatar.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(ring, children.last())
        assertEquals("false", avatar.getAttributeNS(ns, "clipChildren"))
        val main = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val home = File(main, "com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        assert(home.contains("bindHomeRing(item.findViewById(R.id.contact_ring), emergency, shownInk)"))
        assert(home.contains("PressFeedback.HOME_AVATAR_ELEVATION_DP"))
        main.walkTopDown().filter { it.extension == "kt" && it.name != "HomeActivity.kt" && it.name != "KidAvatars.kt" }
            .forEach { assert(!it.readText().contains("contact_ring")) { it.name } }
    }

    /** A light wallpaper (dark ink) gives the white ring its dark hairline; a dark one and an emergency avatar do not. */
    @Test
    fun `the ring's hairline is for a light wallpaper only`() {
        assert(homeRingHairline(darkInk = true, isEmergency = false))
        assert(!homeRingHairline(darkInk = false, isEmergency = false))
        assert(!homeRingHairline(darkInk = true, isEmergency = true))
    }
}
