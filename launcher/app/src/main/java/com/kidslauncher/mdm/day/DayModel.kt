package com.kidslauncher.mdm.day

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * Dagen's day (design 69 §2.4-2.5): the calendar provider's instances of one day as the kid's list - pure, no
 * Android imports, DayModelTest. CalendarStore reads the provider; this decides what shows.
 */

/** `Events.STATUS_CANCELED`. */
const val STATUS_CANCELED = 2

/** `Attendees.ATTENDEE_STATUS_DECLINED`. */
const val ATTENDEE_DECLINED = 2

/** The kid's own events carry this prefix on `Events.UID_2445` (design 69 §4.5), then the phone's tag
 * ([kidEventUid]): it survives a CalDAV round trip. */
const val VIBB_UID_PREFIX = "vibb-"

/** One provider instance, as read (`Instances`). [title], [location] and [description] are the feed's raw text. */
data class InstanceRow(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String?,
    val location: String?,
    val description: String?,
    val calendarId: Long,
    val status: Int? = null,
    val selfStatus: Int? = null,
    val uid: String? = null,
    val customApp: String? = null,
) {
    override fun toString() = "InstanceRow($eventId)"
}

/** One provider calendar (`Calendars`): its row id, key ([calendarKey]), display name and access level. */
data class CalendarInfo(
    val id: Long,
    val key: String,
    val accountType: String,
    val displayName: String,
    val syncEvents: Boolean = true,
    val accessLevel: Int = 0,
    /** The phone's own "Min kalender" that Vibb One made (`ACCOUNT_TYPE_LOCAL`, our NAME). */
    val vibbLocal: Boolean = false,
    /** The provider's `Calendars.NAME` (an ICSx5 subscription's URL, a CalDAV path), for the report's `source`. */
    val name: String? = null,
) {
    override fun toString() = "CalendarInfo($key)"
}

/** How a row sits in its day: an all-day row, a timed one, or a multi-day timed event's first or last day. */
enum class RowKind { ALL_DAY, TIMED, FIRST_DAY, LAST_DAY }

/** One row of the day, rules applied. [title] is the shown title; [location] and [description] are cleaned. */
data class DayRow(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val kind: RowKind,
    val title: String,
    val location: String,
    val description: String,
    val calendarId: Long,
    val calendarKey: String,
    val calendarName: String,
    val ongoing: Boolean,
    /** The kid's own event (step 3): only these may be changed or deleted, and only in the kid's calendar. */
    val ownedByVibb: Boolean,
    /** The provider's all-day event (UTC-midnight based, §2.4) - not a multi-day timed event's middle day. */
    val allDayEvent: Boolean = false,
) {
    val timed: Boolean get() = kind != RowKind.ALL_DAY

    override fun toString() = "DayRow($eventId, $kind)"
}

data class DayView(val allDay: List<DayRow>, val timed: List<DayRow>) {
    val empty: Boolean get() = allDay.isEmpty() && timed.isEmpty()

    companion object {
        val EMPTY = DayView(emptyList(), emptyList())
    }
}

/** The day an event begins on, for its page's header: an all-day one's UTC date (QA L4), a timed one's local date. */
fun eventDay(begin: Long, allDay: Boolean, zone: ZoneId): LocalDate = instanceDays(begin, begin, allDay, zone).first

/** The dates an instance is on (§2.4): an all-day one on its UTC dates from BEGIN to END - 1 ms, a timed one on the
 * local dates; a zero-length one on BEGIN's date. */
fun instanceDays(begin: Long, end: Long, allDay: Boolean, zone: ZoneId): Pair<LocalDate, LocalDate> {
    val z: ZoneId = if (allDay) ZoneOffset.UTC else zone
    val first = Instant.ofEpochMilli(begin).atZone(z).toLocalDate()
    val last = if (end > begin) Instant.ofEpochMilli(end - 1).atZone(z).toLocalDate() else first
    return first to last
}

/** The calendar is shown: the provider syncs it and the parent didn't untick it. */
fun calendarShown(calendar: CalendarInfo?, policy: CalendarPolicy): Boolean =
    calendar != null && calendar.syncEvents && policy.setting(calendar.key).shown

/** An event the kid made in Vibb One on this phone: a `vibb-<this phone's tag>-` UID, or a Vibb UID of any shape
 * that this phone's Vibb One wrote (`CUSTOM_APP_PACKAGE`, which a CalDAV sync doesn't carry) - a sibling's event in a
 * shared calendar is not the kid's (QA L1). */
fun ownedByVibb(uid: String?, customApp: String?, ownPackage: String, phoneTag: String): Boolean {
    if (uid == null || !uid.startsWith(VIBB_UID_PREFIX)) return false
    if (uid.startsWith("$VIBB_UID_PREFIX$phoneTag-")) return true
    return customApp != null && customApp.removeSuffix(".debug") == ownPackage.removeSuffix(".debug")
}

/**
 * The day (§2.5): out go cancelled and declined instances, calendars not shown and events a rule hides (by title or
 * description); a multi-day timed event is a timed row on its first and last day and an all-day row between; two
 * copies with the same shown title, begin, end and location are one row. All-day rows by title, timed rows by begin,
 * end, title, event id. Ongoing = `BEGIN <= now < END`, timed rows only.
 */
fun dayView(
    rows: List<InstanceRow>,
    calendars: Map<Long, CalendarInfo>,
    policy: CalendarPolicy,
    day: LocalDate,
    zone: ZoneId,
    now: Long,
    ownPackage: String,
    phoneTag: String,
): DayView {
    val allDay = ArrayList<DayRow>()
    val timed = ArrayList<DayRow>()
    val seen = HashSet<List<Any>>()
    val sorted = rows.sortedWith(compareBy<InstanceRow> { it.begin }.thenBy { it.end }.thenBy { it.eventId })
    for (row in sorted) {
        if (row.status == STATUS_CANCELED || row.selfStatus == ATTENDEE_DECLINED) continue
        val calendar = calendars[row.calendarId]
        if (calendar == null || !calendarShown(calendar, policy)) continue
        val (first, last) = instanceDays(row.begin, row.end, row.allDay, zone)
        if (day < first || day > last) continue
        val title = TitleRules.apply(policy.rules, calendar.key, row.title, row.description) ?: continue
        val location = TitleRules.clean(row.location, TITLE_MAX)
        if (!seen.add(listOf(title, row.begin, row.end, location))) continue
        val kind = when {
            row.allDay -> RowKind.ALL_DAY
            first == last -> RowKind.TIMED
            day == first -> RowKind.FIRST_DAY
            day == last -> RowKind.LAST_DAY
            else -> RowKind.ALL_DAY
        }
        val out = DayRow(
            eventId = row.eventId,
            begin = row.begin,
            end = row.end,
            kind = kind,
            title = title,
            location = location,
            description = TitleRules.clean(row.description, DESCRIPTION_MAX),
            calendarId = row.calendarId,
            calendarKey = calendar.key,
            calendarName = calendar.displayName,
            ongoing = kind != RowKind.ALL_DAY && row.begin <= now && now < row.end,
            ownedByVibb = ownedByVibb(row.uid, row.customApp, ownPackage, phoneTag),
            allDayEvent = row.allDay,
        )
        if (kind == RowKind.ALL_DAY) allDay += out else timed += out
    }
    allDay.sortWith(compareBy<DayRow> { it.title.lowercase() }.thenBy { it.eventId })
    timed.sortWith(compareBy<DayRow> { it.begin }.thenBy { it.end }.thenBy { it.title.lowercase() }.thenBy { it.eventId })
    return DayView(allDay, timed)
}

/** The provider's Julian day of a date (`Time.getJulianDay` for a whole local day). */
fun julianDay(day: LocalDate): Int = (day.toEpochDay() + 2440588).toInt()
