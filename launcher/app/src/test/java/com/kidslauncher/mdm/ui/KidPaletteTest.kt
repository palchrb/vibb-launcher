package com.kidslauncher.mdm.ui

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

    private fun contrast(a: String, b: String): Double {
        val (l1, l2) = listOf(luminance(resolve(a)), luminance(resolve(b))).sortedDescending()
        return (l1 + 0.05) / (l2 + 0.05)
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
}
