package com.kidslauncher.mdm.sms.engine

/*
 * The message engine's seam (design 77 §6.2): pure Kotlin types every front end goes through - the kid's screens and
 * the notification's reply now, the bridge later (§9 step 8). Nothing reaches the store or the radio around
 * [MessageEngine]. Ids are engine-assigned ULIDs, never reused; [Message.seq] is the only ordering key.
 */

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class MessageId(val value: String)

/** Who acted: the kid's screen, the notification's reply, a remote front end (the bridge, later), the engine itself
 * (receive, rule changes, retention). Recorded on messages, reads and deletions. */
enum class Origin { KID_SCREEN, NOTIFICATION, REMOTE, ENGINE }

/** [address]: a normalised E.164 or short number, or `name:<text>` for a sender name. [contactId]: the policy
 * contact; `null` = not in the phone book. */
data class Participant(val address: String, val contactId: Long?)

data class Conversation(
    val id: ConversationId,
    /** Sorted; one = one-to-one, more = a group (MMS, step 3b). */
    val participants: List<Participant>,
    /** The name it last had (D18: a removed contact's stays). */
    val displayName: String?,
    /** The SIM it lives on; replies go out on it. */
    val subId: Int?,
    val lastSeq: Long,
    val lastAt: Long,
    val unread: Int,
    /** D5/D12 as of now (rules change). */
    val canSend: Boolean,
)

enum class Direction { IN, OUT }

enum class Delivery { RECEIVED, QUEUED, SENDING, SENT, DELIVERED, FAILED, UNCERTAIN }

/**
 * An MMS part's state (§6.2, step 3b): [PENDING] not fetched yet, [WAITING_DATA] waiting for MMS data (D14),
 * [AVAILABLE] our own JPEG is there, [FAILED] it couldn't be fetched or decoded, [TOO_LARGE] over the carrier's maximum,
 * [NOT_SHOWN] pictures are off with this contact (D11), [GONE] the carrier's server no longer has it (D16: an allow that
 * came late, or its expiry passed).
 */
enum class PartState { PENDING, WAITING_DATA, AVAILABLE, FAILED, TOO_LARGE, NOT_SHOWN, GONE }

/** An MMS part (step 3b): [mime] is `image/jpeg` (our own encode), or the received type of a part that isn't shown
 * (video, audio - its bytes aren't kept); [sha256] of the bytes the main process encoded. The text parts are the
 * message's body. Empty for SMS. */
data class Part(
    val index: Int,
    val mime: String,
    val size: Long,
    val sha256: String?,
    val width: Int?,
    val height: Int?,
    val state: PartState,
)

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    /** The engine's monotonic order, the only ordering key. */
    val seq: Long,
    val direction: Direction,
    /** Normalised, or `name:<text>`; `null` for OUT. */
    val sender: String?,
    /** D4 rule 2: let in by the callback window, not a contact or a listed name. */
    val unknownSender: Boolean,
    /** Engine time: received, or queued. */
    val at: Long,
    /** The SMSC's time for IN (shown only if plausible). */
    val sentAt: Long?,
    val body: String?,
    val parts: List<Part>,
    val delivery: Delivery,
    val failure: String?,
    val read: Boolean,
    /** The front end's idempotency key for OUT. */
    val clientId: String?,
    val origin: Origin,
)

sealed interface EngineEvent {
    val seq: Long

    data class MessageAdded(override val seq: Long, val message: Message) : EngineEvent

    data class MessageUpdated(override val seq: Long, val id: MessageId, val delivery: Delivery, val parts: List<Part>) : EngineEvent

    data class Read(override val seq: Long, val conversationId: ConversationId, val upToSeq: Long, val origin: Origin) : EngineEvent

    /** Participants' contact ids, names, canSend or subId changed (a policy change, a new own number). */
    data class ConversationChanged(override val seq: Long, val conversationId: ConversationId) : EngineEvent

    data class MessageDeleted(override val seq: Long, val id: MessageId, val origin: Origin) : EngineEvent
}

/** A picture to send (D11, step 3b): the hash of a picture the engine already holds ([SmsEngine.putPicture] - our own
 * re-encoded JPEG), with its size in pixels for the bubble's shape. */
data class PictureRef(val sha256: String, val width: Int? = null, val height: Int? = null)

data class SendCommand(
    /** A UUID from the front end. */
    val clientId: String,
    val conversationId: ConversationId?,
    /** Or a raw number (normalised and gated here). */
    val to: String?,
    val body: String,
    val pictures: List<PictureRef> = emptyList(),
    val origin: Origin,
)

sealed interface SendResult {
    /** The same message for a repeated [SendCommand.clientId]. */
    data class Accepted(val message: Message) : SendResult

    data class Refused(val reason: Refusal) : SendResult
}

/** [GROUPS_OFF]: design 80 D13 - "Gruppemeldinger" is off, so nothing goes to a group. */
enum class Refusal { NOT_ALLOWED, TIME_RULE, SMS_OFF, RULES_UNKNOWN, TOO_LARGE, EMPTY, DUPLICATE_CLIENT_ID, GROUPS_OFF }

interface MessageEngine {
    fun conversations(): List<Conversation>

    /** Backfill, newest first. */
    fun messages(id: ConversationId, beforeSeq: Long?, limit: Int): List<Message>

    /** Replay from a cursor; a cursor older than the pruned log gets [messages] instead ([oldestEventSeq]). */
    fun events(afterSeq: Long): List<EngineEvent>

    val live: kotlinx.coroutines.flow.Flow<EngineEvent>

    fun send(command: SendCommand): SendResult

    fun markRead(id: ConversationId, upToSeq: Long, origin: Origin)

    /** D18: the kid's screen; later the bridge. */
    fun delete(id: MessageId, origin: Origin)

    fun deleteConversation(id: ConversationId, origin: Origin)

    /** A picture part's bytes: always the main process's own JPEG (D8), never a received part; `null` when the part
     * isn't an available picture. */
    fun openPart(messageId: MessageId, index: Int): java.io.InputStream?

    /** The same by its hash (the bridge's "parts by hash"), for a picture some message holds. */
    fun openPartByHash(sha256: String): java.io.InputStream?
}

/** The store's contract carries the dedup decision, so the in-memory fake and SQLite behave alike (QA L8). */
sealed interface InsertOutgoing {
    data class Inserted(val m: MessageRow) : InsertOutgoing

    data class Existing(val m: MessageRow) : InsertOutgoing
}
