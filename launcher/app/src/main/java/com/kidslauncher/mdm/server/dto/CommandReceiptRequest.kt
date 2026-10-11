package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/command-receipt` (security review L12): the phone got the command
 * and is about to run it - sent before anything runs, so a wipe is confirmed too. */
@Serializable
data class CommandReceiptRequest(
    val commandId: Long,
)
