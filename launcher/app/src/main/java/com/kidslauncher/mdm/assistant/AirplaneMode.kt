package com.kidslauncher.mdm.assistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Design 98: Flymodus through the Digital assistant role - the pure parts (no Android imports), pinned by the shared
 * contract `airplane_mode.json` (in `contracts/core/`, `AirplaneModeTest`).
 */

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

/** §5.1: Android turning airplane mode on at most this long after the kid's route showed its session counts as the
 * kid's (`AirplaneRuntime.recordKidOnStart`). */
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

/** D3: Emergency Callback Mode, or an emergency call within [RECENT_EMERGENCY_CALL_MS]. Not Vibb's own 60-min callback
 * window (the user, 2026-11-15: "112 10 min er greit. Dette er et edge case"). */
fun emergencyBlocks(ecbm: Boolean, lastEmergencyMs: Long?, nowMs: Long): Boolean =
    ecbm || (lastEmergencyMs != null && nowMs - lastEmergencyMs in 0 until RECENT_EMERGENCY_CALL_MS)

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

/** §5.1: who turned airplane mode on at [onMs] - the kid when the kid's §2.2 showed its session at most
 * [KID_ON_WINDOW_MS] before. */
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

/** §4.1: the kid's Flymodus row ([wire] = `airplane_mode.json`'s `state`). */
enum class AirplaneRowState(val wire: String) {
    HIDDEN("hidden"),
    OFF_ONLY("off_only"),
    RESTRICTED("restricted"),
    BUSY_ON("busy_on"),
    BUSY_OFF("busy_off"),
    EMERGENCY("emergency"),
    COOLDOWN("cooldown"),
    FAILED("failed"),
    ON_WIFI("on_wifi"),
    ON_OFFLINE("on_offline"),
    OFF("off"),
    ;

    /** The switch as it is (busy: as it will be). */
    val checked: Boolean get() = this == OFF_ONLY || this == BUSY_ON || this == ON_WIFI || this == ON_OFFLINE

    /** A plain tap does something: on, off, Off-only, try again. */
    val clickable: Boolean get() = this == OFF || this == OFF_ONLY || this == ON_WIFI || this == ON_OFFLINE || this == FAILED

    /** Off at 50 %: the parent decides, or right after an emergency call. */
    val dimmed: Boolean get() = this == RESTRICTED || this == EMERGENCY
}

/** §4.1: a failed turn-on's line shows this long (or until the next tap). */
const val AIRPLANE_FAILED_LINE_MS = 60_000L

/**
 * §4.1's states in priority order. The row shows with bit 64 and the role, and - Off-only (Q98-M5) - whenever airplane
 * mode is on, whatever the bit and the role. [busy] `on`/`off` while §2.2/§2.3 runs. [lastError] with its age: the
 * emergency refusal for [RECENT_EMERGENCY_CALL_MS], the cool-down for [AIRPLANE_COOLDOWN_MS], a failure for
 * [AIRPLANE_FAILED_LINE_MS].
 */
fun airplaneRow(bit: Boolean, role: Boolean, restricted: Boolean, on: Boolean, wifiUp: Boolean, busy: String?, lastError: String?, errorAgeMs: Long?): AirplaneRowState {
    val full = bit && role
    if (!on && !full) return AirplaneRowState.HIDDEN
    if (busy == "off") return AirplaneRowState.BUSY_OFF
    if (busy == "on") return AirplaneRowState.BUSY_ON
    if (on && !full) return AirplaneRowState.OFF_ONLY
    if (restricted) return AirplaneRowState.RESTRICTED
    if (on) return if (wifiUp) AirplaneRowState.ON_WIFI else AirplaneRowState.ON_OFFLINE
    val age = errorAgeMs ?: Long.MAX_VALUE
    return when {
        lastError == AirplaneError.EMERGENCY && age in 0 until RECENT_EMERGENCY_CALL_MS -> AirplaneRowState.EMERGENCY
        lastError == AirplaneError.COOLDOWN && age in 0 until AIRPLANE_COOLDOWN_MS -> AirplaneRowState.COOLDOWN
        (lastError == AirplaneError.TIMEOUT || lastError == AirplaneError.ROUTE_MISSING) && age in 0 until AIRPLANE_FAILED_LINE_MS -> AirplaneRowState.FAILED
        else -> AirplaneRowState.OFF
    }
}

/** §4.1: the line under the row (nb + en), `null` for none. */
fun airplaneLineRes(state: AirplaneRowState): Int? = when (state) {
    AirplaneRowState.OFF_ONLY -> com.kidslauncher.mdm.R.string.kid_settings_airplane_off_only
    AirplaneRowState.RESTRICTED -> com.kidslauncher.mdm.R.string.kid_settings_airplane_parent
    AirplaneRowState.BUSY_ON -> com.kidslauncher.mdm.R.string.kid_settings_airplane_turning_on
    AirplaneRowState.BUSY_OFF -> com.kidslauncher.mdm.R.string.kid_settings_airplane_turning_off
    AirplaneRowState.EMERGENCY -> com.kidslauncher.mdm.R.string.kid_settings_airplane_emergency
    AirplaneRowState.FAILED -> com.kidslauncher.mdm.R.string.kid_settings_airplane_failed
    AirplaneRowState.ON_WIFI -> com.kidslauncher.mdm.R.string.kid_settings_airplane_on_wifi
    AirplaneRowState.ON_OFFLINE -> com.kidslauncher.mdm.R.string.kid_settings_airplane_on_offline
    AirplaneRowState.COOLDOWN, AirplaneRowState.OFF, AirplaneRowState.HIDDEN -> null
}

/** §4.2: Home's line - `on` ("Flymodus er på · Trykk for å slå av") and `busy` ("Slår av…"). */
object HomeAirplaneLine {
    const val ON = "on"
    const val BUSY = "busy"
}

/** §4.2: whenever airplane mode is on (whatever bit 64 and the role), unless the parent's switch is set. */
fun homeAirplaneLine(on: Boolean, restricted: Boolean, busy: Boolean): String? = when {
    !on || restricted -> null
    busy -> HomeAirplaneLine.BUSY
    else -> HomeAirplaneLine.ON
}

/** §4.3: the time-rule screen's sheet - whether there is a "···" at all, and its rows (`airplane`, `parent_code`). */
data class RuleSheet(val button: Boolean, val rows: List<String>)

/** The lock's kind for [ruleSheet]: a rule, the used-up budget alone, or a rule with the budget used up as well. */
object RuleSheetLock {
    const val RULE = "rule"
    const val BUDGET = "budget"
    const val RULE_AND_BUDGET = "rule_and_budget"
}

/**
 * §4.3: Flymodus when §4.1's row would show ([airplaneRow] - bit 64 with the role, or airplane mode on); Foreldrekode
 * only for a rule whose budget isn't used up as well (D11, Q98-R3-L4), with a PIN on the server
 * (`ParentPin.status(..).configured`) and a server that takes the pause (`rule_pause: true`). No rows, no button.
 */
fun ruleSheet(lock: String, airplaneRow: Boolean, pinConfigured: Boolean, rulePause: Boolean): RuleSheet {
    val rows = listOfNotNull(
        "airplane".takeIf { airplaneRow },
        "parent_code".takeIf { lock == RuleSheetLock.RULE && pinConfigured && rulePause },
    )
    return RuleSheet(rows.isNotEmpty(), rows)
}

/**
 * Design 121 §4.4: whether Vibb's switch closes the command stream before the radios go and holds its reconnect until
 * the route ends, so the server sees the close at once and the turn-on's `going_on` report reads "Airplane mode on
 * since" for as long as nothing else arrives. A stream on Wi-Fi that airplane mode keeps ([wifiKept] `true`) stays;
 * any other network goes with the radios ([streamOnWifi]: the stream's network, or the default one while it is down).
 */
fun airplaneHoldsStream(streamOnWifi: Boolean, wifiKept: Boolean?): Boolean = !streamOnWifi || wifiKept != true

/** The hold from [airplaneHoldsStream]'s close until the route ends (a route that never released it lets go after this,
 * as on success), and the wait after a successful turn-on ([airplaneStreamReopens]). */
const val AIRPLANE_STREAM_HOLD_MS = 30_000L

/**
 * Design 121 §4.4 (code QA H1): whether the held stream opens again now. After a failed turn-on ([airplaneOn] false):
 * at once. After a successful one only on a network other than [heldNetwork] - the held one is going with the radios,
 * and a stream opened on it would dangle at the server's front -, so Wi-Fi that airplane mode kept, or the network
 * after airplane mode; once [waitedOut] ([AIRPLANE_STREAM_HOLD_MS] after the release) on any network that is still up.
 */
fun airplaneStreamReopens(airplaneOn: Boolean, heldNetwork: Long?, defaultNetwork: Long?, waitedOut: Boolean): Boolean =
    !airplaneOn || (defaultNetwork != null && (defaultNetwork != heldNetwork || waitedOut))
