package com.kidslauncher.mdm.timerules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Design 98 §4.4: Foreldrekode - the parent code's offline pause of the rule(s) in force only, from the time-rule
 * screen's sheet. Pure (no Android imports), pinned by the shared contract `rule_pause.json` (byte-identical with
 * `server/testdata/`, `RulePauseTest`).
 */

/** The launcher posts its parent-code pauses (`POST /api/devices/rule-pause`) and honours `lifts[].phone_key`. */
const val RULE_PAUSE_CAPABILITY = "rule_pause_v1"

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
