package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.HardeningPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.timerules.TimePolicy
import kotlinx.serialization.Serializable

/*
 * Fail-closed decisions about which policy to enforce - pure functions with no Android imports,
 * so they're unit-tested on the JVM (PolicyGateTest). MdmSyncWorker, AppEnforcer,
 * SettingsFragmentLauncher and KidSettingsActivity feed them the cached policy blob, the fresh
 * server response and the `policy_ever_applied` flag, and act on the answer.
 *
 * Upstream treated every "no usable policy" case as `null`, and `AppEnforcer.apply(null)` means
 * "no restrictions": a corrupt cache, or a server answering a default policy after an error,
 * unsuspended every app and unpinned kiosk. Here only a phone that has genuinely never had a
 * policy gets that treatment (setup must still work); everything else enforces the last policy it
 * accepted (the cache, or the small [LastEnforcedPlan] stored with it), or nothing allowed.
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
 * Whether a freshly fetched policy may replace what the phone enforces now. Two patterns are
 * rejected, both "a server falling back to defaults" rather than a parent's choice:
 * - "no allowlist" (`allowlist == null`, i.e. unmanaged) arriving at a phone that was already
 *   managed: our server never sets a managed allowlist back to NULL (unchecking the last app
 *   writes `[]`). "Already managed" is: the cached policy has an allowlist, or the cache is
 *   unusable (corrupt, or missing although a policy was applied before) - in those cases we can't
 *   prove the phone was unmanaged, so we don't open it up.
 * - no `call_policy` at all on a phone whose calls were managed ([callsManagedLast], written with
 *   the cache, or a cached managed `call_policy`): our server always sends it with an explicit
 *   `managed`, so a missing key is a rolled-back, buggy or forged response (QA blocker 3). The
 *   cached policy, with the last managed call rules, stays in force.
 * - no `time_policy` on a phone that has had one ([timePolicySeen], written with the cache, or a
 *   cached policy with one): our server always sends it (handy step 6), so the rules, budget and
 *   lifts the phone has stay in force rather than falling back to the frozen legacy windows.
 * A legitimate `[]`, removing the PIN, or `call_policy.managed = false` is accepted.
 */
fun judgeFresh(
    fresh: PolicyResponse,
    cached: CachedPolicy,
    policyEverApplied: Boolean,
    callsManagedLast: Boolean = false,
    timePolicySeen: Boolean = false,
): FreshVerdict {
    if (fresh.timePolicy == null) {
        val seen = timePolicySeen || (cached is CachedPolicy.Ok && cached.policy.timePolicy != null)
        if (seen) return FreshVerdict.REJECT_SUSPECT
    }
    if (fresh.callPolicy == null) {
        val callsWereManaged = callsManagedLast ||
            (cached is CachedPolicy.Ok && cached.policy.callPolicy?.managed == true)
        if (callsWereManaged) return FreshVerdict.REJECT_SUSPECT
    }
    if (fresh.allowlist != null) return FreshVerdict.ACCEPT
    val wasManaged = when (cached) {
        is CachedPolicy.Ok -> cached.policy.allowlist != null
        is CachedPolicy.Corrupt -> true
        CachedPolicy.Absent -> policyEverApplied
    }
    return if (wasManaged) FreshVerdict.REJECT_SUSPECT else FreshVerdict.ACCEPT
}

/**
 * The few fields needed to re-lock the phone when the cached policy can't be used: stored next to
 * the cache, in the same commit, every time a policy is accepted. Deliberately tiny and separate
 * from [PolicyResponse], so a launcher update that can't read an old cache blob can still read
 * this. Unknown keys are ignored and every field has a default.
 */
@Serializable
data class LastEnforcedPlan(
    val allowlist: List<String>? = emptyList(),
    val kioskDesired: Boolean = true,
    val lockTaskFeatures: Long = LOCK_TASK_FEATURE_KEYGUARD.toLong(),
    val weekdayStartMinutes: Int? = null,
    val weekdayEndMinutes: Int? = null,
    val weekendStartMinutes: Int? = null,
    val weekendEndMinutes: Int? = null,
    val bedtimeStartMinutes: Int? = null,
    val bedtimeEndMinutes: Int? = null,
    /** The hardening switches, so a fallback keeps the parent's choices (`null` = defaults). */
    val hardening: HardeningPolicy? = null,
    /** The time rules and budget, without lifts (`null` = the windows above, converted). */
    val timePolicy: TimePolicy? = null,
    /** The kiosk app block switch (step 9); missing = off, like [PolicyResponse.blockActivityStart]. */
    val blockActivityStart: Boolean = false,
) {
    fun toPolicy(): PolicyResponse = PolicyResponse(
        allowlist = allowlist,
        kioskDesired = kioskDesired,
        lockTaskFeatures = lockTaskFeatures,
        weekdayStartMinutes = weekdayStartMinutes,
        weekdayEndMinutes = weekdayEndMinutes,
        weekendStartMinutes = weekendStartMinutes,
        weekendEndMinutes = weekendEndMinutes,
        bedtimeStartMinutes = bedtimeStartMinutes,
        bedtimeEndMinutes = bedtimeEndMinutes,
        hardening = hardening,
        timePolicy = timePolicy,
        blockActivityStart = blockActivityStart,
    )

    companion object {
        fun of(policy: PolicyResponse) = LastEnforcedPlan(
            allowlist = policy.allowlist,
            kioskDesired = policy.kioskDesired,
            lockTaskFeatures = policy.lockTaskFeatures,
            weekdayStartMinutes = policy.weekdayStartMinutes,
            weekdayEndMinutes = policy.weekdayEndMinutes,
            weekendStartMinutes = policy.weekendStartMinutes,
            weekendEndMinutes = policy.weekendEndMinutes,
            bedtimeStartMinutes = policy.bedtimeStartMinutes,
            bedtimeEndMinutes = policy.bedtimeEndMinutes,
            hardening = policy.hardening,
            timePolicy = policy.timePolicy?.copy(lifts = emptyList()),
            blockActivityStart = policy.blockActivityStart,
        )

        /** `null` if missing or unreadable. */
        fun decode(json: String?): LastEnforcedPlan? {
            if (json.isNullOrBlank()) return null
            return try {
                ServerJson.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
        }

        fun encode(plan: LastEnforcedPlan): String = ServerJson.encodeToString(serializer(), plan)
    }
}

/** What to enforce. Callers pass [policy] to `AppEnforcer.apply`; `null` means "no policy". */
sealed interface PolicyToApply {
    val policy: PolicyResponse?

    /** A real policy (fresh or cached), or `null` on a phone that has never had one. */
    data class Apply(override val policy: PolicyResponse?) : PolicyToApply

    /**
     * No usable cached policy on a phone that has had one: enforce the last-enforced plan, or -
     * if that's unreadable too - nothing allowed with kiosk on. Re-locks the phone when an
     * override or pause ends, instead of leaving it as the override left it (open).
     */
    data class Fallback(override val policy: PolicyResponse) : PolicyToApply
}

/** Used when neither the cache nor the last-enforced plan can be read: only our own package. */
val NOTHING_ALLOWED_FALLBACK: PolicyResponse = LastEnforcedPlan().toPolicy()

/**
 * [fresh] must already have passed [judgeFresh] (pass `null` when there's no acceptable fresh
 * policy). Falls back to the cache; `Apply(null)` only for a phone that has never applied a
 * policy, so setup/enrollment still works on a brand-new device; otherwise [PolicyToApply.Fallback].
 */
fun choosePolicy(
    fresh: PolicyResponse?,
    cached: CachedPolicy,
    policyEverApplied: Boolean,
    lastEnforced: LastEnforcedPlan?,
): PolicyToApply =
    when {
        fresh != null -> PolicyToApply.Apply(fresh)
        cached is CachedPolicy.Ok -> PolicyToApply.Apply(cached.policy)
        cached is CachedPolicy.Absent && !policyEverApplied -> PolicyToApply.Apply(null)
        else -> PolicyToApply.Fallback(lastEnforced?.toPolicy() ?: NOTHING_ALLOWED_FALLBACK)
    }

/** What the last sync did with the server's policy, as reported in `StatusReportRequest.policyState`. */
enum class FreshOutcome { ACCEPTED, REJECTED_SUSPECT, DECODE_FAILED, SERVER_ERROR, UNREACHABLE }

/**
 * `"ok"` unless the phone isn't enforcing the server's current policy: `"fresh_decode_failed"`,
 * `"rejected_suspect"`, `"server_error"` (the server answered 5xx - it couldn't build a policy,
 * see kid-phone-server's `build_policy`; this is the only way the parent learns of it), or
 * `"cache_corrupt"` (no fresh policy and the cache is unreadable, or
 * missing after a policy was applied). The server shows anything but `"ok"` as a warning.
 */
fun policyState(outcome: FreshOutcome, cached: CachedPolicy, policyEverApplied: Boolean): String =
    when (outcome) {
        FreshOutcome.ACCEPTED -> "ok"
        FreshOutcome.REJECTED_SUSPECT -> "rejected_suspect"
        FreshOutcome.DECODE_FAILED -> "fresh_decode_failed"
        FreshOutcome.SERVER_ERROR -> "server_error"
        FreshOutcome.UNREACHABLE -> when {
            cached is CachedPolicy.Corrupt -> "cache_corrupt"
            cached is CachedPolicy.Absent && policyEverApplied -> "cache_corrupt"
            else -> "ok"
        }
    }

/**
 * The pure core of [AppEnforcer.enforceOnNewPackage]: should a just-installed [packageName] be
 * suspended and hidden right away? Same rules as [computeEnforcementPlan] (never our own package
 * or the system dialer; everything else while [scheduleLocked] except the lock's [lockUsableApps]);
 * with no usable policy on a phone that has had one, the [PolicyToApply.Fallback] plan decides
 * (nothing allowed if even that is unreadable).
 */
fun shouldSuspendNewPackage(
    packageName: String,
    decision: PolicyToApply,
    overrideActive: Boolean,
    ownPackage: String,
    systemDialer: String?,
    scheduleLocked: Boolean = false,
    lockUsableApps: Set<String> = emptySet(),
): Boolean {
    if (overrideActive) return false
    if (packageName == ownPackage || packageName == systemDialer) return false
    // Play services/GSF are never restricted (FCM); the Play Store is suspended by the next
    // apply() according to install mode and the update window - an update of it lands here too.
    if (packageName in com.kidslauncher.mdm.play.PLAY_CORE) return false
    if (scheduleLocked && packageName !in lockUsableApps) return true
    val allowlist = decision.policy?.allowlist ?: return false
    return packageName !in allowlist
}
