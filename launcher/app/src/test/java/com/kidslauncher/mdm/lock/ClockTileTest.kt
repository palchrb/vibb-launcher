package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 42 §1.3-1.4: the timer's title parsed and counted down, the alarm line (pure, lock/ClockTile.kt). */
class ClockTileTest {
    @Test
    fun `the title is the remaining time - else shown as is`() {
        assertEquals(672L, clockSeconds("11:12"))
        assertEquals(3900L, clockSeconds("1:05:00"))
        assertEquals(9L, clockSeconds("0:09"))
        for (garbage in listOf("", "Timer", "11:2", "1:60", "12:34:56:78", "-0:09", "11 :12")) assertNull(garbage, clockSeconds(garbage))
        val parsed = ClockTile.parse("11:12", "1 timer er i gang")
        assertEquals(ClockTile(672, "11:12", "1 timer er i gang"), parsed)
        val text = ClockTile.parse("Pasta ‮er klar", null)
        assertNull(text.remainingS)
        assertEquals("not a time: the cleaned title", "Pasta er klar", text.title)
        assertFalse("redacted", text.toString().contains("Pasta"))
    }

    @Test
    fun `the time counts down locally between the app's updates, which correct it, and stops at 0`() {
        val tile = ClockTile(672, "11:12", null)
        assertEquals(672L, clockRemainingNow(tile, receivedAtMs = 10_000, nowMs = 10_999))
        assertEquals(671L, clockRemainingNow(tile, 10_000, 11_000))
        assertEquals("11:07", clockTileTime(tile, 10_000, 15_500))
        // The app's next update (late by a second here) is the truth again.
        assertEquals(670L, clockRemainingNow(ClockTile(670, "11:10", null), 12_000, 12_000))
        assertEquals(0L, clockRemainingNow(tile, 10_000, 10_000 + 700_000))
        assertFalse("stops at 0:00", clockTicks(tile, 10_000, 10_000 + 700_000, shown = true))
        assertTrue(clockTicks(tile, 10_000, 11_000, shown = true))
        assertFalse("not on the page", clockTicks(tile, 10_000, 11_000, shown = false))
        assertEquals("no time: the title as is", "Pasta", clockTileTime(ClockTile(null, "Pasta", null), 0, 5_000))
        assertFalse(clockTicks(ClockTile(null, "Pasta", null), 0, 5_000, shown = true))
    }

    @Test
    fun `only the pinned clock app's ongoing notification is read`() {
        assertTrue(clockCandidate(ClockApp.PACKAGE, verified = true, ongoing = true))
        assertFalse("a wrong signer", clockCandidate(ClockApp.PACKAGE, verified = false, ongoing = true))
        assertFalse("another package", clockCandidate("com.google.android.deskclock", verified = true, ongoing = true))
        assertFalse("not a running timer", clockCandidate(ClockApp.PACKAGE, verified = true, ongoing = false))
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
