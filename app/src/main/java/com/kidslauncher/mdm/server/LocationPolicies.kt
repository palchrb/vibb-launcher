package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LocationPolicy

/*
 * When a location goes into the status report - pure, tested in LocationPolicyTest. The parent
 * picks per device (handy step 6): off, on request (only for a `locate`/`ring` command), or every
 * N minutes. An active fix shows Android's location indicator and costs battery (CLAUDE.md
 * gotchas), so everything else uses our own cached fix or nothing.
 */

enum class LocationAction {
    /** No location in the report, and no LocationManager call. */
    NONE,

    /** Our own cached last fix (no LocationManager call). */
    CACHED,

    /** A fresh fix now. */
    FRESH,
}

const val MIN_LOCATION_INTERVAL_MINUTES = 10

/** Before step 6 (no `location_policy` from the server): a fresh fix at most every 10 minutes,
 * the cached one otherwise, on every sync. */
val LEGACY_LOCATION_POLICY = LocationPolicy(mode = "interval", intervalMinutes = MIN_LOCATION_INTERVAL_MINUTES)

/**
 * [forced]: a `locate`/`ring` command asked for the phone's location now. [sinceLastFreshMs]: wall
 * time since the last active fetch (negative - the clock went back - counts as due).
 * An unknown mode is treated as "on_request".
 */
fun locationAction(policy: LocationPolicy?, forced: Boolean, sinceLastFreshMs: Long): LocationAction {
    val p = policy ?: LEGACY_LOCATION_POLICY
    return when (p.mode) {
        "off" -> LocationAction.NONE
        "interval" -> {
            val intervalMs = p.intervalMinutes.coerceAtLeast(MIN_LOCATION_INTERVAL_MINUTES) * 60_000L
            if (forced || sinceLastFreshMs < 0 || sinceLastFreshMs >= intervalMs) LocationAction.FRESH else LocationAction.CACHED
        }
        else -> if (forced) LocationAction.FRESH else LocationAction.NONE
    }
}

/** The `locate` command's answer, shown on the server's command list. */
fun locateResultMessage(action: LocationAction, accuracyMeters: Float?, ageSeconds: Long?): Pair<Boolean, String> = when {
    action == LocationAction.NONE -> false to "location is off for this device"
    ageSeconds == null -> false to "no location fix"
    else -> true to buildString {
        append("fix")
        accuracyMeters?.let { append(" ±${it.toInt()} m") }
        append(", ${ageSeconds.coerceAtLeast(0)} s old")
    }
}
