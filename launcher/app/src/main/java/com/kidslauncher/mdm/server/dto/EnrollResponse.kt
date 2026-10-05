package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Response from `POST /api/devices/enroll` - [deviceToken] is the bearer credential for every
 * subsequent call and is only ever shown this once; it's the caller's job to persist it. */
@Serializable
data class EnrollResponse(
    val deviceId: Long,
    val deviceToken: String,
)
