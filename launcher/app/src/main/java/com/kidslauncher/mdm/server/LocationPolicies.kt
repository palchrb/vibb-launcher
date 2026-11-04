package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LocationPolicy

/*
 * When a location goes into the status report - pure, tested in LocationPolicyTest. The parent
 * picks per device (handy step 6): off, on request (only for a `locate` command), or every
 * N minutes. An active fix shows Android's location indicator and costs battery (CLAUDE.md
 * gotchas), so everything else uses our own cached fix or nothing.
 */

enum class LocationAction {
    /** No location in the report, and no LocationManager call. */
    NONE,

    /** Our own cached last fix (no LocationManager call). */
    CACHED,

    /** A fresh fix now - the precise path, GPS first (a `locate` command, a crossing's
     * confirming fix). */
    FRESH,

    /** Design 64 §4.4: a fresh fix now through the fused provider at balanced power (Wi-Fi/cell
     * before GPS) - the heartbeat and a crossing's first fix; GPS only when fused gives nothing. */
    FRESH_BALANCED,
    ;

    /** A LocationManager fix is taken (either path). */
    val fresh: Boolean get() = this == FRESH || this == FRESH_BALANCED
}

const val MIN_LOCATION_INTERVAL_MINUTES = 10

/** Status-report capability (design 64 step 2): this launcher watches the policy's zones itself -
 * one platform fence per zone, a crossing's fixes sent at once, `zone_watch` in every report. */
const val ZONES_CAPABILITY = "zones_v1"

/** Before step 6 (no `location_policy` from the server): a fresh fix at most every 10 minutes,
 * the cached one otherwise, on every sync. */
val LEGACY_LOCATION_POLICY = LocationPolicy(mode = "interval", intervalMinutes = MIN_LOCATION_INTERVAL_MINUTES)

/**
 * [forced]: a `locate` command asked for the phone's location now (security review P-L2: a `ring` is no request for
 * the location). [sinceLastFreshMs]: wall
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

/**
 * The phone's system Location switch: `false` (off) when the parent chose location "off" - the
 * device owner switches it off, and with the CONFIG_LOCATION hardening switch it then stays off
 * (QA step 6 #7); `true` when that hardening switch wants it on for Find my device; `null` =
 * leave it as it is. Emergency calls send their location through the platform's own path.
 */
fun systemLocationTarget(policy: LocationPolicy?, hardeningForcesOn: Boolean): Boolean? = when {
    policy?.mode == "off" -> false
    hardeningForcesOn -> true
    else -> null
}

/**
 * Security review P-L2: the system Location switch stays on after a fix only when the policy needs it on - the
 * CONFIG_LOCATION hardening switch keeps it on for Find my device ([systemLocationTarget]), or the zone watch's fences
 * need it. Otherwise a fix that had to switch it on switches it back off, so under "Only when asked" no app with a
 * location permission can use it after a `locate`.
 */
fun locationStaysOn(policy: LocationPolicy?, hardeningForcesOn: Boolean, zonesOn: Boolean): Boolean =
    systemLocationTarget(policy, hardeningForcesOn) == true || zonesOn

/** The Location switch goes back off after the fix: this fix switched it on ([switchedOn]) and nothing needs it on. */
fun locationBackOff(switchedOn: Boolean, staysOn: Boolean): Boolean = switchedOn && !staysOn

/**
 * Security review P-L3: the oldest cached fix the phone reports - the policy's interval (at least
 * [MIN_LOCATION_INTERVAL_MINUTES]; "Only when asked" has one too, 30 min by default). Older, the report carries none
 * rather than a fix hours or days old: neither a `CACHED` sync nor a fresh fix that failed falls back to it. [still]
 * (the zone watch's heartbeat re-send while the motion sensor says the phone hasn't moved, design 64 Q-3, at most
 * [com.kidslauncher.mdm.zones.STILL_SKIP_MAX_MS] after the last fresh fix) has no limit: the server counts that fix
 * as current.
 */
fun cachedFixMaxAgeMs(policy: LocationPolicy?, still: Boolean): Long? =
    if (still) null else (policy ?: LEGACY_LOCATION_POLICY).intervalMinutes.coerceAtLeast(MIN_LOCATION_INTERVAL_MINUTES) * 60_000L

/** A cached (or last-known) fix [ageMs] old may be reported under [maxAgeMs] (`null` = any age). */
fun cachedFixReportable(ageMs: Long, maxAgeMs: Long?): Boolean = maxAgeMs == null || ageMs <= maxAgeMs
