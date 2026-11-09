package com.kidslauncher.mdm.sms.engine

import com.kidslauncher.mdm.sms.mms.MmsAddress
import com.kidslauncher.mdm.sms.mms.MmsCodec
import com.kidslauncher.mdm.sms.mms.MmsComposeResult
import com.kidslauncher.mdm.sms.mms.MmsNotification
import com.kidslauncher.mdm.sms.mms.MmsOutgoingPart

/*
 * Design 77 step 3b: the MMS half of the engine - the push-first state machine (§6.4), the gated download (D10), held
 * pushes fetched after an allow (D16), the group rule (D12), pictures both ways with the per-contact switch (D11), the
 * waiting state and its retry (D14). Pure Kotlin over the same store as [SmsEngine], no Android imports: the isolated
 * parse (D8), the platform's MMS calls and the picture files are behind [MmsRadio] and [PartFiles], and what the parse
 * hands back arrives as strings and our own encoded pictures ([RetrievedMms]). Every call runs on the engine thread.
 *
 * pushed (`pending_push`, D7) -> [judgePush]: the isolated parse's notification, D10's sender check, D4, then the host
 * and the size -> notified (a placeholder part, PENDING) -> [drainDownloads]: downloading, or waiting_data (D14) ->
 * [onDownloaded] -> the isolated parse -> [onRetrieved] (From again, D12, the parts) -> done | failed | gone.
 */

/** The platform's MMS calls (`SmsManager`), behind an interface for the JVM tests. Each throws if the platform refused
 * at once; the results come back through `MmsResultReceiver` to [MmsFlow]. */
interface MmsRadio {
    /** Fetches [location] for [messageId] on [subId] into our own capped file ([maxBytes]). */
    fun download(messageId: String, location: String, subId: Int?, maxBytes: Int)

    /** Hands an m-send-req to the platform. */
    fun send(messageId: String, pdu: ByteArray, subId: Int?)

    /** An answer to the MMSC (m-acknowledge-ind after a download), fire and forget. */
    fun answer(pdu: ByteArray, subId: Int?)

    object None : MmsRadio {
        override fun download(messageId: String, location: String, subId: Int?, maxBytes: Int) = throw IllegalStateException("no MMS")
        override fun send(messageId: String, pdu: ByteArray, subId: Int?) = throw IllegalStateException("no MMS")
        override fun answer(pdu: ByteArray, subId: Int?) = Unit
    }
}

/** The picture files (§6.4): the main process's own JPEGs, named by the SHA-256 of their bytes. A file no part holds is
 * deleted ([MmsFlow.dropUnheld]). */
interface PartFiles {
    /** Stores [bytes] and returns their SHA-256 (64 lowercase hex). */
    fun put(bytes: ByteArray): String
    fun open(sha256: String): java.io.InputStream?
    fun read(sha256: String): ByteArray?

    /** The file's size, `null` when there is none. */
    fun size(sha256: String): Long?
    fun delete(sha256: String)

    /** Every stored hash with its last-modified time. */
    fun all(): Map<String, Long>

    object None : PartFiles {
        override fun put(bytes: ByteArray): String = throw IllegalStateException("no picture files")
        override fun open(sha256: String): java.io.InputStream? = null
        override fun read(sha256: String): ByteArray? = null
        override fun size(sha256: String): Long? = null
        override fun delete(sha256: String) = Unit
        override fun all(): Map<String, Long> = emptyMap()
    }
}

/** Why a download didn't bring the message. */
enum class DownloadError {
    /** No MMS data or no network (D14): waits, tried again. */
    NO_DATA,

    /** The MMSC answers that it is gone (404/410). */
    GONE,

    /** Anything else: tried again like [NO_DATA], five times. */
    OTHER,
}

/** One part of a fetched MMS as the main process made it from the isolated parse's answer (D8). */
sealed interface RetrievedPart {
    /** Our own JPEG ([PartFiles.put]) from the isolated process's pixels. */
    data class Picture(val sha256: String, val width: Int, val height: Int, val size: Long) : RetrievedPart

    /** A picture the parse was told not to decode (the contact's pictures are off, D11): no pixels, no file. */
    data object NotShown : RetrievedPart

    /** A picture that couldn't be decoded (a format the isolated process can't read, over the limits). */
    data object Undecodable : RetrievedPart

    /** Video, audio, anything else: not shown, its bytes not kept (D11). [mime] as received, cleaned. */
    data class Other(val mime: String) : RetrievedPart
}

/** A fetched MMS (m-retrieve-conf) as the isolated parse handed it back: headers and text as strings, pictures as our
 * own files. */
data class RetrievedMms(
    val from: MmsAddress?,
    val to: List<MmsAddress>,
    val cc: List<MmsAddress>,
    val transactionId: String?,
    val retrieveStatus: Int,
    val texts: List<String>,
    val parts: List<RetrievedPart>,
)

/** What became of a committed push. */
sealed interface PushOutcome {
    /** The rules can't be judged now (D15): the push stays in `pending_push`, judged at the next rule change. */
    data object Waiting : PushOutcome

    /** The MMSC repeated a push we already have (QA L5). */
    data object Duplicate : PushOutcome

    data class Held(val row: HeldRow) : PushOutcome

    data class Blocked(val row: BlockedRow?) : PushOutcome

    /** Shown with its placeholder; [fetch]: it is to be downloaded (else it already says why not, and is notified). */
    data class Shown(val shown: Received.Shown, val fetch: Boolean) : PushOutcome
}

/** An MMS to parse ([MmsFlow.parseJobs]): its downloaded file, whether pictures are decoded (D11), its cap. */
data class ParseJob(val messageId: String, val subId: Int?, val pictures: Boolean, val maxBytes: Int)

class MmsFlow internal constructor(
    private val engine: SmsEngine,
    private val store: MessageStore,
    private val clock: () -> Long,
    private val judge: () -> SmsJudge,
    private val ids: IdSource,
    private val radio: MmsRadio,
    private val files: PartFiles,
) {
    // ---- the push (D7, D10) ----

    /** The committed pushes to judge, oldest first. */
    fun pendingPushes(): List<PendingPushRow> = store.pendingPushes()

    /**
     * D10 for one committed push, with what the isolated parse made of it ([parsed]; `null` = not a notification we
     * accept): the sender (`/TYPE=PLMN` digits), D4 on the number, then - only for an allowed sender - the host and the
     * size. A stranger's push is held unfetched for the parent (D16); a released one ([PendingPushRow.heldId]) runs every
     * check again. The push row goes in the same transaction as its outcome.
     */
    fun judgePush(push: PendingPushRow, parsed: MmsNotification?): PushOutcome {
        val judge = judge()
        if (parsed == null || !MmsGate.answerableId(parsed.transactionId)) {
            return blockPush(push, CleanSender(SenderClass.MALFORMED, ""))
        }
        val number = when (val sender = MmsGate.sender(parsed.from)) {
            is MmsGate.Sender.Blocked -> return blockPush(push, CleanSender(sender.senderClass, ""))
            is MmsGate.Sender.Number -> sender.number
        }
        val verdict = SmsRules.decideIncoming(SenderKind.NUMBER, number, judge)
        if (verdict == IncomingVerdict.HoldRules) return PushOutcome.Waiting
        val clean = SenderClean.of(SenderKind.NUMBER, number, judge.defaultCc)
        val key = pushKey(parsed)
        val outcome = store.transaction {
            store.deletePendingPush(push.id)
            if (push.heldId == null) {
                // QA L5: an MMSC that repeats a push it got no answer to adds no rows.
                if (store.receivedBefore(key)) return@transaction PushOutcome.Duplicate
                store.rememberReceived(key, push.at)
                store.addCounter(SmsEngine.COUNT_RECEIVED)
            }
            when (verdict) {
                is IncomingVerdict.Allow -> admit(push, parsed, clean, verdict, judge)
                IncomingVerdict.HoldParent -> hold(push, clean)
                else -> PushOutcome.Blocked(engine.block(push.at, KIND_MMS, clean, SmsEngine.REASON_RULE))
            }
        }
        if (outcome is PushOutcome.Shown) engine.emit(EngineEvent.MessageAdded(outcome.shown.message.seq, outcome.shown.message))
        return outcome
    }

    /** §6.10: the isolated parse died on this push; the third time it is blocked as malformed. */
    fun pushCrashed(push: PendingPushRow): PushOutcome? {
        if (push.tries + 1 < PARSE_DEATHS) {
            store.countPushTry(push.id)
            return null
        }
        store.addCounter(SmsEngine.COUNT_PARSE_FAILURES)
        return blockPush(push, CleanSender(SenderClass.MALFORMED, ""))
    }

    private fun blockPush(push: PendingPushRow, clean: CleanSender): PushOutcome = store.transaction {
        store.deletePendingPush(push.id)
        PushOutcome.Blocked(engine.block(push.at, KIND_MMS, clean, SmsEngine.REASON_RULE))
    }

    /** D16: a stranger's push, held unfetched - only the push (at most 2 KB) is kept. */
    private fun hold(push: PendingPushRow, clean: CleanSender): PushOutcome {
        val held = store.heldRows().filter { it.state == HeldState.HELD }
        if (!HoldPolicy.admits(SenderKind.NUMBER, clean.sender, held)) {
            return PushOutcome.Blocked(engine.block(push.at, KIND_MMS, clean, SmsEngine.REASON_OVER_CAP))
        }
        val row = HeldRow(
            id = ids.hex(), at = push.at, kind = SenderKind.NUMBER, mms = true, senderClass = clean.senderClass,
            sender = clean.sender, body = null, push = push.pdu, subId = push.subId, sentAt = null,
            expiresAt = push.at + HoldPolicy.WINDOW_MS, state = HeldState.HELD, reported = false,
        )
        store.insertHeld(row)
        store.addCounter(SmsEngine.COUNT_HELD)
        return PushOutcome.Held(row)
    }

    /** An allowed sender (D4 rule 2 or 5): D10's host and size, then the message with its placeholder part. Inside the
     * push's transaction. */
    private fun admit(push: PendingPushRow, parsed: MmsNotification, clean: CleanSender, verdict: IncomingVerdict.Allow, judge: SmsJudge): PushOutcome {
        val carrier = judge.carrier(push.subId)
        val location = MmsGate.location(parsed.contentLocation, carrier.operator, judge.rules?.mmscHosts.orEmpty())
        if (location == MmsGate.Location.Bad) {
            // D10: any other host blocks, with no allow path - Enea's "MMS Fingerprint" (a push pointing elsewhere).
            return PushOutcome.Blocked(engine.block(push.at, KIND_MMS, CleanSender(SenderClass.BAD_LOCATION, ""), SmsEngine.REASON_RULE))
        }
        val now = clock()
        val expiresAt = parsed.expirySeconds.takeIf { it > 0 }?.let { it * 1000 }
        val (state, failure) = when {
            location == MmsGate.Location.UnknownOperator -> MmsState.FAILED to MmsRow.MMSC_UNKNOWN
            !MmsGate.sizeOk(parsed.messageSize, MmsCodec.capOf(carrier.maxMessageBytes)) -> MmsState.TOO_LARGE to null
            expiresAt != null && expiresAt <= now -> MmsState.GONE to null
            else -> MmsState.NOTIFIED to null
        }
        val conversation = engine.conversationFor(clean.sender, verdict.contact?.id, verdict.contact?.name, push.subId)
        val row = MessageRow(
            id = ids.ulid(now), conversationId = conversation.id, seq = store.nextSeq(), direction = Direction.IN,
            sender = clean.sender, senderKind = SenderKind.NUMBER, unknownSender = verdict.unknownSender, at = push.at,
            sentAt = null, body = null, delivery = Delivery.RECEIVED, failure = messageFailure(state, failure), read = false,
            clientId = null, origin = Origin.ENGINE, verdict = MessageVerdict.SHOWN, subId = push.subId, mms = true,
        )
        store.insertMessage(row)
        val mms = MmsRow(
            messageId = row.id, state = state, transactionId = parsed.transactionId, contentLocation = parsed.contentLocation,
            from = clean.sender, size = parsed.messageSize, expiresAt = expiresAt, mmsVersion = parsed.mmsVersion,
            subId = push.subId, failure = failure, notified = state != MmsState.NOTIFIED,
        )
        store.insertMms(mms)
        val parts = listOf(placeholder(state))
        store.putParts(row.id, parts)
        store.insertEvent(EventRow(row.seq, EventType.MESSAGE_ADDED, row.id, conversation.id, Origin.ENGINE, null, now))
        return PushOutcome.Shown(Received.Shown(row.toMessage(parts), conversation), fetch = state == MmsState.NOTIFIED)
    }

    // ---- the download (D10, D14) ----

    /**
     * Hands every notified MMS to the platform's download, oldest first: the host checked again (the list may have
     * changed), the expiry, then `downloading` committed with its try **before** the platform has it. A refusal at once
     * waits (D14). Returns what is newly shown in a final state (to notify).
     */
    fun drainDownloads(): List<Received.Shown> {
        val told = mutableListOf<Received.Shown>()
        for (m in store.mmsIn(setOf(MmsState.NOTIFIED)).sortedBy { it.messageId }) {
            val judge = judge()
            val carrier = judge.carrier(m.subId)
            val location = MmsGate.location(m.contentLocation, carrier.operator, judge.rules?.mmscHosts.orEmpty())
            if (location !is MmsGate.Location.Ok) {
                move(m, MmsState.FAILED, MmsRow.MMSC_UNKNOWN)?.let(told::add)
                continue
            }
            if (m.expiresAt != null && m.expiresAt <= clock()) {
                move(m, MmsState.GONE)?.let(told::add)
                continue
            }
            val requested = m.copy(state = MmsState.DOWNLOADING, tries = m.tries + 1, requestedAt = clock())
            store.updateMms(requested)
            try {
                radio.download(m.messageId, m.contentLocation, m.subId, MmsCodec.capOf(carrier.maxMessageBytes))
            } catch (e: Exception) {
                onDownloadFailed(m.messageId, DownloadError.NO_DATA)?.let(told::add)
            }
        }
        return told
    }

    /** The platform's download failed: D14's waiting state, five tries, then the kid's tap; "gone" is final. A result
     * for a message that isn't downloading (late, or deleted) is ignored. */
    fun onDownloadFailed(messageId: String, error: DownloadError): Received.Shown? {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADING } ?: return null
        return when {
            error == DownloadError.GONE -> move(m, MmsState.GONE)
            m.tries >= MAX_TRIES -> move(m, MmsState.FAILED, MmsRow.RETRY)
            else -> move(m, MmsState.WAITING_DATA)
        }
    }

    /** The file is ours now: `downloaded` is committed before the parse, so a process death resumes there. Returns
     * whether it is to be parsed. */
    fun onDownloaded(messageId: String): Boolean {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADING } ?: return false
        store.updateMms(m.copy(state = MmsState.DOWNLOADED))
        return true
    }

    /** The fetched messages to parse (also what a process death left behind), with whether their pictures are decoded
     * (D11: the sender's `mms` switch; a sender not in the phone book - the callback window - gets none). */
    fun parseJobs(): List<ParseJob> {
        val judge = judge()
        return store.mmsIn(setOf(MmsState.DOWNLOADED)).map { m ->
            val contact = judge.rules?.contacts?.firstOrNull { it.number == m.from }
            ParseJob(m.messageId, m.subId, pictures = contact?.mms == true, maxBytes = MmsCodec.capOf(judge.carrier(m.subId).maxMessageBytes))
        }
    }

    /**
     * The isolated parse's answer for a fetched MMS: its retrieve status, then D10's From (equal to the notification's),
     * then D12's group rule. A message that fails either is dropped (its placeholder goes, `MessageDeleted`) and counted
     * as blocked. Otherwise its text and parts are written, an accepted group moves it to the group's conversation, and
     * the MMSC gets its acknowledgement. Returns it to notify, unless the kid was told already.
     */
    fun onRetrieved(messageId: String, content: RetrievedMms): Received.Shown? {
        // The pictures this answer stored: kept only if a part ends up holding them.
        val fetched = content.parts.filterIsInstance<RetrievedPart.Picture>().map { it.sha256 }
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED || it.state == MmsState.DOWNLOADING }
        if (m == null) {
            dropUnheld(fetched)
            return null
        }
        val status = content.retrieveStatus
        if (status != RETRIEVE_OK) {
            dropUnheld(fetched)
            return when {
                status in RETRIEVE_TRANSIENT -> if (m.tries >= MAX_TRIES) move(m, MmsState.FAILED, MmsRow.RETRY) else move(m, MmsState.WAITING_DATA)
                status == RETRIEVE_NOT_FOUND -> move(m, MmsState.GONE)
                else -> move(m, MmsState.FAILED, "retrieve:$status")
            }
        }
        answer(m, content.transactionId)
        val judge = judge()
        val rules = judge.rules
        if (!MmsGate.sameSender(m.from, content.from, judge.defaultCc)) {
            drop(m, CleanSender(SenderClass.MALFORMED, ""), fetched)
            return null
        }
        val group = MmsGate.group(m.from, content.to, content.cc, judge.ownNumbers(), rules?.contacts.orEmpty(), judge.defaultCc)
        val row = store.message(messageId)
        if (row == null) {
            dropUnheld(fetched)
            return null
        }
        // D12: a group needs a sender that rule 5 lets in - a contact with "Kan sende melding til" (not the callback
        // window alone).
        if (group == MmsGate.Group.Blocked || (group is MmsGate.Group.Many && (row.unknownSender || !SmsRules.releases(SenderKind.NUMBER, m.from, judge)))) {
            drop(m, CleanSender(SenderClass.GROUP, ""), fetched)
            return null
        }
        val parts = content.parts.mapIndexed { i, part -> partOf(i, part) }
        val body = content.texts.filter { it.isNotBlank() }.joinToString("\n").take(BODY_MAX).ifEmpty { null }
        val now = clock()
        val events = mutableListOf<EngineEvent>()
        val result = store.transaction {
            var updated = row.copy(body = body, failure = null)
            var conversation = store.conversation(row.conversationId.orEmpty())
            if (group is MmsGate.Group.Many) {
                // Moved to the group's own conversation: gone from the sender's, added to the group's with a new seq.
                val target = engine.groupConversationFor(listOf(m.from) + group.others, m.subId)
                if (target.id != row.conversationId) {
                    val deleted = store.nextSeq()
                    store.insertEvent(EventRow(deleted, EventType.MESSAGE_DELETED, row.id, row.conversationId, Origin.ENGINE, null, now))
                    events += EngineEvent.MessageDeleted(deleted, MessageId(row.id), Origin.ENGINE)
                    updated = updated.copy(conversationId = target.id, seq = store.nextSeq())
                    store.insertEvent(EventRow(updated.seq, EventType.MESSAGE_ADDED, row.id, target.id, Origin.ENGINE, null, now))
                    conversation = target
                }
            }
            store.updateMessage(updated)
            store.putParts(row.id, parts)
            store.updateMms(m.copy(state = MmsState.DONE, failure = null, notified = true))
            val message = updated.toMessage(parts)
            if (updated.seq != row.seq) {
                events += EngineEvent.MessageAdded(updated.seq, message)
            } else {
                val seq = store.nextSeq()
                store.insertEvent(EventRow(seq, EventType.MESSAGE_UPDATED, row.id, row.conversationId, Origin.ENGINE, null, now))
                events += EngineEvent.MessageUpdated(seq, MessageId(row.id), message.delivery, parts)
            }
            conversation?.let { Received.Shown(message, it) }
        }
        events.forEach(engine::emit)
        dropUnheld(fetched)
        return result?.takeIf { !m.notified }
    }

    /** The isolated parse couldn't read the fetched message (not an m-retrieve-conf we accept): it shows as failed and
     * is counted (D8, §6.10). */
    fun onParseFailed(messageId: String): Received.Shown? {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED } ?: return null
        store.addCounter(SmsEngine.COUNT_PARSE_FAILURES)
        return move(m, MmsState.FAILED, "parse")
    }

    /** §6.10: the isolated process died on this message; the third time it is failed for good (no retry). */
    fun onParseCrashed(messageId: String): Received.Shown? {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED } ?: return null
        if (m.crashes + 1 < PARSE_DEATHS) {
            store.updateMms(m.copy(crashes = m.crashes + 1))
            return null
        }
        store.addCounter(SmsEngine.COUNT_PARSE_FAILURES)
        return move(m.copy(crashes = m.crashes + 1), MmsState.FAILED, "crash")
    }

    /**
     * D14's retry: every waiting MMS is notified again (the next [drainDownloads] fetches it). With [dataOn] (mobile
     * data just came back) also those whose five tries ran out - their count starts again. A download the platform
     * never answered (15 minutes) waits too.
     */
    fun retryWaiting(dataOn: Boolean) {
        val now = clock()
        for (m in store.mmsIn(setOf(MmsState.DOWNLOADING))) {
            if (now - (m.requestedAt ?: 0) >= DOWNLOAD_SILENT_MS) onDownloadFailed(m.messageId, DownloadError.OTHER)
        }
        for (m in store.mmsIn(setOf(MmsState.WAITING_DATA, MmsState.FAILED))) {
            when {
                m.state == MmsState.WAITING_DATA -> move(m.copy(tries = if (dataOn) 0 else m.tries), MmsState.NOTIFIED)
                dataOn && m.failure == MmsRow.RETRY -> move(m.copy(tries = 0), MmsState.NOTIFIED)
            }
        }
    }

    /** The kid's "Trykk for å prøve igjen" (D14): a failed fetch that offers it starts again. Returns whether it did. */
    fun retryDownload(messageId: String): Boolean {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.FAILED && it.failure == MmsRow.RETRY } ?: return false
        move(m.copy(tries = 0), MmsState.NOTIFIED)
        return true
    }

    /** `sms_state.mms_waiting`: the MMS waiting for data. */
    fun waiting(): Int = store.mmsIn(setOf(MmsState.WAITING_DATA)).size

    /** The message's state change: the `mms` row, its placeholder part and the message's failure, with `MessageUpdated`.
     * Returns the message to notify the first time it shows in a state the kid should hear of. */
    private fun move(m: MmsRow, state: MmsState, failure: String? = null): Received.Shown? {
        val row = store.message(m.messageId) ?: return null
        val tell = !m.notified && state in TOLD_STATES
        val parts = listOf(placeholder(state))
        val updated = row.copy(failure = messageFailure(state, failure))
        val seq = store.transaction {
            store.updateMms(m.copy(state = state, failure = failure, notified = m.notified || tell))
            store.updateMessage(updated)
            store.putParts(row.id, parts)
            store.nextSeq().also { store.insertEvent(EventRow(it, EventType.MESSAGE_UPDATED, row.id, row.conversationId, Origin.ENGINE, null, clock())) }
        }
        engine.emit(EngineEvent.MessageUpdated(seq, MessageId(row.id), updated.delivery, parts))
        val conversation = store.conversation(row.conversationId.orEmpty())
        return if (tell && conversation != null) Received.Shown(updated.toMessage(parts), conversation) else null
    }

    /** D10/D12: a fetched message that fails the From or group check goes - placeholder and all - and is counted. */
    private fun drop(m: MmsRow, clean: CleanSender, fetched: List<String>) {
        val row = store.message(m.messageId)
        val held = store.parts(m.messageId).mapNotNull { it.sha256 }
        val seq = store.transaction {
            store.deleteMessageRow(m.messageId)
            engine.block(row?.at ?: clock(), KIND_MMS, clean, SmsEngine.REASON_RULE)
            store.nextSeq().also { store.insertEvent(EventRow(it, EventType.MESSAGE_DELETED, m.messageId, row?.conversationId, Origin.ENGINE, null, clock())) }
        }
        engine.emit(EngineEvent.MessageDeleted(seq, MessageId(m.messageId), Origin.ENGINE))
        store.afterDeletes()
        dropUnheld(held + fetched)
    }

    /** The m-acknowledge-ind for a fetched message (AM's `MmsSender`), through the platform; a failure only logs. */
    private fun answer(m: MmsRow, transactionId: String?) {
        val id = transactionId?.takeIf(MmsGate::answerableId) ?: m.transactionId
        val pdu = (MmsCodec.composeAcknowledge(id, m.mmsVersion) as? MmsComposeResult.Ok)?.bytes ?: return
        try {
            radio.answer(pdu, m.subId)
        } catch (e: Exception) {
            // Nothing to do: the MMSC may send its push again, which the dedup (QA L5) absorbs.
        }
    }

    // ---- sending (D11, D12) ----

    /** A picture the kid picked, as the main process re-encoded it (D8, D11: EXIF and GPS gone): kept by its hash until
     * a send takes it (an unsent one goes after a day, [prune]). */
    fun putPicture(jpeg: ByteArray, width: Int, height: Int): PictureRef = PictureRef(files.put(jpeg), width, height)

    /**
     * Hands one queued MMS to the platform (from [SmsEngine.drainOutbox], the gate already passed for every
     * recipient): its pictures read back from our files, composed with the vendored `PduComposer` under the carrier's
     * maximum. Returns the failure, or `null` when it went to the platform.
     */
    internal fun compose(row: MessageRow, recipients: List<String>): Pair<ByteArray?, String?> {
        val pictures = store.parts(row.id).filter { it.state == PartState.AVAILABLE && it.sha256 != null }.sortedBy { it.index }
        val outgoing = pictures.map { part ->
            val bytes = files.read(part.sha256!!) ?: return null to "picture_missing"
            MmsOutgoingPart.jpeg(bytes)
        } + listOfNotNull(row.body?.takeIf { it.isNotBlank() }?.let(MmsOutgoingPart::text))
        val cap = judge().carrier(row.subId).maxMessageBytes
        return when (val composed = MmsCodec.composeSendReq(recipients, outgoing, cap, transactionId = row.id, nowSeconds = clock() / 1000)) {
            is MmsComposeResult.Ok -> composed.bytes to null
            is MmsComposeResult.Failed -> null to "compose:${composed.reason.name.lowercase()}"
        }
    }

    internal fun sendPdu(row: MessageRow, pdu: ByteArray) = radio.send(row.id, pdu, row.subId)

    /** The parts of an outgoing picture message: our own JPEGs, available from the start. */
    internal fun outgoingParts(pictures: List<PictureRef>): List<Part> = pictures.mapIndexed { i, p ->
        Part(i, MIME_JPEG, files.size(p.sha256) ?: 0, p.sha256, p.width, p.height, PartState.AVAILABLE)
    }

    internal fun pictureSize(ref: PictureRef): Long? = files.size(ref.sha256)

    // ---- the picture files ----

    fun open(sha256: String): java.io.InputStream? = files.open(sha256)

    /** A picture file no part holds any more goes at once - of [candidates], the files a deleted or dropped message
     * held, or a parse stored (D18, §6.4). A pick the kid hasn't sent yet ([putPicture]) is never one of them. */
    fun dropUnheld(candidates: Collection<String>) {
        if (candidates.isEmpty()) return
        val held = store.partHashes()
        candidates.filter { it !in held }.forEach(files::delete)
    }

    /** At each sync: any file no part holds that is older than [STAGED_KEEP_MS] - an unsent pick, or one a process death
     * left between the encode and the parse's answer. */
    fun prune() {
        val held = store.partHashes()
        val before = clock() - STAGED_KEEP_MS
        for ((sha, modified) in files.all()) if (sha !in held && modified < before) files.delete(sha)
    }

    private fun partOf(index: Int, part: RetrievedPart): Part = when (part) {
        is RetrievedPart.Picture -> Part(index, MIME_JPEG, part.size, part.sha256, part.width, part.height, PartState.AVAILABLE)
        RetrievedPart.NotShown -> Part(index, MIME_JPEG, 0, null, null, null, PartState.NOT_SHOWN)
        RetrievedPart.Undecodable -> Part(index, MIME_JPEG, 0, null, null, null, PartState.FAILED)
        is RetrievedPart.Other -> Part(index, cleanMime(part.mime), 0, null, null, null, PartState.FAILED)
    }

    companion object {
        /** The blocked and held rows' kind for an MMS (the server's intake takes `sms` and `mms`). */
        const val KIND_MMS = "mms"

        const val MIME_JPEG = "image/jpeg"

        /** D14: tries before "Trykk for å prøve igjen". */
        const val MAX_TRIES = 5

        /** §6.10: the isolated parse dying this often on one message or push gives it up. */
        const val PARSE_DEATHS = 3

        /** A download the platform never answered is given up this long after it was asked for. */
        const val DOWNLOAD_SILENT_MS = 15 * 60 * 1000L

        /** A picture the kid picked and never sent is kept this long. */
        const val STAGED_KEEP_MS = 24 * 60 * 60 * 1000L

        /** An MMS's text, all text parts joined (each part already at most 5,000 characters, D8). */
        const val BODY_MAX = 10_000

        /** The message's failure while its fetch offers the kid's tap (the screens read it). */
        const val FAILURE_RETRY = "mms_retry"

        /** X-Mms-Retrieve-Status (OMA MMS ENC 1.3 §7.3.50): ok, the transient range, "message not found". */
        const val RETRIEVE_OK = 0x80
        val RETRIEVE_TRANSIENT = 0xC0..0xDF
        const val RETRIEVE_NOT_FOUND = 0xE2

        /** The states the kid hears of once (a fetch that waits, fails or is gone; the done one when it arrives). */
        private val TOLD_STATES = setOf(MmsState.WAITING_DATA, MmsState.FAILED, MmsState.GONE, MmsState.TOO_LARGE)

        /**
         * The platform's download result (`SmsManager.MMS_ERROR_*`) and HTTP status as a [DownloadError]: 404/410 from the
         * MMSC is gone; no connection to the MMS network (3), no data network (8) or MMS data off (11) waits for data
         * (D14); anything else is tried again the same way.
         */
        fun downloadErrorOf(code: Int, httpStatus: Int): DownloadError = when {
            code == MMS_ERROR_HTTP_FAILURE && httpStatus in GONE_STATUSES -> DownloadError.GONE
            code in NO_DATA_ERRORS -> DownloadError.NO_DATA
            else -> DownloadError.OTHER
        }

        private const val MMS_ERROR_HTTP_FAILURE = 4
        private val NO_DATA_ERRORS = setOf(3, 8, 11)
        private val GONE_STATUSES = setOf(404, 410)

        /** The placeholder part an MMS shows until it is parsed. */
        fun placeholder(state: MmsState): Part = Part(0, MIME_JPEG, 0, null, null, null, partStateOf(state))

        fun partStateOf(state: MmsState): PartState = when (state) {
            MmsState.NOTIFIED, MmsState.DOWNLOADING, MmsState.DOWNLOADED, MmsState.DONE -> PartState.PENDING
            MmsState.WAITING_DATA -> PartState.WAITING_DATA
            MmsState.FAILED -> PartState.FAILED
            MmsState.GONE -> PartState.GONE
            MmsState.TOO_LARGE -> PartState.TOO_LARGE
        }

        private fun messageFailure(state: MmsState, failure: String?): String? =
            if (state == MmsState.FAILED && failure == MmsRow.RETRY) FAILURE_RETRY else null

        /** A received part's type as stored: lower case, `type/subtype` of token characters, else `application/octet-stream`. */
        fun cleanMime(raw: String): String {
            val mime = raw.substringBefore(';').trim().lowercase()
            return if (mime.length <= 64 && MIME.matches(mime)) mime else "application/octet-stream"
        }

        private val MIME = Regex("""[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*""")

        /** QA L5: a push's identity - (transaction id, content location), hashed. */
        fun pushKey(n: MmsNotification): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("mms\u0000${n.transactionId}\u0000${n.contentLocation}".toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
