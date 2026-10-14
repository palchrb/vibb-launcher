package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/** The Vibb night palette (fix round 2026-10-06): text colours on the ground stay >= 4.5:1. */
class KidPaletteTest {
    private val colours: Map<String, String> = run {
        val file = listOf("src/main/res/values/colors_kid.xml", "app/src/main/res/values/colors_kid.xml").map(::File).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("color")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun resolve(name: String): String {
        val value = colours.getValue(name)
        return if (value.startsWith("@color/")) resolve(value.removePrefix("@color/")) else value
    }

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").takeLast(6)
        return listOf(0, 2, 4).map { rgb.substring(it, it + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            .let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
    }

    private fun contrast(a: String, b: String): Double = contrastHex(resolve(a), resolve(b))

    private fun contrastHex(a: String, b: String): Double {
        val (l1, l2) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** The colour [top] (#AARRGGBB or #RRGGBB) shows painted over the opaque [under]. */
    private fun over(top: String, under: String): String {
        val t = resolve(top).removePrefix("#")
        val u = resolve(under).removePrefix("#").takeLast(6)
        val alpha = if (t.length == 8) t.substring(0, 2).toInt(16) / 255.0 else 1.0
        val rgb = t.takeLast(6)
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
    fun `every text on the contact sheet is readable on it`() {
        // The name (kid_core), missed calls, the hint and Close (kid_sheet_muted, the light at 78 %).
        for (text in listOf("kid_core", "kid_missed_text", "kid_sheet_muted")) {
            val ratio = contrastHex(over(text, "kid_sheet"), resolve("kid_sheet"))
            assertTrue("$text on kid_sheet: $ratio", ratio >= 4.5)
        }
        // QA M4's number: missed calls #FF8A8A at 7.5:1.
        assertEquals(7.5, contrast("kid_missed_text", "kid_sheet"), 0.05)
        // Message: kid_core text and the peach glyph on the tonal white 8 % over the sheet.
        val message = over("kid_sheet_button", "kid_sheet")
        for (ink in listOf("kid_core", "kid_accent")) {
            val ratio = contrastHex(resolve(ink), message)
            assertTrue("$ink on Message: $ratio", ratio >= 4.5)
        }
        // The tonal button is a quiet field, the handle still visible.
        assertTrue(contrastHex(message, resolve("kid_sheet")) < 1.5)
        assertTrue(contrastHex(over("kid_sheet_handle", "kid_sheet"), resolve("kid_sheet")) >= 1.8)
    }
}
