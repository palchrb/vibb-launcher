package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse

/*
 * Fail-closed decisions about which policy to enforce - pure functions with no Android imports,
 * so they're unit-tested on the JVM (PolicyGateTest). MdmSyncWorker, AppEnforcer,
 * SettingsFragmentLauncher and QuickControlsActivity feed them the cached policy blob, the fresh
 * server response and the `policy_ever_applied` flag, and act on the answer.
 *
 * Upstream treated every "no usable policy" case as `null`, and `AppEnforcer.apply(null)` means
 * "no restrictions": a corrupt cache, or a server answering a default policy after an error,
 * unsuspended every app and unpinned kiosk. Here only a phone that has genuinely never had a
 * policy gets that treatment (setup must still work); everything else keeps whatever the OS is
 * already enforcing (DPM suspensions, lock-task packages and user restrictions persist on their
 * own).
 */

/** What the cached policy preference holds. */
sealed interface CachedPolicy {
    /** Nothing cached (never synced, or wiped). */
    data object Absent : CachedPolicy

    data class Ok(val policy: PolicyResponse) : CachedPolicy

    /** Something is cached but doesn't decode. [error] is for logs only. */
    data class Corrupt(val error: String) : CachedPolicy
}

fun decodeCached(json: String?): CachedPolicy {
    if (json.isNullOrBlank()) return CachedPolicy.Absent
    return try {
        CachedPolicy.Ok(ServerJson.decodeFromString(PolicyResponse.serializer(), json))
    } catch (e: Exception) {
        CachedPolicy.Corrupt(e.toString())
    }
}

/** The outcome of decoding a 2xx `GET /api/devices/policy` body. */
sealed interface FreshDecode {
    data class Ok(val policy: PolicyResponse) : FreshDecode

    /** The server answered but the launcher can't read it - typically a version mismatch, e.g. a
     * `null` for a field this DTO declares non-nullable. Reported as `fresh_decode_failed` rather
     * than looking like "server unreachable" forever. */
    data class Failed(val error: String) : FreshDecode
}

fun decodeFresh(json: String?): FreshDecode {
    if (json.isNullOrBlank()) return FreshDecode.Failed("empty body")
    return try {
        FreshDecode.Ok(ServerJson.decodeFromString(PolicyResponse.serializer(), json))
    } catch (e: Exception) {
        FreshDecode.Failed(e.toString())
    }
}

enum class FreshVerdict { ACCEPT, REJECT_SUSPECT }

/**
 * Whether a freshly fetched policy may replace what the phone enforces now. The one pattern
 * rejected is "no allowlist" (`allowlist == null`, i.e. unmanaged) arriving at a phone that was
 * already managed: our server never sets a managed allowlist back to NULL (unchecking the last
 * app writes `[]`), so this only comes from an old or buggy server falling back to a default
 * policy. "Already managed" is: the cached policy has an allowlist, or the cache is unusable
 * (corrupt, or missing although a policy was applied before) - in those cases we can't prove the
 * phone was unmanaged, so we don't open it up. A legitimate `[]`, or removing the PIN, is accepted.
 */
fun judgeFresh(fresh: PolicyResponse, cached: CachedPolicy, policyEverApplied: Boolean): FreshVerdict {
    if (fresh.allowlist != null) return FreshVerdict.ACCEPT
    val wasManaged = when (cached) {
        is CachedPolicy.Ok -> cached.policy.allowlist != null
        is CachedPolicy.Corrupt -> true
        CachedPolicy.Absent -> policyEverApplied
    }
    return if (wasManaged) FreshVerdict.REJECT_SUSPECT else FreshVerdict.ACCEPT
}

sealed interface PolicyToApply {
    /** Enforce this policy; `null` means "no policy" (fully open). */
    data class Apply(val policy: PolicyResponse?) : PolicyToApply

    /** Touch nothing: leave the suspensions, kiosk pinning and lock decision as they are. */
    data object KeepCurrentState : PolicyToApply
}

/**
 * [fresh] must already have passed [judgeFresh] (pass `null` when there's no acceptable fresh
 * policy). Falls back to the cache; `Apply(null)` only for a phone that has never applied a
 * policy, so setup/enrollment still works on a brand-new device.
 */
fun choosePolicy(fresh: PolicyResponse?, cached: CachedPolicy, policyEverApplied: Boolean): PolicyToApply =
    when {
        fresh != null -> PolicyToApply.Apply(fresh)
        cached is CachedPolicy.Ok -> PolicyToApply.Apply(cached.policy)
        cached is CachedPolicy.Absent && !policyEverApplied -> PolicyToApply.Apply(null)
        else -> PolicyToApply.KeepCurrentState
    }

/** What the last sync did with the server's policy, as reported in `StatusReportRequest.policyState`. */
enum class FreshOutcome { ACCEPTED, REJECTED_SUSPECT, DECODE_FAILED, UNREACHABLE }

/**
 * `"ok"` unless the phone isn't enforcing the server's current policy: `"fresh_decode_failed"`,
 * `"rejected_suspect"`, or `"cache_corrupt"` (no fresh policy and the cache is unreadable, or
 * missing after a policy was applied). The server shows anything but `"ok"` as a warning.
 */
fun policyState(outcome: FreshOutcome, cached: CachedPolicy, policyEverApplied: Boolean): String =
    when (outcome) {
        FreshOutcome.ACCEPTED -> "ok"
        FreshOutcome.REJECTED_SUSPECT -> "rejected_suspect"
        FreshOutcome.DECODE_FAILED -> "fresh_decode_failed"
        FreshOutcome.UNREACHABLE -> when {
            cached is CachedPolicy.Corrupt -> "cache_corrupt"
            cached is CachedPolicy.Absent && policyEverApplied -> "cache_corrupt"
            else -> "ok"
        }
    }

/**
 * The pure core of [AppEnforcer.enforceOnNewPackage]: should a just-installed [packageName] be
 * suspended and hidden right away? Same rules as [computeEnforcementPlan] (never our own package
 * or the system dialer), and fail closed when there's no usable policy on a phone that has had
 * one ([PolicyToApply.KeepCurrentState]): a new app on a managed phone stays blocked until a real
 * policy says otherwise.
 */
fun shouldSuspendNewPackage(
    packageName: String,
    decision: PolicyToApply,
    overrideActive: Boolean,
    ownPackage: String,
    systemDialer: String?,
): Boolean {
    if (overrideActive) return false
    if (packageName == ownPackage || packageName == systemDialer) return false
    return when (decision) {
        PolicyToApply.KeepCurrentState -> true
        is PolicyToApply.Apply -> {
            val allowlist = decision.policy?.allowlist ?: return false
            packageName !in allowlist
        }
    }
}
