package com.kidslauncher.mdm.day

import java.time.Instant
import java.time.ZoneId

/*
 * Dagen on the lock's glance (design 69 §5): the "Neste" line (A) and the calm reminder row (C) - pure; RemindersTest.
 * Both are timed events only, with the rules applied (a hidden event, a calendar switched off, a cancelled or
 * declined one gives nothing - the day model has dropped them already), and both need Dagen on Home. The row is
 * today's alone; the line turns to tomorrow's first event in the evening (the calmer glance, 2026-11-04).
 */

/** `CalendarContract.Reminders.MINUTES_DEFAULT`: no minutes of its own - counts as no reminder. */
const val REMINDER_MINUTES_DEFAULT = -1

/** An event's own alarms count only this many minutes before its start at most (a day-before alarm would hang a row
 * all day; Dagen shows the day). */
const val OWN_REMINDER_MAX_MINUTES = 120

/** Today and what the glance needs of it: the day as the kid sees it, the events' own reminder minutes, and - for the
 * "I morgen" line, read only while the parent's `glance_next` is on - tomorrow as the kid sees it (`null` = not read). */
data class TodayCalendar(val view: DayView, val ownMinutes: Map<Long, List<Int>>, val tomorrow: DayView? = null) {
    override fun toString() = "TodayCalendar(${view.timed.size}, ${tomorrow?.timed?.size})"
}

/** The evening starts at this local hour (the calmer glance, 2026-11-04): the "Neste" line may turn to tomorrow, and
 * the lock shows the wake-up alarm (`lock/ClockTile.kt`'s `glanceWakeAlarm`). */
const val GLANCE_EVENING_HOUR = 18

/** Whether [now] is in the evening in [zone]: from [GLANCE_EVENING_HOUR]:00 to midnight. */
fun glanceEvening(now: Long, zone: ZoneId): Boolean = Instant.ofEpochMilli(now).atZone(zone).hour >= GLANCE_EVENING_HOUR

/** The line's event: today's next ("Neste: …") or, in the evening, tomorrow's first ("I morgen: …"). */
data class GlanceNext(val row: DayRow, val tomorrow: Boolean) {
    override fun toString() = "GlanceNext(${row.eventId}, $tomorrow)"
}

/** A reminder that is up: the event's shown title and its start; [minutesLeft] counts down, 1 = "nå"; [color] the
 * calendar's dot (2026-10-27), none = the calendar glyph. */
data class GlanceReminder(val eventId: Long, val title: String, val beginMs: Long, val minutesLeft: Int, val color: CalendarColor? = null) {
    override fun toString() = "GlanceReminder($eventId)"
}

/** A row's start is today's ("Neste", a reminder): a timed row that starts today. */
internal fun startsToday(row: DayRow): Boolean = row.kind == RowKind.TIMED || row.kind == RowKind.FIRST_DAY

/**
 * When the row's reminder starts (§5.2), checked in this order: the calendar's `off` - none; `before` N - N minutes
 * before; `own` - the event's own alarms ([own], minutes before its start; [REMINDER_MINUTES_DEFAULT] counts as none),
 * the earliest of them at most [OWN_REMINDER_MAX_MINUTES] before; none - no reminder.
 */
fun reminderStart(row: DayRow, remind: Remind, own: List<Int>?): Long? = when (remind) {
    Remind.Off -> null
    is Remind.Before -> row.begin - remind.minutes * 60_000L
    Remind.Own -> own.orEmpty().filter { it in 0..OWN_REMINDER_MAX_MINUTES }.maxOrNull()?.let { row.begin - it * 60_000L }
}

/** The minutes left to [beginMs], rounded up (1 in the last minute). */
fun minutesLeft(beginMs: Long, now: Long): Int = (((beginMs - now) + 59_999) / 60_000).toInt().coerceAtLeast(1)

internal fun remindersOn(policy: CalendarPolicy): Boolean = policy.usable && policy.onHome && policy.lockReminders

/**
 * The reminder row now (C): the master switch on (`lock_reminders: "row"`) and Dagen on Home; up from its start until
 * the event's own; of two or more at once, the soonest event (ties in the day's order).
 */
fun reminderNow(today: TodayCalendar, policy: CalendarPolicy, now: Long): GlanceReminder? {
    if (!remindersOn(policy)) return null
    val row = today.view.timed.firstOrNull { row ->
        startsToday(row) && row.begin > now &&
            (reminderStart(row, policy.setting(row.calendarKey).remind, today.ownMinutes[row.eventId])?.let { it <= now } == true)
    } ?: return null
    return GlanceReminder(row.eventId, row.title, row.begin, minutesLeft(row.begin, now), row.color)
}

/**
 * The "Neste" line now (A): the switch on and Dagen on Home, the first timed event today that starts after [now];
 * ongoing and all-day events never. Once today's last timed event has started and it is evening in [zone]
 * ([glanceEvening]), tomorrow's first timed event instead ("I morgen", the calmer glance, 2026-11-04) - the same rules,
 * nothing when tomorrow has none. After midnight tomorrow is today: the line is "Neste" again.
 */
fun glanceNext(today: TodayCalendar, policy: CalendarPolicy, now: Long, zone: ZoneId): GlanceNext? {
    if (!policy.usable || !policy.onHome || !policy.glanceNext) return null
    today.view.timed.firstOrNull { startsToday(it) && it.begin > now }?.let { return GlanceNext(it, tomorrow = false) }
    if (!glanceEvening(now, zone)) return null
    return today.tomorrow?.timed?.firstOrNull { startsToday(it) }?.let { GlanceNext(it, tomorrow = true) }
}

/**
 * When what the glance shows of the calendar next changes after [now]: a reminder's start, an event's start (the
 * reminder goes, the line moves on), and while a reminder is up its next minute; with the "Neste" line on also the
 * evening's start (the line may turn to tomorrow) and the next midnight in [zone] (the day is read again); `null` =
 * nothing.
 */
fun nextReminderBoundary(today: TodayCalendar, policy: CalendarPolicy, now: Long, zone: ZoneId): Long? {
    val times = ArrayList<Long>()
    if (policy.usable && policy.onHome && policy.glanceNext) {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        times += local.toLocalDate().atTime(GLANCE_EVENING_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        times += local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    for (row in today.view.timed) {
        if (!startsToday(row) || row.begin <= now) continue
        times += row.begin
        if (remindersOn(policy)) {
            val start = reminderStart(row, policy.setting(row.calendarKey).remind, today.ownMinutes[row.eventId])
            if (start != null && start > now) times += start
        }
    }
    reminderNow(today, policy, now)?.let { up -> times += up.beginMs - (up.minutesLeft - 1) * 60_000L }
    return times.filter { it > now }.minOrNull()
}

/** The calendars whose events' own alarms are worth reading today: `own` reminders, with the master switch on. */
fun ownReminderEvents(view: DayView, policy: CalendarPolicy): List<Long> =
    if (!remindersOn(policy)) emptyList()
    else view.timed.filter { startsToday(it) && policy.setting(it.calendarKey).remind == Remind.Own }.map { it.eventId }
