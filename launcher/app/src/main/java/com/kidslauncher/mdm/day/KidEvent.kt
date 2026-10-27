package com.kidslauncher.mdm.day

import java.time.LocalDate
import java.time.LocalDateTime
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

/** The kid's calendar among [calendars]: the parent's pick when the phone has it and may write to it (access at least
 * [contributor]), else the phone's own "Min kalender"; `null` when the kid may add nothing or that calendar isn't
 * shown - a calendar the parent hid takes no new event the kid then can't see (QA nit 9). */
fun kidCalendarOf(policy: CalendarPolicy, calendars: List<CalendarInfo>, contributor: Int): CalendarInfo? {
    if (!policy.usable || !policy.kidAdds) return null
    val key = policy.kidCalendarKey
    return calendars.firstOrNull {
        (if (key != null) it.key == key && it.accessLevel >= contributor else it.vibbLocal) && calendarShown(it, policy)
    }
}

/** A new event's start: the next whole hour; on today past 23:00 that is tomorrow's midnight, never a time gone by
 * (QA nit 2). Another shown day keeps its day. */
fun kidEventDefaultStart(day: LocalDate, now: LocalDateTime): LocalDateTime {
    val next = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
    return if (day == now.toLocalDate()) next else day.atTime(next.toLocalTime())
}

/** The form's length for an event of [durationMs]: the offered length nearest to it (an hour for anything odd). */
fun kidEventMinutes(durationMs: Long): Int {
    val minutes = (durationMs / 60_000L).toInt()
    return KID_EVENT_MINUTES.minByOrNull { kotlin.math.abs(it - minutes) }?.takeIf { durationMs > 0 } ?: KID_EVENT_DEFAULT_MINUTES
}

/** The kid may change or delete only an event this phone's Vibb One made ([ownedByVibb]), in the kid's own calendar
 * (feeds, the parent's events and a sibling's in a shared calendar stay untouched). */
fun kidMayEdit(
    uid: String?,
    customApp: String?,
    ownPackage: String,
    phoneTag: String,
    eventCalendarId: Long,
    kidCalendarId: Long?,
): Boolean = kidCalendarId != null && eventCalendarId == kidCalendarId && ownedByVibb(uid, customApp, ownPackage, phoneTag)

/** The new event's `UID_2445`, `vibb-<phone tag>-<random>`: the marker DAVx5 uploads, so the event is still this
 * phone's after a round trip (QA L1: a sibling's phone has another tag). */
fun kidEventUid(phoneTag: String, random: String): String = "$VIBB_UID_PREFIX$phoneTag-$random"

/** This phone's tag in its events' UIDs: the first 8 hex of SHA-256 over a domain prefix and the phone's stable id
 * (`Settings.Secure.ANDROID_ID`, or an id kept at install) - the id itself never leaves the phone. */
fun phoneTag(stableId: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("vibb-calendar-uid:$stableId".toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
