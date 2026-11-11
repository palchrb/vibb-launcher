package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Home's breathing splash (design 78 QA N6, 2026-11-09): a `values-v29` copy of `launcherHomeTheme` without the
 * `windowSplashScreen*` items replaced the whole style on every phone, so Home launched with the plain splash. A
 * qualified style replaces its unqualified one whole, so a copy must keep every item; a version qualifier at or below
 * minSdk is always the one used and belongs in `values/`.
 */
class SplashThemeTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private val res = File(main, "res")

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

    /** Every `<style>` in [dir]'s XML files: name -> (parent, items). */
    private fun styles(dir: File): Map<String, Pair<String, Map<String, String>>> {
        val out = HashMap<String, Pair<String, Map<String, String>>>()
        for (file in dir.listFiles { f -> f.extension == "xml" }.orEmpty()) {
            val list = parse(file).getElementsByTagName("style")
            for (i in 0 until list.length) {
                val style = list.item(i) as Element
                val items = style.getElementsByTagName("item").let { n ->
                    (0 until n.length).associate { (n.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent.trim() } }
                }
                out[style.getAttribute("name")] = style.getAttribute("parent") to items
            }
        }
        return out
    }

    private val base = styles(File(res, "values"))

    /** [name]'s value for [item], walking the parents in `values/`. */
    private fun resolve(style: String, item: String): String? {
        var name: String? = style
        while (name != null) {
            val (parent, items) = base[name] ?: return null
            items[item]?.let { return it }
            name = parent.removePrefix("@style/").ifEmpty { name.substringBeforeLast('.', "").ifEmpty { null } }
        }
        return null
    }

    private fun minSdk(): Int {
        val gradle = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.exists() }.readText()
        return Regex("""minSdk\s*=\s*(\d+)""").find(gradle)!!.groupValues[1].toInt()
    }

    @Test
    fun `a qualified style keeps every item of the one it replaces`() {
        val qualified = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
        for (dir in qualified) {
            for ((name, style) in styles(dir)) {
                val kept = base[name] ?: continue
                val dropped = kept.second.keys - style.second.keys
                assertTrue("${dir.name}/$name drops $dropped", dropped.isEmpty())
                // The one exception (design 78 §4.4): the pickers' dialog on a page in Lys is the framework's light
                // dialog, so what the pickers draw that we don't name is light too.
                val parent = if (dir.name == "values-notnight" && name == "VibbPickerDialog") "@android:style/Theme.Material.Light.Dialog" else kept.first
                assertEquals("${dir.name}/$name changes its parent", parent, style.first)
            }
        }
    }

    /** Values only: a file-based qualified directory (`mipmap-anydpi-v26`, the adaptive icon) replaces no style. */
    @Test
    fun `no values version qualifier at or below minSdk`() {
        val min = minSdk()
        val dead = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty().map { it.name }.filter { dir ->
            dir.split('-').any { part -> Regex("""v(\d+)""").matchEntire(part)?.groupValues?.get(1)?.toInt()?.let { it <= min } == true }
        }
        assertTrue("fold into the unqualified directory: $dead", dead.isEmpty())
    }

    @Test
    fun `Home launches with the breathing mark, the lock and the other screens with their own`() {
        val manifest = parse(File(main, "AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity").let { n -> (0 until n.length).map { n.item(it) as Element } }
        val home = activities.first { it.getAttributeNS(ns, "name") == ".ui.HomeActivity" }
        assertEquals("@style/launcherHomeTheme", home.getAttributeNS(ns, "theme"))
        assertEquals("@drawable/splash_vibb_breathe", resolve("launcherHomeTheme", "android:windowSplashScreenAnimatedIcon"))
        assertEquals("@color/vibb_night", resolve("launcherHomeTheme", "android:windowSplashScreenBackground"))
        assertEquals("false", resolve("launcherHomeTheme", "android:enforceNavigationBarContrast"))
        // Design 32: the lock's stand-in before its first frame is black; every other screen the plain night, no icon.
        assertEquals("@android:color/black", resolve("PinLockTheme", "android:windowSplashScreenBackground"))
        assertEquals("@drawable/splash_icon_none", resolve("PinLockTheme", "android:windowSplashScreenAnimatedIcon"))
        assertEquals("@color/kid_ground", resolve("launcherBaseTheme", "android:windowSplashScreenBackground"))
        assertEquals("@drawable/splash_icon_none", resolve("launcherBaseTheme", "android:windowSplashScreenAnimatedIcon"))
    }

    /** Every `<color>` in [dir]'s XML files. */
    private fun colours(dir: File): Map<String, String> =
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            parse(file).getElementsByTagName("color").let { n -> (0 until n.length).map { n.item(it) as Element } }
        }.associate { it.getAttribute("name") to it.textContent.trim() }

    /**
     * Design 78 §12.2 R3: Android draws a page's starting window from its manifest theme under the phone's own night
     * mode, before any code of ours. So each variant's theme says its look in values that have one form in every
     * configuration: the two grounds a literal colour defined in `values/` alone, the three bar items plain booleans.
     */
    @Test
    fun `a page's night and light theme carry the look in literals`() {
        val qualified = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
        assertTrue(qualified.any { it.name == "values-notnight" })
        for ((theme, ground, lightBars) in listOf(Triple("KidPageNightTheme", "kid_ground", false), Triple("KidPageLightTheme", "kid_paper", true))) {
            val (parent, items) = base.getValue(theme)
            assertEquals(theme, "KidPageTheme", parent)
            assertEquals(
                theme,
                mapOf(
                    "android:windowBackground" to "@color/$ground",
                    "android:windowSplashScreenBackground" to "@color/$ground",
                    "android:windowLightStatusBar" to "$lightBars",
                    "android:windowLightNavigationBar" to "$lightBars",
                    "android:enforceNavigationBarContrast" to "${!lightBars}",
                ),
                items,
            )
            assertTrue("$ground is a literal", colours(File(res, "values")).getValue(ground).startsWith("#"))
            for (dir in qualified) {
                assertTrue("${dir.name} has $ground", ground !in colours(dir))
                assertTrue("${dir.name} has $theme", theme !in styles(dir))
            }
        }
        // No splash icon over either ground: the plain one of every other screen.
        assertEquals("@drawable/splash_icon_none", resolve("KidPageLightTheme", "android:windowSplashScreenAnimatedIcon"))
    }
}
