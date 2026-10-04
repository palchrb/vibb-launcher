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
    /**
     * `UserManager.DISALLOW_OUTGOING_CALLS`: on while managed and the parent hasn't allowlisted the
     * system dialer. The dialer itself stays usable for emergency calls (the platform exempts
     * them from this restriction), but its keypad can no longer call other numbers or run
     * MMI/USSD codes. The launcher also leaves it off Home while this is set (`AppFilter`).
     */
    val restrictOutgoingCalls: Boolean,
    /**
     * `UserManager.DISALLOW_CONFIG_DATE_TIME` (plus automatic time) while managed, so the clock
     * can't be turned back to stretch a schedule or an override. Lifted with every other
     * restriction while an override is active.
     */
    val lockDateTime: Boolean,
)

/**
 * - [allowlist] `null` means unmanaged: nothing suspended, no kiosk. `[]` means nothing allowed:
 *   every controllable package is suspended, and kiosk (if desired) pins only our own package -
 *   which is HOME and holds LockActivity/Settings, so the phone stays usable.
 * - [overrideActive] (offline-override PIN or the PIN-gated pause) releases everything.
 * - [ownPackage] is never suspended and always pinned.
 * - [systemDialer] (`TelecomManager.getSystemDialerPackage()`) is never suspended or hidden,
 *   managed or not: it's the in-call UI for emergency calls. Unsuspended, its keypad would be a
 *   way around the allowlist (any number, MMI/USSD, call forwarding), so while managed and not
 *   allowlisted, outgoing calls are restricted instead ([EnforcementPlan.restrictOutgoingCalls]).
 *   Leaving it out of [EnforcementPlan.kioskPackages] does NOT keep it out of kiosk mode: with
 *   [LOCK_TASK_FEATURE_KEYGUARD] set, AOSP's LockTaskController lets any system-dialer task run
 *   (its emergency-call exemption), e.g. via a `tel:` link - the call restriction is what
 *   actually stops it. Keyguard is always forced on with kiosk (not only when the server sends
 *   it): lock-screen emergency calls and booting after an auto-reboot depend on it.
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
        return EnforcementPlan(
            emptySet(), null, features, neverRestrict,
            restrictOutgoingCalls = false, lockDateTime = false,
        )
    }
    val allowed = allowlist.toSet()
    val suspend = controllable.filterTo(mutableSetOf()) { it !in allowed && it !in neverRestrict }
    val kiosk = if (kioskDesired) allowed + ownPackage else null
    return EnforcementPlan(
        suspend, kiosk, features, neverRestrict,
        restrictOutgoingCalls = systemDialer != null && systemDialer !in allowed,
        lockDateTime = true,
    )
}
