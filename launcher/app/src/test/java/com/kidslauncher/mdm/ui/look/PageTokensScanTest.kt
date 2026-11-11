package com.kidslauncher.mdm.ui.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 78 §5.1: a page's layouts, drawables, styles and classes name page tokens only - no night token, no literal
 * colour (a white ripple included) - so nothing on a page stays night in Lys. Kalender in step 1; Meldinger, the
 * phone book and kid Settings' Wi-Fi, Bluetooth and sound lists in step 2.
 */
class PageTokensScanTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private val java = File(main, "java/com/kidslauncher/mdm")

    /** The pages' classes, and what they draw with: every Kotlin file of Kalender, Meldinger (the picture viewer is
     * night in both looks, §2) and the lists, the sound picker, and the phone book's three. */
    private val pageClasses: List<File> by lazy {
        fun dir(path: String) = File(java, path).listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        val files = dir("ui/day") + dir("ui/messages").filterNot { it.name == "PictureActivity.kt" } + dir("ui/quickcontrols") +
            dir("ui/kidsettings").filter { it.name.startsWith("KidSoundPicker") } +
            listOf("PhoneBookActivity.kt", "PhoneBookAdapter.kt", "ContactRequestForm.kt").map { File(java, "calls/$it") }
        files.forEach { assertTrue(it.path, it.isFile) }
        files
    }

    /**
     * The page layouts, read from the code: every layout the pages' classes name - `R.layout.x`, or its binding class -
     * and kid Settings' preview strip (inflated as a page, `PageLook.wrap`), with what they include.
     */
    private val layouts: List<String> by lazy {
        val classes = pageClasses.joinToString("\n") { kotlin(it) }
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

    /** White shapes that are only ever drawn tinted (or as a photo's round clip, `bg_kid_circle`); each tint site is
     * asserted below. */
    private val masks = listOf(
        "bg_day_dot", "ic_kid_back", "bg_kid_circle", "ic_kid_search", "ic_kid_search_clear", "ic_kid_message", "ic_kid_phone",
        "ic_kid_check", "app_glyph_photo_library",
    )

    /** The same in both looks (§4.1): what sits on a peach fill, the kid's own bubble, and the badge pill. */
    private val bothLooks = setOf(
        "kid_on_accent", "kid_on_accent_press", "kid_message_out", "kid_message_out_ink", "kid_message_out_dim", "kid_badge", "kid_badge_ink",
    )

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

    private fun drawablesIn(text: String) = Regex("""@drawable/([a-z_0-9]+)""").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `the page layouts, their drawables and their styles name page tokens only`() {
        // Each step's screens, the rows, and the header and the search they include.
        val expected = listOf(
            "activity_day", "activity_event", "activity_event_edit", "item_day_page", "item_day_event", "include_kid_header",
            "activity_messages", "activity_thread", "item_message_bubble", "item_message_day", "item_messages_row", "view_messages_confirm",
            "view_messages_picker", "view_messages_picture_choice", "view_messages_react", "view_kid_search", "view_kid_search_empty",
            "activity_wifi_networks", "item_wifi_network", "activity_bluetooth_devices", "item_bluetooth_device",
            "activity_kid_sound_picker", "item_kid_sound",
            "activity_phone_book", "item_phone_book_row", "item_phone_book_label", "item_phone_book_request", "view_contact_request_form",
        )
        assertTrue("$layouts", layouts.containsAll(expected))
        // The picture viewer is night: its layout is not a page's.
        assertTrue("$layouts", "activity_picture" !in layouts)
        val styles = xml("values/styles")
        // Every drawable a page layout, its styles or its classes name.
        val drawables = LinkedHashSet<String>()
        for (layout in layouts) {
            val text = xml("layout/$layout")
            assertEquals("layout/$layout", emptyList<String>(), strays(text))
            drawables += drawablesIn(text)
            for (style in Regex("""style="@style/([A-Za-z0-9_.]+)"""").findAll(text).map { it.groupValues[1] }) {
                val body = styles.substringAfter("<style name=\"$style\"", "").substringBefore("</style>")
                assertTrue("layout/$layout names $style", body.isNotEmpty())
                assertEquals(style, emptyList<String>(), strays(body))
                drawables += drawablesIn(body)
            }
        }
        for (file in pageClasses) drawables += Regex("""R\.drawable\.([a-z_0-9]+)""").findAll(kotlin(file)).map { it.groupValues[1] }
        assertTrue("$drawables", drawables.containsAll(listOf("bg_day_event", "bg_message_in", "bg_messages_confirm", "bg_kid_page_card", "bg_phone_book_button")))
        for (drawable in drawables - masks.toSet()) assertEquals("drawable/$drawable", emptyList<String>(), strays(xml("drawable/$drawable")))
        // The pickers' number colours.
        assertEquals(emptyList<String>(), strays(xml("color/vibb_picker_number")))
        // The masks: white, and drawn only tinted - the dot by the adapter in the calendar's colour of the page's look,
        // the photo glyph by the thread in the bubble's dim, the circle as a photo's clip or under the avatar's colour,
        // every other one by a page colour on the element that names it.
        for (mask in masks) assertTrue(mask, xml("drawable/$mask").contains("#FFFFFFFF"))
        val adapter = kotlin(File(java, "ui/day/DayListAdapter.kt"))
        assertTrue(adapter.contains("dot.backgroundTintList = ColorStateList.valueOf(color.argb(context))"))
        val thread = kotlin(File(java, "ui/messages/ThreadActivity.kt"))
        assertTrue(thread.contains("image.setImageResource(R.drawable.app_glyph_photo_library)\n                image.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(dimOf(m)))"))
        for (layout in layouts) {
            for (element in xml("layout/$layout").split('<')) {
                val src = Regex("""android:src="@drawable/([a-z_0-9]+)"""").find(element)?.groupValues?.get(1) ?: continue
                if (src !in masks) continue
                val tint = Regex("""android:tint="@color/([a-z_0-9]+)"""").find(element)?.groupValues?.get(1)
                assertTrue("layout/$layout: $src untinted", tint != null && pageColour(tint))
            }
        }
        val header = xml("layout/include_kid_header")
        assertTrue(header.contains("android:src=\"@drawable/ic_kid_back\""))
        assertTrue(header.contains("android:tint=\"@color/kid_page_ink\""))
    }

    @Test
    fun `the pages' classes read page colours only, a dot's through the one resolver`() {
        assertTrue(pageClasses.size >= 20)
        for (file in pageClasses) {
            val code = kotlin(file)
            val named = Regex("""R\.color\.([a-z_0-9]+)""").findAll(code).map { it.groupValues[1] }.filterNot(::pageColour).toList()
            assertEquals("${file.name} names a night token", emptyList<String>(), named)
            val literals = Regex("""\bColor\.[A-Za-z]+|0x[0-9A-Fa-f]{6,8}\b|"#[0-9A-Fa-f]{3,8}"""").findAll(code).map { it.value }.toList()
            assertEquals("${file.name} names a literal colour", emptyList<String>(), literals)
            // CalendarColor.argb is the night table (the lock's glance); a page asks argb(context).
            val nightTable = Regex("""\.(argb|dayArgb)\b(?!\()""").findAll(code).map { it.value }.toList()
            assertEquals("${file.name} reads a dot colour past argb(context)", emptyList<String>(), nightTable)
        }
        val adapter = kotlin(File(java, "ui/day/DayListAdapter.kt"))
        assertEquals(2, Regex("""\.argb\((text\.)?context\)""").findAll(adapter).count())
    }

    /** §12.3: the application's context follows the phone's theme, not the kid's look. */
    @Test
    fun `a page token is read through a page's context, never the application's`() {
        // Kotlin names a page colour only in the pages' own classes ...
        val all = listOf("java", "../debug/java", "../release/java").map { File(main, it) }.flatMap { dir ->
            assertTrue(dir.path, dir.isDirectory)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(all.size > 100)
        val naming = all.filter { kotlin(it).contains("R.color.kid_page_") }.map { it.path.replace(File.separatorChar, '/').substringAfter("/mdm/") }
        assertEquals(setOf("ui/day/DayListAdapter.kt", "ui/messages/ThreadActivity.kt", "calls/PhoneBookAdapter.kt"), naming.toSet())
        // ... and there the application's context only does what isn't drawing: no resource is taken from it, nothing
        // is inflated from it, no colour is asked with it.
        val app = """(?<![.\w])(app|applicationContext|application)"""
        val reads = Regex("$app\\s*\\.\\s*(getColor|getColorStateList|getDrawable|resources|theme|obtainStyledAttributes)\\b")
        val given = Regex("(LayoutInflater\\.from|\\.argb|\\.getColor|\\.getColorStateList|\\.getDrawable)\\(\\s*$app\\b")
        for (file in pageClasses) {
            val code = kotlin(file)
            assertEquals(file.name, emptyList<String>(), (reads.findAll(code) + given.findAll(code)).map { it.value }.toList())
        }
        // Kid Settings' preview, on a night screen: inflated from PageLook.wrap, which sets the look's night bit
        // itself whatever context it is given.
        val settings = kotlin(File(java, "ui/kidsettings/KidSettingsActivity.kt"))
        assertTrue(settings.contains("LayoutInflater.from(PageLook.wrap(this, look)).inflate(R.layout.view_look_preview, "))
    }

    /** §2: the contact card, the "Bilde av …" sheet, the emergency menu and its 112 confirms stay night in both looks,
     * also when a page in Lys opens them. */
    @Test
    fun `the night surfaces a page can open are built from the night`() {
        val sheets = mapOf(
            "calls/ContactSheet.kt" to listOf("LayoutInflater.from(night).inflate(R.layout.sheet_contact, null)", "Dialog(night, R.style.KidSheetDialog)"),
            "calls/KidPhotoSheet.kt" to listOf("LayoutInflater.from(night).inflate(R.layout.sheet_kid_photo, null)", "Dialog(night, R.style.KidSheetDialog)"),
            "calls/EmergencySheet.kt" to listOf("val inflater = LayoutInflater.from(night)", "Dialog(night, R.style.KidSheetDialog)"),
            "calls/EmergencyCall.kt" to listOf("AlertDialog.Builder(nightOver(activity), R.style.KidAlertDialog)"),
            "calls/PhoneBookActivity.kt" to listOf("AlertDialog.Builder(nightOver(this), R.style.KidAlertDialog)"),
        )
        for ((file, uses) in sheets) {
            val code = kotlin(File(java, file))
            for (use in uses) assertTrue("$file: $use", code.contains(use))
            // A sheet is a plain Dialog; an AppCompat confirm would take the page's own night mode back from the activity
            // behind its context, so it is shown through showNight.
            if (file == "calls/EmergencyCall.kt" || file == "calls/PhoneBookActivity.kt") {
                assertTrue(file, code.contains(".let(::showNight)"))
            } else {
                assertTrue(file, code.contains("val night = nightOver(activity)"))
            }
            // Nothing in it is built from the activity itself.
            assertTrue(file, !Regex("""LayoutInflater\.from\(activity\)|Dialog\(activity\b|AlertDialog\.Builder\((activity|this), R\.style\.KidAlertDialog""").containsMatchIn(code))
        }
        val over = kotlin(File(java, "ui/look/NightOver.kt"))
        assertTrue(over.contains("if (activity !is KidPageActivity) return activity"))
        assertTrue(over.contains("ContextThemeWrapper(activity, R.style.PageColors).apply { applyOverrideConfiguration(night) }"))
        assertTrue(over.contains("it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES\n    it.show()"))
    }
}
