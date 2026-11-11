package com.kidslauncher.mdm.ui.messages

import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.sms.engine.Conversation
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.EmojiGroup
import com.kidslauncher.mdm.sms.engine.EmojiList
import com.kidslauncher.mdm.sms.engine.Reaction
import com.kidslauncher.mdm.sms.engine.Reactions
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.MessageEngine
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Part
import com.kidslauncher.mdm.sms.engine.PartState
import com.kidslauncher.mdm.sms.engine.PictureRef
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

    /** Design 80 D13: "Gruppemeldinger" is off - a group conversation is read-only: "Gruppemeldinger er slått av". */
    GROUPS_OFF,
}

/**
 * The compose bar for a conversation with [targets] - its participants' addresses (a group has more than one, D12).
 * One participant is [composeState]'s; a group may be answered only when the gate lets every one of them be texted.
 */
fun composeState(judge: SmsJudge, targets: List<String>): ComposeState {
    if (targets.size <= 1) return composeState(judge, targets.singleOrNull())
    val each = targets.map { composeState(judge, it) }
    // Design 80 D13: with group texts off, a group says so - unless the channel itself is closed or unreadable.
    if (judge.rules?.smsGroups == false && each.none { it == ComposeState.READ_ONLY || it == ComposeState.UNAVAILABLE }) {
        return ComposeState.GROUPS_OFF
    }
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

    /** Design 80 D13: "Gruppemeldinger er slått av". */
    GROUPS_OFF,

    /** A failed picture's "Prøv igjen" that can't send the picture again ([afterRetryRefusal]): "Dette bildet kan ikke
     * sendes". */
    PICTURE,
}

fun afterRefusal(reason: Refusal): AfterRefusal = when (reason) {
    Refusal.TIME_RULE -> AfterRefusal.NOT_NOW
    Refusal.RULES_UNKNOWN -> AfterRefusal.UNAVAILABLE
    Refusal.SMS_OFF -> AfterRefusal.READ_ONLY
    Refusal.NOT_ALLOWED -> AfterRefusal.NOT_ALLOWED
    Refusal.TOO_LARGE -> AfterRefusal.TOO_LONG
    Refusal.DUPLICATE_CLIENT_ID -> AfterRefusal.SEND_AGAIN
    Refusal.EMPTY -> AfterRefusal.NOTHING
    Refusal.GROUPS_OFF -> AfterRefusal.GROUPS_OFF
}

/**
 * After a refused "Prøv igjen" of [failed] ([MessageActions.retry]). The link shows only where the text could go now
 * ([retryOffered]), so a picture message the gate still won't take as it is - "Bilder i meldinger" went off, the
 * picture's file is gone, or it is over the carrier's maximum now - says the picture can't be sent, never "Du kan ikke
 * svare her" or "Meldingen er for lang". Every other refusal has the send's own words ([afterRefusal]).
 */
fun afterRetryRefusal(failed: Message, reason: Refusal): AfterRefusal =
    if (failed.parts.isNotEmpty() && (reason == Refusal.NOT_ALLOWED || reason == Refusal.TOO_LARGE)) AfterRefusal.PICTURE else afterRefusal(reason)

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

    /** [timed]: the message's clock time stands under the bubble ([bubbleTimed]) - after [delivery] when it has one
     * ("Levert · 10:47"). */
    data class Bubble(val message: Message, val delivery: DeliveryLine?, val timed: Boolean) : ThreadItem
}

/** The longest a message may come after the one before it and still go on its run. */
const val RUN_GAP_MS = 5 * 60_000L

/** Whether [next] goes on [message]'s run (2026-11-10, the user: the time once per run, under its last bubble): the same
 * side, the same day of the engine's time in [zone] (a day separator ends a run), and at most [RUN_GAP_MS] after it -
 * a message timed before the one it follows (the clock was set back) begins a new run, so both times show. */
fun sameRun(message: Message, next: Message, zone: ZoneId): Boolean {
    fun day(m: Message) = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
    return message.direction == next.direction && next.at - message.at in 0..RUN_GAP_MS && day(message) == day(next)
}

/** Whether a bubble's time stands under it: the last of its run ([endsRun]). A failed or uncertain send's line stays
 * its own, wherever the bubble is - it is the tap to try again, and nothing was sent at that time. */
fun bubbleTimed(endsRun: Boolean, delivery: DeliveryLine?): Boolean =
    endsRun && delivery != DeliveryLine.NOT_SENT && delivery != DeliveryLine.MAYBE_NOT_SENT

/** The thread from [messages] (any order): oldest first by `seq`, a separator before each new day of the engine's time
 * in [zone], the delivery line by [deliveryLine], the time under each run's last bubble ([sameRun], [bubbleTimed]). */
fun threadItems(messages: List<Message>, now: Long, zone: ZoneId): List<ThreadItem> {
    val sorted = messages.sortedBy { it.seq }
    val lastOut = sorted.lastOrNull { it.direction == Direction.OUT }?.id
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val items = mutableListOf<ThreadItem>()
    var shownDay: LocalDate? = null
    for ((index, m) in sorted.withIndex()) {
        val day = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
        if (day != shownDay) {
            items += ThreadItem.Day(dayLabel(day, today), day)
            shownDay = day
        }
        val delivery = deliveryLine(m, m.id == lastOut)
        val endsRun = sorted.getOrNull(index + 1)?.let { !sameRun(m, it, zone) } ?: true
        items += ThreadItem.Bubble(m, delivery, bubbleTimed(endsRun, delivery))
    }
    return items
}

/**
 * Whether the thread follows its newest message once [messages] are shown (the user, 2026-11-10): it did already - the
 * list was at its end ([threadAtEnd]) and stays there through every change - or the kid just sent one of them, an OUT
 * message newer than [newestBefore], the newest `seq` shown so far (a reaction is never among them). A message that
 * comes in, or a delivery line that changes, while the kid reads older ones moves nothing.
 */
fun threadFollows(following: Boolean, newestBefore: Long, messages: List<Message>): Boolean =
    following || messages.any { it.direction == Direction.OUT && it.seq > newestBefore }

/** Whether a read of the thread marks it read: in front ([resumed]) and following. A message that comes in while the
 * kid reads older ones is not shown, so it stays unread - Home's badge, the list's dot - until the end is reached. */
fun threadReads(resumed: Boolean, following: Boolean): Boolean = resumed && following

/** What the thread's "new message" mark says ([threadNewMark]): nothing, "Ny melding" or "Nye meldinger". */
enum class NewMark { NONE, ONE, MANY }

/**
 * The thread's "new message" mark (the user, 2026-11-10): while the kid reads older messages - the thread doesn't
 * follow - it tells of the messages that came in after [seenSeq], the newest `seq` the list's end was last shown with;
 * one, or more than one (no count). Never the kid's own message (a send goes to the end, [threadFollows]) and never a
 * reaction alone: it has no bubble, so no `seq` among [messages], and changes none.
 */
fun threadNewMark(following: Boolean, seenSeq: Long, messages: List<Message>): NewMark = when {
    following -> NewMark.NONE
    else -> when (messages.count { it.direction == Direction.IN && it.seq > seenSeq }) {
        0 -> NewMark.NONE
        1 -> NewMark.ONE
        else -> NewMark.MANY
    }
}

/** Whether the thread's list is at its end: the last bubble's bottom ([lastBottom], `null` while that bubble isn't laid
 * out) is at most [slop] below the list's visible end [listEnd]. A tall bubble (a picture) counts as shown only when its
 * bottom is. */
fun threadAtEnd(lastBottom: Int?, listEnd: Int, slop: Int): Boolean = lastBottom != null && lastBottom - listEnd <= slop

/** How far a thread that follows scrolls on after a layout - a new bubble, one that grew, the keyboard, the compose
 * bar's height - to show the last bubble's bottom again; 0 for a thread the kid scrolled up in. */
fun threadEndGap(following: Boolean, lastBottom: Int?, listEnd: Int): Int =
    if (following && lastBottom != null) (lastBottom - listEnd).coerceAtLeast(0) else 0

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

// ---- Design 81: reactions ----

/** One emoji of a bubble's chip (D6): [drawable] false when the phone's font can't draw it (a neutral dot shows then,
 * never an empty box); [mine] the kid's own (the peach outline), [pending] until it is sent (dimmed). */
data class ChipEmoji(val emoji: String, val drawable: Boolean, val mine: Boolean, val pending: Boolean)

/** D6 (N1): up to [CHIP_EMOJI] different emoji in the order they came, and [more] further different ones ("+N"). */
data class ReactionChip(val emoji: List<ChipEmoji>, val more: Int)

const val CHIP_EMOJI = 3

/** What the chip shows for an emoji the font can't draw. */
const val MISSING_GLYPH = "•"

/** The chip of a bubble with [reactions] ([Message.reactions]); `null` without any. Equal emoji (by key) are one. */
fun reactionChip(reactions: List<Reaction>, drawable: (String) -> Boolean): ReactionChip? {
    if (reactions.isEmpty()) return null
    val byKey = reactions.groupBy { it.key }
    val shown = byKey.values.take(CHIP_EMOJI).map { same ->
        val mine = same.firstOrNull { it.by == null }
        ChipEmoji(same.first().emoji, drawable(same.first().emoji), mine != null, mine?.pending == true)
    }
    return ReactionChip(shown, byKey.size - shown.size)
}

/** D6 (N2): what TalkBack reads of the reactions, one per person - [item] of the emoji ([unknown] for one the font can't
 * draw) and who ([who] of the reactor's address, `null` for the kid). The bubble's description joins them. */
fun reactionWords(
    reactions: List<Reaction>,
    drawable: (String) -> Boolean,
    unknown: String,
    who: (String?) -> String,
    item: (String, String) -> String,
): List<String> = reactions.map { item(if (drawable(it.emoji)) it.emoji else unknown, who(it.by)) }

/** A cell of the long-press panel's grid (D7). */
sealed interface ReactCell {
    /** An emoji; [current]: the kid's reaction now (outlined in peach - a tap takes it away, another replaces it). */
    data class Pick(val emoji: String, val current: Boolean) : ReactCell

    /** "+": every emoji (D13). */
    data object More : ReactCell

    data object Empty : ReactCell
}

/** What a long press opens (D7): [cells] - the 4 x 2 grid, or `null` when the kid can't react there - and "Fjern
 * reaksjonene" ([removeOthers]). "Slett" is always under them. */
data class ReactPanel(val cells: List<ReactCell>?, val removeOthers: Boolean)

/**
 * D7: the panel for a long press on [message]. The grid only where the compose bar would send now ([compose] open:
 * SMS "Styrt av Vibb", the gate's yes, no time rule, group texts on) and only on a received bubble with text or a
 * picture that is there - the six, "+" and the kid's own other reaction as the eighth cell. "Fjern reaksjonene" when
 * someone else reacted, wherever the thread is (the read-only history too). `null`: nothing but "Slett" - straight to
 * the delete confirm, as before.
 */
fun reactPanel(message: Message, compose: ComposeState): ReactPanel? {
    val reactable = message.direction == Direction.IN && compose == ComposeState.OPEN &&
        (!message.body.isNullOrBlank() || message.parts.any { it.mime.startsWith("image/") && it.state == PartState.AVAILABLE })
    val others = message.reactions.any { it.by != null }
    if (!reactable && !others) return null
    val mine = message.reactions.firstOrNull { it.by == null }
    val cells = if (!reactable) {
        null
    } else {
        val six = Reactions.SIX.map { ReactCell.Pick(it, mine?.key == EmojiList.key(it)) }
        val eighth = mine?.takeIf { six.none { it.current } }?.let { ReactCell.Pick(it.emoji, current = true) } ?: ReactCell.Empty
        six + ReactCell.More + eighth
    }
    return ReactPanel(cells, others)
}

/** A line of the picker (D13): a group's heading ([group] Unicode's name, `null` for "Nylig brukt") or one emoji. */
sealed interface PickerItem {
    data class Heading(val group: String?) : PickerItem

    data class Emoji(val emoji: String) : PickerItem
}

/** D13: "Nylig brukt" holds at most this many. */
const val RECENT_MAX = 5

/** D13: the picker - "Nylig brukt" first, then each group with what the font can draw ([drawable]; a group left empty
 * goes). [groups] come from the pinned list: fully-qualified, no skin tones, no components. */
fun pickerItems(groups: List<EmojiGroup>, recent: List<String>, drawable: (String) -> Boolean): List<PickerItem> {
    val items = mutableListOf<PickerItem>()
    val recents = recent.filter(drawable).take(RECENT_MAX)
    if (recents.isNotEmpty()) {
        items += PickerItem.Heading(null)
        recents.mapTo(items) { PickerItem.Emoji(it) }
    }
    for (group in groups) {
        val shown = group.emoji.filter(drawable)
        if (shown.isEmpty()) continue
        items += PickerItem.Heading(group.name)
        shown.mapTo(items) { PickerItem.Emoji(it) }
    }
    return items
}

/** "Nylig brukt" after the picker's [picked] was sent with [op] (code QA N6): only an add the engine [accepted] moves
 * it to the front - a refused send or a removal leaves the row as it was. */
fun recentAfterSend(recent: List<String>, picked: String, op: com.kidslauncher.mdm.sms.engine.ReactionOp, accepted: Boolean): List<String> =
    if (accepted && op == com.kidslauncher.mdm.sms.engine.ReactionOp.ADD) recentAfter(recent, picked) else recent

/** "Nylig brukt" after [picked] from the picker: newest first, at most [RECENT_MAX], never one of the six (the panel
 * always has them). */
fun recentAfter(recent: List<String>, picked: String): List<String> {
    val key = EmojiList.key(picked)
    if (Reactions.SIX.any { EmojiList.key(it) == key }) return recent.take(RECENT_MAX)
    return (listOf(picked) + recent.filter { EmojiList.key(it) != key }).take(RECENT_MAX)
}

/** A picture's thumbnail shape (height over width) from its stored size, kept between a wide and a tall bound; 3:4
 * when the size isn't known. */
fun pictureRatio(width: Int?, height: Int?): Float =
    if (width != null && height != null && width > 0 && height > 0) (height.toFloat() / width).coerceIn(0.4f, 1.6f) else 0.75f

/** An unsent draft's key: the conversation's id, else the number a new thread is for. */
fun draftKeyOf(conversationId: String?, number: String?): String = conversationId ?: "number:${number.orEmpty()}"

/** The draft a thread shows when it opens (verification L8): under the conversation's key, else under the number's - a
 * thread opened by number moves its draft to the conversation once it learns the id. */
fun keptDraft(drafts: Map<String, KeptText>, conversationId: String?, number: String?): KeptText? =
    conversationId?.let { drafts[it] } ?: number?.let { drafts[draftKeyOf(null, it)] }

/**
 * A thread's unsent text as the phone keeps it (design 78 §5.4.4): a change of look closes Meldinger's task, and a
 * process may die while the kid is elsewhere, so the text outlives both - the words, the draft's `clientId` (a send
 * after a restart still goes once, §6.2) and when it was kept, which decides the oldest.
 */
data class KeptText(val text: String, val clientId: String, val atMs: Long)

/** At most this many characters are kept of one text: the engine's longest send (`SmsEngine.SEND_MAX`). */
const val KEPT_TEXT_MAX = 1_600

/** At most this many conversations keep a text; past it the oldest goes. */
const val KEPT_CONVERSATIONS_MAX = 50

/**
 * [kept] after the thread [key]'s field held [text]: an empty or blank field (sent, or emptied) keeps nothing; a text is
 * kept to its first [KEPT_TEXT_MAX] characters (a surrogate pair never cut in two), newest at [nowMs], and past
 * [KEPT_CONVERSATIONS_MAX] the oldest other conversation's text goes.
 */
fun keepText(kept: Map<String, KeptText>, key: String, text: String, clientId: String, nowMs: Long): Map<String, KeptText> {
    if (text.isBlank()) return kept - key
    val cut = if (text.length <= KEPT_TEXT_MAX) text else text.substring(0, if (text[KEPT_TEXT_MAX - 1].isHighSurrogate()) KEPT_TEXT_MAX - 1 else KEPT_TEXT_MAX)
    val next = LinkedHashMap(kept - key)
    next[key] = KeptText(cut, clientId, nowMs)
    while (next.size > KEPT_CONVERSATIONS_MAX) {
        next.remove(next.entries.filter { it.key != key }.minBy { it.value.atMs }.key)
    }
    return next
}

/** Whether a thread's kept text goes because the kid may no longer text there - its contact, the group, or SMS as a
 * whole ("Av", "Ikke styrt": the phone unmanaged). Not for a time rule or rules that can't be read now: those pass. */
fun keptTextDropped(state: ComposeState): Boolean = when (state) {
    ComposeState.READ_ONLY, ComposeState.NOT_ALLOWED, ComposeState.GROUP_NOT_ALLOWED, ComposeState.GROUPS_OFF -> true
    ComposeState.OPEN, ComposeState.NOT_NOW, ComposeState.UNAVAILABLE -> false
}

/** Whether any kept text may stay at all under the SMS channel's [smsMode]: not when SMS is off or the phone unmanaged
 * (nobody may be texted from Vibb One then); unknown rules (`null`) keep them. */
fun keptTextsStay(smsMode: ChannelMode?): Boolean = smsMode != ChannelMode.OFF && smsMode != ChannelMode.UNMANAGED

/** One kept text as one stored string: when, the id, then the words (which may hold line breaks). */
fun encodeKeptText(kept: KeptText): String = "${kept.atMs}\n${kept.clientId}\n${kept.text}"

/** [encodeKeptText] read back; anything else (an older or broken value) is `null` and is dropped. */
fun decodeKeptText(stored: String?): KeptText? {
    val parts = stored?.split('\n', limit = 3)?.takeIf { it.size == 3 } ?: return null
    val at = parts[0].toLongOrNull() ?: return null
    if (parts[1].isEmpty() || parts[2].isBlank()) return null
    return KeptText(parts[2], parts[1], at)
}

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
     * "Prøv igjen" on a failed (or uncertain) message: the same content again - its text and its pictures, as the first
     * send carried them - through [send] (the gate again), under an id of its own derived from the failed message
     * ([retryClientId]; §6.2: never the failed one's - at most once). Two taps on the same failed bubble are the
     * engine's repeat and send once (code QA of step 2, M1); a retry of the retry, if that fails too, is a new message
     * with a new id. The failed message goes only once the engine accepted a message that [carries] all of it, so the
     * thread holds it once; refused, it stays as it was.
     *
     * The pictures are the failed message's own parts: the engine keeps an outgoing picture's file for as long as a part
     * names its hash, so the bytes are there while the failed message is. A part that can't be sent again (no hash, not
     * available) is a refusal here, as a file the engine no longer holds is one there ([Refusal.NOT_ALLOWED], the
     * engine's answer for a picture it doesn't hold): nothing is sent - never the text without its picture - and
     * nothing is deleted.
     */
    fun retry(send: (SendCommand) -> SendResult?, delete: (MessageId) -> Unit, failed: Message): SendResult? {
        if (failed.direction != Direction.OUT || failed.delivery !in RETRYABLE) return null
        val pictures = failed.parts.sortedBy { it.index }.map { part ->
            val sha = part.sha256?.takeIf { part.state == PartState.AVAILABLE } ?: return SendResult.Refused(Refusal.NOT_ALLOWED)
            PictureRef(sha, part.width, part.height)
        }
        val result = send(SendCommand(retryClientId(failed.id), failed.conversationId, null, failed.body.orEmpty(), pictures, Origin.KID_SCREEN))
        if (result is SendResult.Accepted && result.message.id != failed.id && carries(result.message, failed)) delete(failed.id)
        return result
    }

    /** Whether [sent] holds everything [failed] did: the same text and the same pictures, in their order. */
    private fun carries(sent: Message, failed: Message): Boolean =
        sent.body.orEmpty() == failed.body.orEmpty() && hashes(sent) == hashes(failed)

    private fun hashes(message: Message): List<String?> = message.parts.sortedBy { it.index }.map { it.sha256 }

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
