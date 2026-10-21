package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.badges.cleanGlanceText
import com.kidslauncher.mdm.music.clockText

/*
 * The clock on the lock (design 42, docs/design/42-lock-clock.md at the monorepo root): pure,
 * ClockTileTest. Fossify Clock (FossifyOrg/Clock) posts, by channel:
 * - `simple_alarm_timer`: a running timer, ongoing, title = the remaining time ("11:12", each second),
 *   text "1 timer er i gang";
 * - `simple_alarm_stopwatch`: a running stopwatch, title = the elapsed time, text "Stoppeklokke";
 * - `simple_timer_channel_<sound>_<millis>`: the timer's end (App.onMessageEvent(TimerEvent.Finish)),
 *   a notification only - no activity, no full-screen intent - insistent alarm sound, title the
 *   timer's label or "Timer", text "Tiden er ute", one action "Dismiss", a broadcast to its
 *   HideTimerReceiver;
 * - `Alarm_Channel`: an alarm (setAlarmClock, a full-screen intent - the lock's alarm exemption).
 * The lock parses the strings (never renders the app's views), counts the time locally between the
 * updates and shows the app's next alarm under the date.
 */

/** A title at most this long is read; the text is cleaned like a glance row's. */
const val CLOCK_TITLE_MAX = 20
const val CLOCK_TEXT_MAX = 60

const val CLOCK_CHANNEL_TIMER = "simple_alarm_timer"
const val CLOCK_CHANNEL_STOPWATCH = "simple_alarm_stopwatch"
const val CLOCK_CHANNEL_TIMER_ENDED_PREFIX = "simple_timer_channel_"

/** What the clock app's notification is: a running timer (counts down), a stopwatch (counts up), a timer that ended. */
enum class ClockKind { TIMER, STOPWATCH, ENDED }

/**
 * The listener's filter (§1.4, §2, qa-r6-b-code M1): only [ClockApp.PACKAGE] with the pinned
 * signer ([verified]), and only the three channels above; an alarm, another channel or another
 * package is never read.
 */
fun clockKind(packageName: String, verified: Boolean, channelId: String?): ClockKind? {
    if (packageName != ClockApp.PACKAGE || !verified || channelId == null) return null
    return when {
        channelId == CLOCK_CHANNEL_TIMER -> ClockKind.TIMER
        channelId == CLOCK_CHANNEL_STOPWATCH -> ClockKind.STOPWATCH
        channelId.startsWith(CLOCK_CHANNEL_TIMER_ENDED_PREFIX) -> ClockKind.ENDED
        else -> null
    }
}

/**
 * With several, the tile shows the one that matters most: an ended timer, then a running one, then
 * the stopwatch. A [ClockTile.blank] one is never shown (the empty "Timer" card, 2026-10-21): it
 * can't outrank a tile with content, and alone it shows nothing.
 */
fun clockShown(tiles: Collection<ClockTile>): ClockTile? = tiles.filterNot { it.blank }.minByOrNull { clockRank(it.kind) }

/** The kept keys the listener no longer has active - dropped, so a removal the reader missed can't
 * keep a tile up (2026-10-21: a stale timer entry hid the stopwatch until the next sweep). */
fun clockStale(kept: Collection<String>, active: Collection<String>): Set<String> = kept.toSet() - active.toSet()

private fun clockRank(kind: ClockKind): Int = when (kind) {
    ClockKind.ENDED -> 0
    ClockKind.TIMER -> 1
    ClockKind.STOPWATCH -> 2
}

/**
 * What the tile shows (§1.4): [seconds] parsed from the title (`H:MM:SS`, `MM:SS`, `M:SS`; null =
 * not a time - then [title], cleaned, is shown as is; an ended timer shows its [text]), [text] the
 * cleaned notification text. The redacted [toString] names nothing the app wrote.
 */
data class ClockTile(val kind: ClockKind, val seconds: Long?, val title: String?, val text: String?) {
    /** Nothing to show - no time, no title, no text (the system's autogroup summary on a timer
     * channel has this shape): the reader keeps none, [clockShown] shows none. */
    val blank: Boolean get() = seconds == null && title == null && text == null

    override fun toString() = "ClockTile($kind, time=${seconds != null})"

    companion object {
        fun parse(kind: ClockKind, title: CharSequence?, text: CharSequence?): ClockTile {
            val cleanTitle = cleanGlanceText(title, CLOCK_TITLE_MAX)
            val time = if (kind == ClockKind.ENDED) null else cleanTitle?.let(::clockSeconds)
            return ClockTile(kind, time, cleanTitle, cleanGlanceText(text, CLOCK_TEXT_MAX))
        }
    }
}

private val CLOCK_TIME = Regex("""^(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)$""")

/** "11:12" = 672, "1:05:00" = 3900, "0:09" = 9; anything else null. */
fun clockSeconds(s: String): Long? {
    val m = CLOCK_TIME.matchEntire(s.trim()) ?: return null
    val (h, min, sec) = m.destructured
    return (h.toLongOrNull() ?: 0) * 3600 + min.toLong() * 60 + sec.toLong()
}

/**
 * The time shown now (§1.4): the last parsed value moved locally since it arrived
 * ([receivedAtMs], elapsed realtime) - down for a timer (never below 0), up for a stopwatch - a
 * smoothing between the app's own updates, which correct it. Null when the title isn't a time.
 */
fun clockSecondsNow(tile: ClockTile, receivedAtMs: Long, nowMs: Long): Long? {
    val at = tile.seconds ?: return null
    val passed = ((nowMs - receivedAtMs).coerceAtLeast(0)) / 1000
    return if (tile.kind == ClockKind.STOPWATCH) at + passed else (at - passed).coerceAtLeast(0)
}

/** The tile's big line: the moved time ("11:07", "1:05:00"); an ended timer its text ("Tiden er
 * ute"); else the title as is. */
fun clockTileTime(tile: ClockTile, receivedAtMs: Long, nowMs: Long): String? = when {
    tile.kind == ClockKind.ENDED -> tile.text ?: tile.title
    else -> clockSecondsNow(tile, receivedAtMs, nowMs)?.let { clockText(it * 1000) } ?: tile.title
}

/**
 * The tile's small line under the time: a running timer's or the stopwatch's text ("1 timer er i
 * gang"), never an ended timer's (its text is the big line), and none when it only repeats our own
 * [label] (the stopwatch's "Stoppeklokke" under our "Stoppeklokke"; case and spacing aside).
 */
fun clockSmallText(tile: ClockTile, label: String): String? =
    tile.text?.takeIf { tile.kind != ClockKind.ENDED && clockWords(it) != clockWords(label) }

private fun clockWords(s: String): String = s.trim().replace(Regex("""\s+"""), " ").lowercase()

/** The local tick runs only while the tile shows on the page: a stopwatch always, a timer above 0. */
fun clockTicks(tile: ClockTile?, receivedAtMs: Long, nowMs: Long, shown: Boolean): Boolean {
    if (!shown || tile == null) return false
    val now = clockSecondsNow(tile, receivedAtMs, nowMs) ?: return false
    return tile.kind == ClockKind.STOPWATCH || now > 0
}

/** An action of the notification as the lock judges it: its intent's kind only. */
data class ClockAction(val isActivity: Boolean, val isBroadcast: Boolean)

/**
 * The ended timer's "Stopp" (design 42, the coordinator's decision of 2026-10-20 after Fossify's
 * source): offered only for [ClockKind.ENDED] with exactly one action, and only when that action is
 * a broadcast - never an activity (the design QA's 42-2 rule: nothing opens over the lock). Returns
 * its index, else null.
 */
fun clockStopAction(kind: ClockKind, actions: List<ClockAction>): Int? =
    0.takeIf { kind == ClockKind.ENDED && actions.size == 1 && actions[0].isBroadcast && !actions[0].isActivity }

/** The next alarm under the date (§1.3): its time, and the weekday too when it is more than 24 h away. */
data class GlanceAlarm(val atMs: Long, val withWeekday: Boolean)

const val ALARM_WEEKDAY_AFTER_MS = 24 * 60 * 60_000L

/**
 * `AlarmManager.nextAlarmClock` ([triggerMs], set by [creator]) as the line under the date: only
 * from one of [clockPackages] (the system clock app, Fossify with its signer) and only ahead of
 * [nowMs]; none otherwise.
 */
fun glanceAlarm(triggerMs: Long?, creator: String?, clockPackages: Set<String>, nowMs: Long): GlanceAlarm? {
    if (triggerMs == null || creator == null || creator !in clockPackages || triggerMs <= nowMs) return null
    return GlanceAlarm(triggerMs, triggerMs - nowMs > ALARM_WEEKDAY_AFTER_MS)
}

/**
 * The clock apps whose alarms show over the lock and under the date (§1.2): the system clock app
 * (FLAG_SYSTEM, as before) and Fossify Clock only while its pinned signer is verified.
 */
fun lockClockPackages(systemClock: String?, clockAppVerified: Boolean): Set<String> =
    setOfNotNull(systemClock, ClockApp.PACKAGE.takeIf { clockAppVerified })
