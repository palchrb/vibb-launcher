package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Design 69 §2.4-2.5: the day model - all-day in UTC, multi-day, zero-length, what's left out, the dedupe, the order,
 * "now", and the DST days (the itslearning feed's UTC times). */
class DayModelTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val newYork = ZoneId.of("America/New_York")
    private val own = "me.vibb.launcher"
    private val tag = "1b2c3d4e"
    private val school = CalendarInfo(1, "9f3a61c2d84be705", "at.bitfire.icsdroid", "Skolen")
    private val family = CalendarInfo(2, "c04e7b19a2f6d853", "bitfire.at.davdroid", "Familie")
    private val calendars = mapOf(1L to school, 2L to family)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = oslo) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun utc(y: Int, mo: Int, d: Int) = at(y, mo, d, 0, zone = ZoneOffset.UTC)

    private fun row(id: Long, begin: Long, end: Long, title: String, allDay: Boolean = false, calendar: Long = 1, location: String? = null, description: String? = null, status: Int? = null, self: Int? = null, uid: String? = null) =
        InstanceRow(id, begin, end, allDay, title, location, description, calendar, status, self, uid)

    private fun view(rows: List<InstanceRow>, day: LocalDate, zone: ZoneId = oslo, now: Long = 0, policy: CalendarPolicy = CalendarPolicy.OLDER_SERVER) =
        dayView(rows, calendars, policy, day, zone, now, own, tag)

    @Test
    fun `an event page's day - an all-day event's UTC date west of UTC too (QA L4)`() {
        assertEquals(LocalDate.of(2026, 11, 3), eventDay(utc(2026, 11, 3), true, newYork))
        assertEquals(LocalDate.of(2026, 11, 2), eventDay(utc(2026, 11, 3), false, newYork))
        assertEquals(LocalDate.of(2026, 11, 3), eventDay(at(2026, 11, 3, 0, 30), false, oslo))
        val holiday = row(1, utc(2026, 11, 3), utc(2026, 11, 4), "Planleggingsdag", allDay = true)
        assertTrue(view(listOf(holiday), LocalDate.of(2026, 11, 3), newYork).allDay.single().allDayEvent)
    }

    @Test
    fun `an all-day event is on its UTC dates in every zone`() {
        val holiday = row(1, utc(2026, 10, 26), utc(2026, 10, 27), "Høstferie slutt", allDay = true)
        for (zone in listOf(oslo, newYork)) {
            assertEquals(zone.id, listOf("Høstferie slutt"), view(listOf(holiday), LocalDate.of(2026, 10, 26), zone).allDay.map { it.title })
            assertTrue(zone.id, view(listOf(holiday), LocalDate.of(2026, 10, 25), zone).empty)
            assertTrue(zone.id, view(listOf(holiday), LocalDate.of(2026, 10, 27), zone).empty)
        }
        // A camp over a weekend: every day all-day.
        val camp = row(2, utc(2026, 10, 30), utc(2026, 11, 2), "Leir", allDay = true)
        for (d in 30..31) assertEquals(1, view(listOf(camp), LocalDate.of(2026, 10, d)).allDay.size)
        assertEquals(1, view(listOf(camp), LocalDate.of(2026, 11, 1)).allDay.size)
        assertTrue(view(listOf(camp), LocalDate.of(2026, 11, 2)).empty)
    }

    @Test
    fun `timed events by the local dates - midnight, multi-day and zero-length`() {
        val late = row(1, at(2026, 10, 26, 22), at(2026, 10, 27, 0), "Sent")
        assertEquals(RowKind.TIMED, view(listOf(late), LocalDate.of(2026, 10, 26)).timed.single().kind)
        assertTrue(view(listOf(late), LocalDate.of(2026, 10, 27)).empty)
        val weekend = row(2, at(2026, 10, 30, 17), at(2026, 11, 1, 14), "Hyttetur")
        val morning = row(3, at(2026, 11, 1, 9), at(2026, 11, 1, 10), "Frokost")
        assertEquals(RowKind.FIRST_DAY, view(listOf(weekend), LocalDate.of(2026, 10, 30)).timed.single().kind)
        val middle = view(listOf(weekend), LocalDate.of(2026, 10, 31))
        assertEquals(RowKind.ALL_DAY, middle.allDay.single().kind)
        assertFalse(middle.allDay.single().ongoing)
        val last = view(listOf(morning, weekend), LocalDate.of(2026, 11, 1)).timed
        assertEquals(listOf(RowKind.LAST_DAY, RowKind.TIMED), last.map { it.kind })
        val point = row(4, at(2026, 10, 26, 12), at(2026, 10, 26, 12), "Nå")
        assertEquals(RowKind.TIMED, view(listOf(point), LocalDate.of(2026, 10, 26)).timed.single().kind)
    }

    @Test
    fun `cancelled, declined, hidden calendars and hiding rules are left out`() {
        val day = LocalDate.of(2026, 10, 26)
        val rows = listOf(
            row(1, at(2026, 10, 26, 8), at(2026, 10, 26, 9), "Avlyst", status = STATUS_CANCELED),
            row(2, at(2026, 10, 26, 9), at(2026, 10, 26, 10), "Takket nei", self = ATTENDEE_DECLINED),
            row(3, at(2026, 10, 26, 10), at(2026, 10, 26, 11), "Familie", calendar = 2),
            row(4, at(2026, 10, 26, 11), at(2026, 10, 26, 12), "Ukjent kalender", calendar = 9),
            row(5, at(2026, 10, 26, 12), at(2026, 10, 26, 13), "Frankenstein- for 6. og 7. trinn"),
            row(6, at(2026, 10, 26, 13), at(2026, 10, 26, 14), "DKS- Dikemark", description = "For 6. trinn. Buss kl. 9."),
            row(7, at(2026, 10, 26, 14), at(2026, 10, 26, 15), "Gruppe 2- skoleåret 2026/2027: Matte"),
        )
        val policy = calendarPolicy(
            ServerJson.parseToJsonElement(
                """{"calendars": [{"key": "c04e7b19a2f6d853", "shown": false}],
                   "rules": [{"id": 9, "field": "title", "match": "contains", "pattern": "6. og 7. trinn", "action": "hide", "text": ""},
                             {"id": 10, "field": "description", "match": "contains", "pattern": "For 6. trinn", "action": "hide", "text": ""},
                             {"id": 7, "calendar": "9f3a61c2d84be705", "field": "title", "match": "starts", "pattern": "Gruppe 2- skoleåret 2026/2027:", "action": "prefix", "text": "Skole:"}]}""",
            ),
        )
        assertEquals(listOf("Skole: Matte"), view(rows, day, policy = policy).timed.map { it.title })
        // A calendar that doesn't sync events is not shown either.
        val noSync = mapOf(1L to school.copy(syncEvents = false))
        assertTrue(dayView(rows.takeLast(1), noSync, CalendarPolicy.OLDER_SERVER, day, oslo, 0, own, tag).empty)
    }

    @Test
    fun `two copies are one row, and the order`() {
        val day = LocalDate.of(2026, 10, 26)
        val rows = listOf(
            row(5, at(2026, 10, 26, 17, 30), at(2026, 10, 26, 18, 45), "Fotball: Trening", location = "Kunstgresset"),
            row(4, at(2026, 10, 26, 17, 30), at(2026, 10, 26, 18, 45), "Fotball: Trening", calendar = 2, location = "Kunstgresset"),
            row(3, at(2026, 10, 26, 8, 15), at(2026, 10, 26, 14, 30), "Skole"),
            row(2, at(2026, 10, 26, 8, 15), at(2026, 10, 26, 12, 0), "Tannlege"),
            row(9, utc(2026, 10, 26), utc(2026, 10, 27), "b bursdag", allDay = true),
            row(8, utc(2026, 10, 26), utc(2026, 10, 27), "A ferie", allDay = true),
        )
        val v = view(rows, day)
        assertEquals(listOf("A ferie", "b bursdag"), v.allDay.map { it.title })
        assertEquals(listOf(2L, 3L, 4L), v.timed.map { it.eventId })
    }

    @Test
    fun `now is between begin and end, timed rows only`() {
        val day = LocalDate.of(2026, 10, 26)
        val sfo = row(1, at(2026, 10, 26, 14, 45), at(2026, 10, 26, 15, 30), "SFO")
        val holiday = row(2, utc(2026, 10, 26), utc(2026, 10, 27), "Fri", allDay = true)
        assertTrue(view(listOf(sfo), day, now = sfo.begin).timed.single().ongoing)
        assertFalse(view(listOf(sfo), day, now = sfo.end).timed.single().ongoing)
        assertFalse(view(listOf(sfo), day, now = sfo.begin - 1).timed.single().ongoing)
        assertFalse(view(listOf(holiday), day, now = holiday.begin + 1000).allDay.single().ongoing)
    }

    @Test
    fun `the DST days - a UTC series moves an hour, a 25-hour day is one day`() {
        // The itslearning feed's 09:00Z: 11:00 before 25 October, 10:00 after; the same in March the other way.
        fun localHour(ms: Long) = Instant.ofEpochMilli(ms).atZone(oslo).hour
        val before = row(1, at(2026, 10, 23, 9, zone = ZoneOffset.UTC), at(2026, 10, 23, 10, zone = ZoneOffset.UTC), "Gym")
        val after = row(2, at(2026, 10, 28, 9, zone = ZoneOffset.UTC), at(2026, 10, 28, 10, zone = ZoneOffset.UTC), "Gym")
        assertEquals(11, localHour(view(listOf(before), LocalDate.of(2026, 10, 23)).timed.single().begin))
        assertEquals(10, localHour(view(listOf(after), LocalDate.of(2026, 10, 28)).timed.single().begin))
        // 2026-10-25 has 25 hours: 02:30 is there twice, 23:30 is still that day.
        val night = row(3, at(2026, 10, 25, 1, zone = ZoneOffset.UTC), at(2026, 10, 25, 2, zone = ZoneOffset.UTC), "Natt")
        val evening = row(4, at(2026, 10, 25, 23, 30), at(2026, 10, 25, 23, 45), "Kveld")
        assertEquals(2, view(listOf(night, evening), LocalDate.of(2026, 10, 25)).timed.size)
        // 2027-03-28 has 23 hours; 2027-10-31 is the next 25-hour day.
        val spring = row(5, at(2027, 3, 28, 1, 30), at(2027, 3, 28, 3, 30), "Vår")
        assertEquals(1, view(listOf(spring), LocalDate.of(2027, 3, 28)).timed.size)
        val autumn = row(6, at(2027, 10, 31, 9, zone = ZoneOffset.UTC), at(2027, 10, 31, 10, zone = ZoneOffset.UTC), "Høst")
        assertEquals(10, localHour(view(listOf(autumn), LocalDate.of(2027, 10, 31)).timed.single().begin))
    }

    @Test
    fun `the kid's own events are known by this phone's uid tag`() {
        assertTrue("this phone's, after a CalDAV round trip", ownedByVibb("vibb-$tag-0f9e", null, own, tag))
        assertTrue("a Vibb uid this phone's app wrote", ownedByVibb("vibb-0f9e8d7c-aaaa", "me.vibb.launcher.debug", own, tag))
        assertFalse("a sibling's phone, in a shared calendar (QA L1)", ownedByVibb("vibb-99999999-0f9e", null, own, tag))
        assertFalse("no Vibb uid", ownedByVibb(null, "me.vibb.launcher", own, tag))
        assertFalse(ownedByVibb("abc@itslearning", "at.bitfire.davdroid", own, tag))
        val mine = row(1, at(2026, 10, 31, 14), at(2026, 10, 31, 16), "Besøk hos Ola", uid = "vibb-$tag-x")
        val sibling = row(2, at(2026, 10, 31, 17), at(2026, 10, 31, 18), "Trening", uid = "vibb-99999999-x")
        assertEquals(listOf(true, false), view(listOf(mine, sibling), LocalDate.of(2026, 10, 31)).timed.map { it.ownedByVibb })
        assertEquals(2461340, julianDay(LocalDate.of(2026, 10, 26)))
    }
}
