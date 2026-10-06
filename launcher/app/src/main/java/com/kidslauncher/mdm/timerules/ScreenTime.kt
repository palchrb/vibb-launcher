package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.timedWindowActive
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Screen-time accounting and lift bookkeeping - pure, unit-tested in ScreenTimeTest. The Android
 * side (TimeRulesRuntime, ScreenTimeTracker) supplies the clocks and persists the results with
 * commit(). Design: 06-time-rules.md ("Ledger", "Lifts").
 */

/** The three clocks every decision here looks at. [bootCount] -1 = unknown (BootClock). */
data class Clocks(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

/** A wall-clock change larger than this (forward) is treated as a jump, not drift. */
const val CLOCK_JUMP_TOLERANCE_MS = 5 * 60 * 1000L

/** At most this many budget-lift ids are remembered (ids only grow on the server). */
private const val MAX_REMEMBERED_LIFTS = 50

/**
 * Today's screen time, persisted. [day] is the local date being counted (ISO), [usedMs] the time
 * counted for it, [extraMinutes] the budget lifts applied to it. The anchor is the "trusted" wall
 * time [anchorWallMs] at elapsed realtime [anchorElapsedMs] in boot [bootCount]: within one boot
 * the trusted clock is the anchor plus elapsed realtime, so setting the wall clock back can't
 * start a new day. [appliedBudgetLifts]: budget lifts already added (to any day), never re-added.
 */
@Serializable
data class ScreenTimeLedger(
    val day: String? = null,
    val usedMs: Long = 0,
    val extraMinutes: Int = 0,
    val anchorWallMs: Long = 0,
    val anchorElapsedMs: Long = 0,
    val bootCount: Int = -1,
    val appliedBudgetLifts: List<Long> = emptyList(),
    /** The stored record couldn't be read today ([unreadableLedger]); reported to the parent. */
    val unreadable: Boolean = false,
) {
    fun date(): LocalDate? = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** [ledger] after looking at the clocks: the trusted time now, and the day it belongs to. */
data class Observation(val ledger: ScreenTimeLedger, val trustedNowMs: Long, val newDayByClock: Boolean)

/**
 * Moves the ledger to "now". Within the same boot the elapsed-realtime estimate (anchor plus
 * elapsed) is the reference: a wall clock behind it by more than [CLOCK_JUMP_TOLERANCE_MS] (set
 * back) is ignored; otherwise the wall clock is followed (drift, NTP, or a jump forward - e.g. NTP
 * after a dead RTC). Usage resets only when the estimate itself passes midnight, or on a new boot
 * (or the first observation) whose wall-clock date is later: a wall clock moved forward within a
 * boot - in one jump or in small steps - moves the date but carries today's usage over. The day
 * never moves backwards, so setting the clock back can't refill the budget either.
 */
fun observe(ledger: ScreenTimeLedger, clocks: Clocks, zone: ZoneId): Observation {
    val sameBoot = ledger.day != null && ledger.bootCount >= 0 && ledger.bootCount == clocks.bootCount &&
        clocks.elapsedMs >= ledger.anchorElapsedMs
    val estimate = if (sameBoot) ledger.anchorWallMs + (clocks.elapsedMs - ledger.anchorElapsedMs) else null
    val trusted = if (estimate != null && clocks.wallMs < estimate - CLOCK_JUMP_TOLERANCE_MS) estimate else clocks.wallMs
    fun dateOf(ms: Long) = LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone)
    val clockDate = dateOf(trusted)
    val current = ledger.date()
    val anchored = ledger.copy(anchorWallMs = trusted, anchorElapsedMs = clocks.elapsedMs, bootCount = clocks.bootCount)
    val resetAllowed = estimate == null || (current != null && dateOf(estimate).isAfter(current))
    return when {
        current == null -> Observation(anchored.copy(day = clockDate.toString(), usedMs = 0, extraMinutes = 0), trusted, true)
        !clockDate.isAfter(current) -> Observation(anchored, trusted, false)
        resetAllowed -> Observation(anchored.copy(day = clockDate.toString(), usedMs = 0, extraMinutes = 0, unreadable = false), trusted, true)
        else -> Observation(anchored.copy(day = clockDate.toString()), trusted, false)
    }
}

/**
 * Adds a counted stretch that started at elapsed realtime [sinceElapsedMs] (this boot) and ends
 * now. If the day turned over by the clock meanwhile, only the part after local midnight counts for
 * the new day. A [sinceElapsedMs] from another boot or in the future adds nothing.
 */
fun accrue(ledger: ScreenTimeLedger, sinceElapsedMs: Long, clocks: Clocks, zone: ZoneId): ScreenTimeLedger {
    val delta = if (ledger.bootCount == clocks.bootCount || ledger.day == null) {
        (clocks.elapsedMs - sinceElapsedMs).coerceAtLeast(0)
    } else {
        0
    }
    val obs = observe(ledger, clocks, zone)
    val counted = if (obs.newDayByClock && ledger.day != null) {
        val midnight = obs.ledger.date()!!.atStartOfDay(zone).toInstant().toEpochMilli()
        delta.coerceAtMost((obs.trustedNowMs - midnight).coerceAtLeast(0))
    } else {
        delta
    }
    return obs.ledger.copy(usedMs = obs.ledger.usedMs + counted)
}

/** Adds each budget lift not applied before to the ledger's current day, once per id. */
fun applyBudgetLifts(ledger: ScreenTimeLedger, lifts: List<Lift>): ScreenTimeLedger {
    val fresh = lifts.filter { it.target == TARGET_BUDGET && it.id !in ledger.appliedBudgetLifts }
    if (fresh.isEmpty()) return ledger
    val extra = (ledger.extraMinutes + fresh.sumOf { it.minutes.coerceIn(0, MINUTES_PER_DAY) }).coerceAtMost(MINUTES_PER_DAY)
    val applied = (ledger.appliedBudgetLifts + fresh.map { it.id }).distinct().sortedDescending().take(MAX_REMEMBERED_LIFTS)
    return ledger.copy(extraMinutes = extra, appliedBudgetLifts = applied)
}

/** Today's use against the policy's budget, from an observed ledger. */
fun budgetUse(ledger: ScreenTimeLedger, policy: TimePolicy): BudgetUse? {
    val date = ledger.date() ?: return null
    return BudgetUse(ledger.usedMs, budgetMinutesFor(policy, date), ledger.extraMinutes)
}

/**
 * When the phone first saw a rule lift: the lift lasts while it is in the policy, until
 * [start].untilWallMs, and for at most [durationMs] of elapsed realtime in that boot
 * ([timedWindowActive] - a reboot or a clock set back can't stretch it).
 */
@Serializable
data class LiftRecord(
    val id: Long,
    val untilWallMs: Long,
    val elapsedStartMs: Long,
    val bootCount: Int,
    val durationMs: Long,
) {
    val start: WindowStart get() = WindowStart(untilWallMs, elapsedStartMs, bootCount)
}

/** Records for the rule lifts in [lifts]: existing ones kept, new ones started now, the rest
 * dropped (a lift the server no longer sends has ended or was ended early). */
fun updateLiftRecords(records: List<LiftRecord>, lifts: List<Lift>, clocks: Clocks): List<LiftRecord> {
    val known = records.associateBy { it.id }
    return lifts.filter { it.target == TARGET_RULE }.map { lift ->
        known[lift.id] ?: run {
            val until = minOf(lift.expiresAtMs, clocks.wallMs + lift.minutes.coerceAtLeast(0) * 60_000L)
            LiftRecord(lift.id, until, clocks.elapsedMs, clocks.bootCount, (until - clocks.wallMs).coerceAtLeast(0))
        }
    }
}

fun liftRecordActive(record: LiftRecord, clocks: Clocks): Boolean =
    record.durationMs > 0 && timedWindowActive(record.start, clocks.wallMs, clocks.elapsedMs, clocks.bootCount, record.durationMs)

/** Which rules are lifted now: by rule lifts in [lifts] whose record is still active. */
fun activeRuleLifts(records: List<LiftRecord>, lifts: List<Lift>, clocks: Clocks): RuleLifts {
    val byId = records.associateBy { it.id }
    val active = lifts.filter { it.target == TARGET_RULE && byId[it.id]?.let { r -> liftRecordActive(r, clocks) } == true }
    return RuleLifts(all = active.any { it.ruleId == null }, ruleIds = active.mapNotNull { it.ruleId }.toSet())
}

/** Wall-clock instants at which the active rule lifts end (for the boundary alarm). */
fun liftExpiries(records: List<LiftRecord>, clocks: Clocks): List<Instant> =
    records.filter { liftRecordActive(it, clocks) }.map {
        Instant.ofEpochMilli(minOf(it.untilWallMs, clocks.wallMs + (it.elapsedStartMs + it.durationMs - clocks.elapsedMs)))
    }

/** The ids of the lifts in force: active rule lifts, and budget lifts applied to today. */
fun activeLiftIds(records: List<LiftRecord>, lifts: List<Lift>, ledger: ScreenTimeLedger, clocks: Clocks): List<Long> {
    val byId = records.associateBy { it.id }
    return lifts.filter { lift ->
        when (lift.target) {
            TARGET_RULE -> byId[lift.id]?.let { liftRecordActive(it, clocks) } == true
            TARGET_BUDGET -> lift.id in ledger.appliedBudgetLifts
            else -> false
        }
    }.map { it.id }
}

/** Our screens whose time is free: the lock screen, the phone book, our in-call screen and
 * Settings. Home is not on the list - other apps can be visible over it (picture-in-picture,
 * overlays), QA step 6 #2. */
val FREE_SCREENS = setOf(
    "com.kidslauncher.mdm.ui.LockActivity",
    "com.kidslauncher.mdm.lock.PinLockActivity",
    "com.kidslauncher.mdm.calls.PhoneBookActivity",
    "com.kidslauncher.mdm.calls.InCallActivity",
    "com.kidslauncher.mdm.ui.settings.SettingsActivity",
)

/**
 * Whether screen time counts right now: the screen is on and unlocked - neither Android's keyguard
 * (unmigrated phones, the boot window) nor handy's PIN lock ([pinLocked], step 10, QA 10 #13; a
 * VoIP call the lock steps aside for counts - [voipExempt], design 17 QA #9: up to 3 h of the app
 * over the lock must not be free) - unless one of [FREE_SCREENS]
 * is in front and not sharing the screen (split screen / picture-in-picture). A call doesn't stop
 * the count by itself - only our own in-call screen in front does (QA step 6 #1: otherwise a call
 * or any app claiming a VoIP audio mode made every app free).
 */
fun screenTimeCounts(
    interactive: Boolean,
    keyguardLocked: Boolean,
    freeScreenInFront: Boolean,
    freeScreenSharesScreen: Boolean,
    pinLocked: Boolean = false,
    voipExempt: Boolean = false,
): Boolean = interactive && !keyguardLocked && !(pinLocked && !voipExempt) && !(freeScreenInFront && !freeScreenSharesScreen)

/**
 * The ledger to use when the stored one can't be read (QA step 6 #6): today counts as used up
 * (more than any budget plus extra minutes), and every budget lift still being delivered counts as
 * applied, so a broken record can neither refill the day nor re-add lifts.
 */
fun unreadableLedger(clocks: Clocks, zone: ZoneId, lifts: List<Lift>): ScreenTimeLedger {
    val fresh = observe(ScreenTimeLedger(), clocks, zone).ledger
    return fresh.copy(
        usedMs = 2L * MINUTES_PER_DAY * 60_000L,
        appliedBudgetLifts = lifts.filter { it.target == TARGET_BUDGET }.map { it.id },
        unreadable = true,
    )
}
