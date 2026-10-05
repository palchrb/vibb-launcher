package com.kidslauncher.mdm.timerules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ScreenTimeTest {

    private val zone: ZoneId = ZoneId.of("Europe/Oslo")
    private val minute = 60_000L
    private val hour = 60 * minute

    /** Wall-clock ms of a local time on Monday 2026-10-05 (+ [days]). */
    private fun wall(hourOfDay: Int, minuteOfHour: Int = 0, days: Long = 0): Long =
        LocalDateTime.of(2026, 10, 5, hourOfDay, minuteOfHour).plusDays(days).atZone(zone).toInstant().toEpochMilli()

    /** A boot that started at [bootWall] (elapsed 0 then), observed at wall time [nowWall]. */
    private fun clocks(nowWall: Long, bootWall: Long, boot: Int = 7) = Clocks(nowWall, nowWall - bootWall, boot)

    private val bootAt = wall(6)

    private fun start(nowWall: Long = wall(12)): ScreenTimeLedger = observe(ScreenTimeLedger(), clocks(nowWall, bootAt), zone).ledger

    @Test
    fun `the first observation starts today at zero`() {
        val ledger = start()
        assertEquals("2026-10-05", ledger.day)
        assertEquals(0, ledger.usedMs)
        assertEquals(7, ledger.bootCount)
    }

    @Test
    fun `counted time accrues by elapsed realtime and survives a reboot the same day`() {
        val ledger = start(wall(12))
        val since = wall(12) - bootAt
        val after = accrue(ledger, since, clocks(wall(12, 20), bootAt), zone)
        assertEquals(20 * minute, after.usedMs)
        // Reboot at 13:00: a new boot, same date - usage kept.
        val rebooted = observe(after, Clocks(wall(13), 5_000, 8), zone).ledger
        assertEquals(20 * minute, rebooted.usedMs)
        assertEquals("2026-10-05", rebooted.day)
    }

    @Test
    fun `midnight starts a new day and only the part after it counts`() {
        val ledger = start(wall(23, 50))
        val since = wall(23, 50) - bootAt
        val after = accrue(ledger, since, clocks(wall(0, 10, days = 1), bootAt), zone)
        assertEquals("2026-10-06", after.day)
        assertEquals(10 * minute, after.usedMs)
        assertEquals(0, after.extraMinutes)
    }

    @Test
    fun `setting the clock back can't refill the budget`() {
        // 22:00 Tuesday with 60 min used, the clock set back to Monday 22:00 - same boot.
        val tuesday = observe(ScreenTimeLedger(), clocks(wall(22, days = 1), bootAt), zone).ledger.copy(usedMs = 60 * minute)
        val elapsedNow = wall(22, days = 1) - bootAt + 5 * minute
        val setBack = observe(tuesday, Clocks(wall(22), elapsedNow, 7), zone)
        assertEquals("2026-10-06", setBack.ledger.day)
        assertEquals(60 * minute, setBack.ledger.usedMs)
        // ...and when the real midnight comes, by elapsed time, the new day starts as normal.
        val midnight = observe(setBack.ledger, Clocks(wall(22), elapsedNow + 2 * hour + minute, 7), zone)
        assertEquals("2026-10-07", midnight.ledger.day)
        assertEquals(0, midnight.ledger.usedMs)
    }

    @Test
    fun `moving the clock forward moves the date but carries the usage`() {
        val used = start(wall(20)).copy(usedMs = 90 * minute, extraMinutes = 15)
        val jumped = observe(used, Clocks(wall(9, days = 1), wall(20, 1) - bootAt, 7), zone)
        assertEquals("2026-10-06", jumped.ledger.day)
        assertEquals(90 * minute, jumped.ledger.usedMs)
        assertEquals(15, jumped.ledger.extraMinutes)
        assertFalse(jumped.newDayByClock)

        // Creeping forward in 4-minute steps past midnight: carried as well.
        var ledger = start(wall(23, 40)).copy(usedMs = 30 * minute)
        var elapsed = wall(23, 40) - bootAt
        var shownWall = wall(23, 40)
        repeat(8) {
            elapsed += 10_000
            shownWall += 4 * minute + 10_000
            ledger = observe(ledger, Clocks(shownWall, elapsed, 7), zone).ledger
        }
        assertEquals("2026-10-06", ledger.day)
        assertEquals(30 * minute, ledger.usedMs)
    }

    @Test
    fun `a new boot on a later date starts a new day, an earlier one keeps the day`() {
        val used = start(wall(20)).copy(usedMs = 90 * minute)
        val nextMorning = observe(used, Clocks(wall(7, days = 1), 10_000, 8), zone).ledger
        assertEquals("2026-10-06", nextMorning.day)
        assertEquals(0, nextMorning.usedMs)
        val rolledBack = observe(used, Clocks(wall(7, days = -1), 10_000, 8), zone).ledger
        assertEquals("2026-10-05", rolledBack.day)
        assertEquals(90 * minute, rolledBack.usedMs)
    }

    @Test
    fun `a stretch from another boot adds nothing`() {
        val ledger = start(wall(12))
        val after = accrue(ledger, 0, Clocks(wall(12, 30), 60_000, 9), zone)
        assertEquals(0, after.usedMs)
    }

    @Test
    fun `budget lifts add their minutes once, to the day they are first seen`() {
        val ledger = start()
        val plus30 = Lift(10, TARGET_BUDGET, null, 30, 0)
        val once = applyBudgetLifts(ledger, listOf(plus30))
        assertEquals(30, once.extraMinutes)
        assertEquals(once, applyBudgetLifts(once, listOf(plus30)))
        val twice = applyBudgetLifts(once, listOf(plus30, Lift(11, TARGET_BUDGET, null, 15, 0), Lift(12, TARGET_RULE, 1, 30, 0)))
        assertEquals(45, twice.extraMinutes)
        // The next day resets the extra minutes, and the same lift (still sent) isn't re-added.
        val tomorrow = observe(twice, Clocks(wall(7, days = 1), 10_000, 8), zone).ledger
        assertEquals(0, tomorrow.extraMinutes)
        assertEquals(0, applyBudgetLifts(tomorrow, listOf(plus30)).extraMinutes)
        assertEquals(MINUTES_PER_DAY, applyBudgetLifts(ledger, listOf(Lift(13, TARGET_BUDGET, null, 5000, 0))).extraMinutes)
        val use = budgetUse(once.copy(usedMs = 80 * minute), TimePolicy(dailyBudgetMinutes = List(7) { 60 }))!!
        assertEquals(90, use.effectiveMinutes)
        assertFalse(use.exhausted)
    }

    // Rule lifts

    private val ruleLift = Lift(20, TARGET_RULE, 1, 30, wall(12, 30))

    @Test
    fun `a rule lift runs from when it's first seen and ends on time`() {
        val seen = clocks(wall(12, 5), bootAt)
        val records = updateLiftRecords(emptyList(), listOf(ruleLift), seen)
        assertEquals(1, records.size)
        assertEquals("ends at the server's expiry", wall(12, 30), records[0].untilWallMs)
        assertEquals(RuleLifts(ruleIds = setOf(1)), activeRuleLifts(records, listOf(ruleLift), clocks(wall(12, 29), bootAt)))
        assertEquals(RuleLifts(), activeRuleLifts(records, listOf(ruleLift), clocks(wall(12, 30), bootAt)))
        assertEquals(listOf(Instant.ofEpochMilli(wall(12, 30))), liftExpiries(records, clocks(wall(12, 10), bootAt)))
        assertEquals(records, updateLiftRecords(records, listOf(ruleLift), clocks(wall(12, 20), bootAt)))
    }

    @Test
    fun `a rule lift can't be stretched and stops when the server stops sending it`() {
        val records = updateLiftRecords(emptyList(), listOf(ruleLift), clocks(wall(12, 5), bootAt))
        // Clock set back 20 minutes after 25 real minutes: elapsed time says it's over.
        val setBack = Clocks(wall(12, 10), wall(12, 30) - bootAt, 7)
        assertFalse(liftRecordActive(records[0], setBack))
        assertFalse("a reboot ends it", liftRecordActive(records[0], Clocks(wall(12, 10), 1_000, 8)))
        assertEquals(RuleLifts(), activeRuleLifts(records, emptyList(), clocks(wall(12, 10), bootAt)))
        assertTrue(updateLiftRecords(records, emptyList(), clocks(wall(12, 10), bootAt)).isEmpty())
        // Seen late: the minutes cap it at the server's expiry; seen after it: never active.
        val late = updateLiftRecords(emptyList(), listOf(ruleLift), clocks(wall(12, 40), bootAt))
        assertFalse(liftRecordActive(late[0], clocks(wall(12, 40), bootAt)))
        val longExpiry = Lift(21, TARGET_RULE, null, 15, wall(18))
        val capped = updateLiftRecords(emptyList(), listOf(longExpiry), clocks(wall(12), bootAt))
        assertEquals("at most the lift's minutes", wall(12, 15), capped[0].untilWallMs)
        assertEquals(RuleLifts(all = true), activeRuleLifts(capped, listOf(longExpiry), clocks(wall(12, 10), bootAt)))
    }

    @Test
    fun `active lift ids cover running rule lifts and today's budget lifts`() {
        val budgetLift = Lift(30, TARGET_BUDGET, null, 30, wall(23))
        val ledger = applyBudgetLifts(start(), listOf(budgetLift))
        val records = updateLiftRecords(emptyList(), listOf(ruleLift, budgetLift), clocks(wall(12, 5), bootAt))
        assertEquals(listOf(20L, 30L), activeLiftIds(records, listOf(ruleLift, budgetLift), ledger, clocks(wall(12, 10), bootAt)))
        assertEquals(listOf(30L), activeLiftIds(records, listOf(ruleLift, budgetLift), ledger, clocks(wall(12, 40), bootAt)))
    }

    // When screen time counts (QA step 6 #1, #2)

    @Test
    fun `screen time counts on Home and in other apps, also during calls, but not on our free screens`() {
        assertTrue("an app or Home in front", screenTimeCounts(true, false, freeScreenInFront = false, freeScreenSharesScreen = false))
        assertFalse("our lock screen, phone book, in-call or Settings", screenTimeCounts(true, false, true, false))
        assertTrue("a free screen sharing the screen (split, PiP)", screenTimeCounts(true, false, true, true))
        assertFalse("screen off", screenTimeCounts(false, false, false, false))
        assertFalse("keyguard", screenTimeCounts(true, true, false, false))
        assertEquals(4, FREE_SCREENS.size)
        assertFalse(FREE_SCREENS.any { it.endsWith("HomeActivity") })
    }

    @Test
    fun `an unreadable record fails closed`() {
        val lifts = listOf(Lift(40, TARGET_BUDGET, null, 30, 0), Lift(41, TARGET_RULE, 1, 30, 0))
        val ledger = unreadableLedger(clocks(wall(12), bootAt), zone, lifts)
        assertEquals("2026-10-05", ledger.day)
        assertTrue(ledger.unreadable)
        assertEquals(listOf(40L), ledger.appliedBudgetLifts)
        val use = budgetUse(applyBudgetLifts(ledger, lifts), TimePolicy(dailyBudgetMinutes = List(7) { MINUTES_PER_DAY }))!!
        assertTrue("even the largest budget is used up, and the lift isn't re-added", use.exhausted)
        // The next day is a normal day again.
        val tomorrow = observe(ledger, Clocks(wall(7, days = 1), 10_000, 8), zone).ledger
        assertEquals(0, tomorrow.usedMs)
        assertFalse(tomorrow.unreadable)
    }
}
