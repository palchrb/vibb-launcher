package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse

/*
 * Which call rules are in force, derived from the cached policy and two prefs written in the same
 * commit as that cache (MdmSyncWorker.storeAcceptedPolicy): `calls_managed_last` (whether the last
 * accepted policy had either channel managed) and `last_call_rules` (its rules). Pure, tested
 * in CallPolicyStateTest. CallPolicyStore holds the result in memory for the call services.
 */

fun CallPolicy.toRules(): CallRules {
    val (calls, sms) = channelModes(this)
    return CallRules(
        // The two switches the modes give, for the readers that still ask them.
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
        ownNumbers = ownNumbers.map { OwnNumberRule(it.slot, it.number) },
        mmscHosts = mmscHosts.map { MmscRule(it.mccmnc, it.hosts) },
        smsGroups = smsGroups,
    )
}

/**
 * Design 77 D2: the two channels' states of a `call_policy` ([CallPolicy.callsMode], [CallPolicy.smsMode]). A missing
 * one, or a value that isn't one of the three, reads as "Av" (fail closed) for that channel - though
 * [com.kidslauncher.mdm.server.judgeFresh] rejects an unknown value, and a missing pair once the phone has seen one.
 */
fun channelModes(policy: CallPolicy): Pair<ChannelMode, ChannelMode> =
    (ChannelMode.of(policy.callsMode) ?: ChannelMode.OFF) to (ChannelMode.of(policy.smsMode) ?: ChannelMode.OFF)

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
 * - A cached policy with both channels `unmanaged`, or no `call_policy` on a phone whose calls
 *   were never managed: [CallPolicyState.Unmanaged].
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
 * Only a sent `call_policy` changes `calls_managed_last` (QA blocker 3). A policy without it is
 * only ever accepted on a phone whose calls weren't managed, so it changes nothing. Both channels
 * `unmanaged` clears the stored rules.
 */
fun callPrefsUpdate(accepted: PolicyResponse): CallPrefsUpdate? {
    val callPolicy = accepted.callPolicy ?: return null
    return if (callPolicy.anyChannelManaged) {
        CallPrefsUpdate(true, encodeCallRules(callPolicy.toRules()))
    } else {
        CallPrefsUpdate(false, null)
    }
}

/** Whether either channel is under our control (a missing mode reads as "Av": managed). */
val CallPolicy.anyChannelManaged: Boolean
    get() = channelModes(this).let { (calls, sms) -> calls != ChannelMode.UNMANAGED || sms != ChannelMode.UNMANAGED }
