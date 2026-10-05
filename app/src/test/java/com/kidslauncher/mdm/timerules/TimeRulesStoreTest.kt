package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.withTimeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The call path's answer per source, and how CallPolicyStore.effectiveState() combines it. */
class TimeRulesStoreTest {
    private val schoolHours = LocalDate.of(2026, 10, 5).atTime(9, 0)
    private val evening = LocalDate.of(2026, 10, 5).atTime(19, 0)
    private val school = TimeRule(1, "Skole", KIND_SCHOOL, days = List(7) { TimeWindow(8 * 60, 14 * 60) })
    private val policy = TimePolicy(rules = listOf(school), dailyBudgetMinutes = List(7) { null })
    private val boot = bootCallBlocksOf(policy)

    @Test
    fun `nothing loaded blocks`() {
        assertTrue(callsBlockedFor(TimeRulesSource.NONE, null, null, null, evening))
    }

    @Test
    fun `before unlock the boot copy decides, missing blocks`() {
        assertTrue(callsBlockedFor(TimeRulesSource.BOOT, null, boot, null, schoolHours))
        assertFalse(callsBlockedFor(TimeRulesSource.BOOT, null, boot, null, evening))
        assertTrue(callsBlockedFor(TimeRulesSource.BOOT, null, null, null, evening))
    }

    @Test
    fun `unlocked the live decider answers, else the cached rules, an error blocks`() {
        assertTrue(callsBlockedFor(TimeRulesSource.CE, policy, null, null, schoolHours))
        assertFalse(callsBlockedFor(TimeRulesSource.CE, policy, null, null, evening))
        assertFalse(callsBlockedFor(TimeRulesSource.CE, null, null, null, schoolHours))
        assertFalse("a lift", callsBlockedFor(TimeRulesSource.CE, policy, null, { false }, schoolHours))
        assertTrue(callsBlockedFor(TimeRulesSource.CE, policy, null, { error("broken") }, evening))
    }

    @Test
    fun `effectiveState turns managed calls off during school only`() {
        val managed = CallPolicyState.Managed(CallRules(callsEnabled = true, smsEnabled = true))
        fun effective(at: java.time.LocalDateTime) = withTimeRule(managed, callsBlockedFor(TimeRulesSource.CE, policy, null, null, at))
        assertEquals(false, (effective(schoolHours) as CallPolicyState.Managed).rules.callsEnabled)
        assertEquals(managed, effective(evening))
    }
}
