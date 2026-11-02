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
    fun `a silent school rule silences everything`() {
        assertEquals(RuleSilence(on = true, alarms = false, media = false), ruleSilenceFor(lockAt(9, school)))
    }

    @Test
    fun `a silent bedtime alone lets alarms and its apps' sound through`() {
        assertEquals(RuleSilence(on = true, alarms = true, media = true), ruleSilenceFor(lockAt(14, bedtime)))
    }

    @Test
    fun `silence wins, and any silent rule that isn't a bedtime silences alarms`() {
        // School (silent) and bedtime (silent) at once: fully silent.
        val both = lockAt(13, school, bedtime)
        assertEquals(RuleSilence(on = true), ruleSilenceFor(both))
        // A loud rule and a silent bedtime: silent, alarms ring.
        val customAndBedtime = lockAt(14, custom.copy(days = List(7) { w(13, 15) }), bedtime)
        assertEquals(RuleSilence(on = true, alarms = true, media = true), ruleSilenceFor(customAndBedtime))
        // A loud school and a silent bedtime: alarms ring - only silent rules count.
        val loudSchool = lockAt(13, school.copy(silent = false), bedtime)
        assertTrue(ruleSilenceFor(loudSchool).alarms)
        // A silent custom rule is fully silent.
        assertEquals(RuleSilence(on = true), ruleSilenceFor(lockAt(9, custom.copy(silent = true))))
    }

    @Test
    fun `no silent rule, the override, the pause, a lift or the budget alone - no silence`() {
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, custom)))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, school, override = true)))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, school, lifts = RuleLifts(ruleIds = setOf(1L)))))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(9, school, lifts = RuleLifts(all = true))))
        assertEquals(NOT_SILENT, ruleSilenceFor(lockAt(16, school)))
        val used = BudgetUse(usedMs = 60 * 60_000L, budgetMinutes = 60, extraMinutes = 0)
        val budget = decideTimeLock(TimePolicy(rules = listOf(school), dailyBudgetMinutes = List(7) { 60 }), at(16), false, budget = used)
        assertTrue(budget.locked)
        assertEquals(NOT_SILENT, ruleSilenceFor(budget))
        assertEquals(NOT_SILENT, ruleSilenceFor(UNLOCKED))
    }

    @Test
    fun `Find My Device's ring lifts the silence, the kid's switch-off is put back`() {
        val silent = ruleSilenceFor(lockAt(9, school))
        assertEquals(NOT_SILENT, silenceInForce(silent, ringHeld = true))
        assertEquals(silent, silenceInForce(silent, ringHeld = false))
        val on = ZenRuleSeen(enabled = true, active = true)
        assertTrue("nothing filters", silenceNeedsReassert(silent, on, interruptionsAll = true))
        assertFalse("on and in force", silenceNeedsReassert(silent, on, interruptionsAll = false))
        assertFalse("Android 14 can't say: the filter decides", silenceNeedsReassert(silent, on.copy(active = null), false))
        // Code QA L1: our rule off while the kid's own DND filters - put back all the same.
        assertTrue(silenceNeedsReassert(silent, on.copy(active = false), interruptionsAll = false))
        assertTrue("disabled", silenceNeedsReassert(silent, on.copy(enabled = false), false))
        assertTrue("gone", silenceNeedsReassert(silent, null, false))
        assertFalse("never touches the kid's own DND", silenceNeedsReassert(NOT_SILENT, null, interruptionsAll = false))
        assertFalse(silenceNeedsReassert(NOT_SILENT, on.copy(active = false), interruptionsAll = true))
    }

    /** Code QA H1: an alarm our rule silences is no ringing alarm for the lock - only while it is on and alarms are off. */
    @Test
    fun `the lock hears no alarm only while our rule silences alarms`() {
        assertTrue(silencesAlarms(RuleSilence(on = true)))
        assertFalse("a bedtime lets alarms ring", silencesAlarms(RuleSilence(on = true, alarms = true, media = true)))
        assertFalse(silencesAlarms(NOT_SILENT))
        assertFalse("not known", silencesAlarms(null))
        // Find My Device's ring: what is in force is not silent.
        assertFalse(silencesAlarms(silenceInForce(RuleSilence(on = true), ringHeld = true)))
    }

    /** Code QA L7: the apply's decisions. */
    @Test
    fun `each apply takes the right step`() {
        val silent = RuleSilence(on = true)
        val enabled = ZenRuleSeen(enabled = true)
        assertEquals(ZenStep.SKIP, zenStep(silent, silent, force = false, ours = enabled))
        assertEquals(ZenStep.SKIP, zenStep(NOT_SILENT, NOT_SILENT, false, null))
        assertEquals(ZenStep.RECORD, zenStep(NOT_SILENT, null, false, null))
        assertEquals(ZenStep.OFF, zenStep(NOT_SILENT, null, false, enabled))
        assertEquals(ZenStep.OFF, zenStep(NOT_SILENT, silent, false, enabled))
        assertEquals(ZenStep.ADD, zenStep(silent, NOT_SILENT, false, null))
        assertEquals(ZenStep.ON, zenStep(silent, NOT_SILENT, false, enabled))
        assertEquals("a policy change", ZenStep.ON, zenStep(silent, RuleSilence(on = true, alarms = true), false, enabled))
        // Code QA L2: the first apply of a process (nothing known) switches off and on.
        assertEquals(ZenStep.OFF_ON, zenStep(silent, null, false, enabled))
        assertEquals("the re-assert", ZenStep.OFF_ON, zenStep(silent, silent, force = true, ours = enabled))
        assertEquals("disabled", ZenStep.OFF_ON, zenStep(silent, NOT_SILENT, false, ZenRuleSeen(enabled = false)))
        assertEquals(ZenStep.ADD, zenStep(silent, silent, force = true, ours = null))
        // Code QA M2: a read-back still disabled, or gone, is replaced.
        assertTrue(zenRuleNeedsReplacing(false))
        assertTrue(zenRuleNeedsReplacing(null))
        assertFalse(zenRuleNeedsReplacing(true))
        // L-R1: of duplicate rules an enabled one is kept (first), the rest go; ties by id, so every apply agrees.
        assertEquals(listOf("b", "a", "c"), zenRulesKeptFirst(mapOf("a" to false, "b" to true, "c" to false)))
        assertEquals(listOf("a", "b"), zenRulesKeptFirst(mapOf("b" to true, "a" to true)))
        assertEquals(listOf("a", "b"), zenRulesKeptFirst(mapOf("b" to false, "a" to false)))
        // E7 code QA 5: our own switch-off's "deactivated" echo is no kid's switch-off; a later one is.
        assertTrue(ownOffEcho(ownOffAtMs = 1_000L, nowMs = 1_000L + OWN_OFF_ECHO_MS - 1))
        assertFalse(ownOffEcho(1_000L, 1_000L + OWN_OFF_ECHO_MS))
        assertFalse("no own switch-off of that rule", ownOffEcho(null, 1_000L))
        assertFalse("a clock before it", ownOffEcho(1_000L, 999L))
        // Code QA L3: an older lock decision never overrides a newer one.
        assertTrue(lockIsNewer(decidedAtMs = 10, lastDecidedAtMs = 10))
        assertTrue(lockIsNewer(11, 10))
        assertFalse(lockIsNewer(9, 10))
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
        assertTrue(source("TimeRulesRuntime.kt").contains("ZenRuleRuntime.follow(context, snap.lock, snap.clocks.elapsedMs)"))
        assertTrue(source("AppEnforcer.kt").contains("ZenRuleRuntime.follow(context, lock, lockDecidedAt)"))
        assertTrue(source("Application.kt").contains("ZenRuleRuntime.init(this)"))
        assertTrue(source("MdmDeviceAdminReceiver.kt").contains("ZenRuleRuntime.release(context)"))
        val locate = source("LocateCommands.kt")
        val ring = locate.indexOf("ZenRuleRuntime.holdForRing(context, true)")
        assertTrue("lifted before the ring plays", ring in 0 until locate.indexOf("USAGE_ALARM"))
        assertTrue(locate.contains("ZenRuleRuntime.holdForRing(context, false)"))
        // Code QA M1: a ring that fails puts the hold (and the volumes) back.
        val ringFun = locate.substring(locate.indexOf("fun ring(context: Context)"), locate.indexOf("fun stopRingAndRestore("))
        val catch = ringFun.substring(ringFun.lastIndexOf("} catch (e: Exception) {"))
        assertTrue(catch.contains("stopRingAndRestore(context)"))
        // Code QA H1: the lock's alarm reads ask whether our rule silences alarms.
        val lock = source("PinLockRuntime.kt")
        assertEquals(2, Regex("alarmsSilenced = com.kidslauncher.mdm.timerules.ZenRuleRuntime.silencesAlarms\\(\\)").findAll(lock).count())
        assertTrue(source("VoipRinger.kt").contains("ZenRuleRuntime.letsCallsRing()"))
        // Never a manual DND or our own saved state: off is the condition false, nothing restored.
        val runtime = source("ZenRuleRuntime.kt")
        assertFalse(runtime.contains("setInterruptionFilter"))
        assertFalse(runtime.contains("ringerMode"))
        // L-R1: the rules are listed kept-first, so the apply's drop(1) and the re-assert's firstOrNull agree on it.
        assertTrue(runtime.contains("zenRulesKeptFirst(found.mapValues { it.value.isEnabled })"))
        // L-R2: a switch-on clears a deactivation heard before, right after the condition went true.
        val on = runtime.substringAfter("private fun switchOn(").substringBefore("if (!zenRuleNeedsReplacing")
        assertTrue(on.indexOf("deactivatedId = null") > on.indexOf("condition(context, uri, on = true)"))
        // Every own switch-off goes through switchOff, which stamps it for the echo.
        assertEquals(1, Regex("on = false\\)\\)").findAll(runtime).count())
        assertTrue(runtime.substringAfter("private fun switchOff(").substringBefore("\n    }\n").contains("ownOff = id to android.os.SystemClock.elapsedRealtime()"))
        assertTrue(runtime.contains("if (!ownOffEcho(ownOff?.takeIf { it.first == id }?.second, android.os.SystemClock.elapsedRealtime())) deactivatedId = id"))
        // A flip of silencesAlarms tells the listeners (the time-rule screen's alarm row).
        assertTrue(runtime.contains("if (silencesAlarms() != before) main.post { listeners.forEach { listener -> runCatching { listener() } } }"))
    }
}
