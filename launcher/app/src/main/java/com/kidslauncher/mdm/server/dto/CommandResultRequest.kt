package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/command-result` - never sent for a `wipe` command, since the
 * device is gone by the time it would report back. */
@Serializable
data class CommandResultRequest(
    val commandId: Long,
    val success: Boolean,
    val message: String? = null,
)
