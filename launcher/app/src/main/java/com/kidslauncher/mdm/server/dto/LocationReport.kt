package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Attached to [StatusReportRequest] whenever [com.kidslauncher.mdm.server.LocateCommands.currentLocation]
 * has a reading available - on every regular heartbeat, not just after a command, so the Find My
 * Device map's trail stays reasonably fresh without needing repeated explicit requests. */
@Serializable
data class LocationReport(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val capturedAt: String,
)
