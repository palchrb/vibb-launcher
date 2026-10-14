package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.ui.home.EMERGENCY_AVATAR
import com.kidslauncher.mdm.ui.home.TONE_SLATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/** The Vibb night palette (fix round 2026-10-06): text colours on the ground stay >= 4.5:1. */
class KidPaletteTest {
    private fun resFile(path: String): File =
        listOf("src/main/res/$path", "app/src/main/res/$path").map(::File).first { it.exists() }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(resFile(path))

    private val colours: Map<String, String> = run {
        val nodes = parse("values/colors_kid.xml").getElementsByTagName("color")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    /** A colour resource's name, an `@color/` reference or a literal `#...` to its `#[AA]RRGGBB`. */
    private fun resolve(name: String): String {
        if (name.startsWith("#")) return name
        val value = colours.getValue(name.removePrefix("@color/"))
        return if (value.startsWith("@color/")) resolve(value) else value
    }

    private fun alphaOf(hex: String): Double {
        val h = hex.removePrefix("#")
        return if (h.length == 8) h.substring(0, 2).toInt(16) / 255.0 else 1.0
    }

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").takeLast(6)
        return listOf(0, 2, 4).map { rgb.substring(it, it + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            .let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
    }

    /**
     * WCAG contrast of [top] as painted over [under]: a translucent [top] is composited first
     * (qa-34-s3-code N2, it used to be measured as if opaque); [under] must be opaque - composite
     * a translucent ground with [over] first.
     */
    private fun contrast(top: String, under: String): Double {
        val u = resolve(under)
        assertEquals("$under must be opaque here", 1.0, alphaOf(u), 0.0)
        val (l1, l2) = listOf(luminance(over(top, u)), luminance(u)).sortedDescending()
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** The colour [top] (#AARRGGBB or #RRGGBB) shows painted over the opaque [under], as `#RRGGBB`. */
    private fun over(top: String, under: String): String {
        val t = resolve(top)
        val u = resolve(under).removePrefix("#").takeLast(6)
        val alpha = alphaOf(t)
        val rgb = t.removePrefix("#").takeLast(6)
        return "#" + listOf(0, 2, 4).joinToString("") {
            val c = alpha * rgb.substring(it, it + 2).toInt(16) + (1 - alpha) * u.substring(it, it + 2).toInt(16)
            "%02X".format(Math.round(c).toInt())
        }
    }

    @Test
    fun `the ground is the Vibb night`() {
        assertTrue(resolve("kid_ground").equals("#0C0C14", ignoreCase = true))
        assertTrue(resolve("vibb_night").equals("#0C0C14", ignoreCase = true))
    }

    @Test
    fun `ink, accent and light text are readable on the ground and its lift`() {
        for (text in listOf("kid_ink", "kid_accent", "kid_core", "kid_slider")) {
            for (ground in listOf("kid_ground", "kid_ground_lift")) {
                val ratio = contrast(text, ground)
                assertTrue("$text on $ground: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `the contact sheet is night, never a white surface`() {
        // Design 34 P3 (QA M4): the sheet and its badge ring are the lifted night.
        assertEquals("@color/kid_ground_lift", colours.getValue("kid_sheet"))
        assertEquals("#1C1A2A", resolve("kid_sheet").uppercase())
        // Home's window banner keeps the dark ink (on its own light pill).
        assertEquals("#1B1B1F", resolve("kid_sheet_ink").uppercase())
    }

    @Test
    fun `every text and glyph in the contact sheet's layout is readable on what is under it`() {
        // qa-34-s3-code L3: the colours sheet_contact.xml really uses, not a list kept by hand.
        val android = "http://schemas.android.com/apk/res/android"
        val app = "http://schemas.android.com/apk/res-auto"
        val styleText: Map<String, String?> = run {
            val styles = parse("values/styles.xml").getElementsByTagName("style")
            (0 until styles.length).map { styles.item(it) as Element }.associate { style ->
                val items = style.getElementsByTagName("item")
                style.getAttribute("name") to (0 until items.length).map { items.item(it) as Element }
                    .firstOrNull { it.getAttribute("name") == "android:textColor" }?.textContent?.trim()
            }
        }
        fun glyphColour(drawable: String): String {
            val path = parse("drawable/${drawable.removePrefix("@drawable/")}.xml").getElementsByTagName("path").item(0) as Element
            return path.getAttributeNS(android, "strokeColor").ifEmpty { path.getAttributeNS(android, "fillColor") }
        }
        fun solidOf(drawable: String): String? {
            val solids = parse("drawable/${drawable.removePrefix("@drawable/")}.xml").getElementsByTagName("solid")
            return (solids.item(0) as Element?)?.getAttributeNS(android, "color")
        }
        val sheet = resolve("kid_sheet")
        val root = parse("layout/sheet_contact.xml").documentElement
        assertEquals("@drawable/bg_kid_sheet", root.getAttributeNS(android, "background"))
        assertEquals("@color/kid_sheet", solidOf("@drawable/bg_kid_sheet"))
        val views = root.getElementsByTagName("*")
        var checked = 0
        for (view in (0 until views.length).map { views.item(it) as Element }) {
            val id = view.getAttributeNS(android, "id").removePrefix("@+id/")
            // The missed badge's pill is design 34 step 2's colour (P2: kid_badge, checked there).
            if (id == "sheet_badge") continue
            val style = view.getAttribute("style").removePrefix("@style/")
            val text = view.getAttributeNS(android, "textColor").ifEmpty { styleText[style].orEmpty() }
            val glyph = view.getAttributeNS(app, "drawableTopCompat")
            if (text.isEmpty() && glyph.isEmpty()) continue
            val tint = view.getAttributeNS(android, "backgroundTint")
            val background = view.getAttributeNS(android, "background")
            val ground = when {
                tint.isNotEmpty() -> over(tint, sheet)
                background.startsWith("@drawable/") -> solidOf(background)?.let { over(it, sheet) } ?: sheet
                else -> sheet
            }
            if (text.isNotEmpty()) {
                val ratio = contrast(text, ground)
                assertTrue("$id text $text on $ground: $ratio", ratio >= 4.5)
                checked++
            }
            if (glyph.isNotEmpty()) {
                val ink = view.getAttributeNS(app, "drawableTint").ifEmpty { glyphColour(glyph) }
                val ratio = contrast(ink, ground)
                assertTrue("$id glyph $ink on $ground: $ratio", ratio >= 4.5)
                checked++
            }
        }
        // The name, missed calls, Call (text and glyph), Message (text and glyph), the hint, Close.
        assertEquals(8, checked)
        // QA M4's number: missed calls #FF8A8A at 7.5:1. The tonal button stays a quiet field, the handle visible.
        assertEquals(7.5, contrast("kid_missed_text", "kid_sheet"), 0.05)
        assertTrue(contrast("kid_sheet_button", "kid_sheet") < 1.5)
        assertTrue(contrast("kid_sheet_handle", "kid_sheet") >= 1.8)
    }

    @Test
    fun `the call green carries white`() {
        // qa-34-s3-code L1: Call, the answer button and Home's call card share one green, white >= 4.5:1.
        assertTrue(contrast("kid_ink", "kid_call_button") >= 4.5)
        for (drawable in listOf("bg_call_green", "bg_home_call_card")) {
            assertTrue(drawable, resFile("drawable/$drawable.xml").readText().contains("@color/kid_call_button"))
        }
    }

    @Test
    fun `the phone book and settings tiles carry white and stand off the ground`() {
        // Design 34 P1 (QA M3): the Phone book tile is a muted call green, Settings the slate tone.
        assertTrue(resolve("kid_phone_book").equals("#3A7552", ignoreCase = true))
        assertTrue(resolve("kid_settings_tile").equals("#%06X".format(TONE_SLATE and 0xFFFFFF), ignoreCase = true))
        for (tile in listOf("kid_phone_book", "kid_settings_tile")) {
            assertTrue(tile, contrast("kid_ink", tile) >= 4.5)
            assertTrue(tile, contrast(tile, "kid_ground") >= 3.5)
        }
        // The legacy icon's light disc: a dark night glyph would show on it, the night stands off it.
        assertTrue(contrast("kid_icon_fallback", "kid_ground") >= 3.0)
    }

    @Test
    fun `the 112 avatar and the tonal emergency colours are one pair`() {
        // qa-34-s3-code N3: EMERGENCY_AVATAR is these two resources, which P7's pill uses too.
        assertEquals(resolve("kid_emergency_tonal").uppercase(), "#%06X".format(EMERGENCY_AVATAR.background and 0xFFFFFF))
        assertEquals(resolve("kid_emergency_text").uppercase(), "#%06X".format(EMERGENCY_AVATAR.ink and 0xFFFFFF))
        assertTrue(contrast("kid_emergency_text", "kid_emergency_tonal") >= 6.6)
    }

    @Test
    fun `the lock's tonal emergency pills carry their label and glyph`() {
        // Design 34 P7: what the keypad (activity_pin_lock.xml) and the VoIP ring card
        // (view_voip_ring.xml, qa-34-s8-code L2) really use, from the shared resources.
        val android = "http://schemas.android.com/apk/res/android"
        for ((layout, id) in listOf("activity_pin_lock" to "pin_emergency", "view_voip_ring" to "voip_ring_emergency")) {
            val all = parse("layout/$layout.xml").getElementsByTagName("*")
            val views = (0 until all.length).map { all.item(it) as Element }
                .associateBy { it.getAttributeNS(android, "id").removePrefix("@+id/") }
            val pill = views.getValue(id).getAttributeNS(android, "background").removePrefix("@drawable/")
            val solid = parse("drawable/$pill.xml").getElementsByTagName("solid").item(0) as Element
            val ground = solid.getAttributeNS(android, "color")
            assertEquals(layout, "@color/kid_emergency_tonal", ground)
            val label = views.getValue("${id}_label").getAttributeNS(android, "textColor")
            val glyph = views.getValue("${id}_icon").getAttributeNS(android, "tint")
            for (ink in listOf(label, glyph)) {
                val ratio = contrast(ink, ground)
                assertTrue("$layout: $ink on $ground: $ratio", ratio >= 4.5)
            }
            assertEquals(6.6, contrast(label, ground), 0.1)
        }
        // The press (N4): a ripple of the label's red at 20 % inside the pill - calm, no new colour.
        val ripple = parse("drawable/bg_pin_emergency_tonal.xml").documentElement
        assertEquals("ripple", ripple.tagName)
        val press = ripple.getAttributeNS(android, "color")
        assertEquals(resolve("kid_emergency_text").takeLast(6).uppercase(), press.takeLast(6).uppercase())
        assertEquals(0.2, alphaOf(press), 0.01)
        // The saturated pill is gone with its last user.
        assertTrue(listOf("src/main/res/drawable", "app/src/main/res/drawable").none { File(it, "bg_pin_emergency.xml").exists() })
        // Muted, never garish: the pill is a quiet field on the night (QA N4: about 1.3:1 - its text
        // and glyph identify it), and its red reads on the bare night too.
        assertTrue(contrast("kid_emergency_tonal", "kid_ground") < 1.5)
        assertTrue(contrast("kid_emergency_text", "kid_ground") >= 4.5)
    }
}
