package com.kidslauncher.mdm.ui

import java.io.File
import java.util.Locale
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
    private fun src(path: String) = stripComments(File(sources, "com/kidslauncher/mdm/$path").readText())

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
        assertTrue(ClockFormat.patternIs24("H.mm")) // fi
        assertTrue(ClockFormat.patternIs24("H.mm 'Uhr'"))
        assertTrue(ClockFormat.patternIs24("kk:mm"))
        assertFalse(ClockFormat.patternIs24("h:mm a")) // en_US
        assertFalse(ClockFormat.patternIs24("a h:mm")) // ko
        assertFalse(ClockFormat.patternIs24("h:mm 'Hour'"))
        assertFalse(ClockFormat.patternIs24("K:mm a"))
    }

    /** qa-34-s6-code M1: the system locale comes from the LocaleManager; the system resources (which
     * may carry Vibb's per-app language on Android 13+) only when it can't tell. */
    @Test
    fun `the system locale is the LocaleManager's, never the per-app one`() {
        val system = Locale.US
        val perApp = Locale.forLanguageTag("nb-NO")
        assertEquals(system, ClockFormat.chooseSystemLocale(system, perApp, perApp))
        assertEquals(perApp, ClockFormat.chooseSystemLocale(null, perApp, Locale.ROOT))
        assertEquals(Locale.ROOT, ClockFormat.chooseSystemLocale(null, null, Locale.ROOT))
        // The phone-side wiring: the LocaleManager's systemLocales feed it, and nothing in
        // ClockFormat reads the context's (per-app) configuration.
        val code = src("ui/ClockFormat.kt")
        assertTrue("getSystemService(LocaleManager::class.java)?.systemLocales" in code)
        assertTrue("chooseSystemLocale(fromManager, fromResources, Locale.getDefault())" in code)
        assertFalse("context.resources" in code)
        assertFalse(".resources.configuration" in code.substringAfter("fun systemIs24("))
        // Every format decision goes through systemLocale(context).
        assertEquals(3, Regex("""val locale = systemLocale\(context\)""").findAll(code).count() +
            Regex("""systemIs24\(context, systemLocale\(context\)\)""").findAll(code).count())
    }

    /** N6: the locale's own pattern with the hour field forced; a face drops the AM/PM marker. */
    @Test
    fun `face and until patterns from the locale's own`() {
        assertEquals("Hm", ClockFormat.skeleton(true))
        assertEquals("hm", ClockFormat.skeleton(false))
        assertEquals("h:mm", ClockFormat.facePattern("h:mm a"))
        assertEquals("h:mm", ClockFormat.facePattern("h:mm a"))
        assertEquals("h:mm", ClockFormat.facePattern("a h:mm"))
        assertEquals("H.mm", ClockFormat.facePattern("H.mm"))
        assertEquals("HH:mm", ClockFormat.facePattern("HH:mm"))
        assertEquals("h:mm 'a'", ClockFormat.facePattern("h:mm 'a'"))
        assertEquals("K:mm", ClockFormat.facePattern("aK:mm"))
    }

    @Test
    fun `the formatted times - faces without AM PM, ends with it`() {
        val us = Locale.US
        val nb = Locale.forLanguageTag("nb-NO")
        val fi = Locale.forLanguageTag("fi-FI")
        // 24 h: two hour digits, the locale's separator (fi H.mm, as its status bar).
        assertEquals("06:15", ClockFormat.format("HH:mm", 6, 15, true, nb, amPm = false))
        assertEquals("21.30", ClockFormat.format("H.mm", 21, 30, true, fi, amPm = false))
        // 12 h face (the status bar's look).
        assertEquals("6:15", ClockFormat.format(ClockFormat.facePattern("h:mm a"), 6, 15, false, us, amPm = false))
        assertEquals("12:05", ClockFormat.format(ClockFormat.facePattern("h:mm a"), 0, 5, false, us, amPm = false))
        // 12 h end time (L1): "until 9:30 PM" is never ambiguous.
        assertEquals("9:30 PM", ClockFormat.format("h:mm a", 21, 30, false, us, amPm = true))
        assertEquals("9:30 AM", ClockFormat.format("h:mm a", 9, 30, false, us, amPm = true))
        // A pattern java.time can't read falls back to ours.
        assertEquals("21:30", ClockFormat.format("{x", 21, 30, true, us, amPm = true))
        assertEquals("9:30 PM", ClockFormat.format("{x", 21, 30, false, us, amPm = true))
        assertEquals("9:30", ClockFormat.format("{x", 21, 30, false, us, amPm = false))
    }

    /** Only `ui/ClockFormat.kt` may ask for a time format or spell a time pattern (qa-34-design M7). */
    @Test
    fun `no clock format outside ClockFormat`() {
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.endsWith("/ui/ClockFormat.kt").not() }
            .flatMap { file ->
                val code = stripComments(file.readText())
                FORBIDDEN.filter { it.containsMatchIn(code) }.map { "${file.name}: ${it.pattern}" }
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
            val u = "${'$'}{now.hour}:${'$'}{now.minute}"
            """.trimIndent(),
        )
        assertFalse("is24HourFormat" in code)
        val hits = FORBIDDEN.filter { it.containsMatchIn(code) }.map { it.pattern }
        assertEquals(2, hits.size) // the pattern literal and the template (N5)
        val own = File(sources, "com/kidslauncher/mdm/ui/ClockFormat.kt").readText()
        assertTrue(""""HH:mm"""" in own && """"h:mm"""" in own && "getBestDateTimePattern(locale, skeleton)" in own)
    }

    /** Every time TextClock: the XML's two formats are ClockFormat's first-frame ones, and the code
     * binds it to the system's choice on the start/resume path - not only inside a watch lambda
     * (qa-34-s6-code L2). */
    @Test
    fun `every time TextClock is bound to the system format`() {
        val android = "http://schemas.android.com/apk/res/android"
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
                clocks += id
            }
        }
        assertEquals(listOf("lock_clock", "lock_glance_clock"), clocks.sorted())
        // The glance clock: in resumeNow's own body (every start and resume).
        val glance = src("lock/GlancePanel.kt")
        assertTrue("ClockFormat.bind(glance.lockGlanceClock)" in body(glance, "private fun resumeNow()"))
        // The time-rule clock: in onStart, before the watch is set up.
        val lock = src("ui/LockActivity.kt")
        assertTrue("ClockFormat.bind(binding.lockClock)" in body(lock, "override fun onStart()").substringBefore("ClockFormat.watch("))
    }

    /** N5: each surface's string gets its time straight from ClockFormat - faces from formatTime,
     * end times ("until") from formatUntil (L1). */
    @Test
    fun `every clock surface formats through ClockFormat`() {
        val surfaces = mapOf(
            "lock/GlancePanel.kt" to """RelativeDay\.TODAY -> ClockFormat\.formatTime\(activity, ms, zone\)""",
            "ui/home/MissedCallText.kt" to """val time = [\w.]*ClockFormat\.formatTime\(context, summary\.lastAtMs, zone\)""",
            "ui/LockActivity.kt" to """getString\(R\.string\.lock_until, ClockFormat\.formatUntil\(this, it\)\)""",
            "ui/HomeActivity.kt" to """getString\(R\.string\.home_window_open, ClockFormat\.formatUntil\(this, until\)\)""",
            "server/WindowRelock.kt" to """val time = [\w.]*ClockFormat\.formatUntil\(app, untilMs\)""",
            "play/PlayRuntime.kt" to """val time = [\w.]*ClockFormat\.formatUntil\(context, untilMs\)""",
            "ui/settings/launcher/SettingsFragmentLauncher.kt" to
                """R\.string\.settings_play_install_mode_active,\s*[\w.]*ClockFormat\.formatUntil\(requireContext\(\), until\)""",
        )
        for ((path, call) in surfaces) assertTrue(path, Regex(call).containsMatchIn(src(path)))
        // The "time" those three build is the one their strings get.
        assertTrue(Regex("""getString\(title, time\)""").containsMatchIn(src("server/WindowRelock.kt")))
        assertTrue(Regex("""notification_install_mode_title, time\)""").containsMatchIn(src("play/PlayRuntime.kt")))
        assertEquals(3, Regex("""R\.string\.missed_when_\w+, (day, )?time\)""").findAll(src("ui/home/MissedCallText.kt")).count())
        // The lock screens follow a change of the format or the system locale while shown (N6).
        for (path in listOf("lock/GlancePanel.kt", "ui/LockActivity.kt")) {
            assertTrue(path, "ClockFormat.watch(" in src(path) && "ClockFormat.unwatch(" in src(path))
        }
        assertTrue("Intent.ACTION_LOCALE_CHANGED" in src("ui/ClockFormat.kt"))
    }

    /** A function's body: from its signature to the first line that closes it at its indent. */
    private fun body(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(signature, start >= 0)
        val indent = code.lastIndexOf('\n', start).let { code.substring(it + 1, start) }
        val end = code.indexOf("\n$indent}", start)
        return code.substring(start, end)
    }

    private fun stripComments(code: String): String =
        code.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")

    private companion object {
        val FORBIDDEN = listOf(
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
            // N5: a string template spelling a clock from .hour/.minute.
            Regex("""\$\{[^}]*\.hour\}[^"\n]*:"""),
        )
    }
}
