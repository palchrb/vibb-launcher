package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/apps/progress` - purely transient, drives the admin site's
 * unified Apps list "Installing NN%"/"Install failed" status label. Best-effort from this side; a
 * dropped report just means one stale-looking status until the next one lands or the row goes
 * stale. [percent] is meaningless when [failed] is true - send 0. [reason] is a failure's short code
 * ([installFailureReason], [com.kidslauncher.mdm.server.InstallFailureKind]; never Android's text),
 * left out when there is none - the contract `install_failure.json`. */
@Serializable
data class InstallProgressReport(
    val trackedAppId: Long,
    val percent: Int,
    val failed: Boolean = false,
    val reason: String? = null,
)
