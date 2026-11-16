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
 * Settings pages SystemUI starts from the shade. Its filters are exactly [SettingsSink.ACTIONS] and, with a `package:`
 * URI, [SettingsSink.PACKAGE_ACTIONS], at priority -999 (a visible Settings wins, a tie is unlikely); it has no look,
 * no task of its own in Recents, nothing that reaches past the keyguard or the lock task, and its code only finishes.
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

    private fun filters() = sink.getElementsByTagName("intent-filter").let { f -> (0 until f.length).map { f.item(it) as Element } }

    @Test
    fun `two filters - the plain actions and the package actions - at priority -999, category DEFAULT`() {
        val (plain, withPackage) = filters().also { assertEquals(2, it.size) }
        for (filter in listOf(plain, withPackage)) {
            assertEquals(SettingsSink.PRIORITY.toString(), filter.getAttributeNS(ns, "priority"))
            assertEquals(listOf("android.intent.category.DEFAULT"), children(filter, "category"))
        }
        assertEquals(-999, SettingsSink.PRIORITY)
        assertEquals(SettingsSink.ACTIONS, children(plain, "action"))
        assertEquals(0, plain.getElementsByTagName("data").length)
        assertEquals(SettingsSink.PACKAGE_ACTIONS, children(withPackage, "action"))
        val data = withPackage.getElementsByTagName("data")
        assertEquals(1, data.length)
        val only = data.item(0) as Element
        assertEquals(1, only.attributes.length)
        assertEquals("package", only.getAttributeNS(ns, "scheme"))
        val all = SettingsSink.ACTIONS + SettingsSink.PACKAGE_ACTIONS
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.startsWith("android.settings.") })
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
        // Nothing that would put it over the keyguard, keep it alive, move it out of the lock-task rules or the process.
        for (attribute in listOf(
            "permission", "showWhenLocked", "turnScreenOn", "launchMode", "allowTaskReparenting", "lockTaskMode",
            "directBootAware", "process", "documentLaunchMode", "persistableMode",
        )) {
            assertFalse(attribute, sink.hasAttributeNS(ns, attribute))
        }
    }

    @Test
    fun `a plain Activity whose code only finishes - it opens nothing and reads only the action`() {
        val code = file("java/com/kidslauncher/mdm/server/SettingsSinkActivity.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
        // Theme.NoDisplay under an AppCompat base throws in onCreate, in the Device Owner's own process.
        assertTrue(code.contains("\nclass SettingsSinkActivity : Activity() {"))
        assertTrue(code.contains("import android.app.Activity\n"))
        val onCreate = code.substringAfter("override fun onCreate(").substringBefore("\n    }")
        assertTrue(onCreate.trimEnd().endsWith("finish()"))
        // The intent is read once, for its action.
        assertEquals(1, Regex("intent").findAll(code.replace("Intent", "")).count())
        assertTrue(code.contains("intent?.action"))
        for (banned in listOf(
            "startActivit", "sendBroadcast", "startService", "startForegroundService", "bindService", "Extra", "extras",
            "setResult", "PendingIntent", "onResume", "onNewIntent", "getIntent",
        )) {
            assertFalse(banned, code.contains(banned))
        }
    }

    @Test
    fun `the log names only the sink's own actions`() {
        for (action in SettingsSink.ACTIONS + SettingsSink.PACKAGE_ACTIONS) assertEquals(action, SettingsSink.logName(action))
        assertEquals("another action", SettingsSink.logName(null))
        assertEquals("another action", SettingsSink.logName("android.settings.WIFI_SETTINGS"))
        assertEquals("another action", SettingsSink.logName("x\ny"))
    }
}
