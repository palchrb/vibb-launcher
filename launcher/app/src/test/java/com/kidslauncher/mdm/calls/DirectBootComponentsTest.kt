package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** KidAppComponentFactory's list of direct-boot-aware components must match the manifest: a
 * component missing from it would run the unlocked setup before unlock (QA direct-boot #1). */
class DirectBootComponentsTest {

    private val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map(::File).first { it.exists() }

    private fun manifestComponents(): Pair<Set<String>, Set<String>> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val aware = mutableSetOf<String>()
        val all = mutableSetOf<String>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val name = element.getAttributeNS(ns, "name").let { if (it.startsWith(".")) "com.kidslauncher.mdm$it" else it }
                all += name
                if (element.getAttributeNS(ns, "directBootAware") == "true") aware += name
            }
        }
        return aware to all
    }

    @Test
    fun `the list matches the manifest's directBootAware components`() {
        val (aware, _) = manifestComponents()
        assertEquals(aware, DirectBootComponents.CLASS_NAMES)
    }

    @Test
    fun `Home, the lock screen and the background services need the unlocked setup`() {
        val (_, all) = manifestComponents()
        for (name in listOf(
            "com.kidslauncher.mdm.ui.HomeActivity", "com.kidslauncher.mdm.ui.LockActivity",
            "com.kidslauncher.mdm.server.KidVpnService", "com.kidslauncher.mdm.server.CommandListenerService",
            "com.kidslauncher.mdm.server.PackageReplacedReceiver",
        )) {
            assertTrue(name, name in all)
            assertTrue(name, DirectBootComponents.needsUnlockedSetup(name))
        }
        assertFalse(DirectBootComponents.needsUnlockedSetup("com.kidslauncher.mdm.calls.KidCallScreeningService"))
    }
}
