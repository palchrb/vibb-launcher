package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The bridge from a policy to the time-rule lock; an older server's fixed windows are converted
 * (the minute-by-minute equivalence is in TimeRulesTest). */
class KidModeEnforcerTest {

    /** 2026-10-05 is a Monday. */
    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDate.of(2026, 10, 5).plusDays(dayOffset.toLong()).atTime(hour, minute)

    private val monday = 0
    private val tuesday = 1
    private val saturday = 5

    @Test
    fun `an override or pause clears the lock, otherwise the schedule decides`() {
        val policy = PolicyResponse(bedtimeStartMinutes = 21 * 60, bedtimeEndMinutes = 7 * 60)
        val night = at(monday, 23)
        assertEquals(LockReason.BEDTIME, KidModeEnforcer.lockNow(policy, overrideActive = false, at = night).reason)
        assertEquals(LockReason.NONE, KidModeEnforcer.lockNow(policy, overrideActive = true, at = night).reason)
        assertEquals(LockReason.NONE, KidModeEnforcer.lockNow(null, overrideActive = false, at = night).reason)
    }

    @Test
    fun `null policy and a policy without schedule fields are never locked`() {
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(null, at(monday, 23)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(PolicyResponse(), at(monday, 23)))
    }

    @Test
    fun `legacy bedtime window wraps overnight and locks inside it`() {
        val policy = PolicyResponse(bedtimeStartMinutes = 21 * 60, bedtimeEndMinutes = 7 * 60)
        assertEquals(LockReason.BEDTIME, KidModeEnforcer.evaluate(policy, at(monday, 23)))
        assertEquals(LockReason.BEDTIME, KidModeEnforcer.evaluate(policy, at(monday, 3)))
        assertEquals(LockReason.BEDTIME, KidModeEnforcer.evaluate(policy, at(monday, 21)))
        assertEquals("bedtime end is exclusive", LockReason.NONE, KidModeEnforcer.evaluate(policy, at(monday, 7)))
    }

    @Test
    fun `legacy bedtime start equal to end means no restriction`() {
        val policy = PolicyResponse(bedtimeStartMinutes = 0, bedtimeEndMinutes = 0)
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(monday, 0)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(monday, 12)))
    }

    @Test
    fun `legacy weekday window locks outside allowed hours as a custom rule`() {
        val policy = PolicyResponse(weekdayStartMinutes = 9 * 60, weekdayEndMinutes = 19 * 60)
        assertEquals(LockReason.RULE, KidModeEnforcer.evaluate(policy, at(tuesday, 8)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(tuesday, 12)))
        assertEquals(LockReason.RULE, KidModeEnforcer.evaluate(policy, at(tuesday, 19)))
    }

    @Test
    fun `legacy weekday start equal to end means always allowed, weekend uses its own window`() {
        assertEquals(
            LockReason.NONE,
            KidModeEnforcer.evaluate(PolicyResponse(weekdayStartMinutes = 600, weekdayEndMinutes = 600), at(tuesday, 3)),
        )
        val policy = PolicyResponse(
            weekdayStartMinutes = 9 * 60,
            weekdayEndMinutes = 19 * 60,
            weekendStartMinutes = 0,
            weekendEndMinutes = 0,
        )
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(saturday, 3)))
    }

    @Test
    fun `time_policy wins over the legacy fields`() {
        val school = TimeRule(1, "Skole", KIND_SCHOOL, days = List(5) { TimeWindow(8 * 60 + 15, 14 * 60) } + listOf(null, null))
        val policy = PolicyResponse(
            bedtimeStartMinutes = 21 * 60,
            bedtimeEndMinutes = 7 * 60,
            timePolicy = TimePolicy(rules = listOf(school), dailyBudgetMinutes = List(7) { null }),
        )
        assertEquals(LockReason.SCHOOL, KidModeEnforcer.evaluate(policy, at(monday, 9)))
        assertEquals("the legacy bedtime is ignored", LockReason.NONE, KidModeEnforcer.evaluate(policy, at(monday, 23)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(saturday, 9)))
    }
}
