package com.kidslauncher.mdm.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Design 34 P12: one clock format on the whole phone. The pure rules of [ClockFormat], and a scan
 * that keeps every other source from asking Android for a time format or spelling a time pattern
 * of its own (qa-34-design M7), and every time TextClock bound to the system's format.
 */
class ClockFormatTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private val sources = File(main, "java")

    @Test
    fun `TIME_12_24 wins, else the system locale's default`() {
        assertTrue(ClockFormat.is24("24", localeIs24 = false))
        assertFalse(ClockFormat.is24("12", localeIs24 = true))
        for (unset in listOf(null, "", "garbage")) {
            assertTrue(ClockFormat.is24(unset, localeIs24 = true))
            assertFalse(ClockFormat.is24(unset, localeIs24 = false))
        }
    }

    @Test
    fun `a locale's jm pattern is 24 h when it has H or k outside quotes`() {
        assertTrue(ClockFormat.patternIs24("HH:mm")) // nb, de
        assertTrue(ClockFormat.patternIs24("H:mm")) // fi
        assertTrue(ClockFormat.patternIs24("H.mm 'Uhr'"))
        assertTrue(ClockFormat.patternIs24("kk:mm"))
        assertFalse(ClockFormat.patternIs24("h:mm a")) // en_US
        assertFalse(ClockFormat.patternIs24("a h:mm")) // ko
        assertFalse(ClockFormat.patternIs24("h:mm 'Hour'"))
        assertFalse(ClockFormat.patternIs24("K:mm a"))
    }

    @Test
    fun `the patterns and the formatted times`() {
        assertEquals("HH:mm", ClockFormat.timePattern(true))
        assertEquals("h:mm", ClockFormat.timePattern(false))
        // 24 h always has two hour digits: the lock and the glance agree now (6:15 vs 06:15 is gone).
        assertEquals("06:15", ClockFormat.formatTime(6, 15, true))
        assertEquals("00:05", ClockFormat.formatTime(0, 5, true))
        assertEquals("23:59", ClockFormat.formatTime(23, 59, true))
        assertEquals("6:15", ClockFormat.formatTime(6, 15, false))
        assertEquals("6:40", ClockFormat.formatTime(18, 40, false))
        assertEquals("12:05", ClockFormat.formatTime(0, 5, false))
        assertEquals("12:00", ClockFormat.formatTime(12, 0, false))
        assertEquals("1:07", ClockFormat.formatTime(13, 7, false))
    }

    /** Only `ui/ClockFormat.kt` may ask for a time format or spell a time pattern (qa-34-design M7). */
    @Test
    fun `no clock format outside ClockFormat`() {
        val forbidden = listOf(
            Regex("""\bis24HourFormat\b"""),
            Regex("""\bgetTimeFormat\b"""),
            Regex("""\bgetTimeInstance\b"""),
            Regex("""\bgetDateTimeInstance\b"""),
            Regex("""\bofLocalized(Date)?Time\b"""),
            Regex("""\bFORMAT_SHOW_TIME\b"""),
            // A string literal holding a time pattern: H:mm, HH:mm, h:mm, hh:mm, k:mm, kk:mm, K:mm.
            Regex(""""[^"\n]*(?<![A-Za-z])[HhkK]{1,2}[:.]mm[^"\n]*""""),
            // A best pattern asked for with an hour field (j, H, h, k, K).
            Regex("""getBestDateTimePattern\([^,]*,\s*"[^"]*[jJHhkK]"""),
            // The old two-digit clock text ("%02d:%02d" of an hour and a minute; not h:mm:ss durations).
            Regex("""(?<!:)%02d:%02d(?!:)"""),
        )
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.endsWith("/ui/ClockFormat.kt").not() }
            .flatMap { file ->
                val code = stripComments(file.readText())
                forbidden.filter { it.containsMatchIn(code) }.map { "${file.name}: ${it.pattern}" }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the scan sees what it is meant to`() {
        val code = stripComments(
            """
            // DateFormat.is24HourFormat(context) in a comment is fine
            /* "HH:mm" in a block comment too */
            val t = DateFormat.format("HH:mm", ms)
            """.trimIndent(),
        )
        assertFalse("is24HourFormat" in code)
        assertEquals(1, Regex(""""HH:mm"""").findAll(code).count())
        // ClockFormat itself holds them.
        val own = File(sources, "com/kidslauncher/mdm/ui/ClockFormat.kt").readText()
        assertTrue(""""HH:mm"""" in own && """"h:mm"""" in own && "getBestDateTimePattern(locale, \"jm\")" in own)
    }

    /** Every time TextClock: the XML's two formats are ClockFormat's, and the code binds it to the
     * system's choice (a TextClock picks by Vibb's per-app language otherwise). */
    @Test
    fun `every time TextClock is bound to the system format`() {
        val android = "http://schemas.android.com/apk/res/android"
        val kotlin = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { stripComments(it.readText()) }
        val clocks = mutableListOf<String>()
        File(main, "res/layout").listFiles()!!.filter { it.extension == "xml" }.forEach { layout ->
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(layout)
            val nodes = doc.getElementsByTagName("TextClock")
            for (i in 0 until nodes.length) {
                val clock = nodes.item(i) as Element
                val f12 = clock.getAttributeNS(android, "format12Hour")
                val f24 = clock.getAttributeNS(android, "format24Hour")
                if (f12.isEmpty() && f24.isEmpty()) continue // a date clock, its pattern set in code
                val id = clock.getAttributeNS(android, "id").substringAfter("/")
                assertEquals("${layout.name} $id", ClockFormat.PATTERN_12, f12)
                assertEquals("${layout.name} $id", ClockFormat.PATTERN_24, f24)
                val binding = id.split("_").mapIndexed { n, part -> if (n == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")
                assertTrue("${layout.name} $id is bound", Regex("""ClockFormat\.bind\([\w.]*\b$binding\)""").containsMatchIn(kotlin))
                clocks += id
            }
        }
        assertEquals(listOf("lock_clock", "lock_glance_clock"), clocks.sorted())
    }

    /** The surfaces the design names all go through ClockFormat (qa-34-design M7's inventory). */
    @Test
    fun `every clock surface formats through ClockFormat`() {
        fun src(path: String) = stripComments(File(sources, "com/kidslauncher/mdm/$path").readText())
        for (path in listOf(
            "lock/GlancePanel.kt", "ui/LockActivity.kt", "ui/home/MissedCallText.kt", "ui/HomeActivity.kt",
            "server/WindowRelock.kt", "play/PlayRuntime.kt", "ui/settings/launcher/SettingsFragmentLauncher.kt",
        )) {
            assertTrue(path, "ClockFormat.formatTime(" in src(path))
        }
        // The lock screens follow a change while shown.
        for (path in listOf("lock/GlancePanel.kt", "ui/LockActivity.kt")) {
            assertTrue(path, "ClockFormat.watch(" in src(path) && "ClockFormat.unwatch(" in src(path))
        }
    }

    private fun stripComments(code: String): String =
        code.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")
}
