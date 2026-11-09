package com.kidslauncher.mdm.day

import java.time.LocalDate

/**
 * `CalendarStore`'s kept days (an LRU of [max] days) with a generation (2026-11-09, calendar-fresh): every [clear] and
 * [forget] moves it on, and [put] stores only under the generation its read began with - so a read that started before
 * a provider change can't store its stale rows after the change has dropped the days. Thread-safe; pure; DayRowsCacheTest.
 */
class DayRowsCache<V>(private val max: Int) {
    private var generation = 0L
    private val days = object : LinkedHashMap<LocalDate, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, V>?) = size > max
    }

    /** Taken before the provider is asked; handed back to [put]. */
    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun get(day: LocalDate): V? = days[day]

    /** Keeps [rows] for [day] only when nothing was dropped since [readAt] was taken; whether it was kept. */
    @Synchronized
    fun put(day: LocalDate, readAt: Long, rows: V): Boolean {
        if (readAt != generation) return false
        days[day] = rows
        return true
    }

    /** The provider changed: every day goes, and every read in flight stores nothing. */
    @Synchronized
    fun clear() {
        generation++
        days.clear()
    }

    /** These days are read afresh at their next read (the glance's tap); a read in flight stores nothing. */
    @Synchronized
    fun forget(vararg forgotten: LocalDate) {
        generation++
        for (day in forgotten) days.remove(day)
    }
}
