package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/status` - a best-effort heartbeat; a failed send must never affect
 * the local lock decision, only the admin site's visibility into it. [policyState] is `"ok"` or
 * why the phone isn't enforcing the server's current policy (see
 * [com.kidslauncher.mdm.server.policyState]); [restrictionsPaused] is the PIN-gated Settings
 * kill-switch ([com.kidslauncher.mdm.server.RestrictionsPause]). The server shows both as
 * warnings on the device page. [capabilities] says what this launcher enforces (`call_policy_v1`:
 * the server's `call_policy`); [callState] is what it did about calls. */
@Serializable
data class StatusReportRequest(
    val lockReason: String,
    val kioskEngaged: Boolean,
    val installedApps: List<InstalledApp>? = null,
    val appVersion: String? = null,
    val appVersionCode: Int? = null,
    val offlineOverrideUsed: Boolean = false,
    val location: LocationReport? = null,
    val policyState: String? = null,
    val restrictionsPaused: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val callState: CallState? = null,
    /** Our notification listener (app badges) has access - see `BadgeListenerService`. */
    val notificationListenerEnabled: Boolean? = null,
    /** Time rules and screen time right now (handy step 6) - see [TimeState]. */
    val timeState: TimeState? = null,
    /** How nudges reach the phone (handy step 7) - see [PushReport]. */
    val push: PushReport? = null,
    /** Play install mode is on (handy step 7) - the parent opened Play with the PIN. */
    val installMode: InstallModeReport? = null,
    /** The nightly Play update window is in force (Play Store unsuspended, screen off). */
    val playWindowActive: Boolean = false,
    /** `false` when the platform refused to suspend the Play Store (step 9 B4); `null` = not tried. */
    val playStoreSuspendable: Boolean? = null,
)

/**
 * `StatusReportRequest.push`. [fcmToken] is the current FCM registration token (`null`: none, or
 * no FCM in this build); [transport] "fcm" or "sse" and, for SSE, [reason] (see
 * [com.kidslauncher.mdm.push.SseReason]). [lastNudgeId] is the `n` of the last FCM message we
 * got - the acknowledgement the server's health check waits for. [lastPriority]/
 * [lastOriginalPriority] ("high"/"normal"/"unknown") show FCM deprioritising our nudges.
 */
@Serializable
data class PushReport(
    val fcmToken: String?,
    val transport: String,
    val fcmConfigured: Boolean,
    val gmsAvailable: Boolean,
    val lastNudgeMs: Long?,
    val lastNudgeId: String?,
    val lastPriority: String?,
    val lastOriginalPriority: String?,
    val reason: String?,
)

/** `StatusReportRequest.installMode`: wall-clock end of the Play install mode window. */
@Serializable
data class InstallModeReport(val untilMs: Long)

/**
 * `StatusReportRequest.timeState`, shown on the server's device page. [day] is the local date
 * being counted, [budgetMinutes] today's budget including [extraMinutes] from the parent's
 * "+ screen time" (`null` = unlimited), [activeRuleId]/[activeRuleName] the strongest active rule,
 * [liftsActive] the lift ids in force, [lockReason] a [com.kidslauncher.mdm.server.LockReason] name.
 */
@Serializable
data class TimeState(
    val day: String?,
    val usedMinutes: Int,
    val budgetMinutes: Int?,
    val extraMinutes: Int,
    val activeRuleId: Long?,
    val activeRuleName: String?,
    val callsBlocked: Boolean,
    val lockReason: String,
    val liftsActive: List<Long>,
    /** The screen-time record couldn't be read today, so it counts as used up (QA step 6 #6). */
    val ledgerUnreadable: Boolean = false,
)

/**
 * `StatusReportRequest.callState` - shown on the server's Calls & SMS page (kid-phone-server's
 * `handlers::calls::call_warnings`). No defaults on purpose: every field is always sent.
 * [state] is "unmanaged", "managed" or "fail_closed". Times are ISO-8601 (UTC).
 * [lastEmergencyCallAt]/[callbackWindowUntil] report an emergency call and the window in which
 * anyone may call back (QA blocker 2) so the parent is told. [callLogReadable] false means the
 * callback window can't open from the call log (QA step 2 #7), shown as a warning. [bootPolicy] is
 * the device-protected copy of the call rules used before the first unlock (task 15): "ok",
 * "write_failed", "unreadable" (it wouldn't make the same decisions) or "unknown".
 */
@Serializable
data class CallState(
    val state: String,
    val dialerRoleHeld: Boolean,
    val redirectionRoleHeld: Boolean,
    val defaultDialer: String?,
    val systemDialer: String?,
    val smsRestricted: Boolean,
    val outgoingRestricted: Boolean,
    val defaultSmsPackage: String?,
    val lastError: String?,
    val lastEmergencyCallAt: String?,
    val callbackWindowUntil: String?,
    val callLogReadable: Boolean,
    val bootPolicy: String,
)
