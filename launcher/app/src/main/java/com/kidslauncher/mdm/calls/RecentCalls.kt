package com.kidslauncher.mdm.calls

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Design 106 §3 (B2): "Siste", the phone book's second list - which calls show, merged and grouped by day, which rows
 * are unseen, the Siste tab's badge, its sightings, the tab rule and Back. Pure, no Android imports - `RecentCallsTest`
 * and `PhoneBookListTest`. The glue is [MissedCallsRepo.load] (one call-log read for the badges and Siste),
 * [RecentCallsAdapter] and [PhoneBookActivity].
 */

/** A Siste row's kind: a missed call (red), a call that came in and was answered, or one the kid made. */
enum class RecentKind { MISSED, IN, OUT }

/** Where the call was: the phone, or Element X (missed calls only, §3.3). */
enum class RecentSource { PHONE, ELEMENT_X }

/**
 * One Siste row: [count] calls of one [kind] with [contact] from [source], merged while adjacent on one day; [atMs] the
 * newest. [unseen]: it holds a missed call that still counts for the contact's badge (§3.2).
 */
data class RecentRow(
    val contact: RuleContact,
    val kind: RecentKind,
    val source: RecentSource,
    val count: Int,
    val atMs: Long,
    val unseen: Boolean,
)

/** One day of Siste, newest first. */
data class RecentDay(val day: LocalDate, val rows: List<RecentRow>)

/** A live missed Element X call by its key's hash ([elementKeyHash]): the contact's number now and when it turned
 * into a missed call - what the badge counts. */
data class LiveElementCall(val number: String, val sinceMs: Long)

/**
 * The calls Siste shows (§3.2), grouped by local day ([zone]), newest first:
 * - only [contacts] of the phone book, matched as the badges match them ([PhoneNumbers.normalize] equal to the contact's
 *   number) - strangers, a contact that left the rules and an inbound-only contact never show;
 * - from the call log: [LoggedCallType.INCOMING_ANSWERED] as IN, [LoggedCallType.OUTGOING] as OUT,
 *   [LoggedCallType.MISSED] as MISSED; every other type (blocked, rejected - the kid's decline and our backstop alike
 *   -, voicemail) is left out;
 * - Element X: one MISSED row per [records] entry;
 * - of the last 14 days ([MISSED_CALL_LOG_WINDOW_MS] before [nowMs]);
 * - adjacent rows with the same contact, kind, source and day merge (count summed, the newest time);
 * - a phone row is unseen when it holds one of [unseenPhone]'s rows ([unseenMissedCalls], the badge's own); an Element
 *   X row when [live] has an entry with its hash, for its contact, after the contact's seen mark ([seenMs]).
 */
fun recentCalls(
    log: List<CallLogEntry>,
    records: List<ElementCallRecord>,
    contacts: List<RuleContact>,
    defaultCc: String,
    unseenPhone: Map<String, List<CallLogEntry>>,
    live: Map<String, LiveElementCall>,
    seenMs: Map<String, Long>,
    nowMs: Long,
    zone: ZoneId,
): List<RecentDay> {
    val byNumber = LinkedHashMap<String, RuleContact>()
    contacts.forEach { byNumber.putIfAbsent(it.number, it) }
    val since = nowMs - MISSED_CALL_LOG_WINDOW_MS
    val unseenRows = unseenPhone.values.flatten().toSet()

    data class Call(val contact: RuleContact, val kind: RecentKind, val source: RecentSource, val atMs: Long, val unseen: Boolean)

    val calls = mutableListOf<Call>()
    for (entry in log) {
        if (entry.atMs < since) continue
        val kind = when (entry.type) {
            LoggedCallType.INCOMING_ANSWERED -> RecentKind.IN
            LoggedCallType.OUTGOING -> RecentKind.OUT
            LoggedCallType.MISSED -> RecentKind.MISSED
            LoggedCallType.OTHER -> continue
        }
        val number = entry.number?.let { PhoneNumbers.normalize(it, defaultCc)?.value } ?: continue
        val contact = byNumber[number] ?: continue
        calls += Call(contact, kind, RecentSource.PHONE, entry.atMs, kind == RecentKind.MISSED && entry in unseenRows)
    }
    for (record in records) {
        if (record.atMs < since) continue
        val contact = byNumber[record.number] ?: continue
        val entry = live[record.hash]
        val unseen = entry != null && entry.number == record.number && entry.sinceMs > (seenMs[record.number] ?: Long.MIN_VALUE)
        calls += Call(contact, RecentKind.MISSED, RecentSource.ELEMENT_X, record.atMs, unseen)
    }
    calls.sortByDescending { it.atMs }

    val days = mutableListOf<RecentDay>()
    var rows = mutableListOf<RecentRow>()
    var day: LocalDate? = null
    for (call in calls) {
        val callDay = Instant.ofEpochMilli(call.atMs).atZone(zone).toLocalDate()
        if (callDay != day) {
            day?.let { days += RecentDay(it, rows) }
            day = callDay
            rows = mutableListOf()
        }
        val last = rows.lastOrNull()
        if (last != null && last.contact.number == call.contact.number && last.kind == call.kind && last.source == call.source) {
            rows[rows.size - 1] = last.copy(count = last.count + 1, unseen = last.unseen || call.unseen)
        } else {
            rows += RecentRow(call.contact, call.kind, call.source, 1, call.atMs, call.unseen)
        }
    }
    day?.let { days += RecentDay(it, rows) }
    return days
}

/** The Siste tab's badge (§3.4): the missed calls the badges count, summed over the contacts in the phone book - so it
 * always equals the sum of the rust pills in the book. */
fun recentBadge(missed: Map<String, ContactMissed>, contacts: List<RuleContact>): Int =
    contacts.mapTo(LinkedHashSet()) { it.number }.sumOf { missed[it]?.count ?: 0 }

/** A day heading's words (§3.2): "I dag", "I går", the weekday two to six days ago, else the weekday and the date. */
sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class Weekday(val day: DayOfWeek) : DayLabel

    data class Date(val date: LocalDate) : DayLabel
}

fun dayLabel(day: LocalDate, today: LocalDate): DayLabel = when (today.toEpochDay() - day.toEpochDay()) {
    0L -> DayLabel.Today
    1L -> DayLabel.Yesterday
    in 2L..6L -> DayLabel.Weekday(day.dayOfWeek)
    else -> DayLabel.Date(day)
}

/** A row's place in its day: its background's corners (§3.6). */
enum class RowPlace { FIRST, MIDDLE, LAST, SINGLE }

fun rowPlace(index: Int, size: Int): RowPlace = when {
    size <= 1 -> RowPlace.SINGLE
    index == 0 -> RowPlace.FIRST
    index == size - 1 -> RowPlace.LAST
    else -> RowPlace.MIDDLE
}

/** Siste as one flat list (§3.6, L5): a day's heading, then its rows - each an item of its own. */
sealed interface RecentItem {
    val key: String

    data class Day(val day: LocalDate, val label: DayLabel) : RecentItem {
        override val key get() = "day:$day"
    }

    /** [day] and [label]: the row's day and its words against today (code QA L4) - so a new day rebinds the row and its
     * TalkBack sentence says "i går" after midnight. */
    data class Call(val row: RecentRow, val place: RowPlace, val emergency: Boolean, val day: LocalDate, val label: DayLabel) : RecentItem {
        override val key get() = "call:${row.contact.id}:${row.contact.number}:${row.kind}:${row.source}:${row.atMs}"
    }
}

/** The flat list for [days], the labels against [today] (computed at each load, so a new day moves "I dag"). */
fun recentItems(days: List<RecentDay>, today: LocalDate, emergency: Set<String>): List<RecentItem> = buildList {
    for (day in days) {
        val label = dayLabel(day.day, today)
        add(RecentItem.Day(day.day, label))
        day.rows.forEachIndexed { i, row ->
            add(RecentItem.Call(row, rowPlace(i, day.rows.size), row.contact.number in emergency, day.day, label))
        }
    }
}

/**
 * Siste's sightings (§3.4): the red, unseen rows on screen now ([shown]: each row and whether at least half of it is
 * visible) - per contact the newest of its visible ones, so a row scrolled out of view never raises the mark. Fed to
 * Siste's own [TopSightings], keyed by the contact's number.
 */
fun recentVisible(shown: List<Pair<RecentRow, Boolean>>): Map<String, Long> {
    val out = mutableMapOf<String, Long>()
    for ((row, visible) in shown) {
        if (!visible || !row.unseen || row.kind != RecentKind.MISSED) continue
        val number = row.contact.number
        out[number] = maxOf(out[number] ?: Long.MIN_VALUE, row.atMs)
    }
    return out
}

/** From this font scale a Siste row's time moves under its word (design 106; the contacts' [rowLayout] once had the same 1.3). */
fun recentTimeBelow(fontScale: Float): Boolean = fontScale >= 1.3f

// ---- the tab ----

/** The phone book's two lists (§3.5). */
enum class PhoneBookTab { CONTACTS, RECENT }

/** The switch shows only while the kid can ask (calls managed and on - [ContactRequestForm.canAsk]) and the request form
 * is closed. */
fun phoneBookSwitchShown(canAsk: Boolean, formOpen: Boolean): Boolean = canAsk && !formOpen

/** A visit starts on Kontakter (Q5); only a configuration change ([restored]: the saved tab came back with the visit)
 * keeps Siste. */
fun tabAtVisitStart(restored: Boolean, saved: PhoneBookTab?): PhoneBookTab = if (restored && saved != null) saved else PhoneBookTab.CONTACTS

/** The tab after [render]-time checks and a way in by `onNewIntent` (§3.5, Q7, L1, code QA M1): Kontakter whenever the
 * switch may not show, or the book was [entered] again from outside (any new intent - the notification with one caller
 * or several, the system call log, a tel: link, the time-rule screen's row). */
fun tabAfter(tab: PhoneBookTab, switchShown: Boolean, entered: Boolean): PhoneBookTab =
    if (!switchShown || entered) PhoneBookTab.CONTACTS else tab
