package com.kidslauncher.mdm.ui.kidsettings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 111: Kid Settings is a task of its own, as Kalender's, Meldinger's and the phone book's - its four sub-pages
 * share the `.kidsettings` affinity, none of the five is kept out of Recents, one intent opens it, and its window is
 * opaque on the plain night ground (the user's Q1: path (b), no copy of the wallpaper).
 */
class KidSettingsTaskTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path))

    private fun elements(path: String, tag: String): List<Element> =
        parse(path).getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private val five = setOf(
        ".ui.kidsettings.KidSettingsActivity",
        ".ui.quickcontrols.WifiNetworksActivity", ".ui.quickcontrols.BluetoothDevicesActivity",
        ".ui.kidsettings.KidSoundPickerActivity", ".ui.kidsettings.ShortcutsPickerActivity",
    )

    @Test
    fun `the five share their own task, stay in recents and are not exported`() {
        val activities = elements("AndroidManifest.xml", "activity")
        val task = activities.filter { it.getAttributeNS(ns, "taskAffinity") == "\${applicationId}.kidsettings" }
        assertEquals(five, task.map { it.getAttributeNS(ns, "name") }.toSet())
        for (activity in task) {
            val name = activity.getAttributeNS(ns, "name")
            assertFalse(name, activity.hasAttributeNS(ns, "excludeFromRecents"))
            assertEquals(name, "false", activity.getAttributeNS(ns, "exported"))
            assertEquals("$name has no filter", 0, activity.getElementsByTagName("intent-filter").length)
            assertFalse(name, activity.hasAttributeNS(ns, "directBootAware"))
            assertFalse(name, activity.hasAttributeNS(ns, "clearTaskOnLaunch"))
        }
        val root = task.single { it.getAttributeNS(ns, "name") == ".ui.kidsettings.KidSettingsActivity" }
        assertEquals("singleTop", root.getAttributeNS(ns, "launchMode"))
        assertEquals("@style/KidSettingsTheme", root.getAttributeNS(ns, "theme"))
        // The sub-pages are plain pages above it (one class each, the applied look's KidPageTheme; PageManifestTest).
        for (page in task - root) assertEquals(page.getAttributeNS(ns, "name"), "", page.getAttributeNS(ns, "launchMode"))
        // No alias stands in for any of them.
        assertTrue(elements("AndroidManifest.xml", "activity-alias").none { it.getAttributeNS(ns, "targetActivity") in five })
    }

    /** Path (b): opaque, so Android runs the task animation and not the wallpaper-intra one; the window, the splash and
     * the layout's root are one night colour, so a starting window already matches; bars as the night page's. */
    @Test
    fun `its window is opaque on the night ground`() {
        val style = elements("res/values/styles.xml", "style").single { it.getAttribute("name") == "KidSettingsTheme" }
        assertEquals("launcherBaseTheme", style.getAttribute("parent"))
        val items = style.getElementsByTagName("item").let { nodes ->
            (0 until nodes.length).associate { (nodes.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent.trim() } }
        }
        assertEquals(
            mapOf(
                "android:windowBackground" to "@color/kid_ground",
                "android:windowSplashScreenBackground" to "@color/kid_ground",
                "android:windowLightStatusBar" to "false",
                "android:windowLightNavigationBar" to "false",
                "android:enforceNavigationBarContrast" to "true",
                "android:statusBarColor" to "@android:color/transparent",
                "android:navigationBarColor" to "@android:color/transparent",
            ),
            items,
        )
        // kid_ground has one value in every configuration, so the splash is the same night in either phone theme.
        val res = file("res")
        val qualified = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") }
            .flatMap { it.walk().filter { f -> f.isFile && f.name.endsWith(".xml") }.toList() }
        assertTrue(qualified.none { it.readText().contains("<color name=\"kid_ground\"") })
        assertTrue(qualified.none { it.readText().contains("<style name=\"KidSettingsTheme\"") })
        val layout = elements("res/layout/activity_kid_settings.xml", "ScrollView").first()
        assertEquals("@color/kid_ground", layout.getAttributeNS(ns, "background"))
    }

    /** One builder (the phone book's code QA M1): the component and `NEW_TASK`, nothing a filter compares, never
     * `CLEAR_TOP` - a task already there comes back as it was, and no start can stack a second Kid Settings. */
    @Test
    fun `one intent opens it, and nothing else names the screen`() {
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, KidSettingsActivity.LAUNCH_FLAGS)
        assertEquals(0, KidSettingsActivity.LAUNCH_FLAGS and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val java = file("java/com/kidslauncher/mdm")
        val code = java.walk().filter { it.isFile && it.name.endsWith(".kt") }.associate {
            it.relativeTo(java).path to it.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\\n]*"), "")
        }
        val builder = code.getValue("ui/kidsettings/KidSettingsActivity.kt").substringAfter("fun intent(context: Context): Intent =")
            .substringBefore("\n\n")
        assertEquals("Intent(context, KidSettingsActivity::class.java).addFlags(LAUNCH_FLAGS)", builder.trim())
        // The class is named in the builder and the language's task rule alone; the tile and the swipe use the builder.
        val naming = code.filterValues { it.contains("KidSettingsActivity::class") }.keys
        assertEquals(setOf("ui/kidsettings/KidSettingsActivity.kt", "ui/LauncherLocale.kt"), naming)
        val callers = code.filterValues { it.contains("KidSettingsActivity.intent(") }.keys
        assertEquals(setOf("ui/HomeActivity.kt", "ui/home/HomeGridAdapter.kt"), callers)
        // Nothing forwards into the root: it takes no intent of its own.
        val screen = code.getValue("ui/kidsettings/KidSettingsActivity.kt")
        assertFalse(screen.contains("onNewIntent"))
        assertFalse(screen.contains("getStringExtra") || screen.contains("intent.action") || screen.contains("intent.data"))
    }
}
