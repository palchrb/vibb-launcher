package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The gear bug (2026-11-16, hardening.md "SystemUI's Settings links while Settings is hidden"): the sink for the
 * Settings pages SystemUI starts from the shade. Its filter is exactly [SettingsSink.ACTIONS] at priority -1 (a visible
 * Settings wins), it has no look and no task of its own in Recents, and its code only finishes.
 */
class SettingsSinkTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private val sink: Element by lazy {
        val nodes = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file("AndroidManifest.xml")).getElementsByTagName("activity")
        (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttributeNS(ns, "name") == ".server.SettingsSinkActivity" }
    }

    private fun children(parent: Element, tag: String) =
        parent.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, "name") } }

    @Test
    fun `the filter is exactly the sink's actions, at priority -1, category DEFAULT`() {
        val filters = sink.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        val filter = filters.item(0) as Element
        assertEquals("-1", filter.getAttributeNS(ns, "priority"))
        assertEquals(SettingsSink.ACTIONS, children(filter, "action"))
        assertEquals(listOf("android.intent.category.DEFAULT"), children(filter, "category"))
        assertEquals(0, filter.getElementsByTagName("data").length)
        assertEquals(SettingsSink.ACTIONS.size, SettingsSink.ACTIONS.toSet().size)
        assertTrue(SettingsSink.ACTIONS.all { it.startsWith("android.settings.") })
    }

    @Test
    fun `no look, its own task, not in Recents, startable by SystemUI`() {
        for ((attribute, value) in listOf(
            "theme" to "@android:style/Theme.NoDisplay", "exported" to "true", "noHistory" to "true",
            "excludeFromRecents" to "true", "taskAffinity" to "",
        )) {
            assertTrue(attribute, sink.hasAttributeNS(ns, attribute))
            assertEquals(attribute, value, sink.getAttributeNS(ns, attribute))
        }
        assertFalse(sink.hasAttributeNS(ns, "permission"))
    }

    @Test
    fun `the code only finishes - it opens nothing and reads nothing from the intent`() {
        val code = file("java/com/kidslauncher/mdm/server/SettingsSinkActivity.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
        val onCreate = code.substringAfter("override fun onCreate(").substringBefore("\n    }")
        assertTrue(onCreate.trimEnd().endsWith("finish()"))
        for (banned in listOf(
            "startActivit", "sendBroadcast", "startService", "startForegroundService", "bindService", "Extra", "extras",
            ".data", "dataString", "getParcelable", "setResult", "PendingIntent", "onResume", "onNewIntent",
        )) {
            assertFalse(banned, code.contains(banned))
        }
    }

    @Test
    fun `the log names only the sink's own actions`() {
        for (action in SettingsSink.ACTIONS) assertEquals(action, SettingsSink.logName(action))
        assertEquals("another action", SettingsSink.logName(null))
        assertEquals("another action", SettingsSink.logName("android.settings.WIFI_SETTINGS"))
        assertEquals("another action", SettingsSink.logName("x\ny"))
    }
}
