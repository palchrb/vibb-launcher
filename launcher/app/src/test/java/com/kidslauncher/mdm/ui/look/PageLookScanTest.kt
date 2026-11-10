package com.kidslauncher.mdm.ui.look

import com.kidslauncher.mdm.calls.DirectBootComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 78 §5.3 and §6, as source scans: who touches the kid's look (stored in CE prefs, so never before the first
 * unlock), and that every screen that is not a page is night as a constant.
 */
class PageLookScanTest {
    private val java = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val sources: Map<String, String> by lazy {
        java.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .associate { it.relativeTo(java).path.replace(File.separatorChar, '/') to code(it) }
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
        // Home: once, in onResume, after the lock's dark return.
        val home = sources.getValue("$root/ui/HomeActivity.kt")
        assertEquals(1, Regex("""\bPageLook\b""").findAll(home).count())
        val resume = home.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue(resume.indexOf("if (darkUnderLock())") in 0 until resume.indexOf("PageLook.onHomeResumed(this)"))
        // Kid Settings: the row is shown in debug builds only (step 1), and a release build draws nothing of it.
        val settings = sources.getValue("$root/ui/kidsettings/KidSettingsActivity.kt")
        assertTrue(settings.contains("if (BuildConfig.DEBUG) binding.kidSettingsLookSection.visibility = View.VISIBLE"))
        assertEquals(1, Regex("""kidSettingsLookSection\.visibility\s*=""").findAll(settings).count())
        val render = settings.substringAfter("private fun renderLook() {").substringBefore("\n    }\n")
        assertTrue(render.trimStart().startsWith("if (binding.kidSettingsLookSection.visibility != View.VISIBLE) return"))
        val layout = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
            .resolve("layout/activity_kid_settings.xml").readText()
        val section = layout.substringAfter("android:id=\"@+id/kid_settings_look_section\"").substringBefore(">")
        assertTrue(section.contains("android:visibility=\"gone\""))
    }

    @Test
    fun `every activity that is not a page is night as a constant, set before its base context`() {
        // AppCompat follows the app's night mode unless told otherwise; a plain Activity has a framework theme
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
        // Nothing else sets a night mode: no setDefaultNightMode, and the app's own only in PageLook.
        assertTrue(sources.values.none { it.contains("setDefaultNightMode") })
        assertEquals(listOf("$root/ui/look/PageLook.kt"), sources.filterValues { it.contains("setApplicationNightMode") }.keys.toList())
    }
}
