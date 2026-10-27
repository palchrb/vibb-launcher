package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
           "calendars": [{"key": "$key", "shown": true, "remind": "$mode", "remind_minutes": ${minutes ?: "null"}}]}""",
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
        assertEquals(GlanceReminder(10, "Fotball: Trening", at(17, 30), 15), reminderNow(today(p), p, at(17, 15)))
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
        assertEquals(11L, glanceNext(today(p), p, at(7))!!.eventId)
        assertEquals(10L, glanceNext(today(p), p, at(9))!!.eventId)
        assertNull(glanceNext(today(p), p, at(18)))
        val off = remind("c04e7b19a2f6d853", "off", next = false)
        assertNull(glanceNext(today(off), off, at(7)))
    }

    @Test
    fun `the next boundary - a reminder's start, its next minute, an event's start`() {
        val p = remind("c04e7b19a2f6d853", "before", 15)
        assertEquals(at(8, 15), nextReminderBoundary(today(p), p, at(7)))
        assertEquals(at(17, 15), nextReminderBoundary(today(p), p, at(15)))
        // Up with 15 minutes left: the next minute's change.
        assertEquals(at(17, 16), nextReminderBoundary(today(p), p, at(17, 15)))
        assertEquals(at(17, 30), nextReminderBoundary(today(p), p, at(17, 29, )))
        assertNull(nextReminderBoundary(today(p), p, at(19)))
        assertEquals(15, minutesLeft(at(17, 30), at(17, 15)))
        assertEquals(1, minutesLeft(at(17, 30), at(17, 29, 30)))
    }

    private fun at(h: Int, m: Int, s: Int) = at(h, m) + s * 1000L
}
