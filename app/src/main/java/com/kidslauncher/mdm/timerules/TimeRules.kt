package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.LockReason
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Named time rules and the daily screen-time budget - pure Kotlin, no Android imports, unit-tested
 * in TimeRulesTest. Design: docs/design/06-time-rules.md in the handy workspace. The server sends
 * `PolicyResponse.timePolicy`; an older server's weekday/weekend/bedtime windows are converted with
 * [legacyTimePolicy] (the same algorithm as kid-phone-server's `time_rules::legacy_rules`).
 *
 * Fail closed: a rule whose `days` isn't 7 entries is always active, an out-of-range window covers
 * its whole day, an unknown kind counts as custom, and a budget array of the wrong size means 0
 * minutes every day.
 */

const val MINUTES_PER_DAY = 24 * 60

/** One day's window, minutes since local midnight. `start < end`: that day; `start > end`: ends
 * the next day; `start == end`: 24 hours from [start]. */
@Serializable
data class TimeWindow(val start: Int = 0, val end: Int = 0)

/** `time_policy.rules[]`. [days] is Monday first, `null` = not on that day. */
@Serializable
data class TimeRule(
    val id: Long = 0,
    val name: String = "",
    val kind: String = KIND_CUSTOM,
    val callsAllowed: Boolean = false,
    val exemptApps: List<String> = emptyList(),
    val days: List<TimeWindow?> = emptyList(),
)

const val KIND_SCHOOL = "school"
const val KIND_BEDTIME = "bedtime"
const val KIND_CUSTOM = "custom"

/** `time_policy.lifts[]`: the parent lifted a rule ([TARGET_RULE], [ruleId] `null` = every rule)
 * until [expiresAtMs] (and for at most [minutes]), or added [minutes] of screen time
 * ([TARGET_BUDGET]) to the day the phone first sees it. */
@Serializable
data class Lift(
    val id: Long = 0,
    val target: String = "",
    val ruleId: Long? = null,
    val minutes: Int = 0,
    val expiresAtMs: Long = 0,
)

const val TARGET_RULE = "rule"
const val TARGET_BUDGET = "budget"

/** `PolicyResponse.timePolicy`. [dailyBudgetMinutes]: Monday first, `null` = unlimited. */
@Serializable
data class TimePolicy(
    val rules: List<TimeRule> = emptyList(),
    val dailyBudgetMinutes: List<Int?> = emptyList(),
    val lifts: List<Lift> = emptyList(),
)

/** No rules, no budget. */
val NO_TIME_RULES = TimePolicy(dailyBudgetMinutes = List(7) { null })

/** A rule occurrence: active from [start] (inclusive) to [end] (exclusive), local date-times. */
data class Span(val start: LocalDateTime, val end: LocalDateTime) {
    operator fun contains(t: LocalDateTime) = !t.isBefore(start) && t.isBefore(end)
}

private fun dayIndex(date: LocalDate) = date.dayOfWeek.value - 1

private fun validMinute(m: Int) = m in 0 until MINUTES_PER_DAY

/** The occurrence of [rule] that starts on [date], if any (see [TimeRule] for the fail-closed cases). */
fun spanOn(rule: TimeRule, date: LocalDate): Span? {
    val midnight = date.atStartOfDay()
    if (rule.days.size != 7) return Span(midnight, midnight.plusDays(1))
    val w = rule.days[dayIndex(date)] ?: return null
    if (!validMinute(w.start) || !validMinute(w.end)) return Span(midnight, midnight.plusDays(1))
    val start = midnight.plusMinutes(w.start.toLong())
    val end = when {
        w.start < w.end -> midnight.plusMinutes(w.end.toLong())
        w.start > w.end -> midnight.plusDays(1).plusMinutes(w.end.toLong())
        else -> start.plusDays(1)
    }
    return Span(start, end)
}

/** The occurrence of [rule] covering [t], if it's active then (a span is at most 24 h, so only
 * today's and yesterday's can cover [t]). */
fun activeSpan(rule: TimeRule, t: LocalDateTime): Span? =
    listOfNotNull(spanOn(rule, t.toLocalDate()), spanOn(rule, t.toLocalDate().minusDays(1)))
        .filter { t in it }
        .maxByOrNull { it.end }

fun isActive(rule: TimeRule, t: LocalDateTime): Boolean = activeSpan(rule, t) != null

/** Strongest first: school, bedtime, custom (unknown kinds count as custom), then server order. */
private fun kindRank(kind: String) = when (kind) {
    KIND_SCHOOL -> 0
    KIND_BEDTIME -> 1
    else -> 2
}

fun lockReasonFor(kind: String): LockReason = when (kind) {
    KIND_SCHOOL -> LockReason.SCHOOL
    KIND_BEDTIME -> LockReason.BEDTIME
    else -> LockReason.RULE
}

/** The day's budget in minutes, `null` = unlimited. A malformed array is 0 (restrictive). */
fun budgetMinutesFor(policy: TimePolicy, date: LocalDate): Int? {
    if (policy.dailyBudgetMinutes.size != 7) return 0
    return policy.dailyBudgetMinutes[dayIndex(date)]?.coerceIn(0, MINUTES_PER_DAY)
}

fun hasBudget(policy: TimePolicy): Boolean =
    policy.dailyBudgetMinutes.size != 7 || policy.dailyBudgetMinutes.any { it != null }

/** Which rules a parent's lift has suspended right now. */
data class RuleLifts(val all: Boolean = false, val ruleIds: Set<Long> = emptySet()) {
    fun lifts(rule: TimeRule) = all || rule.id in ruleIds
}

/** Today's screen-time use against the budget. */
data class BudgetUse(val usedMs: Long, val budgetMinutes: Int?, val extraMinutes: Int) {
    val effectiveMinutes: Int? get() = budgetMinutes?.let { it + extraMinutes }
    val exhausted: Boolean get() = effectiveMinutes?.let { usedMs >= it * 60_000L } == true
    val remainingMs: Long? get() = effectiveMinutes?.let { (it * 60_000L - usedMs).coerceAtLeast(0) }
}

/**
 * What the time rules allow right now. [rules]: the active, un-lifted rules, strongest first.
 * [usableApps]: while locked, the apps that stay usable (still only if the allowlist allows them).
 */
data class TimeLock(
    val reason: LockReason,
    val rules: List<TimeRule> = emptyList(),
    val budgetExhausted: Boolean = false,
    val callsAllowed: Boolean = true,
    val usableApps: Set<String> = emptySet(),
    /** When the strongest active rule's current occurrence ends. */
    val until: LocalDateTime? = null,
) {
    val locked: Boolean get() = reason != LockReason.NONE
    val callsBlocked: Boolean get() = locked && !callsAllowed
}

val UNLOCKED = TimeLock(LockReason.NONE)

/**
 * The lock in force at local date-time [at]: nothing while the offline override or the pause is
 * active; otherwise the active rules that no lift suspends (calls only if every one allows them,
 * usable apps = the intersection of their exempt apps); otherwise, with the budget used up,
 * SCREEN_TIME with calls and [messagingApps] usable. Rule exemptions are not cut by the budget.
 */
fun decideTimeLock(
    policy: TimePolicy?,
    at: LocalDateTime,
    overrideActive: Boolean,
    lifts: RuleLifts = RuleLifts(),
    budget: BudgetUse? = null,
    messagingApps: Set<String> = emptySet(),
): TimeLock {
    if (overrideActive || policy == null) return UNLOCKED
    val active = policy.rules.withIndex()
        .filter { (_, rule) -> !lifts.lifts(rule) && isActive(rule, at) }
        .sortedWith(compareBy({ kindRank(it.value.kind) }, { it.index }))
        .map { it.value }
    val exhausted = budget?.exhausted == true
    if (active.isNotEmpty()) {
        val usable = active.map { it.exemptApps.toSet() }.reduce { a, b -> a intersect b }
        return TimeLock(
            reason = lockReasonFor(active.first().kind),
            rules = active,
            budgetExhausted = exhausted,
            callsAllowed = active.all { it.callsAllowed },
            usableApps = usable,
            until = activeSpan(active.first(), at)?.end,
        )
    }
    if (exhausted) return TimeLock(LockReason.SCREEN_TIME, budgetExhausted = true, usableApps = messagingApps)
    return UNLOCKED
}

/** Whether a no-calls rule is in force at [at] - the call path's question ([decideTimeLock]'s
 * [TimeLock.callsBlocked] without the budget, which never blocks calls). */
fun callsBlockedAt(policy: TimePolicy?, at: LocalDateTime, overrideActive: Boolean, lifts: RuleLifts): Boolean =
    decideTimeLock(policy, at, overrideActive, lifts).callsBlocked

/**
 * The next instant after [now] at which the lock may change: a rule occurrence starting or ending
 * within the next 8 days, local midnight while a budget is set (the day's budget resets), and
 * [extraInstants] (lift expiries). `null` = nothing ahead. Local times map to instants with the
 * zone's rules (a time in a DST gap moves later, one in an overlap takes the earlier offset).
 */
fun nextBoundary(policy: TimePolicy?, now: Instant, zone: ZoneId, extraInstants: Collection<Instant> = emptyList()): Instant? {
    val candidates = mutableListOf<Instant>()
    candidates += extraInstants
    if (policy != null) {
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        for (offset in -1L..8L) {
            val date = today.plusDays(offset)
            for (rule in policy.rules) {
                spanOn(rule, date)?.let { span ->
                    candidates += span.start.atZone(zone).toInstant()
                    candidates += span.end.atZone(zone).toInstant()
                }
            }
            if (hasBudget(policy)) candidates += date.atStartOfDay(zone).toInstant()
        }
    }
    return candidates.filter { it.isAfter(now) }.minOrNull()
}

/**
 * An older server's fixed windows as rules (kid-phone-server's `time_rules::legacy_rules` does the
 * same for its migration). Old meaning: the weekday/weekend window is when the phone may be used
 * (outside it: locked), bedtime is when it's locked; `null` or start == end = no restriction; all
 * on the local minute of day. Calls stayed allowed during both. Names are left empty: the lock
 * screen then shows its own localized label for the kind.
 */
fun legacyTimePolicy(
    weekdayStart: Int?,
    weekdayEnd: Int?,
    weekendStart: Int?,
    weekendEnd: Int?,
    bedtimeStart: Int?,
    bedtimeEnd: Int?,
): TimePolicy {
    val rules = mutableListOf<TimeRule>()
    if (bedtimeStart != null && bedtimeEnd != null && bedtimeStart != bedtimeEnd) {
        rules += TimeRule(-1, "", KIND_BEDTIME, callsAllowed = true, days = List(7) { TimeWindow(bedtimeStart, bedtimeEnd) })
    }
    fun allowed(day: Int): TimeWindow? {
        val (s, e) = if (day < 5) weekdayStart to weekdayEnd else weekendStart to weekendEnd
        return if (s == null || e == null || s == e) null else TimeWindow(s, e)
    }
    val windows = (0 until 7).map(::allowed)
    val combinable = windows.all { it != null && it.start < it.end } &&
        (0 until 7).all { d -> windows[(d + 1) % 7]!!.start <= windows[d]!!.end }
    if (combinable) {
        rules += TimeRule(-2, "", KIND_CUSTOM, callsAllowed = true, days = (0 until 7).map { d ->
            TimeWindow(windows[d]!!.end, windows[(d + 1) % 7]!!.start)
        })
    } else {
        val morning = windows.map { w -> w?.takeIf { it.start < it.end && it.start > 0 }?.let { TimeWindow(0, it.start) } }
        val evening = windows.map { w ->
            when {
                w == null -> null
                w.start < w.end -> TimeWindow(w.end, 0)
                else -> TimeWindow(w.end, w.start)
            }
        }
        if (morning.any { it != null }) rules += TimeRule(-2, "", KIND_CUSTOM, callsAllowed = true, days = morning)
        if (evening.any { it != null }) rules += TimeRule(-3, "", KIND_CUSTOM, callsAllowed = true, days = evening)
    }
    return TimePolicy(rules = rules, dailyBudgetMinutes = List(7) { null })
}

/** Local date-time of [instant] in [zone]. */
fun localAt(instant: Instant, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(instant, zone)

/** `HH:mm` of a local date-time, for the lock screen. */
fun LocalDateTime.clockText(): String = "%02d:%02d".format(hour, minute)

/**
 * The device-protected copy of the no-calls rules, read before the first unlock after a reboot
 * (when the CE policy cache can't be read): only each such rule's windows - no names, apps or
 * lifts. Strict: anything but `v == 1` is unreadable, which blocks calls (restrictive).
 */
@Serializable
data class BootCallBlocks(val v: Int = 0, val rules: List<BootBlockRule> = emptyList()) {
    fun blockedAt(t: LocalDateTime): Boolean = rules.any { isActive(TimeRule(days = it.days), t) }
}

@Serializable
data class BootBlockRule(val days: List<TimeWindow?> = emptyList())

const val BOOT_CALL_BLOCKS_VERSION = 1

fun bootCallBlocksOf(policy: TimePolicy?): BootCallBlocks =
    BootCallBlocks(BOOT_CALL_BLOCKS_VERSION, policy?.rules.orEmpty().filter { !it.callsAllowed }.map { BootBlockRule(it.days) })

fun encodeBootCallBlocks(blocks: BootCallBlocks): String =
    com.kidslauncher.mdm.server.ServerJson.encodeToString(BootCallBlocks.serializer(), blocks)

/** `null` = missing, unreadable or another version (the caller then blocks calls). */
fun decodeBootCallBlocks(json: String?): BootCallBlocks? {
    if (json.isNullOrBlank()) return null
    return try {
        com.kidslauncher.mdm.server.ServerJson.decodeFromString(BootCallBlocks.serializer(), json)
            .takeIf { it.v == BOOT_CALL_BLOCKS_VERSION }
    } catch (e: Exception) {
        null
    }
}

/** Identifies what a lock enforces (reason, rules, usable apps, calls) - a change of any of them
 * means the suspension must be re-applied, even when the reason stays the same. */
fun TimeLock.key(): String =
    if (!locked) "NONE" else "$reason|${rules.map { it.id }}|${usableApps.sorted()}|$callsAllowed"
