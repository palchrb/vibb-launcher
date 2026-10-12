package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/command-receipt` (security review L12): the phone got the command
 * and is about to run it - sent before anything runs, so a wipe is confirmed too. */
@Serializable
data class CommandReceiptRequest(
    val commandId: Long,
    /** [PendingCommand.requestedAt]: the receipt counts only for that command (left out when unknown). */
    val requestedAt: String? = null,
)
