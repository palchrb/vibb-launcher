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
            MessageIntent(ACTION_SENDTO, listOf("smsto:+4791234567"), messages),
            resolveMessageButton(contact("sms"), smsEnabled = true, defaultSmsPackage = messages, usable = everything),
        )
        assertNull(resolveMessageButton(contact("sms"), smsEnabled = false, defaultSmsPackage = messages, usable = everything))
        assertNull(resolveMessageButton(contact("sms"), smsEnabled = true, defaultSmsPackage = null, usable = everything))
        assertNull(resolveMessageButton(contact("sms"), smsEnabled = true, defaultSmsPackage = messages, usable = nothing))
    }

    @Test
    fun `element needs a Matrix ID and an allowed Element X`() {
        assertEquals(
            MessageIntent(
                ACTION_VIEW, listOf("matrix:u/mamma:matrix.org?action=chat", "element://user/@mamma:matrix.org"),
                MessagePackages.ELEMENT_X,
            ),
            resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, everything),
        )
        assertNull(resolveMessageButton(contact("element", null), true, messages, everything))
        assertNull(resolveMessageButton(contact("element", "mamma"), true, messages, everything))
        assertNull(resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, usable = { it != MessagePackages.ELEMENT_X }))
        // Not tied to the SMS switch.
        assertTrue(resolveMessageButton(contact("element", "@mamma:matrix.org"), false, null, everything) != null)
    }

    @Test
    fun `element chat uri is the MSC2312 form without the at sign`() {
        assertEquals("matrix:u/a.b-c:example.org:8448?action=chat", elementChatUri("@a.b-c:example.org:8448"))
        // Legal localpart characters that mean something in a URI are encoded (qa-09-code #8).
        assertEquals("matrix:u/a%2Fb%2Bc%3Dd:x.org?action=chat", elementChatUri("@a/b+c=d:x.org"))
        assertEquals("element://user/@a%2Fb:x.org", elementUserUri("@a/b:x.org"))
        // Never a matrix.to link: Element X doesn't handle those.
        val intent = resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, everything)!!
        assertFalse(intent.uris.any { it.contains("matrix.to") })
    }

    @Test
    fun `a learned element room opens the DM first, then today's profile links`() {
        val room = ElementRoom("@kid:vibb.me", "!xrLg_-U0V0jULeIm_fvol9NEvBHNxqHrSP5K1R7BUpI")
        val asked = mutableListOf<String>()
        val intent = resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, everything) {
            asked += it
            room
        }!!
        assertEquals(listOf("@mamma:matrix.org"), asked)
        assertEquals(
            listOf(
                "elementx://open/%40kid%3Avibb.me/%21xrLg_-U0V0jULeIm_fvol9NEvBHNxqHrSP5K1R7BUpI",
                "matrix:u/mamma:matrix.org?action=chat",
                "element://user/@mamma:matrix.org",
            ),
            intent.uris,
        )
        assertEquals(MessagePackages.ELEMENT_X, intent.packageName)
        // An invalid stored pair is never turned into a link: the profile as before.
        val broken = resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, everything) {
            ElementRoom("@kid:vibb.me", "!room|thread")
        }!!
        assertEquals(listOf("matrix:u/mamma:matrix.org?action=chat", "element://user/@mamma:matrix.org"), broken.uris)
        // No button without a usable Element X, learned room or not.
        assertNull(resolveMessageButton(contact("element", "@mamma:matrix.org"), true, messages, nothing) { room })
        // Other apps never ask for a room.
        resolveMessageButton(contact("sms"), true, messages, everything) { error("asked $it") }
        resolveMessageButton(contact("signal"), true, messages, everything) { error("asked $it") }
    }

    @Test
    fun `signal uses the first usable Signal-protocol app and an E164 number`() {
        assertEquals(
            MessageIntent(ACTION_VIEW, listOf("https://signal.me/#p/+4791234567"), "im.molly.app"),
            resolveMessageButton(contact("signal"), true, messages, usable = { it == "im.molly.app" || it == "org.thoughtcrime.securesms" }),
        )
        assertEquals("im.molly.app", resolveMessageButton(contact("signal"), true, messages, everything)?.packageName)
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
        assertTrue(isMatrixId("@a/b+c=d:[::1]:8448"))
        for (bad in listOf(
            "mamma:matrix.org", "@:matrix.org", "@mamma", "@mamma:", "@ma mma:x.org", null,
            "@a:b?action=join&via=x", "@a#b:x.org", "@a?b:x.org", "@Mamma:x.org", "@a:x.org/path",
        )) {
            assertFalse("$bad", isMatrixId(bad))
        }
    }

    @Test
    fun `the messaging apps chosen for contacts stay usable when the budget is used up`() {
        val contacts = listOf(
            RuleContact(1, "A", "+4790000001", messageApp = "sms"),
            RuleContact(2, "B", "+4790000002", messageApp = "element", messageAddress = "@b:example.org"),
            RuleContact(3, "C", "+4790000003", messageApp = "signal"),
            RuleContact(4, "D", "+4790000004", messageApp = "none"),
        )
        val rules = CallRules(callsEnabled = true, smsEnabled = true, contacts = contacts)
        assertEquals(
            setOf("sms.app", MessagePackages.ELEMENT_X) + MessagePackages.SIGNAL,
            messagingAppPackages(rules, "sms.app"),
        )
        assertFalse("sms.app" in messagingAppPackages(rules.copy(smsEnabled = false), "sms.app"))
        assertTrue(messagingAppPackages(null, "sms.app").isEmpty())
        assertEquals(setOf(MessagePackages.ELEMENT_X) + MessagePackages.SIGNAL, messagingAppPackages(rules, null))
    }
}
