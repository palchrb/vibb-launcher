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
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.ReactionCommand
import com.kidslauncher.mdm.sms.engine.ReactionOp
import com.kidslauncher.mdm.sms.engine.ReactionRef
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
    private val engine = SmsEngine(store, { now }, { judge() }, radio, ids, emojiList = { EmojiAsset.list })
    private var timeBlocked = false

    private fun rules(contacts: List<RuleContact>, names: List<String> = emptyList()) =
        CallRules(callsEnabled = true, smsEnabled = false, contacts = contacts, callsMode = ChannelMode.MANAGED, smsMode = ChannelMode.MANAGED, smsNames = names)

    private fun judge() = SmsJudge(
        state = rules?.let { CallPolicyState.Managed(it) } ?: CallPolicyState.UnknownFailClosed,
        roleHeld = true,
        timeBlocked = { timeBlocked },
        platformEmergency = { false },
    )

    /** Each its own message: the SMSC's time stamp moves on (a re-delivery would repeat it, QA L6). */
    private var smsc = 0L

    private fun sms(from: String, body: String = "hei", kind: SenderKind = SenderKind.NUMBER) =
        IncomingSms(kind, from, body, sentAt = now - 1000 + ++smsc, subId = 1, receivedAt = now)

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
    fun `a re-delivered SMS is stored once, and a day later its key is gone (QA L6)`() {
        val first = sms("+4791234567", "to ganger")
        assertTrue(engine.receiveSms(first) is Received.Shown)
        assertEquals(Received.Duplicate, engine.receiveSms(first.copy(receivedAt = now + 5_000)))
        val stranger = sms("+4790000041")
        assertTrue(engine.receiveSms(stranger) is Received.HeldForParent)
        assertEquals(Received.Duplicate, engine.receiveSms(stranger))
        assertEquals(1, engine.messages(engine.conversations().single().id, null, 10).size)
        assertEquals("the stranger is held once", 1, held().size)
        assertEquals(2L, store.counters()["received"])
        now += SmsEngine.RECEIVED_KEEP_MS + 1
        engine.prune()
        assertTrue(engine.receiveSms(first) is Received.Shown)
    }

    @Test
    fun `SMS not managed while the role is still ours - shown unfiltered, never lost (QA L3)`() {
        rules = rules!!.copy(smsMode = ChannelMode.UNMANAGED)
        val shown = engine.receiveSms(sms("+4790000041", "fra en fremmed")) as Received.Shown
        assertFalse(shown.message.unknownSender)
        assertTrue(engine.receiveSms(sms("+4791234567")) is Received.Shown)
        assertTrue(held().isEmpty())
    }

    @Test
    fun `MMS pushes are kept within their bounds and pruned after a week (QA L4)`() {
        val push = { at: Long -> com.kidslauncher.mdm.sms.engine.PendingPushRow(ids.hex(), ByteArray(100), null, at) }
        assertFalse("oversize", engine.keepPush(com.kidslauncher.mdm.sms.engine.PendingPushRow(ids.hex(), ByteArray(SmsEngine.PUSH_MAX + 1), null, now)))
        repeat(SmsEngine.PUSH_ROWS) { assertTrue(engine.keepPush(push(now))) }
        assertFalse("past the cap", engine.keepPush(push(now)))
        assertEquals(2L, store.counters()["blocked.malformed"])
        now += SmsEngine.PUSH_KEEP_MS + 1
        engine.prune()
        assertEquals(0, store.pendingPushCount())
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
        assertEquals(Received.HeldForRules, engine.receiveSms(sms("+4790000041")))
        assertTrue(engine.conversations().isEmpty())
        assertTrue("never posted", engine.unreported(50).isEmpty())
        now += 60_000
        rules = rules(listOf(mamma))
        val shown = engine.judgeHeldForRules()
        assertEquals(arrived, (shown.single() as Received.Shown).message.at)
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
        val held = (engine.receiveSms(sms("+4790000041", "fra en fremmed")) as Received.HeldForParent).row
        engine.receiveSms(sms("DNB", "kode", SenderKind.ALPHANUMERIC))
        // A contact for another number releases nothing; a listed name releases no number.
        rules = rules(listOf(mamma, RuleContact(9, "Annen", "+4790000042", smsIn = true)), names = listOf("+4790000041"))
        assertTrue(engine.releasePass(emptyList()).isEmpty())
        rules = rules(listOf(mamma, RuleContact(9, "Ny", "+4790000041", smsIn = true)))
        val released = engine.releasePass(emptyList()).single() as Received.Shown
        assertEquals("fra en fremmed", released.message.body)
        assertEquals(held.at, released.message.at)
        assertEquals(HeldState.RELEASED, store.held(held.id)!!.state)
        assertNull("the body moved out of held", store.held(held.id)!!.body)
        // The name is still held until it is listed.
        rules = rules!!.copy(smsNames = listOf("dnb"))
        assertEquals("kode", (engine.releasePass(emptyList()).single() as Received.Shown).message.body)
    }

    @Test
    fun `expiry at 24 hours deletes the body and runs before a release`() {
        val row = (engine.receiveSms(sms("+4790000041")) as Received.HeldForParent).row
        now += HoldPolicy.WINDOW_MS
        // The offline phone's first policy has the allow: expiry first, so nothing is released.
        rules = rules(listOf(RuleContact(9, "Ny", "+4790000041", smsIn = true)))
        assertTrue(engine.releasePass(emptyList()).isEmpty())
        val expired = store.held(row.id)!!
        assertEquals(HeldState.EXPIRED, expired.state)
        assertNull(expired.body)
        assertTrue(store.deletePasses > 0)
        assertEquals("expired", engine.unreported(50).single().state)
    }

    @Test
    fun `drops come first - a row deleted and then made a contact is deleted`() {
        val a = (engine.receiveSms(sms("+4790000041")) as Received.HeldForParent).row
        val b = (engine.receiveSms(sms("+4790000041")) as Received.HeldForParent).row
        rules = rules(listOf(RuleContact(9, "Ny", "+4790000041", smsIn = true)))
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
        repeat(HoldPolicy.PER_NUMBER) { engine.receiveSms(sms("+4790000041")) }
        assertTrue("an eleventh from one number", engine.receiveSms(sms("+4790000041")) is Received.Blocked)
        repeat(HoldPolicy.NUMBERS_POOL - HoldPolicy.PER_NUMBER) { engine.receiveSms(sms("+47900001%02d".format(it))) }
        assertTrue(engine.receiveSms(sms("+4790000199")) is Received.Blocked)
        assertEquals(true to true, engine.counts().let { it.numbersFull to it.namesFull })
        assertEquals(HoldPolicy.NUMBERS_POOL + HoldPolicy.NAMES_POOL, held().size)
        assertEquals(4L, store.counters()["blocked.over_cap"])
    }

    @Test
    fun `a long body is cut and marked`() {
        val row = (engine.receiveSms(sms("+4790000041", "a".repeat(2000))) as Received.HeldForParent).row
        assertEquals(HoldPolicy.BODY_MAX, row.body!!.length)
        assertTrue(row.body!!.endsWith("…"))
    }

    @Test
    fun `the posted rows are who and when, marked once, final rows go`() {
        val row = (engine.receiveSms(sms("+4790000041", "hemmelig")) as Received.HeldForParent).row
        engine.receiveSms(sms("12"))
        val posted = engine.unreported(50)
        assertEquals(listOf("held" to "+4790000041", "blocked" to ""), posted.map { it.state to it.sender })
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
        val row = (engine.receiveSms(sms("+4790000041")) as Received.HeldForParent).row
        val posted = engine.unreported(50)
        engine.releasePass(listOf(row.id))
        engine.markReported(posted)
        assertEquals("dropped", engine.unreported(50).single().state)
    }

    // ---- Design 81: reactions ----

    private val mammaNumber = "+4791234567"

    private fun kidSays(text: String): Message =
        (engine.send(SendCommand(java.util.UUID.randomUUID().toString(), null, mammaNumber, text, origin = Origin.KID_SCREEN)) as SendResult.Accepted).message

    private fun react(target: Message, emoji: String, op: ReactionOp = ReactionOp.ADD, clientId: String = java.util.UUID.randomUUID().toString()) =
        engine.send(SendCommand(clientId, target.conversationId, null, "", origin = Origin.KID_SCREEN, reaction = ReactionCommand(target.id, emoji, op, "nb")))

    private fun bubble(id: ConversationId, of: Message) = engine.messages(id, null, 10).single { it.id == of.id }

    @Test
    fun `an iPhone's reaction attaches to the bubble it names - its own outcome, not unread, nothing moves (D2-D6, D12)`() {
        val mine = kidSays("Kommer kl 5")
        val id = mine.conversationId
        val before = engine.conversation(id)!!
        now += 1000
        val attached = engine.receiveSms(sms(mammaNumber, "Likte «Kommer kl 5»")) as Received.Attached
        assertTrue(attached.notify)
        assertEquals(ReactionRef(mine.id, "👍", ReactionOp.ADD), attached.reaction.reaction)
        val after = engine.conversation(id)!!
        assertEquals(0, after.unread)
        assertEquals(before.lastSeq, after.lastSeq)
        // The engine folds (M2): the bubbles only, each with its reactions.
        assertEquals(listOf(mine.id), engine.messages(id, null, 10).map { it.id })
        assertEquals(listOf(mammaNumber to "👍"), bubble(id, mine).reactions.map { it.by to it.emoji })
        // The events carry the reaction row itself (the bridge's m.reaction, L8).
        assertEquals(attached.reaction.id, engine.events(0).filterIsInstance<EngineEvent.MessageAdded>().last().message.id)
        assertEquals(mine.id, engine.notifiable(id, 5).single().target!!.id)
    }

    @Test
    fun `a replacement and a removal change the badge and the lines, a real message's line stays (D4, D12)`() {
        val mine = kidSays("Kommer kl 5")
        val id = mine.conversationId
        engine.receiveSms(sms(mammaNumber, "Hallo?"))
        engine.receiveSms(sms(mammaNumber, "Likte «Kommer kl 5»"))
        assertTrue(engine.receiveSms(sms(mammaNumber, "Satte et hjerte ved «Kommer kl 5»")) is Received.Attached)
        assertEquals(listOf("❤️"), bubble(id, mine).reactions.map { it.emoji })
        assertEquals(listOf("Hallo?", "Satte et hjerte ved «Kommer kl 5»"), engine.notifiable(id, 5).map { it.message.body })
        val removed = engine.receiveSms(sms(mammaNumber, "Fjernet et hjerte fra «Kommer kl 5»")) as Received.Attached
        assertFalse("a removal never notifies", removed.notify)
        assertTrue(bubble(id, mine).reactions.isEmpty())
        assertEquals(listOf("Hallo?"), engine.notifiable(id, 5).map { it.message.body })
        assertEquals(1, engine.conversation(id)!!.unread)
        // With nothing left to remove it is a message (D4).
        assertTrue(engine.receiveSms(sms(mammaNumber, "Fjernet et hjerte fra «Kommer kl 5»")) is Received.Shown)
    }

    @Test
    fun `reading the thread marks a reaction that came later read too`() {
        val mine = kidSays("Ok")
        val id = mine.conversationId
        engine.receiveSms(sms(mammaNumber, "Hei"))
        engine.markRead(id, engine.conversation(id)!!.lastSeq, Origin.KID_SCREEN)
        engine.receiveSms(sms(mammaNumber, "Likte «Ok»"))
        assertEquals(0, engine.conversation(id)!!.unread)
        assertEquals(1, engine.notifiable(id, 5).size)
        // The thread marks up to its newest bubble, older than the reaction: the reaction on it goes read with it.
        engine.markRead(id, engine.conversation(id)!!.lastSeq, Origin.KID_SCREEN)
        assertTrue(engine.notifiable(id, 5).isEmpty())
    }

    @Test
    fun `a sentence naming no bubble, and a reaction the parent released, stay text (N3, D10)`() {
        val mine = kidSays("Ok")
        val id = mine.conversationId
        assertTrue(engine.receiveSms(sms(mammaNumber, "Likte «filmen»")) is Received.Shown)
        // Mamma taken out of the phone book: her reaction is held; let in again, it shows as the text it is.
        rules = rules(listOf(trener, ambulanse))
        assertTrue(engine.receiveSms(sms(mammaNumber, "Likte «Ok»")) is Received.HeldForParent)
        rules = rules(listOf(mamma, trener, ambulanse))
        assertEquals("Likte «Ok»", (engine.releasePass(emptyList()).single() as Received.Shown).message.body)
        assertTrue(bubble(id, mine).reactions.isEmpty())
    }

    @Test
    fun `the kid's reaction goes as Apple's words through the outbox - pending, then sent, a failed change drops out (D4, D8)`() {
        val hei = (engine.receiveSms(sms(mammaNumber, "Hei")) as Received.Shown).message
        val id = hei.conversationId
        val like = react(hei, "👍") as SendResult.Accepted
        assertEquals("Likte «Hei»", like.message.body)
        assertEquals(listOf<String?>(null), bubble(id, hei).reactions.map { it.by })
        assertTrue(bubble(id, hei).reactions.single().pending)
        engine.drainOutbox()
        assertEquals(mammaNumber to "Likte «Hei»", sent.last())
        engine.onSent(like.message.id.value, 0, 1, true, 0)
        assertFalse(bubble(id, hei).reactions.single().pending)
        val change = react(hei, "🎉") as SendResult.Accepted
        assertEquals("Reagerte med 🎉 på «Hei»", change.message.body)
        engine.drainOutbox()
        engine.onSent(change.message.id.value, 0, 1, false, 1)
        assertEquals(listOf("👍"), bubble(id, hei).reactions.map { it.emoji })
        // No bubble of its own: the thread and the list's last line see only "Hei".
        assertEquals(listOf(hei.id), engine.messages(id, null, 10).map { it.id })
        assertEquals(hei.seq, engine.conversation(id)!!.lastSeq)
    }

    @Test
    fun `the gate refuses a reaction as it refuses a text, and a repeated clientId is the same reaction (L5)`() {
        val hei = (engine.receiveSms(sms(mammaNumber, "Hei")) as Received.Shown).message
        val mine = kidSays("Ok")
        timeBlocked = true
        assertEquals(SendResult.Refused(Refusal.TIME_RULE), react(hei, "👍"))
        timeBlocked = false
        assertEquals("the kid's own bubble", SendResult.Refused(Refusal.NOT_ALLOWED), react(mine, "👍"))
        assertEquals("not a listed emoji", SendResult.Refused(Refusal.NOT_ALLOWED), react(hei, "hei"))
        assertEquals("nothing to remove", SendResult.Refused(Refusal.NOT_ALLOWED), react(hei, "👍", ReactionOp.REMOVE))
        val first = react(hei, "👍", clientId = "c1") as SendResult.Accepted
        assertEquals(first.message.id, (react(hei, "👍", clientId = "c1") as SendResult.Accepted).message.id)
        assertEquals(SendResult.Refused(Refusal.DUPLICATE_CLIENT_ID), react(hei, "❤️", clientId = "c1"))
        assertEquals(1, store.reactions(listOf(hei.id.value)).size)
    }

    @Test
    fun `Fjern reaksjonene takes only others' reactions, a deleted bubble takes all of them, each told (D5, L8)`() {
        val hei = (engine.receiveSms(sms(mammaNumber, "Hei")) as Received.Shown).message
        val id = hei.conversationId
        val mammas = engine.receiveSms(sms(mammaNumber, "Likte «Hei»")) as Received.Attached
        val kids = react(hei, "😂") as SendResult.Accepted
        engine.removeReactions(hei.id, Origin.KID_SCREEN)
        assertEquals(listOf<String?>(null), bubble(id, hei).reactions.map { it.by })
        assertTrue(engine.events(0).filterIsInstance<EngineEvent.MessageDeleted>().any { it.id == mammas.reaction.id })
        engine.delete(hei.id, Origin.KID_SCREEN)
        val deleted = engine.events(0).filterIsInstance<EngineEvent.MessageDeleted>().map { it.id }
        assertTrue(deleted.containsAll(listOf(hei.id, kids.message.id)))
        assertTrue(store.reactions(listOf(hei.id.value)).isEmpty())
    }
}
