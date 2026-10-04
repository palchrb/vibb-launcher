package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.managed

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
     * `UserManager.DISALLOW_OUTGOING_CALLS` (emergency calls are exempt; the launcher also leaves
     * the system dialer off Home while it's set, `AppFilter`):
     * - calls managed: when calls are off, or while our own dialer isn't in place (we don't hold
     *   the dialer role) - then nothing of ours screens the system dialer's keypad. With our
     *   dialer in place, our call-redirection and in-call services filter outgoing calls instead,
     *   so allowed contacts can be called.
     * - calls fail closed: always.
     * - calls not managed (step 1): while the app allowlist is managed and the parent hasn't
     *   allowlisted the system dialer - its keypad would otherwise call any number and run
     *   MMI/USSD codes.
     * Call rules are never lifted by an override or pause (decision after QA review).
     */
    val restrictOutgoingCalls: Boolean,
    /** `UserManager.DISALLOW_SMS`: calls managed and SMS off, or calls fail closed. Not lifted by
     * an override. The SMS apps are also suspended then (in [suspend]) because RCS chat bypasses
     * this restriction (QA #8). */
    val restrictSms: Boolean,
    /** Deny CALL_PHONE/ANSWER_PHONE_CALLS to third-party apps: whenever calls are managed. */
    val denyCallPermissions: Boolean,
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
 * - [overrideActive] (offline-override PIN or the PIN-gated pause) releases every app restriction,
 *   but never the call rules ([callState]).
 * - [ownPackage] is never suspended and always pinned.
 * - [systemDialer] (`TelecomManager.getSystemDialerPackage()`) is never suspended or hidden,
 *   managed or not: it's the in-call UI for emergency calls. Unsuspended, its keypad would be a
 *   way around the allowlist (any number, MMI/USSD, call forwarding), so outgoing calls are
 *   restricted instead unless our own dialer screens them ([EnforcementPlan.restrictOutgoingCalls]).
 *   Leaving it out of [EnforcementPlan.kioskPackages] does NOT keep it out of kiosk mode: with
 *   [LOCK_TASK_FEATURE_KEYGUARD] set, AOSP's LockTaskController lets any system-dialer task run
 *   (its emergency-call exemption), e.g. via a `tel:` link - the call restriction (or our
 *   redirection/in-call services) is what actually stops it. Keyguard is always forced on with
 *   kiosk (not only when the server sends it): lock-screen emergency calls and booting after an
 *   auto-reboot depend on it. While calls are managed the dialer is never pinned, even if
 *   allowlisted (QA 02 criterion T8).
 * - [callState]: the call rules in force; [ourDialerActive]: we hold the dialer role, so our
 *   services see every call. [smsPackages]: the default SMS app and the like (Messages, STK);
 *   while SMS is blocked they're suspended whatever the allowlist says, override or not.
 */
fun computeEnforcementPlan(
    allowlist: List<String>?,
    kioskDesired: Boolean,
    serverLockTaskFeatures: Long,
    overrideActive: Boolean,
    controllable: Collection<String>,
    ownPackage: String,
    systemDialer: String?,
    callState: CallPolicyState = CallPolicyState.Unmanaged,
    ourDialerActive: Boolean = false,
    smsPackages: Set<String> = emptySet(),
): EnforcementPlan {
    val neverRestrict = setOfNotNull(ownPackage, systemDialer)
    val features = serverLockTaskFeatures.toInt() or LOCK_TASK_FEATURE_KEYGUARD
    val appsManaged = allowlist != null && !overrideActive
    val allowed = allowlist.orEmpty().toSet()

    val restrictSms = when (callState) {
        CallPolicyState.Unmanaged -> false
        CallPolicyState.UnknownFailClosed -> true
        is CallPolicyState.Managed -> !callState.rules.smsEnabled
    }
    val restrictOutgoingCalls = when (callState) {
        CallPolicyState.Unmanaged -> appsManaged && systemDialer != null && systemDialer !in allowed
        CallPolicyState.UnknownFailClosed -> true
        is CallPolicyState.Managed -> !callState.rules.callsEnabled || !ourDialerActive
    }

    val suspend = mutableSetOf<String>()
    if (appsManaged) controllable.filterTo(suspend) { it !in allowed && it !in neverRestrict }
    if (restrictSms) controllable.filterTo(suspend) { it in smsPackages && it !in neverRestrict }

    val kiosk = if (appsManaged && kioskDesired) {
        val pinned = allowed + ownPackage
        if (callState.managed && systemDialer != null) pinned - systemDialer else pinned
    } else {
        null
    }
    return EnforcementPlan(
        suspend = suspend,
        kioskPackages = kiosk,
        lockTaskFeatures = features,
        neverRestrict = neverRestrict,
        restrictOutgoingCalls = restrictOutgoingCalls,
        lockDateTime = appsManaged,
        restrictSms = restrictSms,
        denyCallPermissions = callState.managed,
    )
}

/** Runtime permissions that let an app place or answer calls itself, around our dialer. */
val CALL_PERMISSIONS = setOf("android.permission.CALL_PHONE", "android.permission.ANSWER_PHONE_CALLS")

/** Messaging apps suspended while SMS is blocked, besides the default SMS app: Google Messages
 * (RCS chats over data with any number - DISALLOW_SMS doesn't cover RCS, QA #8), AOSP Messaging,
 * and the SIM toolkit (can send SMS/USSD). Only installed, controllable ones are touched. */
val KNOWN_SMS_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.android.messaging",
    "com.android.mms",
    "com.android.stk",
)

fun smsPackages(defaultSmsPackage: String?): Set<String> = KNOWN_SMS_PACKAGES + setOfNotNull(defaultSmsPackage)

/**
 * Third-party apps whose call permissions are denied while calls are managed: every non-system
 * package except ours that requests one of [CALL_PERMISSIONS] ([requested]: non-system packages
 * only, mapped to their requested permissions). System apps are left alone - denying them is
 * risky, and the redirection/in-call services cover their calls.
 */
fun callPermissionTargets(requested: Map<String, Collection<String>>, ownPackage: String): Set<String> =
    requested.filter { (pkg, perms) -> pkg != ownPackage && perms.any { it in CALL_PERMISSIONS } }.keys
