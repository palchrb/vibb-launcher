package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Attached to [StatusReportRequest] whenever [com.kidslauncher.mdm.server.LocateCommands.currentLocation]
 * has a reading available - on every regular heartbeat, not just after a command, so the Find My
 * Device map's trail stays reasonably fresh without needing repeated explicit requests.
 *
 * Design 64 step 2: also each entry of [StatusReportRequest.extraLocations]. [provider] is
 * Android's provider of the fix (`gps`, `network`, `fused`; `null` = unknown, left out); [mock] is
 * `Location.isMock` - only a debug build ever sends `true` (a release build ignores a mock fix and
 * counts it, [ZoneWatchReport.mockIgnored]); `false` is left out. The
 * server stores both (migration 0093, shown only in the Locate fix row's title). Pinned by `zone_status_report.json` (`ZoneReportTest`). */
@Serializable
data class LocationReport(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val capturedAt: String,
    val provider: String? = null,
    val mock: Boolean = false,
)
