package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.EngineEvent
import com.kidslauncher.mdm.sms.engine.HeldState
import com.kidslauncher.mdm.sms.engine.HoldPolicy
import com.kidslauncher.mdm.sms.engine.IdSource
import com.kidslauncher.mdm.sms.engine.IncomingSms
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Received
import com.kidslauncher.mdm.sms.engine.Refusal
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.engine.SmsRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 77 §6.2, D15, D16: the engine's logic over the in-memory store ([FakeMessageStore]). */
class SmsEngineTest {
    private var now = 1_793_800_000_000L
    private val mamma = RuleContact(1, "Mamma", "+4791234567", inbound = true, outbound = true, smsIn = true, smsOut = true, mms = true)
    private val trener = RuleContact(3, "Trener", "+4790000003", inbound = true, outbound = true)
    private val ambulanse = RuleContact(4, "Ambulanse", "113", inbound = true, outbound = true, smsIn = true, smsOut = true)
    private var rules: CallRules? = rules(listOf(mamma, trener, ambulanse))
    private val store = FakeMessageStore()
    private val sent = mutableListOf<Pair<String, String>>()
    private var parts = 1
    private val radio = object : SmsRadio {
        override fun send(messageId: String, address: String, body: String, subId: Int?, deliveryReport: Boolean): Int {
            sent += address to body
            return parts
        }
    }
    private var counter = 0
    private val ids = object : IdSource {
        override fun ulid(now: Long) = "U%022d".format(++counter)
        override fun hex() = "%032x".format(++counter)
    }
    private val engine = SmsEngine(store, { now }, { judge() }, radio, ids)

    private fun rules(contacts: List<RuleContact>, names: List<String> = emptyList()) =
        CallRules(callsEnabled = true, smsEnabled = false, contacts = contacts, callsMode = ChannelMode.MANAGED, smsMode = ChannelMode.MANAGED, smsNames = names)

    private fun judge() = SmsJudge(
        state = rules?.let { CallPolicyState.Managed(it) } ?: CallPolicyState.UnknownFailClosed,
        roleHeld = true,
        platformEmergency = { false },
    )

    private fun sms(from: String, body: String = "hei", kind: SenderKind = SenderKind.NUMBER) =
        IncomingSms(kind, from, body, sentAt = now - 1000, subId = 1, receivedAt = now)

    private fun held() = store.heldRows()

    // ---- receive ----

    @Test
    fun `an allowed contact's message is shown, with its event and conversation`() {
        val shown = engine.receiveSms(sms("91234567")) as Received.Shown
        assertEquals("Mamma", shown.conversation.displayName)
        assertEquals(listOf(1L), shown.conversation.participants.map { it.contactId })
        assertEquals("+4791234567", shown.message.sender)
        val events = engine.events(0)
        assertEquals(listOf(shown.message.id), events.filterIsInstance<EngineEvent.MessageAdded>().map { it.message.id })
        assertEquals(1, engine.conversations().single().unread)
        assertTrue(engine.conversations().single().canSend)
        // A cursor past it replays nothing.
        assertTrue(engine.events(events.last().seq).isEmpty())
    }

    @Test
    fun `a blocked sender keeps who and when only`() {
        assertTrue(engine.receiveSms(sms("*100#", "secret")) is Received.Blocked)
        val row = store.blockedRows().single()
        assertEquals("malformed", row.senderClass.wire)
        assertEquals("", row.sender)
        assertEquals(1L, store.counters()["blocked.malformed"])
        assertTrue(engine.conversations().isEmpty())
    }

    // ---- send: idempotency, outbox, delivery ----

    @Test
    fun `the same clientId twice sends once, another body is refused`() {
        val first = engine.send(SendCommand("c1", null, "91234567", "hei", origin = Origin.KID_SCREEN)) as SendResult.Accepted
        val again = engine.send(SendCommand("c1", null, "91234567", "hei", origin = Origin.NOTIFICATION)) as SendResult.Accepted
        assertEquals(first.message.id, again.message.id)
        assertEquals(SendResult.Refused(Refusal.DUPLICATE_CLIENT_ID), engine.send(SendCommand("c1", null, "91234567", "hallo", origin = Origin.KID_SCREEN)))
        engine.drainOutbox()
        engine.drainOutbox()
        assertEquals(listOf("+4791234567" to "hei"), sent)
        assertEquals(Delivery.SENDING, store.message(first.message.id.value)!!.delivery)
    }

    @Test
    fun `a refused send leaves nothing in the store`() {
        assertEquals(SendResult.Refused(Refusal.NOT_ALLOWED), engine.send(SendCommand("c2", null, "+4790000003", "hei", origin = Origin.KID_SCREEN)))
        assertEquals(SendResult.Refused(Refusal.EMPTY), engine.send(SendCommand("c3", null, "91234567", "  ", origin = Origin.KID_SCREEN)))
        assertEquals(SendResult.Refused(Refusal.TOO_LARGE), engine.send(SendCommand("c4", null, "91234567", "x".repeat(SmsEngine.SEND_MAX + 1), origin = Origin.KID_SCREEN)))
        assertTrue(engine.conversations().isEmpty())
        assertNull(store.messageByClientId("c2"))
    }

    @Test
    fun `sent once every part is sent, delivered by the report, failed by any part`() {
        parts = 2
        val m = (engine.send(SendCommand("c5", null, "91234567", "lang", origin = Origin.KID_SCREEN)) as SendResult.Accepted).message
        engine.drainOutbox()
        engine.onSent(m.id.value, 0, 2, ok = true, code = 0)
        assertEquals(Delivery.SENDING, store.message(m.id.value)!!.delivery)
        engine.onSent(m.id.value, 1, 2, ok = true, code = 0)
        assertEquals(Delivery.SENT, store.message(m.id.value)!!.delivery)
        engine.onDelivered(m.id.value, ok = true)
        assertEquals(Delivery.DELIVERED, store.message(m.id.value)!!.delivery)
        val f = (engine.send(SendCommand("c6", null, "91234567", "x", origin = Origin.KID_SCREEN)) as SendResult.Accepted).message
        engine.drainOutbox()
        engine.onSent(f.id.value, 0, 2, ok = false, code = 7)
        assertEquals(Delivery.FAILED to "sms:7", store.message(f.id.value)!!.let { it.delivery to it.failure })
    }

    @Test
    fun `SENDING with no result for 10 minutes is UNCERTAIN, never sent again, and a late result still applies`() {
        val m = (engine.send(SendCommand("c7", null, "91234567", "hei", origin = Origin.KID_SCREEN)) as SendResult.Accepted).message
        engine.drainOutbox()
        now += SmsEngine.UNCERTAIN_AFTER_MS - 1
        engine.sweepUncertain()
        assertEquals(Delivery.SENDING, store.message(m.id.value)!!.delivery)
        now += 1
        engine.sweepUncertain()
        assertEquals(Delivery.UNCERTAIN, store.message(m.id.value)!!.delivery)
        // A replay gives each message's state as it is now.
        assertTrue(engine.events(0).any { it is EngineEvent.MessageUpdated && it.delivery == Delivery.UNCERTAIN })
        engine.drainOutbox()
        assertEquals(1, sent.size)
        engine.onSent(m.id.value, 0, 1, ok = true, code = 0)
        assertEquals(Delivery.SENT, store.message(m.id.value)!!.delivery)
    }

    @Test
    fun `a queued message the rules no longer allow fails instead of going out`() {
        val m = (engine.send(SendCommand("c8", null, "91234567", "hei", origin = Origin.KID_SCREEN)) as SendResult.Accepted).message
        rules = rules!!.copy(smsMode = ChannelMode.OFF)
        engine.drainOutbox()
        assertTrue(sent.isEmpty())
        assertEquals("refused:sms_off", store.message(m.id.value)!!.failure)
    }

    // ---- rules change, delete ----

    @Test
    fun `a contact losing sms_out loses the compose bar, keeps the history (ConversationChanged)`() {
        engine.receiveSms(sms("+4791234567"))
        rules = rules(listOf(mamma.copy(smsOut = false), trener))
        val changed = engine.rulesChanged()
        assertEquals(1, changed.size)
        assertFalse(engine.conversations().single().canSend)
        assertTrue(engine.events(0).any { it is EngineEvent.ConversationChanged })
        assertEquals(1, engine.messages(changed.single(), null, 10).size)
        assertTrue("nothing changes twice", engine.rulesChanged().isEmpty())
    }

    @Test
    fun `a delete emits MessageDeleted and the read mark Read`() {
        val shown = engine.receiveSms(sms("+4791234567")) as Received.Shown
        engine.markRead(ConversationId(shown.conversation.id), shown.message.seq, Origin.KID_SCREEN)
        assertEquals(0, engine.conversations().single().unread)
        engine.delete(shown.message.id, Origin.KID_SCREEN)
        val events = engine.events(0)
        assertTrue(events.any { it is EngineEvent.Read })
        assertEquals(Origin.KID_SCREEN, (events.last() as EngineEvent.MessageDeleted).origin)
        assertTrue(engine.conversations().isEmpty())
    }

    // ---- D15: held for the rules ----

    @Test
    fun `held while the rules can't be read, judged when they return - with the time it arrived`() {
        rules = null
        val arrived = now
        assertEquals(Received.HeldForRules, engine.receiveSms(sms("+4791234567")))
        assertEquals(Received.HeldForRules, engine.receiveSms(sms("+4741414141")))
        assertTrue(engine.conversations().isEmpty())
        assertTrue("never posted", engine.unreported(50).isEmpty())
        now += 60_000
        rules = rules(listOf(mamma))
        val shown = engine.judgeHeldForRules()
        assertEquals(arrived, shown.single().message.at)
        assertEquals("the stranger goes to the parent", 1, held().size)
        assertTrue(store.messagesHeldForRules().isEmpty())
    }

    @Test
    fun `held for the rules is capped`() {
        rules = null
        repeat(HoldPolicy.RULES_MAX) { engine.receiveSms(sms("+4791234567")) }
        assertTrue(engine.receiveSms(sms("+4791234567", "lost")) is Received.Blocked)
        assertEquals(1L, store.counters()["blocked.over_cap"])
    }

    // ---- D16: held for the parent ----

    @Test
    fun `a held message is released only for its exact sender`() {
        val held = (engine.receiveSms(sms("+4741414141", "fra en fremmed")) as Received.HeldForParent).row
        engine.receiveSms(sms("DNB", "kode", SenderKind.ALPHANUMERIC))
        // A contact for another number releases nothing; a listed name releases no number.
        rules = rules(listOf(mamma, RuleContact(9, "Annen", "+4741414142", smsIn = true)), names = listOf("+4741414141"))
        assertTrue(engine.releasePass(emptyList()).isEmpty())
        rules = rules(listOf(mamma, RuleContact(9, "Ny", "+4741414141", smsIn = true)))
        val released = engine.releasePass(emptyList()).single()
        assertEquals("fra en fremmed", released.message.body)
        assertEquals(held.at, released.message.at)
        assertEquals(HeldState.RELEASED, store.held(held.id)!!.state)
        assertNull("the body moved out of held", store.held(held.id)!!.body)
        // The name is still held until it is listed.
        rules = rules!!.copy(smsNames = listOf("dnb"))
        assertEquals("kode", engine.releasePass(emptyList()).single().message.body)
    }

    @Test
    fun `expiry at 24 hours deletes the body and runs before a release`() {
        val row = (engine.receiveSms(sms("+4741414141")) as Received.HeldForParent).row
        now += HoldPolicy.WINDOW_MS
        // The offline phone's first policy has the allow: expiry first, so nothing is released.
        rules = rules(listOf(RuleContact(9, "Ny", "+4741414141", smsIn = true)))
        assertTrue(engine.releasePass(emptyList()).isEmpty())
        val expired = store.held(row.id)!!
        assertEquals(HeldState.EXPIRED, expired.state)
        assertNull(expired.body)
        assertTrue(store.deletePasses > 0)
        assertEquals("expired", engine.unreported(50).single().state)
    }

    @Test
    fun `drops come first - a row deleted and then made a contact is deleted`() {
        val a = (engine.receiveSms(sms("+4741414141")) as Received.HeldForParent).row
        val b = (engine.receiveSms(sms("+4741414141")) as Received.HeldForParent).row
        rules = rules(listOf(RuleContact(9, "Ny", "+4741414141", smsIn = true)))
        val released = engine.releasePass(listOf(a.id))
        assertEquals(1, released.size)
        assertEquals(HeldState.DROPPED, store.held(a.id)!!.state)
        assertNull(store.held(a.id)!!.body)
        assertEquals(HeldState.RELEASED, store.held(b.id)!!.state)
    }

    @Test
    fun `an emergency-number contact's text is held, never released`() {
        assertTrue(engine.receiveSms(sms("113")) is Received.HeldForParent)
        assertTrue(engine.releasePass(emptyList()).isEmpty())
    }

    @Test
    fun `the pools and the per-sender caps never evict, and report hold_full`() {
        repeat(HoldPolicy.PER_NAME) { engine.receiveSms(sms("Posten", kind = SenderKind.ALPHANUMERIC)) }
        assertTrue("a fourth from one name", engine.receiveSms(sms("POSTEN", kind = SenderKind.ALPHANUMERIC)) is Received.Blocked)
        repeat(HoldPolicy.NAMES_POOL - HoldPolicy.PER_NAME) { engine.receiveSms(sms("Navn$it", kind = SenderKind.ALPHANUMERIC)) }
        assertTrue("the names pool is full", engine.receiveSms(sms("Annet", kind = SenderKind.ALPHANUMERIC)) is Received.Blocked)
        assertEquals(false to true, engine.counts().let { it.numbersFull to it.namesFull })
        // The numbers keep their own pool.
        repeat(HoldPolicy.PER_NUMBER) { engine.receiveSms(sms("+4741414141")) }
        assertTrue("an eleventh from one number", engine.receiveSms(sms("+4741414141")) is Received.Blocked)
        repeat(HoldPolicy.NUMBERS_POOL - HoldPolicy.PER_NUMBER) { engine.receiveSms(sms("+47414100%02d".format(it))) }
        assertTrue(engine.receiveSms(sms("+4741419999")) is Received.Blocked)
        assertEquals(true to true, engine.counts().let { it.numbersFull to it.namesFull })
        assertEquals(HoldPolicy.NUMBERS_POOL + HoldPolicy.NAMES_POOL, held().size)
        assertEquals(4L, store.counters()["blocked.over_cap"])
    }

    @Test
    fun `a long body is cut and marked`() {
        val row = (engine.receiveSms(sms("+4741414141", "a".repeat(2000))) as Received.HeldForParent).row
        assertEquals(HoldPolicy.BODY_MAX, row.body!!.length)
        assertTrue(row.body!!.endsWith("…"))
    }

    @Test
    fun `the posted rows are who and when, marked once, final rows go`() {
        val row = (engine.receiveSms(sms("+4741414141", "hemmelig")) as Received.HeldForParent).row
        engine.receiveSms(sms("12"))
        val posted = engine.unreported(50)
        assertEquals(listOf("held" to "+4741414141", "blocked" to ""), posted.map { it.state to it.sender })
        assertEquals(row.at + HoldPolicy.WINDOW_MS, posted.first().expiresAt)
        assertFalse(posted.toString().contains("hemmelig"))
        engine.markReported(posted)
        assertTrue(engine.unreported(50).isEmpty())
        assertTrue(store.blockedRows().isEmpty())
        // A state change is posted again; once the server has the final state the row goes.
        engine.releasePass(listOf(row.id))
        val dropped = engine.unreported(50).single()
        assertEquals("dropped", dropped.state)
        engine.markReported(listOf(dropped))
        assertNull(store.held(row.id))
    }

    @Test
    fun `a row that moved after it was posted is posted again`() {
        val row = (engine.receiveSms(sms("+4741414141")) as Received.HeldForParent).row
        val posted = engine.unreported(50)
        engine.releasePass(listOf(row.id))
        engine.markReported(posted)
        assertEquals("dropped", engine.unreported(50).single().state)
    }
}
