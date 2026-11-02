package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Design 69 step 3: the kid's own event - the form's input, its length, who may change it, and that only the store's
 * three guarded writes touch the provider. */
class KidEventTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val day = LocalDate.of(2026, 10, 31)

    @Test
    fun `the form - a title, the day and time, a length it offers`() {
        val input = kidEventInput("  Besøk hos Ola ", "Hos Ola", day, LocalTime.of(14, 0), 120, oslo)!!
        assertEquals("Besøk hos Ola", input.title)
        assertEquals("Hos Ola", input.place)
        assertEquals(ZonedDateTime.of(2026, 10, 31, 14, 0, 0, 0, oslo).toInstant().toEpochMilli(), input.beginMs)
        assertEquals(2 * 3_600_000L, input.endMs - input.beginMs)
        assertNull(kidEventInput("   ", "", day, LocalTime.NOON, 60, oslo))
        assertNull(kidEventInput("Tur", "", day, LocalTime.NOON, 50, oslo))
        assertEquals(KID_EVENT_TITLE_MAX, kidEventInput("x".repeat(300), "", day, LocalTime.NOON, 60, oslo)!!.title.length)
        // A start in the spring's DST gap moves forward, as ZonedDateTime does.
        val gap = kidEventInput("Natt", "", LocalDate.of(2027, 3, 28), LocalTime.of(2, 30), 30, oslo)!!
        assertEquals(ZonedDateTime.of(2027, 3, 28, 3, 30, 0, 0, oslo).toInstant().toEpochMilli(), gap.beginMs)
    }

    @Test
    fun `a change writes the title and place only when the kid changed them (72b R-L5, QA L6)`() {
        // The parent's 150-character title came back; the field shows its first 100. The kid moves only the time.
        val parents = "Foreldremøte og dugnad: " + "x".repeat(126)
        val shown = parents.take(KID_EVENT_TITLE_MAX)
        val moved = kidEventInput(shown, "Skolen", day, LocalTime.of(18, 0), 60, oslo, titleShown = shown, placeShown = "Skolen")!!
        val columns = kidEventUpdateColumns(moved, "Europe/Oslo")
        assertFalse(FeedColumns.TITLE in columns)
        assertTrue(moved.timesChanged)
        assertFalse(FeedColumns.LOCATION in columns)
        assertEquals(moved.beginMs, columns[FeedColumns.DTSTART])
        // A handed-back all-day event changed in the form becomes a timed one, in the phone's zone.
        assertEquals(0, columns[FeedColumns.ALL_DAY])
        assertEquals("Europe/Oslo", columns[FeedColumns.TIMEZONE])
        // A field the kid changed goes in, cleaned and cut as ever.
        val renamed = kidEventInput("Dugnad  ", "Skolen", day, LocalTime.of(18, 0), 60, oslo, titleShown = shown, placeShown = "Skolen")!!
        assertEquals("Dugnad", kidEventUpdateColumns(renamed, "Europe/Oslo")[FeedColumns.TITLE])
        assertFalse(FeedColumns.LOCATION in kidEventUpdateColumns(renamed, "Europe/Oslo"))
        // A new event writes both.
        val fresh = kidEventInput("Tur", "", day, LocalTime.NOON, 60, oslo)!!
        assertTrue(fresh.titleChanged && fresh.placeChanged && fresh.timesChanged)
        // A handed-back all-day event whose place alone changed keeps its day: no time column (launcher QA M1).
        val shownTimes = KidEventTimes(day, LocalTime.of(1, 0), 180)
        val place = kidEventInput("Skidag", "Tryvann", day, LocalTime.of(1, 0), 180, oslo, titleShown = "Skidag", placeShown = "", timesShown = shownTimes)!!
        assertEquals(setOf(FeedColumns.LOCATION), kidEventUpdateColumns(place, "Europe/Oslo").keys)
        // The kid moves it: the times go in whole, timed.
        val later = kidEventInput("Skidag", "", day, LocalTime.of(9, 0), 180, oslo, titleShown = "Skidag", placeShown = "", timesShown = shownTimes)!!
        assertEquals(setOf(FeedColumns.DTSTART, FeedColumns.DTEND, FeedColumns.ALL_DAY, FeedColumns.TIMEZONE), kidEventUpdateColumns(later, "Europe/Oslo").keys)
        // Nothing changed: nothing written.
        assertTrue(kidEventUpdateColumns(kidEventInput("Skidag", "", day, LocalTime.of(1, 0), 180, oslo, "Skidag", "", shownTimes)!!, "Europe/Oslo").isEmpty())
    }

    @Test
    fun `an event's length is the nearest offered one`() {
        assertEquals(120, kidEventMinutes(2 * 3_600_000L))
        assertEquals(90, kidEventMinutes(80 * 60_000L))
        assertEquals(15, kidEventMinutes(60_000L))
        assertEquals(KID_EVENT_DEFAULT_MINUTES, kidEventMinutes(0))
    }

    @Test
    fun `only this phone's Vibb One events in the kid's calendar may be changed`() {
        val own = "me.vibb.launcher"
        val tag = phoneTag("a1b2c3d4e5f60718")
        val uid = kidEventUid(tag, "0f9e8d7c-1111-2222-3333-444455556666")
        assertEquals("vibb-$tag-0f9e8d7c-1111-2222-3333-444455556666", uid)
        assertTrue(kidMayEdit(uid, null, own, tag, 5, 5))
        assertTrue("written here before the tag", kidMayEdit("vibb-1", "me.vibb.launcher.debug", own, tag, 5, 5))
        assertFalse("another calendar", kidMayEdit(uid, null, own, tag, 6, 5))
        assertFalse("no kid's calendar", kidMayEdit(uid, null, own, tag, 5, null))
        assertFalse("the parent's event", kidMayEdit("abc@nextcloud", "at.bitfire.davdroid", own, tag, 5, 5))
        // QA L1: a sibling's phone in the same shared calendar has another tag.
        val sibling = kidEventUid(phoneTag("ffeeddccbbaa9988"), "0f9e8d7c-1111-2222-3333-444455556666")
        assertFalse("a sibling's event", kidMayEdit(sibling, null, own, tag, 5, 5))
    }

    @Test
    fun `the phone tag - 8 hex of a hash, the same every time, never the id`() {
        val tag = phoneTag("a1b2c3d4e5f60718")
        assertTrue(tag, Regex("[0-9a-f]{8}").matches(tag))
        assertEquals(tag, phoneTag("a1b2c3d4e5f60718"))
        assertTrue(tag != phoneTag("a1b2c3d4e5f60719"))
        // SHA-256("vibb-calendar-uid:" + id), its first four bytes as hex: the format calendar.md documents.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("vibb-calendar-uid:a1b2c3d4e5f60718".toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        assertEquals(expected, tag)
    }

    @Test
    fun `a new event starts at the next hour, never in the past (nit 2)`() {
        val now = LocalDateTime.of(2026, 10, 31, 14, 20)
        assertEquals(LocalDateTime.of(2026, 10, 31, 15, 0), kidEventDefaultStart(day, now))
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), kidEventDefaultStart(day, LocalDateTime.of(2026, 10, 31, 23, 10)))
        assertEquals(LocalDateTime.of(2026, 11, 5, 15, 0), kidEventDefaultStart(LocalDate.of(2026, 11, 5), now))
    }

    @Test
    fun `the kid's calendar - the pick, else the phone's own, and only when shown (nit 9)`() {
        val picked = CalendarInfo(1, "c04e7b19a2f6d853", "bitfire.at.davdroid", "Familie", accessLevel = 700)
        val readOnly = CalendarInfo(2, "9f3a61c2d84be705", "bitfire.at.davdroid", "Skole", accessLevel = 200)
        val local = CalendarInfo(3, "51d8e0aa3b7c9462", "LOCAL", "Min kalender", accessLevel = 700, vibbLocal = true)
        val all = listOf(picked, readOnly, local)
        fun policy(calendars: String) =
            calendarPolicy(ServerJson.parseToJsonElement("""{"kid_adds": true, "calendars": [$calendars], "rules": []}"""))
        val pick = { key: String, shown: Boolean -> """{"key": "$key", "shown": $shown, "writable_for_kid": true}""" }
        assertEquals(1L, kidCalendarOf(policy(pick(picked.key, true)), all, 500)?.id)
        assertNull("read-only", kidCalendarOf(policy(pick(readOnly.key, true)), all, 500))
        assertEquals(3L, kidCalendarOf(policy(""), all, 500)?.id)
        assertNull("the pick hidden", kidCalendarOf(policy(pick(picked.key, false)), all, 500))
        val localHidden = """{"key": "${local.key}", "shown": false}"""
        assertNull("the phone's own hidden", kidCalendarOf(policy(localHidden), all, 500))
        assertNull("no + at all", kidCalendarOf(CalendarPolicy.OLDER_SERVER, all, 500))
    }

    @Test
    fun `the provider is written only by the store's guarded writes`() {
        val src = listOf("src/main/java/com/kidslauncher/mdm", "app/src/main/java/com/kidslauncher/mdm").map(::File).first { it.isDirectory }
        val writers = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
            val text = f.readText()
            Regex("""contentResolver\.(insert|update|delete)\(""").containsMatchIn(text) &&
                (text.contains("CalendarContract") || text.contains("Events.CONTENT_URI"))
        }.map { it.name }.toList()
        assertEquals(listOf("CalendarStore.kt"), writers)
        val store = File(src, "calendar/CalendarStore.kt").readText()
        // The kid's change and delete each check the event is still theirs first.
        for (fn in listOf("updateKidEventBlocking", "deleteKidEventBlocking")) {
            val body = store.substringAfter("fun $fn(").substringBefore("\n    }\n")
            assertTrue(fn, body.contains("kidMayEditBlocking(context, eventId)"))
        }
        // The kid's own: the local calendar and the three guarded writes; design 72's link calendars below their own
        // marker (`FeedEventsTest` checks those go through the sync-adapter URI).
        val kid = store.substringBefore("// ---- the kid's events written to a CalDAV calendar")
        assertTrue(kid.length < store.length)
        assertEquals(4, Regex("""contentResolver\.(insert|update|delete)\(""").findAll(kid).count())
        // Design 72b's hand-backs: three writes, each against the row as it is now - live, this phone's, no second row
        // with the uid.
        val back = store.substringAfter("// ---- the kid's events written to a CalDAV calendar").substringBefore("// ---- calendar links (design 72")
        assertEquals(3, Regex("""contentResolver\.(insert|update|delete)\(""").findAll(back).count())
        val apply = back.substringAfter("fun applyKidBackBlocking(").substringBefore("\n    }\n")
        assertEquals(2, Regex("""if \(ours\(op\.eventId\) == null\) continue""").findAll(apply).count())
        assertTrue(apply.contains("if (rows.any { !it.deleted && it.uid == op.uid }) continue"))
    }
}
