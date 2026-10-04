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
)

/**
 * `StatusReportRequest.callState` - shown on the server's Calls & SMS page (kid-phone-server's
 * `handlers::calls::call_warnings`). No defaults on purpose: every field is always sent.
 * [state] is "unmanaged", "managed" or "fail_closed". Times are ISO-8601 (UTC).
 * [lastEmergencyCallAt]/[callbackWindowUntil] report an emergency call and the window in which
 * anyone may call back (QA blocker 2) so the parent is told. [callLogReadable] false means the
 * callback window can't open from the call log (QA step 2 #7), shown as a warning.
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
)
