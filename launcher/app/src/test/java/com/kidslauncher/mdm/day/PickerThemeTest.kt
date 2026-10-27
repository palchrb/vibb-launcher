package com.kidslauncher.mdm.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/** "Ny avtale"'s date and time pickers (jellystar2, 2026-10-27: every day number but the selected one was invisible):
 * both are built with VibbPickerDialog, and the theme names every colour the pickers draw, readable on its panel. */
class PickerThemeTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    private fun styles(): Map<String, Pair<String, Map<String, String>>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(main, "res/values/styles.xml"))
        val out = HashMap<String, Pair<String, Map<String, String>>>()
        val list = doc.getElementsByTagName("style")
        for (i in 0 until list.length) {
            val style = list.item(i) as Element
            val items = HashMap<String, String>()
            val children = style.getElementsByTagName("item")
            for (j in 0 until children.length) {
                val item = children.item(j) as Element
                items[item.getAttribute("name")] = item.textContent.trim()
            }
            out[style.getAttribute("name")] = style.getAttribute("parent") to items
        }
        return out
    }

    private fun colours(): Map<String, String> {
        val text = File(main, "res/values/colors_kid.xml").readText()
        return Regex("""<color name="([a-z_]+)">([^<]+)</color>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun resolve(ref: String, colours: Map<String, String>): String {
        var value = ref
        repeat(4) { if (value.startsWith("@color/")) value = colours.getValue(value.removePrefix("@color/")) }
        return value
    }

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").takeLast(6)
        val channels = (0 until 3).map { rgb.substring(it * 2, it * 2 + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun contrast(a: String, b: String): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test
    fun `both pickers are built with our dialog theme`() {
        val sources = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val calls = sources.flatMap { f -> Regex("""\b(DatePickerDialog|TimePickerDialog)\(([^,]*),\s*([^,]*),""").findAll(f.readText()).map { f.name to it } }
        assertEquals(calls.map { it.first }.toString(), 2, calls.size)
        for ((file, call) in calls) assertEquals("$file: ${call.value}", "R.style.VibbPickerDialog", call.groupValues[3].trim())
    }

    @Test
    fun `the theme names every colour the pickers draw, readable on its panel`() {
        val all = styles()
        val colours = colours()
        val (parent, theme) = all.getValue("VibbPickerDialog")
        assertEquals("@android:style/Theme.Material.Dialog", parent)
        for (key in listOf("android:windowBackground", "android:colorBackground", "android:colorAccent", "android:colorControlNormal",
            "android:textColorPrimary", "android:textColorSecondary", "android:textColorPrimaryInverse")) {
            assertTrue(key, key in theme)
        }
        assertEquals("@style/VibbDatePicker", theme["android:datePickerStyle"])
        assertEquals("@style/VibbTimePicker", theme["android:timePickerStyle"])
        val date = all.getValue("VibbDatePicker").second
        val time = all.getValue("VibbTimePicker").second
        assertEquals("@color/vibb_picker_number", date["android:calendarTextColor"])
        assertEquals("@color/vibb_picker_number", time["android:numbersTextColor"])
        assertEquals("@color/vibb_picker_number", time["android:numbersInnerTextColor"])

        val panel = resolve(theme.getValue("android:colorBackground"), colours)
        val ink = resolve(theme.getValue("android:textColorPrimary"), colours)
        assertTrue("day numbers on the panel", contrast(ink, panel) >= 4.5)
        // The header and the selection: night text on peach.
        val accent = resolve(theme.getValue("android:colorAccent"), colours)
        assertTrue("on the peach", contrast(resolve(theme.getValue("android:textColorPrimaryInverse"), colours), accent) >= 4.5)
        assertEquals(accent, resolve(date.getValue("android:headerBackground"), colours))
        // The clock face's numbers on its own disc.
        assertTrue("clock numbers", contrast(ink, resolve(time.getValue("android:numbersBackgroundColor"), colours)) >= 4.5)

        val selector = File(main, "res/color/vibb_picker_number.xml").readText()
        assertTrue(selector.contains("""<item android:color="@color/kid_ground" android:state_activated="true" />"""))
        assertTrue(selector.contains("""<item android:color="@color/kid_ink" />"""))
    }
}
