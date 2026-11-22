package com.kidslauncher.mdm.timerules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Design 98 §4.4: Foreldrekode - the parent code's offline pause of the rule(s) in force only, from the time-rule
 * screen's sheet. Pure (no Android imports), pinned by the shared contract `rule_pause.json` (byte-identical with
 * `server/testdata/`, `RulePauseTest`).
 */

/** The PWA's own lengths for a rule lift (the server's `time_rules::RULE_LIFT_MINUTES`); the phone offers no other. */
val RULE_LIFT_MINUTES: List<Int> = listOf(15, 30, 60, 120)

/** One rule of [RulePauseRequest]: its id (a copy's negative id too) and its own end. */
@Serializable
data class RulePauseRuleBody(
    @SerialName("rule_id") val ruleId: Long,
    @SerialName("until_ms") val untilMs: Long,
)

/** The exact `POST /api/devices/rule-pause` body (§4.4.4). */
@Serializable
data class RulePauseRequest(
    val key: String,
    val minutes: Int,
    @SerialName("started_at_ms") val startedAtMs: Long,
    val rules: List<RulePauseRuleBody>,
)

/** The post's `200` answer: accepted with the server's lift ids (and `over` for a pause already over), or not
 * (`reason`: `unknown_rule`). */
@Serializable
data class RulePauseAnswer(
    val accepted: Boolean = false,
    @SerialName("lift_ids") val liftIds: List<Long> = emptyList(),
    val over: Boolean = false,
    val reason: String? = null,
)

/** §4.4.3: one rule's window of a parent-code pause (`timedWindowActive`'s [untilWallMs] and [durationMs]). */
@Serializable
data class PhonePauseRule(val id: Long, val untilWallMs: Long, val durationMs: Long)

/**
 * §4.4.3: `phone_lifts_v1` - a parent-code pause as the gate stored it: its [key], [minutes], each rule's own window,
 * the elapsed time and boot it started in. No PIN material. Only `ParentPin.kt` writes it.
 */
@Serializable
data class PhonePause(
    val key: String,
    val minutes: Int,
    val rules: List<PhonePauseRule>,
    val elapsedStartMs: Long,
    val bootCount: Int,
)

/** §4.4.3: `phone_lifts_sync_v1` - the server's side of one pause: [posted] (done: 200, a final 4xx, or dropped),
 * [ackedAtWallMs] (the 200's arrival, or the GET start of a policy carrying the key), the server's [liftIds]. */
@Serializable
data class PhonePauseSync(
    val key: String,
    val posted: Boolean,
    val ackedAtWallMs: Long?,
    val liftIds: List<Long>,
)

/** One served lift with a phone key, as [PausePolicyView] keeps it: the key and the rule (a twin: the copy's negative
 * id). */
@Serializable
data class PausePolicyLift(val key: String, val ruleId: Long)

/** The newest accepted policy as the pauses read it: when its GET started, and its lifts with a phone key, each with its
 * rule (code QA M1: the PWA's "Avslutt nå" ends one rule's row of a pause). */
@Serializable
data class PausePolicyView(val getStartedMs: Long, val lifts: Set<PausePolicyLift>) {
    /** The phone keys the policy carries (the ack). */
    val keys: Set<String> get() = lifts.mapTo(HashSet()) { it.key }
}

/** The view of a policy fetched with its GET started at [getStartedMs]: every lift with a phone key and a rule. */
fun pausePolicyView(policy: TimePolicy?, getStartedMs: Long): PausePolicyView = PausePolicyView(
    getStartedMs,
    policy?.lifts.orEmpty().mapNotNullTo(LinkedHashSet<PausePolicyLift>()) { l ->
        val key = l.phoneKey
        val ruleId = l.ruleId
        if (key != null && ruleId != null) PausePolicyLift(key, ruleId) else null
    },
)

/**
 * The rules the time-rule screen's title names, of those locking now: a design 93 copy is named by the rule it copies
 * when that rule locks too (one name, not two), and by itself when it doesn't - a rule turned on that is switched off
 * for the phone, or one without days (design 123 QA L2).
 */
fun pauseTitleRules(rules: List<TimeRule>): List<TimeRule> =
    rules.filter { it.id > 0 || it.copyOf == null || rules.none { r -> r.id == it.copyOf } }

/** §4.4.2: the offered lengths and, for a chosen length, each rule's own end. */
data class RulePausePlan(val offered: List<Int>, val rules: List<RulePauseRuleBody>)

private const val MINUTE_MS = 60_000L

/** §4.4.3: an entry is kept until its last end has passed by the clock slack, at most [PHONE_PAUSES_MAX] entries. */
const val PHONE_PAUSE_SLACK_MS = 5 * 60_000L
const val PHONE_PAUSES_MAX = 8

/** §4.4.5: the server takes no start older than this; an entry never posted is kept until then (code QA L5). */
const val PHONE_PAUSE_POST_MAX_AGE_MS = 7 * 24 * 60 * 60_000L

/**
 * §4.4.2 (`rulePausePlan`): the active, un-lifted rules in force at [at] ([policy] already `inForceAt` now), each with its
 * own end - `min(now + minutes, that rule's current occurrence end, its until_ms)`; a design 93 copy with `copy_of` is
 * paused with the rule it copies (the same end). Nothing while the budget is used up as well (Q98-R3-L4) or nothing is
 * in force. A length is offered only when it ends no later than the latest end among the rules in force (the shortest
 * always, when none does: each rule's end is capped by its own anyway). [minutes] `null`: the lengths only.
 */
fun rulePausePlan(policy: TimePolicy, at: LocalDateTime, zone: ZoneId, lifts: RuleLifts, budgetExhausted: Boolean, minutes: Int?, nowMs: Long): RulePausePlan {
    if (budgetExhausted) return RulePausePlan(emptyList(), emptyList())
    val active = policy.rules.filter { !lifts.lifts(it) && isActive(it, at) }
    if (active.isEmpty()) return RulePausePlan(emptyList(), emptyList())
    val ends = active.associateWith { rule ->
        val occurrence = activeSpan(rule, at)!!.end.atZone(zone).toInstant().toEpochMilli()
        minOf(occurrence, rule.untilMs ?: Long.MAX_VALUE)
    }
    val latest = ends.values.max()
    val offered = RULE_LIFT_MINUTES.filter { nowMs + it * MINUTE_MS <= latest }.ifEmpty { RULE_LIFT_MINUTES.take(1) }
    if (minutes == null || minutes !in RULE_LIFT_MINUTES) return RulePausePlan(offered, emptyList())
    val byId = linkedMapOf<Long, Long>()
    for ((rule, end) in ends) {
        val until = minOf(nowMs + minutes * MINUTE_MS, end)
        byId[rule.id] = maxOf(byId[rule.id] ?: 0L, until)
        rule.copyOf?.let { copied -> byId[copied] = maxOf(byId[copied] ?: 0L, until) }
    }
    return RulePausePlan(offered, byId.entries.sortedBy { it.key }.map { RulePauseRuleBody(it.key, it.value) })
}

/** The stored entry of a plan's rules, started at [clocks] (`LiftRecord`-style windows). */
fun phonePauseOf(key: String, minutes: Int, rules: List<RulePauseRuleBody>, clocks: Clocks): PhonePause = PhonePause(
    key = key,
    minutes = minutes,
    rules = rules.map { PhonePauseRule(it.ruleId, it.untilMs, (it.untilMs - clocks.wallMs).coerceAtLeast(0)) },
    elapsedStartMs = clocks.elapsedMs,
    bootCount = clocks.bootCount,
)

/** When [entry] started (the gate's wall clock). */
fun phonePauseStartMs(entry: PhonePause): Long = entry.rules.firstOrNull()?.let { it.untilWallMs - it.durationMs } ?: 0L

/**
 * The entries after the gate adds [added]: the ones still within their last end plus the slack, and the ones never
 * [posted] whose start the server still takes (code QA L5: the dead pause is still posted, §4.4.3) - at most
 * [PHONE_PAUSES_MAX], the oldest posted dropped first, then the oldest.
 */
fun phonePausesAfterAdd(entries: List<PhonePause>, added: PhonePause, nowMs: Long, posted: Set<String>): List<PhonePause> {
    val kept = entries.filterTo(ArrayList()) { e ->
        e.key != added.key && (
            (e.rules.maxOfOrNull { it.untilWallMs } ?: 0L) + PHONE_PAUSE_SLACK_MS > nowMs ||
                (e.key !in posted && phonePauseStartMs(e) + PHONE_PAUSE_POST_MAX_AGE_MS > nowMs)
            )
    }
    while (kept.size >= PHONE_PAUSES_MAX) {
        val oldestPosted = kept.indexOfFirst { it.key in posted }
        kept.removeAt(if (oldestPosted >= 0) oldestPosted else 0)
    }
    return kept + added
}

/**
 * §4.4.3 (`phonePauseLifts`): the rules [entry] still lifts - each rule's window is active (a reboot or the clock set
 * back ends it at once) and the pause isn't acked yet, or the newest policy whose GET started after the ack still
 * carries a lift with its key **for that rule** (or there is no such policy yet): the PWA's "Avslutt nå" on one rule of
 * a two-rule pause ends that rule alone (code QA M1). An unknown or pruned entry lifts nothing, whatever the policy says.
 */
fun phonePauseLifts(entry: PhonePause?, sync: PhonePauseSync?, policy: PausePolicyView?, clocks: Clocks): Set<Long> {
    if (entry == null) return emptySet()
    val acked = sync?.ackedAtWallMs
    val judged = if (acked == null || policy == null || policy.getStartedMs <= acked) null else policy
    return entry.rules.filter { r ->
        (judged == null || PausePolicyLift(entry.key, r.id) in judged.lifts) && r.durationMs > 0 && com.kidslauncher.mdm.server.timedWindowActive(
            com.kidslauncher.mdm.server.WindowStart(r.untilWallMs, entry.elapsedStartMs, entry.bootCount),
            clocks.wallMs, clocks.elapsedMs, clocks.bootCount, r.durationMs,
        )
    }.map { it.id }.toSet()
}

/** The wall-clock ends of the rules [entry] still lifts (the boundary alarm, §4.4.1 step 4). */
fun phonePauseExpiries(entry: PhonePause, lifted: Set<Long>, clocks: Clocks): List<java.time.Instant> =
    entry.rules.filter { it.id in lifted }.map { r ->
        java.time.Instant.ofEpochMilli(minOf(r.untilWallMs, clocks.wallMs + (entry.elapsedStartMs + r.durationMs - clocks.elapsedMs)))
    }

/** The post's outcome for the phone (§4.4.4): acked with the server's ids, done (a final answer), or retry. */
enum class RulePauseAction(val wire: String) { ACKED("acked"), DONE("done"), RETRY("retry") }

/** §4.4.4: 200 accepted acks; 200 not accepted and every 4xx are final; a 404 (a rolled-back server) is retried until
 * the pause's last end, then dropped; a 5xx or no answer ([status] `null`) is retried. */
fun rulePauseAction(status: Int?, answer: RulePauseAnswer?, pastEnd: Boolean): RulePauseAction = when {
    status == null -> RulePauseAction.RETRY
    status in 200..299 -> if (answer?.accepted == true) RulePauseAction.ACKED else RulePauseAction.DONE
    status == 404 -> if (pastEnd) RulePauseAction.DONE else RulePauseAction.RETRY
    status in 400..499 -> RulePauseAction.DONE
    else -> RulePauseAction.RETRY
}

/** §4.4.4: the sync record after an accepted policy fetched at [view] - a policy carrying the key acks an un-acked
 * pause (whichever comes first). */
fun syncAfterPolicy(sync: PhonePauseSync?, key: String, view: PausePolicyView): PhonePauseSync? = when {
    key !in view.keys -> sync
    sync == null -> PhonePauseSync(key, posted = false, ackedAtWallMs = view.getStartedMs, liftIds = emptyList())
    sync.ackedAtWallMs == null -> sync.copy(ackedAtWallMs = view.getStartedMs)
    else -> sync
}
