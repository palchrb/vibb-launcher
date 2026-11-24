package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Response from `POST /api/devices/enroll` - [deviceToken] is the bearer credential for every
 * subsequent call and is only ever shown this once; it's the caller's job to persist it. [key]: design 129 S1, what
 * the server did with the enrolment's key (`observed` with its `key_id`, or `refused` with a `reason`). */
@Serializable
data class EnrollResponse(
    val deviceId: Long,
    val deviceToken: String,
    val key: EnrollKeyAnswer? = null,
)

/** The enrolment's key: `{key_id, state: "observed"}` or `{state: "refused", reason, retry_after_s?}`. */
@Serializable
data class EnrollKeyAnswer(
    val state: String,
    val keyId: String? = null,
    val reason: String? = null,
    val retryAfterS: Long? = null,
)
