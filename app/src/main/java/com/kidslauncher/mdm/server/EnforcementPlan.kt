package com.kidslauncher.mdm.server

/*
 * The pure part of AppEnforcer.apply(): which packages to suspend+hide and which to pin in
 * kiosk mode, given the allowlist. No Android imports, unit-tested in EnforcementPlanTest.
 * AppEnforcer only diffs this plan against the live PackageManager/DevicePolicyManager state.
 */

/** `DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD`, duplicated so this file stays Android-free. */
const val LOCK_TASK_FEATURE_KEYGUARD = 32

data class EnforcementPlan(
    /** Packages that must be suspended and hidden. Everything else controllable is released. */
    val suspend: Set<String>,
    /** Packages to pin with `setLockTaskPackages`, or `null` for "kiosk off". */
    val kioskPackages: Set<String>?,
    /** Lock-task features to set before pinning; only meaningful when [kioskPackages] is set. */
    val lockTaskFeatures: Int,
    /** Packages that must be unsuspended and unhidden whatever their current state, every cycle. */
    val neverRestrict: Set<String>,
)

/**
 * - [allowlist] `null` means unmanaged: nothing suspended, no kiosk. `[]` means nothing allowed:
 *   every controllable package is suspended, and kiosk (if desired) pins only our own package -
 *   which is HOME and holds LockActivity/Settings, so the phone stays usable.
 * - [overrideActive] (offline-override PIN or the PIN-gated pause) releases everything.
 * - [ownPackage] is never suspended and always pinned.
 * - [systemDialer] (`TelecomManager.getSystemDialerPackage()`) is never suspended or hidden,
 *   managed or not: it's the in-call UI for emergency calls. It is not added to the kiosk
 *   packages on our account (its keypad and `tel:` links would bypass the allowlist); it is only
 *   pinned if the parent allowlisted it. Emergency calls from the lock screen work through the
 *   keyguard, which is why [LOCK_TASK_FEATURE_KEYGUARD] is always forced on with kiosk - not only
 *   when the server sends it (the server's always-on bit is the first line, this the second).
 */
fun computeEnforcementPlan(
    allowlist: List<String>?,
    kioskDesired: Boolean,
    serverLockTaskFeatures: Long,
    overrideActive: Boolean,
    controllable: Collection<String>,
    ownPackage: String,
    systemDialer: String?,
): EnforcementPlan {
    val neverRestrict = setOfNotNull(ownPackage, systemDialer)
    val features = serverLockTaskFeatures.toInt() or LOCK_TASK_FEATURE_KEYGUARD
    if (overrideActive || allowlist == null) {
        return EnforcementPlan(emptySet(), null, features, neverRestrict)
    }
    val allowed = allowlist.toSet()
    val suspend = controllable.filterTo(mutableSetOf()) { it !in allowed && it !in neverRestrict }
    val kiosk = if (kioskDesired) allowed + ownPackage else null
    return EnforcementPlan(suspend, kiosk, features, neverRestrict)
}
