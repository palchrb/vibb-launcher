package com.kidslauncher.mdm.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
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
    fun `an event's length is the nearest offered one`() {
        assertEquals(120, kidEventMinutes(2 * 3_600_000L))
        assertEquals(90, kidEventMinutes(80 * 60_000L))
        assertEquals(15, kidEventMinutes(60_000L))
        assertEquals(KID_EVENT_DEFAULT_MINUTES, kidEventMinutes(0))
    }

    @Test
    fun `only Vibb One's events in the kid's calendar may be changed`() {
        val own = "me.vibb.launcher"
        assertTrue(kidMayEdit("vibb-1", null, own, 5, 5))
        assertTrue(kidMayEdit(null, "me.vibb.launcher.debug", own, 5, 5))
        assertFalse("another calendar", kidMayEdit("vibb-1", null, own, 6, 5))
        assertFalse("no kid's calendar", kidMayEdit("vibb-1", null, own, 5, null))
        assertFalse("the parent's event", kidMayEdit("abc@nextcloud", "at.bitfire.davdroid", own, 5, 5))
        assertEquals("vibb-abc", kidEventUid("abc"))
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
        assertEquals(4, Regex("""contentResolver\.(insert|update|delete)\(""").findAll(store).count())
    }
}
