package com.kidslauncher.mdm.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** 2026-11-09 (calendar-fresh): the kept days' generation - a read begun before a provider change never stores its
 * stale rows after the change dropped the days; the glance's tap forgets today and tomorrow; the LRU stays. */
class DayRowsCacheTest {
    private val today = LocalDate.of(2026, 11, 9)

    @Test
    fun `a read begun before a change stores nothing after it, the next read does`() {
        val cache = DayRowsCache<String>(9)
        assertTrue(cache.put(today, cache.generation(), "before"))
        // A read begins (a miss on another day, the provider asked) ...
        val readAt = cache.generation()
        // ... the provider changes and the debounced drop runs ...
        cache.clear()
        assertNull(cache.get(today))
        // ... and the read, done, may not keep its rows from before the change.
        assertFalse(cache.put(today, readAt, "stale"))
        assertNull(cache.get(today))
        // A read begun after the change keeps its rows.
        assertTrue(cache.put(today, cache.generation(), "fresh"))
        assertEquals("fresh", cache.get(today))
    }

    @Test
    fun `the tap forgets only the days asked, and a read in flight stores nothing`() {
        val cache = DayRowsCache<String>(9)
        val tomorrow = today.plusDays(1)
        val later = today.plusDays(5)
        for (day in listOf(today, tomorrow, later)) cache.put(day, cache.generation(), "$day")
        val readAt = cache.generation()
        cache.forget(today, tomorrow)
        assertNull(cache.get(today))
        assertNull(cache.get(tomorrow))
        assertEquals("$later", cache.get(later))
        assertFalse(cache.put(today, readAt, "stale"))
    }

    @Test
    fun `the least recently read day goes past the limit`() {
        val cache = DayRowsCache<Int>(2)
        cache.put(today, cache.generation(), 0)
        cache.put(today.plusDays(1), cache.generation(), 1)
        cache.get(today)
        cache.put(today.plusDays(2), cache.generation(), 2)
        assertEquals(0, cache.get(today))
        assertNull(cache.get(today.plusDays(1)))
        assertEquals(2, cache.get(today.plusDays(2)))
    }

    @Test
    fun `the store takes the generation before it asks the provider`() {
        val store = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/calendar/CalendarStore.kt") }
            .first { it.exists() }.readText()
        val body = store.substringAfter("fun rowsBlocking(").substringBefore("\n    }\n")
        assertTrue(body.indexOf("rows.generation()") in 0 until body.indexOf("contentResolver.query("))
        assertTrue(body.contains("rows.put(day, readAt, out)"))
    }
}
