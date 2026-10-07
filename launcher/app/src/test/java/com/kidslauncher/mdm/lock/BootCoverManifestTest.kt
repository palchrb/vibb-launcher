package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.calls.DirectBootComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 16b (QA #7-#11): the boot cover's manifest entry and theme, and the rules that keep a
 * direct-boot-aware HOME of ours from becoming a boot lockout (QA #10, the BFU rule).
 */
class BootCoverManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path))

    /** Code only: comments may name what the code must never do. */
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val cover: Element by lazy {
        val nodes = parse("AndroidManifest.xml").getElementsByTagName("activity")
        (0 until nodes.length).map { nodes.item(it) as Element }.first { it.getAttributeNS(ns, "name") == ".lock.BootCoverActivity" }
    }

    @Test
    fun `direct-boot-aware, own process, disabled until armed, a HOME at priority 0`() {
        assertEquals("true", cover.getAttributeNS(ns, "directBootAware"))
        assertEquals(":bootcover", cover.getAttributeNS(ns, "process"))
        assertTrue(cover.getAttributeNS(ns, "process").endsWith(BOOT_COVER_PROCESS_SUFFIX))
        assertEquals("false", cover.getAttributeNS(ns, "enabled"))
        assertEquals("true", cover.getAttributeNS(ns, "exported"))
        assertEquals("\${applicationId}.bootcover", cover.getAttributeNS(ns, "taskAffinity"))
        assertEquals("@style/BootCoverTheme", cover.getAttributeNS(ns, "theme"))
        val filters = cover.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        val filter = filters.item(0) as Element
        assertEquals("0", filter.getAttributeNS(ns, "priority"))
        val categories = filter.getElementsByTagName("category").let { c -> (0 until c.length).map { (c.item(it) as Element).getAttributeNS(ns, "name") } }
        assertEquals(setOf("android.intent.category.HOME", "android.intent.category.DEFAULT"), categories.toSet())
        assertTrue("com.kidslauncher.mdm.lock.BootCoverActivity" in DirectBootComponents.CLASS_NAMES)
    }

    @Test
    fun `a framework NoActionBar theme on Vibb night, nothing from AppCompat (QA 11)`() {
        val styles = parse("res/values/styles.xml").getElementsByTagName("style")
        val theme = (0 until styles.length).map { styles.item(it) as Element }.first { it.getAttribute("name") == "BootCoverTheme" }
        assertEquals("@android:style/Theme.Material.NoActionBar", theme.getAttribute("parent"))
        val items = theme.getElementsByTagName("item").let { i -> (0 until i.length).associate { (i.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } } }
        assertEquals("@color/kid_ground", items["android:windowBackground"])
        assertTrue(file("res/values/colors_kid.xml").readText().contains("<color name=\"kid_ground\">#0C0C14</color>"))
        assertTrue(file("res/drawable/splash_vibb_breathe.xml").readText().contains("<animated-vector"))
    }

    @Test
    fun `the cover reads no CE storage, uses no native code, starts no lock task or service`() {
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertTrue("a plain framework Activity", activity.contains("class BootCoverActivity : Activity()"))
        for (banned in listOf(
            "androidx.", "getDefaultSharedPreferences", "LauncherPreferences", "PinLockStore", "CallPolicyStore",
            "PinLockRuntime", "startLockTask", "startService", "startForegroundService", "bindService",
            "System.loadLibrary", "Tsnet", "tsembed", "AppEnforcer", "SyncRunner",
        )) {
            assertFalse(banned, activity.contains(banned))
        }
        // Its own state is device-protected only; the AVD starts in onResume.
        assertTrue(activity.contains("createDeviceProtectedStorageContext()"))
        assertTrue(activity.substringAfter("override fun onResume()").substringBefore("\n    }").contains("logo?.start()"))
        // Every SharedPreferences the cover touches comes from the device-protected context.
        assertEquals(1, Regex("getSharedPreferences\\(").findAll(activity).count())
    }

    @Test
    fun `Application onCreate does nothing in the cover's process (QA 10)`() {
        val app = code("java/com/kidslauncher/mdm/Application.kt")
        val onCreate = app.substringAfter("override fun onCreate()")
        val guard = onCreate.indexOf("isBootCoverProcess(getProcessName())) return")
        assertTrue(guard > 0)
        for (later in listOf("instance = this", "CallPolicyStore.refresh", "setDefaultUncaughtExceptionHandler", "BootClock.init")) {
            assertTrue(later, onCreate.indexOf(later) > guard)
        }
    }
}
