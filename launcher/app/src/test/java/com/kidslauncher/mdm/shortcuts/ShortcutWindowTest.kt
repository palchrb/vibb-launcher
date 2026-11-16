package com.kidslauncher.mdm.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Snarveier's Flymodus window and header (fix round 2026-11-16): the window is one pixel and claims no bars (it drew
 * black bars, and a full-screen dark window would turn the bar icons white over a light app), its theme keeps the bars
 * transparent should it ever be laid out full screen, and the Vibb notification's header is the app's name alone.
 */
class ShortcutWindowTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path))

    @Test
    fun `the Flymodus window's theme has transparent bars drawn edge to edge`() {
        val nodes = parse("AndroidManifest.xml").getElementsByTagName("activity")
        val window = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".shortcuts.ShortcutAirplaneActivity" }
        assertEquals("@style/ShortcutWindowTheme", window.getAttributeNS(ns, "theme"))

        val styles = parse("res/values/styles.xml").getElementsByTagName("style")
        val theme = (0 until styles.length).map { styles.item(it) as Element }.single { it.getAttribute("name") == "ShortcutWindowTheme" }
        assertEquals("@android:style/Theme.Material.NoActionBar", theme.getAttribute("parent"))
        val items = theme.getElementsByTagName("item").let { i ->
            (0 until i.length).associate { (i.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } }
        }
        for ((name, value) in listOf(
            "android:windowIsTranslucent" to "true",
            "android:windowBackground" to "@android:color/transparent",
            "android:backgroundDimEnabled" to "false",
            "android:windowDrawsSystemBarBackgrounds" to "true",
            "android:statusBarColor" to "@android:color/transparent",
            "android:navigationBarColor" to "@android:color/transparent",
            "android:enforceStatusBarContrast" to "false",
            "android:enforceNavigationBarContrast" to "false",
            "android:windowLayoutInDisplayCutoutMode" to "always",
        )) {
            assertEquals(name, value, items[name])
        }
        // No qualified copy of the theme replaces it on some phones (in any XML file of a qualified values folder).
        val qualified = file("res").listFiles().orEmpty().filter { it.name.startsWith("values-") }
            .flatMap { it.listFiles().orEmpty().filter { f -> f.name.endsWith(".xml") } }
        assertTrue(qualified.isNotEmpty())
        for (copy in qualified) assertFalse(copy.path, copy.readText().contains("\"ShortcutWindowTheme\""))
    }

    @Test
    fun `the window is one pixel, laid out in onCreate before anything is drawn`() {
        val code = file("java/com/kidslauncher/mdm/shortcuts/ShortcutAirplaneActivity.kt").readText()
        val claim = code.substringAfter("private fun claimNoBars()").substringBefore("\n    }")
        assertTrue(claim.contains("window.setLayout(1, 1)"))
        assertTrue(claim.contains("window.setGravity(Gravity.TOP or Gravity.START)"))
        val onCreate = code.substringAfter("override fun onCreate(").substringBefore("\n    }")
        assertTrue(onCreate.indexOf("super.onCreate") in 0 until onCreate.indexOf("claimNoBars()"))
        // Still a focusable window: the route waits for its first focus.
        assertFalse(code.contains("FLAG_NOT_FOCUSABLE"))
    }

    @Test
    fun `the Vibb notification's header reads only Vibb One`() {
        val code = file("java/com/kidslauncher/mdm/shortcuts/ShortcutsNotification.kt").readText()
        assertFalse(code.contains("setSubText"))
        for (strings in listOf("res/values/strings.xml", "res/values-nb/strings.xml")) {
            assertFalse(strings, file(strings).readText().contains("shortcuts_subtext"))
        }
    }
}
