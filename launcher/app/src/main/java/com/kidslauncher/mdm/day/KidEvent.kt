package com.kidslauncher.mdm.day

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * The kid's own events (design 69 step 3, §4.5, mockup §1b): "Ny avtale" - what, the day, from, how long, an optional
 * place; no guests, no recurrence, no reminder of its own (the calendar's rule decides). Pure; KidEventTest.
 */

/** The lengths the form offers, in minutes. */
val KID_EVENT_MINUTES = listOf(15, 30, 45, 60, 90, 120, 180)
const val KID_EVENT_DEFAULT_MINUTES = 60
const val KID_EVENT_TITLE_MAX = 100
const val KID_EVENT_PLACE_MAX = 100

/** A form that can be saved: the cleaned title and place, the start and end as instants. */
data class KidEventInput(val title: String, val place: String, val beginMs: Long, val endMs: Long) {
    override fun toString() = "KidEventInput"
}

/** The form checked: a title (cleaned, at most [KID_EVENT_TITLE_MAX]), a length of [KID_EVENT_MINUTES]; `null` =
 * nothing to save yet (no title). The start is the local time on the day (a DST gap moves it forward, as `ZonedDateTime`
 * does). */
fun kidEventInput(title: String, place: String, day: LocalDate, start: LocalTime, minutes: Int, zone: ZoneId): KidEventInput? {
    val t = TitleRules.clean(title, KID_EVENT_TITLE_MAX)
    if (t.isEmpty() || minutes !in KID_EVENT_MINUTES) return null
    val begin = day.atTime(start).atZone(zone).toInstant().toEpochMilli()
    return KidEventInput(t, TitleRules.clean(place, KID_EVENT_PLACE_MAX), begin, begin + minutes * 60_000L)
}

/** The form's length for an event of [durationMs]: the offered length nearest to it (an hour for anything odd). */
fun kidEventMinutes(durationMs: Long): Int {
    val minutes = (durationMs / 60_000L).toInt()
    return KID_EVENT_MINUTES.minByOrNull { kotlin.math.abs(it - minutes) }?.takeIf { durationMs > 0 } ?: KID_EVENT_DEFAULT_MINUTES
}

/** The kid may change or delete only an event Vibb One made, in the kid's own calendar (feeds and the parent's events
 * stay untouched). */
fun kidMayEdit(uid: String?, customApp: String?, ownPackage: String, eventCalendarId: Long, kidCalendarId: Long?): Boolean =
    kidCalendarId != null && eventCalendarId == kidCalendarId && ownedByVibb(uid, customApp, ownPackage)

/** The new event's `UID_2445`: the [VIBB_UID_PREFIX] marker DAVx5 uploads, so the event is still the kid's after a
 * round trip. */
fun kidEventUid(random: String): String = VIBB_UID_PREFIX + random
