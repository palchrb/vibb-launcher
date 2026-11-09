package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.badges.GlanceMissedRow
import com.kidslauncher.mdm.badges.glanceWithSms
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.IdSource
import com.kidslauncher.mdm.sms.engine.IncomingSms
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Received
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.engine.SmsRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 77 §6.8 (step 5): the lock glance's Meldinger rows - from the engine's accepted messages only, the text only
 * with the parent's switch, gone when read or deleted, back after a swipe only with a newer message. */
class SmsGlanceTest {
    private var now = 1_793_800_000_000L
    private val mamma = RuleContact(1, "Mamma", "+4791234567", inbound = true, outbound = true, smsIn = true, smsOut = true, mms = true)
    private val rules = CallRules(
        callsEnabled = true, contacts = listOf(mamma), callsMode = ChannelMode.MANAGED, smsMode = ChannelMode.MANAGED,
    )
    private val store = FakeMessageStore()
    private val radio = object : SmsRadio {
        override fun send(messageId: String, address: String, body: String, subId: Int?, deliveryReport: Boolean) = 1
    }
    private var counter = 0
    private val ids = object : IdSource {
        override fun ulid(now: Long) = "U%022d".format(++counter)
        override fun hex() = "%032x".format(++counter)
    }
    private val engine = SmsEngine(
        store, { now }, { SmsJudge(state = CallPolicyState.Managed(rules), roleHeld = true, platformEmergency = { false }) }, radio, ids,
    )
    private var smsc = 0L

    private fun receive(from: String, body: String, kind: SenderKind = SenderKind.NUMBER): Received {
        now += 1000
        return engine.receiveSms(IncomingSms(kind, from, body, sentAt = now - 1000 + ++smsc, subId = 1, receivedAt = now))
    }

    /** What SmsHome's refresh builds on the engine thread. */
    private fun rows(showText: Boolean) = engine.conversations().filter { it.unread > 0 }
        .mapNotNull { smsGlanceRow(it, engine.messages(it.id, null, SMS_GLANCE_LOOKBACK), showText) }

    @Test
    fun `only accepted messages make a row - a stranger's and a sender name's held messages never do`() {
        assertTrue(receive("+4790000041", "fra en fremmed") is Received.HeldForParent)
        assertTrue(receive("DNB", "kode 1234", SenderKind.ALPHANUMERIC) is Received.HeldForParent)
        assertTrue(rows(showText = true).isEmpty())
        receive("+4791234567", "Middag kl 5")
        val row = rows(showText = true).single()
        assertEquals("+4791234567", row.conversation.participants.single().address)
        assertEquals("Middag kl 5", row.text)
    }

    @Test
    fun `the text only with the parent's switch - the name and the count without it`() {
        receive("+4791234567", "Hei")
        receive("+4791234567", "Kommer du?‮ snart")
        val off = rows(showText = false).single()
        assertEquals(2, off.count)
        assertNull(off.text)
        assertFalse(off.picture)
        assertEquals(now, off.timeMs)
        val on = rows(showText = true).single()
        assertEquals("the newest, cleaned", "Kommer du? snart", on.text)
        assertEquals(off.upToSeq, on.upToSeq)
        // The switch itself: only managed rules that say so.
        assertTrue(smsLockText(CallPolicyState.Managed(rules.copy(smsLockText = true))))
        assertFalse(smsLockText(CallPolicyState.Managed(rules)))
        assertFalse(smsLockText(CallPolicyState.Unmanaged))
        assertFalse(smsLockText(CallPolicyState.UnknownFailClosed))
        assertFalse("the redacted row", on.toString().contains("Kommer") || on.toString().contains("4791234567"))
    }

    @Test
    fun `the row goes when the thread is read or the message deleted`() {
        val first = receive("+4791234567", "Hei") as Received.Shown
        engine.markRead(ConversationId(first.conversation.id), first.message.seq, Origin.KID_SCREEN)
        assertTrue(rows(showText = false).isEmpty())
        val second = receive("+4791234567", "Igjen") as Received.Shown
        assertEquals(1, rows(showText = false).single().count)
        engine.delete(second.message.id, Origin.KID_SCREEN)
        assertTrue(rows(showText = false).isEmpty())
    }

    @Test
    fun `a swiped-away row comes back only with a newer message`() {
        receive("+4791234567", "Hei")
        val row = rows(showText = false).single()
        val hidden = mapOf(row.conversationId to row.upToSeq)
        assertTrue(smsGlanceShown(listOf(row), hidden).isEmpty())
        receive("+4791234567", "Hallo?")
        val newer = rows(showText = false).single()
        assertEquals(listOf(newer), smsGlanceShown(listOf(newer), hidden))
    }

    @Test
    fun `the rows rank by time among the missed calls`() {
        receive("+4791234567", "Hei")
        val sms = rows(showText = false).single()
        val older = GlanceMissedRow("+4790000002", "Pappa", 1, sms.timeMs - 60_000)
        val newer = GlanceMissedRow("+4790000003", "Mormor", 1, sms.timeMs + 60_000)
        assertEquals(listOf(newer, sms, older), glanceWithSms(listOf(newer, older), listOf(sms)))
    }

    /** SmsHome reads the text only through the parent's switch; the panel's SMS rows come only from the engine's
     * summary (GlanceReader skips our notifications). */
    @Test
    fun `the wiring - the switch, the summary, the panel`() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        fun code(name: String) = root.walkTopDown().first { it.name == name }.readText()
        val home = code("SmsHome.kt")
        assertTrue(home.contains("val showText = smsLockText(CallPolicyStore.state)"))
        assertTrue(home.contains("smsGlanceRow(it, engine.messages(it.id, null, SMS_GLANCE_LOOKBACK), showText)"))
        val panel = code("GlancePanel.kt")
        assertTrue(panel.contains("val sms = SmsGlance.shown(SmsHome.summary.glance.also { smsRows = it })"))
        assertTrue(panel.contains("is GlanceSmsRow -> GlanceTarget.Thread(row.conversationId)"))
        assertTrue(panel.contains("SmsHome.removeListener(smsChanged)"))
    }
}
