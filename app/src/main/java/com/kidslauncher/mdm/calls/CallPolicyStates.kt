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

fun CallPolicy.toRules() = CallRules(
    callsEnabled = callsEnabled,
    smsEnabled = smsEnabled,
    defaultCc = defaultCountryCode,
    contacts = contacts.map {
        RuleContact(it.id, it.name, it.number, it.inbound, it.outbound, it.showOnHome, it.messageApp, it.messageAddress)
    },
)

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
                !callPolicy.managed -> CallPolicyState.Unmanaged
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
    return if (callPolicy.managed) {
        CallPrefsUpdate(true, encodeCallRules(callPolicy.toRules()))
    } else {
        CallPrefsUpdate(false, null)
    }
}
