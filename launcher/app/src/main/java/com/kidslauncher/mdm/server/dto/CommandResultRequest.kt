package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/command-result` - what the command did. A `wipe` that works never
 * reports (the phone reboots into the reset); one that is refused does (security review M8). */
@Serializable
data class CommandResultRequest(
    val commandId: Long,
    val success: Boolean,
    val message: String? = null,
    /** [PendingCommand.requestedAt], as in [CommandReceiptRequest]. */
    val requestedAt: String? = null,
)
