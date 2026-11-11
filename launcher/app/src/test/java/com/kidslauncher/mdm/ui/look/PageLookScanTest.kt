package com.kidslauncher.mdm.ui.look

import com.kidslauncher.mdm.calls.DirectBootComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 78 §5.3, §6 and §12.2, as source scans: who touches the kid's look (stored in CE prefs, so never before the
 * first unlock), that every screen that is not a page is night as a constant, that nothing asks the system for a
 * night mode (R1), and that a page's class is chosen in one place and never leaves the process (R5, R7).
 */
class PageLookScanTest {
    private val java = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun sourcesOf(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .associate { it.relativeTo(dir).path.replace(File.separatorChar, '/') to code(it) }

    private val sources: Map<String, String> by lazy { sourcesOf(java) }

    /** The main source set with the debug one and the release one - what either build compiles in. */
    private val shipped: Map<String, String> by lazy {
        sources + listOf("debug", "release").flatMap { variant ->
            val dir = File(java, "../../$variant/java")
            assertTrue(variant, dir.isDirectory)
            sourcesOf(dir).map { "$variant:${it.key}" to it.value }
        }
    }

    @Test
    fun `the look is touched only from the unlocked start, the pages, kid Settings and Home's resume`() {
        val root = "com/kidslauncher/mdm"
        // The prefs file has one reader and writer.
        assertEquals(listOf("$root/ui/look/PageLook.kt"), sources.filterValues { it.contains("\"kid_look\"") }.keys.toList())
        val callers = sources.filterValues { Regex("""\b(PageLook|LookMode)\b""").containsMatchIn(it) }.keys
            .filterNot { it.startsWith("$root/ui/look/") }.toSet()
        // Home only runs a change that had to wait for a page to leave the screen (PageLook.onHomeResumed); it is
        // not direct-boot-aware, so it exists only after the unlocked start.
        assertEquals(setOf("$root/Application.kt", "$root/ui/kidsettings/KidSettingsActivity.kt", "$root/ui/HomeActivity.kt"), callers)
        // None of them runs before the first unlock.
        for (caller in callers + "$root/ui/look/PageLook.kt" + "$root/ui/look/KidPageActivity.kt") {
            val className = caller.removeSuffix(".kt").replace('/', '.')
            assertTrue(className, className !in DirectBootComponents.CLASS_NAMES)
        }
        // The application: once, inside initUnlocked, guarded on its own.
        val app = sources.getValue("$root/Application.kt")
        assertEquals(1, Regex("""\bPageLook\b""").findAll(app).count())
        val unlocked = app.substringAfter("private fun initUnlocked() {").substringBefore("\n    }\n")
        assertTrue(unlocked.contains("com.kidslauncher.mdm.ui.look.PageLook.atUnlockedStart(this)"))
        // The launcher's language is still asked for there, once, before the rest of the setup - as built, its own
        // item now (§12.9); its order against the look no longer matters, the look asks nothing of the system.
        assertEquals(1, app.split("LauncherLocales.atUnlockedStart(").size - 1)
        val language = unlocked.indexOf("com.kidslauncher.mdm.ui.LauncherLocales.atUnlockedStart(this)")
        assertTrue(language in 0 until unlocked.indexOf("val setupStart"))
        // Home: once, in onResume, after the lock's dark return.
        val home = sources.getValue("$root/ui/HomeActivity.kt")
        assertEquals(1, Regex("""\bPageLook\b""").findAll(home).count())
        val resume = home.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue(resume.indexOf("if (darkUnderLock())") in 0 until resume.indexOf("PageLook.onHomeResumed(this)"))
        // Kid Settings: the row is shown, and listens, in debug builds only (step 1); a release build draws nothing
        // of it.
        val settings = sources.getValue("$root/ui/kidsettings/KidSettingsActivity.kt")
        val debug = settings.substringAfter("if (BuildConfig.DEBUG) {", "").substringBefore("\n        }\n")
        assertTrue(debug.trimStart().startsWith("binding.kidSettingsLookSection.visibility = View.VISIBLE"))
        assertTrue(debug.contains("PageLook.choose(this, mode)"))
        assertEquals(1, Regex("""kidSettingsLookSection\.visibility\s*=""").findAll(settings).count())
        assertEquals(1, Regex("""PageLook\.choose\(""").findAll(settings).count())
        val render = settings.substringAfter("private fun renderLook() {").substringBefore("\n    }\n")
        assertTrue(render.trimStart().startsWith("if (binding.kidSettingsLookSection.visibility != View.VISIBLE) return"))
        val layout = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
            .resolve("layout/activity_kid_settings.xml").readText()
        val section = layout.substringAfter("android:id=\"@+id/kid_settings_look_section\"").substringBefore(">")
        assertTrue(section.contains("android:visibility=\"gone\""))
    }

    @Test
    fun `a page takes its look, its colours and its place in the registry from its base class`() {
        val page = sources.getValue("com/kidslauncher/mdm/ui/look/KidPageActivity.kt")
        // The look of the class, not the applied one: a window and its content never disagree (§12.2 R4).
        assertTrue(page.contains("override fun lookNightMode(): Int = PageLook.pageNightMode(javaClass)"))
        // The page colours over the launcher's theme, forced: without `true` the theme's own night values stay.
        assertTrue(page.contains("override fun getTheme(): Resources.Theme? = super.getTheme()?.also { it.applyStyle(R.style.PageColors, true) }"))
        // The registry of live pages (what a change finishes) and the started count (when a change may run).
        val calls = listOf(
            "onCreate(savedInstanceState: Bundle?)" to "PageLook.pageCreated(this)",
            "onStart()" to "PageLook.pageTaskStarted()",
            "onStop()" to "PageLook.pageTaskStopped()",
            "onDestroy()" to "PageLook.pageDestroyed(this)",
        )
        for ((method, call) in calls) {
            val body = page.substringAfter("override fun $method {", "").substringBefore("\n    }\n")
            assertTrue("$method: $call", body.contains(call))
            assertEquals(call, 1, page.split(call).size - 1)
        }
        // Kid Settings' preview is drawn as a page in a look: the same colours, forced the same way.
        val look = sources.getValue("com/kidslauncher/mdm/ui/look/PageLook.kt")
        assertTrue(look.contains("wrapped.theme.applyStyle(R.style.PageColors, true)"))
        // §12.2 R6's check: a page of the other look's class closes its task before it sets any content - asked
        // once the activity exists, and each page's own onCreate stops there.
        val create = page.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    }\n")
        assertTrue(create.indexOf("super.onCreate(savedInstanceState)") in 0 until create.indexOf("PageLook.closeIfOtherLook(this)"))
        for (name in listOf("DayActivity", "EventActivity", "EventEditActivity")) {
            val own = sources.getValue("com/kidslauncher/mdm/ui/day/$name.kt")
                .substringAfter("override fun onCreate(savedInstanceState: Bundle?) {")
            assertTrue(name, own.trimStart().startsWith("super.onCreate(savedInstanceState)\n        if (isFinishing) return"))
        }
    }

    @Test
    fun `every activity that is not a page is night as a constant, set before its base context`() {
        // AppCompat follows the phone's theme unless told otherwise; a plain Activity has a framework theme
        // (PageManifestTest). The three classes that extend AppCompatActivity directly:
        val direct = sources.filterValues { Regex(""":\s*AppCompatActivity\(\)""").containsMatchIn(it) }.keys
        val root = "com/kidslauncher/mdm"
        assertEquals(setOf("$root/ui/UIObject.kt", "$root/lock/PinLockActivity.kt", "$root/calls/InCallActivity.kt"), direct.toSet())
        fun attach(file: String): String {
            val body = sources.getValue(file).substringAfter("override fun attachBaseContext(newBase: Context) {", "").substringBefore("\n    }\n")
            assertTrue(file, body.isNotEmpty())
            assertTrue(file, body.indexOf("delegate.localNightMode = ") in 0 until body.indexOf("super.attachBaseContext(newBase)"))
            return body
        }
        for (file in listOf("$root/lock/PinLockActivity.kt", "$root/calls/InCallActivity.kt")) {
            assertTrue(file, attach(file).contains("delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES"))
        }
        // UIObjectActivity's constant is night; only the page base class says otherwise, and only pages extend it.
        assertTrue(attach("$root/ui/UIObject.kt").contains("delegate.localNightMode = lookNightMode()"))
        assertTrue(sources.getValue("$root/ui/UIObject.kt").contains("protected open fun lookNightMode(): Int = AppCompatDelegate.MODE_NIGHT_YES"))
        val overriding = sources.filterValues { it.contains("override fun lookNightMode()") }.keys
        assertEquals(setOf("$root/ui/look/KidPageActivity.kt"), overriding.toSet())
        val pages = sources.filterValues { Regex(""":\s*KidPageActivity\(\)""").containsMatchIn(it) }.keys
        assertEquals(setOf("$root/ui/day/DayActivity.kt", "$root/ui/day/EventActivity.kt", "$root/ui/day/EventEditActivity.kt"), pages.toSet())
        // Nothing else sets a night mode: no setDefaultNightMode, and no night mode of the app's (§12.2 R1) - the
        // system keeps that one per package and writes it into the same record as the launcher's language.
        assertEquals(emptyList<String>(), shipped.filterValues { it.contains("setDefaultNightMode") || it.contains("setApplicationNightMode") }.keys.toList())
    }

    @Test
    fun `a page's class is named in the table alone, and a start's intent goes nowhere but into the start`() {
        val root = "com/kidslauncher/mdm"
        // R5: neither a night class nor a twin as a class literal anywhere else - the intent builders and the two
        // task rules ask the table. (`is DayActivity` holds for a twin and stays allowed.)
        val literal = Regex("""\b(Light)?(Day|Event|EventEdit)Activity::class""")
        assertEquals(listOf("$root/ui/look/PageKind.kt"), shipped.filterValues { literal.containsMatchIn(it) }.keys.toList())
        assertEquals(6, literal.findAll(shipped.getValue("$root/ui/look/PageKind.kt")).count())
        // The table's users: the three builders, the look's own task rule and the language's.
        val users = shipped.filterValues { Regex("""\bPageKind\b""").containsMatchIn(it) }.keys.filterNot { it.startsWith("$root/ui/look/") }
        assertEquals(
            setOf("$root/ui/day/DayActivity.kt", "$root/ui/day/EventActivity.kt", "$root/ui/day/EventEditActivity.kt", "$root/ui/LauncherLocale.kt"),
            users.toSet(),
        )
        assertTrue(shipped.getValue("$root/ui/look/PageLook.kt").contains("fun closesTask(rootClassName: String?): Boolean = PageKind.DAY.has(rootClassName)"))
        assertTrue(shipped.getValue("$root/ui/LauncherLocale.kt").contains("PageKind.DAY.has(rootClassName) || rootClassName == MessagesActivity::class.java.name"))
        // One chooser: each builder names the applied look's class, and nothing else asks for one.
        val chosen = mapOf(
            "$root/ui/day/DayActivity.kt" to listOf("Intent(context, PageKind.DAY.current())"),
            "$root/ui/day/EventActivity.kt" to listOf("Intent(context, PageKind.EVENT.current())"),
            "$root/ui/day/EventEditActivity.kt" to listOf("Intent(context, PageKind.EVENT_EDIT.current())", "Intent(context, PageKind.EVENT_EDIT.current())"),
        )
        val uses = Regex("""Intent\(context, PageKind\.[A-Z_]+\.current\(\)\)|PageKind\.DAY\.has\(rootClassName\)|import com\.kidslauncher\.mdm\.ui\.look\.PageKind\n""")
        for ((file, text) in shipped.filterKeys { !it.startsWith("$root/ui/look/") }) {
            assertEquals(file, chosen[file].orEmpty(), uses.findAll(text).map { it.value }.filter { it.startsWith("Intent(") }.toList())
            assertTrue(file, "PageKind" !in uses.replace(text, ""))
        }
        // R7: a builder's intent is handed straight to startActivity, every time - never kept, never wrapped in a
        // PendingIntent, never given to a notification or to the system as a preferred activity. So no variant's
        // name outlives the look it was built in, outside the process.
        val builder = """(DayActivity\.intent|EventActivity\.intent|EventEditActivity\.newIntent|EventEditActivity\.editIntent)\("""
        var starts = 0
        for ((file, text) in shipped) {
            val built = Regex("\\b$builder").findAll(text).count()
            val started = Regex("\\bstartActivity\\((com\\.kidslauncher\\.mdm\\.ui\\.day\\.)?$builder").findAll(text).count()
            assertEquals(file, built, started)
            starts += started
        }
        assertEquals(6, starts)
    }
}
