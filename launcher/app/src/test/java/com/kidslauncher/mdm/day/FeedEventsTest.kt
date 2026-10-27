package com.kidslauncher.mdm.day

import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Design 72 §4: one series' provider rows - DURATION, EXDATE and its fallback, all-day UTC midnights, floating with
 * its local UNTIL, an unknown zone, ORIGINAL_INSTANCE_TIME - and the writes through the sync-adapter URI. */
class FeedEventsTest {
    private val oslo = ZoneId.of("Europe/Oslo")

    private fun ms(local: String, zone: ZoneId) =
        LocalDateTime.parse(local, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the column names are CalendarContract's`() {
        val c = FeedColumns
        val pairs = listOf(
            c.SYNC_ID to Events._SYNC_ID, c.UID to Events.UID_2445, c.SERIES_REV to Events.SYNC_DATA1,
            c.WRITTEN_ZONE to Events.SYNC_DATA2, c.TITLE to Events.TITLE, c.DESCRIPTION to Events.DESCRIPTION,
            c.LOCATION to Events.EVENT_LOCATION, c.STATUS to Events.STATUS, c.DTSTART to Events.DTSTART,
            c.DTEND to Events.DTEND, c.DURATION to Events.DURATION, c.TIMEZONE to Events.EVENT_TIMEZONE,
            c.ALL_DAY to Events.ALL_DAY, c.RRULE to Events.RRULE, c.RDATE to Events.RDATE, c.EXDATE to Events.EXDATE,
            c.HAS_ALARM to Events.HAS_ALARM, c.ORIGINAL_SYNC_ID to Events.ORIGINAL_SYNC_ID,
            c.ORIGINAL_INSTANCE_TIME to Events.ORIGINAL_INSTANCE_TIME, c.ORIGINAL_ALL_DAY to Events.ORIGINAL_ALL_DAY,
        )
        for ((ours, theirs) in pairs) assertEquals(theirs, ours)
        assertEquals(Events.STATUS_TENTATIVE, c.STATUS_TENTATIVE)
        assertEquals(Events.STATUS_CONFIRMED, c.STATUS_CONFIRMED)
        assertEquals(Events.STATUS_CANCELED, c.STATUS_CANCELED)
        assertEquals(Calendars.CAL_ACCESS_READ, FEED_ACCESS_LEVEL)
    }

    @Test
    fun `all-day as UTC midnights, recurring with a duration in days`() {
        val camp = FeedEvent(
            uid = "camp", rev = "r", start = "20261102", end = "20261104", allDay = true,
            rrule = "FREQ=WEEKLY;UNTIL=20261230", exdates = listOf("20261109"),
            overrides = listOf(FeedOverride("20261116", start = "20261117", end = "20261118", summary = "Flyttet")),
        )
        val rows = seriesRows(camp, oslo)!!
        val master = rows.rows[0].values
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), master[FeedColumns.DTSTART])
        assertEquals("P2D", master[FeedColumns.DURATION])
        assertNull(master[FeedColumns.DTEND])
        assertEquals("UTC", master[FeedColumns.TIMEZONE])
        assertEquals(1, master[FeedColumns.ALL_DAY])
        assertEquals("FREQ=WEEKLY;UNTIL=20261230", master[FeedColumns.RRULE])
        assertEquals("20261109", master[FeedColumns.EXDATE])
        val moved = rows.rows[1].values
        assertEquals(LocalDateTime.of(2026, 11, 16, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), moved[FeedColumns.ORIGINAL_INSTANCE_TIME])
        assertEquals(1, moved[FeedColumns.ORIGINAL_ALL_DAY])
        assertFalse(rows.floating)
        // A single all-day event: DTEND exclusive, no duration.
        val day = seriesRows(FeedEvent(uid = "d", rev = "r", start = "20261113", end = "20261114", allDay = true), oslo)!!.rows[0].values
        assertEquals(86_400_000L, (day[FeedColumns.DTEND] as Long) - (day[FeedColumns.DTSTART] as Long))
    }

    @Test
    fun `floating is written in the phone's zone with its local UNTIL made UTC`() {
        val e = FeedEvent(uid = "f", rev = "r", start = "20261105T090000", end = "20261105T100000", tz = null, rrule = "FREQ=WEEKLY;UNTIL=20261231T090000")
        val rows = seriesRows(e, oslo)!!
        val master = rows.rows[0].values
        assertTrue(rows.floating)
        assertEquals("Europe/Oslo", master[FeedColumns.WRITTEN_ZONE])
        assertEquals("Europe/Oslo", master[FeedColumns.TIMEZONE])
        assertEquals(ms("20261105T090000", oslo), master[FeedColumns.DTSTART])
        assertEquals("PT3600S", master[FeedColumns.DURATION])
        assertEquals("FREQ=WEEKLY;UNTIL=20261231T080000Z", master[FeedColumns.RRULE])
        // A date UNTIL in a timed series: the end of that day.
        assertEquals("FREQ=DAILY;UNTIL=20261231T225959Z", ruleWithUtcUntil("FREQ=DAILY;UNTIL=20261231", false, oslo))
        // A zoned series' UTC UNTIL stays.
        assertEquals("FREQ=DAILY;UNTIL=20261231T080000Z", ruleWithUtcUntil("FREQ=DAILY;UNTIL=20261231T080000Z", false, oslo))
    }

    @Test
    fun `an unknown zone is the phone's and counted`() {
        val rows = seriesRows(FeedEvent(uid = "u", rev = "r", start = "20261105T090000", end = "20261105T100000", tz = "Mars/Olympus"), oslo)!!
        assertTrue(rows.unknownZone && rows.floating)
        assertEquals("Europe/Oslo", rows.rows[0].values[FeedColumns.TIMEZONE])
        val utc = seriesRows(FeedEvent(uid = "z", rev = "r", start = "20261105T090000", end = "20261105T100000", tz = "UTC"), oslo)!!
        assertFalse(utc.unknownZone || utc.floating)
        assertEquals(ms("20261105T090000", ZoneOffset.UTC), utc.rows[0].values[FeedColumns.DTSTART])
        assertNull(seriesRows(FeedEvent(uid = "bad", rev = "r", start = "nope", end = "nope"), oslo))
    }

    @Test
    fun `EXDATE's fallback writes each exclusion as a cancelled override row`() {
        val e = FeedEvent(
            uid = "w", rev = "r", start = "20261102T081500", end = "20261102T091500", tz = "Europe/Oslo",
            rrule = "FREQ=WEEKLY", exdates = listOf("20261109T081500", "20261116T081500"),
            overrides = listOf(FeedOverride("20261116T081500", start = "20261116T101500", end = "20261116T111500")),
        )
        val column = seriesRows(e, oslo, exdateColumn = true)!!
        assertEquals("20261109T071500Z,20261116T071500Z", column.rows[0].values[FeedColumns.EXDATE])
        assertEquals(2, column.rows.size)
        val fallback = seriesRows(e, oslo, exdateColumn = false)!!
        assertNull(fallback.rows[0].values[FeedColumns.EXDATE])
        val cancelled = fallback.rows.drop(2).single().values
        assertEquals("w#20261109T081500", cancelled[FeedColumns.SYNC_ID])
        assertEquals(FeedColumns.STATUS_CANCELED, cancelled[FeedColumns.STATUS])
        assertEquals(ms("20261109T081500", oslo), cancelled[FeedColumns.ORIGINAL_INSTANCE_TIME])
        assertEquals(ms("20261109T091500", oslo), cancelled[FeedColumns.DTEND])
        assertTrue(EXDATE_COLUMN)
    }

    @Test
    fun `a document that doesn't read is null`() {
        assertNull(feedDocument("{"))
        assertNull(feedDocument("""{"v": 2, "id": "x", "rev": "r", "events": []}"""))
        assertEquals(0, feedDocument("""{"v": 1, "id": "x", "rev": "r", "events": [], "later": 1}""")!!.events.size)
    }

    @Test
    fun `every write and delete goes through the sync-adapter URI`() {
        val dir = File("src/main/java/com/kidslauncher/mdm/calendar").let { if (it.isDirectory) it else File("app/${it.path}") }
        val source = File(dir, "CalendarStore.kt").readText().substringAfter("// ---- calendar links (design 72")
        val writes = source.lines().filter { line ->
            listOf("newInsert(", "newDelete(", "newUpdate(", ".insert(", ".delete(", ".update(").any { it in line } && "fun " !in line
        }
        assertTrue(writes.size >= 6)
        for (line in writes) assertTrue(line, "asFeedAdapter(" in line)
        // The kid's calendar rules never pick a link's calendar: access 200, LOCAL.
        assertTrue("FEED_ACCESS_LEVEL" in source)
        // The orchestration writes nothing itself.
        assertFalse(Regex("""contentResolver|ContentProviderOperation""").containsMatchIn(File(dir, "CalendarFeeds.kt").readText()))
    }
}
