package com.kidslauncher.mdm.calls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * The call policy mirrored to device-protected (DE) storage for the time between a reboot and the
 * first unlock, when credential-encrypted storage - the policy cache - can't be read (task 15, QA
 * blocker 4; design: 02-calls.md "Direct boot (task 15)"). Pure, tested in BootCallPolicyTest;
 * CallPolicyStore reads and writes it.
 *
 * DE data is readable before the user has authenticated, so this holds only what the call path
 * needs before unlock: the mode, whether calls are on, the default country code and the allowed
 * numbers. No names, no tokens, no PIN hash.
 */

const val BOOT_POLICY_VERSION = 1

@Serializable
data class BootCallPolicy(
    @SerialName("v") val version: Int,
    @SerialName("mode") val mode: String,
    @SerialName("calls_enabled") val callsEnabled: Boolean,
    @SerialName("default_cc") val defaultCc: String,
    @SerialName("inbound") val inbound: List<String>,
    @SerialName("outbound") val outbound: List<String>,
) {
    companion object {
        const val MANAGED = "managed"
        const val UNMANAGED = "unmanaged"
        const val FAIL_CLOSED = "fail_closed"
    }
}

/** Strict on purpose: an unknown key or a missing field means we don't understand the file. */
private val BootJson = Json {
    ignoreUnknownKeys = false
}

/** The DE copy of [state] (what the call path would enforce after unlock). Lists sorted, so the
 * encoding is stable and [bootPolicyRewrite] only writes on a real change. */
fun bootPolicyFor(state: CallPolicyState): BootCallPolicy = when (state) {
    is CallPolicyState.Managed -> BootCallPolicy(
        version = BOOT_POLICY_VERSION,
        mode = BootCallPolicy.MANAGED,
        callsEnabled = state.rules.callsEnabled,
        defaultCc = state.rules.defaultCc,
        inbound = state.rules.inbound.sorted(),
        outbound = state.rules.outbound.sorted(),
    )
    CallPolicyState.Unmanaged -> BootCallPolicy(BOOT_POLICY_VERSION, BootCallPolicy.UNMANAGED, false, "", emptyList(), emptyList())
    CallPolicyState.UnknownFailClosed ->
        BootCallPolicy(BOOT_POLICY_VERSION, BootCallPolicy.FAIL_CLOSED, false, "", emptyList(), emptyList())
}

fun encodeBootPolicy(policy: BootCallPolicy): String = BootJson.encodeToString(BootCallPolicy.serializer(), policy)

/** Result of reading the DE copy; everything but [Ok] fails closed. */
sealed interface BootPolicyRead {
    data object Missing : BootPolicyRead
    data class Corrupt(val why: String) : BootPolicyRead
    /** Written by a build with another format version (a downgrade): we can't trust our reading. */
    data class UnsupportedVersion(val version: Int?) : BootPolicyRead
    data class Ok(val policy: BootCallPolicy) : BootPolicyRead
}

fun decodeBootPolicy(json: String?): BootPolicyRead {
    if (json.isNullOrBlank()) return BootPolicyRead.Missing
    val obj = try {
        BootJson.parseToJsonElement(json) as? JsonObject ?: return BootPolicyRead.Corrupt("not an object")
    } catch (e: Exception) {
        return BootPolicyRead.Corrupt("unparseable")
    }
    val version = (obj["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    if (version != BOOT_POLICY_VERSION) return BootPolicyRead.UnsupportedVersion(version)
    val policy = try {
        BootJson.decodeFromJsonElement(BootCallPolicy.serializer(), obj)
    } catch (e: Exception) {
        return BootPolicyRead.Corrupt("fields")
    }
    return when (policy.mode) {
        BootCallPolicy.UNMANAGED, BootCallPolicy.FAIL_CLOSED -> BootPolicyRead.Ok(policy)
        BootCallPolicy.MANAGED -> validManaged(policy)
        else -> BootPolicyRead.Corrupt("mode")
    }
}

private val COUNTRY_CODE = Regex("[1-9][0-9]{0,2}")

private fun validManaged(policy: BootCallPolicy): BootPolicyRead {
    if (!COUNTRY_CODE.matches(policy.defaultCc)) return BootPolicyRead.Corrupt("default_cc")
    // Every number must be in the normalised form the rules compare against.
    val bad = (policy.inbound + policy.outbound).any { PhoneNumbers.normalize(it, policy.defaultCc)?.value != it }
    return if (bad) BootPolicyRead.Corrupt("number") else BootPolicyRead.Ok(policy)
}

/** What the call path enforces before the first unlock: the DE copy, or fail closed. */
fun bootPolicyState(read: BootPolicyRead): CallPolicyState {
    val policy = (read as? BootPolicyRead.Ok)?.policy ?: return CallPolicyState.UnknownFailClosed
    return when (policy.mode) {
        BootCallPolicy.UNMANAGED -> CallPolicyState.Unmanaged
        BootCallPolicy.MANAGED -> {
            val numbers = (policy.inbound + policy.outbound).distinct()
            CallPolicyState.Managed(
                CallRules(
                    callsEnabled = policy.callsEnabled,
                    // SMS is enforced by the persisted DISALLOW_SMS restriction, not by the call path.
                    smsEnabled = false,
                    defaultCc = policy.defaultCc,
                    contacts = numbers.map {
                        RuleContact(number = it, inbound = it in policy.inbound, outbound = it in policy.outbound)
                    },
                ),
            )
        }
        else -> CallPolicyState.UnknownFailClosed
    }
}

/** The new DE string for the CE-derived [state], or `null` when [current] already says the same. */
fun bootPolicyRewrite(current: String?, state: CallPolicyState): String? {
    val desired = encodeBootPolicy(bootPolicyFor(state))
    return if (current == desired) null else desired
}
