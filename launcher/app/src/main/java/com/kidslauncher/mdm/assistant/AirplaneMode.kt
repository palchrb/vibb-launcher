package com.kidslauncher.mdm.assistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Design 98: Flymodus through the Digital assistant role - the pure parts (no Android imports), pinned by the shared
 * contract `airplane_mode.json` (byte-identical with `server/testdata/`, `AirplaneModeTest`).
 */

/** The launcher reports `airplane_mode` and shows the kid's Flymodus row (bit 64); the server's Home page shows its
 * switch only for a phone claiming it. */
const val AIRPLANE_MODE_CAPABILITY = "airplane_mode_v1"

/** One airplane-mode period (§5.1): [onBy] `kid` or `other`; [offBy] `kid`, `parent`, `emergency` or `other`. Only the
 * last period may be open ([offMs]/[offBy] `null`). */
@Serializable
data class AirplanePeriod(
    @SerialName("on_ms") val onMs: Long,
    @SerialName("on_by") val onBy: String,
    @SerialName("off_ms") val offMs: Long?,
    @SerialName("off_by") val offBy: String?,
)

/** `airplane_mode` in a full report (§5.1; not a heartbeat field). Every key always sent. */
@Serializable
data class AirplaneModeReport(
    /** `AIRPLANE_MODE_ON`. */
    val on: Boolean,
    /** True only in the report-only post before a turn-on (§2.2 step 2). */
    @SerialName("going_on") val goingOn: Boolean,
    /** `RoleManager.isRoleHeld(ROLE_ASSISTANT)`. */
    val assistant: Boolean,
    /** Wi-Fi stays on in airplane mode (§3.5): `null` never set up. */
    @SerialName("wifi_kept") val wifiKept: Boolean?,
    /** The last failed or refused turn-on within 24 h; `cooldown` is never sent. */
    @SerialName("last_error") val lastError: String?,
    /** Roles §3.2's window moved and couldn't take back. */
    @SerialName("default_apps_changed") val defaultAppsChanged: List<String>,
    /** At most [MAX_AIRPLANE_PERIODS], the last 7 days, oldest first. */
    val periods: List<AirplanePeriod>,
)

const val MAX_AIRPLANE_PERIODS = 10

/** The words of [AirplanePeriod.onBy] and [AirplanePeriod.offBy]. */
object AirplaneBy {
    const val KID = "kid"
    const val PARENT = "parent"
    const val EMERGENCY = "emergency"
    const val OTHER = "other"
}

/** The refusals and failures of a turn-on (§2.2), `last_error`'s words. */
object AirplaneError {
    const val ROUTE_MISSING = "route_missing"
    const val REFUSED = "refused"
    const val TIMEOUT = "timeout"
    const val EMERGENCY = "emergency"
    const val COOLDOWN = "cooldown"
}

/** §2.2 step 1: a turn-on less than this after the last one started is refused (`cooldown`). */
const val AIRPLANE_COOLDOWN_MS = 10_000L

/** §2.2 step 1 and D3: no turn-on this long after an emergency call (`emergency`). */
const val RECENT_EMERGENCY_CALL_MS = 10 * 60_000L

/** §5.1: Android ending airplane mode at most this long after [com.kidslauncher.mdm.calls.CallSystem.emergencyPlaced]
 * counts as the emergency call's doing. */
const val EMERGENCY_OFF_WINDOW_MS = 10_000L

/** §5.1: Android turning airplane mode on at most this long after the route started counts as the kid's. */
const val KID_ON_WINDOW_MS = 5_000L

/** §5.1: the periods kept, from the last week. */
const val AIRPLANE_PERIODS_MAX_AGE_MS = 7 * 24 * 60 * 60_000L

/** The answer of [airplaneOnPlan]: [GO], or the refusal ([AirplaneError] words, and [ALREADY_ON]). */
object AirplaneOn {
    const val GO = "go"
    const val ALREADY_ON = "already_on"
}

/**
 * §2.2 step 1, the refusals in order: the role or the service missing, the parent's `DISALLOW_AIRPLANE_MODE`, airplane
 * mode already on, an emergency call or callback mode ([emergency], [emergencyBlocks]), the cool-down. Takes no time
 * rule: turning airplane mode on is always allowed under a rule (the user, 2026-11-14; `rules.on` rows).
 */
fun airplaneOnPlan(roleHeld: Boolean, serviceReady: Boolean, restricted: Boolean, on: Boolean, emergency: Boolean, cooldown: Boolean): String = when {
    !roleHeld || !serviceReady -> AirplaneError.ROUTE_MISSING
    restricted -> AirplaneError.REFUSED
    on -> AirplaneOn.ALREADY_ON
    emergency -> AirplaneError.EMERGENCY
    cooldown -> AirplaneError.COOLDOWN
    else -> AirplaneOn.GO
}

/** D3: Emergency Callback Mode, an emergency call within [RECENT_EMERGENCY_CALL_MS], or Vibb's own callback window. */
fun emergencyBlocks(ecbm: Boolean, lastEmergencyMs: Long?, nowMs: Long, callbackWindowOpen: Boolean): Boolean =
    ecbm || callbackWindowOpen || (lastEmergencyMs != null && nowMs - lastEmergencyMs in 0 until RECENT_EMERGENCY_CALL_MS)

/** Whether a turn-on now falls in the cool-down after the one that started at [lastStartMs]. */
fun inCooldown(lastStartMs: Long?, nowMs: Long): Boolean = lastStartMs != null && nowMs - lastStartMs in 0 until AIRPLANE_COOLDOWN_MS

/**
 * §5.1 (`emergency.off_by`): who ended airplane mode at [offMs]. [byVibb] is Vibb's own doing (`kid` for §2.3, `parent`
 * for the hardening switch); otherwise an emergency call placed at most [EMERGENCY_OFF_WINDOW_MS] before, or callback
 * mode; otherwise `other`.
 */
fun offByOf(byVibb: String?, placedMs: Long?, offMs: Long, ecbm: Boolean): String = when {
    byVibb != null -> byVibb
    ecbm -> AirplaneBy.EMERGENCY
    placedMs != null && offMs - placedMs in 0..EMERGENCY_OFF_WINDOW_MS -> AirplaneBy.EMERGENCY
    else -> AirplaneBy.OTHER
}

/** §5.1: who turned airplane mode on at [onMs] - the kid when §2.2 started at most [KID_ON_WINDOW_MS] before. */
fun onByOf(routeStartMs: Long?, onMs: Long): String =
    if (routeStartMs != null && onMs - routeStartMs in 0..KID_ON_WINDOW_MS) AirplaneBy.KID else AirplaneBy.OTHER

/**
 * The periods after an edge (§5.1, `AirplaneLog`): on opens a period unless one is open; off closes the open one
 * (an off with none open records nothing). Then [prunedPeriods].
 */
fun periodsAfterEdge(periods: List<AirplanePeriod>, on: Boolean, atMs: Long, by: String, nowMs: Long): List<AirplanePeriod> {
    val open = periods.lastOrNull()?.takeIf { it.offMs == null }
    val next = when {
        on && open == null -> periods + AirplanePeriod(atMs, by, null, null)
        !on && open != null -> periods.dropLast(1) + open.copy(offMs = maxOf(atMs, open.onMs), offBy = by)
        else -> periods
    }
    return prunedPeriods(next, nowMs)
}

/** The periods the report carries: closed ones from the last week, at most [MAX_AIRPLANE_PERIODS], oldest first. */
fun prunedPeriods(periods: List<AirplanePeriod>, nowMs: Long): List<AirplanePeriod> =
    periods.filter { p -> p.offMs == null || nowMs - p.offMs < AIRPLANE_PERIODS_MAX_AGE_MS }.takeLast(MAX_AIRPLANE_PERIODS)

/** What Vibb does to airplane mode at a time-rule edge (§7.2): nothing, ever - Vibb never ends airplane mode because a
 * rule starts, ends, is paused or lifted (the kid could be on a flight). `rules.edges` pins it. */
@Suppress("UNUSED_PARAMETER")
fun airplaneActionAtRuleEdge(edge: String, airplaneOn: Boolean): String? = null
