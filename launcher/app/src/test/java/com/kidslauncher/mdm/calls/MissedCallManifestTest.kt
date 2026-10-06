package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Design 12-missed-calls.md: Telecom's broadcast reaches our receiver, and the call log opens the
 * phone book (QA #5, #6, #7). */
class MissedCallManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val manifest by lazy {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun elements(parent: Element, tag: String) =
        parent.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun component(tag: String, name: String): Element =
        elements(manifest.documentElement, tag).first { it.getAttributeNS(ns, "name") == name }

    private fun names(parent: Element, tag: String) = elements(parent, tag).map { it.getAttributeNS(ns, "name") }

    @Test
    fun `the receiver takes Telecom's missed-call broadcast`() {
        val receiver = component("receiver", ".calls.MissedCallReceiver")
        // Protected broadcast, sent with READ_PHONE_STATE as the receiver permission (QA #7).
        assertEquals("true", receiver.getAttributeNS(ns, "exported"))
        assertEquals("", receiver.getAttributeNS(ns, "permission"))
        // Not direct-boot-aware (QA #5): no CE storage, no call log before the first unlock.
        assertEquals("", receiver.getAttributeNS(ns, "directBootAware"))
        assertTrue("com.kidslauncher.mdm.calls.MissedCallReceiver" !in DirectBootComponents.CLASS_NAMES)
        val filters = elements(receiver, "intent-filter")
        assertEquals(1, filters.size)
        // TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION (checked against the android-36 SDK).
        assertEquals(listOf("android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION"), names(filters.single(), "action"))
        val permissions = names(manifest.documentElement, "uses-permission")
        assertTrue(permissions.containsAll(listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
        )))
    }

    @Test
    fun `the swipe receiver only takes our own delete intent`() {
        val receiver = component("receiver", ".calls.MissedCallDismissReceiver")
        assertEquals("false", receiver.getAttributeNS(ns, "exported"))
        assertEquals("", receiver.getAttributeNS(ns, "directBootAware"))
        assertTrue(elements(receiver, "intent-filter").isEmpty())
        assertTrue("com.kidslauncher.mdm.calls.MissedCallDismissReceiver" !in DirectBootComponents.CLASS_NAMES)
    }

    @Test
    fun `the phone book has its own call-log filter, matching the pin`() {
        val phoneBook = component("activity", ".calls.PhoneBookActivity")
        assertEquals("true", phoneBook.getAttributeNS(ns, "exported"))
        assertEquals("", phoneBook.getAttributeNS(ns, "directBootAware"))
        val callLog = elements(phoneBook, "intent-filter").filter { filter ->
            elements(filter, "data").any { it.getAttributeNS(ns, "mimeType") == CALL_LOG_TYPE }
        }
        assertEquals(1, callLog.size)
        val filter = callLog.single()
        assertEquals(listOf("android.intent.action.VIEW"), names(filter, "action"))
        assertEquals(listOf("android.intent.category.DEFAULT"), names(filter, "category"))
        // Only the type: no scheme, so content://call_log/calls matches too; no priority.
        val data = elements(filter, "data").single()
        assertEquals("", data.getAttributeNS(ns, "scheme"))
        assertEquals("", filter.getAttributeNS(ns, "priority"))
    }
}
