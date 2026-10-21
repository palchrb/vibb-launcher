package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PLAN "Fewer persistent notifications" (2026-10-21): the VPN drops its foreground state once the
 * filter is up and always-on ([vpnHoldsForeground]); the anchor keeps one notification, "Vibb One"
 * with no body. `startForeground` stays the first thing both services' `onCreate` does (the API-34
 * rule, launcher/CLAUDE.md "Non-obvious gotchas").
 */
class QuietNotificationsTest {

    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    private fun source(name: String) = file("src/main/java/com/kidslauncher/mdm/server/$name").readText()

    /** The body of `fun <name>(...) {`, up to its closing brace at the same indent. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val indent = text.substring(text.lastIndexOf('\n', start) + 1, start)
        val open = text.indexOf("{\n", start) + 2
        return text.substring(open, text.indexOf("\n$indent}\n", open))
    }

    private fun statements(body: String) = body.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") }

    @Test
    fun `the VPN holds its foreground state unless the filter is up and always-on`() {
        assertFalse(vpnHoldsForeground(tunnelUp = true, alwaysOnIsOurs = true))
        // The filter down (establish failed, the read loop died): the notification comes back.
        assertTrue(vpnHoldsForeground(tunnelUp = false, alwaysOnIsOurs = true))
        // Up but not the always-on VPN yet (first launch before the first sync).
        assertTrue(vpnHoldsForeground(tunnelUp = true, alwaysOnIsOurs = false))
        assertTrue(vpnHoldsForeground(tunnelUp = false, alwaysOnIsOurs = false))
    }

    @Test
    fun `startForeground stays first in both services' onCreate`() {
        for (name in listOf("KidVpnService.kt", "CommandListenerService.kt")) {
            val lines = statements(body(source(name), "override fun onCreate()"))
            assertEquals(name, "super.onCreate()", lines[0])
            assertTrue("$name: ${lines[1]}", lines[1].startsWith("startForeground("))
        }
    }

    @Test
    fun `the VPN drops its foreground state only in settleForeground, and answers a foreground start first`() {
        val vpn = source("KidVpnService.kt")
        assertEquals(1, Regex("""stopForeground\(""").findAll(vpn).count())
        val settle = body(vpn, "private fun settleForeground()")
        assertTrue("stopForeground(STOP_FOREGROUND_REMOVE)" in settle)
        assertTrue("vpnHoldsForeground(" in settle)
        // onStartCommand's first statement answers a startForegroundService start.
        val onStart = statements(body(vpn, "override fun onStartCommand("))
        assertEquals("if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) enterForeground()", onStart[0])
        // start()'s only startForegroundService carries the extra.
        val start = body(vpn, "fun start(context: Context)")
        assertEquals(1, Regex("""startForegroundService\(""").findAll(vpn).count())
        assertTrue("startForegroundService(context, intent.putExtra(EXTRA_FOREGROUND, true))" in start)
    }

    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
        val list = doc.getElementsByTagName("string")
        return (0 until list.length).map { list.item(it) as org.w3c.dom.Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun `the anchor's notification is Vibb One with no body, in en and nb`() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            val s = strings(path)
            assertEquals(path, "Vibb One", s["notification_listener_title"])
            assertFalse(path, "notification_listener_text" in s)
        }
        val build = body(source("CommandListenerService.kt"), "private fun buildNotification()")
        assertTrue(".setContentTitle(getString(R.string.notification_listener_title))" in build)
        assertFalse("setContentText" in build)
        assertTrue("NOTIFICATION_CHANNEL_LISTENER" in build)
        assertTrue("startForeground(COMMAND_LISTENER_NOTIFICATION_ID" in source("CommandListenerService.kt"))
    }
}
