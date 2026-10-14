package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 34 P11: the launcher is called "Vibb One" (debug "Vibb One (debug)") wherever Android
 * shows its name - Settings › Apps, permission dialogs, the Home-app picker, notification headers
 * and Recents (`app_name`), the anchor's ongoing notification (`notification_listener_title`, en
 * and nb) and Android's VPN settings and dialog (the VPN session, `vpn_session`, en and nb). The
 * package, the namespace and the release asset keep their old names.
 */
class VibbOneNameTest {

    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    private fun string(path: String, name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
        val strings = doc.getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == name }
            .textContent
    }

    @Test
    fun `the app label is Vibb One, the debug build's Vibb One (debug)`() {
        assertEquals("Vibb One", string("src/main/res/values/donottranslate.xml", "app_name"))
        assertEquals("Vibb One (debug)", string("src/debug/res/values/donottranslate.xml", "app_name"))
    }

    @Test
    fun `the anchor's notification and the VPN carry the name, in en and nb`() {
        assertEquals("Vibb One", string("src/main/res/values/strings.xml", "notification_listener_title"))
        assertEquals("Vibb One", string("src/main/res/values-nb/strings.xml", "notification_listener_title"))
        assertEquals("Vibb One filter", string("src/main/res/values/strings.xml", "vpn_session"))
        assertEquals("Vibb One-filter", string("src/main/res/values-nb/strings.xml", "vpn_session"))
        val vpn = file("src/main/java/com/kidslauncher/mdm/server/KidVpnService.kt").readText()
        assertTrue(".setSession(getString(R.string.vpn_session))" in vpn)
        assertEquals(1, Regex("""\.setSession\(""").findAll(vpn).count())
    }

    @Test
    fun `no old name is left in the launcher's main or debug sources`() {
        val old = listOf("Kids Launcher", "Kids Device MDM", "Kid Phone Filter")
        // All of src/debug (java, manifest, res) - qa-34-s12-code L2.
        val roots = listOf("src/main/res", "src/main/java", "src/main/AndroidManifest.xml", "src/debug")
        var files = 0
        for (root in roots) {
            file(root).walkTopDown().filter { it.isFile }.forEach { f ->
                files++
                val text = f.readText()
                for (name in old) assertFalse("$name in ${f.path}", name in text)
            }
        }
        assertTrue(file("src/debug/AndroidManifest.xml").isFile)
        assertTrue(file("src/debug/java").isDirectory)
        assertTrue(files > 100)
    }
}
