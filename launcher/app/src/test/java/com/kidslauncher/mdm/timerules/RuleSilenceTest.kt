package com.kidslauncher.mdm.timerules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** A time rule's "Stille" switch (server migration 0121): which Do Not Disturb a lock asks for. */
class RuleSilenceTest {

    /** 2026-10-05 is a Monday. */
    private val monday: LocalDate = LocalDate.of(2026, 10, 5)

    private fun at(hour: Int): LocalDateTime = monday.atTime(hour, 0)

    private fun w(h1: Int, h2: Int) = TimeWindow(h1 * 60, h2 * 60)

    private val school = TimeRule(1, "Skole", KIND_SCHOOL, callsAllowed = false, silent = true, days = List(7) { w(8, 14) })
    private val bedtime = TimeRule(2, "", KIND_BEDTIME, callsAllowed = true, silent = true, days = List(7) { w(13, 15) })
    private val custom = TimeRule(3, "Middag", KIND_CUSTOM, callsAllowed = true, days = List(7) { w(9, 10) })

    private fun lockAt(hour: Int, vararg rules: TimeRule, override: Boolean = false, lifts: RuleLifts = RuleLifts()) =
        decideTimeLock(TimePolicy(rules = rules.toList(), dailyBudgetMinutes = List(7) { null }), at(hour), override, lifts)

    @Test
    fun `a silent school rule silences everything, calls only through the screening`() {
        val lock = lockAt(9, school)
        assertEquals(RuleSilence(on = true, alarms = false, media = false, calls = true), ruleSilenceFor(lock, callsScreened = true))
        // Unmanaged calls under a no-calls rule: nothing else stops them, so they don't ring.
        assertEquals(RuleSilence(on = true, calls = false), ruleSilenceFor(lock, callsScreened = false))
    }

    @Test
    fun `a silent bedtime alone lets alarms and its apps' sound through`() {
        val lock = lockAt(14, bedtime)
        assertEquals(RuleSilence(on = true, alarms = true, media = true, calls = true), ruleSilenceFor(lock, callsScreened = false))
    }

    @Test
    fun `silence wins, and any silent rule that isn't a bedtime silences alarms`() {
        // School (silent) and bedtime (silent) at once: fully silent.
        val both = lockAt(13, school, bedtime)
        assertEquals(RuleSilence(on = true, calls = true), ruleSilenceFor(both, callsScreened = true))
        // A loud rule and a silent bedtime: silent, alarms ring.
        val customAndBedtime = lockAt(14, custom.copy(days = List(7) { w(13, 15) }), bedtime)
        assertEquals(RuleSilence(on = true, alarms = true, media = true, calls = true), ruleSilenceFor(customAndBedtime, false))
        // A loud school and a silent bedtime: alarms ring - only silent rules count.
        val loudSchool = lockAt(13, school.copy(silent = false), bedtime)
        assertTrue(ruleSilenceFor(loudSchool, true).alarms)
        // A silent custom rule is fully silent.
        assertEquals(RuleSilence(on = true, calls = true), ruleSilenceFor(lockAt(9, custom.copy(silent = true)), false))
    }

    @Test
    fun `no silent rule, the override, the pause, a lift or the budget alone - no silence`() {
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, custom), true))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, school, override = true), true))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, school, lifts = RuleLifts(ruleIds = setOf(1L))), true))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, school, lifts = RuleLifts(all = true)), true))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(16, school), true))
        val used = BudgetUse(usedMs = 60 * 60_000L, budgetMinutes = 60, extraMinutes = 0)
        val budget = decideTimeLock(TimePolicy(rules = listOf(school), dailyBudgetMinutes = List(7) { 60 }), at(16), false, budget = used)
        assertTrue(budget.locked)
        assertEquals(NOT_SILENT, ruleSilenceFor(budget, true))
        assertEquals(NOT_SILENT, ruleSilenceFor(UNLOCKED, true))
    }

    @Test
    fun `Find My Device's ring lifts the silence, the kid's switch-off is put back`() {
        val silent = ruleSilenceFor(lockAt(9, school), true)
        assertEquals(NOT_SILENT, silenceInForce(silent, ringHeld = true))
        assertEquals(silent, silenceInForce(silent, ringHeld = false))
        assertTrue(silenceNeedsReassert(silent, interruptionsAll = true))
        assertFalse("on and filtering", silenceNeedsReassert(silent, interruptionsAll = false))
        assertFalse("never touches the kid's own DND", silenceNeedsReassert(NOT_SILENT, interruptionsAll = false))
        assertFalse(silenceNeedsReassert(NOT_SILENT, interruptionsAll = true))
    }

    @Test
    fun `a rule without the key is not silent`() {
        val decoded = com.kidslauncher.mdm.server.ServerJson.decodeFromString(TimeRule.serializer(), """{"id": 1, "kind": "school"}""")
        assertFalse(decoded.silent)
        val silent = com.kidslauncher.mdm.server.ServerJson.decodeFromString(TimeRule.serializer(), """{"id": 1, "kind": "school", "silent": true}""")
        assertTrue(silent.silent)
    }

    private fun source(name: String): String {
        val root = listOf("src/main/java/com/kidslauncher/mdm", "app/src/main/java/com/kidslauncher/mdm").map(::File).first { it.exists() }
        return root.walk().first { it.isFile && it.name == name }.readText()
    }

    /** Where the rule follows the lock, and Find My Device's ring around it. */
    @Test
    fun `the zen rule follows every lock decision and steps aside for the ring`() {
        assertTrue(source("TimeRulesRuntime.kt").contains("ZenRuleRuntime.follow(context, snap.lock)"))
        assertTrue(source("AppEnforcer.kt").contains("ZenRuleRuntime.follow(context, lock)"))
        assertTrue(source("Application.kt").contains("ZenRuleRuntime.init(this)"))
        assertTrue(source("MdmDeviceAdminReceiver.kt").contains("ZenRuleRuntime.release(context)"))
        val locate = source("LocateCommands.kt")
        val ring = locate.indexOf("ZenRuleRuntime.holdForRing(context, true)")
        assertTrue("lifted before the ring plays", ring in 0 until locate.indexOf("USAGE_ALARM"))
        assertTrue(locate.contains("ZenRuleRuntime.holdForRing(context, false)"))
        assertTrue(source("VoipRinger.kt").contains("ZenRuleRuntime.letsCallsRing()"))
        // Never a manual DND or our own saved state: off is the condition false, nothing restored.
        val runtime = source("ZenRuleRuntime.kt")
        assertFalse(runtime.contains("setInterruptionFilter"))
        assertFalse(runtime.contains("ringerMode"))
    }
}
