package com.kidslauncher.mdm.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Snarveier's Flymodus window and header (fix round 2026-11-16): the window draws transparent bars edge to edge with
 * no window animation (it drew black bars that slid away), and the Vibb notification's header is the app's name alone.
 */
class ShortcutWindowTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path))

    @Test
    fun `the Flymodus window's theme has transparent bars, edge to edge, and no animation`() {
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
            "android:windowAnimationStyle" to "@null",
        )) {
            assertEquals(name, value, items[name])
        }
        // No qualified copy of the theme replaces it on some phones.
        val qualified = file("res").listFiles().orEmpty().filter { it.name.startsWith("values-") }
            .mapNotNull { File(it, "styles.xml").takeIf(File::exists) }
        for (copy in qualified) assertFalse(copy.path, copy.readText().contains("\"ShortcutWindowTheme\""))
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
