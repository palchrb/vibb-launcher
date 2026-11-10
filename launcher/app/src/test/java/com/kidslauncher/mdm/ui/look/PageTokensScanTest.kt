package com.kidslauncher.mdm.ui.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 78 §5.1: a page's layouts, drawables, styles and classes name page tokens only - no night token, no literal
 * colour (a white ripple included) - so nothing on a page stays night in Lys. Kalender in step 1.
 */
class PageTokensScanTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    /**
     * The page layouts, read from the code: every layout Kalender's classes name - `R.layout.x`, or its binding
     * class - and kid Settings' preview strip (inflated as a page, `PageLook.wrap`), with what they include.
     */
    private val layouts: List<String> by lazy {
        val classes = File(main, "java/com/kidslauncher/mdm/ui/day").listFiles { f -> f.extension == "kt" }.orEmpty()
            .joinToString("\n") { kotlin(it) }
        val named = Regex("""R\.layout\.([a-z_0-9]+)""").findAll(classes).map { it.groupValues[1] } +
            Regex("""\b([A-Z][A-Za-z0-9]*)Binding\b""").findAll(classes)
                .map { it.groupValues[1].replace(Regex("(?<=.)([A-Z])"), "_$1").lowercase() }
        val found = LinkedHashSet(named.toList() + "view_look_preview")
        val unread = ArrayDeque(found)
        while (unread.isNotEmpty()) {
            for (included in Regex("""layout="@layout/([a-z_0-9]+)"""").findAll(xml("layout/${unread.removeFirst()}"))) {
                if (found.add(included.groupValues[1])) unread += included.groupValues[1]
            }
        }
        found.toList()
    }

    private val drawables = listOf(
        "bg_day_event", "bg_day_event_now", "bg_day_add", "bg_day_save", "ic_day_add", "bg_kid_back_button",
        "bg_kid_page_dialog", "bg_look_preview", "bg_look_preview_in", "bg_look_preview_out",
    )

    /** White shapes that are only ever drawn tinted; each tint site is asserted below. */
    private val masks = listOf("bg_day_dot", "ic_kid_back")

    /** The same in both looks (§4.1): what sits on a peach fill, and the kid's own bubble. */
    private val bothLooks = setOf("kid_on_accent", "kid_on_accent_press", "kid_message_out", "kid_message_out_ink", "kid_message_out_dim")

    private fun xml(path: String): String =
        File(main, "res/$path.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun kotlin(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun pageColour(name: String) = name.startsWith("kid_page_") || name in bothLooks

    /** What [text] (comments stripped) names that isn't a page colour. */
    private fun strays(text: String): List<String> =
        Regex("""@color/([a-z_0-9]+)""").findAll(text).map { it.groupValues[1] }.filterNot(::pageColour).map { "@color/$it" }.toList() +
            Regex("""[">]#[0-9A-Fa-f]{3,8}["<]""").findAll(text).map { it.value }.toList() +
            Regex("""@android:color/[a-z_0-9]+""").findAll(text).map { it.value }.toList()

    @Test
    fun `the page layouts, their drawables and their styles name page tokens only`() {
        // The three screens, the pager's page and the rows, and the header they include.
        assertTrue("$layouts", layouts.containsAll(listOf("activity_day", "activity_event", "activity_event_edit", "item_day_page", "item_day_event", "include_kid_header")))
        for (layout in layouts) assertEquals("layout/$layout", emptyList<String>(), strays(xml("layout/$layout")))
        for (drawable in drawables) assertEquals("drawable/$drawable", emptyList<String>(), strays(xml("drawable/$drawable")))
        // Every drawable and style a page layout names is one of those checked here.
        val styles = xml("values/styles")
        for (layout in layouts) {
            val text = xml("layout/$layout")
            for (drawable in Regex("""@drawable/([a-z_0-9]+)""").findAll(text).map { it.groupValues[1] }) {
                assertTrue("layout/$layout names $drawable", drawable in drawables || drawable in masks)
            }
            for (style in Regex("""style="@style/([A-Za-z0-9_.]+)"""").findAll(text).map { it.groupValues[1] }) {
                val body = styles.substringAfter("<style name=\"$style\"", "").substringBefore("</style>")
                assertTrue("layout/$layout names $style", body.isNotEmpty())
                assertEquals(style, emptyList<String>(), strays(body))
                for (drawable in Regex("""@drawable/([a-z_0-9]+)""").findAll(body).map { it.groupValues[1] }) {
                    assertTrue("$style names $drawable", drawable in drawables)
                }
            }
        }
        // The pickers' number colours.
        assertEquals(emptyList<String>(), strays(xml("color/vibb_picker_number")))
        // The masks: drawn only tinted - the dot by the adapter in the calendar's colour of the page's look, the
        // back arrow in the page's ink.
        for (mask in masks) assertTrue(mask, xml("drawable/$mask").contains("#FFFFFFFF"))
        val adapter = kotlin(File(main, "java/com/kidslauncher/mdm/ui/day/DayListAdapter.kt"))
        assertTrue(adapter.contains("dot.backgroundTintList = ColorStateList.valueOf(color.argb(context))"))
        val header = xml("layout/include_kid_header")
        assertTrue(header.contains("android:src=\"@drawable/ic_kid_back\""))
        assertTrue(header.contains("android:tint=\"@color/kid_page_ink\""))
    }

    @Test
    fun `Kalender's classes read page colours only, a dot's through the one resolver`() {
        val files = File(main, "java/com/kidslauncher/mdm/ui/day").listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(files.size >= 5)
        for (file in files) {
            val code = kotlin(file)
            val named = Regex("""R\.color\.([a-z_0-9]+)""").findAll(code).map { it.groupValues[1] }.filterNot(::pageColour).toList()
            assertEquals("${file.name} names a night token", emptyList<String>(), named)
            val literals = Regex("""\bColor\.[A-Za-z]+|0x[0-9A-Fa-f]{6,8}\b|"#[0-9A-Fa-f]{3,8}"""").findAll(code).map { it.value }.toList()
            assertEquals("${file.name} names a literal colour", emptyList<String>(), literals)
            // CalendarColor.argb is the night table (the lock's glance); a page asks argb(context).
            val nightTable = Regex("""\.(argb|dayArgb)\b(?!\()""").findAll(code).map { it.value }.toList()
            assertEquals("${file.name} reads a dot colour past argb(context)", emptyList<String>(), nightTable)
        }
        val adapter = kotlin(File(main, "java/com/kidslauncher/mdm/ui/day/DayListAdapter.kt"))
        assertEquals(2, Regex("""\.argb\((text\.)?context\)""").findAll(adapter).count())
    }
}
