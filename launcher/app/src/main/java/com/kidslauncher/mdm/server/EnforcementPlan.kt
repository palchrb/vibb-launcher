package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.play.PLAY_CORE
import com.kidslauncher.mdm.play.PLAY_NEVER_RESTRICT
import com.kidslauncher.mdm.play.PLAY_STORE
import com.kidslauncher.mdm.play.PlayState
import com.kidslauncher.mdm.play.playStoreSuspended

/*
 * The pure part of AppEnforcer.apply(): which packages to suspend+hide and which to pin in
 * kiosk mode, given the allowlist. No Android imports, unit-tested in EnforcementPlanTest.
 * AppEnforcer only diffs this plan against the live PackageManager/DevicePolicyManager state.
 */

/** `DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD`, duplicated so this file stays Android-free. */
const val LOCK_TASK_FEATURE_KEYGUARD = 32

data class EnforcementPlan(
    /** Packages that must be suspended. Everything else controllable is unsuspended. */
    val suspend: Set<String>,
    /**
     * Packages that must also be hidden: the ones not allowed at all (allowlist, SMS off). A
     * subset of [suspend]. Allowed apps suspended only by the schedule lock stay visible: hiding a
     * package broadcasts PACKAGE_REMOVED, which drops its alarms, jobs and widgets every night (QA
     * step 4 #2).
     */
    val hide: Set<String>,
    /** Packages to pin with `setLockTaskPackages`, or `null` for "kiosk off". */
    val kioskPackages: Set<String>?,
    /** Lock-task features to set before pinning; only meaningful when [kioskPackages] is set. */
    val lockTaskFeatures: Int,
    /** The recents provider when it is in [kioskPackages] - taken out while the PIN lock is LOCKED
     * ([lockTaskWhileLocked], qa-16d-code #1). */
    val recentsPin: String? = null,
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
     * can't be turned back to stretch a schedule, an override or the callback window after an
     * emergency call. Lifted while an override is active - unless calls are managed, or the policy
     * has any time rule or budget (handy step 6, QA #3: the rules run on the wall clock and the
     * zone, also on a phone with no allowlist, and a zone changed during an override would shift
     * every rule afterwards). Automatic time zone is forced together with it.
     */
    val lockDateTime: Boolean,
    /** `UserManager.DISALLOW_CREATE_WINDOWS` while a screen-time budget is set (QA step 6 #2:
     * overlay windows over our free screens would be uncounted use). Lifted by an override. */
    val restrictCreateWindows: Boolean = false,
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
 * - [scheduleLocked]: bedtime or outside screen time ([KidModeEnforcer.lockReasonNow] isn't NONE,
 *   so never under an override). Every controllable package except [neverRestrict] and
 *   [alarmApp] (the default alarm/clock app, so alarms ring) is suspended - but not hidden -
 *   whatever the allowlist (also an unmanaged one - the lock comes from a server policy), and
 *   kiosk pins only our own package (which holds LockActivity, the phone book and the in-call UI)
 *   plus an allowlisted system dialer while calls are unmanaged.
 * - [lockUsableApps]: while [scheduleLocked], the apps the lock leaves usable - a rule's exempt
 *   apps, or the contacts' messaging apps once the screen-time budget is used up (handy step 6,
 *   `decideTimeLock`). They are spared by the lock and pinned, but still only if the allowlist
 *   allows them. [ruleBlocksCalls]: the active rule allows no calls (school) - outgoing calls are
 *   then restricted (emergency calls are exempt) and the system dialer isn't pinned, whatever the
 *   call state; with calls managed the caller also passes the rule-restricted call state.
 * - [timeRulesSet]/[budgetSet]: the enforced time policy has any rule or budget / a budget (see
 *   [EnforcementPlan.lockDateTime] and [EnforcementPlan.restrictCreateWindows]).
 * - [inputMethods]: the enabled/default keyboards, never suspended or hidden - the PIN dialogs
 *   (lock screen, Settings gate) need one (QA step 4 #3).
 *   The call restrictions don't change: allowed calls and emergency calls keep working
 *   (qa-security P0 #3 - the overlay alone could be escaped through Recents or a notification).
 * - Play (handy step 7, [com.kidslauncher.mdm.play]): Play services and GSF are in
 *   [EnforcementPlan.neverRestrict] (FCM); the Play Store is never hidden but suspended while apps
 *   are managed or a lock is on, except in install mode ([playState], not during a lock) and the
 *   nightly update window (also during a lock - the screen is off). None of them is pinned in
 *   kiosk, whatever the allowlist - except the Play Store in install mode.
 * - The lock-task features are the server's (plus KEYGUARD and the block bit): design 16's drop of
 *   OVERVIEW with the block bit is reverted (16d, the user's rule: never remove a stock gesture
 *   without asking) - the recents provider is pinned with the other helpers instead
 *   ([HelperKind.RECENTS]).
 * - [recentsPackage]: the system's recents provider, a system app ([AppEnforcer.systemRecentsPackage]);
 *   in [EnforcementPlan.neverRestrict] (design 16d): never hidden or suspended by enforcement -
 *   not by the allowlist, SMS off or a time-rule lock - so quickstep and the gestures keep
 *   working on any phone, also one whose stock launcher has a launcher icon. AppFilter keeps it off
 *   Home and the drawer; only the update fence suspends it.
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
    scheduleLocked: Boolean = false,
    alarmApp: String? = null,
    inputMethods: Set<String> = emptySet(),
    lockUsableApps: Set<String> = emptySet(),
    ruleBlocksCalls: Boolean = false,
    timeRulesSet: Boolean = false,
    budgetSet: Boolean = false,
    playState: PlayState = PlayState(),
    blockActivityStart: Boolean = false,
    lockTaskHelpers: Set<String> = emptySet(),
    recentsPackage: String? = null,
): EnforcementPlan {
    val neverRestrict = setOfNotNull(ownPackage, systemDialer, recentsPackage) + inputMethods + PLAY_NEVER_RESTRICT
    val features = lockTaskFeatures(serverLockTaskFeatures, blockActivityStart)
    val appsManaged = allowlist != null && !overrideActive
    val locked = scheduleLocked && !overrideActive
    val allowed = allowlist.orEmpty().toSet()
    val callsBlockedByRule = locked && ruleBlocksCalls

    val restrictSms = when (callState) {
        CallPolicyState.Unmanaged -> false
        CallPolicyState.UnknownFailClosed -> true
        is CallPolicyState.Managed -> !callState.rules.smsEnabled
    }
    val restrictOutgoingCalls = callsBlockedByRule || when (callState) {
        CallPolicyState.Unmanaged -> appsManaged && systemDialer != null && systemDialer !in allowed
        CallPolicyState.UnknownFailClosed -> true
        is CallPolicyState.Managed -> !callState.rules.callsEnabled || !ourDialerActive
    }

    val hide = mutableSetOf<String>()
    // The Play Store is never hidden: FCM needs it installed, and hiding broadcasts a removal.
    if (appsManaged) controllable.filterTo(hide) { it !in allowed && it !in neverRestrict && it != PLAY_STORE }
    if (restrictSms) controllable.filterTo(hide) { it in smsPackages && it !in neverRestrict }
    val suspend = hide.toMutableSet()
    // The alarm app is spared by the lock only, so an alarm set inside bedtime still rings; it
    // isn't pinned, so in kiosk it can show its alarm but not be opened from Home.
    if (locked) controllable.filterTo(suspend) { it !in neverRestrict && it != alarmApp && it !in lockUsableApps }
    // The Play Store follows its own rule, whatever the allowlist or the lock's usable apps say.
    suspend.remove(PLAY_STORE)
    val playSuspended = playStoreSuspended(appsManaged, locked, playState) || (locked && !playState.updateWindow)
    if (playSuspended && PLAY_STORE in controllable) suspend += PLAY_STORE
    val installModePin = playState.installMode && !locked && PLAY_STORE in controllable

    val kiosk = if (appsManaged && kioskDesired) {
        // During the lock: our package and the lock's usable apps, plus an allowlisted system
        // dialer while calls are unmanaged (then it is the in-call UI the parent allowed) unless
        // the rule allows no calls.
        val pinned = if (locked) {
            setOf(ownPackage) + allowed.filter { it in lockUsableApps } +
                allowed.filter { it == systemDialer && !callsBlockedByRule }
        } else {
            allowed + ownPackage
        }
        val withoutDialer = if (callState.managed && systemDialer != null) pinned - systemDialer else pinned
        // The block bit has no emergency exemption: the resolved system helpers (emergency
        // dialer, Telecom, permission dialogs, pickers, emergency alerts) are pinned with it - also
        // during a time-rule lock. The system dialer too (qa-09-code #1): it is the in-call UI of
        // emergency calls (and of every call while calls are unmanaged), which the block would
        // otherwise replace with the "app blocked" screen. Without the bit, AOSP's LockTaskController
        // let the dialer's package start in kiosk anyway (KEYGUARD is always set), so this adds
        // no reach; its calls stay screened by our redirection/in-call services or
        // DISALLOW_OUTGOING_CALLS ([restrictOutgoingCalls], unchanged). Never Settings or Play.
        // The recents provider is one of the helpers (design 16d): the gestures' RecentsActivity.
        val helpers = if (blockActivityStart) lockTaskHelpers + setOfNotNull(systemDialer) - PLAY_CORE else emptySet()
        (withoutDialer - PLAY_CORE) + helpers + setOfNotNull(PLAY_STORE.takeIf { installModePin })
    } else {
        null
    }
    return EnforcementPlan(
        suspend = suspend,
        hide = hide,
        kioskPackages = kiosk,
        recentsPin = recentsPackage?.takeIf { kiosk != null && it in kiosk },
        // As the server sent them: OVERVIEW stays (design 16d; Recents is pinned with the helpers).
        lockTaskFeatures = features,
        neverRestrict = neverRestrict,
        restrictOutgoingCalls = restrictOutgoingCalls,
        // Also while calls are managed: the callback window compares call-log times with the wall
        // clock (QA step 2 #4). Not lifted by an override then.
        lockDateTime = appsManaged || callState.managed || timeRulesSet,
        restrictSms = restrictSms,
        denyCallPermissions = callState.managed,
        restrictCreateWindows = budgetSet && !overrideActive,
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
