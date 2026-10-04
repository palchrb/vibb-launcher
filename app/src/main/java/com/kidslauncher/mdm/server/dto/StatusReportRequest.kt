package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/status` - a best-effort heartbeat; a failed send must never affect
 * the local lock decision, only the admin site's visibility into it. [policyState] is `"ok"` or
 * why the phone isn't enforcing the server's current policy (see
 * [com.kidslauncher.mdm.server.policyState]); the server shows anything else as a warning. */
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
)
