package com.kidslauncher.mdm.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The anchor and Play parts of handy step 7's manifest, and design 19: no Firebase left in it
 * (the Gradle guard `checkReleaseHasNoGoogleServices` keeps the SDK off the release classpath). */
class PushManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private val doc = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map(::File).first { it.exists() }
        .let { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it) }

    private fun component(tag: String, name: String): Element? {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .firstOrNull { it.getAttributeNS(android, "name") == name }
    }

    @Test
    fun `no Firebase in the manifest (19)`() {
        val all = doc.getElementsByTagName("*")
        val names = (0 until all.length).map { (all.item(it) as Element).getAttributeNS(android, "name") }
        val firebase = names.filter { name ->
            name.startsWith("com.google.firebase") || name.startsWith("firebase_") || "Fcm" in name ||
                name.startsWith("delivery_metrics") || name == "com.google.android.c2dm.permission.RECEIVE"
        }
        assertEquals(emptyList<String>(), firebase)
        val placeholders = (0 until all.length).flatMap { i ->
            val attrs = (all.item(i) as Element).attributes
            (0 until attrs.length).map { attrs.item(it).nodeValue }
        }.filter { "fcm" in it.lowercase() }
        assertEquals(emptyList<String>(), placeholders)
        // The tools: namespace stays for other entries, but nothing is removed from a library any more.
        val removed = (0 until all.length).map { all.item(it) as Element }.filter { it.getAttributeNS(tools, "node") == "remove" }
        assertEquals(emptyList<Element>(), removed)
    }

    @Test
    fun `the anchor is a specialUse service with a subtype`() {
        val anchor = component("service", ".server.CommandListenerService")!!
        assertEquals("specialUse", anchor.getAttributeNS(android, "foregroundServiceType"))
        val property = anchor.getElementsByTagName("property").item(0) as Element
        assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", property.getAttributeNS(android, "name"))
        val perms = doc.getElementsByTagName("uses-permission")
        val names = (0 until perms.length).map { (perms.item(it) as Element).getAttributeNS(android, "name") }
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in names)
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" !in names)
    }

    @Test
    fun `the Play link blocker declares the filters it is made preferred for`() {
        val blocker = component("activity", ".play.PlayLinkBlockedActivity")!!
        val schemes = blocker.getElementsByTagName("data").let { d -> (0 until d.length).map { (d.item(it) as Element).getAttributeNS(android, "scheme") } }
        assertTrue("market" in schemes && "https" in schemes)
    }
}
