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

    /** A fetched message was dropped before its parse: its downloaded file goes now (verification N-V3). */
    fun discardDownload(messageId: String) {}

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
    /** Picks the kid hasn't sent yet ([putPicture]), by hash and when: a delete never takes their files (code QA N8). */
    private val staged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---- the push (D7, D10) ----

    /** The committed pushes to judge, oldest first. */
    fun pendingPushes(): List<PendingPushRow> = store.pendingPushes()

    /**
     * D10 for one committed push, with what the isolated parse made of it ([parsed]; `null` = not a notification we
     * accept): the sender (`/TYPE=PLMN` digits), D4 on the number, then - only for an allowed sender - the host and the
     * size. A stranger's push is held unfetched for the parent (D16); a released one ([PendingPushRow.heldId]) runs every
     * check again, and its held row says what came of it ("released" only once the kid has it, code QA L9). The push
     * row goes in the same transaction as its outcome, and a repeat of the same push - blocked ones included - is known
     * until its own expiry (at most a week, code QA L2).
     */
    fun judgePush(push: PendingPushRow, parsed: MmsNotification?): PushOutcome {
        val judge = judge()
        val now = clock()
        val key = parsed?.let(::pushKey) ?: rawPushKey(push.pdu)
        val keepUntil = (parsed?.expirySeconds?.takeIf { it > 0 }?.let { it * 1000 } ?: (now + PUSH_KEY_KEEP_MS))
            .coerceIn(now + SmsEngine.RECEIVED_KEEP_MS, now + PUSH_KEY_KEEP_MS)
        if (push.heldId != null && store.held(push.heldId)?.state != HeldState.HELD) {
            // The parent's "Slett nå" came after the allow: nothing of it is fetched.
            store.deletePendingPush(push.id)
            return PushOutcome.Blocked(null)
        }
        if (parsed == null || !MmsGate.answerableId(parsed.transactionId)) {
            return blockPush(push, CleanSender(SenderClass.MALFORMED, ""), key, keepUntil)
        }
        val number = when (val sender = MmsGate.sender(parsed.from)) {
            is MmsGate.Sender.Blocked -> return blockPush(push, CleanSender(sender.senderClass, ""), key, keepUntil)
            is MmsGate.Sender.Number -> sender.number
        }
        val verdict = SmsRules.decideIncoming(SenderKind.NUMBER, number, judge)
        if (verdict == IncomingVerdict.HoldRules) return PushOutcome.Waiting
        val clean = SenderClean.of(SenderKind.NUMBER, number, judge.defaultCc)
        val outcome = store.transaction {
            store.deletePendingPush(push.id)
            if (push.heldId == null) {
                // QA L5: an MMSC that repeats a push it got no answer to adds no rows.
                if (store.receivedBefore(key)) return@transaction PushOutcome.Duplicate
                remember(key, keepUntil)
                store.addCounter(SmsEngine.COUNT_RECEIVED)
            }
            val result = when (verdict) {
                is IncomingVerdict.Allow -> admit(push, parsed, clean, verdict, judge)
                IncomingVerdict.HoldParent -> hold(push, clean)
                else -> PushOutcome.Blocked(engine.block(push.at, KIND_MMS, clean, SmsEngine.REASON_RULE))
            }
            push.heldId?.let { settleRelease(it, result) }
            result
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
        val now = clock()
        return blockPush(push, CleanSender(SenderClass.MALFORMED, ""), rawPushKey(push.pdu), now + PUSH_KEY_KEEP_MS)
    }

    private fun blockPush(push: PendingPushRow, clean: CleanSender, key: String, keepUntil: Long): PushOutcome = store.transaction {
        store.deletePendingPush(push.id)
        if (push.heldId == null) {
            // Code QA L2: a repeated push that blocks early adds no second blocked row.
            if (store.receivedBefore(key)) return@transaction PushOutcome.Duplicate
            remember(key, keepUntil)
        }
        val result = PushOutcome.Blocked(engine.block(push.at, KIND_MMS, clean, SmsEngine.REASON_RULE))
        push.heldId?.let { settleRelease(it, result) }
        result
    }

    /** `received_key` keeps a key a day past its `at`: the push's key is kept until [keepUntil]. */
    private fun remember(key: String, keepUntil: Long) = store.rememberReceived(key, keepUntil - SmsEngine.RECEIVED_KEEP_MS)

    /** Code QA L9: a released push's held row is "released" once the kid has the message (shown - a fetch that then
     * fails is still shown), "dropped" when the second judgement blocks it or holds it again. Inside the transaction. */
    private fun settleRelease(heldId: String, result: PushOutcome) {
        val held = store.held(heldId)?.takeIf { it.state == HeldState.HELD } ?: return
        val state = if (result is PushOutcome.Shown) HeldState.RELEASED else HeldState.DROPPED
        store.updateHeld(held.copy(state = state, body = null, push = null, reported = false))
        if (state == HeldState.RELEASED) store.addCounter(SmsEngine.COUNT_RELEASED)
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
            subId = push.subId, failure = failure, notified = state != MmsState.NOTIFIED, deferred = push.heldId != null,
        )
        store.insertMms(mms)
        val parts = listOf(placeholder(state))
        store.putParts(row.id, parts)
        store.insertEvent(EventRow(row.seq, EventType.MESSAGE_ADDED, row.id, conversation.id, Origin.ENGINE, null, now))
        return PushOutcome.Shown(Received.Shown(row.toMessage(parts), conversation), fetch = state == MmsState.NOTIFIED)
    }

    // ---- the sender, again (code QA M2) ----

    private enum class SenderNow { ALLOW, WAIT, DROP }

    /**
     * D10's promise holds at every fetch, not only at the push: the sender is judged again by D4 as of now. A contact
     * whose "Kan sende melding til" went off (or who was removed), or a stranger the callback window let in after the
     * window closed, is no longer allowed - nothing more is fetched for them. Rules that can't be read wait.
     */
    private fun senderNow(m: MmsRow, judge: SmsJudge): SenderNow = when (SmsRules.decideIncoming(SenderKind.NUMBER, m.from, judge)) {
        is IncomingVerdict.Allow -> SenderNow.ALLOW
        IncomingVerdict.HoldRules -> SenderNow.WAIT
        else -> SenderNow.DROP
    }

    private fun dropForSender(m: MmsRow, judge: SmsJudge, fetched: List<String> = emptyList()) =
        drop(m, SenderClean.of(SenderKind.NUMBER, m.from, judge.defaultCc), fetched)

    // ---- the download (D10, D14) ----

    /**
     * Hands every notified MMS to the platform's download, oldest first: the sender judged again (code QA M2), the host
     * checked again (the list may have changed), the expiry, then `downloading` committed with its try **before** the
     * platform has it. A refusal at once waits (D14). Returns what is newly shown in a final state (to notify).
     */
    fun drainDownloads(): List<Received.Shown> {
        val told = mutableListOf<Received.Shown>()
        for (m in store.mmsIn(setOf(MmsState.NOTIFIED)).sortedBy { it.messageId }) {
            val judge = judge()
            when (senderNow(m, judge)) {
                SenderNow.ALLOW -> Unit
                SenderNow.WAIT -> continue
                SenderNow.DROP -> {
                    dropForSender(m, judge)
                    continue
                }
            }
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

    /** Code QA L5: the download ran past our byte cap (the MMSC sent more than its notification said, or more than the
     * carrier's maximum) - too large, final; never fetched again. */
    fun onDownloadTooLarge(messageId: String): Received.Shown? {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADING } ?: return null
        return move(m, MmsState.TOO_LARGE)
    }

    /** The file is ours now: `downloaded` is committed before the parse, so a process death resumes there. Returns
     * whether it is to be parsed. */
    fun onDownloaded(messageId: String): Boolean {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADING } ?: return false
        store.updateMms(m.copy(state = MmsState.DOWNLOADED))
        return true
    }

    /**
     * The fetched messages to parse (also what a process death left behind), with whether their pictures are decoded
     * (D11: the sender's `mms` switch; a sender not in the phone book - the callback window - gets none). A sender no
     * longer allowed is dropped here, before the parse (code QA M2); one the rules can't judge waits.
     */
    fun parseJobs(): List<ParseJob> {
        val judge = judge()
        return store.mmsIn(setOf(MmsState.DOWNLOADED)).mapNotNull { m ->
            when (senderNow(m, judge)) {
                SenderNow.WAIT -> return@mapNotNull null
                SenderNow.DROP -> {
                    dropForSender(m, judge)
                    return@mapNotNull null
                }
                SenderNow.ALLOW -> Unit
            }
            val contact = judge.rules?.contacts?.firstOrNull { it.number == m.from }
            ParseJob(m.messageId, m.subId, pictures = contact?.mms == true, maxBytes = MmsCodec.capOf(judge.carrier(m.subId).maxMessageBytes))
        }
    }

    /**
     * Code QA M1: the parse's attempt is counted **before** it runs, so a death of the main process while it handles
     * the answer (not only of the isolated one) counts too - the third start gives up instead of looping. Returns
     * whether to parse now, and what to notify when the message is given up.
     */
    fun beginParse(messageId: String): Pair<Boolean, Received.Shown?> {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED } ?: return false to null
        if (m.crashes >= PARSE_DEATHS) {
            store.addCounter(SmsEngine.COUNT_PARSE_FAILURES)
            return false to move(m, MmsState.FAILED, FAILURE_CRASH)
        }
        store.updateMms(m.copy(crashes = m.crashes + 1))
        return true to null
    }

    /** The isolated service couldn't be reached (not the message's fault): the attempt [beginParse] counted is given
     * back. */
    fun parseUnavailable(messageId: String) {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED && it.crashes > 0 } ?: return
        store.updateMms(m.copy(crashes = m.crashes - 1))
    }

    /** Whether a fetched message still waits for its parse (its downloaded file is kept then). */
    fun awaitsParse(messageId: String): Boolean = store.mms(messageId)?.state == MmsState.DOWNLOADED

    /**
     * The isolated parse's answer for a fetched MMS: its retrieve status, the sender judged again (code QA M2), D10's
     * From (equal to the notification's), then D12's group rule. A message that fails one is dropped (its placeholder
     * goes, `MessageDeleted`), counted as blocked, and **not answered** (code QA L1). Otherwise its text and parts are
     * written, an accepted group moves it to the group's conversation, and the MMSC gets its answer - an m-notifyresp-ind
     * "retrieved" for an immediate fetch, an m-acknowledge-ind for a deferred one (code QA L2). Returns it to notify:
     * quietly when the kid was told of it before (it waited), with the conversation it left when it moved (code QA L8).
     * A message of exactly one text and nothing else that is a reaction naming a bubble of the conversation it ended up
     * in becomes that reaction (design 81 D9): [Received.Attached].
     */
    fun onRetrieved(messageId: String, content: RetrievedMms): Received? {
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
        val judge = judge()
        when (senderNow(m, judge)) {
            SenderNow.ALLOW -> Unit
            SenderNow.WAIT -> {
                // Rules that can't be read: it stays fetched and is parsed again once they can - and this parse doesn't
                // count against its three (verification N-V3).
                store.updateMms(m.copy(crashes = (m.crashes - 1).coerceAtLeast(0)))
                dropUnheld(fetched)
                return null
            }
            SenderNow.DROP -> {
                dropForSender(m, judge, fetched)
                return null
            }
        }
        val rules = judge.rules
        if (!MmsGate.sameSender(m.from, content.from, judge.defaultCc)) {
            drop(m, CleanSender(SenderClass.MALFORMED, ""), fetched)
            return null
        }
        // Design 80 §4.4: a gap in the own numbers is filled from an approved contact's message to the kid alone; the
        // learned number is "us" only while the parent and the SIMs give none. Design 80 D13: groups off blocks every
        // group.
        val contacts = rules?.contacts.orEmpty()
        var learned = ownLearnedFor(judge)
        val senderAllowed = rules?.smsMode == com.kidslauncher.mdm.calls.ChannelMode.MANAGED &&
            contacts.any { it.number == m.from && it.smsIn } && !judge.isEmergency(m.from)
        OwnLearning.learn(
            learned, judge.ownNumbers(), m.from, senderAllowed, content.to, content.cc, contacts, judge::isEmergency,
            judge.defaultCc, clock(), judge.simSet(),
        )?.let {
            store.putOwnLearned(OwnLearning.encode(it))
            learned = it
        }
        val own = MmsGate.ownNumbers(rules?.ownNumbers.orEmpty(), judge.simNumbers(), learned?.number)
        val group = MmsGate.group(m.from, content.to, content.cc, own, contacts, judge.defaultCc, groups = rules?.smsGroups != false)
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
        answer(m, content.transactionId)
        val parts = content.parts.mapIndexed { i, part -> partOf(i, part) }
        val body = content.texts.filter { it.isNotBlank() }.joinToString("\n").take(BODY_MAX).ifEmpty { null }
        val now = clock()
        val events = mutableListOf<EngineEvent>()
        var movedFrom: String? = null
        // Design 81 D9 (H1): only an MMS that holds nothing but one text may be a reaction. Until now this row was a
        // placeholder, which no reaction attaches to (code QA M1): whatever it turns out to be, nothing hangs on it.
        val mayReact = body != null && Reactions.candidate(textParts = content.texts.size, otherParts = content.parts.size)
        val result = store.transaction {
            var updated = row.copy(body = body, failure = null)
            var conversation = store.conversation(row.conversationId.orEmpty())
            if (group is MmsGate.Group.Many) {
                // Moved to the group's own conversation: gone from the sender's, added to the group's with a new seq.
                val target = engine.groupConversationFor(listOf(m.from) + group.others, m.subId)
                if (target.id != row.conversationId) {
                    movedFrom = row.conversationId
                    val deleted = store.nextSeq()
                    store.insertEvent(EventRow(deleted, EventType.MESSAGE_DELETED, row.id, row.conversationId, Origin.ENGINE, null, now))
                    events += EngineEvent.MessageDeleted(deleted, MessageId(row.id), Origin.ENGINE)
                    updated = updated.copy(conversationId = target.id, seq = store.nextSeq())
                    store.insertEvent(EventRow(updated.seq, EventType.MESSAGE_ADDED, row.id, target.id, Origin.ENGINE, null, now))
                    conversation = target
                }
            }
            // Matched in the conversation it ended up in, against the bubbles older than it (D3, D9).
            val link = if (mayReact && conversation != null) engine.attachment(body!!, m.from, conversation.id, updated.seq) else null
            if (link != null) updated = updated.attachedTo(link)
            store.updateMessage(updated)
            store.putParts(row.id, parts)
            store.updateMms(m.copy(state = MmsState.DONE, failure = null, notified = true, crashes = 0))
            val message = updated.toMessage(parts)
            if (updated.seq != row.seq) {
                events += EngineEvent.MessageAdded(updated.seq, message)
            } else {
                val seq = store.nextSeq()
                store.insertEvent(EventRow(seq, EventType.MESSAGE_UPDATED, row.id, row.conversationId, Origin.ENGINE, null, now))
                events += EngineEvent.MessageUpdated(seq, MessageId(row.id), message.delivery, parts)
            }
            conversation?.let {
                // A reaction whose placeholder was told before only rebuilds the notification (D12).
                if (link != null) Received.Attached(message, it, notify = link.op == ReactionOp.ADD && !m.notified, movedFrom = movedFrom)
                else Received.Shown(message, it, again = m.notified, movedFrom = movedFrom)
            }
        }
        events.forEach(engine::emit)
        dropUnheld(fetched)
        return result
    }

    /** Design 80 §4.4: the own number learned from a message, in force now (`sms_state.own_learned`). */
    fun ownLearned(): OwnLearned? = ownLearnedFor(judge())

    /** [OwnLearning.current] on the stored state; a state it clears (a SIM change, the parent's number, a contact with
     * the number) is forgotten in the store, so nothing comes back after the reason goes. */
    private fun ownLearnedFor(judge: SmsJudge): OwnLearned? {
        val raw = store.ownLearned() ?: return null
        // Code QA L2: the active SIMs can't be read right now - keep what is stored, use none of it.
        val sims = judge.simSet().ifEmpty { return null }
        val current = OwnLearning.current(OwnLearning.decode(raw), sims, judge.rules?.ownNumbers.orEmpty(), judge.rules?.contacts.orEmpty())
        if (current == null) store.putOwnLearned(null)
        return current
    }

    /** The isolated parse couldn't read the fetched message (not an m-retrieve-conf we accept): it shows as not
     * readable and is counted (D8, §6.10). */
    fun onParseFailed(messageId: String): Received.Shown? {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED } ?: return null
        store.addCounter(SmsEngine.COUNT_PARSE_FAILURES)
        return move(m, MmsState.FAILED, FAILURE_PARSE)
    }

    /** §6.10: the isolated process died on this message. Its attempt was counted by [beginParse]; the third one fails
     * it for good (no retry). */
    fun onParseCrashed(messageId: String): Received.Shown? {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.DOWNLOADED } ?: return null
        if (m.crashes < PARSE_DEATHS) return null
        store.addCounter(SmsEngine.COUNT_PARSE_FAILURES)
        return move(m, MmsState.FAILED, FAILURE_CRASH)
    }

    /**
     * D14's retry: every waiting MMS is notified again (the next [drainDownloads] judges the sender again and fetches
     * it). With [dataOn] (mobile data just came back) also those whose five tries ran out - their count starts again. A
     * download the platform never answered (15 minutes) waits too. One that failed for an unknown MCC/MNC is tried again
     * once the MMSC list knows this SIM (a policy after step 0's table, code QA L10). Returns whether anything moved.
     */
    fun retryWaiting(dataOn: Boolean): Boolean {
        val now = clock()
        var moved = false
        for (m in store.mmsIn(setOf(MmsState.DOWNLOADING))) {
            if (now - (m.requestedAt ?: 0) >= DOWNLOAD_SILENT_MS) onDownloadFailed(m.messageId, DownloadError.OTHER)
        }
        val judge = judge()
        for (m in store.mmsIn(setOf(MmsState.WAITING_DATA, MmsState.FAILED))) {
            val again = when {
                m.state == MmsState.WAITING_DATA -> m.copy(tries = if (dataOn) 0 else m.tries)
                dataOn && m.failure == MmsRow.RETRY -> m.copy(tries = 0)
                m.failure == MmsRow.MMSC_UNKNOWN && MmsGate.known(judge.carrier(m.subId).operator, judge.rules?.mmscHosts.orEmpty()) -> m
                else -> null
            } ?: continue
            move(again, MmsState.NOTIFIED)
            moved = true
        }
        return moved
    }

    /** The kid's "Trykk for å prøve igjen" (D14): a failed fetch that offers it starts again. Returns whether it did. */
    fun retryDownload(messageId: String): Boolean {
        val m = store.mms(messageId)?.takeIf { it.state == MmsState.FAILED && it.failure == MmsRow.RETRY } ?: return false
        move(m.copy(tries = 0), MmsState.NOTIFIED)
        return true
    }

    /** Code QA L6: the isolated parse service couldn't be bound (counted; `last_error` says so). */
    fun countUnavailable() = store.addCounter(COUNT_PARSE_UNAVAILABLE)

    /** `sms_state.mms_waiting`: the MMS waiting for data. */
    fun waiting(): Int = store.mmsIn(setOf(MmsState.WAITING_DATA)).size

    /** The message's state change: the `mms` row, its placeholder part and the message's failure, with `MessageUpdated`.
     * Returns the message to notify the first time it shows in a state the kid should hear of. A fetch that waits or
     * fails is deferred: its answer is then an acknowledgement (code QA L2). */
    private fun move(m: MmsRow, state: MmsState, failure: String? = null): Received.Shown? {
        val row = store.message(m.messageId) ?: return null
        val tell = !m.notified && state in TOLD_STATES
        val parts = listOf(placeholder(state))
        val updated = row.copy(failure = messageFailure(state, failure))
        val deferred = m.deferred || state == MmsState.WAITING_DATA || state == MmsState.FAILED
        val seq = store.transaction {
            store.updateMms(m.copy(state = state, failure = failure, notified = m.notified || tell, deferred = deferred))
            store.updateMessage(updated)
            store.putParts(row.id, parts)
            store.nextSeq().also { store.insertEvent(EventRow(it, EventType.MESSAGE_UPDATED, row.id, row.conversationId, Origin.ENGINE, null, clock())) }
        }
        engine.emit(EngineEvent.MessageUpdated(seq, MessageId(row.id), updated.delivery, parts))
        val conversation = store.conversation(row.conversationId.orEmpty())
        return if (tell && conversation != null) Received.Shown(updated.toMessage(parts), conversation) else null
    }

    /** D10/D12, code QA M2: a message that fails the sender, From or group check goes - placeholder and all - and is
     * counted. It is never answered. */
    private fun drop(m: MmsRow, clean: CleanSender, fetched: List<String>) {
        val row = store.message(m.messageId)
        val held = store.parts(m.messageId).mapNotNull { it.sha256 }
        if (m.state == MmsState.DOWNLOADED || m.state == MmsState.DOWNLOADING) radio.discardDownload(m.messageId)
        // Design 81 (code QA M1): a message still being fetched never carries a reaction, so none goes with it.
        val seq = store.transaction {
            store.deleteMessageRow(m.messageId)
            engine.block(row?.at ?: clock(), KIND_MMS, clean, SmsEngine.REASON_RULE)
            store.nextSeq().also { store.insertEvent(EventRow(it, EventType.MESSAGE_DELETED, m.messageId, row?.conversationId, Origin.ENGINE, null, clock())) }
        }
        engine.emit(EngineEvent.MessageDeleted(seq, MessageId(m.messageId), Origin.ENGINE))
        store.afterDeletes()
        dropUnheld(held + fetched)
    }

    /** The answer for a fetched, kept message, through the platform (a failure only logs): an immediate fetch's
     * m-notifyresp-ind "retrieved" with the notification's transaction id, a deferred one's m-acknowledge-ind (AM's
     * `MmsSender`, OMA MMS ENC; code QA L2). */
    private fun answer(m: MmsRow, transactionId: String?) {
        val immediate = !m.deferred && m.tries <= 1
        val composed = if (immediate) {
            MmsCodec.composeNotifyResp(m.transactionId, com.kidslauncher.mdm.sms.mms.MmsNotifyStatus.RETRIEVED, m.mmsVersion)
        } else {
            MmsCodec.composeAcknowledge(transactionId?.takeIf(MmsGate::answerableId) ?: m.transactionId, m.mmsVersion)
        }
        val pdu = (composed as? MmsComposeResult.Ok)?.bytes ?: return
        try {
            radio.answer(pdu, m.subId)
        } catch (e: Exception) {
            // Nothing to do: the MMSC may send its push again, which the dedup (QA L5) absorbs.
        }
    }

    // ---- sending (D11, D12) ----

    /** A picture the kid picked, as the main process re-encoded it (D8, D11: EXIF and GPS gone): kept by its hash until
     * a send takes it (an unsent one goes after a day, [prune]). */
    fun putPicture(jpeg: ByteArray, width: Int, height: Int): PictureRef {
        val sha = files.put(jpeg)
        staged[sha] = clock()
        return PictureRef(sha, width, height)
    }

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

    /** The parts of an outgoing picture message: our own JPEGs, available from the start (no longer a pick). */
    internal fun outgoingParts(pictures: List<PictureRef>): List<Part> = pictures.mapIndexed { i, p ->
        staged.remove(p.sha256)
        Part(i, MIME_JPEG, files.size(p.sha256) ?: 0, p.sha256, p.width, p.height, PartState.AVAILABLE)
    }

    internal fun pictureSize(ref: PictureRef): Long? = files.size(ref.sha256)

    // ---- the picture files ----

    fun open(sha256: String): java.io.InputStream? = files.open(sha256)

    /** A picture file no part holds any more goes at once - of [candidates], the files a deleted or dropped message
     * held, or a parse stored (D18, §6.4). A pick the kid hasn't sent yet ([putPicture]) is never taken (code QA N8). */
    fun dropUnheld(candidates: Collection<String>) {
        if (candidates.isEmpty()) return
        val held = store.partHashes()
        candidates.filter { it !in held && !staged.containsKey(it) }.forEach(files::delete)
    }

    /** At each sync: any file no part holds that is older than [STAGED_KEEP_MS] - an unsent pick, or one a process death
     * left between the encode and the parse's answer. */
    fun prune() {
        val held = store.partHashes()
        val before = clock() - STAGED_KEEP_MS
        staged.entries.removeIf { it.value < before }
        for ((sha, modified) in files.all()) if (sha !in held && !staged.containsKey(sha) && modified < before) files.delete(sha)
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

        /** The message's failure when the fetched message couldn't be read or killed the parse: "Meldingen kunne ikke
         * vises" (D8; code QA N7), not "couldn't be fetched". */
        const val FAILURE_UNREADABLE = "mms_unreadable"

        /** Code QA L6: passes the parse service couldn't be reached in. */
        const val COUNT_PARSE_UNAVAILABLE = "mms_parse_unavailable"

        /** The `mms` row's failure for a parse that refused the bytes, and for one that died three times. */
        const val FAILURE_PARSE = "parse"
        const val FAILURE_CRASH = "crash"

        /** Code QA L2: a push's identity is kept until its own expiry, at least a day and at most this long - an
         * unanswered push the MMSC repeats until then adds no second row. */
        const val PUSH_KEY_KEEP_MS = 7 * 24 * 60 * 60 * 1000L

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

        private fun messageFailure(state: MmsState, failure: String?): String? = when {
            state != MmsState.FAILED -> null
            failure == MmsRow.RETRY -> FAILURE_RETRY
            failure == FAILURE_PARSE || failure == FAILURE_CRASH -> FAILURE_UNREADABLE
            else -> null
        }

        /** A received part's type as stored: lower case, `type/subtype` of token characters, else `application/octet-stream`. */
        fun cleanMime(raw: String): String {
            val mime = raw.substringBefore(';').trim().lowercase()
            return if (mime.length <= 64 && MIME.matches(mime)) mime else "application/octet-stream"
        }

        private val MIME = Regex("""[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*""")

        /** A push the isolated parse couldn't read: its identity is its bytes, hashed. */
        fun rawPushKey(pdu: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("mms-raw\u0000".toByteArray(Charsets.UTF_8))
            digest.update(pdu)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /** QA L5: a push's identity - (transaction id, content location), hashed. */
        fun pushKey(n: MmsNotification): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("mms\u0000${n.transactionId}\u0000${n.contentLocation}".toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
