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
        assertEquals(ClockKind.ENDED, clockShown(listOf(ClockKind.STOPWATCH, ClockKind.ENDED, ClockKind.TIMER)))
        assertEquals(ClockKind.TIMER, clockShown(listOf(ClockKind.STOPWATCH, ClockKind.TIMER)))
        assertNull(clockShown(emptyList()))
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
