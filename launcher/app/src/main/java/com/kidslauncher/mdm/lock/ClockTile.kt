package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.badges.cleanGlanceText
import com.kidslauncher.mdm.music.clockText

/*
 * The clock on the lock (design 42, docs/design/42-lock-clock.md at the monorepo root): pure,
 * ClockTileTest. Fossify Clock's running timer posts an ongoing notification whose title is the
 * remaining time ("11:12", updated each second) and whose text is "1 timer er i gang"; the lock
 * parses the title (never renders the app's views), counts down locally between the updates and
 * shows the app's next alarm under the date.
 */

/** A title at most this long is read; the text is cleaned like a glance row's. */
const val CLOCK_TITLE_MAX = 20
const val CLOCK_TEXT_MAX = 60

/**
 * What the timer tile shows (§1.4): [remainingS] parsed from the title (`H:MM:SS`, `MM:SS`, `M:SS`;
 * null = not a time - then [title], cleaned, is shown as is), [text] the cleaned notification text.
 * The redacted [toString] names nothing the app wrote.
 */
data class ClockTile(val remainingS: Long?, val title: String?, val text: String?) {
    override fun toString() = "ClockTile(time=${remainingS != null})"

    companion object {
        fun parse(title: CharSequence?, text: CharSequence?): ClockTile {
            val cleanTitle = cleanGlanceText(title, CLOCK_TITLE_MAX)
            return ClockTile(cleanTitle?.let(::clockSeconds), cleanTitle, cleanGlanceText(text, CLOCK_TEXT_MAX))
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
 * The time shown now (§1.4): the last parsed value counted down locally since it arrived
 * ([receivedAtMs], elapsed realtime) - a smoothing between the app's own updates, which correct it -
 * never below 0. Null when the title isn't a time.
 */
fun clockRemainingNow(tile: ClockTile, receivedAtMs: Long, nowMs: Long): Long? {
    val remaining = tile.remainingS ?: return null
    val passed = ((nowMs - receivedAtMs).coerceAtLeast(0)) / 1000
    return (remaining - passed).coerceAtLeast(0)
}

/** The tile's big line: the counted-down time ("11:12", "1:05:00"), else the title as is. */
fun clockTileTime(tile: ClockTile, receivedAtMs: Long, nowMs: Long): String? =
    clockRemainingNow(tile, receivedAtMs, nowMs)?.let { clockText(it * 1000) } ?: tile.title

/** The local countdown ticks only while there is a time above 0 and the tile shows on the page. */
fun clockTicks(tile: ClockTile?, receivedAtMs: Long, nowMs: Long, shown: Boolean): Boolean =
    shown && tile != null && (clockRemainingNow(tile, receivedAtMs, nowMs) ?: 0) > 0

/**
 * The listener's filter (§1.4, §2): only [ClockApp.PACKAGE] with the pinned signer ([verified]) and
 * only its ongoing notifications - a timer. Another package, or the right one with a wrong signer,
 * is never read.
 */
fun clockCandidate(packageName: String, verified: Boolean, ongoing: Boolean): Boolean =
    packageName == ClockApp.PACKAGE && verified && ongoing

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
