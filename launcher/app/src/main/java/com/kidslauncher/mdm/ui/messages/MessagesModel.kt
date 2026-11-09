package com.kidslauncher.mdm.ui.messages

import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.sms.engine.Conversation
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.MessageEngine
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Part
import com.kidslauncher.mdm.sms.engine.PartState
import com.kidslauncher.mdm.sms.engine.Refusal
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.engine.SmsRules
import com.kidslauncher.mdm.timerules.TimeLock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * The kid's message screens as data (design 77 §6.7, step 2): Home's tile, the list, the thread's days, delivery lines
 * and pictures, what the compose bar may do, and the kid's deletes. Pure, no Android imports, tested in
 * MessagesModelTest. MessagesActivity, ThreadActivity and PictureActivity only render what these return; the engine's
 * gate (SmsRules, inside `send`) stays the authority - [composeState] only shows its answer before the kid types.
 */

/** Home's Meldinger tile (§6.7): while SMS is "Styrt av Vibb" with the role ours, and in every other state while there
 * is history to read - gone only while there is none. */
fun messagesTileShown(smsMode: ChannelMode?, roleHeld: Boolean, hasHistory: Boolean): Boolean =
    (smsMode == ChannelMode.MANAGED && roleHeld) || hasHistory

/** §6.7, "Under a time rule": the screens are reachable where the rule says calls are, as the phone book - the
 * used-up budget keeps calls, so it keeps Meldinger. `null` = no time policy. */
fun messagesUsableUnder(lock: TimeLock?): Boolean = lock == null || !lock.locked || lock.callsAllowed

/** What the thread's bottom shows instead of, or as, the compose bar. */
enum class ComposeState {
    /** The compose bar. */
    OPEN,

    /** SMS "Av" or "Ikke styrt": the history stays readable in Vibb One, nothing is sent from it (D6). */
    READ_ONLY,

    /** A time rule without calls: "Du kan ikke sende meldinger nå". */
    NOT_NOW,

    /** This conversation can't be answered: a sender name, a contact without "Kan få melding fra", a stranger let in
     * by the callback window. */
    NOT_ALLOWED,

    /** "Styrt av Vibb" but the rules can't be read or the role isn't ours yet: nothing can be sent right now. */
    UNAVAILABLE,

    /** A group with someone the kid may not text (D12): "Du kan bare svare når alle i samtalen er i telefonboka di". */
    GROUP_NOT_ALLOWED,
}

/**
 * The compose bar for a conversation with [targets] - its participants' addresses (a group has more than one, D12).
 * One participant is [composeState]'s; a group may be answered only when the gate lets every one of them be texted.
 */
fun composeState(judge: SmsJudge, targets: List<String>): ComposeState {
    if (targets.size <= 1) return composeState(judge, targets.singleOrNull())
    val each = targets.map { composeState(judge, it) }
    return when {
        each.all { it == ComposeState.OPEN } -> ComposeState.OPEN
        // The channel's or the time rule's answer is the same for all; only the people differ.
        each.any { it == ComposeState.NOT_ALLOWED } -> ComposeState.GROUP_NOT_ALLOWED
        else -> each.first { it != ComposeState.OPEN }
    }
}

/**
 * D11: the compose bar's "+" (a picture) - only while the bar is open and every recipient is a contact with "Bilder i
 * meldinger" on. The engine's `send` checks the same again; this only shows its answer.
 */
fun picturesAllowed(judge: SmsJudge, targets: List<String>): Boolean {
    if (targets.isEmpty() || composeState(judge, targets) != ComposeState.OPEN) return false
    val contacts = judge.rules?.contacts.orEmpty()
    return targets.all { target -> contacts.any { it.number == target && it.mms && it.smsOut } }
}

/**
 * The compose bar for a conversation with [target] (its one participant's address, or the number a new thread is for;
 * `null` for a group - step 3b - or none). The history is read-only while SMS isn't "Styrt av Vibb" (also for an
 * emergency number, which the gate would let through: no compose bar outside "Styrt av Vibb", §6.7). Otherwise the
 * gate's own answer ([SmsRules.decideSend], D5) as it stands now, with its reason.
 */
fun composeState(judge: SmsJudge, target: String?): ComposeState {
    when (judge.state.smsMode) {
        ChannelMode.OFF, ChannelMode.UNMANAGED -> return ComposeState.READ_ONLY
        null -> return ComposeState.UNAVAILABLE
        ChannelMode.MANAGED -> {}
    }
    if (!judge.roleHeld) return ComposeState.UNAVAILABLE
    if (target == null || target.startsWith(SmsEngine.NAME_PREFIX) || target.startsWith(SmsEngine.UNKNOWN_PREFIX)) {
        return ComposeState.NOT_ALLOWED
    }
    return when (val verdict = SmsRules.decideSend(target, judge)) {
        is SmsRules.SendVerdict.Allow -> ComposeState.OPEN
        is SmsRules.SendVerdict.Refused -> when (verdict.reason) {
            Refusal.TIME_RULE -> ComposeState.NOT_NOW
            Refusal.RULES_UNKNOWN -> ComposeState.UNAVAILABLE
            Refusal.SMS_OFF -> ComposeState.READ_ONLY
            else -> ComposeState.NOT_ALLOWED
        }
    }
}

/** What the thread does after the gate refused a send (code QA of step 2, L5: each refusal its own words). */
enum class AfterRefusal {
    NOT_NOW,
    NOT_ALLOWED,
    UNAVAILABLE,
    READ_ONLY,

    /** "Meldingen er for lang" - the field's own limit keeps this from happening. */
    TOO_LONG,

    /** The draft's id was taken by another text (an edit between two taps): a new id, sent again once, silently. */
    SEND_AGAIN,

    /** An empty text: nothing to say. */
    NOTHING,
}

fun afterRefusal(reason: Refusal): AfterRefusal = when (reason) {
    Refusal.TIME_RULE -> AfterRefusal.NOT_NOW
    Refusal.RULES_UNKNOWN -> AfterRefusal.UNAVAILABLE
    Refusal.SMS_OFF -> AfterRefusal.READ_ONLY
    Refusal.NOT_ALLOWED -> AfterRefusal.NOT_ALLOWED
    Refusal.TOO_LARGE -> AfterRefusal.TOO_LONG
    Refusal.DUPLICATE_CLIENT_ID -> AfterRefusal.SEND_AGAIN
    Refusal.EMPTY -> AfterRefusal.NOTHING
}

/**
 * Whether "Ny melding" shows: the gate would send to a contact on its list now. Judged on a contact that isn't an
 * emergency number (code QA N3: D5 lets those through even under a rule without calls), so the answer is the time
 * rule's and the role's, not rule 1's.
 */
fun mayStartNew(judge: SmsJudge, rules: CallRules?): Boolean {
    val contact = newMessageContacts(rules).firstOrNull { !judge.isEmergency(it.number) } ?: return false
    return composeState(judge, contact.number) == ComposeState.OPEN
}

/** The contact avatars' share of the messages (§6.7: the badge is missed calls plus unread messages): each one-to-one
 * conversation with a number and something unread, by that number - never a sender name, a group or `unknown:`. */
fun unreadByNumber(conversations: List<Conversation>): Map<String, Int> = conversations.mapNotNull { c ->
    val address = c.participants.singleOrNull()?.address ?: return@mapNotNull null
    if (c.unread <= 0 || address.startsWith(SmsEngine.NAME_PREFIX) || address.startsWith(SmsEngine.UNKNOWN_PREFIX)) null else address to c.unread
}.groupBy({ it.first }, { it.second }).mapValues { (_, counts) -> counts.sum() }

/** "Ny melding" (§6.7, D3): the contacts the kid may text, in the parent's order - text-only contacts included, only
 * those with "Kan få melding fra" (`sms_out`). No free number: a number outside the list never gets a thread here. */
fun newMessageContacts(rules: CallRules?): List<RuleContact> =
    rules?.contacts.orEmpty().filter { it.smsOut && it.number.isNotEmpty() }

/** The one-to-one conversation of a (normalised) [number], if there is one. */
fun conversationOf(number: String, conversations: List<Conversation>): Conversation? =
    conversations.firstOrNull { it.participants.singleOrNull()?.address == number }

/** One row of the list: the conversation, its newest message (`null` = none read), and who it is with. */
data class ListRow(
    val conversation: Conversation,
    val last: Message?,
    val who: Who,
    /** The phone-book contact now (photo, avatar colour), if the conversation is with one. */
    val contact: RuleContact?,
)

/** Who a conversation is with, as the kid reads it. */
sealed interface Who {
    /** A contact's name (the current one, else the name it last had - D18). */
    data class Name(val name: String) : Who

    /** A sender name ("DNB"). */
    data class Sender(val name: String) : Who

    /** A number with no name. */
    data class Number(val number: String) : Who

    /** No usable sender (QA L3's `unknown:` conversation). */
    data object Unknown : Who

    /** A group (D12): each participant as the kid reads them, in the conversation's order. */
    data class Group(val members: List<Who>) : Who
}

/** The title a row shows for [who] ([unknown]: the words for no usable sender); a group lists its members. */
fun whoTitle(who: Who, unknown: String): String = when (who) {
    is Who.Name -> who.name
    is Who.Sender -> who.name
    is Who.Number -> who.number
    Who.Unknown -> unknown
    is Who.Group -> who.members.joinToString(", ") { whoTitle(it, unknown) }
}

/**
 * Meldinger's search (design 79 §4.1): the rows whose **shown title** matches - never a message's text (the user: "bare
 * kontakt navn"); a conversation with a bare number shows digits, so digits match there (QA N5). The phone book's rank
 * and fold ([searchHits]); [titleOf] is [whoTitle] with the screen's words.
 */
fun searchConversations(rows: List<ListRow>, query: String, collator: java.text.Collator, titleOf: (Who) -> String): List<Pair<ListRow, com.kidslauncher.mdm.ui.search.SearchMatch>> =
    com.kidslauncher.mdm.ui.search.searchHits(rows, { titleOf(it.who) }, query, collator)

/** One address as the kid reads it: the contact's name now, else the number (a group's member, a bubble's sender). */
fun whoOfAddress(address: String, rules: CallRules?): Who =
    rules?.contacts?.firstOrNull { it.number == address }?.name?.takeIf { it.isNotBlank() }?.let { Who.Name(it) } ?: Who.Number(address)

/** [conversation]'s name: the policy contact's current name, else the stored one (a removed contact keeps it, D18),
 * else the sender name or the number; a group lists its members. */
fun whoOf(conversation: Conversation, rules: CallRules?): Who {
    if (conversation.participants.size > 1) return Who.Group(conversation.participants.map { whoOfAddress(it.address, rules) })
    val one = conversation.participants.singleOrNull()
    val contact = contactOf(conversation, rules)
    contact?.name?.takeIf { it.isNotBlank() }?.let { return Who.Name(it) }
    val address = one?.address.orEmpty()
    return when {
        address.startsWith(SmsEngine.NAME_PREFIX) -> Who.Sender(conversation.displayName ?: address.removePrefix(SmsEngine.NAME_PREFIX))
        address.startsWith(SmsEngine.UNKNOWN_PREFIX) -> Who.Unknown
        !conversation.displayName.isNullOrBlank() -> Who.Name(conversation.displayName)
        address.isNotEmpty() -> Who.Number(address)
        else -> Who.Unknown
    }
}

/** The phone-book contact a one-to-one conversation is with now (by its id, else its number). */
fun contactOf(conversation: Conversation, rules: CallRules?): RuleContact? {
    val one = conversation.participants.singleOrNull() ?: return null
    val contacts = rules?.contacts.orEmpty()
    return one.contactId?.let { id -> contacts.firstOrNull { it.id == id } } ?: contacts.firstOrNull { it.number == one.address }
}

/** The list, newest first (by the engine's `seq`, never the SMSC's time); a conversation with nothing left is gone. */
fun listRows(entries: List<Pair<Conversation, Message?>>, rules: CallRules?): List<ListRow> =
    entries.filter { it.second != null }
        .sortedByDescending { it.first.lastSeq }
        .map { (conversation, last) -> ListRow(conversation, last, whoOf(conversation, rules), contactOf(conversation, rules)) }

/** A list row's last line. */
sealed interface LastLine {
    data class Text(val text: String, val mine: Boolean) : LastLine

    /** A picture without text: "Bilde". */
    data class Picture(val mine: Boolean) : LastLine

    data object Empty : LastLine
}

fun lastLine(message: Message?): LastLine {
    if (message == null) return LastLine.Empty
    val mine = message.direction == Direction.OUT
    val text = message.body.orEmpty().replace(Regex("\\s+"), " ").trim()
    return when {
        text.isNotEmpty() -> LastLine.Text(text, mine)
        message.parts.any { it.mime.startsWith("image/") } -> LastLine.Picture(mine)
        else -> LastLine.Empty
    }
}

/** The list's time: the clock today, "I går" yesterday, the weekday within the week, else the date. */
sealed interface TimeLabel {
    data class Clock(val ms: Long) : TimeLabel

    data object Yesterday : TimeLabel

    data class Weekday(val day: DayOfWeek) : TimeLabel

    data class Date(val date: LocalDate, val withYear: Boolean) : TimeLabel
}

/** [at] against [now] in [zone]. A time after today (the clock set back) reads as today's clock. */
fun listTimeLabel(at: Long, now: Long, zone: ZoneId): TimeLabel {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val ago = ChronoUnit.DAYS.between(day, today)
    return when {
        ago <= 0 -> TimeLabel.Clock(at)
        ago == 1L -> TimeLabel.Yesterday
        ago < 7 -> TimeLabel.Weekday(day.dayOfWeek)
        else -> TimeLabel.Date(day, withYear = day.year != today.year)
    }
}

/** A day separator in the thread. */
sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class Day(val date: LocalDate) : DayLabel
}

fun dayLabel(day: LocalDate, today: LocalDate): DayLabel = when (ChronoUnit.DAYS.between(day, today)) {
    0L -> DayLabel.Today
    1L -> DayLabel.Yesterday
    else -> DayLabel.Day(day)
}

/** What under a message of the kid's says how it went (§6.7). */
enum class DeliveryLine {
    SENDING,
    SENT,
    DELIVERED,

    /** "Ikke sendt - trykk for å prøve igjen". */
    NOT_SENT,

    /** `UNCERTAIN` (no answer from the platform in 10 minutes): it may or may not have gone - tap to send again. */
    MAYBE_NOT_SENT,
}

/** Whether a failed or uncertain message invites a tap: only where the kid could send now (code QA L5) - in "Av",
 * "Ikke styrt", under a rule or to a contact no longer allowed it says "Ikke sendt" and nothing more. */
fun retryOffered(line: DeliveryLine?, compose: ComposeState): Boolean =
    (line == DeliveryLine.NOT_SENT || line == DeliveryLine.MAYBE_NOT_SENT) && compose == ComposeState.OPEN

/** "Sendt"/"Levert"/"Sender…" under the kid's last message only; a failed or uncertain one always says so, wherever it
 * is - a quiet failure higher up would read as sent. */
fun deliveryLine(message: Message, lastOut: Boolean): DeliveryLine? {
    if (message.direction != Direction.OUT) return null
    return when (message.delivery) {
        Delivery.FAILED -> DeliveryLine.NOT_SENT
        Delivery.UNCERTAIN -> DeliveryLine.MAYBE_NOT_SENT
        else -> if (!lastOut) {
            null
        } else {
            when (message.delivery) {
                Delivery.QUEUED, Delivery.SENDING -> DeliveryLine.SENDING
                Delivery.SENT -> DeliveryLine.SENT
                Delivery.DELIVERED -> DeliveryLine.DELIVERED
                else -> null
            }
        }
    }
}

/** A line of the thread: a day separator or a message. */
sealed interface ThreadItem {
    data class Day(val label: DayLabel, val date: LocalDate) : ThreadItem

    data class Bubble(val message: Message, val delivery: DeliveryLine?) : ThreadItem
}

/** The thread from [messages] (any order): oldest first by `seq`, a separator before each new day of the engine's time
 * in [zone], the delivery line by [deliveryLine]. */
fun threadItems(messages: List<Message>, now: Long, zone: ZoneId): List<ThreadItem> {
    val sorted = messages.sortedBy { it.seq }
    val lastOut = sorted.lastOrNull { it.direction == Direction.OUT }?.id
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val items = mutableListOf<ThreadItem>()
    var shownDay: LocalDate? = null
    for (m in sorted) {
        val day = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
        if (day != shownDay) {
            items += ThreadItem.Day(dayLabel(day, today), day)
            shownDay = day
        }
        items += ThreadItem.Bubble(m, deliveryLine(m, m.id == lastOut))
    }
    return items
}

/** How a part shows in its bubble (§6.7, D11, D14; an SMS has none). */
sealed interface PartView {
    /** Our own JPEG, ready: a rounded thumbnail (its shape from [width] and [height]), a tap opens it. */
    data class Picture(val index: Int, val sha256: String?, val width: Int?, val height: Int?) : PartView

    /** Not fetched yet: "Et bilde er på vei". */
    data object Waiting : PartView

    /** D14: waiting for MMS data - "Bilde venter - trenger mobildata". */
    data object WaitingData : PartView

    /** Pictures are off with this contact: "Et bilde ble ikke vist". */
    data object NotShown : PartView

    /** Couldn't be fetched or decoded, or too large: "Bildet kunne ikke hentes" - with [retry] (D14: five tries ran out)
     * "- trykk for å prøve igjen", and a tap fetches it again. */
    data class Failed(val retry: Boolean = false) : PartView

    /** The carrier's server no longer has it (D16): "Bildet er ikke lenger tilgjengelig". */
    data object Gone : PartView

    /** The fetched message couldn't be read, or killed the parse (D8; code QA N7): "Meldingen kunne ikke vises". */
    data object Unreadable : PartView

    /** Video, audio, anything else: "Vedlegg som ikke kan vises"; its bytes aren't kept. */
    data object Unsupported : PartView
}

/** [retry]: the message's fetch offers the kid's tap ([MmsFlow.FAILURE_RETRY] on the message); [unreadable]: the
 * fetched message couldn't be read ([MmsFlow.FAILURE_UNREADABLE]). */
fun partViews(parts: List<Part>, retry: Boolean = false, unreadable: Boolean = false): List<PartView> = parts.sortedBy { it.index }.mapNotNull { part ->
    when {
        unreadable && part.state == PartState.FAILED -> PartView.Unreadable
        part.mime == "text/plain" -> null
        part.state == PartState.NOT_SHOWN -> PartView.NotShown
        !part.mime.startsWith("image/") -> PartView.Unsupported
        part.state == PartState.AVAILABLE -> PartView.Picture(part.index, part.sha256, part.width, part.height)
        part.state == PartState.PENDING -> PartView.Waiting
        part.state == PartState.WAITING_DATA -> PartView.WaitingData
        part.state == PartState.GONE -> PartView.Gone
        part.state == PartState.FAILED -> PartView.Failed(retry)
        else -> PartView.Failed()
    }
}

/** Whether a message's failed fetch offers "Trykk for å prøve igjen" (D14). */
fun fetchRetryOffered(message: Message): Boolean =
    message.direction == Direction.IN && message.failure == com.kidslauncher.mdm.sms.engine.MmsFlow.FAILURE_RETRY

/** Whether a received MMS couldn't be read (code QA N7). */
fun fetchUnreadable(message: Message): Boolean =
    message.direction == Direction.IN && message.failure == com.kidslauncher.mdm.sms.engine.MmsFlow.FAILURE_UNREADABLE

/** A picture's thumbnail shape (height over width) from its stored size, kept between a wide and a tall bound; 3:4
 * when the size isn't known. */
fun pictureRatio(width: Int?, height: Int?): Float =
    if (width != null && height != null && width > 0 && height > 0) (height.toFloat() / width).coerceIn(0.4f, 1.6f) else 0.75f

/** An unsent draft's key: the conversation's id, else the number a new thread is for. */
fun draftKeyOf(conversationId: String?, number: String?): String = conversationId ?: "number:${number.orEmpty()}"

/** The draft a thread shows when it opens (verification L8): under the conversation's key, else under the number's - a
 * thread opened by number moves its draft to the conversation once it learns the id. Text and `clientId`. */
fun keptDraft(drafts: Map<String, Pair<String, String>>, conversationId: String?, number: String?): Pair<String, String>? =
    conversationId?.let { drafts[it] } ?: number?.let { drafts[draftKeyOf(null, it)] }

/** What the kid asked to delete, waiting for the in-screen confirm (D18: one confirm, no trash, no undo). */
sealed interface DeleteTarget {
    data class OneMessage(val id: MessageId) : DeleteTarget

    data class WholeConversation(val id: com.kidslauncher.mdm.sms.engine.ConversationId) : DeleteTarget
}

/** The kid's actions through the engine's seam (origin [Origin.KID_SCREEN]); run on the engine thread. */
object MessageActions {
    /** D18, after the confirm: the rows go from the store, `MessageDeleted` for each. */
    fun delete(engine: MessageEngine, target: DeleteTarget) {
        when (target) {
            is DeleteTarget.OneMessage -> engine.delete(target.id, Origin.KID_SCREEN)
            is DeleteTarget.WholeConversation -> engine.deleteConversation(target.id, Origin.KID_SCREEN)
        }
    }

    /**
     * "Prøv igjen" on a failed (or uncertain) message: the same text again through [send] (the gate again), under an id
     * of its own derived from the failed message ([retryClientId]; §6.2: never the failed one's - at most once). Two
     * taps on the same failed bubble are the engine's repeat and send once (code QA of step 2, M1); a retry of the
     * retry, if that fails too, is a new message with a new id. Once accepted the failed message goes, so the thread
     * holds the text once; refused, it stays as it was.
     */
    fun retry(send: (SendCommand) -> SendResult?, delete: (MessageId) -> Unit, failed: Message): SendResult? {
        if (failed.direction != Direction.OUT || failed.delivery !in RETRYABLE) return null
        val result = send(SendCommand(retryClientId(failed.id), failed.conversationId, null, failed.body.orEmpty(), origin = Origin.KID_SCREEN))
        if (result is SendResult.Accepted && result.message.id != failed.id) delete(failed.id)
        return result
    }

    fun retryClientId(failed: MessageId): String = "retry:" + failed.value

    val RETRYABLE = setOf(Delivery.FAILED, Delivery.UNCERTAIN)
}

/**
 * One reload at a time, and one more for everything asked meanwhile (code QA of step 2, L2): a burst of engine events
 * is at most two reads, never one per event. [start] runs the read and calls its `done` when the answer is shown.
 * Main thread only.
 */
class Coalescer(private val start: (done: () -> Unit) -> Unit) {
    private var running = false
    private var again = false

    fun request() {
        if (running) {
            again = true
            return
        }
        running = true
        start(::finished)
    }

    private fun finished() {
        running = false
        if (again) {
            again = false
            request()
        }
    }
}

/**
 * "Del" (§6.7, code QA of step 2, L6): the share sheet's targets the parent didn't allow, to leave out
 * (`EXTRA_EXCLUDE_COMPONENTS`) - every resolved component ([resolved]: package to component name) whose package
 * isn't [usable] (installed, not suspended, on the allowlist), and the system's nearby-share component ([nearby]) unless
 * its package is. On an unmanaged phone ([usable] always true) nothing is left out.
 */
fun shareExclusions(resolved: List<Pair<String, String>>, nearby: Pair<String, String>?, usable: (String) -> Boolean): List<String> =
    (resolved + listOfNotNull(nearby)).filter { (pkg, _) -> !usable(pkg) }.map { it.second }.distinct()

/**
 * The draft an `smsto:` brings (`ComposeActivity`, code QA N1): the `sms_body` extra, else `EXTRA_TEXT`, else the
 * link's own `?body=` read from the *encoded* scheme-specific part and decoded once - so "&" in a body survives as
 * "%26". Cut to [max].
 */
fun smsDraft(smsBody: String?, extraText: String?, encodedSsp: String?, max: Int): String? {
    val fromLink = encodedSsp?.substringAfter('?', "")?.split('&')?.firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.let {
        runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()
    }
    return (smsBody ?: extraText ?: fromLink)?.take(max)?.takeIf { it.isNotEmpty() }
}

/**
 * Code QA M3: whether the photo picker's answer may be read - a `content://` URI whose authority isn't one of ours
 * (`<package>` and `<package>.*`: the share provider, the MMS provider). A `file://` URI, or one into our own providers,
 * would have the launcher read its own private files on the answering activity's say-so.
 */
fun pickedUriAcceptable(scheme: String?, authority: String?, ownPackage: String): Boolean {
    if (scheme?.lowercase() != "content" || authority.isNullOrEmpty()) return false
    // A user-qualified authority (`10@media`) is judged by its provider part.
    val owner = authority.substringAfterLast('@').lowercase()
    val own = ownPackage.lowercase()
    return owner != own && !owner.startsWith("$own.")
}
