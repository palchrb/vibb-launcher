package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.Clocks
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import com.kidslauncher.mdm.timerules.Lift
import com.kidslauncher.mdm.timerules.LiftRecord
import com.kidslauncher.mdm.timerules.RuleLifts
import com.kidslauncher.mdm.timerules.TARGET_RULE
import com.kidslauncher.mdm.timerules.TimeLock
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.budgetMinutesFor
import com.kidslauncher.mdm.timerules.decideTimeLock
import com.kidslauncher.mdm.timerules.liftExpiry
import com.kidslauncher.mdm.timerules.liftRecordActive
import com.kidslauncher.mdm.ui.LockGlyph
import com.kidslauncher.mdm.ui.RuleLabel
import com.kidslauncher.mdm.ui.ruleLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * The time rule's card on the lock's glance page (design r13 §1, `RuleCardTest`): pure, fed by the snapshot that
 * `TimeRulesRuntime.recheck` computes once. Information only - what the lock allows is `decideTimeLock`'s, never this.
 */

/** The card's kind: its glyph ([glyph]) and its fallback label (`ruleLabel`, r13 §1.2). */
enum class RuleCardKind { SCHOOL, BEDTIME, RULE, SCREEN_TIME }

/** A rule's kind as the card's: school, bedtime, else (custom or unknown) a rule; no rule = the used-up budget. */
fun ruleCardKind(ruleKind: String?): RuleCardKind = when (ruleKind) {
    null -> RuleCardKind.SCREEN_TIME
    KIND_SCHOOL -> RuleCardKind.SCHOOL
    KIND_BEDTIME -> RuleCardKind.BEDTIME
    else -> RuleCardKind.RULE
}

/** The time-rule screen's glyph for the kind (`lockGlyph`). */
fun RuleCardKind.glyph(): LockGlyph = when (this) {
    RuleCardKind.SCHOOL -> LockGlyph.BOOK
    RuleCardKind.BEDTIME -> LockGlyph.MOON
    RuleCardKind.RULE -> LockGlyph.DOT
    RuleCardKind.SCREEN_TIME -> LockGlyph.HOURGLASS
}

/** What the card says: [kind], [name] the rule's own name (null = the kind's label), [until] the certain end. */
data class RuleCard(val kind: RuleCardKind, val name: String?, val until: LocalDateTime?) {
    /** Line 1, as the time-rule screen's title: the rule's own name, else the kind's label. */
    val label: RuleLabel get() = name?.let { RuleLabel.Named(it) } ?: RuleLabel.Kind(kind)
}

/**
 * The card for the lock in force: null unless [reason] (the enforced `mdm.lockReason()`) and [lock] (the snapshot's
 * decision) both lock, with the same reason - the pref is what enforcement and the time-rule screen go by, the snapshot
 * gives the details; a moment where they disagree shows no card. The strongest rule's kind and name
 * (`lock.rules.first()`), else SCREEN_TIME with no name and no end.
 */
fun ruleCard(reason: LockReason, lock: TimeLock, end: LocalDateTime?): RuleCard? {
    if (reason == LockReason.NONE || !lock.locked || reason != lock.reason) return null
    val rule = lock.rules.firstOrNull() ?: return RuleCard(RuleCardKind.SCREEN_TIME, null, null)
    val name = (ruleLabel(rule.kind, rule.name) as? RuleLabel.Named)?.name
    return RuleCard(ruleCardKind(rule.kind), name, end)
}

/**
 * The lock's certain end, or null. [TimeLock.until] (the strongest rule's occurrence end) when all of:
 * - it is a single instant in [zone] (not in a DST gap or overlap);
 * - the decision at `until` is unlocked, with the policy, the rule lifts in force *at* `until` ([liftsAtEnd]) and the
 *   budget of `until`'s own day: today's [budget] when `until.toLocalDate() == ledgerDay`, else a fresh day's
 *   (`budgetMinutesFor`; a budget lift applies to the day the phone first sees it, so the next day starts with none).
 * Null for SCREEN_TIME (the parent may add time; midnight is no promise either).
 */
fun lockEnd(
    policy: TimePolicy,
    lock: TimeLock,
    liftsAtEnd: RuleLifts,
    budget: BudgetUse?,
    ledgerDay: LocalDate?,
    zone: ZoneId,
): LocalDateTime? {
    if (!lock.locked || lock.rules.isEmpty() || lock.reason == LockReason.SCREEN_TIME) return null
    val until = lock.until ?: return null
    if (zone.rules.getValidOffsets(until).size != 1) return null
    val day = until.toLocalDate()
    val budgetAtEnd = if (day == ledgerDay) budget else BudgetUse(0, budgetMinutesFor(policy, day), 0)
    val after = decideTimeLock(policy, until, overrideActive = false, lifts = liftsAtEnd, budget = budgetAtEnd)
    return if (after.locked) null else until
}

/** The rule lifts still in force at [at]: those active now ([liftRecordActive]) whose expiry is at or after [at]
 * (r13 R13-6: a lift ending before the rule's end can't make that end certain). */
fun ruleLiftsAt(records: List<LiftRecord>, lifts: List<Lift>, clocks: Clocks, at: Instant): RuleLifts {
    val byId = records.associateBy { it.id }
    val active = lifts.filter { lift ->
        lift.target == TARGET_RULE &&
            byId[lift.id]?.let { liftRecordActive(it, clocks) && !liftExpiry(it, clocks).isBefore(at) } == true
    }
    return RuleLifts(all = active.any { it.ruleId == null }, ruleIds = active.mapNotNull { it.ruleId }.toSet())
}

/** [lockEnd] for a snapshot: the lifts at the end built from its records, its budget, its ledger's day and zone. */
fun lockEndOf(snap: TimeRulesRuntime.Snapshot): LocalDateTime? {
    val policy = snap.policy ?: return null
    val until = snap.lock.until ?: return null
    val at = until.atZone(snap.zone).toInstant()
    return lockEnd(
        policy, snap.lock, ruleLiftsAt(snap.records, policy.lifts, snap.clocks, at), snap.budget, snap.ledger?.date(), snap.zone,
    )
}

/** The glance's card for [reason] and [snap] (r13 §1.1's glue). */
fun ruleCardOf(reason: LockReason, snap: TimeRulesRuntime.Snapshot): RuleCard? =
    if (reason == LockReason.NONE) null else ruleCard(reason, snap.lock, lockEndOf(snap))
