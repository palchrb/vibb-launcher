package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageButtonsTest {

    private val messages = "com.google.android.apps.messaging"
    private val everything: (String) -> Boolean = { true }
    private val nothing: (String) -> Boolean = { false }

    private fun contact(app: String, address: String? = null, number: String = "+4791234567") =
        RuleContact(1, "Mamma", number, inbound = true, outbound = true, messageApp = app, messageAddress = address)

    @Test
    fun `sms opens the default SMS app only while SMS is on`() {
        assertEquals(
            MessageIntent(ACTION_SENDTO, "smsto:+4791234567", messages),
            resolveMessageButton(contact("sms"), smsEnabled = true, defaultSmsPackage = messages, usable = everything),
        )
        assertNull(resolveMessageButton(contact("sms"), smsEnabled = false, defaultSmsPackage = messages, usable = everything))
        assertNull(resolveMessageButton(contact("sms"), smsEnabled = true, defaultSmsPackage = null, usable = everything))
        assertNull(resolveMessageButton(contact("sms"), smsEnabled = true, defaultSmsPackage = messages, usable = nothing))
    }

    @Test
    fun `element needs a Matrix ID and an allowed Element X`() {
        assertEquals(
            MessageIntent(ACTION_VIEW, "https://matrix.to/#/@mamma:matrix.org", MessagePackages.ELEMENT_X),
            resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, everything),
        )
        assertNull(resolveMessageButton(contact("element", null), true, messages, everything))
        assertNull(resolveMessageButton(contact("element", "mamma"), true, messages, everything))
        assertNull(resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages) { it != MessagePackages.ELEMENT_X })
        // Not tied to the SMS switch.
        assertTrue(resolveMessageButton(contact("element", "@mamma:matrix.org"), false, null, everything) != null)
    }

    @Test
    fun `signal uses the first usable Signal-protocol app and an E164 number`() {
        assertEquals(
            MessageIntent(ACTION_VIEW, "https://signal.me/#p/+4791234567", "im.molly.app"),
            resolveMessageButton(contact("signal"), true, messages) { it == "im.molly.app" || it == "org.thoughtcrime.securesms" },
        )
        assertEquals("com.kidsmdm.im", resolveMessageButton(contact("signal"), true, messages, everything)?.packageName)
        assertNull(resolveMessageButton(contact("signal"), true, messages, nothing))
        assertNull(resolveMessageButton(contact("signal", number = "1881"), true, messages, everything))
    }

    @Test
    fun `none and unknown apps have no button`() {
        assertNull(resolveMessageButton(contact("none"), true, messages, everything))
        assertNull(resolveMessageButton(contact("whatsapp"), true, messages, everything))
    }

    @Test
    fun `matrix ids match the server's check`() {
        assertTrue(isMatrixId("@mamma:matrix.org"))
        assertTrue(isMatrixId("@a.b-c:example.org:8448"))
        for (bad in listOf("mamma:matrix.org", "@:matrix.org", "@mamma", "@mamma:", "@ma mma:x.org", null)) {
            assertFalse("$bad", isMatrixId(bad))
        }
    }
}
