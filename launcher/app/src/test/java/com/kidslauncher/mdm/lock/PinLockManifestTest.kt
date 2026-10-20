package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Handy step 10 (QA 10 #12 and the acceptance criteria): the lock screen's manifest entry and
 * the backup rules. */
class PinLockManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path))

    private fun activity(name: String): Element {
        val nodes = parse("AndroidManifest.xml").getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }.first { it.getAttributeNS(ns, "name") == name }
    }

    @Test
    fun `the lock is its own portrait task, out of recents, and not direct-boot-aware`() {
        val lock = activity(".lock.PinLockActivity")
        assertEquals("", lock.getAttributeNS(ns, "directBootAware"))
        assertEquals("portrait", lock.getAttributeNS(ns, "screenOrientation"))
        assertEquals("true", lock.getAttributeNS(ns, "excludeFromRecents"))
        assertEquals("false", lock.getAttributeNS(ns, "exported"))
        assertEquals("singleTask", lock.getAttributeNS(ns, "launchMode"))
        val handled = lock.getAttributeNS(ns, "configChanges").split('|').toSet()
        assertTrue(
            "no recreation for a SIM swap, language, font or density change (qa-10-code 6)",
            handled.containsAll(setOf("mcc", "mnc", "locale", "fontScale", "density", "layoutDirection", "orientation", "screenSize", "uiMode")),
        )
        assertEquals("\${applicationId}.pinlock", lock.getAttributeNS(ns, "taskAffinity"))
        // Design 32 (§7.1, design 17 qa-16-17-code #2): showWhenLocked, never turn-screen-on - that
        // would keep it visible while dark only by waking the phone at every lock-up.
        assertEquals("true", lock.getAttributeNS(ns, "showWhenLocked"))
        assertEquals("", lock.getAttributeNS(ns, "turnScreenOn"))
        val code = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
        assertTrue(!code.contains("setTurnScreenOn") && !code.contains("turnScreenOn"))
        val onCreate = code.substringAfter("override fun onCreate(").substringBefore("\n    }\n")
        val secure = onCreate.indexOf("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        assertTrue(secure in 0 until onCreate.indexOf("setContentView("))
        assertTrue("com.kidslauncher.mdm.lock.PinLockActivity" !in com.kidslauncher.mdm.calls.DirectBootComponents.CLASS_NAMES)
        assertTrue(com.kidslauncher.mdm.calls.DirectBootComponents.needsUnlockedSetup("com.kidslauncher.mdm.lock.PinLockActivity"))
    }

    /** Launcher round 7 §3.1.6 (design 32 §7): the notice wake reuses VoipWakeActivity - the only
     * activities that turn the screen on are our call screen and that wake window, no new one. */
    @Test
    fun `the turn-screen-on activities are exactly the call screen and the wake window`() {
        val nodes = parse("AndroidManifest.xml").getElementsByTagName("activity")
        val turnOn = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttributeNS(ns, "turnScreenOn") == "true" }
            .map { it.getAttributeNS(ns, "name") }
            .toSet()
        assertEquals(setOf(".calls.InCallActivity", ".lock.VoipWakeActivity"), turnOn)
        // A notice wake never touches the ring's try detection (17b): only a VOIP wake reports its pause.
        val wake = file("java/com/kidslauncher/mdm/lock/VoipWakeActivity.kt").readText()
        assertTrue(wake.contains("if (kind == WakeKind.VOIP) PinLockRuntime.voipWakePaused(covered = !isFinishing)"))
        assertEquals(1, Regex("""voipWakePaused\(""").findAll(wake).count())
        assertEquals(WakeKind.NOTICE, wakeKindOf("NOTICE"))
        assertEquals(WakeKind.VOIP, wakeKindOf("VOIP"))
        assertEquals("an intent without the extra is a ring's, as before", WakeKind.VOIP, wakeKindOf(null))
    }

    @Test
    fun `nothing is backed up or transferred - the prefs hold the PIN hashes`() {
        val app = parse("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
        val rules = parse("res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as Element
            val excluded = element.getElementsByTagName("exclude")
            val domains = (0 until excluded.length).map { (excluded.item(it) as Element).getAttribute("domain") }.toSet()
            assertTrue(section, domains.containsAll(setOf("sharedpref", "device_sharedpref", "file", "database", "root")))
        }
    }
}
