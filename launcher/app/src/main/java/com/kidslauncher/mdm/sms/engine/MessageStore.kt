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
    /** An MMS (step 3b): IN, its fetch is in the `mms` table; OUT, it goes as an m-send-req (a picture, or a group). */
    val mms: Boolean = false,
) {
    /** The seam's message, with its [parts] from the `part` table (empty for SMS). */
    fun toMessage(parts: List<Part> = emptyList()): Message = Message(
        id = MessageId(id),
        conversationId = ConversationId(conversationId.orEmpty()),
        seq = seq,
        direction = direction,
        sender = sender.takeIf { direction == Direction.IN },
        unknownSender = unknownSender,
        at = at,
        sentAt = sentAt,
        body = body,
        parts = parts.sortedBy { it.index },
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

/**
 * The raw m-notification-ind of an MMS (D7), at most 2 KB, until it is judged (D10). [at] is the phone's receipt - a
 * held push released by the parent (D16) comes back here with its first receipt and [heldId]. [tries]: the isolated
 * parse died on it (three times blocks it as malformed, §6.10).
 */
class PendingPushRow(val id: String, val pdu: ByteArray, val subId: Int?, val at: Long, val heldId: String? = null, val tries: Int = 0)

/** An incoming MMS's place in §6.4's machine (the `mms` table, one row per IN MMS message). */
enum class MmsState {
    /** Allowed and checked (D10): to be fetched. */
    NOTIFIED,

    /** Handed to the platform's download; its result comes to `MmsResultReceiver`. */
    DOWNLOADING,

    /** D14: the fetch couldn't run (no MMS data, no network); tried again when data comes back and at each sync. */
    WAITING_DATA,

    /** Fetched into our file; the isolated parse is next (a process death resumes here). */
    DOWNLOADED,

    /** Parsed: its parts are in `part`, its text in the body. */
    DONE,

    /** Couldn't be fetched or parsed ([MmsRow.failure] says why; [MmsRow.RETRY] offers the kid's tap, D14). */
    FAILED,

    /** The carrier's server no longer has it, or its expiry passed. */
    GONE,

    /** Larger than the carrier's maximum (D10 check 3). */
    TOO_LARGE,
}

/** An incoming MMS's fetch (§6.4). [from] is the notification's sender, normalised; the retrieve-conf's must equal it. */
data class MmsRow(
    val messageId: String,
    val state: MmsState,
    val transactionId: String,
    val contentLocation: String,
    val from: String,
    /** X-Mms-Message-Size as the notification said. */
    val size: Long,
    /** X-Mms-Expiry in ms (`null`: none); past it the carrier's server has dropped it. */
    val expiresAt: Long?,
    val mmsVersion: Int,
    val subId: Int?,
    /** Download tries since the last reset (D14: five, then the kid's tap). */
    val tries: Int = 0,
    /** When the last download was handed to the platform. */
    val requestedAt: Long? = null,
    /** Times the isolated parse died on it (§6.10: three marks it failed). */
    val crashes: Int = 0,
    /** Why it failed or waits: [RETRY], [MMSC_UNKNOWN], a parse or platform reason. */
    val failure: String? = null,
    /** The kid was told of it (one notification per MMS). */
    val notified: Boolean = false,
    /** Not fetched at once: released by the parent (D16), or after waiting or failing (D14). Its answer to the MMSC is
     * then an m-acknowledge-ind, an immediate fetch's an m-notifyresp-ind "retrieved" (code QA L2). */
    val deferred: Boolean = false,
) {
    companion object {
        /** D14: five tries gave up - the bubble offers "Trykk for å prøve igjen". */
        const val RETRY = "retry"

        /** D10: this SIM's MCC/MNC isn't on the MMSC list - nothing is fetched (fail closed). */
        const val MMSC_UNKNOWN = "mmsc_unknown"
    }
}

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

    /** The message with its parts and its `mms` row. */
    fun deleteMessageRow(id: String)

    /** Shown messages of a conversation with `seq < beforeSeq` (all when `null`), newest first. */
    fun messages(conversationId: String, beforeSeq: Long?, limit: Int): List<MessageRow>
    fun messagesHeldForRules(): List<MessageRow>
    fun messagesInDelivery(states: Set<Delivery>): List<MessageRow>

    /** Marks a conversation's shown IN messages up to [upToSeq] read, in one statement (QA L7: no walk over a whole
     * conversation inside a transaction the receive path may wait for). Returns how many changed. */
    fun markReadUpTo(conversationId: String, upToSeq: Long): Int

    /** QA L6: an SMS the platform delivered again (its raw row survived our commit) is known by [key] for a day. */
    fun receivedBefore(key: String): Boolean
    fun rememberReceived(key: String, at: Long)
    fun pruneReceived(before: Long)

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
    fun pendingPushCount(): Int
    fun prunePendingPush(before: Long)

    /** The committed pushes not judged yet, oldest first (step 3b). */
    fun pendingPushes(): List<PendingPushRow>
    fun deletePendingPush(id: String)
    fun countPushTry(id: String)

    /** The parts of a message by index (§6.6); [putParts] replaces them all. */
    fun parts(messageId: String): List<Part>
    fun putParts(messageId: String, parts: List<Part>)

    /** Every picture hash a part of any message holds (a file nobody holds is deleted, §6.4). */
    fun partHashes(): Set<String>

    fun insertMms(row: MmsRow)
    fun mms(messageId: String): MmsRow?
    fun updateMms(row: MmsRow)
    fun mmsIn(states: Set<MmsState>): List<MmsRow>

    fun addCounter(key: String, by: Long = 1)
    fun counters(): Map<String, Long>

    /** After deleting bodies (expiry, a drop, the kid's delete): no deleted text lingers in free pages or the WAL
     * (`secure_delete` is on; this checkpoints the WAL - QA R2-L3). */
    fun afterDeletes()
}
