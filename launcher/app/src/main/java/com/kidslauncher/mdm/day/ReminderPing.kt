package com.kidslauncher.mdm.day

/*
 * The quiet row plus a ping (the user, 2026-10-27; PLAN "Lock-screen reminders: a third option"): with
 * `lock_reminders: "ping"` the phone plays its notification sound once as a reminder's row comes up - no notification,
 * no heads-up, nothing in the shade, never a vibration. Pure; ReminderPingTest. The Android side is
 * `calendar/ReminderPinger` (an exact alarm at the next instant from [nextPingCheck], the sound through
 * `RingtoneManager`).
 */

/** What the phone is doing as a reminder comes up, besides the parent's choice. */
data class PingQuiet(
    /** `AudioManager.RINGER_MODE_NORMAL` - silent and vibrate give no sound. */
    val ringerNormal: Boolean,
    /** No Do Not Disturb (`INTERRUPTION_FILTER_ALL`) - the parent's, bedtime's or the kid's own. */
    val interruptionsAll: Boolean,
    /** A call (or a call's ringing) is on: `AudioManager.getMode()` isn't `MODE_NORMAL`. */
    val inCall: Boolean,
    /** A time rule is in force - bedtime is one (`TimeLock.rules`, lifts and the override counted). */
    val ruleOn: Boolean,
    /** The row can be seen where it lives: the screen is off (`!PowerManager.isInteractive`, the sound makes the kid
     * look) or the lock's glance is up. While the phone is in use the sound would point at nothing (the coordinator's
     * call, QA M2). */
    val rowVisible: Boolean,
) {
    val allowsSound: Boolean get() = ringerNormal && interruptionsAll && !inCall && !ruleOn && rowVisible
}

/** A check this long after the row came up is late (a boot, a sync, the switch turned on mid-window): no sound. */
const val PING_LATE_MS = 90_000L

enum class PingSound {
    /** Nothing to do: no reminder up, the ping not chosen, or this reminder had its moment already. */
    NONE,

    /** Play the sound once, and remember the reminder. */
    PLAY,

    /** The row came up while the phone was quiet: remember the reminder, no sound (not later either). */
    QUIET,
}

data class PingDecision(val sound: PingSound, val key: String? = null)

/** One reminder: the event and its start (an event moved to another time is a new reminder). */
fun pingKey(up: GlanceReminder): String = "${up.eventId}@${up.beginMs}"

/**
 * When the row of [up] (`reminderNow`'s at [now]) came up: its reminder's start, or later the start of the last event
 * before it in the day's order whose own row hid it (two at once show the soonest); `null` = not today's.
 */
fun reminderUpSince(today: TodayCalendar, policy: CalendarPolicy, up: GlanceReminder, now: Long): Long? {
    val rows = today.view.timed
    val at = rows.indexOfFirst { it.eventId == up.eventId && it.begin == up.beginMs }
    if (at < 0) return null
    val row = rows[at]
    val start = reminderStart(row, policy.setting(row.calendarKey).remind, today.ownMinutes[row.eventId]) ?: return null
    var since = start
    for (before in rows.subList(0, at)) {
        if (!startsToday(before) || before.begin <= since || before.begin > now) continue
        if (reminderStart(before, policy.setting(before.calendarKey).remind, today.ownMinutes[before.eventId]) == null) continue
        since = before.begin
    }
    return since
}

/**
 * The ping for the reminder [up] now (`reminderNow`'s at [now], up since [upSince] - [reminderUpSince]): only with
 * `lock_reminders: "ping"`, once per reminder ([pinged] holds the keys that had their moment), and a sound only when
 * [quiet] allows it and the check isn't late ([PING_LATE_MS]) - otherwise the moment passes without one.
 */
fun reminderPing(
    up: GlanceReminder?,
    upSince: Long?,
    now: Long,
    policy: CalendarPolicy,
    quiet: PingQuiet,
    pinged: Set<String>,
): PingDecision {
    if (up == null || !policy.lockPing || !remindersOn(policy)) return PingDecision(PingSound.NONE)
    val key = pingKey(up)
    if (key in pinged) return PingDecision(PingSound.NONE)
    val onTime = upSince != null && now - upSince <= PING_LATE_MS
    return PingDecision(if (onTime && quiet.allowsSound) PingSound.PLAY else PingSound.QUIET, key)
}

/**
 * When a reminder's row may next come up after [now] (`null` = not today, or the ping not chosen): a reminder's start,
 * and an event's start while its reminder is up - the next one may surface then (two at once show the soonest).
 */
fun nextPingCheck(today: TodayCalendar, policy: CalendarPolicy, now: Long): Long? {
    if (!policy.lockPing || !remindersOn(policy)) return null
    var next: Long? = null
    for (row in today.view.timed) {
        if (!startsToday(row) || row.begin <= now) continue
        val start = reminderStart(row, policy.setting(row.calendarKey).remind, today.ownMinutes[row.eventId]) ?: continue
        val at = if (start > now) start else row.begin
        if (next == null || at < next) next = at
    }
    return next
}

/** The keys worth keeping: a reminder whose event started more than a day before [now] can't come up again. */
fun keptPings(pinged: Set<String>, now: Long): Set<String> = pinged.filterTo(LinkedHashSet()) { key ->
    key.substringAfter('@', "").toLongOrNull()?.let { it > now - 86_400_000L } == true
}
