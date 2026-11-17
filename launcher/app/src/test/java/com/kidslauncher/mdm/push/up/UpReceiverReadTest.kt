package com.kidslauncher.mdm.push.up

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 108 §5.1 (M1, L4): the request is read in full before `goAsync()`, the sender only from Android's shared
 * identity, unknown extras ignored, a known extra of the wrong form or type ignores the request, and a sender known only
 * by `pi` or AND_2's `application` is never trusted.
 */
class UpReceiverReadTest {
    private class Extras(val map: Map<String, Any?>) : UpExtrasView {
        override fun has(key: String) = key in map

        override fun string(key: String) = map[key] as? String
    }

    private val element = "io.element.android.x"
    private val vapid = "B" + "A".repeat(86)

    private fun read(action: String, sender: String?, vararg extras: Pair<String, Any?>) = UpRequests.read(action, sender, Extras(mapOf(*extras)))

    @Test
    fun `a REGISTER with the shared identity is read, unknown extras ignored`() {
        val got = read(
            UpSpec.REGISTER, element,
            "token" to "tok-element-1", "message" to "@emma:example.org", "vapid" to vapid,
            // AND_2's extras and anything newer: ignored, never a refusal.
            "features" to arrayOf("bytes_message"), "application" to "com.example.other", "pi" to Any(), "future" to 7,
        )
        assertEquals(UpRequests.Read.Request(UpRequest.Register(element, "tok-element-1", "@emma:example.org", vapid)), got)
    }

    @Test
    fun `no shared identity - never resolved through pi or application`() {
        val got = read(UpSpec.REGISTER, null, "token" to "tok-1", "pi" to Any(), "application" to element)
        assertEquals(UpRequests.Read.OldConnector(element), got)
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.UNREGISTER, null, "token" to "tok-1", "application" to element))
    }

    @Test
    fun `a known extra of the wrong form or type ignores the request`() {
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.REGISTER, element))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.REGISTER, element, "token" to ""))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.REGISTER, element, "token" to "t".repeat(101)))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.REGISTER, element, "token" to 42))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.REGISTER, element, "token" to "tok", "vapid" to "short"))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.REGISTER, element, "token" to "tok", "message" to 3))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.MESSAGE_ACK, element, "token" to "tok"))
        assertEquals(UpRequests.Read.Ignored, read(UpSpec.MESSAGE_ACK, element, "token" to "tok", "id" to "i".repeat(101)))
        assertEquals(UpRequests.Read.Ignored, read("org.unifiedpush.android.distributor.LINK", element, "token" to "tok"))
    }

    @Test
    fun `MESSAGE_ACK is read without an identity, UNREGISTER only with one`() {
        assertEquals(UpRequests.Read.Request(UpRequest.Ack(null, "tok", "id1")), read(UpSpec.MESSAGE_ACK, null, "token" to "tok", "id" to "id1"))
        assertEquals(UpRequests.Read.Request(UpRequest.Unregister(element, "tok")), read(UpSpec.UNREGISTER, element, "token" to "tok"))
    }

    @Test
    fun `onReceive reads the identity and the request before goAsync, and hands over only the request (M1)`() {
        val source = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
            .resolve("com/kidslauncher/mdm/push/up/UpReceiver.kt").readText()
        val onReceive = source.substringAfter("override fun onReceive(").substringBefore("\n    }\n")
        val sender = onReceive.indexOf("sentFromPackage")
        val readAt = onReceive.indexOf("UpRequests.read(intent.action, sender, BundleExtras(intent.extras))")
        val goAsync = onReceive.indexOf("goAsync()")
        assertTrue(sender in 0 until readAt)
        assertTrue(readAt in 0 until goAsync)
        // After the first goAsync, nothing reads the Intent or the identity again.
        val after = onReceive.substring(goAsync)
        assertFalse(after, "intent" in after.replace("Intent", ""))
        assertFalse("sentFromPackage" in after)
        assertFalse("getParcelable" in source || "\"pi\"" in source.substringAfter("class UpReceiver"))
    }
}
