package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Design 69 §5: the reminder row (C) per mode, the master switch, the rules on it, the soonest of two, the countdown,
 * the "Neste" line (A), and the next boundary. */
class RemindersTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val day = LocalDate.of(2026, 10, 26)
    private val football = CalendarInfo(1, "c04e7b19a2f6d853", "at.bitfire.icsdroid", "Fotball")
    private val school = CalendarInfo(2, "9f3a61c2d84be705", "at.bitfire.icsdroid", "Skolen")

    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 26, h, m, 0, 0, oslo).toInstant().toEpochMilli()

    private fun policy(json: String) = calendarPolicy(ServerJson.parseToJsonElement(json))

    private fun remind(key: String, mode: String, minutes: Int? = null, master: String = "row", next: Boolean = false, rules: String = "[]") = policy(
        """{"on_home": true, "lock_reminders": "$master", "glance_next": $next, "rules": $rules,
           "calendars": [{"key": "$key", "shown": true, "remind": "$mode", "remind_minutes": ${minutes ?: "null"}, "color": "sky"}]}""",
    )

    private val training = InstanceRow(10, at(17, 30), at(18, 45), false, "Fotball: Trening", null, null, 1)
    private val math = InstanceRow(11, at(8, 15), at(14, 30), false, "Matte", null, null, 2)
    private val holiday = InstanceRow(12, at(0), at(0) + 86_400_000, true, "Fri", null, null, 1)

    private fun today(p: CalendarPolicy, own: Map<Long, List<Int>> = emptyMap(), now: Long = 0) = TodayCalendar(
        dayView(listOf(training, math, holiday), mapOf(1L to football, 2L to school), p, day, oslo, now, "me.vibb.launcher", "1b2c3d4e"),
        own,
    )

    @Test
    fun `each calendar's mode decides the start`() {
        val row = today(CalendarPolicy.OLDER_SERVER).view.timed.first { it.eventId == 10L }
        assertNull(reminderStart(row, Remind.Off, listOf(15)))
        assertEquals(at(17, 15), reminderStart(row, Remind.Before(15), null))
        // Own: the earliest alarm at most 120 min before - a day-before alarm doesn't count; MINUTES_DEFAULT is none.
        assertEquals(at(16, 30), reminderStart(row, Remind.Own, listOf(1440, 60, 15)))
        assertNull(reminderStart(row, Remind.Own, listOf(REMINDER_MINUTES_DEFAULT)))
        assertNull(reminderStart(row, Remind.Own, emptyList()))
        assertNull(reminderStart(row, Remind.Own, listOf(1440)))
    }

    @Test
    fun `the row is up from its start until the event, under the master switch and Dagen on Home`() {
        val p = remind("c04e7b19a2f6d853", "before", 15)
        assertNull(reminderNow(today(p), p, at(17, 14)))
        // The calendar's colour rides along to the lock row's dot (QA L4).
        assertEquals(GlanceReminder(10, "Fotball: Trening", at(17, 30), 15, CalendarColor.SKY), reminderNow(today(p), p, at(17, 15)))
        assertEquals(1, reminderNow(today(p), p, at(17, 29, ))!!.minutesLeft)
        assertEquals(2, reminderNow(today(p), p, at(17, 28) + 30_000)!!.minutesLeft)
        assertNull(reminderNow(today(p), p, at(17, 30)))
        // The master switch off: nothing, whatever the calendar says; Dagen off Home too; an older server too.
        val off = remind("c04e7b19a2f6d853", "before", 15, master = "off")
        assertNull(reminderNow(today(off), off, at(17, 20)))
        val notHome = policy("""{"on_home": false, "lock_reminders": "row", "calendars": [{"key": "c04e7b19a2f6d853", "remind": "before", "remind_minutes": 15}]}""")
        assertNull(reminderNow(today(notHome), notHome, at(17, 20)))
        assertNull(reminderNow(today(CalendarPolicy.OLDER_SERVER), CalendarPolicy.OLDER_SERVER, at(17, 20)))
        // Own reminders: from the event's own alarm.
        val own = remind("c04e7b19a2f6d853", "own")
        assertEquals(10L, reminderNow(today(own, mapOf(10L to listOf(30))), own, at(17, 0))!!.eventId)
        assertNull(reminderNow(today(own, mapOf(10L to listOf(30))), own, at(16, 59)))
        assertEquals(listOf(10L), ownReminderEvents(today(own).view, own))
        assertEquals(emptyList<Long>(), ownReminderEvents(today(off).view, off))
    }

    @Test
    fun `the rules apply to the reminder, all-day never reminds, two at once give the soonest`() {
        val renamed = remind(
            "c04e7b19a2f6d853", "before", 15,
            rules = """[{"id": 1, "field": "title", "match": "starts", "pattern": "Fotball:", "action": "prefix", "text": "Trening:"}]""",
        )
        assertEquals("Trening: Trening", reminderNow(today(renamed), renamed, at(17, 20))!!.title)
        val hidden = remind(
            "c04e7b19a2f6d853", "before", 15,
            rules = """[{"id": 1, "field": "title", "match": "contains", "pattern": "trening", "action": "hide", "text": ""}]""",
        )
        assertNull(reminderNow(today(hidden), hidden, at(17, 20)))
        // Both calendars remind 60 min before; at 08:00 only Matte's is up; a second event soon after: the soonest.
        val both = policy(
            """{"on_home": true, "lock_reminders": "row", "calendars": [
                {"key": "c04e7b19a2f6d853", "remind": "before", "remind_minutes": 60},
                {"key": "9f3a61c2d84be705", "remind": "before", "remind_minutes": 60}]}""",
        )
        val early = InstanceRow(13, at(17, 40), at(18), false, "Etterpå", null, null, 2)
        val view = dayView(listOf(training, math, holiday, early), mapOf(1L to football, 2L to school), both, day, oslo, 0, "x", "1b2c3d4e")
        assertEquals(11L, reminderNow(TodayCalendar(view, emptyMap()), both, at(8))!!.eventId)
        assertEquals(10L, reminderNow(TodayCalendar(view, emptyMap()), both, at(17))!!.eventId)
    }

    @Test
    fun `the Neste line - the next timed event, never ongoing or all-day, under its switch`() {
        val p = remind("c04e7b19a2f6d853", "off", next = true, master = "off")
        assertEquals(GlanceNext(today(p).view.timed.first { it.eventId == 11L }, tomorrow = false), glanceNext(today(p), p, at(7), oslo))
        assertEquals(10L, glanceNext(today(p), p, at(9), oslo)!!.row.eventId)
        assertNull(glanceNext(today(p), p, at(18), oslo))
        val off = remind("c04e7b19a2f6d853", "off", next = false)
        assertNull(glanceNext(today(off), off, at(7), oslo))
    }

    private fun tomorrowAt(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 27, h, m, 0, 0, oslo).toInstant().toEpochMilli()

    /** Tomorrow's day: a 09:00 timed event, an all-day one before it (never the line's), and a later one. */
    private fun withTomorrow(p: CalendarPolicy, rows: List<InstanceRow> = listOf(
        InstanceRow(21, tomorrowAt(0), tomorrowAt(0) + 86_400_000, true, "Fri i morgen", null, null, 1),
        InstanceRow(22, tomorrowAt(9), tomorrowAt(10), false, "Gym", null, null, 2),
        InstanceRow(23, tomorrowAt(12), tomorrowAt(13), false, "Lunsj", null, null, 2),
    )) = today(p).copy(tomorrow = dayView(rows, mapOf(1L to football, 2L to school), p, day.plusDays(1), oslo, 0, "me.vibb.launcher", "1b2c3d4e"))

    /** The calmer glance (2026-11-04, board D): from 18:00, once today's last timed event has started, tomorrow's
     * first timed event - "I morgen: Gym 09:00"; nothing when tomorrow has none; "Neste" again after midnight. */
    @Test
    fun `I morgen - tomorrow's first timed event from 18_00 once today's last has started`() {
        val p = remind("c04e7b19a2f6d853", "off", next = true, master = "off")
        val t = withTomorrow(p)
        // 17:59: Fotball (17:30) has started - but it isn't evening yet.
        assertNull(glanceNext(t, p, at(17, 59), oslo))
        // 18:00: evening, and Fotball - still ongoing until 18:45 - has started: tomorrow's first timed event, not the
        // all-day one before it.
        assertEquals(GlanceNext(t.tomorrow!!.timed.first { it.eventId == 22L }, tomorrow = true), glanceNext(t, p, at(18), oslo))
        assertEquals(22L, glanceNext(t, p, at(23, 59), oslo)!!.row.eventId)
        // An event later this evening comes first: "Neste" until it starts.
        val evening = remind("c04e7b19a2f6d853", "off", next = true, master = "off")
        val late = InstanceRow(14, at(19), at(20), false, "Kino", null, null, 2)
        val withLate = TodayCalendar(
            dayView(listOf(training, math, late), mapOf(1L to football, 2L to school), evening, day, oslo, 0, "me.vibb.launcher", "1b2c3d4e"),
            emptyMap(), t.tomorrow,
        )
        assertEquals(GlanceNext(withLate.view.timed.first { it.eventId == 14L }, tomorrow = false), glanceNext(withLate, evening, at(18, 30), oslo))
        assertEquals(22L, glanceNext(withLate, evening, at(19), oslo)!!.row.eventId)
        // Tomorrow with only an all-day event, an empty tomorrow, tomorrow not read: nothing.
        assertNull(glanceNext(withTomorrow(p, listOf(InstanceRow(21, tomorrowAt(0), tomorrowAt(0) + 86_400_000, true, "Fri", null, null, 1))), p, at(20), oslo))
        assertNull(glanceNext(withTomorrow(p, emptyList()), p, at(20), oslo))
        assertNull(glanceNext(today(p), p, at(20), oslo))
        // The switch off: no line, evening or not.
        val off = remind("c04e7b19a2f6d853", "off", next = false)
        assertNull(glanceNext(withTomorrow(off), off, at(20), oslo))
        // Midnight: tomorrow is today (the glance reads the day again at the boundary) - "Neste: Gym 09:00".
        val next = TodayCalendar(t.tomorrow!!, emptyMap())
        assertEquals(GlanceNext(next.view.timed.first { it.eventId == 22L }, tomorrow = false), glanceNext(next, p, tomorrowAt(0), oslo))
        // The zone: 18:00 in Oslo is 17:00 UTC - the same instant is not evening in UTC.
        assertTrue(glanceEvening(at(18), oslo))
        assertFalse(glanceEvening(at(18), ZoneId.of("UTC")))
        assertFalse(glanceEvening(at(17, 59), oslo))
        assertFalse(glanceEvening(tomorrowAt(0), oslo))
        assertNull(glanceNext(t, p, at(18), ZoneId.of("UTC")))
    }

    @Test
    fun `the next boundary - a reminder's start, its next minute, an event's start`() {
        val p = remind("c04e7b19a2f6d853", "before", 15)
        assertEquals(at(8, 15), nextReminderBoundary(today(p), p, at(7), oslo))
        assertEquals(at(17, 15), nextReminderBoundary(today(p), p, at(15), oslo))
        // Up with 15 minutes left: the next minute's change.
        assertEquals(at(17, 16), nextReminderBoundary(today(p), p, at(17, 15), oslo))
        assertEquals(at(17, 30), nextReminderBoundary(today(p), p, at(17, 29, ), oslo))
        assertNull(nextReminderBoundary(today(p), p, at(19), oslo))
        // The calmer glance: with the "Neste" line on, the evening's start and midnight too (the line turns to
        // tomorrow, then the day is read again).
        val next = remind("c04e7b19a2f6d853", "off", next = true, master = "off")
        assertEquals(at(17, 30), nextReminderBoundary(today(next), next, at(17), oslo))
        assertEquals(at(18), nextReminderBoundary(today(next), next, at(17, 30), oslo))
        assertEquals(tomorrowAt(0), nextReminderBoundary(today(next), next, at(18), oslo))
        assertEquals(15, minutesLeft(at(17, 30), at(17, 15)))
        assertEquals(1, minutesLeft(at(17, 30), at(17, 29, 30)))
    }

    private fun at(h: Int, m: Int, s: Int) = at(h, m) + s * 1000L
}
