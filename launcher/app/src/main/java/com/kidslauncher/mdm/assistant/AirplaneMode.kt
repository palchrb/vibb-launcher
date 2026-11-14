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
