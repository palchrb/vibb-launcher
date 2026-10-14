package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.server.VPN_SESSION
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
 * and nb) and Android's VPN settings and dialog (the VPN session). The package, the namespace and
 * the release asset keep their old names.
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
    fun `the anchor's notification and the VPN carry the name`() {
        assertEquals("Vibb One", string("src/main/res/values/strings.xml", "notification_listener_title"))
        assertEquals("Vibb One", string("src/main/res/values-nb/strings.xml", "notification_listener_title"))
        assertEquals("Vibb One filter", VPN_SESSION)
        val vpn = file("src/main/java/com/kidslauncher/mdm/server/KidVpnService.kt").readText()
        assertTrue(".setSession(VPN_SESSION)" in vpn)
    }

    @Test
    fun `no old name is left in the launcher's resources or sources`() {
        val old = listOf("Kids Launcher", "Kids Device MDM", "Kid Phone Filter")
        val roots = listOf("src/main/res", "src/debug/res", "src/main/java", "src/main/AndroidManifest.xml")
        for (root in roots) {
            file(root).walkTopDown().filter { it.isFile }.forEach { f ->
                val text = f.readText()
                for (name in old) assertFalse("$name in ${f.path}", name in text)
            }
        }
    }
}
