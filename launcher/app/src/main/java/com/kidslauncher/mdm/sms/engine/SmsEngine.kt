package com.kidslauncher.mdm.sms.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * The message engine (design 77 §6.2): receive, the gate, the store, the outbox and delivery, holding for the rules
 * (D15) and for the parent (D16), the event log. Pure Kotlin over [MessageStore] and [SmsRadio] - no Android imports,
 * so SmsEngineTest runs it on the JVM with an in-memory store. `SmsRuntime` gives it the SQLite store, the platform's
 * SmsManager, the rules in memory and its threads (D7: the receive path on its own executor, everything else on the
 * engine thread).
 */

/** An SMS as `SMS_DELIVER` brought it, its parts joined. [kind] comes from the raw PDUs ([SmsSenderType]). */
data class IncomingSms(
    val kind: SenderKind,
    val address: String?,
    val body: String,
    val sentAt: Long?,
    val subId: Int?,
    val receivedAt: Long,
)

/** What became of an incoming message (the receive path notifies after `finish()` from it). */
sealed interface Received {
    data class Shown(val message: Message, val conversation: ConversationRow) : Received
    data object HeldForRules : Received
    data class HeldForParent(val row: HeldRow) : Received
    data class Blocked(val row: BlockedRow?) : Received

    /** QA L6: the platform delivered this message again; it is already stored (or held, or blocked). */
    data object Duplicate : Received
}

/**
 * A received SMS's identity for [SmsEngine]'s de-duplication (QA L6): its sender as received, the SMSC's time stamp and
 * the body, hashed - the text itself is never kept for it. Two messages differ in one of them, a re-delivery doesn't.
 */
fun receiveKey(sms: IncomingSms): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update("${sms.address}\u0000${sms.sentAt}\u0000${sms.body}".toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** The platform's SMS sending, behind an interface for the JVM tests. */
interface SmsRadio {
    /** Hands [body] to the platform for [address] on [subId] (`null` = the default SMS subscription), one sent result
     * per part to [SmsEngine.onSent] and, with [deliveryReport], a delivery report for the last part to
     * [SmsEngine.onDelivered]. Returns the number of parts; throws if the platform refused. */
    fun send(messageId: String, address: String, body: String, subId: Int?, deliveryReport: Boolean): Int
}

/** A row of `POST /api/devices/sms-held` (§7): who and when, never what. */
data class HeldPost(
    val id: String,
    val at: Long,
    val kind: String,
    val senderClass: String,
    val sender: String,
    val expiresAt: Long,
    val state: String,
)

/** The counters and the pools for `sms_state` (§7); [mmsWaiting]: MMS waiting for data (D14). */
data class EngineCounts(val counters: Map<String, Long>, val numbersFull: Boolean, val namesFull: Boolean, val mmsWaiting: Int = 0)

class SmsEngine(
    private val store: MessageStore,
    private val clock: () -> Long,
    /** The rules in force now (read from memory, never decoded per message). */
    private val judge: () -> SmsJudge,
    private val radio: SmsRadio,
    private val ids: IdSource = RandomIds,
    mmsRadio: MmsRadio = MmsRadio.None,
    files: PartFiles = PartFiles.None,
) : MessageEngine {
    /** Step 3b: the MMS half (§6.4, D10-D12, D14) over the same store, events and conversations. */
    val mms = MmsFlow(this, store, clock, judge, ids, mmsRadio, files)

    /**
     * The live events: a "something changed" signal for the front ends on the phone, never the record (that is
     * [events]'s replayable log). A burst larger than the buffer - a long conversation deleted, a rule change over many
     * conversations - keeps the newest and drops the oldest, so [emit] never fails and the last change always arrives
     * (code QA of step 2, L1: with the default it dropped the newest for every collector).
     */
    private val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val live: Flow<EngineEvent> = events.asSharedFlow()

    // ---- Receive (D4, D7) ----

    /** D7's steps 2 and 3: judge (the rules are in memory) and commit the message, the held row or the blocked row
     * with its event, in one transaction. Called on the receive executor; nothing slow happens here. */
    fun receiveSms(sms: IncomingSms): Received {
        val judge = judge()
        val verdict = SmsRules.decideIncoming(sms.kind, sms.address, judge)
        val clean = SenderClean.of(sms.kind, sms.address, judge.defaultCc)
        val key = receiveKey(sms)
        val received = store.transaction {
            // QA L6: the platform delivers a message again when its raw row outlived our commit (a telephony restart).
            if (store.receivedBefore(key)) return@transaction Received.Duplicate
            store.rememberReceived(key, sms.receivedAt)
            store.addCounter(COUNT_RECEIVED)
            when (verdict) {
                is IncomingVerdict.Allow -> show(sms, clean, verdict, sms.receivedAt)
                IncomingVerdict.HoldRules -> holdForRules(sms, clean)
                IncomingVerdict.HoldParent -> holdForParent(sms, clean)
                IncomingVerdict.Block -> Received.Blocked(block(sms.receivedAt, "sms", clean, REASON_RULE))
            }
        }
        if (received is Received.Shown) emit(EngineEvent.MessageAdded(received.message.seq, received.message))
        return received
    }

    /** Inside a transaction: the conversation found or made, the message and its `MessageAdded`. */
    internal fun show(sms: IncomingSms, clean: CleanSender, verdict: IncomingVerdict.Allow, at: Long, origin: Origin = Origin.ENGINE): Received.Shown {
        // A sender without a usable address reaches here only unfiltered (QA L3): its own conversation, never a number's.
        val address = addressOf(clean).ifEmpty { UNKNOWN_PREFIX + com.kidslauncher.mdm.day.TitleRules.clean(sms.address, 20) }
        val conversation = conversationFor(address, verdict.contact?.id, verdict.contact?.name ?: nameOf(clean), sms.subId)
        val seq = store.nextSeq()
        val row = MessageRow(
            id = ids.ulid(clock()),
            conversationId = conversation.id,
            seq = seq,
            direction = Direction.IN,
            sender = address,
            senderKind = sms.kind,
            unknownSender = verdict.unknownSender,
            at = at,
            sentAt = sms.sentAt,
            body = sms.body,
            delivery = Delivery.RECEIVED,
            failure = null,
            read = false,
            clientId = null,
            origin = origin,
            verdict = MessageVerdict.SHOWN,
            subId = sms.subId,
        )
        store.insertMessage(row)
        store.insertEvent(EventRow(seq, EventType.MESSAGE_ADDED, row.id, conversation.id, origin, null, clock()))
        return Received.Shown(row.toMessage(), conversation)
    }

    private fun holdForRules(sms: IncomingSms, clean: CleanSender): Received {
        if (store.messagesHeldForRules().size >= HoldPolicy.RULES_MAX) {
            return Received.Blocked(block(sms.receivedAt, "sms", clean, REASON_OVER_CAP))
        }
        store.insertMessage(
            MessageRow(
                id = ids.ulid(clock()), conversationId = null, seq = 0, direction = Direction.IN, sender = sms.address,
                senderKind = sms.kind, unknownSender = false, at = sms.receivedAt, sentAt = sms.sentAt, body = sms.body,
                delivery = Delivery.RECEIVED, failure = null, read = false, clientId = null, origin = Origin.ENGINE,
                verdict = MessageVerdict.HELD_RULES, subId = sms.subId,
            ),
        )
        return Received.HeldForRules
    }

    private fun holdForParent(sms: IncomingSms, clean: CleanSender): Received {
        val held = store.heldRows().filter { it.state == HeldState.HELD }
        if (!HoldPolicy.admits(sms.kind, clean.sender, held)) {
            return Received.Blocked(block(sms.receivedAt, "sms", clean, REASON_OVER_CAP))
        }
        val row = HeldRow(
            id = ids.hex(), at = sms.receivedAt, kind = sms.kind, mms = false, senderClass = clean.senderClass,
            sender = clean.sender, body = HoldPolicy.keptBody(sms.body), push = null, subId = sms.subId, sentAt = sms.sentAt,
            expiresAt = sms.receivedAt + HoldPolicy.WINDOW_MS, state = HeldState.HELD, reported = false,
        )
        store.insertHeld(row)
        store.addCounter(COUNT_HELD)
        return Received.HeldForParent(row)
    }

    /** A blocked row (kind, class, sender, time - never a body) and its count; past [HoldPolicy.BLOCKED_MAX] rows
     * only the count. */
    internal fun block(at: Long, kind: String, clean: CleanSender, reason: String): BlockedRow? {
        store.addCounter(if (reason == REASON_OVER_CAP) "$COUNT_BLOCKED.$REASON_OVER_CAP" else "$COUNT_BLOCKED.${clean.senderClass.wire}")
        if (store.blockedRows().size >= HoldPolicy.BLOCKED_MAX) return null
        val row = BlockedRow(ids.hex(), at, kind, clean.senderClass, clean.sender, reason, reported = false)
        store.insertBlocked(row)
        return row
    }

    private fun addressOf(clean: CleanSender): String =
        if (clean.senderClass == SenderClass.ALPHANUMERIC) NAME_PREFIX + clean.sender else clean.sender

    private fun nameOf(clean: CleanSender): String? = clean.sender.takeIf { clean.senderClass == SenderClass.ALPHANUMERIC }

    /** The one-to-one conversation of [address], made if new; a known one takes the newest SIM and the contact's
     * current name (a removed contact's conversation keeps the name it had, D18). */
    internal fun conversationFor(address: String, contactId: Long?, name: String?, subId: Int?): ConversationRow {
        val existing = store.conversationByKey(address)
        if (existing != null) {
            val updated = existing.copy(
                subId = subId ?: existing.subId,
                displayName = name ?: existing.displayName,
                participants = listOf(Participant(address, contactId ?: existing.participants.firstOrNull()?.contactId)),
            )
            if (updated != existing) store.putConversation(updated)
            return updated
        }
        val row = ConversationRow(
            id = ids.ulid(clock()), key = address, displayName = name, subId = subId, createdAt = clock(),
            participants = listOf(Participant(address, contactId)), canSend = false,
        )
        val made = row.copy(canSend = canSend(row, judge()))
        store.putConversation(made)
        return made
    }

    /** D12: a group's conversation, keyed by its sorted participant set (normalised numbers), made if new; each
     * participant's contact as of now. */
    internal fun groupConversationFor(addresses: List<String>, subId: Int?): ConversationRow {
        val sorted = addresses.distinct().sorted()
        val key = MmsGate.groupKey(sorted)
        val judge = judge()
        val participants = sorted.map { a -> Participant(a, judge.rules?.contacts?.firstOrNull { it.number == a }?.id) }
        val existing = store.conversationByKey(key)
        if (existing != null) {
            val updated = existing.copy(subId = subId ?: existing.subId, participants = participants)
            if (updated != existing) store.putConversation(updated)
            return updated
        }
        val row = ConversationRow(
            id = ids.ulid(clock()), key = key, displayName = null, subId = subId, createdAt = clock(), participants = participants, canSend = false,
        )
        val made = row.copy(canSend = canSend(row, judge))
        store.putConversation(made)
        return made
    }

    // ---- D15: held for the rules ----

    /** Judges every message held while the rules couldn't be (D15), as if just received: shown, held for the parent,
     * blocked, or still held. Returns what is shown now (for the notifications). */
    fun judgeHeldForRules(): List<Received.Shown> {
        val shown = mutableListOf<Received.Shown>()
        for (row in store.messagesHeldForRules().sortedBy { it.at }) {
            val kind = row.senderKind ?: SenderKind.MALFORMED
            val sms = IncomingSms(kind, row.sender, row.body.orEmpty(), row.sentAt, row.subId, row.at)
            val judge = judge()
            val verdict = SmsRules.decideIncoming(kind, row.sender, judge)
            if (verdict == IncomingVerdict.HoldRules) continue
            val clean = SenderClean.of(kind, row.sender, judge.defaultCc)
            val result = store.transaction {
                store.deleteMessageRow(row.id)
                when (verdict) {
                    is IncomingVerdict.Allow -> show(sms, clean, verdict, row.at)
                    IncomingVerdict.HoldParent -> holdForParent(sms, clean)
                    else -> Received.Blocked(block(row.at, "sms", clean, REASON_RULE))
                }
            }
            if (result is Received.Shown) {
                shown += result
                emit(EngineEvent.MessageAdded(result.message.seq, result.message))
            }
        }
        if (shown.isNotEmpty() || store.messagesHeldForRules().isEmpty()) store.afterDeletes()
        return shown
    }

    // ---- D16: held for the parent ----

    /**
     * At every accepted policy (D16): the parent's deletions first ([drops], `sms_held_drop` - QA R2-L4), then expiry
     * (QA R2-L2: an offline phone's first allow releases nothing past the window), then each held row is released only
     * when rule 5 or 6 now lets its exact sender in. Returns the released messages (notified like new ones).
     */
    fun releasePass(drops: Collection<String>): List<Received.Shown> {
        var deleted = false
        val dropSet = drops.toSet()
        store.transaction {
            for (row in store.heldRows()) {
                if (row.state == HeldState.HELD && row.id in dropSet) {
                    store.updateHeld(row.copy(state = HeldState.DROPPED, body = null, push = null, reported = false))
                    deleted = true
                }
            }
        }
        if (deleted) store.afterDeletes()
        expire()
        val released = mutableListOf<Received.Shown>()
        val judge = judge()
        for (row in store.heldRows().filter { it.state == HeldState.HELD && it.mms }.sortedBy { it.at }) {
            if (!SmsRules.releases(row.kind, row.sender, judge)) continue
            // A held MMS push (D16): back to the pushes with its first receipt, judged again there (MmsFlow.judgePush) -
            // every D10 check (From, the SIM's MMS server, the size) runs again before anything is fetched.
            store.transaction {
                store.insertPendingPush(PendingPushRow(ids.hex(), row.push ?: ByteArray(0), row.subId, row.at, heldId = row.id))
                store.updateHeld(row.copy(state = HeldState.RELEASED, body = null, push = null, reported = false))
                store.addCounter(COUNT_RELEASED)
            }
        }
        for (row in store.heldRows().filter { it.state == HeldState.HELD && !it.mms }.sortedBy { it.at }) {
            if (!SmsRules.releases(row.kind, row.sender, judge)) continue
            val shown = store.transaction {
                val contact = if (row.kind == SenderKind.NUMBER) judge.rules?.contacts?.firstOrNull { it.number == row.sender } else null
                val sms = IncomingSms(row.kind, row.sender, row.body.orEmpty(), row.sentAt, row.subId, row.at)
                val result = show(sms, CleanSender(row.senderClass, row.sender), IncomingVerdict.Allow(false, contact), row.at)
                store.updateHeld(row.copy(state = HeldState.RELEASED, body = null, push = null, reported = false))
                store.addCounter(COUNT_RELEASED)
                result
            }
            released += shown
            emit(EngineEvent.MessageAdded(shown.message.seq, shown.message))
        }
        if (released.isNotEmpty()) store.afterDeletes()
        return released
    }

    /** D16's expiry: at every sync, the backstop alarm, boot, and before any release. The body (or the push) is
     * deleted on the phone and the row's `expired` state posted. */
    fun expire() {
        val now = clock()
        var any = false
        store.transaction {
            for (row in store.heldRows()) {
                if (row.state == HeldState.HELD && HoldPolicy.expired(row, now)) {
                    store.updateHeld(row.copy(state = HeldState.EXPIRED, body = null, push = null, reported = false))
                    store.addCounter(COUNT_EXPIRED)
                    any = true
                }
            }
        }
        if (any) store.afterDeletes()
    }

    /** The rows the server hasn't seen in their state (held first, then blocked), at most [limit]. */
    fun unreported(limit: Int): List<HeldPost> {
        val held = store.heldRows().filter { !it.reported }.sortedBy { it.at }.map {
            HeldPost(it.id, it.at, if (it.mms) "mms" else "sms", it.senderClass.wire, it.sender, it.expiresAt, it.state.wire)
        }
        val blocked = store.blockedRows().filter { !it.reported }.sortedBy { it.at }.map {
            HeldPost(it.id, it.at, it.kind, it.senderClass.wire, it.sender, it.at, STATE_BLOCKED)
        }
        return (held + blocked).take(limit)
    }

    /** After a 2xx: each posted row is reported in the state it was posted in (a row that moved since is posted
     * again). A held row in its final state, and a blocked row, go once the server has them (§6.6). */
    fun markReported(posted: Collection<HeldPost>) {
        store.transaction {
            for (post in posted) {
                if (post.state == STATE_BLOCKED) {
                    store.blockedRows().firstOrNull { it.id == post.id }?.let { store.deleteBlocked(it.id) }
                    continue
                }
                val row = store.held(post.id) ?: continue
                if (row.state.wire != post.state) continue
                if (row.state == HeldState.HELD) store.updateHeld(row.copy(reported = true)) else store.deleteHeld(row.id)
            }
        }
    }

    /** Housekeeping at each sync: blocked rows older than 30 days and events older than 30 days go (§6.2, §6.6). */
    fun prune() {
        val now = clock()
        store.transaction {
            store.blockedRows().filter { now - it.at > HoldPolicy.BLOCKED_KEEP_MS }.forEach { store.deleteBlocked(it.id) }
            store.pruneEvents(now - EVENT_KEEP_MS)
            store.pruneReceived(now - RECEIVED_KEEP_MS)
            // QA L4: a push the rules couldn't judge yet (D15) waits at most a week.
            store.prunePendingPush(now - PUSH_KEEP_MS)
        }
        mms.prune()
    }

    /**
     * D7 for an MMS push (step 1 only keeps it; step 3b parses it): committed as it came, unless it is larger than
     * [PUSH_MAX] or [PUSH_ROWS] wait already (QA L4) - then only counted, as blocked `malformed`. Returns whether kept.
     */
    fun keepPush(row: PendingPushRow): Boolean = store.transaction {
        if (row.pdu.size > PUSH_MAX || store.pendingPushCount() >= PUSH_ROWS) {
            store.addCounter("$COUNT_BLOCKED.${SenderClass.MALFORMED.wire}")
            false
        } else {
            store.insertPendingPush(row)
            true
        }
    }

    /** QA L7: a receive slower than D7's budget, counted (its time is logged by the caller, never the content). */
    fun countSlowReceive() {
        store.addCounter(COUNT_SLOW_RECEIVES)
    }

    fun counts(): EngineCounts {
        val (numbers, names) = HoldPolicy.full(store.heldRows().filter { it.state == HeldState.HELD })
        return EngineCounts(store.counters(), numbers, names, mms.waiting())
    }

    // ---- Send (D5), the outbox and delivery ----

    override fun send(command: SendCommand): SendResult {
        store.messageByClientId(command.clientId)?.let { return repeated(it, command) }
        if (command.body.isBlank() && command.pictures.isEmpty()) return SendResult.Refused(Refusal.EMPTY)
        if (command.body.length > SEND_MAX) return SendResult.Refused(Refusal.TOO_LARGE)
        val judge = judge()
        val targets = targetsOf(command) ?: return SendResult.Refused(Refusal.NOT_ALLOWED)
        // D5 for every recipient: one refusal refuses the whole message (D12: a group reply only when everyone in it may
        // be texted).
        val allows = targets.map { target ->
            when (val verdict = SmsRules.decideSend(target, judge)) {
                is SmsRules.SendVerdict.Refused -> return SendResult.Refused(verdict.reason)
                is SmsRules.SendVerdict.Allow -> verdict
            }
        }
        if (command.pictures.isNotEmpty()) {
            // D11: a picture only when every recipient is a contact with "Bilder i meldinger" on, only our own re-encoded
            // JPEGs the engine holds, within the carrier's maximum.
            if (command.pictures.size > SEND_PICTURES_MAX || allows.any { it.contact?.mms != true }) return SendResult.Refused(Refusal.NOT_ALLOWED)
            val sizes = command.pictures.map { mms.pictureSize(it) ?: return SendResult.Refused(Refusal.NOT_ALLOWED) }
            val cap = com.kidslauncher.mdm.sms.mms.MmsCodec.capOf(judge.carrier(null).maxMessageBytes)
            if (sizes.sum() + command.body.length * 4L + MMS_OVERHEAD > cap) return SendResult.Refused(Refusal.TOO_LARGE)
        }
        val isMms = command.pictures.isNotEmpty() || targets.size > 1
        val parts = if (isMms) mms.outgoingParts(command.pictures) else emptyList()
        val single = allows.singleOrNull()
        val inserted = store.transaction {
            val existing = command.conversationId?.let { store.conversation(it.value) }
            val conversation = existing ?: conversationFor(single!!.number, single.contact?.id, single.contact?.name, null)
            val seq = store.nextSeq()
            val row = MessageRow(
                id = ids.ulid(clock()), conversationId = conversation.id, seq = seq, direction = Direction.OUT,
                sender = single?.number, senderKind = SenderKind.NUMBER, unknownSender = false, at = clock(), sentAt = null,
                body = command.body, delivery = Delivery.QUEUED, failure = null, read = true, clientId = command.clientId,
                origin = command.origin, verdict = MessageVerdict.SHOWN, subId = conversation.subId, mms = isMms,
            )
            val result = store.insertOutgoing(row)
            if (result is InsertOutgoing.Inserted) {
                if (parts.isNotEmpty()) store.putParts(row.id, parts)
                store.insertEvent(EventRow(seq, EventType.MESSAGE_ADDED, row.id, conversation.id, command.origin, null, clock()))
            }
            result
        }
        return when (inserted) {
            is InsertOutgoing.Inserted -> {
                val message = inserted.m.toMessage(parts)
                emit(EngineEvent.MessageAdded(inserted.m.seq, message))
                SendResult.Accepted(message)
            }
            is InsertOutgoing.Existing -> repeated(inserted.m, command)
        }
    }

    /** A repeated `clientId` (QA M5): the same body and pictures are the same message, sent once; anything else is
     * refused. */
    private fun repeated(row: MessageRow, command: SendCommand): SendResult {
        val parts = partsOf(row)
        val same = row.body == command.body && parts.mapNotNull { it.sha256 } == command.pictures.map { it.sha256 }
        return if (same) SendResult.Accepted(row.toMessage(parts)) else SendResult.Refused(Refusal.DUPLICATE_CLIENT_ID)
    }

    /** The numbers a send goes to: the conversation's participants - a group's reply goes to the stored participants,
     * never re-derived from the last message (D12; FM #595, #709, #744); a name or an unknown sender can't be texted
     * (D17) - else the raw [SendCommand.to]. */
    private fun targetsOf(command: SendCommand): List<String>? {
        val id = command.conversationId ?: return command.to?.let(::listOf)
        val conversation = store.conversation(id.value) ?: return null
        val addresses = conversation.participants.map { it.address }
        if (addresses.isEmpty() || addresses.size > com.kidslauncher.mdm.sms.mms.MmsCodec.MAX_RECIPIENTS) return null
        return addresses.takeUnless { list -> list.any { it.startsWith(NAME_PREFIX) || it.startsWith(UNKNOWN_PREFIX) } }
    }

    /**
     * Hands every queued message to the platform, oldest first: the gate again (the rules may have changed since it
     * was queued), then `SENDING` committed **before** the platform has it (§6.2 step 3), then the send. A refusal
     * or a throw is `FAILED`. Called on the engine thread.
     */
    fun drainOutbox() {
        for (row in store.messagesInDelivery(setOf(Delivery.QUEUED)).sortedBy { it.seq }) {
            if (row.mms) {
                drainMms(row)
                continue
            }
            val verdict = SmsRules.decideSend(row.sender, judge())
            if (verdict is SmsRules.SendVerdict.Refused) {
                setDelivery(row, Delivery.FAILED, "refused:${verdict.reason.name.lowercase()}")
                continue
            }
            val sending = setDelivery(row, Delivery.SENDING, null, sendingAt = clock()) ?: continue
            try {
                val parts = radio.send(row.id, row.sender.orEmpty(), row.body.orEmpty(), row.subId, deliveryReport = REQUEST_DELIVERY_REPORTS)
                store.message(row.id)?.takeIf { it.partsTotal == 0 }?.let { store.updateMessage(it.copy(partsTotal = parts)) }
            } catch (e: Exception) {
                setDelivery(sending, Delivery.FAILED, "radio:${e.javaClass.simpleName}")
            }
        }
    }

    /** An MMS from the outbox (D11, D12): every recipient through the gate again, a picture's "Bilder i meldinger"
     * again, composed from our own files, `SENDING` committed before the platform has it. A refusal or a throw is
     * `FAILED`. */
    private fun drainMms(row: MessageRow) {
        val judge = judge()
        val conversation = row.conversationId?.let { store.conversation(it) }
        val recipients = conversation?.participants?.map { it.address }?.takeIf { it.size > 1 } ?: listOfNotNull(row.sender)
        val pictures = partsOf(row).any { it.sha256 != null }
        for (recipient in recipients.ifEmpty { listOf("") }) {
            when (val verdict = SmsRules.decideSend(recipient, judge)) {
                is SmsRules.SendVerdict.Refused -> {
                    setDelivery(row, Delivery.FAILED, "refused:${verdict.reason.name.lowercase()}")
                    return
                }
                is SmsRules.SendVerdict.Allow -> if (pictures && verdict.contact?.mms != true) {
                    setDelivery(row, Delivery.FAILED, "refused:${Refusal.NOT_ALLOWED.name.lowercase()}")
                    return
                }
            }
        }
        val (pdu, failure) = mms.compose(row, recipients)
        if (pdu == null) {
            setDelivery(row, Delivery.FAILED, failure)
            return
        }
        val sending = setDelivery(row, Delivery.SENDING, null, sendingAt = clock()) ?: return
        try {
            mms.sendPdu(row, pdu)
        } catch (e: Exception) {
            setDelivery(sending, Delivery.FAILED, "radio:${e.javaClass.simpleName}")
        }
    }

    /** An MMS send's result from the platform (with the MMSC's m-send-conf read in the isolated parse): `SENT`, or
     * `FAILED` with its code. A late result after `UNCERTAIN` still applies. */
    fun onMmsSent(messageId: String, ok: Boolean, code: String?) {
        val row = store.message(messageId) ?: return
        if (row.direction != Direction.OUT || !row.mms || row.delivery !in setOf(Delivery.SENDING, Delivery.UNCERTAIN)) return
        if (ok) {
            store.transaction {
                store.addCounter(COUNT_SENT)
                setDelivery(row, Delivery.SENT, null)
            }
        } else {
            setDelivery(row, Delivery.FAILED, "mms:${code ?: "error"}")
        }
    }

    /** One part's sent result ([part] of [total]; a result for a message we don't know, or one already final, is
     * ignored). Every part sent is `SENT`; any part failed is `FAILED` with the platform's code (QA L4). A late result
     * after `UNCERTAIN` still applies. */
    fun onSent(messageId: String, part: Int, total: Int, ok: Boolean, code: Int) {
        val row = store.message(messageId) ?: return
        if (row.direction != Direction.OUT || row.delivery !in setOf(Delivery.SENDING, Delivery.UNCERTAIN)) return
        if (!ok) {
            setDelivery(row, Delivery.FAILED, "sms:$code")
            return
        }
        val parts = maxOf(row.partsTotal, total, 1)
        val mask = row.partsSent or (1L shl part.coerceIn(0, 62))
        val all = (0 until minOf(parts, 63)).all { mask and (1L shl it) != 0L }
        if (all) {
            store.transaction {
                store.addCounter(COUNT_SENT)
                setDelivery(row.copy(partsTotal = parts, partsSent = mask), Delivery.SENT, null)
            }
        } else {
            store.updateMessage(row.copy(partsTotal = parts, partsSent = mask))
        }
    }

    /** The last part's delivery report: delivered, or failed for good (a "still trying" report never gets here). */
    fun onDelivered(messageId: String, ok: Boolean) {
        val row = store.message(messageId) ?: return
        if (row.direction != Direction.OUT || row.delivery !in setOf(Delivery.SENDING, Delivery.SENT, Delivery.UNCERTAIN)) return
        if (ok) setDelivery(row, Delivery.DELIVERED, null) else setDelivery(row, Delivery.FAILED, "delivery")
    }

    /** §6.2 step 4 (QA L4): a message in `SENDING` with no sent result for 10 minutes is `UNCERTAIN` - never sent again
     * by itself (at most once); the kid or the bridge may send again with a new `clientId`. */
    fun sweepUncertain() {
        val now = clock()
        for (row in store.messagesInDelivery(setOf(Delivery.SENDING))) {
            if (now - (row.sendingAt ?: row.at) >= UNCERTAIN_AFTER_MS) setDelivery(row, Delivery.UNCERTAIN, null)
        }
    }

    /** Writes a new delivery state and its `MessageUpdated` in one transaction; `null` if the row is gone. */
    internal fun setDelivery(row: MessageRow, delivery: Delivery, failure: String?, sendingAt: Long? = row.sendingAt): MessageRow? {
        val updated = store.transaction {
            val current = store.message(row.id) ?: return@transaction null
            val next = current.copy(
                delivery = delivery, failure = failure, sendingAt = sendingAt,
                partsTotal = maxOf(current.partsTotal, row.partsTotal), partsSent = current.partsSent or row.partsSent,
            )
            store.updateMessage(next)
            val seq = store.nextSeq()
            store.insertEvent(EventRow(seq, EventType.MESSAGE_UPDATED, row.id, row.conversationId, Origin.ENGINE, null, clock()))
            next to seq
        } ?: return null
        emit(EngineEvent.MessageUpdated(updated.second, MessageId(row.id), delivery, partsOf(updated.first)))
        return updated.first
    }

    // ---- Rules change: conversations ----

    /** A policy change: each conversation's contact, name and [Conversation.canSend] as of now; a change is stored and
     * told (`ConversationChanged`). What is already stored stays, also a removed contact's (D18). */
    fun rulesChanged(): List<ConversationId> {
        val judge = judge()
        val changed = mutableListOf<ConversationId>()
        for (row in store.conversations()) {
            val participants = row.participants.map { p ->
                val contact = judge.rules?.contacts?.firstOrNull { it.number == p.address }
                p.copy(contactId = contact?.id ?: if (judge.rules == null) p.contactId else null)
            }
            val name = participants.singleOrNull()?.contactId?.let { id -> judge.rules?.contacts?.firstOrNull { it.id == id }?.name }
            val next = row.copy(participants = participants, displayName = name ?: row.displayName)
            val withSend = next.copy(canSend = canSend(next, judge))
            if (withSend == row) continue
            val seq = store.transaction {
                store.putConversation(withSend)
                store.nextSeq().also {
                    store.insertEvent(EventRow(it, EventType.CONVERSATION_CHANGED, null, row.id, Origin.ENGINE, null, clock()))
                }
            }
            changed += ConversationId(row.id)
            emit(EngineEvent.ConversationChanged(seq, ConversationId(row.id)))
        }
        return changed
    }

    /** D5/D12 as of now: every participant a number the gate lets the kid text (a name never). */
    internal fun canSend(row: ConversationRow, judge: SmsJudge): Boolean =
        row.participants.isNotEmpty() && row.participants.all {
            !it.address.startsWith(NAME_PREFIX) && SmsRules.decideSend(it.address, judge) is SmsRules.SendVerdict.Allow
        }

    // ---- The seam's reads, reads marks and deletes ----

    override fun conversations(): List<Conversation> {
        val judge = judge()
        return store.conversations().mapNotNull { conversationOf(it, judge) }.sortedByDescending { it.lastSeq }
    }

    /** One conversation (a thread's read, code QA of step 2, L2: not every conversation's); `null` when it is gone or
     * has no message left. */
    fun conversation(id: ConversationId): Conversation? = store.conversation(id.value)?.let { conversationOf(it, judge()) }

    /** The one-to-one conversation keyed by [address] (a normalised number, `name:<text>`), as [conversation]. */
    fun conversationWith(address: String): Conversation? = store.conversationByKey(address)?.let { conversationOf(it, judge()) }

    private fun conversationOf(row: ConversationRow, judge: SmsJudge): Conversation? {
        val last = store.lastMessage(row.id) ?: return null
        return Conversation(
            id = ConversationId(row.id), participants = row.participants.sortedBy { it.address }, displayName = row.displayName,
            subId = row.subId, lastSeq = last.seq, lastAt = last.at, unread = store.unread(row.id), canSend = canSend(row, judge),
        )
    }

    override fun messages(id: ConversationId, beforeSeq: Long?, limit: Int): List<Message> =
        store.messages(id.value, beforeSeq, limit).map { it.toMessage(partsOf(it)) }

    /** A message's parts (§6.6): an SMS has none, so only an MMS asks the store. */
    internal fun partsOf(row: MessageRow): List<Part> = if (row.mms) store.parts(row.id) else emptyList()

    override fun events(afterSeq: Long): List<EngineEvent> = store.events(afterSeq, EVENTS_PAGE).mapNotNull { e ->
        when (e.type) {
            EventType.MESSAGE_ADDED -> e.messageId?.let(store::message)?.let { EngineEvent.MessageAdded(e.seq, it.toMessage(partsOf(it))) }
            EventType.MESSAGE_UPDATED -> e.messageId?.let(store::message)?.let { EngineEvent.MessageUpdated(e.seq, MessageId(it.id), it.delivery, partsOf(it)) }
            EventType.READ -> EngineEvent.Read(e.seq, ConversationId(e.conversationId.orEmpty()), e.upToSeq ?: 0, e.origin)
            EventType.CONVERSATION_CHANGED -> EngineEvent.ConversationChanged(e.seq, ConversationId(e.conversationId.orEmpty()))
            EventType.MESSAGE_DELETED -> EngineEvent.MessageDeleted(e.seq, MessageId(e.messageId.orEmpty()), e.origin)
        }
    }

    /** The oldest `seq` the log still has: a cursor before it gets a backfill instead (§6.2). */
    fun oldestEventSeq(): Long? = store.oldestEventSeq()

    override fun markRead(id: ConversationId, upToSeq: Long, origin: Origin) {
        val seq = store.transaction {
            // One statement (QA L7): the receive path may wait for this transaction.
            if (store.markReadUpTo(id.value, upToSeq) == 0) return@transaction null
            store.nextSeq().also { store.insertEvent(EventRow(it, EventType.READ, null, id.value, origin, upToSeq, clock())) }
        } ?: return
        emit(EngineEvent.Read(seq, id, upToSeq, origin))
    }

    override fun delete(id: MessageId, origin: Origin) {
        var pictures = emptyList<String>()
        val seq = store.transaction {
            val row = store.message(id.value)?.takeIf { it.verdict == MessageVerdict.SHOWN } ?: return@transaction null
            pictures = partsOf(row).mapNotNull { it.sha256 }
            store.deleteMessageRow(id.value)
            store.nextSeq().also { store.insertEvent(EventRow(it, EventType.MESSAGE_DELETED, id.value, null, origin, null, clock())) }
        } ?: return
        store.afterDeletes()
        // D18: a picture file no message holds any more goes with it.
        mms.dropUnheld(pictures)
        emit(EngineEvent.MessageDeleted(seq, id, origin))
    }

    override fun deleteConversation(id: ConversationId, origin: Origin) {
        for (row in store.messages(id.value, null, Int.MAX_VALUE)) delete(MessageId(row.id), origin)
        store.transaction { store.deleteConversationRow(id.value) }
    }

    /** Only an available picture of a shown message - by construction the main process's own JPEG (D8): nothing else
     * is ever in the picture files. */
    override fun openPart(messageId: MessageId, index: Int): java.io.InputStream? {
        val row = store.message(messageId.value)?.takeIf { it.verdict == MessageVerdict.SHOWN && it.mms } ?: return null
        val part = store.parts(row.id).firstOrNull { it.index == index && it.state == PartState.AVAILABLE } ?: return null
        return part.sha256?.let(mms::open)
    }

    override fun openPartByHash(sha256: String): java.io.InputStream? =
        if (sha256 in store.partHashes()) mms.open(sha256) else null

    /** D11: a picture the kid picked, re-encoded by the main process (our own JPEG, no EXIF), held for a send. */
    fun putPicture(jpeg: ByteArray, width: Int, height: Int): PictureRef = mms.putPicture(jpeg, width, height)

    internal fun emit(event: EngineEvent) {
        events.tryEmit(event)
    }

    companion object {
        /** A sender name's conversation key and participant address (§6.2). */
        const val NAME_PREFIX = "name:"

        /** The conversation key of a sender without a usable address, let in unfiltered (QA L3). */
        const val UNKNOWN_PREFIX = "unknown:"

        const val COUNT_RECEIVED = "received"
        const val COUNT_SENT = "sent"
        const val COUNT_HELD = "held"
        const val COUNT_RELEASED = "released"
        const val COUNT_EXPIRED = "expired"
        const val COUNT_PARSE_FAILURES = "parse_failures"
        const val COUNT_BLOCKED = "blocked"
        const val REASON_RULE = "rule"
        const val REASON_OVER_CAP = "over_cap"
        const val STATE_BLOCKED = "blocked"
        const val COUNT_SLOW_RECEIVES = "slow_receives"

        /** QA L6: a received message's identity is kept a day. */
        const val RECEIVED_KEEP_MS = 24 * 60 * 60 * 1000L

        /** D7, QA L4: an MMS push kept is at most 2 KB, at most 100 of them, for at most 7 days. */
        const val PUSH_MAX = 2048
        const val PUSH_ROWS = 100
        const val PUSH_KEEP_MS = 7 * 24 * 60 * 60 * 1000L

        /** §6.2 step 4: `SENDING` without a sent result this long is `UNCERTAIN`. */
        const val UNCERTAIN_AFTER_MS = 10 * 60 * 1000L

        /** §6.2: the event log is kept 30 days. */
        const val EVENT_KEEP_MS = 30 * 24 * 60 * 60 * 1000L

        private const val EVENTS_PAGE = 500

        /** A text longer than this (about ten UCS-2 parts, twenty GSM-7) is refused, never auto-converted to MMS. */
        const val SEND_MAX = 1600

        /** D11: pictures in one message the kid sends. */
        const val SEND_PICTURES_MAX = 1

        /** An m-send-req's headers and SMIL around its parts, kept free under the carrier's maximum. */
        const val MMS_OVERHEAD = 2048L

        // Step 0 check: Norwegian operators don't bill delivery reports (design 77 §6.2). Until then they are requested.
        const val REQUEST_DELIVERY_REPORTS = true
    }
}
