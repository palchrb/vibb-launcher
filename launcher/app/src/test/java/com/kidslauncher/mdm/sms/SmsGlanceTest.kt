package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.badges.GlanceMissedRow
import com.kidslauncher.mdm.badges.glanceWithSms
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.engine.Conversation
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.IdSource
import com.kidslauncher.mdm.sms.engine.IncomingSms
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Part
import com.kidslauncher.mdm.sms.engine.PartState
import com.kidslauncher.mdm.sms.engine.Participant
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
        receive("+4791234567", "Kommer du?\u202E snart")
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
    fun `the rows rank by time among the missed calls, and count toward the eight rows and the more line`() {
        receive("+4791234567", "Hei")
        val sms = rows(showText = false).single()
        val older = GlanceMissedRow("+4790000002", "Pappa", 1, sms.timeMs - 60_000)
        val newer = GlanceMissedRow("+4790000003", "Mormor", 1, sms.timeMs + 60_000)
        assertEquals(listOf(newer, sms, older), glanceWithSms(listOf(newer, older), listOf(sms)))
        // Eight older missed calls and our row: ours is bound (newest), the oldest call goes to "+ 1".
        val calls = (1..8).map { GlanceMissedRow("+47900001%02d".format(it), null, 1, sms.timeMs - it * 60_000L) }
        val all = glanceWithSms(calls, listOf(sms))
        val layout = com.kidslauncher.mdm.lock.glanceLayout(tile = false, rowCount = all.size, timer = false)
        assertEquals(8, layout.rows)
        assertEquals(1, layout.more)
        assertEquals(sms, all.first())
        assertEquals(calls.last(), all.last())
    }

    // ---- what a row says (the pure row from the engine's messages) ----

    private val part = Part(0, "image/jpeg", 1000, "ab", 1200, 900, PartState.AVAILABLE)

    private fun message(seq: Long, body: String?, sender: String = "+4791234567", parts: List<Part> = emptyList(), unknown: Boolean = false) =
        Message(
            MessageId("M$seq"), ConversationId("C1"), seq, Direction.IN, sender, unknown, now + seq, null, body, parts,
            Delivery.RECEIVED, null, read = false, clientId = null, origin = Origin.ENGINE,
        )

    private fun conversation(vararg addresses: String, unread: Int = 1) = Conversation(
        ConversationId("C1"), addresses.map { Participant(it, null) }, null, 1, 9, now, unread, canSend = true,
    )

    @Test
    fun `a picture says Bilde only with the switch on`() {
        val picture = message(3, body = null, parts = listOf(part))
        val off = smsGlanceRow(conversation("+4791234567"), listOf(picture), showText = false)!!
        assertFalse(off.picture)
        assertNull(off.text)
        val on = smsGlanceRow(conversation("+4791234567"), listOf(picture), showText = true)!!
        assertTrue(on.picture)
        assertNull(on.text)
        // A picture with text: the text, not "Bilde".
        val both = smsGlanceRow(conversation("+4791234567"), listOf(message(4, "Se her", parts = listOf(part))), showText = true)!!
        assertEquals("Se her", both.text)
        assertFalse(both.picture)
    }

    @Test
    fun `a group's row carries its newest sender, a one-to-one's none`() {
        val group = smsGlanceRow(conversation("+4790000001", "+4790000002", unread = 2), listOf(message(5, "Kommer", sender = "+4790000002"), message(4, "Hei", sender = "+4790000001")), true)!!
        assertEquals("+4790000002", group.sender)
        assertEquals("Kommer", group.text)
        assertNull(smsGlanceRow(conversation("+4791234567"), listOf(message(5, "Hei")), true)!!.sender)
    }

    @Test
    fun `the callback window's stranger is accepted - its row is marked, its text only with the switch`() {
        val stranger = message(6, "Ring meg", sender = "+4790000099", unknown = true)
        val on = smsGlanceRow(conversation("+4790000099"), listOf(stranger), showText = true)!!
        assertTrue(on.unknownSender)
        assertEquals("Ring meg", on.text)
        val off = smsGlanceRow(conversation("+4790000099"), listOf(stranger), showText = false)!!
        assertTrue(off.unknownSender)
        assertNull(off.text)
    }

    @Test
    fun `the newest unread incoming message, past the kid's own reply`() {
        val reply = message(8, "Ok").copy(direction = Direction.OUT, sender = null)
        val row = smsGlanceRow(conversation("+4791234567"), listOf(reply, message(7, "Hei")), true)!!
        assertEquals(7, row.upToSeq)
        assertEquals("Hei", row.text)
        assertNull(smsGlanceRow(conversation("+4791234567", unread = 0), listOf(message(7, "Hei")), true))
    }

    // ---- the notice wake (launcher round 7 §3) ----

    private fun alerts(
        silent: Boolean = false,
        enabled: Boolean = true,
        importance: Int? = 3,
        passesFilter: Boolean = true,
        gainsRow: Boolean = true,
    ) = smsPostAlerts(posted = true, silent = silent, enabled = enabled, channelImportance = importance, passesFilter = passesFilter, gainsRow = gainsRow)

    /** Only an accepted message's alerting post that brings its row onto the lock is a notice that came - the parent's
     * switch and the lock's gate (noticeWakeAllowed) decide the rest. */
    @Test
    fun `the notice wake - an alerting post, no Do Not Disturb, a new row`() {
        assertTrue(alerts())
        assertTrue(alerts(importance = 4))
        assertFalse("stored and notified silently (a time rule, a picture told again)", alerts(silent = true))
        assertFalse("the silent channel", alerts(importance = 2))
        assertFalse("the channel unknown", alerts(importance = null))
        assertFalse("notifications off", alerts(enabled = false))
        assertFalse("any Do Not Disturb - a silent rule that allows calls, the kid's own (code QA H1)", alerts(passesFilter = false))
        assertFalse("a newer message in a row already on the lock (code QA M1)", alerts(gainsRow = false))
        assertFalse("not posted", smsPostAlerts(false, false, true, 3, passesFilter = true, gainsRow = true))
    }

    @Test
    fun `a row is gained by the first unread message, or the first after a swipe`() {
        assertTrue("the conversation's only unread", smsGainsRow(unreadAfter = 1, hiddenMark = null, messageSeq = 10))
        assertFalse("its second", smsGainsRow(unreadAfter = 2, hiddenMark = null, messageSeq = 11))
        assertTrue("the first after the row was swiped away", smsGainsRow(unreadAfter = 3, hiddenMark = 11, messageSeq = 12))
        assertFalse("a mark not older than it", smsGainsRow(unreadAfter = 3, hiddenMark = 12, messageSeq = 12))
        // The engine's count: the first message from Mamma gains the row, the second doesn't.
        receive("+4791234567", "Hei")
        assertEquals(1, engine.conversations().single().unread)
        receive("+4791234567", "Hallo?")
        assertEquals(2, engine.conversations().single().unread)
    }

    @Test
    fun `a swipe keeps the notification when a newer message came during the drag (code QA L2)`() {
        assertTrue(smsDismissCancels(swipedUpToSeq = 5, currentUpToSeq = 5))
        assertTrue("the row is gone now", smsDismissCancels(swipedUpToSeq = 5, currentUpToSeq = null))
        assertFalse(smsDismissCancels(swipedUpToSeq = 5, currentUpToSeq = 6))
    }

    /** Step 3b's code QA L8: a group MMS that leaves the sender's one-to-one conversation keeps what is still unread
     * there in its notification - the notification goes only when nothing is. */
    @Test
    fun `after a group move the one-to-one notification keeps what is still unread`() {
        val first = receive("+4791234567", "Hei") as Received.Shown
        val id = ConversationId(first.conversation.id)
        val left = smsNotifiedMessages(engine.messages(id, null, 10), 5)
        assertEquals(listOf("Hei"), left.map { it.body })
        engine.markRead(id, first.message.seq, Origin.KID_SCREEN)
        assertTrue("nothing unread: the notification goes", smsNotifiedMessages(engine.messages(id, null, 10), 5).isEmpty())
        // Oldest first, at most five.
        repeat(7) { receive("+4791234567", "m$it") }
        assertEquals((2..6).map { "m$it" }, smsNotifiedMessages(engine.messages(id, null, 10), 5).map { it.body })
    }

    /** The one source check left: the wake's one trigger is our notification's own post - after it, never in "not
     * sent" or a group move's re-post. */
    @Test
    fun `the notifier's one wake comes after the post`() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val notifier = root.walkTopDown().first { it.name == "SmsNotifier.kt" }.readText()
        assertEquals(1, Regex("""noticeCame\(""").findAll(notifier).count())
        val notify = notifier.substringAfter("fun notify(").substringBefore("private fun wakeIfAlerts(")
        assertTrue(notify, notify.indexOf("manager.notify(") in 0 until notify.indexOf("if (wake) wakeIfAlerts("))
        assertTrue(notifier.substringAfter("fun afterMove(").contains("wake = false"))
        assertFalse(notifier.substringAfter("fun notSent(").substringBefore("fun cancel(").contains("wakeIfAlerts"))
    }
}
