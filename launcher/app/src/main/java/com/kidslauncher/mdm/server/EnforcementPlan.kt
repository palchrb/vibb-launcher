package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.calls.smsMode
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

/** The calendar sync apps Dagen reads (design 69 §4.4): ICSx5 (subscribed feeds) and DAVx5 (CalDAV). A time rule's lock
 * never suspends them while they are allowed, so the school feed is current at school. */
val CALENDAR_SYNC_APPS = setOf("at.bitfire.icsdroid", "at.bitfire.davdroid")

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
    /** Design 105 §2.1: during a time rule with the kiosk on, the allowed clock apps the rule doesn't leave usable - out
     * of [kioskPackages], added back by [lockTaskWhileLocked] only while the PIN lock is LOCKED or the screen is dark.
     * Still spared from suspension, so their alarms ring. Empty otherwise (no rule, the budget alone, a window, the kiosk
     * off). */
    val ruleClockPins: Set<String> = emptySet(),
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
    /** `UserManager.DISALLOW_SMS` (design 77 D1, D15): SMS "Av", or "Styrt av Vibb" or the rules unknown while the SMS
     * role isn't ours - incoming messages are lost then, nobody is there to filter them. Never while we hold the role
     * in "Styrt av Vibb" (we filter) or with the rules unknown (the engine holds them, D15). Not lifted by an
     * override. */
    val restrictSms: Boolean,
    /** Design 77 §8.3: the other SMS apps are suspended and hidden (in [suspend]/[hide]) whenever SMS isn't
     * "Ikke styrt" - RCS chat bypasses [restrictSms] (QA #8), and in "Styrt av Vibb" only our engine may text. */
    val suspendSmsApps: Boolean = restrictSms,
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
    /** `UserManager.DISALLOW_CONFIG_TETHERING` while apps are managed and no window is open
     * (design 24b §1.5): setting it untethers, so a hotspot turned on in a window goes at the relock. */
    val restrictTethering: Boolean = false,
    /** `setPermittedAccessibilityServices` / `setPermittedInputMethods` (design 24b §1.5): the
     * allowlist while apps are managed (system services and keyboards are always permitted);
     * `null` (all) otherwise - also on calls-only phones (QA #4). Keyboards: only outside windows.
     * Accessibility services: also in windows (the user's decision on QA 24b High 1) - the one
     * block a window keeps, so a service that can read the parent PIN can't be turned on there;
     * ticking an app in the PWA allows its service. */
    val permittedAccessibility: List<String>? = null,
    val permittedInputMethods: List<String>? = null,
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
 *   [alarmApps] (the lock's clock apps - every system clock and a verified Fossify Clock, [sparedAlarmApps] - so
 *   alarms ring) is suspended - but not hidden -
 *   whatever the allowlist (also an unmanaged one - the lock comes from a server policy), and
 *   kiosk pins only our own package (which holds LockActivity, the phone book and the in-call UI)
 *   plus an allowlisted system dialer while calls are unmanaged, and (bug 2026-11-02) the allowed [alarmApps]: their
 *   ringing screen is a new task of their own, which lock task refuses for a package off the list - the alarm rang and
 *   Stop never came up. The kid can reach the clock app (alarms, timer, stopwatch) during the rule through it, as
 *   with the kiosk off (`pinLockHelpers`' clock app). Design 105 (the user withdrew that): during a time **rule**
 *   ([ruleLocked]) the allowed clocks the rule doesn't leave usable leave the kiosk list ([EnforcementPlan.ruleClockPins])
 *   and are pinned only while the PIN lock is LOCKED or the screen is dark ([lockTaskWhileLocked]) - no reach there,
 *   and the ringing screen still comes over the glance; unlocked and lit, lock task refuses every way into the clock
 *   and the list's change closes its task. The used-up budget alone (no rule, nothing to tick) keeps them pinned.
 * - [lockUsableApps]: while [scheduleLocked], the apps the lock leaves usable - a rule's exempt
 *   apps, or the contacts' messaging apps once the screen-time budget is used up (handy step 6,
 *   `decideTimeLock`). They are spared by the lock and pinned, but still only if the allowlist
 *   allows them. [ruleBlocksCalls]: the active rule allows no calls (school) - outgoing calls are
 *   then restricted (emergency calls are exempt) and the system dialer isn't pinned, whatever the
 *   call state; with calls managed the caller also passes the rule-restricted call state.
 * - [timeRulesSet]/[budgetSet]: the enforced time policy has any rule or budget / a budget (see
 *   [EnforcementPlan.lockDateTime] and [EnforcementPlan.restrictCreateWindows]).
 * - [inputMethods]: the keyboards never suspended or hidden - since design 24b only the enabled
 *   ones that are system or allowlisted ([keptInputMethods]): a keyboard sideloaded in a window is
 *   hidden at the relock. The parent PIN types on our own keypad, so it needs none.
 *   The call restrictions don't change: allowed calls and emergency calls keep working
 *   (qa-security P0 #3 - the overlay alone could be escaped through Recents or a notification).
 * - Play (handy step 7, [com.kidslauncher.mdm.play]): Play services and GSF are in
 *   [EnforcementPlan.neverRestrict] (other apps' FCM - Element X); the Play Store is never hidden
 *   but suspended while apps are managed or a lock is on, except in install mode ([playState], not
 *   during a lock) and the nightly update window (also during a lock - the screen is off). None
 *   of them is pinned in kiosk, whatever the allowlist - except the Play Store in install mode.
 * - The lock-task features are the server's (plus KEYGUARD and the block bit): design 16's drop of
 *   OVERVIEW with the block bit is reverted (16d, the user's rule: never remove a stock gesture
 *   without asking) - the recents provider is pinned with the other helpers instead
 *   ([HelperKind.RECENTS]).
 * - [recentsPackage]: the system's recents provider, a system app ([AppEnforcer.systemRecentsPackage]);
 *   in [EnforcementPlan.neverRestrict] (design 16d): never hidden or suspended by enforcement -
 *   not by the allowlist, SMS off or a time-rule lock - so quickstep and the gestures keep
 *   working on any phone, also one whose stock launcher has a launcher icon. AppFilter keeps it off
 *   Home and the drawer; only the update fence suspends it.
 * - [webViewProvider]: the system WebView's package (design 26c §6.3,
 *   [AppEnforcer.webViewProviderPackage]), in [EnforcementPlan.neverRestrict]: the music app plays
 *   Spotify through a WebView, and a suspended or hidden provider breaks every WebView on the phone
 *   (the launcher's own login screens too), so no allowlist, SMS rule or time-rule lock touches it.
 *   It has no launcher icon, so `controllablePackages` already leaves it out on most phones; this is
 *   the guard.
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
    alarmApps: Set<String> = emptySet(),
    inputMethods: Set<String> = emptySet(),
    lockUsableApps: Set<String> = emptySet(),
    ruleBlocksCalls: Boolean = false,
    timeRulesSet: Boolean = false,
    budgetSet: Boolean = false,
    playState: PlayState = PlayState(),
    blockActivityStart: Boolean = false,
    lockTaskHelpers: Set<String> = emptySet(),
    recentsPackage: String? = null,
    /** The Emergency SOS activity's package (bug 25b-H): never hidden or suspended. */
    emergencySos: String? = null,
    /** The system WebView's package (design 26c §6.3): never hidden or suspended. */
    webViewProvider: String? = null,
    /** Design 77 D2: the SMS channel's state (`null` = the rules are unknown); [callState] is the calls channel's. */
    smsMode: ChannelMode? = callState.smsMode,
    /** Design 77 D1: we hold the SMS role, so our engine receives every message. */
    ourSmsRoleActive: Boolean = false,
    /** Design 102 §4.1: Chrome's login role, set only once its lockdown is written and read back
     * (`BrowserConfig`): unhidden and unsuspended (a suspended app shows "app paused") though not allowed, pinned in
     * the kiosk (M11, M12), suspended by a time-rule lock like any app (D7), never in the accessibility or input
     * method lists and never among the lock's usable apps. A ticked Chrome is an allowed app and ignores the role. */
    loginBrowser: String? = null,
    /** Design 119 §4.2: a login window is open - only then, and in Play's update window ([playState], code QA M1), is
     * [loginBrowser]'s Chrome unhidden (it stays pinned while ready, and is never suspended for the role: a window
     * unhides it alone). */
    loginWindowOpen: Boolean = false,
    /** Design 105 §2.1: the lock in force has a time rule (`lock.rules.isNotEmpty()`), not only the used-up budget. */
    ruleLocked: Boolean = false,
): EnforcementPlan {
    val neverRestrict = setOfNotNull(ownPackage, systemDialer, recentsPackage, emergencySos, webViewProvider) + inputMethods + PLAY_NEVER_RESTRICT
    val features = lockTaskFeatures(serverLockTaskFeatures, blockActivityStart)
    val appsManaged = allowlist != null && !overrideActive
    val locked = scheduleLocked && !overrideActive
    val allowed = allowlist.orEmpty().toSet()
    val callsBlockedByRule = locked && ruleBlocksCalls

    val restrictSms = smsRestricted(smsMode, ourSmsRoleActive)
    val suspendSmsApps = smsMode != ChannelMode.UNMANAGED
    val restrictOutgoingCalls = callsBlockedByRule || when (callState) {
        CallPolicyState.Unmanaged -> appsManaged && systemDialer != null && systemDialer !in allowed
        CallPolicyState.UnknownFailClosed -> true
        is CallPolicyState.Managed -> !callState.rules.callsEnabled || !ourDialerActive
    }

    val roleApp = loginBrowser?.takeIf { appsManaged && it !in allowed }
    val hide = mutableSetOf<String>()
    // The Play Store is never hidden: other apps' FCM (Element X) needs it installed, and hiding
    // broadcasts a removal.
    if (appsManaged) controllable.filterTo(hide) { it !in allowed && it !in neverRestrict && it != PLAY_STORE && it != roleApp }
    if (suspendSmsApps) controllable.filterTo(hide) { it in smsPackages && it !in neverRestrict }
    val suspend = hide.toMutableSet()
    // Design 119 §4.2: the role's Chrome is hidden outside a login window - not suspended, so a window's unhide alone
    // brings it out; and out in Play's update window too (code QA M1), so Play keeps it updated.
    if (roleApp != null && com.kidslauncher.mdm.logins.loginChromeHidden(true, loginWindowOpen, playState.updateWindow) && roleApp in controllable) {
        hide += roleApp
    }
    // The alarm apps are spared by the lock only, so an alarm set inside bedtime still rings (an alarm app that isn't
    // allowed is hidden above, lock or not); the allowed ones are pinned during the lock too (below).
    // Design 69 §4.4: the calendar sync apps, while allowed, keep syncing through a lock (not neverRestrict, which
    // would also spare them the hide of an app that isn't allowed).
    if (locked) {
        controllable.filterTo(suspend) {
            it !in neverRestrict && it !in alarmApps && it !in lockUsableApps && !(it in CALENDAR_SYNC_APPS && it in allowed)
        }
    }
    // The Play Store follows its own rule, whatever the allowlist or the lock's usable apps say.
    suspend.remove(PLAY_STORE)
    val playSuspended = playStoreSuspended(appsManaged, locked, playState) || (locked && !playState.updateWindow)
    if (playSuspended && PLAY_STORE in controllable) suspend += PLAY_STORE
    val installModePin = playState.installMode && !locked && PLAY_STORE in controllable
    // Design 105 §2.1: during a rule the clocks a parent didn't tick leave the list; the chrome pins them while LOCKED or dark.
    val clocksOut = if (locked && ruleLocked) allowed.filter { it in alarmApps && it !in lockUsableApps }.toSet() else emptySet()

    val kiosk = if (appsManaged && kioskDesired) {
        // During the lock: our package and the lock's usable apps, plus an allowlisted system
        // dialer while calls are unmanaged (then it is the in-call UI the parent allowed) unless
        // the rule allows no calls, plus the allowed alarm apps (bug 2026-11-02): an unpinned clock's ringing
        // screen is refused by lock task, so the alarm rang with no way to Stop it.
        val pinned = if (locked) {
            setOf(ownPackage) + allowed.filter { it in lockUsableApps } +
                allowed.filter { it == systemDialer && !callsBlockedByRule } + allowed.filter { it in alarmApps && it !in clocksOut }
        } else {
            allowed + ownPackage + setOfNotNull(roleApp)
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
        // Bug 25b-H: the Emergency SOS activity is pinned with or without the block bit - without
        // it a non-pinned package's new task is a lock-task violation, and the five presses would
        // do nothing in kiosk (with it: "App is not available" over the lock).
        (withoutDialer - PLAY_CORE) + helpers + (setOfNotNull(emergencySos) - PLAY_CORE) + setOfNotNull(PLAY_STORE.takeIf { installModePin })
    } else {
        null
    }
    return EnforcementPlan(
        suspend = suspend,
        hide = hide,
        kioskPackages = kiosk,
        recentsPin = recentsPackage?.takeIf { kiosk != null && it in kiosk },
        // A clock the list still pins otherwise (a helper, say) is no pin of the rule's.
        ruleClockPins = if (kiosk == null) emptySet() else clocksOut - kiosk,
        // As the server sent them: OVERVIEW stays (design 16d; Recents is pinned with the helpers).
        lockTaskFeatures = features,
        neverRestrict = neverRestrict,
        restrictOutgoingCalls = restrictOutgoingCalls,
        // Also while calls are managed: the callback window compares call-log times with the wall
        // clock (QA step 2 #4). Not lifted by an override then.
        // Design 77 D16: the held messages' 24 hours run on the wall clock too.
        lockDateTime = appsManaged || callState.managed || smsMode != ChannelMode.UNMANAGED || timeRulesSet,
        restrictSms = restrictSms,
        suspendSmsApps = suspendSmsApps,
        denyCallPermissions = callState.managed,
        restrictCreateWindows = budgetSet && !overrideActive,
        restrictTethering = appsManaged,
        permittedAccessibility = allowed.sorted().takeIf { allowlist != null },
        permittedInputMethods = allowed.sorted().takeIf { appsManaged },
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
 * `DISALLOW_SMS` for the SMS channel's [mode] (`null` = the rules are unknown) - design 77 D1, D15: "Av" always;
 * "Styrt av Vibb" and unknown rules only while the role isn't ours ([roleHeld]) - nothing of ours would filter, and a
 * message that reaches no filter must not reach the kid. With the role ours the engine filters (managed) or holds
 * (unknown, D15). "Ikke styrt" never.
 */
fun smsRestricted(mode: ChannelMode?, roleHeld: Boolean): Boolean = when (mode) {
    ChannelMode.OFF -> true
    ChannelMode.MANAGED, null -> !roleHeld
    ChannelMode.UNMANAGED -> false
}

/**
 * Third-party apps whose call permissions are denied while calls are managed: every non-system
 * package except ours that requests one of [CALL_PERMISSIONS] ([requested]: non-system packages
 * only, mapped to their requested permissions). System apps are left alone - denying them is
 * risky, and the redirection/in-call services cover their calls.
 */
fun callPermissionTargets(requested: Map<String, Collection<String>>, ownPackage: String): Set<String> =
    requested.filter { (pkg, perms) -> pkg != ownPackage && perms.any { it in CALL_PERMISSIONS } }.keys

/** The permission [phoneStateGrants] grants (design 57 (d)). */
const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"

/**
 * Design 57 (d): while the call-permission denial is on ([denyCalls]) it greys the whole Phone group ("not allowed by
 * admin") of every [callPermissionTargets] app, so one that also asks for READ_PHONE_STATE (WhatsApp, to know a
 * carrier call is on) could never get it. Those apps get it by policy when they are allowed: in the effective
 * [allowlist], or every one while apps are unmanaged (`null`): [GRANT_STATE_GRANTED]. Every other app in [requested]
 * (third-party only) that requests READ_PHONE_STATE - an app updated out of the targets or taken off the allowlist
 * too - is [GRANT_STATE_DEFAULT]: the policy fix cleared without a revoke (a revoke kills the app, a WhatsApp call with
 * it), as the denials go back and as [shouldResetOwnPermissions] does. Our own package is never in it (its grant is
 * self-fixed). The override and the pause change nothing (call rules are never lifted): the caller passes the
 * allowlist, not the window's plan. Returns the wanted state per requester.
 */
fun phoneStateGrants(
    requested: Map<String, Collection<String>>,
    ownPackage: String,
    allowlist: Collection<String>?,
    denyCalls: Boolean,
): Map<String, Int> {
    val targets = if (denyCalls) callPermissionTargets(requested, ownPackage) else emptySet()
    return requested.filter { (pkg, perms) -> pkg != ownPackage && READ_PHONE_STATE in perms }
        .mapValues { (pkg, _) ->
            if (pkg in targets && (allowlist == null || pkg in allowlist)) GRANT_STATE_GRANTED else GRANT_STATE_DEFAULT
        }
}

/**
 * The keys the parent switches off per app on its page (design 127 §3.1; design 68 for the camera), each with the
 * runtime permissions it covers - an app asks for the key when it requests any of them, and each one it requests is
 * denied. In display order; the shared contract `app_access.json`'s `keys` (AppAccessContractTest). `network` (Nett)
 * joins in step 3. `GET_ACCOUNTS` stays out: since Android 8 it shows only the app's own accounts.
 */
enum class AccessKey(val key: String, val permissions: List<String>) {
    CAMERA("camera", listOf("android.permission.CAMERA")),
    LOCATION(
        "location",
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        ),
    ),
    MICROPHONE("microphone", listOf("android.permission.RECORD_AUDIO")),
    CONTACTS("contacts", listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS")),
    ;

    companion object {
        fun of(key: String): AccessKey? = entries.firstOrNull { it.key == key }

        /** The key a runtime [permission] belongs to, `null` for none of them. */
        fun ofPermission(permission: String): AccessKey? = entries.firstOrNull { permission in it.permissions }
    }
}

/** The packages no [AccessKey] is ever applied or reported for (design 127 §3.3 `never`): Vibb One and Vibb music, in
 * either build - our own package is left out besides - but only when signed with our own certificate. */
val ACCESS_NEVER = setOf("me.vibb.launcher", "me.vibb.launcher.debug", "me.vibb.music", "me.vibb.music.debug")

/** Whether [pkg] (a third-party app: the caller's filter) gets the parent's [AccessKey]s: never ours, nor an
 * [ACCESS_NEVER] package signed with our certificate ([sameSigner], `MusicTrust.sameSigner`). */
fun accessTarget(pkg: String, ownPackage: String, sameSigner: (String) -> Boolean): Boolean =
    pkg != ownPackage && !(pkg in ACCESS_NEVER && sameSigner(pkg))

/** An [ACCESS_NEVER] name with another signer: it gets every [AccessKey] denied, whatever the policy says. */
private fun otherSigner(pkg: String): Boolean = pkg in ACCESS_NEVER

/** One runtime permission of one package. */
data class Grant(val packageName: String, val permission: String)

/** One [AccessKey] of one package (the report's `refused`, [AppEnforcer.accessRefused]). */
data class AppKey(val packageName: String, val key: AccessKey)

/** Every (package, permission) [accessGrants] works on, with its key: each [accessTarget] in [requested] (third-party
 * only, mapped to their requested permissions) and each of its requested permissions an [AccessKey] covers. */
fun accessTargets(
    requested: Map<String, Collection<String>>,
    ownPackage: String,
    sameSigner: (String) -> Boolean,
): Map<Grant, AccessKey> {
    val out = LinkedHashMap<Grant, AccessKey>()
    for ((pkg, perms) in requested) {
        if (!accessTarget(pkg, ownPackage, sameSigner)) continue
        for (key in AccessKey.entries) for (permission in key.permissions) {
            if (permission in perms) out[Grant(pkg, permission)] = key
        }
    }
    return out
}

/** [accessGrants]' answer: the grant states to write (only where they differ), and the denials that wait for a call's
 * end ([waiting]). */
data class AccessPlan(val writes: Map<Grant, Int>, val waiting: Set<Grant>) {
    val deferred: Boolean get() = waiting.isNotEmpty()
}

/**
 * Design 127 §4.1 (design 68 §3.2 widened): the parent's Kamera, Posisjon, Mikrofon and Kontakter off per app. Targets:
 * [accessTargets]. Each is [GRANT_STATE_DENIED] when its package's key is in [denied] and no window is open
 * ([windowOpen]: the pause or the override lift it, the relock's apply denies again), else [GRANT_STATE_DEFAULT] - the
 * policy fix cleared, the kid's dialog decides; **we never GRANT**. Revoking a granted permission kills the app, so
 * while [callLive] a target going to DENIED from another state that the app holds now ([held]) is left out and
 * [AccessPlan.waiting]; one it doesn't hold is denied at once. [denied] `null` (no `app_permissions`, a `Fallback`)
 * writes nothing, except in a window: every target reading DENIED goes to DEFAULT (we are the only admin, so a
 * `Fallback` phone's pause still lifts it). [current] is each target's grant state now; writes only where it differs
 * ([grantWrite]). An [ACCESS_NEVER] name another key signed ([sameSigner] false) is denied every key it asks for,
 * policy or none, until a window opens.
 */
fun accessGrants(
    requested: Map<String, Collection<String>>,
    ownPackage: String,
    denied: Map<String, Set<AccessKey>>?,
    windowOpen: Boolean,
    callLive: Boolean,
    held: Set<Grant>,
    current: Map<Grant, Int>,
    sameSigner: (String) -> Boolean,
): AccessPlan {
    val writes = LinkedHashMap<Grant, Int>()
    val waiting = LinkedHashSet<Grant>()
    for ((grant, key) in accessTargets(requested, ownPackage, sameSigner)) {
        val now = current[grant] ?: GRANT_STATE_DEFAULT
        val fromOtherSigner = otherSigner(grant.packageName)
        val wanted = when {
            denied == null && !fromOtherSigner ->
                if (windowOpen && now == GRANT_STATE_DENIED) GRANT_STATE_DEFAULT else continue
            (fromOtherSigner || key in denied?.get(grant.packageName).orEmpty()) && !windowOpen -> GRANT_STATE_DENIED
            else -> GRANT_STATE_DEFAULT
        }
        if (wanted == GRANT_STATE_DENIED && now != GRANT_STATE_DENIED && callLive && grant in held) {
            waiting += grant
            continue
        }
        grantWrite(now, wanted)?.let { writes[grant] = it }
    }
    return AccessPlan(writes, waiting)
}

/**
 * Design 127 §3.2's `refused` after a pass: every (package, key) the pass worked on ([evaluated]) and didn't leave
 * waiting ([waiting]) is refused exactly when one of its DENIED writes failed ([failed]: `setPermissionGrantState`'s
 * `false` or a throw); the rest keep [before]. So a pass that succeeds - or the parent's on - clears it.
 */
fun accessRefusedAfter(before: Set<AppKey>, evaluated: Set<AppKey>, waiting: Set<AppKey>, failed: Set<AppKey>): Set<AppKey> =
    before.filterTo(LinkedHashSet()) { it !in evaluated || it in waiting } + failed

/** Design 68 §3.2: the call-end wake applies again only when a denial waits ([deferred]) and no call is live now -
 * [callLive] is read only then (a Telecom binder call). */
fun deferredAccessApplyDue(deferred: Boolean, callLive: () -> Boolean): Boolean = deferred && !callLive()

/**
 * The policy's `app_permissions` (design 127 §3.2), read field by field: the keys the parent switched off per package
 * on this phone (`[{package_name, camera?, location?, microphone?, contacts?}]`, each `"deny"`). An entry without a
 * valid package or without a known key at `"deny"` is dropped; any other value is the default. `null` - the key
 * missing or not a list (a `Fallback`'s plan) - means "touch nothing" ([accessGrants]).
 */
fun appPermissionsOf(element: kotlinx.serialization.json.JsonElement?): Map<String, Set<AccessKey>>? {
    val list = element as? kotlinx.serialization.json.JsonArray ?: return null
    val out = LinkedHashMap<String, MutableSet<AccessKey>>()
    for (item in list) {
        val entry = item as? kotlinx.serialization.json.JsonObject ?: continue
        fun string(key: String) = (entry[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val pkg = string("package_name")?.takeIf { com.kidslauncher.mdm.appdisplay.isValidPackageName(it) } ?: continue
        val keys = AccessKey.entries.filter { string(it.key) == "deny" }
        if (keys.isNotEmpty()) out.getOrPut(pkg) { LinkedHashSet() } += keys
    }
    return out
}

/**
 * Security review S-L3 (with code QA L2): the alarm apps the schedule lock spares (and, while allowed, pins in kiosk) -
 * the lock's clock apps ([com.kidslauncher.mdm.lock.lockClockPackages]): every [systemClocks] app (each system
 * `SHOW_ALARMS` handler) and Fossify Clock while [clockAppVerified] (installed with its pinned signer). Since bug
 * 2026-11-02 the verified Fossify Clock always, as `pinLockHelpers`' clock app - no longer only while it held the next
 * alarm or resolved `SHOW_ALARMS`, so its alarm queued behind a system clock's was suspended. An allowlisted app that
 * calls `setAlarmClock` is never spared by it.
 */
fun sparedAlarmApps(systemClocks: Set<String>, clockAppVerified: Boolean): Set<String> =
    com.kidslauncher.mdm.lock.lockClockPackages(systemClocks, clockAppVerified)

/** Security review S-L5: `DISALLOW_BLUETOOTH_SHARING` while the phone is [managed] (hardening's sense), lifted by the
 * override and the pause ([overrideActive]) - Bluetooth headphones (design 52) are unaffected. */
fun bluetoothSharingRestricted(managed: Boolean, overrideActive: Boolean): Boolean = managed && !overrideActive
