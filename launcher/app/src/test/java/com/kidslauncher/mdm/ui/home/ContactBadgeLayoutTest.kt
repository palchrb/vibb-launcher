package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Emulator run 2026-10-06: the green call badge on Home contacts was clipped by the avatar. Since design 106 the teal
 * unread pill sits in its place, under the same rule. */
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
    fun `the pills sit on the frame, inside it, with nothing clipping them`() {
        val badge = byId("contact_unread_badge")
        val frame = byId("contact_frame")
        assertEquals(frame, badge.parentNode)
        assertEquals("false", frame.getAttributeNS(ns, "clipChildren"))
        assertEquals("false", root.getAttributeNS(ns, "clipChildren"))
        // No negative margins: the badge stays within the frame's bounds.
        for (margin in listOf("layout_marginEnd", "layout_marginBottom", "layout_marginTop", "layout_marginStart")) {
            val value = badge.getAttributeNS(ns, margin)
            assert(!value.startsWith("-")) { "$margin = $value" }
        }
        // Design 106 §2.2: the rust pill at the top end, the teal one at the bottom end; the green call badge is gone.
        assertEquals(frame, byId("contact_badge").parentNode)
        assertEquals("top|end", byId("contact_badge").getAttributeNS(ns, "layout_gravity"))
        assertEquals("bottom|end", badge.getAttributeNS(ns, "layout_gravity"))
        val all = root.getElementsByTagName("*")
        assert((0 until all.length).none { (all.item(it) as Element).getAttributeNS(ns, "id") == "@+id/contact_call_badge" })
    }

    /** Mockup A: the ring is Home's alone - gone in the layout, drawn last, shown by Home, never by the phone book. */
    @Test
    fun `the ring is gone by default, drawn last and only Home shows it`() {
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
        assert(home.contains("bindHomeRing(item.findViewById(R.id.contact_ring), emergency, pill)"))
        assert(home.contains("PressFeedback.HOME_AVATAR_ELEVATION_DP"))
        main.walkTopDown().filter { it.extension == "kt" && it.name != "HomeActivity.kt" && it.name != "KidAvatars.kt" }
            .forEach { assert(!it.readText().contains("contact_ring")) { it.name } }
    }

    /** The name pill (2026-11-19): the avatar, the pill over its bottom edge, then the badges' frame over both - one
     * cell, in that order, so equal Z (PressFeedback.contact) draws them so; the pill's top is 10 dp over the avatar's
     * bottom (6 + 76 - 10), and sizeContact keeps that at either avatar size. */
    @Test
    fun `the name pill lies over the avatar, under the badges`() {
        val cell = byId("contact_cell")
        val children = (0 until cell.childNodes.length).map { cell.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("@+id/contact_avatar", "@+id/contact_name", "@+id/contact_frame"), children.map { it.getAttributeNS(ns, "id") })
        assertEquals("false", cell.getAttributeNS(ns, "clipChildren"))
        assertEquals("${CONTACT_TOP_ROOM_DP}dp", byId("contact_avatar").getAttributeNS(ns, "layout_marginTop"))
        assertEquals("${CONTACT_TOP_ROOM_DP + 76 - NAME_PILL_OVERLAP_DP}dp", byId("contact_name").getAttributeNS(ns, "layout_marginTop"))
        assertEquals("14sp", byId("contact_name").getAttributeNS(ns, "textSize"))
        assertEquals(listOf("3dp", "3dp"), listOf("paddingTop", "paddingBottom").map { byId("contact_name").getAttributeNS(ns, it) })
        val main = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val avatars = File(main, "com/kidslauncher/mdm/ui/home/KidAvatars.kt").readText()
        assert(avatars.contains("dp(context, (CONTACT_TOP_ROOM_DP + avatarDp - NAME_PILL_OVERLAP_DP).toFloat())"))
        val press = File(main, "com/kidslauncher/mdm/ui/home/PressFeedback.kt").readText()
        assert(press.contains("findViewById<View>(R.id.contact_name)?.elevation = avatar.elevation"))
        assert(press.contains("findViewById<View>(R.id.contact_frame)?.apply"))
        val home = File(main, "com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        assert(home.contains("KidAvatars.bindNamePill(this, pill)"))
        // The pill's colours are Android's hint on Home's own ground, from the same state as the ground (code QA M1).
        assert(home.contains("val pill = com.kidslauncher.mdm.ui.home.namePillStyle(shownDarkText)"))
        assert(home.contains("callPartsRestyleDue(shownInk, shownDarkText, state.ink, state.supportsDarkText)"))
        assert(!home.contains("WallpaperManager"))
    }

    /** Code QA L3-L5: the teal badge rests on the pill's top edge, off the name's letters; the focus ring is the cell's
     * background (around the avatar, under the pill), not the item's foreground; 8 dp under the pill for its shadow. */
    @Test
    fun `the badge sits above the pill, the focus ring under it, the shadow has room`() {
        assertEquals("${NAME_PILL_OVERLAP_DP}dp", byId("contact_unread_badge").getAttributeNS(ns, "layout_marginBottom"))
        assertEquals("8dp", root.getAttributeNS(ns, "paddingBottom"))
        val main = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val press = File(main, "com/kidslauncher/mdm/ui/home/PressFeedback.kt").readText()
        val contact = press.substringAfter("fun contact(item: View").substringBefore("\n    }\n")
        assert(contact.contains("item.foreground = null"))
        assert(contact.contains("cell.isDuplicateParentStateEnabled = true"))
        assert(contact.contains("cell.background = focusRing(item.context, avatarPx, KidAvatars.dp(item.context, CONTACT_TOP_ROOM_DP.toFloat()))"))
    }
}
