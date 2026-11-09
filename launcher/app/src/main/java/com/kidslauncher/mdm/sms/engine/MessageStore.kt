package com.kidslauncher.mdm.sms.engine

/*
 * The engine's store (design 77 D6, §6.6): our own SQLite in CE storage - the system of record, nothing written to the
 * Telephony provider. This interface is what the engine's logic runs on: `SqliteMessageStore` on the phone, an
 * in-memory fake in SmsEngineTest that keeps the same contract ([insertOutgoing]'s dedup decision included, QA L8).
 * Every write the engine makes that belongs together runs inside one [transaction].
 */

/** `message.verdict`: shown, or kept unseen while the rules couldn't be judged (D15). */
enum class MessageVerdict { SHOWN, HELD_RULES }

data class ConversationRow(
    val id: String,
    /** The sorted participant set, or `name:<text>` - the one-to-one conversation's normalised address. */
    val key: String,
    val displayName: String?,
    val subId: Int?,
    val createdAt: Long,
    val participants: List<Participant>,
    /** [Conversation.canSend] as last told (a change emits `ConversationChanged`). */
    val canSend: Boolean,
)

data class MessageRow(
    val id: String,
    /** `null` while [verdict] is [MessageVerdict.HELD_RULES]: it belongs nowhere until it is judged. */
    val conversationId: String?,
    /** 0 while held for the rules: a seq is given when it is shown. */
    val seq: Long,
    val direction: Direction,
    /** IN: the sender as received ([senderKind]'s raw address while held for the rules, then normalised or
     * `name:<text>`); OUT: the number sent to. */
    val sender: String?,
    val senderKind: SenderKind?,
    val unknownSender: Boolean,
    val at: Long,
    val sentAt: Long?,
    val body: String?,
    val delivery: Delivery,
    val failure: String?,
    val read: Boolean,
    val clientId: String?,
    val origin: Origin,
    val verdict: MessageVerdict,
    val subId: Int?,
    /** OUT: how many SMS parts the platform took, and which have reported sent (a bit each). */
    val partsTotal: Int = 0,
    val partsSent: Long = 0,
    /** OUT: when it was handed to the platform ([Delivery.SENDING]). */
    val sendingAt: Long? = null,
) {
    fun toMessage(): Message = Message(
        id = MessageId(id),
        conversationId = ConversationId(conversationId.orEmpty()),
        seq = seq,
        direction = direction,
        sender = sender.takeIf { direction == Direction.IN },
        unknownSender = unknownSender,
        at = at,
        sentAt = sentAt,
        body = body,
        parts = emptyList(),
        delivery = delivery,
        failure = failure,
        read = read,
        clientId = clientId,
        origin = origin,
    )
}

/** D16's row states, as the server's intake names them. */
enum class HeldState(val wire: String) { HELD("held"), RELEASED("released"), EXPIRED("expired"), DROPPED("dropped") }

/** A message held for the parent (D16): the body (SMS) or the push (MMS, step 3b) lives here, never in `message`,
 * until a release. [sender] is the normalised number or the cleaned name the release matches on. */
data class HeldRow(
    /** 32 lowercase hex: the id the server and the parent's `sms_held_drop` name it by. */
    val id: String,
    val at: Long,
    val kind: SenderKind,
    val mms: Boolean,
    val senderClass: SenderClass,
    val sender: String,
    val body: String?,
    val push: ByteArray?,
    val subId: Int?,
    val sentAt: Long?,
    val expiresAt: Long,
    val state: HeldState,
    /** The server has this row in its [state]. */
    val reported: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is HeldRow && other.id == id && other.state == state &&
        other.reported == reported && other.body == body && other.push.contentEqualsOrNull(push)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "HeldRow($id, $kind, $state)"
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)

/** A blocked message: kind, class, sender and time only - never a body (D6). */
data class BlockedRow(
    val id: String,
    val at: Long,
    /** "sms" or "mms". */
    val kind: String,
    val senderClass: SenderClass,
    /** Cleaned by its class (empty for the classes without a sender). */
    val sender: String,
    /** "rule" (D4 rules 4/8) or "over_cap" (D15/D16). */
    val reason: String,
    val reported: Boolean,
)

enum class EventType { MESSAGE_ADDED, MESSAGE_UPDATED, READ, CONVERSATION_CHANGED, MESSAGE_DELETED }

data class EventRow(
    val seq: Long,
    val type: EventType,
    val messageId: String?,
    val conversationId: String?,
    val origin: Origin,
    val upToSeq: Long?,
    val at: Long,
)

/** The raw m-notification-ind of an MMS (D7; parsed and fetched in step 3b), at most 2 KB. */
class PendingPushRow(val id: String, val pdu: ByteArray, val subId: Int?, val at: Long)

interface MessageStore {
    /** Runs [block] in one transaction: everything or nothing is written. */
    fun <T> transaction(block: () -> T): T

    /** The next value of the one counter for messages and events. */
    fun nextSeq(): Long

    fun conversationByKey(key: String): ConversationRow?
    fun conversation(id: String): ConversationRow?
    fun conversations(): List<ConversationRow>
    fun putConversation(row: ConversationRow)
    fun deleteConversationRow(id: String)

    fun insertMessage(row: MessageRow)

    /** The dedup contract (§6.2, QA M5): a row whose `client_id` is already stored is not inserted - that row is
     * returned as [InsertOutgoing.Existing]. */
    fun insertOutgoing(row: MessageRow): InsertOutgoing
    fun messageByClientId(clientId: String): MessageRow?
    fun message(id: String): MessageRow?
    fun updateMessage(row: MessageRow)
    fun deleteMessageRow(id: String)

    /** Shown messages of a conversation with `seq < beforeSeq` (all when `null`), newest first. */
    fun messages(conversationId: String, beforeSeq: Long?, limit: Int): List<MessageRow>
    fun messagesHeldForRules(): List<MessageRow>
    fun messagesInDelivery(states: Set<Delivery>): List<MessageRow>

    /** Shown IN messages of a conversation not read yet. */
    fun unread(conversationId: String): Int

    /** The newest shown message of a conversation. */
    fun lastMessage(conversationId: String): MessageRow?

    fun insertEvent(row: EventRow)
    fun events(afterSeq: Long, limit: Int): List<EventRow>
    fun oldestEventSeq(): Long?
    fun pruneEvents(before: Long)

    fun insertHeld(row: HeldRow)
    fun held(id: String): HeldRow?
    fun heldRows(): List<HeldRow>
    fun updateHeld(row: HeldRow)
    fun deleteHeld(id: String)

    fun insertBlocked(row: BlockedRow)
    fun blockedRows(): List<BlockedRow>
    fun updateBlocked(row: BlockedRow)
    fun deleteBlocked(id: String)

    fun insertPendingPush(row: PendingPushRow)

    fun addCounter(key: String, by: Long = 1)
    fun counters(): Map<String, Long>

    /** After deleting bodies (expiry, a drop, the kid's delete): no deleted text lingers in free pages or the WAL
     * (`secure_delete` is on; this checkpoints the WAL - QA R2-L3). */
    fun afterDeletes()
}
