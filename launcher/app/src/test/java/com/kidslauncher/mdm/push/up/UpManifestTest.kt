package com.kidslauncher.mdm.push.up

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 108 §5.1-§5.2: the distributor's receiver and Link Activity - exactly their filters, installed disabled - and
 * no other receiver newly exported (the pinned set below changes only with a design that says why).
 */
class UpManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        .let { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it) }

    private fun all(tag: String): List<Element> = doc.getElementsByTagName(tag).let { n -> (0 until n.length).map { n.item(it) as Element } }

    private fun named(tag: String, name: String) = all(tag).single { it.getAttributeNS(ns, "name") == name }

    private fun Element.children(tag: String): List<Element> = getElementsByTagName(tag).let { n -> (0 until n.length).map { n.item(it) as Element } }

    private fun Element.attr(name: String) = getAttributeNS(ns, name)

    @Test
    fun `the receiver takes exactly REGISTER, UNREGISTER and MESSAGE_ACK, exported and installed disabled`() {
        val receiver = named("receiver", ".push.up.UpReceiver")
        assertEquals("true", receiver.attr("exported"))
        assertEquals("false", receiver.attr("enabled"))
        assertEquals("", receiver.attr("directBootAware"))
        val actions = receiver.children("action").map { it.attr("name") }
        assertEquals(listOf(UpSpec.REGISTER, UpSpec.UNREGISTER, UpSpec.MESSAGE_ACK), actions)
        assertTrue(receiver.children("data").isEmpty())
    }

    @Test
    fun `the Link Activity answers unifiedpush link only, windowless, out of Recents, disabled`() {
        val link = named("activity", ".push.up.UpLinkActivity")
        assertEquals("true", link.attr("exported"))
        assertEquals("false", link.attr("enabled"))
        assertEquals("true", link.attr("excludeFromRecents"))
        assertEquals("true", link.attr("noHistory"))
        assertEquals("", link.attr("taskAffinity"))
        assertEquals("@android:style/Theme.Translucent.NoTitleBar", link.attr("theme"))
        val filter = link.children("intent-filter").single()
        assertEquals(listOf("android.intent.action.VIEW"), filter.children("action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.DEFAULT"), filter.children("category").map { it.attr("name") })
        val data = filter.children("data").single()
        assertEquals(UpSpec.LINK_SCHEME to UpSpec.LINK_HOST, data.attr("scheme") to data.attr("host"))
    }

    @Test
    fun `the package receiver is not exported and takes only the removal and the data clear`() {
        val receiver = named("receiver", ".push.up.UpPackageReceiver")
        assertEquals("false", receiver.attr("exported"))
        assertEquals(
            listOf("android.intent.action.PACKAGE_FULLY_REMOVED", "android.intent.action.PACKAGE_DATA_CLEARED"),
            receiver.children("action").map { it.attr("name") },
        )
    }

    @Test
    fun `no other receiver is newly exported`() {
        val exported = all("receiver").filter { it.attr("exported") == "true" }.associate { it.attr("name") to it.attr("permission") }
        assertEquals(
            mapOf(
                ".calls.MissedCallReceiver" to "", // Telecom's protected SHOW_MISSED_CALLS_NOTIFICATION (design 12)
                ".server.MdmDeviceAdminReceiver" to "android.permission.BIND_DEVICE_ADMIN",
                ".push.up.UpReceiver" to "", // design 108: any app may ask; Android's shared identity decides
                ".sms.SmsDeliverReceiver" to "android.permission.BROADCAST_SMS",
                ".sms.MmsPushReceiver" to "android.permission.BROADCAST_WAP_PUSH",
                ".timerules.TimeRuleReceiver" to "", // the system's time and zone broadcasts (handy step 6)
            ),
            exported,
        )
    }
}
