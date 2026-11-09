package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 79 §3.5: the card's buttons, moved out of `ContactSheet.show` unchanged - and the row's, the same rule. */
class ContactActionsTest {
    private val sms = "com.google.android.apps.messaging"
    private val everything: (String) -> Boolean = { true }
    private val nothing: (String) -> Boolean = { false }
    private val active = 4 // android.telecom.Call.STATE_ACTIVE

    private fun rules(smsMode: ChannelMode, smsEnabled: Boolean = smsMode != ChannelMode.OFF) =
        CallRules(callsEnabled = true, smsEnabled = smsEnabled, smsMode = smsMode)

    private val mamma = RuleContact(1, "Mamma", "+4791234567", inbound = true, outbound = true, messageApp = "sms", smsOut = true)

    @Test
    fun `Ring is there for every contact, off while another call is live, an emergency number excepted`() {
        val r = rules(ChannelMode.MANAGED)
        assertTrue(contactActions(mamma, r, emergency = false, emptyList(), sms, everything).callEnabled)
        assertFalse(contactActions(mamma, r, emergency = false, listOf(active), sms, everything).callEnabled)
        assertTrue(contactActions(mamma, r, emergency = true, listOf(active), sms, everything).callEnabled)
        // A call still being set up doesn't count (secondCallAllowed's rule).
        assertTrue(contactActions(mamma, r, emergency = false, listOf(9), sms, everything).callEnabled)
    }

    @Test
    fun `Melding - our own thread in Styrt av Vibb, else the app, else none`() {
        assertEquals(MessageAction.OwnThread, contactActions(mamma, rules(ChannelMode.MANAGED), false, emptyList(), sms, everything).message)
        // Without sms_out in Styrt av Vibb: no own thread, and the SMS app route is the old rule's.
        val noOut = mamma.copy(smsOut = false)
        assertEquals(
            MessageAction.App(MessageIntent(ACTION_SENDTO, listOf("smsto:+4791234567"), sms)),
            contactActions(noOut, rules(ChannelMode.MANAGED), false, emptyList(), sms, everything).message,
        )
        assertEquals(
            MessageAction.App(MessageIntent(ACTION_SENDTO, listOf("smsto:+4791234567"), sms)),
            contactActions(mamma, rules(ChannelMode.UNMANAGED), false, emptyList(), sms, everything).message,
        )
        assertEquals(MessageAction.None, contactActions(mamma, rules(ChannelMode.UNMANAGED), false, emptyList(), sms, nothing).message)
        assertEquals(MessageAction.None, contactActions(mamma, rules(ChannelMode.OFF), false, emptyList(), sms, everything).message)
        assertEquals(MessageAction.None, contactActions(mamma, null, false, emptyList(), sms, everything).message)
        val element = mamma.copy(messageApp = "element", messageAddress = "@mamma:example.org")
        val room = ElementRoom("@kid:vibb.me", "!xrLg_-U0V0jULeIm_fvol9NEvBHNxqHrSP5K1R7BUpI")
        val answer = contactActions(element, rules(ChannelMode.MANAGED), false, emptyList(), sms, everything) { room }.message
        assertTrue(answer is MessageAction.App && answer.intent.packageName == MessagePackages.ELEMENT_X)
        assertTrue((answer as MessageAction.App).intent.uris.first().startsWith("elementx://open/"))
    }

    /** The card asks the one rule, and no longer decides on its own. */
    @Test
    fun `the card's buttons come from contactActions`() {
        val sheet = listOf(File("src/main/java/com/kidslauncher/mdm/calls/ContactSheet.kt"), File("app/src/main/java/com/kidslauncher/mdm/calls/ContactSheet.kt"))
            .first { it.exists() }.readText()
        assertTrue(sheet.contains("contactActions("))
        for (token in listOf("secondCallAllowed(", "opensOwnThread(", "resolveMessageButton(")) {
            assertFalse(token, sheet.substringAfter("object ContactSheet").contains(token))
        }
    }
}
