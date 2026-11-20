package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.ui.search.SearchMatch
import com.kidslauncher.mdm.ui.search.searchHits
import com.kidslauncher.mdm.ui.search.searchQuery
import com.kidslauncher.mdm.ui.search.searching
import java.text.Collator

/*
 * The phone book as one list (design 79 §3-§4, `PhoneBookListTest`, `ContactActionsTest`) - pure: what the list shows,
 * in which order, which buttons a contact has, how the row is laid out, and which missed calls a visit has seen.
 *
 * The order (the user, 2026-11-09): a request's answer once at the very top; the contacts with an unseen missed call,
 * newest call first; everyone else A-Å; the kid's open requests under "Venter på svar"; the emergency numbers last
 * ("ikke legg nødnr på toppen, man skal jo i 99.99% av tilfellene ha tak i noen andre.. Legg den på bunnen") - A-Å
 * among themselves. An emergency number with an unseen missed call sits in the top like any other contact.
 */

/** Under a time rule the phone book is usable where the rule allows calls - where the time-rule screen offers its row
 * (`timeLockRows`) and where Meldinger is usable ("as the phone book", `messagesUsableUnder`); the used-up budget keeps
 * calls, so it keeps the phone book. `null` = no time policy. Since its own task (2026-11-16) Recents can bring it over
 * the time-rule screen, so its resume asks this ([PhoneBookActivity.redirectIfLocked]). */
fun phoneBookUsableUnder(lock: com.kidslauncher.mdm.timerules.TimeLock?): Boolean = lock == null || !lock.locked || lock.callsAllowed

/** A top row counts as seen when it was on screen this long while the phone book was resumed and focused (§3.2). */
const val PHONE_BOOK_SEEN_MS = 1_000L

/** The search pill shows from this many rows (§4.1): contacts in the phone book, conversations in Meldinger,
 * contacts in "Ny melding". */
const val SEARCH_MIN_ROWS = 8

/** The row's text column must keep this much for the buttons to stay on the name's line. */
private const val ROW_INLINE_MIN_TEXT_DP = 120f

/** The name's size (`item_phone_book_row.xml`'s `row_name`). */
const val ROW_NAME_SP = 16f

/** The buttons stay on the name's line while the text column holds this many of the name's em (the Jelly Star's
 * layout, 2026-11-20: 8 at font 1.0, about 6.3 at 1.3, 5.6 at 1.5 on the Jelly Star's 128 dp column). */
private const val ROW_INLINE_MIN_NAME_EM = 6f

/** The row's fixed parts (§3.4): the tile's start padding, the avatar, the gap, the two 48 dp button targets. */
const val ROW_PADDING_START_DP = 10f
const val ROW_AVATAR_DP = 44f
const val ROW_GAP_DP = 10f
const val ROW_BUTTONS_DP = 96f

/** A stacked row's text keeps this much off the tile's end (the buttons are on their own line, §3.4: 214 dp at 288). */
const val ROW_STACKED_END_DP = 10f

/** Where a row's Ring and Melding sit. */
enum class RowLayout {
    /** On the name's line, at the tile's end. */
    INLINE,

    /** On a line of their own under the text, from the text's start (large text, or a narrow screen). */
    STACKED,
}

/** The text column of an inline row on a [contentDp] wide page. */
fun rowTextColumnDp(contentDp: Float): Float = contentDp - ROW_PADDING_START_DP - ROW_AVATAR_DP - ROW_GAP_DP - ROW_BUTTONS_DP

/**
 * §3.4 as changed for the Jelly Star (2026-11-20): inline while the text column is at least 120 dp and holds at least
 * 6 em of the name; else stacked. [nameDp] is the name's real rendered size in dp ([ROW_NAME_SP] through Android's
 * font scaling, non-linear since Android 14), so on the Jelly Star's 288 dp the buttons stay inline through font 1.3
 * and stack from 1.5 (design 79's first rule stacked from 1.3).
 */
fun rowLayout(contentDp: Float, nameDp: Float): RowLayout {
    val column = rowTextColumnDp(contentDp)
    return if (column >= ROW_INLINE_MIN_TEXT_DP && column >= ROW_INLINE_MIN_NAME_EM * nameDp) RowLayout.INLINE else RowLayout.STACKED
}

// ---- the buttons: one rule for the card and the row ----

/** A contact's Melding: our own thread ("Styrt av Vibb"), another app's ([MessageIntent]), or none. */
sealed interface MessageAction {
    data object None : MessageAction

    data object OwnThread : MessageAction

    data class App(val intent: MessageIntent) : MessageAction
}

/** A contact's buttons: Ring (always there; [callEnabled] off while another call is live, emergency excepted) and
 * [message]. */
data class ContactActions(val callEnabled: Boolean, val message: MessageAction)

/**
 * The contact card's buttons, moved out of `ContactSheet.show` unchanged (design 79 §3.5), and the row's: Ring for every
 * listed contact - off while another call is live ([secondCallAllowed], an emergency number excepted); Melding to our
 * own thread while SMS is "Styrt av Vibb" and the kid may text them ([opensOwnThread]), else the app
 * [resolveMessageButton] finds, else none. [rules] `null` (not managed): no Melding.
 */
fun contactActions(
    contact: RuleContact,
    rules: CallRules?,
    emergency: Boolean,
    otherCallStates: List<Int>,
    defaultSmsPackage: String?,
    usable: (String) -> Boolean,
    learnedRoom: (String) -> ElementRoom? = { null },
): ContactActions {
    val callEnabled = secondCallAllowed(otherCallStates, emergency)
    val message = when {
        rules == null -> MessageAction.None
        opensOwnThread(contact, rules.smsMode) -> MessageAction.OwnThread
        else -> resolveMessageButton(contact, rules.smsEnabled, defaultSmsPackage, usable, learnedRoom)
            ?.let { MessageAction.App(it) } ?: MessageAction.None
    }
    return ContactActions(callEnabled, message)
}

// ---- the list ----

/** The newest of a contact's missed calls, phone or Element X. */
val ContactMissed.lastAtMs: Long
    get() = maxOf(phone?.takeIf { it.count > 0 }?.lastAtMs ?: Long.MIN_VALUE, element?.takeIf { it.count > 0 }?.lastAtMs ?: Long.MIN_VALUE)

/** One row of the phone book. [key]: its stable id for the adapter's diff. */
sealed interface PhoneBookRow {
    val key: String

    /** A request's answer ("Ikke lagt til", ...), shown once at the very top. */
    data class Answer(val request: RequestRow) : PhoneBookRow {
        override val key get() = "request:${request.id}"
    }

    /**
     * A contact. [top]: it is in this visit's top (an unseen missed call when the visit began, or one that came during
     * it); [missed] what still counts now (`null` once the kid dealt with it - the row stays where it is until the kid
     * leaves). [match]: the matched part of the name while searching.
     */
    data class Contact(
        val contact: RuleContact,
        val emergency: Boolean,
        val missed: ContactMissed?,
        val unread: Int,
        val top: Boolean = false,
        val match: SearchMatch? = null,
    ) : PhoneBookRow {
        override val key get() = "contact:${contact.id}:${contact.number}"

        /** The newest missed call the row shows (red line), or `null`. */
        val shownMissedAtMs: Long? get() = missed?.takeIf { it.count > 0 }?.lastAtMs
    }

    /** "Venter på svar" over the open requests. */
    data object WaitingLabel : PhoneBookRow {
        override val key get() = "label:waiting"
    }

    /** An open request: not clickable. */
    data class Waiting(val request: RequestRow, val match: SearchMatch? = null) : PhoneBookRow {
        override val key get() = "request:${request.id}"
    }

    /** No readable rules: the one 112 row ("Nødsamtale"); a tap opens the emergency menu. */
    data object EmergencyDialer : PhoneBookRow {
        override val key get() = "emergency:112"
    }
}

/** What the phone book shows: the rows; whether the search pill is there; "Fant ingenting"; the name "Be om å legge til
 * «…»" would ask for (`null` = no button). */
data class PhoneBookList(
    val rows: List<PhoneBookRow>,
    val search: Boolean,
    val nothingFound: Boolean = false,
    val askName: String? = null,
)

/**
 * The visit's top (§3.2): fixed when the phone book opens - [top] `null`, the contacts with an unseen missed call now
 * ([missed]), each with its newest call - and after that only joined: a contact whose missed call comes during the visit
 * joins with its time; nothing leaves, and a contact already there keeps its time (no row moves under the kid's finger).
 */
fun visitTopAfter(top: Map<String, Long>?, missed: Map<String, ContactMissed>): Map<String, Long> {
    val now = missed.filterValues { it.count > 0 }.mapValues { it.value.lastAtMs }
    if (top == null) return now
    return top + now.filterKeys { it !in top }
}

/**
 * The phone book's rows (design 79 §3.1, §4.1). [view] what is listed ([phoneBookView]); [emergency] the listed numbers
 * that are emergency numbers (computed once per render); [missed] and [unread] what each contact's badge and line say;
 * [visitTop] the visit's top ([visitTopAfter]; `null` until the first missed calls of the visit are loaded - then no
 * top); [requests] the kid's requests ([contactRequestRows]); [query] what the pill holds; [canAsk] calls managed and on
 * (the "+", the search, the request rows and "Be om å legge til").
 */
fun phoneBookRows(
    view: PhoneBookView,
    emergency: Set<String>,
    missed: Map<String, ContactMissed>,
    unread: Map<String, Int>,
    visitTop: Map<String, Long>?,
    requests: List<RequestRow>,
    query: String,
    collator: Collator,
    canAsk: Boolean,
): PhoneBookList {
    if (view.emergencyDialer) return PhoneBookList(listOf(PhoneBookRow.EmergencyDialer), search = false)
    val byName = compareBy<RuleContact, String>(collator) { it.name }.thenBy { it.id }
    val contacts = view.contacts.sortedWith(byName)
    fun row(contact: RuleContact, top: Boolean = false, match: SearchMatch? = null) = PhoneBookRow.Contact(
        contact, contact.number in emergency, missed[contact.number], unread[contact.number] ?: 0, top, match,
    )
    val shownRequests = if (canAsk) requests else emptyList()
    val waiting = shownRequests.filter { it.line == RequestLine.WAITING }
    val search = canAsk && (query.isNotEmpty() || view.contacts.size >= SEARCH_MIN_ROWS)

    if (search && searching(query)) {
        // The top dissolves: the hits by rank, each rank A-Å; the open requests as their waiting rows; the emergency
        // numbers last, as in the list.
        val (alarm, people) = contacts.partition { it.number in emergency }
        val hits = searchHits(people, { it.name }, query, collator).map { (c, m) -> row(c, match = m) }
        val waitingHits = searchHits(waiting, { it.name }, query, collator).map { (r, m) -> PhoneBookRow.Waiting(r, m) }
        val alarmHits = searchHits(alarm, { it.name }, query, collator).map { (c, m) -> row(c, match = m) }
        val rows = buildList {
            addAll(hits)
            if (waitingHits.isNotEmpty()) add(PhoneBookRow.WaitingLabel)
            addAll(waitingHits)
            addAll(alarmHits)
        }
        if (rows.isNotEmpty()) return PhoneBookList(rows, search = true)
        return PhoneBookList(emptyList(), search = true, nothingFound = true, askName = askName(query).takeIf { canAsk })
    }

    val top = visitTop.orEmpty()
    val (topContacts, rest) = contacts.partition { it.number in top }
    val rows = buildList {
        shownRequests.filter { it.line != RequestLine.WAITING }.forEach { add(PhoneBookRow.Answer(it)) }
        topContacts.sortedWith(compareByDescending<RuleContact> { top.getValue(it.number) }.then(byName))
            .forEach { add(row(it, top = true)) }
        rest.filter { it.number !in emergency }.forEach { add(row(it)) }
        if (waiting.isNotEmpty()) {
            add(PhoneBookRow.WaitingLabel)
            waiting.forEach { add(PhoneBookRow.Waiting(it)) }
        }
        rest.filter { it.number in emergency }.forEach { add(row(it)) }
    }
    return PhoneBookList(rows, search)
}

/** "Be om å legge til «Lina»" (§4.1): the query as the name, only with at least two letters (so never a number). */
fun askName(query: String): String? {
    val name = searchQuery(query)
    return name.takeIf { it.count(Char::isLetter) >= 2 }
}

// ---- seen ----

/**
 * Which of the visit's top rows the kid has seen (§3.2 rule 1): a row counts once it was on screen, at the top, showing
 * a missed call, for at least [PHONE_BOOK_SEEN_MS] (in all) while the phone book was resumed and focused - up to the
 * newest missed call it showed that long (a newer call that came during the visit and was on screen less still counts
 * as unseen). Fed after every layout, scroll, resume, pause and focus change ([update]); read when the kid leaves
 * ([seen]). Pure; [state]/[restore] keep it across a rotation.
 */
class TopSightings {
    private class Sighting(var atMs: Long, var accumulatedMs: Long = 0, var since: Long? = null, var seenUpToMs: Long? = null)

    private val sightings = mutableMapOf<String, Sighting>()

    /**
     * Now [nowMs] the rows on screen are [visible] (number -> the newest missed call the row shows; only top rows with a
     * red line), and the phone book is [active] (resumed and focused) or not.
     */
    fun update(visible: Map<String, Long>, active: Boolean, nowMs: Long) {
        for ((number, s) in sightings) {
            val showing = if (active) visible[number] else null
            val since = s.since
            if (since != null && (showing == null || showing != s.atMs)) {
                s.accumulatedMs += (nowMs - since).coerceAtLeast(0)
                s.since = null
            }
            if (s.accumulatedMs >= PHONE_BOOK_SEEN_MS) s.seenUpToMs = maxOf(s.seenUpToMs ?: Long.MIN_VALUE, s.atMs)
        }
        if (!active) return
        for ((number, atMs) in visible) {
            val s = sightings.getOrPut(number) { Sighting(atMs) }
            if (s.atMs != atMs) {
                // A newer call on the same row: counted from now.
                s.atMs = atMs
                s.accumulatedMs = 0
            }
            if (s.since == null) s.since = nowMs
        }
    }

    /** Number -> the newest missed call the visit saw ([MissedCallsRepo.markSeen]'s `upToMs`), at [nowMs]. */
    fun seen(nowMs: Long): Map<String, Long> {
        update(emptyMap(), active = false, nowMs = nowMs)
        return sightings.mapNotNull { (number, s) -> s.seenUpToMs?.let { number to it } }.toMap()
    }

    /** For a rotation: per number its call time, the time on screen so far and what it has seen. */
    fun state(nowMs: Long): List<SightingState> {
        update(emptyMap(), active = false, nowMs = nowMs)
        return sightings.map { (number, s) -> SightingState(number, s.atMs, s.accumulatedMs, s.seenUpToMs) }
    }

    fun restore(state: List<SightingState>) {
        sightings.clear()
        for (it in state) sightings[it.number] = Sighting(it.atMs, it.accumulatedMs, seenUpToMs = it.seenUpToMs)
    }
}

/** One row's sighting, kept across a rotation ([TopSightings.state]). */
data class SightingState(val number: String, val atMs: Long, val accumulatedMs: Long, val seenUpToMs: Long?)

/** Which glyph a part of a phone-book row's sub-line carries. */
enum class RowLineKind { MISSED, UNREAD }

/** One part of the sub-line: its glyph's kind and its text (a time or a count). */
data class RowLinePart(val kind: RowLineKind, val text: String)

/**
 * The parts of a Kontakter row's sub-line (design 106, variant D), the missed call first: its time, or its count when
 * there are several ([missedWhen] is asked only for one call), then the unread count, "99+" past 99 as the pills
 * had it. Either part alone, none for neither.
 */
fun rowLineParts(missedCount: Int, unread: Int, missedWhen: () -> String): List<RowLinePart> = listOfNotNull(
    if (missedCount > 0) RowLinePart(RowLineKind.MISSED, if (missedCount > 1) com.kidslauncher.mdm.ui.home.badgeText(missedCount)!! else missedWhen()) else null,
    com.kidslauncher.mdm.ui.home.badgeText(unread)?.let { RowLinePart(RowLineKind.UNREAD, it) },
)

/** A glyph part's characters: the glyph's placeholder, a narrow no-break space, the text - never split by a line break. */
const val ROW_GLYPH = "\uFFFC"
const val ROW_GLYPH_GAP = "\u202F"
