package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.LockReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeRulesTest {

    /** 2026-10-05 is a Monday. */
    private val monday: LocalDate = LocalDate.of(2026, 10, 5)

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): LocalDateTime =
        monday.plusDays(dayOffset.toLong()).atTime(hour, minute)

    private fun w(h1: Int, m1: Int, h2: Int, m2: Int) = TimeWindow(h1 * 60 + m1, h2 * 60 + m2)

    private val weekdays = { window: TimeWindow -> List(5) { window } + listOf(null, null) }

    private val school = TimeRule(1, "Skole", KIND_SCHOOL, callsAllowed = false, exemptApps = listOf("cal", "vibb"),
        days = weekdays(w(8, 15, 14, 0)))
    private val bedtime = TimeRule(2, "Leggetid", KIND_BEDTIME, callsAllowed = true, exemptApps = listOf("vibb"),
        days = List(7) { w(21, 0, 7, 0) })
    private val custom = TimeRule(3, "Middag", "something-new", callsAllowed = true, days = List(7) { w(17, 0, 18, 0) })

    private fun policy(vararg rules: TimeRule, budget: List<Int?> = List(7) { null }) =
        TimePolicy(rules = rules.toList(), dailyBudgetMinutes = budget)

    // Active-rule resolution

    @Test
    fun `a same-day window is active from start, inclusive, to end, exclusive`() {
        assertFalse(isActive(school, at(0, 8, 14)))
        assertTrue(isActive(school, at(0, 8, 15)))
        assertTrue(isActive(school, at(0, 13, 59)))
        assertFalse(isActive(school, at(0, 14, 0)))
        assertFalse("not on Saturday", isActive(school, at(5, 9)))
    }

    @Test
    fun `an overnight window runs into the next day, also across the week`() {
        assertTrue(isActive(bedtime, at(0, 23)))
        assertTrue(isActive(bedtime, at(1, 3)))
        assertFalse(isActive(bedtime, at(1, 7)))
        // Sunday's window ends Monday morning.
        val sundayOnly = TimeRule(days = List(6) { null } + listOf(w(22, 0, 6, 0)))
        assertTrue(isActive(sundayOnly, at(0, 5, 59)))
        assertFalse(isActive(sundayOnly, at(0, 6)))
        assertFalse("Monday's own night is off", isActive(sundayOnly, at(0, 23)))
        // Friday's window covers Saturday morning even though Saturday has none.
        val fridayNight = TimeRule(days = List(4) { null } + listOf(w(20, 0, 8, 0), null, null))
        assertTrue(isActive(fridayNight, at(5, 7)))
    }

    @Test
    fun `start equal to end is 24 hours from start`() {
        val allDay = TimeRule(days = listOf(TimeWindow(0, 0)) + List(6) { null })
        assertTrue(isActive(allDay, at(0, 0)))
        assertTrue(isActive(allDay, at(0, 23, 59)))
        assertFalse(isActive(allDay, at(1, 0)))
        val noonToNoon = TimeRule(days = listOf(TimeWindow(720, 720)) + List(6) { null })
        assertTrue(isActive(noonToNoon, at(1, 11, 59)))
        assertFalse(isActive(noonToNoon, at(1, 12)))
    }

    @Test
    fun `malformed rules fail closed`() {
        val noDays = TimeRule(days = emptyList())
        assertTrue("days.size != 7 is always active", isActive(noDays, at(3, 12)))
        val badWindow = TimeRule(days = listOf(TimeWindow(-5, 1440)) + List(6) { null })
        assertTrue("out-of-range window covers its whole day", isActive(badWindow, at(0, 0)))
        assertTrue(isActive(badWindow, at(0, 23, 59)))
        assertFalse(isActive(badWindow, at(1, 12)))
        assertEquals("unknown kind is custom", LockReason.RULE, decideTimeLock(policy(custom), at(0, 17, 30), false).reason)
        assertEquals("a broken budget is 0", 0, budgetMinutesFor(TimePolicy(dailyBudgetMinutes = listOf(60)), monday))
        assertEquals(0, budgetMinutesFor(policy(budget = List(7) { -5 }), monday))
    }

    // The lock decision

    @Test
    fun `school blocks calls and leaves only its exempt apps`() {
        val lock = decideTimeLock(policy(school, bedtime), at(0, 9), false)
        assertEquals(LockReason.SCHOOL, lock.reason)
        assertTrue(lock.callsBlocked)
        assertEquals(setOf("cal", "vibb"), lock.usableApps)
        assertEquals(at(0, 14), lock.until)
    }

    @Test
    fun `bedtime keeps calls, and overlapping rules intersect exemptions and AND calls`() {
        val lock = decideTimeLock(policy(bedtime), at(0, 22), false)
        assertEquals(LockReason.BEDTIME, lock.reason)
        assertFalse(lock.callsBlocked)
        assertEquals(setOf("vibb"), lock.usableApps)
        assertEquals(at(1, 7), lock.until)

        val lateSchool = school.copy(days = List(7) { w(20, 0, 23, 0) })
        val both = decideTimeLock(policy(bedtime, lateSchool), at(0, 22), false)
        assertEquals("school is the strongest kind", LockReason.SCHOOL, both.reason)
        assertEquals(listOf(lateSchool, bedtime), both.rules)
        assertTrue(both.callsBlocked)
        assertEquals(setOf("vibb"), both.usableApps)
    }

    @Test
    fun `override, pause and lifts end the rule`() {
        val p = policy(school)
        assertEquals(UNLOCKED, decideTimeLock(p, at(0, 9), overrideActive = true))
        assertEquals(UNLOCKED, decideTimeLock(p, at(0, 9), false, RuleLifts(ruleIds = setOf(1))))
        assertEquals(UNLOCKED, decideTimeLock(p, at(0, 9), false, RuleLifts(all = true)))
        assertEquals(LockReason.SCHOOL, decideTimeLock(p, at(0, 9), false, RuleLifts(ruleIds = setOf(99))).reason)
        assertFalse(callsBlockedAt(p, at(0, 9), false, RuleLifts(all = true)))
        assertTrue(callsBlockedAt(p, at(0, 9), false, RuleLifts()))
        assertFalse(callsBlockedAt(null, at(0, 9), false, RuleLifts()))
    }

    @Test
    fun `a used-up budget locks with calls and messaging apps, a rule keeps its own exemptions`() {
        val p = policy(bedtime, budget = List(7) { 60 })
        val used = BudgetUse(usedMs = 60 * 60_000L, budgetMinutes = 60, extraMinutes = 0)
        val lock = decideTimeLock(p, at(0, 12), false, budget = used, messagingApps = setOf("element"))
        assertEquals(LockReason.SCREEN_TIME, lock.reason)
        assertFalse(lock.callsBlocked)
        assertEquals(setOf("element"), lock.usableApps)
        assertTrue(lock.budgetExhausted)

        val night = decideTimeLock(p, at(0, 22), false, budget = used, messagingApps = setOf("element"))
        assertEquals(LockReason.BEDTIME, night.reason)
        assertTrue(night.budgetExhausted)
        assertEquals(setOf("vibb"), night.usableApps)

        val left = BudgetUse(usedMs = 59 * 60_000L, budgetMinutes = 60, extraMinutes = 0)
        assertEquals(UNLOCKED, decideTimeLock(p, at(0, 12), false, budget = left))
        assertEquals(60_000L, left.remainingMs)
        val extra = used.copy(extraMinutes = 30)
        assertFalse(extra.exhausted)
        assertEquals(90, extra.effectiveMinutes)
        assertEquals(UNLOCKED, decideTimeLock(p, at(0, 12), true, budget = used))
        val unlimited = BudgetUse(usedMs = 10 * 3_600_000L, budgetMinutes = null, extraMinutes = 0)
        assertFalse(unlimited.exhausted)
        assertNull(unlimited.remainingMs)
    }

    @Test
    fun `the budget is per weekday`() {
        val p = policy(budget = listOf(60, 60, 60, 60, 60, 120, null))
        assertEquals(60, budgetMinutesFor(p, monday))
        assertEquals(120, budgetMinutesFor(p, monday.plusDays(5)))
        assertNull(budgetMinutesFor(p, monday.plusDays(6)))
        assertTrue(hasBudget(p))
        assertFalse(hasBudget(NO_TIME_RULES))
    }

    @Test
    fun `the lock key changes with the rule, usable apps and calls`() {
        val a = decideTimeLock(policy(school), at(0, 9), false)
        val b = decideTimeLock(policy(school.copy(exemptApps = listOf("cal"))), at(0, 9), false)
        val c = decideTimeLock(policy(school.copy(callsAllowed = true)), at(0, 9), false)
        assertNotEquals(a.key(), b.key())
        assertNotEquals(a.key(), c.key())
        assertEquals("NONE", UNLOCKED.key())
    }

    // Next boundary

    private val oslo: ZoneId = ZoneId.of("Europe/Oslo")

    private fun instant(local: LocalDateTime, zone: ZoneId = oslo): Instant = local.atZone(zone).toInstant()

    @Test
    fun `the next boundary is the nearest rule edge`() {
        val p = policy(school, bedtime)
        assertEquals(instant(at(0, 8, 15)), nextBoundary(p, instant(at(0, 7, 30)), oslo))
        assertEquals(instant(at(0, 14)), nextBoundary(p, instant(at(0, 8, 15)), oslo))
        assertEquals(instant(at(0, 21)), nextBoundary(p, instant(at(0, 14)), oslo))
        assertEquals("an overnight end", instant(at(1, 7)), nextBoundary(p, instant(at(0, 23)), oslo))
        assertEquals("Friday school -> Friday bedtime", instant(at(4, 21)), nextBoundary(p, instant(at(4, 15)), oslo))
        assertNull(nextBoundary(policy(), instant(at(0, 12)), oslo))
        assertNull(nextBoundary(null, instant(at(0, 12)), oslo))
    }

    @Test
    fun `midnight is a boundary with a budget, and lift expiries are too`() {
        val withBudget = policy(budget = List(7) { 60 })
        assertEquals(instant(at(1, 0)), nextBoundary(withBudget, instant(at(0, 23)), oslo))
        val lift = instant(at(0, 9, 30))
        assertEquals(lift, nextBoundary(policy(school), instant(at(0, 9)), oslo, listOf(lift, instant(at(0, 8)))))
    }

    @Test
    fun `daylight saving time`() {
        // 2026-03-29 02:00 -> 03:00 in Oslo. A rule starting 02:30 starts when the clock says 03:30
        // (the gap moves later), a 22:00-07:00 night is an hour shorter but still ends at 07:00.
        val spring = LocalDate.of(2026, 3, 29)
        val dayIndex = spring.dayOfWeek.value - 1
        val gapRule = TimeRule(days = List(7) { if (it == dayIndex) w(2, 30, 5, 0) else null })
        val beforeGap = ZonedDateTime.of(spring.atTime(1, 0), oslo).toInstant()
        assertEquals(ZonedDateTime.of(spring.atTime(3, 30), oslo).toInstant(), nextBoundary(policy(gapRule), beforeGap, oslo))
        val night = TimeRule(days = List(7) { w(22, 0, 7, 0) })
        val saturdayNight = ZonedDateTime.of(spring.minusDays(1).atTime(23, 0), oslo).toInstant()
        assertEquals(ZonedDateTime.of(spring.atTime(7, 0), oslo).toInstant(), nextBoundary(policy(night), saturdayNight, oslo))
        assertTrue(isActive(night, localAt(ZonedDateTime.of(spring.atTime(3, 30), oslo).toInstant(), oslo)))

        // 2026-10-25 03:00 -> 02:00. A rule ending 02:30 ends at the first 02:30 (earlier offset);
        // both 02:15s are inside it as local times.
        val autumn = LocalDate.of(2026, 10, 25)
        val autumnRule = TimeRule(days = List(7) { w(1, 0, 2, 30) })
        val firstOneAm = ZonedDateTime.ofLocal(autumn.atTime(1, 30), oslo, null).toInstant()
        val end = nextBoundary(policy(autumnRule), firstOneAm, oslo)!!
        assertEquals(ZonedDateTime.ofLocal(autumn.atTime(2, 30), oslo, java.time.ZoneOffset.ofHours(2)).toInstant(), end)
        val secondQuarterPast = ZonedDateTime.ofLocal(autumn.atTime(2, 15), oslo, java.time.ZoneOffset.ofHours(1)).toInstant()
        assertTrue(isActive(autumnRule, localAt(secondQuarterPast, oslo)))
    }

    // Boot copy for the call path

    @Test
    fun `the boot copy holds only the no-calls windows and fails closed`() {
        val blocks = bootCallBlocksOf(policy(school, bedtime))
        assertEquals(1, blocks.rules.size)
        assertTrue(blocks.blockedAt(at(0, 9)))
        assertFalse(blocks.blockedAt(at(0, 22)))
        val json = encodeBootCallBlocks(blocks)
        assertFalse("no names in DE", json.contains("Skole"))
        assertFalse("no apps in DE", json.contains("vibb"))
        assertEquals(blocks, decodeBootCallBlocks(json))
        assertNull(decodeBootCallBlocks(null))
        assertNull(decodeBootCallBlocks("{"))
        assertNull("another version", decodeBootCallBlocks("""{"v":2,"rules":[]}"""))
        assertNull("no version", decodeBootCallBlocks("""{"rules":[]}"""))
        assertFalse(decodeBootCallBlocks(encodeBootCallBlocks(bootCallBlocksOf(null)))!!.blockedAt(at(0, 9)))
    }

    // An older server's fixed windows

    /** The pre-step-6 KidModeEnforcer.evaluate, kept here as the oracle: true = locked. */
    private fun legacyLocked(
        t: LocalDateTime,
        ws: Int?, we: Int?, es: Int?, ee: Int?, bs: Int?, be: Int?,
    ): Boolean {
        val m = t.hour * 60 + t.minute
        fun inWindow(s: Int, e: Int) = if (s < e) m in s until e else m >= s || m < e
        if (bs != null && be != null && bs != be && inWindow(bs, be)) return true
        val weekend = t.dayOfWeek.value >= 6
        val (s, e) = if (weekend) es to ee else ws to we
        if (s == null || e == null || s == e) return false
        return !inWindow(s, e)
    }

    @Test
    fun `converted legacy windows lock exactly when the old schedule did, minute by minute`() {
        val configs = listOf(
            listOf(7 * 60, 20 * 60, 8 * 60, 21 * 60, 21 * 60, 7 * 60),
            listOf(7 * 60, 20 * 60, 8 * 60, 21 * 60, null, null),
            listOf(null, null, null, null, 21 * 60, 7 * 60),
            listOf(9 * 60, 19 * 60, null, null, null, null),
            listOf(null, null, 10 * 60, 18 * 60, 22 * 60, 6 * 60),
            listOf(22 * 60, 2 * 60, 8 * 60, 20 * 60, null, null),
            listOf(7 * 60, 9 * 60, 21 * 60, 23 * 60, null, null),
            listOf(0, 20 * 60, 0, 22 * 60, 13 * 60, 14 * 60),
            listOf(600, 600, 0, 0, 0, 0),
            listOf(7 * 60, 20 * 60, 20 * 60, 23 * 60 + 59, 23 * 60, 23 * 60 + 30),
            listOf(null, null, null, null, null, null),
        )
        for (c in configs) {
            val converted = legacyTimePolicy(c[0], c[1], c[2], c[3], c[4], c[5])
            assertTrue(converted.rules.all { it.callsAllowed })
            for (minute in 0 until 7 * 24 * 60) {
                val t = monday.atStartOfDay().plusMinutes(minute.toLong())
                val old = legacyLocked(t, c[0], c[1], c[2], c[3], c[4], c[5])
                val new = decideTimeLock(converted, t, false).locked
                assertEquals("$c at $t", old, new)
            }
        }
    }

    @Test
    fun `the typical legacy schedule becomes one custom rule plus bedtime`() {
        val p = legacyTimePolicy(7 * 60, 20 * 60, 8 * 60, 21 * 60, 21 * 60, 7 * 60)
        assertEquals(listOf(KIND_BEDTIME, KIND_CUSTOM), p.rules.map { it.kind })
        assertEquals(TimeWindow(20 * 60, 7 * 60), p.rules[1].days[0])
        assertEquals("Friday until Saturday 08:00", TimeWindow(20 * 60, 8 * 60), p.rules[1].days[4])
        assertEquals(List(7) { null }, p.dailyBudgetMinutes)
        assertEquals(LockReason.BEDTIME, decideTimeLock(p, at(0, 22), false).reason)
        assertEquals(LockReason.RULE, decideTimeLock(p, at(0, 20, 30), false).reason)
        assertTrue(legacyTimePolicy(null, null, null, null, null, null).rules.isEmpty())
    }
}
