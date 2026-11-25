package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.anyChannelManaged
import com.kidslauncher.mdm.server.dto.HardeningPolicy
import com.kidslauncher.mdm.server.dto.LocationPolicy
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

    /** Something is cached but doesn't decode. [error] is for logs only: the exception class, never its
     * message (a decode error quotes the input, which may hold design 36's secret servers). */
    data class Corrupt(val error: String) : CachedPolicy
}

fun decodeCached(json: String?): CachedPolicy {
    if (json.isNullOrBlank()) return CachedPolicy.Absent
    return try {
        CachedPolicy.Ok(ServerJson.decodeFromString(PolicyResponse.serializer(), json))
    } catch (e: Exception) {
        CachedPolicy.Corrupt(e.javaClass.simpleName)
    }
}

/**
 * A cached policy that decodes but has no app list (`allowlist == null`, unmanaged) on a phone that was managed counts as
 * unreadable ([CachedPolicy.Corrupt]): the sync then enforces the last-enforced plan, and the report says
 * `cache_corrupt`. "Was managed" is the [LastEnforcedPlan] written in the same commit as every accepted policy: it has an
 * app list, or it is missing or unreadable although a policy was applied ([policyEverApplied]). A phone whose apps are
 * genuinely unmanaged has a plan without an app list, and its cache stays [CachedPolicy.Ok]; a phone that has never had
 * a policy is unchanged (setup must still work).
 */
fun judgeCached(cached: CachedPolicy, policyEverApplied: Boolean, lastEnforced: LastEnforcedPlan?): CachedPolicy {
    if (cached !is CachedPolicy.Ok || cached.policy.allowlist != null || !policyEverApplied) return cached
    val wasManaged = lastEnforced == null || lastEnforced.allowlist != null
    return if (wasManaged) CachedPolicy.Corrupt("no_allowlist") else cached
}

/** The policy as `storeAcceptedPolicy` caches it: without `set_kid_pin`'s Child PIN (design 59 §1) -
 * the PIN lives only in the one fresh answer it came in. */
fun cacheablePolicy(policy: PolicyResponse): PolicyResponse =
    policy.pendingCommand?.takeIf { it.pin != null }?.let { policy.copy(pendingCommand = it.copy(pin = null)) } ?: policy

/** What `storeAcceptedPolicy` writes (design r10 §3.2): [cacheablePolicy] with the kid PIN's hash and salt `""`
 * (redacted, as the parent PIN's, [redactedPolicy]) - the sealed `pin_lock_state` copy is the only one on disk. Only
 * the cache: the sync applies the policy with the hash, so `configure` seals it from that apply. */
fun policyToCache(policy: PolicyResponse): PolicyResponse = cacheablePolicy(policy).let { cacheable ->
    cacheable.kidLock?.let { cacheable.copy(kidLock = it.copy(pinHash = "", pinSalt = "")) } ?: cacheable
}

/** The outcome of decoding a 2xx `GET /api/devices/policy` body. */
sealed interface FreshDecode {
    data class Ok(val policy: PolicyResponse) : FreshDecode

    /** The server answered but the launcher can't read it - typically a version mismatch, e.g. a
     * `null` for a field this DTO declares non-nullable. Reported as `fresh_decode_failed` rather
     * than looking like "server unreachable" forever. [error]: the exception class only, like
     * [CachedPolicy.Corrupt]. */
    data class Failed(val error: String) : FreshDecode
}

fun decodeFresh(json: String?): FreshDecode {
    if (json.isNullOrBlank()) return FreshDecode.Failed("empty body")
    return try {
        FreshDecode.Ok(ServerJson.decodeFromString(PolicyResponse.serializer(), json))
    } catch (e: Exception) {
        FreshDecode.Failed(e.javaClass.simpleName)
    }
}

enum class FreshVerdict { ACCEPT, REJECT_SUSPECT }

/** Design 77 D2: both channels' states sent, each one of the three wire names. */
val com.kidslauncher.mdm.server.dto.CallPolicy.hasChannelModes: Boolean
    get() = com.kidslauncher.mdm.calls.ChannelMode.of(callsMode) != null &&
        com.kidslauncher.mdm.calls.ChannelMode.of(smsMode) != null

/**
 * Whether a freshly fetched policy may replace what the phone enforces now. Two patterns are
 * rejected, both "a server falling back to defaults" rather than a parent's choice:
 * - "no allowlist" (`allowlist == null`, i.e. unmanaged) arriving at a phone that was already
 *   managed: our server never sets a managed allowlist back to NULL (unchecking the last app
 *   writes `[]`). "Already managed" is: the cached policy has an allowlist, or the cache is
 *   unusable (corrupt, or missing although a policy was applied before) - in those cases we can't
 *   prove the phone was unmanaged, so we don't open it up.
 * - no `call_policy` at all on a phone whose calls were managed ([callsManagedLast], written with
 *   the cache, or a cached `call_policy` with either channel managed): our server always sends it,
 *   so a missing key is a rolled-back, buggy or forged response (QA blocker 3). The cached policy,
 *   with the last managed call rules, stays in force.
 * - no `time_policy` on a phone that has had one ([timePolicySeen], written with the cache, or a
 *   cached policy with one): our server always sends it (handy step 6), so the rules, budget and
 *   lifts the phone has stay in force.
 * - a `call_policy` without `calls_mode`/`sms_mode` on a phone that has had them ([channelModesSeen], written with the
 *   cache, or a cached policy with them; design 77 D2): a rolled-back server can't loosen a channel silently. A mode
 *   that isn't one of the three is rejected always - we can't tell what it would allow.
 * A legitimate `[]`, removing the PIN, or both channels `unmanaged` is accepted.
 */
fun judgeFresh(
    fresh: PolicyResponse,
    cached: CachedPolicy,
    policyEverApplied: Boolean,
    callsManagedLast: Boolean = false,
    timePolicySeen: Boolean = false,
    channelModesSeen: Boolean = false,
): FreshVerdict {
    if (fresh.timePolicy == null) {
        val seen = timePolicySeen || (cached is CachedPolicy.Ok && cached.policy.timePolicy != null)
        if (seen) return FreshVerdict.REJECT_SUSPECT
    }
    if (fresh.callPolicy == null) {
        val callsWereManaged = callsManagedLast ||
            (cached is CachedPolicy.Ok && cached.policy.callPolicy?.anyChannelManaged == true)
        if (callsWereManaged) return FreshVerdict.REJECT_SUSPECT
    }
    fresh.callPolicy?.let { callPolicy ->
        if (callPolicy.callsMode == null && callPolicy.smsMode == null) {
            val seen = channelModesSeen || (cached is CachedPolicy.Ok && cached.policy.callPolicy?.hasChannelModes == true)
            if (seen) return FreshVerdict.REJECT_SUSPECT
        } else if (!callPolicy.hasChannelModes) {
            return FreshVerdict.REJECT_SUSPECT
        }
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
    /** The hardening switches, so a fallback keeps the parent's choices (`null` = defaults). */
    val hardening: HardeningPolicy? = null,
    /** The time rules and budget, without lifts (`null` = none). */
    val timePolicy: TimePolicy? = null,
    /** The kiosk app block switch (step 9); missing = off, like [PolicyResponse.blockActivityStart]. */
    val blockActivityStart: Boolean = false,
    /** The parent's screen timeout (seconds), so a fallback keeps auto-lock; `null` = none known. */
    val screenTimeoutSeconds: Int? = null,
    /** "App updates only on Wi-Fi" (design 13), so an unusable cache doesn't spend mobile data. */
    val appUpdatesWifiOnly: Boolean? = null,
    /** Design 97: "I utlandet"; a stored record without it reads Ingen data ([abroadChoice], fail closed). */
    val abroad: String? = null,
    /** Design 95 rev. 3: Bare Vibb (`"vibb"`) or not; missing reads as on. */
    val appsMobile: String? = null,
    /** Design 95 rev. 3 item 3: the mobile-network settings lock; missing reads locked (fail closed). */
    val mobileSettingsLocked: Boolean? = true,
    /** Design 126 A5: "Blokker 2G"; missing reads blocked (fail closed). */
    @kotlinx.serialization.SerialName("block_2g") val block2g: Boolean? = true,
    /** Design 126 A5 (2026-11-22): "Tillat 2G i utlandet"; missing reads off (no abroad lift, fail closed). */
    @kotlinx.serialization.SerialName("allow_2g_abroad") val allow2gAbroad: Boolean? = false,
    /** Advanced › Android Auto; missing reads hidden (fail closed). */
    val allowAndroidAuto: Boolean = false,
    /** Design 97 code QA L3: the Home Screen's switches, so a fallback keeps the kid's own Mobildata Off (bit 32); an
     * older record reads none. */
    val quickControlsMask: Long = 0,
    /** Security review P-M1 (2026-11-17 apps review): the parent's location choice - off, on request or every N
     * minutes, with the zones - so a fallback never tracks more than the parent chose; a record without it (written
     * before the fix) reads as on request ([enforcedLocationPolicy]). */
    val locationPolicy: LocationPolicy? = null,
) {
    fun toPolicy(): PolicyResponse = PolicyResponse(
        allowlist = allowlist,
        kioskDesired = kioskDesired,
        lockTaskFeatures = lockTaskFeatures,
        hardening = hardening,
        timePolicy = timePolicy,
        blockActivityStart = blockActivityStart,
        screenTimeoutSeconds = screenTimeoutSeconds,
        appUpdatesWifiOnly = appUpdatesWifiOnly,
        abroad = abroad,
        appsMobile = appsMobile,
        mobileSettingsLocked = mobileSettingsLocked,
        block2g = block2g,
        allow2gAbroad = allow2gAbroad,
        allowAndroidAuto = allowAndroidAuto,
        quickControlsMask = quickControlsMask,
        locationPolicy = locationPolicy,
    )

    companion object {
        fun of(policy: PolicyResponse) = LastEnforcedPlan(
            allowlist = policy.allowlist,
            kioskDesired = policy.kioskDesired,
            lockTaskFeatures = policy.lockTaskFeatures,
            hardening = policy.hardening,
            timePolicy = policy.timePolicy?.copy(lifts = emptyList()),
            blockActivityStart = policy.blockActivityStart,
            screenTimeoutSeconds = policy.screenTimeoutSeconds,
            appUpdatesWifiOnly = policy.appUpdatesWifiOnly,
            abroad = policy.abroad,
            appsMobile = policy.appsMobile,
            mobileSettingsLocked = policy.mobileSettingsLocked,
            block2g = policy.block2g,
            allow2gAbroad = policy.allow2gAbroad,
            allowAndroidAuto = policy.allowAndroidAuto,
            quickControlsMask = policy.quickControlsMask,
            locationPolicy = policy.locationPolicy,
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

    /** Design 59 §3.8 (L-1): the policy carries the parent's choices - a fresh one or the `Ok` cache. Never a
     * [Fallback] (the last-enforced plan has no `kid_lock`) nor `Apply(null)`. */
    val carriesParentChoice: Boolean get() = this is Apply && policy != null

    /** Where [policy] came from; Android's PIN is rolled back only on [PolicySource.SERVER] ([rollbackAllowed]). */
    val source: PolicySource
        get() = when (this) {
            is Apply -> when {
                policy == null -> PolicySource.NONE
                fromServer -> PolicySource.SERVER
                else -> PolicySource.CACHE
            }
            is Fallback -> PolicySource.FALLBACK
        }

    /** A real policy (fresh or cached), or `null` on a phone that has never had one. [fromServer]: the server sent it
     * in this sync (accepted by [judgeFresh]); `false` for the cache. */
    data class Apply(override val policy: PolicyResponse?, val fromServer: Boolean = false) : PolicyToApply

    /**
     * No usable cached policy on a phone that has had one: enforce the last-enforced plan, or -
     * if that's unreadable too - nothing allowed with kiosk on. Re-locks the phone when an
     * override or pause ends, instead of leaving it as the override left it (open).
     */
    data class Fallback(override val policy: PolicyResponse) : PolicyToApply
}

/** Where an enforced policy came from. */
enum class PolicySource { SERVER, CACHE, FALLBACK, NONE }

/**
 * Android's PIN is cleared ([com.kidslauncher.mdm.lock.rollbackDue]) only by the server's own choice: a policy it sent
 * in this sync without `kid_lock`'s Android keyguard. A saved copy, the last-enforced plan or no policy never clears it -
 * a saved copy can be older than the server's choice, or changed on the phone.
 */
fun rollbackAllowed(source: PolicySource): Boolean = source == PolicySource.SERVER

/** Used when neither the cache nor the last-enforced plan can be read: only our own package. */
val NOTHING_ALLOWED_FALLBACK: PolicyResponse = LastEnforcedPlan().toPolicy()

/**
 * [fresh] must already have passed [judgeFresh] (pass `null` when there's no acceptable fresh
 * policy), and [cached] [judgeCached]. Falls back to the cache; `Apply(null)` only for a phone that has never applied a
 * policy, so setup/enrollment still works on a brand-new device; otherwise [PolicyToApply.Fallback].
 */
fun choosePolicy(
    fresh: PolicyResponse?,
    cached: CachedPolicy,
    policyEverApplied: Boolean,
    lastEnforced: LastEnforcedPlan?,
): PolicyToApply =
    when {
        fresh != null -> PolicyToApply.Apply(fresh, fromServer = true)
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
 * or the system dialer or the system's [recentsPackage] (design 16d); everything else while
 * [scheduleLocked] except the lock's [lockUsableApps]);
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
    recentsPackage: String? = null,
    relockBlocked: Set<String> = emptySet(),
    /** Bug 25b-H: the Emergency SOS activity's package is never restricted (an update lands here). */
    emergencySos: String? = null,
): Boolean {
    if (overrideActive) return false
    if (packageName == ownPackage || packageName == systemDialer || packageName == recentsPackage) return false
    if (packageName == emergencySos) return false
    // Play services/GSF are never restricted (other apps' FCM - Element X); the Play Store is
    // suspended by the next apply() according to install mode and the update window - an update
    // of it lands here too.
    if (packageName in com.kidslauncher.mdm.play.PLAY_CORE) return false
    if (scheduleLocked && packageName !in lockUsableApps) return true
    val allowlist = decision.policy?.allowlist ?: return false
    // Design 24b §1.6: a relock-blocked package counts as not allowlisted.
    return packageName !in allowlist || packageName in relockBlocked
}
