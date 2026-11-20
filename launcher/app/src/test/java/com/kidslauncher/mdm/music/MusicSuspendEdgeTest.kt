package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Design 54 R2/R3: `suspend_at_s`, the first instant a time rule suspends the music app ([musicSuspendEdgeS]). */
class MusicSuspendEdgeTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val music = "me.vibb.music"

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, oslo).toInstant().toEpochMilli()
    private fun s(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = (ms(y, mo, d, h, mi) / 1000).toInt()

    /** Monday 2026-11-23, 12:00 in Oslo. */
    private val now = ms(2026, 11, 23, 12)

    private fun everyDay(start: Int, end: Int) = List(7) { TimeWindow(start * 60, end * 60) }
    private fun rule(id: Long, days: List<TimeWindow?>, exempt: List<String> = emptyList(), untilMs: Long? = null) =
        TimeRule(id = id, exemptApps = exempt, days = days, untilMs = untilMs)
    private fun policy(vararg rules: TimeRule) = TimePolicy(rules = rules.toList(), dailyBudgetMinutes = List(7) { null })

    private fun edge(policy: TimePolicy?, nowMs: Long = now, lifts: List<TimedLift> = emptyList(), overrideEndMs: Long = 0) =
        musicSuspendEdgeS(policy, nowMs, oslo, music, { ruleLiftsAt(lifts, it) }, overrideEndMs, lifts.map { Instant.ofEpochMilli(it.untilMs) })

    @Test
    fun `a rule that keeps the music app gives no edge, one that doesn't gives its start`() {
        assertEquals(0, edge(policy(rule(1, everyDay(21, 7), listOf(music)))))
        assertEquals(s(2026, 11, 23, 21), edge(policy(rule(1, everyDay(21, 7), listOf("other.app")))))
        assertEquals("no policy", 0, edge(null))
        assertEquals("no rules", 0, edge(policy()))
    }

    @Test
    fun `two rules at once keep only the apps both keep`() {
        val evening = rule(1, everyDay(18, 22), listOf(music))
        assertEquals(s(2026, 11, 23, 20), edge(policy(evening, rule(2, everyDay(20, 23), listOf("other.app")))))
        assertEquals("both keep it", 0, edge(policy(evening, rule(2, everyDay(20, 23), listOf(music, "other.app")))))
    }

    @Test
    fun `a lift that ends inside the rule - the edge is its end`() {
        val school = policy(rule(7, everyDay(10, 16)))
        assertEquals(s(2026, 11, 23, 14), edge(school, lifts = listOf(TimedLift(7, ms(2026, 11, 23, 14)))))
        assertEquals("a lift of every rule", s(2026, 11, 23, 14), edge(school, lifts = listOf(TimedLift(null, ms(2026, 11, 23, 14)))))
        assertEquals("another rule's lift", 0, edge(school, lifts = listOf(TimedLift(8, ms(2026, 11, 23, 14)))))
    }

    @Test
    fun `the override ending inside a rule - the edge is its end`() {
        assertEquals(s(2026, 11, 23, 13, 30), edge(policy(rule(7, everyDay(10, 16))), overrideEndMs = ms(2026, 11, 23, 13, 30)))
    }

    @Test
    fun `a rule turned on now is gone from its until_ms (design 93)`() {
        val monday = listOf(TimeWindow(20 * 60, 22 * 60)) + List<TimeWindow?>(6) { null }
        assertEquals("gone before its window", 0, edge(policy(rule(-3, monday, untilMs = ms(2026, 11, 23, 13)))))
        assertEquals(s(2026, 11, 23, 20), edge(policy(rule(-3, monday, untilMs = ms(2026, 11, 23, 23)))))
    }

    @Test
    fun `a DST day - a start in the spring gap moves later`() {
        // Sunday 2027-03-28, Oslo: 02:00 -> 03:00. A rule from 02:30 starts at 03:30 CEST.
        val sunday = List<TimeWindow?>(6) { null } + TimeWindow(2 * 60 + 30, 6 * 60)
        assertEquals(
            Instant.parse("2027-03-28T01:30:00Z").epochSecond.toInt(),
            edge(policy(rule(1, sunday)), nowMs = ms(2027, 3, 27, 12)),
        )
    }

    @Test
    fun `the screen-time budget never gives an edge (R1_11)`() {
        assertEquals(0, edge(TimePolicy(dailyBudgetMinutes = List(7) { 0 })))
        assertEquals(0, edge(TimePolicy(rules = listOf(rule(1, everyDay(21, 7), listOf(music))), dailyBudgetMinutes = List(7) { 0 })))
    }

    @Test
    fun `nothing within 8 days or 64 boundaries is 0`() {
        val bedtime = policy(rule(9, everyDay(21, 7)))
        assertEquals(0, edge(bedtime, lifts = listOf(TimedLift(null, now + 9 * 24 * 3_600_000L))))
        assertEquals(s(2026, 11, 25, 21), edge(bedtime, lifts = listOf(TimedLift(null, now + 2 * 24 * 3_600_000L))))
        // 40 short rules keeping the music app: 80 boundaries before 20:00.
        val short = (1..40).map { i -> rule(100L + i, List(7) { TimeWindow(12 * 60 + 2 * i, 12 * 60 + 2 * i + 1) }, listOf(music)) }
        val evening = rule(1, everyDay(20, 22))
        assertEquals(s(2026, 11, 23, 20), edge(policy(evening)))
        assertEquals(0, edge(TimePolicy(rules = short + evening, dailyBudgetMinutes = List(7) { null })))
    }

    @Test
    fun `already suspended now is 0`() {
        assertEquals(0, edge(policy(rule(7, everyDay(10, 16)))))
    }
}
