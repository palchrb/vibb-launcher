package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** The oldest undelivered remote command for this device, if any - see
 * [PolicyResponse.pendingCommand]. [command] is one of "ring" | "stop_ring" | "lock" | "wipe" |
 * "locate" | "end_windows" | "allow_blocked" | "start_pause" (design 24b). */
@Serializable
data class PendingCommand(
    val id: Long,
    val command: String,
)
