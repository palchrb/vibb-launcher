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
        assertTrue("com.kidslauncher.mdm.lock.PinLockActivity" !in com.kidslauncher.mdm.calls.DirectBootComponents.CLASS_NAMES)
        assertTrue(com.kidslauncher.mdm.calls.DirectBootComponents.needsUnlockedSetup("com.kidslauncher.mdm.lock.PinLockActivity"))
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
