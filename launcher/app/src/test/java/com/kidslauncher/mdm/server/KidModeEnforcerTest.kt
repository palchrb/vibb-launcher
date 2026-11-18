package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The bridge from a policy to the time-rule lock. */
class KidModeEnforcerTest {

    /** 2026-10-05 is a Monday. */
    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDate.of(2026, 10, 5).plusDays(dayOffset.toLong()).atTime(hour, minute)

    private val monday = 0
    private val saturday = 5

    private val bedtime = TimePolicy(
        rules = listOf(TimeRule(2, "Leggetid", KIND_BEDTIME, callsAllowed = true, days = List(7) { TimeWindow(21 * 60, 7 * 60) })),
        dailyBudgetMinutes = List(7) { null },
    )

    @Test
    fun `an override or pause clears the lock, otherwise the rules decide`() {
        val policy = PolicyResponse(timePolicy = bedtime)
        val night = at(monday, 23)
        assertEquals(LockReason.BEDTIME, KidModeEnforcer.lockNow(policy, overrideActive = false, at = night).reason)
        assertEquals(LockReason.NONE, KidModeEnforcer.lockNow(policy, overrideActive = true, at = night).reason)
        assertEquals(LockReason.NONE, KidModeEnforcer.lockNow(null, overrideActive = false, at = night).reason)
    }

    @Test
    fun `null policy and a policy without time rules are never locked`() {
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(null, at(monday, 23)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(PolicyResponse(), at(monday, 23)))
    }

    @Test
    fun `time_policy decides`() {
        val school = TimeRule(1, "Skole", KIND_SCHOOL, days = List(5) { TimeWindow(8 * 60 + 15, 14 * 60) } + listOf(null, null))
        val policy = PolicyResponse(timePolicy = TimePolicy(rules = listOf(school), dailyBudgetMinutes = List(7) { null }))
        assertEquals(LockReason.SCHOOL, KidModeEnforcer.evaluate(policy, at(monday, 9)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(monday, 23)))
        assertEquals(LockReason.NONE, KidModeEnforcer.evaluate(policy, at(saturday, 9)))
    }
}
