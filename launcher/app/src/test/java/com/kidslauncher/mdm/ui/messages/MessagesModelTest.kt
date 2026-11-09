package com.kidslauncher.mdm.ui.messages

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.sms.FakeMessageStore
import com.kidslauncher.mdm.sms.engine.Conversation
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.EngineEvent
import com.kidslauncher.mdm.sms.engine.IdSource
import com.kidslauncher.mdm.sms.engine.IncomingSms
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Part
import com.kidslauncher.mdm.sms.engine.PartState
import com.kidslauncher.mdm.sms.engine.Participant
import com.kidslauncher.mdm.sms.engine.Received
import com.kidslauncher.mdm.sms.engine.Refusal
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.engine.SmsRadio
import com.kidslauncher.mdm.timerules.TimeLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Design 77 §6.7, step 2: the kid's message screens as data - the tile, the list, the thread, the compose gate, the
 * read-only history, the deletes and the retry. */
class MessagesModelTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val mamma = RuleContact(1, "Mamma", "+4790000001", inbound = true, outbound = true, smsIn = true, smsOut = true)
    private val trener = RuleContact(3, "Trener", "+4790000003", inbound = true, outbound = true, smsIn = true)
    private val bestemor = RuleContact(5, "Bestemor", "+4790000005", smsOut = true)

    private fun rules(sms: ChannelMode = ChannelMode.MANAGED, contacts: List<RuleContact> = listOf(mamma, trener, bestemor)) =
        CallRules(callsEnabled = true, contacts = contacts, callsMode = ChannelMode.MANAGED, smsMode = sms)

    private fun judge(rules: CallRules? = rules(), role: Boolean = true, timeBlocked: Boolean = false) = SmsJudge(
        state = rules?.let { CallPolicyState.Managed(it) } ?: CallPolicyState.UnknownFailClosed,
        roleHeld = role,
        timeBlocked = { timeBlocked },
        platformEmergency = { false },
    )

    private fun conversation(id: String, address: String, contactId: Long? = null, name: String? = null, lastSeq: Long = 1, unread: Int = 0) =
        Conversation(ConversationId(id), listOf(Participant(address, contactId)), name, null, lastSeq, 0, unread, canSend = false)

    private fun message(id: String, seq: Long, at: Long, direction: Direction = Direction.IN, delivery: Delivery = Delivery.RECEIVED, body: String? = "hei", parts: List<Part> = emptyList()) =
        Message(
            MessageId(id), ConversationId("c"), seq, direction, null, false, at, null, body, parts, delivery, null, read = true,
            clientId = null, origin = Origin.ENGINE,
        )

    // ---- Home's tile, the time rule ----

    @Test
    fun `the tile shows in Styrt av Vibb with the role, and wherever there is history`() {
        assertTrue(messagesTileShown(ChannelMode.MANAGED, roleHeld = true, hasHistory = false))
        assertFalse(messagesTileShown(ChannelMode.MANAGED, roleHeld = false, hasHistory = false))
        for (mode in listOf(ChannelMode.OFF, ChannelMode.UNMANAGED, null)) {
            assertFalse("$mode", messagesTileShown(mode, roleHeld = true, hasHistory = false))
            assertTrue("$mode", messagesTileShown(mode, roleHeld = false, hasHistory = true))
        }
    }

    @Test
    fun `under a time rule Meldinger is reachable only where calls are`() {
        assertTrue(messagesUsableUnder(null))
        assertTrue(messagesUsableUnder(TimeLock(LockReason.NONE)))
        assertTrue(messagesUsableUnder(TimeLock(LockReason.RULE, callsAllowed = true)))
        // The used-up budget keeps calls, so it keeps Meldinger.
        assertTrue(messagesUsableUnder(TimeLock(LockReason.SCREEN_TIME, budgetExhausted = true, callsAllowed = true)))
        assertFalse(messagesUsableUnder(TimeLock(LockReason.SCHOOL, callsAllowed = false)))
    }

    // ---- the compose gate and the read-only history ----

    @Test
    fun `the compose bar shows only what the gate would send now`() {
        assertEquals(ComposeState.OPEN, composeState(judge(), "+4790000001"))
        // A text-only contact (D3: sms_out without calls) can be texted.
        assertEquals(ComposeState.OPEN, composeState(judge(), "+4790000005"))
        // A contact without "Kan få melding fra", a stranger, a sender name, a group: no bar.
        assertEquals(ComposeState.NOT_ALLOWED, composeState(judge(), "+4790000003"))
        assertEquals(ComposeState.NOT_ALLOWED, composeState(judge(), "+4790000041"))
        assertEquals(ComposeState.NOT_ALLOWED, composeState(judge(), "name:DNB"))
        assertEquals(ComposeState.NOT_ALLOWED, composeState(judge(), "unknown:x"))
        assertEquals(ComposeState.NOT_ALLOWED, composeState(judge(), null))
        // A time rule without calls: "Du kan ikke sende meldinger nå".
        assertEquals(ComposeState.NOT_NOW, composeState(judge(timeBlocked = true), "+4790000001"))
        // "Styrt av Vibb" but nothing can go out: the rules unknown, the role not ours.
        assertEquals(ComposeState.UNAVAILABLE, composeState(judge(rules = null), "+4790000001"))
        assertEquals(ComposeState.UNAVAILABLE, composeState(judge(role = false), "+4790000001"))
    }

    @Test
    fun `in Av and Ikke styrt the history is read-only, also for an emergency number`() {
        for (mode in listOf(ChannelMode.OFF, ChannelMode.UNMANAGED)) {
            assertEquals(ComposeState.READ_ONLY, composeState(judge(rules(mode)), "+4790000001"))
            assertEquals(ComposeState.READ_ONLY, composeState(judge(rules(mode)), "112"))
            assertEquals(ComposeState.READ_ONLY, composeState(judge(rules(mode)), null))
        }
        assertEquals(ComposeState.READ_ONLY, composeState(SmsJudge(CallPolicyState.Unmanaged, roleHeld = false), "+4790000001"))
        assertEquals(ComposeState.NOT_NOW, refusalState(Refusal.TIME_RULE))
        assertEquals(ComposeState.READ_ONLY, refusalState(Refusal.SMS_OFF))
    }

    @Test
    fun `Ny melding lists only the contacts with sms_out, in the parent's order`() {
        assertEquals(listOf(mamma, bestemor), newMessageContacts(rules()))
        assertTrue(newMessageContacts(null).isEmpty())
        assertTrue(newMessageContacts(rules(contacts = listOf(mamma.copy(smsOut = false), bestemor.copy(number = "")))).isEmpty())
    }

    // ---- the list ----

    @Test
    fun `the list is newest first by seq, named by the phone book, else as it was`() {
        val old = conversation("a", "+4790000001", contactId = 1, name = "Mamma", lastSeq = 3)
        val newer = conversation("b", "name:DNB", name = "DNB", lastSeq = 9)
        val removed = conversation("c", "+4790000009", contactId = 9, name = "Onkel", lastSeq = 5)
        val stranger = conversation("d", "+4790000041", lastSeq = 7)
        val empty = conversation("e", "+4790000005", lastSeq = 11)
        val last = message("m", 1, 0)
        val rows = listRows(listOf(old to last, newer to last, removed to last, stranger to last, empty to null), rules(contacts = listOf(mamma.copy(name = "Mor"))))
        assertEquals(listOf("b", "d", "c", "a"), rows.map { it.conversation.id.value })
        assertEquals(
            listOf(Who.Sender("DNB"), Who.Number("+4790000041"), Who.Name("Onkel"), Who.Name("Mor")),
            rows.map { it.who },
        )
        assertEquals(1L, rows.last().contact?.id)
        assertNull(rows[2].contact)
        assertEquals(Who.Unknown, whoOf(conversation("f", "unknown:x"), null))
        assertEquals("b", conversationOf("name:DNB", listOf(old, newer))?.id?.value)
        assertEquals("a", conversationOf("+4790000001", listOf(old, newer))?.id?.value)
        assertNull(conversationOf("+4790000002", listOf(old, newer)))
    }

    @Test
    fun `the last line is the text on one line, a picture is Bilde`() {
        assertEquals(LastLine.Text("hei du", mine = false), lastLine(message("m", 1, 0, body = "hei\n du ")))
        assertEquals(LastLine.Text("ok", mine = true), lastLine(message("m", 1, 0, Direction.OUT, Delivery.SENT, body = "ok")))
        val picture = Part(0, "image/jpeg", 10, "aa", 4, 3, PartState.AVAILABLE)
        assertEquals(LastLine.Picture(mine = false), lastLine(message("m", 1, 0, body = null, parts = listOf(picture))))
        assertEquals(LastLine.Empty, lastLine(null))
    }

    @Test
    fun `the list's times - the clock today, yesterday, the weekday this week, else the date`() {
        val now = at(9, 15)
        assertEquals(TimeLabel.Clock(at(9, 8)), listTimeLabel(at(9, 8), now, zone))
        assertEquals(TimeLabel.Clock(at(9, 20)), listTimeLabel(at(9, 20), now, zone))
        assertEquals(TimeLabel.Yesterday, listTimeLabel(at(8, 23, 59), now, zone))
        assertEquals(TimeLabel.Weekday(DayOfWeek.TUESDAY), listTimeLabel(at(3, 12), now, zone))
        assertEquals(TimeLabel.Date(LocalDate.of(2026, 11, 2), withYear = false), listTimeLabel(at(2, 12), now, zone))
        val lastYear = LocalDateTime.of(2025, 12, 30, 10, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(TimeLabel.Date(LocalDate.of(2025, 12, 30), withYear = true), listTimeLabel(lastYear, now, zone))
    }

    // ---- the thread ----

    @Test
    fun `the thread runs by seq with a separator for each day, and the delivery under the kid's last`() {
        val now = at(9, 15)
        val items = threadItems(
            listOf(
                message("c", 3, at(9, 9), Direction.OUT, Delivery.DELIVERED),
                message("a", 1, at(8, 22)),
                message("b", 2, at(9, 8), Direction.OUT, Delivery.SENT),
                message("d", 4, at(9, 10)),
            ),
            now, zone,
        )
        assertEquals(
            listOf("day:Yesterday", "a", "day:Today", "b", "c", "d"),
            items.map { if (it is ThreadItem.Day) "day:${it.label.javaClass.simpleName}" else (it as ThreadItem.Bubble).message.id.value },
        )
        val bubbles = items.filterIsInstance<ThreadItem.Bubble>().associate { it.message.id.value to it.delivery }
        assertEquals(mapOf("a" to null, "b" to null, "c" to DeliveryLine.DELIVERED, "d" to null), bubbles)
        assertEquals(DayLabel.Day(LocalDate.of(2026, 11, 3)), dayLabel(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `a failed or uncertain message says so wherever it is, the others only when last`() {
        val failed = message("f", 1, 0, Direction.OUT, Delivery.FAILED)
        assertEquals(DeliveryLine.NOT_SENT, deliveryLine(failed, lastOut = false))
        assertEquals(DeliveryLine.MAYBE_NOT_SENT, deliveryLine(failed.copy(delivery = Delivery.UNCERTAIN), lastOut = false))
        assertEquals(DeliveryLine.SENDING, deliveryLine(failed.copy(delivery = Delivery.QUEUED), lastOut = true))
        assertEquals(DeliveryLine.SENDING, deliveryLine(failed.copy(delivery = Delivery.SENDING), lastOut = true))
        assertNull(deliveryLine(failed.copy(delivery = Delivery.SENT), lastOut = false))
        assertNull(deliveryLine(message("i", 1, 0), lastOut = true))
    }

    @Test
    fun `parts - our own picture, else a line for what isn't shown`() {
        fun part(index: Int, mime: String, state: PartState) = Part(index, mime, 1, null, null, null, state)
        assertEquals(
            listOf(PartView.Picture(1, null, null, null), PartView.Waiting, PartView.NotShown, PartView.Failed, PartView.Unsupported),
            partViews(
                listOf(
                    part(5, "video/mp4", PartState.AVAILABLE),
                    part(0, "text/plain", PartState.AVAILABLE),
                    part(1, "image/jpeg", PartState.AVAILABLE),
                    part(2, "image/jpeg", PartState.WAITING_DATA),
                    part(3, "image/jpeg", PartState.NOT_SHOWN),
                    part(4, "image/jpeg", PartState.TOO_LARGE),
                ),
            ),
        )
        assertTrue(partViews(emptyList()).isEmpty())
    }

    // ---- the deletes and the retry, through the engine ----

    private var now = at(9, 12)
    private var engineRules: CallRules? = rules()
    private val sent = mutableListOf<String>()
    private var counter = 0
    private val engine = SmsEngine(
        FakeMessageStore(), { now }, { judge(engineRules) },
        object : SmsRadio {
            override fun send(messageId: String, address: String, body: String, subId: Int?, deliveryReport: Boolean): Int {
                sent += body
                return 1
            }
        },
        object : IdSource {
            override fun ulid(now: Long) = "U%022d".format(++counter)
            override fun hex() = "%032x".format(++counter)
        },
    )

    private fun receive(from: String, body: String) =
        engine.receiveSms(IncomingSms(SenderKind.NUMBER, from, body, sentAt = now + ++counter, subId = 1, receivedAt = now)) as Received.Shown

    @Test
    fun `deleting a message leaves the rest, deleting the conversation leaves nothing - each told as MessageDeleted`() {
        val first = receive("+4790000001", "en")
        receive("+4790000001", "to")
        val id = ConversationId(first.conversation.id)
        MessageActions.delete(engine, DeleteTarget.OneMessage(first.message.id))
        assertEquals(listOf("to"), engine.messages(id, null, 10).map { it.body })
        MessageActions.delete(engine, DeleteTarget.WholeConversation(id))
        assertTrue(engine.conversations().isEmpty())
        val deleted = engine.events(0).filterIsInstance<EngineEvent.MessageDeleted>()
        assertEquals(2, deleted.size)
        assertTrue(deleted.all { it.origin == Origin.KID_SCREEN })
    }

    @Test
    fun `try again sends the text with a new id and drops the failed one, a refusal keeps it`() {
        val failed = (engine.send(SendCommand("first", null, "+4790000001", "kommer snart", origin = Origin.KID_SCREEN)) as SendResult.Accepted).message
        engine.drainOutbox()
        engine.onSent(failed.id.value, 0, 1, ok = false, code = 1)
        val stored = engine.messages(failed.conversationId, null, 10).single()
        assertEquals(Delivery.FAILED, stored.delivery)
        // A refusal (SMS switched off meanwhile) keeps the failed message.
        engineRules = rules(ChannelMode.OFF)
        val refused = MessageActions.retry(engine::send, { engine.delete(it, Origin.KID_SCREEN) }, stored, "second")
        assertEquals(SendResult.Refused(Refusal.SMS_OFF), refused)
        assertEquals(listOf(stored.id), engine.messages(failed.conversationId, null, 10).map { it.id })
        // Allowed again: the same text once more, under a new id; the failed one goes.
        engineRules = rules()
        val again = MessageActions.retry(engine::send, { engine.delete(it, Origin.KID_SCREEN) }, stored, "third") as SendResult.Accepted
        assertEquals("third", again.message.clientId)
        assertEquals(listOf(again.message.id), engine.messages(failed.conversationId, null, 10).map { it.id })
        engine.drainOutbox()
        assertEquals(listOf("kommer snart", "kommer snart"), sent)
        // Only a failed or uncertain message of the kid's is ever sent again.
        assertNull(MessageActions.retry(engine::send, {}, again.message, "fourth"))
    }

    @Test
    fun `the compose bar's id - two taps with one id send once (QA N2)`() {
        val command = SendCommand("draft-1", null, "+4790000001", "hei", origin = Origin.KID_SCREEN)
        val first = engine.send(command) as SendResult.Accepted
        val second = engine.send(command) as SendResult.Accepted
        assertEquals(first.message.id, second.message.id)
        engine.drainOutbox()
        assertEquals(listOf("hei"), sent)
    }
}
