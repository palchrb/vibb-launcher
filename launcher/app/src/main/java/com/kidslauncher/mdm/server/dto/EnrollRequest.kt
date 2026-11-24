package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/enroll` - the short one-shot code shown on the admin site; since design 129 (§5.1) with
 * the new hardware key's [chain] (standard Base64 DER, leaf first) and its [proof], both left out (defaults are never
 * encoded) when no key could be made. */
@Serializable
data class EnrollRequest(
    val enrollmentCode: String,
    val chain: List<String>? = null,
    val proof: String? = null,
)
