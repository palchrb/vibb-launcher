package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.Clocks
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_CUSTOM
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import com.kidslauncher.mdm.timerules.Lift
import com.kidslauncher.mdm.timerules.LiftRecord
import com.kidslauncher.mdm.timerules.RuleLifts
import com.kidslauncher.mdm.timerules.TARGET_RULE
import com.kidslauncher.mdm.timerules.TimeLock
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeWindow
import com.kidslauncher.mdm.timerules.UNLOCKED
import com.kidslauncher.mdm.timerules.decideTimeLock
import com.kidslauncher.mdm.ui.LockGlyph
import com.kidslauncher.mdm.ui.RuleLabel
import com.kidslauncher.mdm.ui.ruleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Design r13 §1.6: the glance's rule card - what it says ([ruleCard], [ruleLabel]) and when its end is certain ([lockEnd]). */
class RuleCardTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val monday = LocalDate.of(2026, 10, 26)

    private fun minutes(h: Int, m: Int = 0) = h * 60 + m
    private fun everyDay(start: Int, end: Int) = List(7) { TimeWindow(start, end) }
    private fun rule(id: Long, kind: String, days: List<TimeWindow?>, name: String = "") = TimeRule(id, name, kind, days = days)
    private fun policy(vararg rules: TimeRule, budget: List<Int?> = List(7) { null }) =
        TimePolicy(rules = rules.toList(), dailyBudgetMinutes = budget)

    private fun lockAt(p: TimePolicy, at: LocalDateTime, lifts: RuleLifts = RuleLifts(), budget: BudgetUse? = null): TimeLock =
        decideTimeLock(p, at, overrideActive = false, lifts = lifts, budget = budget)

    private fun end(p: TimePolicy, at: LocalDateTime, lifts: RuleLifts = RuleLifts(), budget: BudgetUse? = null, zone: ZoneId = oslo) =
        lockEnd(p, lockAt(p, at, lifts, budget), lifts, budget, at.toLocalDate(), zone, at)

    private val school = rule(1, KIND_SCHOOL, everyDay(minutes(8), minutes(14, 30)))

    @Test
    fun `the card - each reason its kind and name, none or a disagreement no card`() {
        val p = policy(school.copy(name = "Fotballtrening"))
        val at = monday.atTime(10, 0)
        val lock = lockAt(p, at)
        val until = monday.atTime(14, 30)
        assertEquals(RuleCard(RuleCardKind.SCHOOL, "Fotballtrening", until), ruleCard(LockReason.SCHOOL, lock, until))
        // A blank name: the kind's label (the screens resolve it).
        val plain = lockAt(policy(school), at)
        assertEquals(RuleCard(RuleCardKind.SCHOOL, null, until), ruleCard(LockReason.SCHOOL, plain, until))
        assertEquals(RuleLabel.Kind(RuleCardKind.SCHOOL), ruleCard(LockReason.SCHOOL, plain, null)!!.label)
        val bedtime = lockAt(policy(rule(2, KIND_BEDTIME, everyDay(minutes(21), minutes(7)))), monday.atTime(22, 0))
        assertEquals(RuleCardKind.BEDTIME, ruleCard(LockReason.BEDTIME, bedtime, null)!!.kind)
        val custom = lockAt(policy(rule(3, "unknown-kind", everyDay(minutes(9), minutes(11)))), at)
        assertEquals(RuleCardKind.RULE, ruleCard(LockReason.RULE, custom, null)!!.kind)
        // The used-up budget: the hourglass, no name, no end - whatever end it is handed.
        val budget = lockAt(policy(budget = List(7) { 30 }), at, budget = BudgetUse(30 * 60_000L, 30, 0))
        assertEquals(RuleCard(RuleCardKind.SCREEN_TIME, null, null), ruleCard(LockReason.SCREEN_TIME, budget, until))
        assertEquals(LockGlyph.HOURGLASS, RuleCardKind.SCREEN_TIME.glyph())
        assertEquals(LockGlyph.BOOK, RuleCardKind.SCHOOL.glyph())
        assertEquals(LockGlyph.MOON, RuleCardKind.BEDTIME.glyph())
        assertEquals(LockGlyph.DOT, RuleCardKind.RULE.glyph())
        // No lock, and the pref and the snapshot disagreeing: no card.
        assertNull(ruleCard(LockReason.NONE, UNLOCKED, null))
        assertNull(ruleCard(LockReason.NONE, lock, until))
        assertNull(ruleCard(LockReason.SCHOOL, UNLOCKED, until))
        assertNull(ruleCard(LockReason.BEDTIME, lock, until))
    }

    @Test
    fun `the label - the rule's own name, else the kind`() {
        assertEquals(RuleLabel.Named("Fotballtrening"), ruleLabel(KIND_CUSTOM, "Fotballtrening"))
        assertEquals(RuleLabel.Kind(RuleCardKind.SCHOOL), ruleLabel(KIND_SCHOOL, "  "))
        assertEquals(RuleLabel.Kind(RuleCardKind.BEDTIME), ruleLabel(KIND_BEDTIME, ""))
        assertEquals(RuleLabel.Kind(RuleCardKind.RULE), ruleLabel("whatever", ""))
        assertEquals("no rule: the used-up budget", RuleLabel.Kind(RuleCardKind.SCREEN_TIME), ruleLabel(null, "ignored"))
    }

    @Test
    fun `a plain window gives its end`() {
        assertEquals(monday.atTime(14, 30), end(policy(school), monday.atTime(10, 0)))
    }

    @Test
    fun `no end when something locks at it - the next day, an overlap, an adjacent rule, today's budget, the budget lock`() {
        // All day every day: the next day's occurrence starts at the end.
        assertNull(end(policy(rule(4, KIND_CUSTOM, everyDay(0, 0))), monday.atTime(10, 0)))
        // A 22:00-07:00 window whose next day starts at 07:00 (the same rule).
        val days = MutableList<TimeWindow?>(7) { TimeWindow(minutes(22), minutes(7)) }
        days[monday.plusDays(1).dayOfWeek.value - 1] = TimeWindow(minutes(7), minutes(15))
        assertNull(end(policy(rule(5, KIND_BEDTIME, days)), monday.atTime(23, 0)))
        // Two overlapping rules, and an adjacent one (Span is start-inclusive).
        assertNull(end(policy(school, rule(6, KIND_CUSTOM, everyDay(minutes(14), minutes(16)))), monday.atTime(10, 0)))
        assertNull(end(policy(school, rule(7, KIND_CUSTOM, everyDay(minutes(14, 30), minutes(16)))), monday.atTime(10, 0)))
        // Today's budget used up under a daytime rule: the budget locks at 14:30.
        val budget = BudgetUse(60 * 60_000L, 60, 0)
        assertNull(end(policy(school, budget = List(7) { 60 }), monday.atTime(10, 0), budget = budget))
        // The budget lock itself: never an end.
        val p = policy(budget = List(7) { 60 })
        val lock = lockAt(p, monday.atTime(10, 0), budget = budget)
        assertEquals(LockReason.SCREEN_TIME, lock.reason)
        assertNull(lockEnd(p, lock, RuleLifts(), budget, monday, oslo, monday.atTime(10, 0)))
    }

    @Test
    fun `bedtime with today's budget used up ends at 07 00 - tomorrow's budget is fresh, unless it is 0 (R13-2)`() {
        val bedtime = rule(8, KIND_BEDTIME, everyDay(minutes(22), minutes(7)))
        val usedUp = BudgetUse(90 * 60_000L, 90, 0)
        assertEquals(monday.plusDays(1).atTime(7, 0), end(policy(bedtime, budget = List(7) { 90 }), monday.atTime(22, 30), budget = usedUp))
        val noneTomorrow = MutableList<Int?>(7) { 90 }
        noneTomorrow[monday.plusDays(1).dayOfWeek.value - 1] = 0
        assertNull(end(policy(bedtime, budget = noneTomorrow), monday.atTime(22, 30), budget = usedUp))
        // Tomorrow unlimited: the end.
        assertEquals(monday.plusDays(1).atTime(7, 0), end(policy(bedtime), monday.atTime(22, 30), budget = usedUp))
    }

    @Test
    fun `an end in the spring-forward gap is no end (R13-2)`() {
        // Europe/Oslo, the last Sunday of March 2026: 02:00-03:00 doesn't exist.
        val sunday = LocalDate.of(2026, 3, 29)
        val night = rule(9, KIND_CUSTOM, everyDay(minutes(1), minutes(2, 30)))
        val at = sunday.atTime(1, 30)
        assertNull(lockEnd(policy(night), lockAt(policy(night), at), RuleLifts(), null, sunday, oslo, at))
        // The same window a week before ends as usual.
        val before = sunday.minusDays(7)
        assertEquals(before.atTime(2, 30), lockEnd(policy(night), lockAt(policy(night), before.atTime(1, 30)), RuleLifts(), null, before, oslo, before.atTime(1, 30)))
    }

    @Test
    fun `a lifted second rule running past the end - its lift ending after the end gives the end, before it none (R13-6)`() {
        val second = rule(10, KIND_CUSTOM, everyDay(minutes(14), minutes(16)))
        val p = policy(school, second).copy(lifts = listOf(Lift(id = 50, target = TARGET_RULE, ruleId = 10, minutes = 600, expiresAtMs = Long.MAX_VALUE)))
        val at = monday.atTime(10, 0)
        val endAt = monday.atTime(14, 30).atZone(oslo).toInstant()
        val nowMs = at.atZone(oslo).toInstant().toEpochMilli()
        val clocks = Clocks(nowMs, 1_000_000L, 3)
        fun record(untilMs: Long) = LiftRecord(50, untilMs, clocks.elapsedMs, 3, untilMs - nowMs)
        val lastsPast = listOf(record(endAt.toEpochMilli() + 60 * 60_000L))
        val endsBefore = listOf(record(endAt.toEpochMilli() - 60_000L))
        val now = RuleLifts(ruleIds = setOf(10L))
        val lock = lockAt(p, at, lifts = now)
        assertEquals(listOf(1L), lock.rules.map { it.id })
        val liftsPast = ruleLiftsAt(lastsPast, p.lifts, clocks, endAt)
        assertEquals(now, liftsPast)
        assertEquals(monday.atTime(14, 30), lockEnd(p, lock, liftsPast, null, monday, oslo, at))
        val liftsBefore = ruleLiftsAt(endsBefore, p.lifts, clocks, endAt)
        assertEquals(RuleLifts(), liftsBefore)
        assertNull(lockEnd(p, lock, liftsBefore, null, monday, oslo, at))
        // A lift ending exactly at the end counts.
        assertEquals(now, ruleLiftsAt(listOf(record(endAt.toEpochMilli())), p.lifts, clocks, endAt))
    }

    /** qa-r13-rule-card-code Q-6: under a rule that leaves an app usable, today's time left must last until the end. */
    @Test
    fun `no end when the budget can run out first in the rule's usable apps`() {
        val withChat = school.copy(exemptApps = listOf("org.example.chat"))
        val p = policy(withChat, budget = List(7) { 90 })
        val tenLeft = BudgetUse(80 * 60_000L, 90, 0)
        assertNull("10 min left, 4.5 h to the end", end(p, monday.atTime(10, 0), budget = tenLeft))
        assertEquals("10 min left, 5 min to the end", monday.atTime(14, 30), end(p, monday.atTime(14, 25), budget = tenLeft))
        assertEquals("no usable app: nothing counts", monday.atTime(14, 30), end(policy(school, budget = List(7) { 90 }), monday.atTime(10, 0), budget = tenLeft))
        assertEquals("no budget today", monday.atTime(14, 30), end(policy(withChat), monday.atTime(10, 0), budget = BudgetUse(80 * 60_000L, null, 0)))
        // An end on another day starts a fresh budget: bedtime with chat and little left still ends at 07:00.
        val bedtime = rule(11, KIND_BEDTIME, everyDay(minutes(22), minutes(7))).copy(exemptApps = listOf("org.example.chat"))
        assertEquals(monday.plusDays(1).atTime(7, 0), end(policy(bedtime, budget = List(7) { 90 }), monday.atTime(22, 30), budget = tenLeft))
    }

    /** Mutation L11: the glance's listeners run before the missed calls' re-learn in `recheck` (one layout). */
    @Test
    fun `the lock listeners run before relearn`() {
        val runtime = listOf("src/main", "app/src/main").map { java.io.File(it, "java/com/kidslauncher/mdm/timerules/TimeRulesRuntime.kt") }
            .first { it.exists() }.readText()
        val recheck = runtime.substringAfter("fun recheck(").substringBefore("TimeRuleAlarm.schedule(context)")
        val listeners = recheck.indexOf("notifyLockListeners(snap)")
        val relearn = recheck.indexOf("MissedCallNotifier.relearn(context)")
        assertTrue(recheck, listeners in 0 until relearn)
        assertTrue(recheck.indexOf("reevaluateLockReasonFromCache(context, snap)") in 0 until listeners)
    }
}
