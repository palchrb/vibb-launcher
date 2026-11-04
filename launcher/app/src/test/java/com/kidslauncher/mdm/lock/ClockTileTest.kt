package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 42 §1.3-1.4: Fossify Clock's three notification shapes, the time moved locally, the ended
 * timer's one broadcast action, the alarm line (pure, lock/ClockTile.kt). */
class ClockTileTest {
    @Test
    fun `only the pinned clock app's three channels are read (qa-r6-b-code M1)`() {
        val pkg = ClockApp.PACKAGE
        assertEquals(ClockKind.TIMER, clockKind(pkg, verified = true, channelId = "simple_alarm_timer"))
        assertEquals(ClockKind.STOPWATCH, clockKind(pkg, verified = true, channelId = "simple_alarm_stopwatch"))
        assertEquals(ClockKind.ENDED, clockKind(pkg, verified = true, channelId = "simple_timer_channel_content://alarm_1729400000000"))
        // A ringing alarm, an upcoming-alarm or any other channel, no channel: nothing.
        for (channel in listOf("Alarm_Channel", "upcoming_alarms", "simple_alarm_timerX", "simple_timer", null)) {
            assertNull("$channel", clockKind(pkg, verified = true, channelId = channel))
        }
        assertNull("a wrong signer", clockKind(pkg, verified = false, channelId = "simple_alarm_timer"))
        assertNull("the right channel from another package", clockKind("com.example.clock", verified = true, channelId = "simple_alarm_timer"))
        // Several at once: an ended timer first, then a running one, then the stopwatch.
        val timer = ClockTile.parse(ClockKind.TIMER, "11:12", "1 timer er i gang")
        val stopwatch = ClockTile.parse(ClockKind.STOPWATCH, "30:55", "Stoppeklokke")
        val ended = ClockTile.parse(ClockKind.ENDED, "Timer", "Tiden er ute")
        assertEquals(ended, clockShown(listOf(stopwatch, ended, timer)))
        assertEquals(timer, clockShown(listOf(stopwatch, timer)))
        assertNull(clockShown(emptyList()))
    }

    @Test
    fun `a blank tile never shows - nor outranks one with content (the empty Timer card, 2026-10-21)`() {
        // The system's autogroup summary on a timer channel: no title, no text.
        val blankTimer = ClockTile.parse(ClockKind.TIMER, null, null)
        val blankEnded = ClockTile.parse(ClockKind.ENDED, " ", "⁠\n")
        assertTrue(blankTimer.blank)
        assertTrue("whitespace and invisible marks clean to nothing", blankEnded.blank)
        for (shape in listOf(ClockTile.parse(ClockKind.TIMER, "Pasta", null), ClockTile.parse(ClockKind.ENDED, null, "Tiden er ute"), ClockTile.parse(ClockKind.TIMER, "0:09", null))) {
            assertFalse("$shape", shape.blank)
        }
        val stopwatch = ClockTile.parse(ClockKind.STOPWATCH, "30:55", "Stoppeklokke")
        assertEquals(stopwatch, clockShown(listOf(blankEnded, blankTimer, stopwatch)))
        assertNull("alone it shows nothing", clockShown(listOf(blankTimer)))
    }

    @Test
    fun `the kept keys the listener no longer has are stale`() {
        assertEquals(setOf("timer"), clockStale(listOf("timer", "stopwatch"), listOf("stopwatch")))
        assertEquals(emptySet<String>(), clockStale(listOf("stopwatch"), listOf("stopwatch", "other")))
        assertEquals(setOf("a", "b"), clockStale(listOf("a", "b"), emptyList()))
    }

    @Test
    fun `the small line - not the ended timer's, not a repeat of the label`() {
        assertEquals("1 timer er i gang", clockSmallText(ClockTile.parse(ClockKind.TIMER, "11:12", "1 timer er i gang"), "Timer"))
        // The stopwatch's "Stoppeklokke" under our "Stoppeklokke": once is enough, case and spacing aside.
        for (text in listOf("Stoppeklokke", "stoppeklokke", "  STOPPEKLOKKE ")) {
            assertNull(text, clockSmallText(ClockTile.parse(ClockKind.STOPWATCH, "30:55", text), "Stoppeklokke"))
        }
        assertNull(clockSmallText(ClockTile.parse(ClockKind.STOPWATCH, "1:02", "Stopwatch"), "stopwatch"))
        assertEquals("Lap 2", clockSmallText(ClockTile.parse(ClockKind.STOPWATCH, "1:02", "Lap 2"), "Stoppeklokke"))
        assertNull("the ended timer's text is its big line", clockSmallText(ClockTile.parse(ClockKind.ENDED, "Timer", "Tiden er ute"), "Timer"))
        assertNull(clockSmallText(ClockTile.parse(ClockKind.TIMER, "11:12", null), "Timer"))
    }

    @Test
    fun `the title is the time - else shown as is - and an ended timer shows its text`() {
        assertEquals(672L, clockSeconds("11:12"))
        assertEquals(3900L, clockSeconds("1:05:00"))
        assertEquals(9L, clockSeconds("0:09"))
        for (garbage in listOf("", "Timer", "11:2", "1:60", "12:34:56:78", "-0:09", "11 :12")) assertNull(garbage, clockSeconds(garbage))
        assertEquals(ClockTile(ClockKind.TIMER, 672, "11:12", "1 timer er i gang"), ClockTile.parse(ClockKind.TIMER, "11:12", "1 timer er i gang"))
        val text = ClockTile.parse(ClockKind.TIMER, "Pasta ‮er klar", null)
        assertNull(text.seconds)
        assertEquals("not a time: the cleaned title", "Pasta er klar", text.title)
        assertFalse("redacted", text.toString().contains("Pasta"))
        // The timer's end: a label such as "7:30" is no time; the big line is "Tiden er ute".
        val ended = ClockTile.parse(ClockKind.ENDED, "7:30", "Tiden er ute")
        assertNull(ended.seconds)
        assertEquals("Tiden er ute", clockTileTime(ended, 0, 99_000))
        assertFalse(clockTicks(ended, 0, 1_000, shown = true))
    }

    @Test
    fun `a timer counts down and stops at 0, the stopwatch counts up - each update corrects it`() {
        val tile = ClockTile(ClockKind.TIMER, 672, "11:12", null)
        assertEquals(672L, clockSecondsNow(tile, receivedAtMs = 10_000, nowMs = 10_999))
        assertEquals(671L, clockSecondsNow(tile, 10_000, 11_000))
        assertEquals("11:07", clockTileTime(tile, 10_000, 15_500))
        assertEquals("the next update is the truth", 670L, clockSecondsNow(ClockTile(ClockKind.TIMER, 670, "11:10", null), 12_000, 12_000))
        assertEquals(0L, clockSecondsNow(tile, 10_000, 10_000 + 700_000))
        assertFalse("stops at 0:00", clockTicks(tile, 10_000, 10_000 + 700_000, shown = true))
        assertTrue(clockTicks(tile, 10_000, 11_000, shown = true))
        assertFalse("not on the page", clockTicks(tile, 10_000, 11_000, shown = false))
        val stopwatch = ClockTile(ClockKind.STOPWATCH, 1705, "28:25", "Stoppeklokke")
        assertEquals("28:30", clockTileTime(stopwatch, 0, 5_000))
        assertTrue("it never ends", clockTicks(stopwatch, 0, 10_000_000, shown = true))
        assertEquals("no time: the title as is", "Pasta", clockTileTime(ClockTile(ClockKind.TIMER, null, "Pasta", null), 0, 5_000))
    }

    @Test
    fun `Stopp is the ended timer's one broadcast action - never an activity`() {
        val broadcast = ClockAction(isActivity = false, isBroadcast = true)
        assertEquals(0, clockStopAction(ClockKind.ENDED, listOf(broadcast)))
        assertNull("an activity", clockStopAction(ClockKind.ENDED, listOf(ClockAction(isActivity = true, isBroadcast = false))))
        assertNull("a service", clockStopAction(ClockKind.ENDED, listOf(ClockAction(isActivity = false, isBroadcast = false))))
        assertNull("two actions", clockStopAction(ClockKind.ENDED, listOf(broadcast, broadcast)))
        assertNull("none", clockStopAction(ClockKind.ENDED, emptyList()))
        assertNull("a running timer has no button", clockStopAction(ClockKind.TIMER, listOf(broadcast)))
        assertNull(clockStopAction(ClockKind.STOPWATCH, listOf(broadcast)))
    }

    @Test
    fun `the alarm line - a clock app's next alarm, with the weekday past 24 h`() {
        val now = 1_000_000_000L
        val clocks = lockClockPackages("com.android.deskclock", clockAppVerified = true)
        assertEquals(setOf("com.android.deskclock", ClockApp.PACKAGE), clocks)
        assertEquals(GlanceAlarm(now + 3_600_000, withWeekday = false), glanceAlarm(now + 3_600_000, ClockApp.PACKAGE, clocks, now))
        assertEquals(GlanceAlarm(now + ALARM_WEEKDAY_AFTER_MS + 1, withWeekday = true), glanceAlarm(now + ALARM_WEEKDAY_AFTER_MS + 1, "com.android.deskclock", clocks, now))
        assertNull("another app's setAlarmClock", glanceAlarm(now + 3_600_000, "com.example.reminders", clocks, now))
        assertNull("Fossify without its signer", glanceAlarm(now + 3_600_000, ClockApp.PACKAGE, lockClockPackages(null, clockAppVerified = false), now))
        assertNull("none", glanceAlarm(null, null, clocks, now))
        assertNull("past", glanceAlarm(now, ClockApp.PACKAGE, clocks, now))
    }

    /** The calmer glance (2026-11-04, board D): only the evening's wake-up alarm - from 18:00, ringing before noon the
     * next day - and on the date line only when it fits. */
    @Test
    fun `the wake-up alarm - from 18_00, before noon the next day, through midnight, in the phone's zone`() {
        val oslo = java.time.ZoneId.of("Europe/Oslo")
        fun at(day: Int, h: Int, m: Int = 0) = java.time.ZonedDateTime.of(2026, 11, day, h, m, 0, 0, oslo).toInstant().toEpochMilli()
        fun alarm(ms: Long) = GlanceAlarm(ms, withWeekday = false)
        val seven = alarm(at(5, 7))
        // 17:59 nothing, 18:00 the alarm.
        assertNull(glanceWakeAlarm(seven, at(4, 17, 59), oslo))
        assertEquals(seven, glanceWakeAlarm(seven, at(4, 18), oslo))
        // Before noon the next day: 11:59 yes, 12:00 never (a midday alarm).
        assertEquals(alarm(at(5, 11, 59)), glanceWakeAlarm(alarm(at(5, 11, 59)), at(4, 20), oslo))
        assertNull(glanceWakeAlarm(alarm(at(5, 12)), at(4, 20), oslo))
        // During the day nothing - not even an alarm this afternoon (the user's "Alarm 13:35").
        assertNull(glanceWakeAlarm(alarm(at(4, 13, 35)), at(4, 9), oslo))
        assertNull(glanceWakeAlarm(seven, at(4, 12), oslo))
        assertNull(glanceWakeAlarm(seven, at(4, 15), oslo))
        // Through midnight until it rings: 23:59, 00:00, 06:59.
        assertEquals(seven, glanceWakeAlarm(seven, at(4, 23, 59), oslo))
        assertEquals(seven, glanceWakeAlarm(seven, at(5, 0), oslo))
        assertEquals(seven, glanceWakeAlarm(seven, at(5, 6, 59), oslo))
        // After midnight the cut is still that noon: an alarm at noon or one a day later never.
        assertNull(glanceWakeAlarm(alarm(at(5, 12)), at(5, 0, 30), oslo))
        assertNull(glanceWakeAlarm(alarm(at(6, 7)), at(5, 0, 30), oslo))
        assertNull(glanceWakeAlarm(alarm(at(6, 7)), at(4, 20), oslo))
        // The zone: 18:00 in Oslo is 17:00 UTC - the same instant is not evening there.
        assertNull(glanceWakeAlarm(seven, at(4, 18), java.time.ZoneId.of("UTC")))
        assertNull(glanceWakeAlarm(null, at(4, 20), oslo))
        // The date line holds it, or it takes the line under the date.
        assertTrue(glanceAlarmBeside(288f, 200f, 88f))
        assertFalse(glanceAlarmBeside(288f, 200f, 88.5f))
    }
}

/** Design 42 §1.1: the clock app is known by its package and its release signer. */
class ClockAppTest {
    @Test
    fun `installed means the pinned signer among the package's signers`() {
        assertEquals("org.fossify.clock", ClockApp.PACKAGE)
        assertTrue(Regex("[0-9a-f]{64}").matches(ClockApp.SIGNER_SHA256))
        assertTrue(clockAppVerified(setOf(ClockApp.SIGNER_SHA256)))
        assertTrue("case and other lineage entries", clockAppVerified(setOf("ab".repeat(32), ClockApp.SIGNER_SHA256.uppercase())))
        assertFalse("another key", clockAppVerified(setOf("ab".repeat(32))))
        assertFalse("not installed or unreadable", clockAppVerified(null))
        assertFalse(clockAppVerified(emptySet()))
    }
}
