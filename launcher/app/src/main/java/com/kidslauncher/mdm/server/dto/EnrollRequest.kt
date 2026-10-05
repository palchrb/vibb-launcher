package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/enroll` - the short one-shot code shown on the admin site. */
@Serializable
data class EnrollRequest(
    val enrollmentCode: String,
)
