package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse

/*
 * Which call rules are in force, derived from the cached policy and two prefs written in the same
 * commit as that cache (MdmSyncWorker.storeAcceptedPolicy): `calls_managed_last` (the last
 * accepted policy's explicit `call_policy.managed`) and `last_call_rules` (its rules). Pure, tested
 * in CallPolicyStateTest. CallPolicyStore holds the result in memory for the call services.
 */

fun CallPolicy.toRules(): CallRules {
    val (calls, sms) = channelModes(this)
    return CallRules(
        // The old switches as the derived wire keys say them (D2), for the readers that still ask them.
        callsEnabled = calls != ChannelMode.OFF,
        smsEnabled = sms == ChannelMode.UNMANAGED,
        defaultCc = defaultCountryCode,
        contacts = contacts.map {
            RuleContact(
                it.id, it.name, it.number, it.inbound, it.outbound, it.showOnHome, it.messageApp, it.messageAddress, it.photo,
                smsIn = it.smsIn, smsOut = it.smsOut, mms = it.mms,
            )
        },
        emergencyMenu = emergencyMenu?.map { EmergencyEntry(it.number, it.name) },
        callsMode = calls,
        smsMode = sms,
        smsNames = smsNames,
        smsLockText = smsLockText,
    )
}

/**
 * Design 77 D2: the two channels' states of a `call_policy`. A server with the SMS engine sends `calls_mode` and
 * `sms_mode` ([CallPolicy.callsMode]); an older one only `managed`/`calls_enabled`/`sms_enabled`, read by D2's
 * migration table (`sms_contract.json`'s `migration` rows): not managed is both "Ikke styrt", else calls on/off and SMS
 * unfiltered/off. A value that isn't one of the three never gets here: [com.kidslauncher.mdm.server.judgeFresh] rejects
 * the policy - and should one be cached, it reads as "Av" (fail closed) for that channel.
 */
fun channelModes(policy: CallPolicy): Pair<ChannelMode, ChannelMode> {
    if (policy.callsMode != null || policy.smsMode != null) {
        return (ChannelMode.of(policy.callsMode) ?: ChannelMode.OFF) to (ChannelMode.of(policy.smsMode) ?: ChannelMode.OFF)
    }
    if (!policy.managed) return ChannelMode.UNMANAGED to ChannelMode.UNMANAGED
    return (if (policy.callsEnabled) ChannelMode.MANAGED else ChannelMode.OFF) to
        (if (policy.smsEnabled) ChannelMode.UNMANAGED else ChannelMode.OFF)
}

fun encodeCallRules(rules: CallRules): String = ServerJson.encodeToString(CallRules.serializer(), rules)

/** `null` if missing or unreadable. */
fun decodeCallRules(json: String?): CallRules? {
    if (json.isNullOrBlank()) return null
    return try {
        ServerJson.decodeFromString(CallRules.serializer(), json)
    } catch (e: Exception) {
        null
    }
}

/**
 * - A cached policy with an explicit `managed: false`, or no `call_policy` on a phone whose calls
 *   were never managed (an older server): [CallPolicyState.Unmanaged].
 * - A cached managed `call_policy`: its rules.
 * - Anything else while [callsManagedLast] is set (no `call_policy` in the cache, cache corrupt
 *   or missing): the [lastRules] stored with the last managed policy - so parents can still call
 *   (QA blocker 3) - or, if those are unreadable too, [CallPolicyState.UnknownFailClosed].
 * - A corrupt cache on a phone without [callsManagedLast]: also fail closed - we can't tell.
 */
fun callPolicyState(cached: CachedPolicy, callsManagedLast: Boolean, lastRules: CallRules?): CallPolicyState {
    val fallback = if (lastRules != null && callsManagedLast) {
        CallPolicyState.Managed(lastRules)
    } else {
        CallPolicyState.UnknownFailClosed
    }
    return when (cached) {
        is CachedPolicy.Ok -> {
            val callPolicy = cached.policy.callPolicy
            when {
                callPolicy == null -> if (callsManagedLast) fallback else CallPolicyState.Unmanaged
                !callPolicy.anyChannelManaged -> CallPolicyState.Unmanaged
                else -> CallPolicyState.Managed(callPolicy.toRules())
            }
        }
        is CachedPolicy.Corrupt -> fallback
        CachedPolicy.Absent -> if (callsManagedLast) fallback else CallPolicyState.Unmanaged
    }
}

/** What to write next to an accepted policy: `null` = leave both prefs as they are. */
data class CallPrefsUpdate(val callsManagedLast: Boolean, val lastCallRules: String?)

/**
 * Only an explicit `managed` value changes `calls_managed_last` (QA blocker 3). A policy without
 * `call_policy` is only ever accepted on a phone whose calls weren't managed, so it changes
 * nothing. `managed: false` clears the stored rules.
 */
fun callPrefsUpdate(accepted: PolicyResponse): CallPrefsUpdate? {
    val callPolicy = accepted.callPolicy ?: return null
    return if (callPolicy.anyChannelManaged) {
        CallPrefsUpdate(true, encodeCallRules(callPolicy.toRules()))
    } else {
        CallPrefsUpdate(false, null)
    }
}

/** Whether either channel is under our control: D2's derived wire `managed`, judged from the modes when they're sent. */
val CallPolicy.anyChannelManaged: Boolean
    get() = channelModes(this).let { (calls, sms) -> calls != ChannelMode.UNMANAGED || sms != ChannelMode.UNMANAGED }
