package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.LoggedCallType.INCOMING_ANSWERED
import com.kidslauncher.mdm.calls.LoggedCallType.MISSED
import com.kidslauncher.mdm.calls.LoggedCallType.OTHER
import com.kidslauncher.mdm.calls.LoggedCallType.OUTGOING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Design 106 §3.2 (B2): which calls Siste shows, how they merge and group by day, which rows are unseen (exactly what the
 * badges count) and the Siste tab's badge. One phone book of Emma, Jack, Lily, Mamma, Pappa and Ambulanse; Lily is
 * inbound only (no sheet, so never in Siste). The zone is fixed, "now" is Monday 16 November 2026 15:00.
 */
class RecentCallsTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(16, 15)

    private val emma = RuleContact(1, "Emma", "+4790000001", inbound = true, outbound = true)
    private val jack = RuleContact(2, "Jack", "+4790000002", inbound = true, outbound = true)
    private val lily = RuleContact(3, "Lily", "+4790000003", inbound = true, outbound = false)
    private val mamma = RuleContact(
        4, "Mamma", "+4790000004", inbound = true, outbound = true, messageApp = "element", messageAddress = "@mamma:example.org",
    )
    private val pappa = RuleContact(5, "Pappa", "+4790000005", inbound = true, outbound = true)
    private val ambulanse = RuleContact(6, "Ambulanse", "113", inbound = true, outbound = true)
    private val rules = CallRules(callsEnabled = true, contacts = listOf(emma, jack, lily, mamma, pappa, ambulanse))
    private val book = rules.phoneBook

    private var nextId = 1L
    private fun log(number: String?, type: LoggedCallType, atMs: Long) = CallLogEntry(number, type, atMs, id = nextId++)

    private fun recent(
        log: List<CallLogEntry>,
        records: List<ElementCallRecord> = emptyList(),
        live: Map<String, LiveElementCall> = emptyMap(),
        seen: Map<String, Long> = emptyMap(),
    ) = recentCalls(log, records, book, rules.defaultCc, unseenMissedCalls(log, rules.contacts, rules.defaultCc, seen), live, seen, now, zone)

    private fun shape(days: List<RecentDay>) = days.map { day ->
        day.day.toString() to day.rows.map { "${it.contact.name} ${it.kind} ${it.source} x${it.count}${if (it.unseen) " unseen" else ""}" }
    }

    @Test
    fun `only phone-book contacts, only calls in, out and missed - never blocked, rejected or voicemail`() {
        val days = recent(
            listOf(
                log("90000001", OUTGOING, at(16, 14)), // Emma, written without the country code
                log("+4790000004", INCOMING_ANSWERED, at(16, 13)),
                log("+4799999999", MISSED, at(16, 12)), // a stranger
                log("+4790000003", MISSED, at(16, 11)), // Lily: inbound only, no sheet
                log("+4790000002", OTHER, at(16, 10)), // Jack declined (REJECTED), or blocked, or voicemail
                log(null, MISSED, at(16, 9)), // a hidden number
                log("113", MISSED, at(16, 8)), // the emergency contact is a contact
                log("+4790000005", MISSED, at(16, 7)),
            ),
        )
        assertEquals(
            listOf(
                "2026-11-16" to listOf(
                    "Emma OUT PHONE x1", "Mamma IN PHONE x1", "Ambulanse MISSED PHONE x1 unseen", "Pappa MISSED PHONE x1 unseen",
                ),
            ),
            shape(days),
        )
    }

    @Test
    fun `adjacent calls merge - only the same contact, kind and source, never across a day`() {
        val days = recent(
            listOf(
                log("+4790000005", MISSED, at(16, 14, 30)),
                log("+4790000005", MISSED, at(16, 14, 20)),
                log("+4790000004", INCOMING_ANSWERED, at(16, 13)),
                log("+4790000001", OUTGOING, at(16, 12)),
                log("+4790000005", MISSED, at(16, 11)), // Pappa again, not adjacent: a row of its own
                log("+4790000005", MISSED, at(16, 0, 5)),
                log("+4790000005", MISSED, at(15, 23, 55)), // Pappa, adjacent but yesterday
                log("+4790000001", OUTGOING, at(15, 20)),
                log("+4790000001", INCOMING_ANSWERED, at(15, 19)), // Emma, adjacent but another kind
            ),
            seen = mapOf("+4790000005" to at(16, 0, 0)),
        )
        assertEquals(
            listOf(
                "2026-11-16" to listOf(
                    "Pappa MISSED PHONE x2 unseen", "Mamma IN PHONE x1", "Emma OUT PHONE x1", "Pappa MISSED PHONE x2 unseen",
                ),
                "2026-11-15" to listOf("Pappa MISSED PHONE x1", "Emma OUT PHONE x1", "Emma IN PHONE x1"),
            ),
            shape(days),
        )
        // The merged row's time is its newest call's.
        assertEquals(at(16, 14, 30), days[0].rows[0].atMs)
        assertEquals(2, days[0].rows[0].count)
    }

    @Test
    fun `unseen is the badge's own - after the seen mark and after a call back`() {
        val calls = listOf(
            log("+4790000005", MISSED, at(16, 14)),
            log("+4790000005", MISSED, at(16, 13)),
            log("+4790000002", MISSED, at(16, 12)),
            log("+4790000002", OUTGOING, at(16, 12, 30)), // Jack was called back: dealt with
            log("+4790000001", MISSED, at(16, 11)),
        ).sortedByDescending { it.atMs }
        val seen = mapOf("+4790000005" to at(16, 13, 30), "+4790000001" to at(16, 10))
        val rows = recent(calls, seen = seen).single().rows
        // Pappa's two merge into one row, unseen while any call in it still counts (14:00, after his 13:30 mark).
        assertEquals(
            listOf("Pappa MISSED x2 true", "Jack OUT x1 false", "Jack MISSED x1 false", "Emma MISSED x1 true"),
            rows.map { "${it.contact.name} ${it.kind} x${it.count} ${it.unseen}" },
        )
        // Seen up to his newest call: the row is no longer unseen.
        assertFalse(recent(calls, seen = seen + ("+4790000005" to at(16, 14))).single().rows.first().unseen)
        // Every unseen row's contact has a badge, and the badge counts what the unseen rows hold.
        val unseen = unseenMissedCalls(calls, rules.contacts, rules.defaultCc, seen)
        assertEquals(rows.filter { it.unseen }.map { it.contact.number }.toSet(), unseen.keys)
    }

    @Test
    fun `an Element X row is a missed call in the app, unseen exactly while the badge counts it`() {
        val hash = elementKeyHash("0|io.element.android.x|!mamma-dm:example.org|1")
        val record = ElementCallRecord(mamma.number, at(16, 14), hash, open = true)
        val live = mapOf(hash to LiveElementCall(mamma.number, at(16, 14)))
        val row = recent(emptyList(), listOf(record), live).single().rows.single()
        assertEquals(RecentKind.MISSED to RecentSource.ELEMENT_X, row.kind to row.source)
        assertTrue(row.unseen)
        // Seen after the call, or gone from the live store (the room opened in Element X): the row stays, not red-unseen.
        assertFalse(recent(emptyList(), listOf(record), live, seen = mapOf(mamma.number to at(16, 14, 1))).single().rows.single().unseen)
        assertFalse(recent(emptyList(), listOf(record.copy(open = false))).single().rows.single().unseen)
        // A live entry for another contact under the same hash is no match.
        assertFalse(recent(emptyList(), listOf(record), mapOf(hash to LiveElementCall(pappa.number, at(16, 14)))).single().rows.single().unseen)
        // An Element X row and a phone row of the same contact never merge.
        val both = recent(listOf(log("+4790000004", MISSED, at(16, 13, 59))), listOf(record), live).single().rows
        assertEquals(listOf(RecentSource.ELEMENT_X, RecentSource.PHONE), both.map { it.source })
        // The badge counts the same call: the live entry after the seen mark.
        val badge = elementMissedSummaries(listOf(ElementMissedCall("@mamma:example.org", at(16, 14))), rules, emptyMap())
        assertEquals(1, badge.getValue(mamma.number).count)
        // A record of a contact not in the book never shows.
        assertTrue(recent(emptyList(), listOf(record.copy(number = "+4799999999"))).isEmpty())
    }

    @Test
    fun `fourteen days, grouped by local day - today, yesterday, a weekday, a date`() {
        val days = recent(
            listOf(
                log("+4790000001", OUTGOING, at(16, 0, 30)),
                log("+4790000001", OUTGOING, at(15, 23, 30)), // late Sunday in Oslo, already Sunday 22:30 UTC
                log("+4790000002", OUTGOING, at(11, 12)),
                log("+4790000004", OUTGOING, at(9, 12)),
                log("+4790000005", OUTGOING, at(2, 15, 1)), // just inside the 14 days
                log("+4790000005", OUTGOING, at(2, 14, 59)), // just outside
            ),
        )
        val today = LocalDate.of(2026, 11, 16)
        assertEquals(listOf("2026-11-16", "2026-11-15", "2026-11-11", "2026-11-09", "2026-11-02"), days.map { it.day.toString() })
        assertEquals(
            listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.Weekday(DayOfWeek.WEDNESDAY), DayLabel.Date(LocalDate.of(2026, 11, 9)),
                DayLabel.Date(LocalDate.of(2026, 11, 2))),
            days.map { dayLabel(it.day, today) },
        )
        assertEquals(DayLabel.Weekday(DayOfWeek.SATURDAY), dayLabel(LocalDate.of(2026, 11, 14), today))
        assertEquals(DayLabel.Weekday(DayOfWeek.TUESDAY), dayLabel(LocalDate.of(2026, 11, 10), today))
        assertEquals(1, days.last().rows.single().count)
    }

    @Test
    fun `one flat list - a heading per day, each row's place in its day`() {
        assertEquals(RowPlace.SINGLE, rowPlace(0, 1))
        assertEquals(listOf(RowPlace.FIRST, RowPlace.LAST), (0..1).map { rowPlace(it, 2) })
        assertEquals(listOf(RowPlace.FIRST, RowPlace.MIDDLE, RowPlace.MIDDLE, RowPlace.LAST), (0..3).map { rowPlace(it, 4) })
        val days = recent(
            listOf(
                log("+4790000001", OUTGOING, at(16, 14)),
                log("+4790000002", OUTGOING, at(16, 13)),
                log("113", MISSED, at(16, 12)),
                log("+4790000005", OUTGOING, at(15, 12)),
            ),
        )
        val items = recentItems(days, LocalDate.of(2026, 11, 16), emergency = setOf("113"))
        assertEquals(
            listOf("day Today", "Emma FIRST", "Jack MIDDLE", "Ambulanse LAST emergency", "day Yesterday", "Pappa SINGLE"),
            items.map {
                when (it) {
                    is RecentItem.Day -> "day ${it.label}"
                    is RecentItem.Call -> "${it.row.contact.name} ${it.place}${if (it.emergency) " emergency" else ""}"
                }
            },
        )
        assertEquals(items.size, items.map { it.key }.toSet().size)
        // Code QA L4: a row carries its day's words, so the next day's load differs and rebinds (TalkBack's "i går").
        val tomorrow = recentItems(days, LocalDate.of(2026, 11, 17), emergency = setOf("113"))
        val emma = { list: List<RecentItem> -> list.filterIsInstance<RecentItem.Call>().first() }
        assertEquals(DayLabel.Today, emma(items).label)
        assertEquals(DayLabel.Yesterday, emma(tomorrow).label)
        assertEquals(emma(items).key, emma(tomorrow).key)
        assertTrue(emma(items) != emma(tomorrow))
    }

    @Test
    fun `the Siste badge is the sum of the book's rust pills`() {
        val calls = listOf(
            log("+4790000005", MISSED, at(16, 14)),
            log("+4790000005", MISSED, at(16, 13)),
            log("+4790000001", MISSED, at(16, 12)),
            log("+4790000003", MISSED, at(16, 11)), // Lily: a badge-worthy call, but she isn't in the book
        )
        val phone = missedCallSummaries(calls, rules.contacts, rules.defaultCc, emptyMap())
        val element = elementMissedSummaries(listOf(ElementMissedCall("@mamma:example.org", at(16, 10))), rules, emptyMap())
        val missed = contactMissed(phone, element)
        assertEquals(book.sumOf { missed[it.number]?.count ?: 0 }, recentBadge(missed, book))
        assertEquals(4, recentBadge(missed, book))
        assertEquals(0, recentBadge(emptyMap(), book))
    }
}
