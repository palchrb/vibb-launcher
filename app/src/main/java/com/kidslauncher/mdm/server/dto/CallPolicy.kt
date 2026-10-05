package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * `PolicyResponse.callPolicy` (JSON `call_policy`) - see kid-phone-server's `build_call_policy`.
 * Our server always sends it with an explicit [managed]; a missing object (an upstream or older
 * server) means "calls not managed" only on a phone whose calls were never managed - otherwise
 * the whole response is rejected ([com.kidslauncher.mdm.server.judgeFresh]).
 *
 * Defaults inside a present object deny, [managed] included: `{}` is "managed, calls and SMS off,
 * no contacts" - unmanaging needs an explicit `managed: false` (QA blocker 3).
 */
@Serializable
data class CallPolicy(
    val managed: Boolean = true,
    val callsEnabled: Boolean = false,
    val smsEnabled: Boolean = false,
    val defaultCountryCode: String = "47",
    val contacts: List<PolicyContact> = emptyList(),
)

/** One contact. [number] is normalised server-side (E.164 or a 3-6 digit short number);
 * [messageApp] is "none", "sms", "element" or "signal", already resolved against the device
 * default; [messageAddress] is the Matrix ID for "element"; [photo] is the SHA-256 (hex) of the
 * contact's photo, fetched by [com.kidslauncher.mdm.calls.ContactPhotos], or null. */
@Serializable
data class PolicyContact(
    val id: Long = 0,
    val name: String = "",
    val number: String = "",
    val inbound: Boolean = false,
    val outbound: Boolean = false,
    val showOnHome: Boolean = false,
    val messageApp: String = "none",
    val messageAddress: String? = null,
    val photo: String? = null,
)
