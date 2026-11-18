package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * `PolicyResponse.callPolicy` (JSON `call_policy`) - see the server's `build_call_policy`. The server always sends it
 * with explicit [callsMode]/[smsMode]; a missing object means "calls not managed" only on a phone whose calls were
 * never managed - otherwise the whole response is rejected ([com.kidslauncher.mdm.server.judgeFresh]).
 *
 * Defaults inside a present object deny: `{}` is "calls and SMS off, no contacts" - unmanaging needs both modes
 * `unmanaged` (QA blocker 3).
 */
@Serializable
data class CallPolicy(
    val defaultCountryCode: String = "47",
    val contacts: List<PolicyContact> = emptyList(),
    /** Design 73: the emergency menu in the parent's order (normalised numbers, at least one); `null` from a server
     * before it - the phone then offers 112 alone ([com.kidslauncher.mdm.calls.emergencyMenu]). Sent managed or not. */
    val emergencyMenu: List<PolicyEmergencyEntry>? = null,
    /** Design 77 D2: each channel's own state, `"off" | "managed" | "unmanaged"` - always sent; a missing one reads as
     * `"off"` ([com.kidslauncher.mdm.calls.channelModes]). Once seen, a policy without them is rejected whole
     * ([com.kidslauncher.mdm.server.judgeFresh]); an unknown value is too. */
    val callsMode: String? = null,
    val smsMode: String? = null,
    /** Design 77 D17: the parent's sender names let through (exact, by the shared fold). */
    val smsNames: List<String> = emptyList(),
    /** Design 77 D12: the phone's own numbers the parent typed, per SIM slot (used by the group rule, step 3b). */
    val ownNumbers: List<PolicyOwnNumber> = emptyList(),
    /** Design 77 D10: the carriers' MMS servers per MCC/MNC (used by the MMS download gate, step 3b). */
    val mmscHosts: List<PolicyMmscHosts> = emptyList(),
    /** Design 77 D16: the held messages the parent deleted ("Slett nå"), dropped before anything is released. */
    val smsHeldDrop: List<String> = emptyList(),
    /** Design 77 §14: the kid's contact requests of the last 7 days and what became of them
     * ([com.kidslauncher.mdm.calls.ContactRequests]); empty when missing. */
    val contactRequests: List<PolicyContactRequest> = emptyList(),
    /** Design 80 D13: "Gruppemeldinger" - off blocks every group MMS ([com.kidslauncher.mdm.sms.engine.MmsGate.group]).
     * Always sent; absent is off (fail closed). */
    val smsGroups: Boolean = false,
)

/** One of [CallPolicy.ownNumbers]: the SIM slot (1 or 2) and the normalised number. */
@Serializable
data class PolicyOwnNumber(
    val slot: Int = 0,
    val number: String = "",
)

/** One of [CallPolicy.mmscHosts]. */
@Serializable
data class PolicyMmscHosts(
    val mccmnc: String = "",
    val hosts: List<String> = emptyList(),
)

/** One number of [CallPolicy.emergencyMenu]: the server's `emergency::PolicyEntry`; [name] may be empty. */
@Serializable
data class PolicyEmergencyEntry(
    val number: String = "",
    val name: String = "",
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
    /** Design 77 D3: the contact may text the kid / the kid may text the contact / pictures both ways. Independent of
     * [inbound]/[outbound]; absent denies. The server never sends [smsIn] for an emergency number. */
    val smsIn: Boolean = false,
    val smsOut: Boolean = false,
    val mms: Boolean = false,
)
