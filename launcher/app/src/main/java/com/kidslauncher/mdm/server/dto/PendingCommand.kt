package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** The oldest undelivered remote command for this device, if any - see
 * [PolicyResponse.pendingCommand]. [command] is one of "ring" | "lock" | "wipe". */
@Serializable
data class PendingCommand(
    val id: Long,
    val command: String,
)
