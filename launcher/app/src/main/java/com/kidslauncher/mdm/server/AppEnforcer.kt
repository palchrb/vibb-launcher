package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.telecom.TelecomManager
import android.util.Log
import com.kidslauncher.mdm.ui.wallpaper.ClearStep
import com.kidslauncher.mdm.ui.wallpaper.WallpaperApplier
import com.kidslauncher.mdm.ui.wallpaper.hardeningClearSteps
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallPrefs
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.CALL_LOG_TYPE
import com.kidslauncher.mdm.calls.EmergencyDialer
import com.kidslauncher.mdm.calls.MissedCallNotifier
import com.kidslauncher.mdm.calls.PhoneBookEntryActivity
import com.kidslauncher.mdm.calls.RoleAction
import com.kidslauncher.mdm.calls.dialerRoleAction
import com.kidslauncher.mdm.calls.roleReportNeeded
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.forCalls
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.sms.DENY_PLAY_SERVICES_SMS
import com.kidslauncher.mdm.sms.PLAY_SERVICES
import com.kidslauncher.mdm.sms.SmsPrefs
import com.kidslauncher.mdm.sms.SmsRuntime
import com.kidslauncher.mdm.sms.lockDefaultAppsFor
import com.kidslauncher.mdm.sms.smsPermissionPass
import com.kidslauncher.mdm.sms.smsPermissionTargets
import com.kidslauncher.mdm.sms.smsPermissionsDenied
import com.kidslauncher.mdm.sms.smsRoleAction
import com.kidslauncher.mdm.sms.smsRoleReturnTo
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.calls.withTimeRule
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.hasBudget
import com.kidslauncher.mdm.timerules.key
import com.kidslauncher.mdm.ui.HomeActivity
import com.kidslauncher.mdm.play.PLAY_STORE
import com.kidslauncher.mdm.play.PlayLinkBlockedActivity
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.play.PlayState

private const val LOG_TAG = "AppEnforcer"

/** Design 114 §7.1: always self-granted while device owner ([AppEnforcer.apply]); never background location. */
val OWN_LOCATION_PERMISSIONS = listOf(
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Self-granted while calls are managed - see [AppEnforcer.applyCallPermissions]. */
private val OWN_CALL_PERMISSIONS = listOf(
    android.Manifest.permission.READ_CONTACTS,
    android.Manifest.permission.CALL_PHONE,
    android.Manifest.permission.READ_PHONE_STATE,
    android.Manifest.permission.READ_CALL_LOG,
    // Only to delete blocked calls after 30 days (calls.BlockedCallLog, cleanup 2026-10-06).
    android.Manifest.permission.WRITE_CALL_LOG,
)

/** `Telephony.Sms.Intents.ACTION_SMS_EMERGENCY_CB_RECEIVED` (system API). */
private const val ACTION_SMS_EMERGENCY_CB_RECEIVED = "android.provider.action.SMS_EMERGENCY_CB_RECEIVED"
/** `TelecomManager.ACTION_MANAGE_BLOCKED_NUMBERS` (hidden; `createManageBlockedNumbersIntent` is the public way in). */
private const val MANAGE_BLOCKED_NUMBERS = "android.telecom.action.MANAGE_BLOCKED_NUMBERS"

/**
 * The set of packages [AppEnforcer] will ever consider suspending/hiding, and that
 * [com.kidslauncher.mdm.server.MdmSyncWorker]'s status report offers the admin site as
 * allow/restrict checkboxes - the two must stay in sync, or the admin could check a box for an
 * app this loop then silently never acts on.
 *
 * A direct [PackageManager] query, not the launcher's own `Application.apps` (LauncherApps-based)
 * list: that excludes apps already hidden via [android.app.admin.DevicePolicyManager.setApplicationHidden],
 * so relying on it here would mean a hidden app could never be found again to un-hide it - a
 * permanent one-way lock. But a raw, unfiltered [PackageManager.getInstalledApplications] is
 * actively dangerous: it includes core OS/system packages that must never be suspended (doing so
 * can crash or boot-loop the device - this is not hypothetical, it happened during development).
 * So: third-party (non-system) apps are always included, and system apps only if they expose a
 * launcher (home-screen) icon - see the function's own doc comment for why this replaced an
 * earlier hand-maintained package-name allowlist.
 */
/** [packageName] is installed for this user, hidden or not (`MATCH_UNINSTALLED_PACKAGES`, as [controllablePackages]). */
internal fun installedEvenHidden(pm: PackageManager, packageName: String): Boolean = try {
    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

internal fun controllablePackages(pm: PackageManager): List<String> {
    // MATCH_UNINSTALLED_PACKAGES is required here, or this list silently drops any package this
    // same enforcer has already hidden via setApplicationHidden - PackageManager excludes hidden
    // packages from getInstalledApplications() by default. Without this flag, a hidden app can
    // never be found again to un-hide it: a permanent one-way lock, and exactly the bug this
    // function was written to avoid in the first place (see the class-level doc above).
    //
    // System apps are included only if they expose a launcher (home-screen) icon - a real,
    // user-facing app a kid could actually open - rather than via a hand-maintained package-name
    // allowlist. The allowlist approach (this file's history) worked for the GrapheneOS test
    // device but left a GMS/OEM device (Chrome, Play Store, Gmail, YouTube, Maps, the
    // manufacturer's own Camera/Gallery/Messages, ...) with almost nothing controllable, since
    // none of those package names were in the list. A launcher-intent check is device-agnostic:
    // it naturally includes exactly the apps a kid can tap open, and naturally excludes headless
    // system services/components (SystemUI, telephony internals, resource overlays, ...) since
    // those don't expose a launcher activity in the first place - without needing to enumerate
    // every OEM's package names by hand. MATCH_UNINSTALLED_PACKAGES on this query too, for the
    // same already-hidden-app reason as above.
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchablePackages = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()

    return pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .filter { info ->
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                info.packageName in launchablePackages
        }
        .map { it.packageName }
        .distinct()
}

/**
 * `TelecomManager.getSystemDialerPackage()` - the preloaded dialer, which is the in-call UI for
 * emergency calls. [computeEnforcementPlan] never suspends or hides it. `null` if the platform
 * doesn't say (then nothing extra is exempted).
 */
internal fun systemDialerPackage(context: Context): String? =
    try {
        context.getSystemService(TelecomManager::class.java)?.systemDialerPackage
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't look up the system dialer package", e)
        null
    }

/**
 * The system's Recents activity (`config_recentsComponentName` read through `Resources.getSystem()`,
 * e.g. Pixel's quickstep RecentsActivity inside the stock launcher). `null` when the platform
 * doesn't say. Design 16d trusts its package only through [AppEnforcer.systemRecentsPackage].
 */
internal fun recentsComponent(): ComponentName? = try {
    val res = android.content.res.Resources.getSystem()
    val id = res.getIdentifier("config_recentsComponentName", "string", "android")
    if (id == 0) null else ComponentName.unflattenFromString(res.getString(id))
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read the recents component", e)
    null
}

/**
 * The package of [recentsComponent], untrusted - only AppFilter uses it, to keep whatever it names
 * off Home and the drawer (leaving out more is safe). Everything else takes
 * [AppEnforcer.systemRecentsPackage].
 */
internal fun recentsPackage(): String? = recentsComponent()?.packageName

/**
 * The Emergency SOS activity's package (bug 25b-H, [HelperKind.EMERGENCY_SOS]): the system handler
 * of [ACTION_LAUNCH_EMERGENCY] SystemUI picks ([emergencySosHandler]). Never
 * hidden or suspended by enforcement (an SOS app with a launcher icon - Personal Safety - would
 * otherwise be hidden and the five presses would do nothing) and never on the kid's app list.
 */
internal fun emergencySosPackage(context: Context): String? = try {
    val pm = context.packageManager
    fun handlers(intent: Intent): List<String> = pm.queryIntentActivities(
        intent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
    ).mapNotNull { it.activityInfo?.packageName }
    // Never Settings or the camera (an OEM that handles SOS there): like every pinned helper.
    val forbidden = handlers(Intent(Settings.ACTION_SETTINGS)).take(1) +
        handlers(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)).take(1) +
        handlers(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)).take(1)
    emergencySosHandler(handlers(Intent(ACTION_LAUNCH_EMERGENCY)), forbidden.toSet(), preferredEmergencySosPackage(pm))
} catch (e: Exception) {
    null
}

/** SystemUI's `config_preferredEmergencySosPackage` (empty on AOSP, set by an OEM overlay), if readable. */
private fun preferredEmergencySosPackage(pm: PackageManager): String? = try {
    val res = pm.getResourcesForApplication("com.android.systemui")
    @Suppress("DiscouragedApi") // Another package's resource: only by name.
    val id = res.getIdentifier("config_preferredEmergencySosPackage", "string", "com.android.systemui")
    if (id == 0) null else res.getString(id).takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** The last [alarmAppPackages] logged. */
@Volatile
private var loggedAlarmApps: Set<String>? = null

/**
 * The alarm/clock apps the schedule lock spares, so an alarm set inside bedtime still rings (QA step 4 #2), and pins
 * while allowed (bug 2026-11-02): the lock's clock apps - every system clock app and Fossify Clock with its pinned
 * signer ([sparedAlarmApps]; since security review S-L3 never the next alarm's creator as such: any allowlisted app may
 * call `setAlarmClock`, it is not spared for it). Empty if there's none.
 */
internal fun alarmAppPackages(context: Context): Set<String> = try {
    sparedAlarmApps(com.kidslauncher.mdm.lock.ClockApp.systemClocks(context), com.kidslauncher.mdm.lock.ClockApp.installed(context))
        .also { apps ->
            // Code QA L5: the set (every system SHOW_ALARMS handler) is usable during a rule once allowed - logged when
            // it changes, so a device shows what an OEM puts in it (package names of system apps only).
            if (apps != loggedAlarmApps) {
                loggedAlarmApps = apps
                Log.i(LOG_TAG, "Alarm apps (spared by a rule, pinned while allowed): ${apps.sorted()}")
            }
        }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't look up the alarm app", e)
    emptySet()
}

/**
 * The default and enabled keyboards - never suspended or hidden, or the PIN dialogs couldn't take
 * input (QA step 4 #3).
 */
internal fun inputMethodPackages(context: Context): Set<String> = try {
    val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let { ComponentName.unflattenFromString(it)?.packageName }
    val enabled = context.getSystemService(InputMethodManager::class.java)
        ?.enabledInputMethodList.orEmpty().map { it.packageName }
    (enabled + listOfNotNull(default)).toSet()
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't look up the input methods", e)
    emptySet()
}

/**
 * The enabled and default keyboards, and which of them are system apps - for the keyboard rule
 * of design 24b §1.5 ([keptInputMethods]). Empty on an error (then none is kept on purpose; the
 * parent PIN has its own keypad).
 */
internal fun inputMethodsWithSystem(context: Context): Pair<Set<String>, Set<String>> = try {
    val pm = context.packageManager
    val enabled = inputMethodPackages(context)
    val system = enabled.filterTo(mutableSetOf()) { pkg ->
        try {
            (pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }
    enabled to system
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't look up the input methods", e)
    emptySet<String>() to emptySet()
}

/**
 * Suspends and hides installed apps that aren't on [PolicyResponse.allowlist] (`null` means
 * unmanaged: nothing suspended; `[]` means nothing allowed), and - only when the server says so
 * via [PolicyResponse.kioskDesired] - pins the device to the allowed packages via Android's
 * Device Owner lock-task API. The decisions themselves are [computeEnforcementPlan] (pure,
 * unit-tested); this object applies them. Only acts when this app is device owner - a no-op
 * otherwise, so it's safe to ship before the phone is actually re-provisioned.
 *
 * Callers decide *which* policy to pass with [choosePolicy]: `apply(null)` means "fully open"
 * and is only right for a phone that has never had a policy, or while an override is active.
 */
object AppEnforcer {

    /**
     * The time-rule lock the last completed [apply] enforced ([com.kidslauncher.mdm.timerules.key];
     * `null` until one ran in this process). [reevaluateLockReasonFromCache] re-applies whenever
     * this disagrees with the current lock, so a failed or interrupted apply at a boundary is
     * retried on the next re-check instead of waiting for the sync (QA step 4 #10).
     */
    @Volatile
    var lastEnforcedLockKey: String? = null
        private set

    /** The [PlayState] the last completed [apply] enforced (install mode, update window) - a
     * re-check re-applies when it changes, like [lastEnforcedLockKey]. */
    @Volatile
    var lastEnforcedPlayState: PlayState? = null
        private set

    /** Whether the last completed [apply] enforced an open window's plan (the pause or the override: Android's
     * Settings unhidden, no kiosk); `null` until one ran in this process. Settings' system-page rows read it, so
     * "no page" is said only once the window's plan is in place (code QA Q-2). */
    @Volatile
    var lastEnforcedOverride: Boolean? = null
        private set

    /** Design 119 code QA M1: the last completed [apply] left the login role's Chrome unhidden for Play's update window -
     * the screen-on hides it at once ([PlayRuntime.suspendStoreAtScreenOn]), before the re-check's apply. */
    @Volatile
    var lastChromeUpdateUnhidden: Boolean = false
        private set

    private val appliedListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Called on the main thread after each [apply] that got as far as enforcing its plan. */
    fun addAppliedListener(listener: () -> Unit) {
        appliedListeners += listener
    }

    fun removeAppliedListener(listener: () -> Unit) {
        appliedListeners -= listener
    }

    /** The packages the last [apply] in this process wanted suspended (`null` = none ran yet) -
     * the PIN lock's camera release never lifts one of these (CameraLock). */
    @Volatile
    var lastPlanSuspend: Set<String>? = null
        private set

    /** The packages the last [apply] in this process wanted hidden (`null` = none ran yet): with [lastPlanSuspend] what
     * Home, the drawer and the system-page rows leave out, whatever the platform did ([PlatformRefusals]). */
    @Volatile
    var lastPlanHide: Set<String>? = null
        private set

    /** The last [apply]'s never-restrict set (`null` = none ran yet): what map links' step 2 never suspends. */
    @Volatile
    var lastNeverRestrict: Set<String>? = null
        private set

    /** The last [apply] set its permitted accessibility and keyboard lists (design 24b §1.5): AOSP
     * refuses a list that leaves out an enabled non-system service, and the last list stays. */
    @Volatile
    var permittedListsOk: Boolean = true
        private set

    /**
     * Synchronized: the sync, the pause switch, the offline override and the schedule re-check
     * ([reevaluateLockReasonFromCache]) can all call this from different threads, and two passes
     * interleaving their suspend/unsuspend loops could leave a mix of both. Never call it on the
     * main thread (it can wait for a running pass, and starting the VPN reads files).
     */
    @Synchronized
    fun apply(context: Context, decision: PolicyToApply, fromSync: Boolean = false) =
        apply(context, decision.policy, fromSync, parentChoice = decision.carriesParentChoice, fromServer = rollbackAllowed(decision.source))

    /** [parentChoice] (design 59 §3.8, L-1): [policy] is a fresh or `Ok` cached one ([PolicyToApply.carriesParentChoice]).
     * [fromServer]: the server sent it in this sync ([rollbackAllowed]) - only then may Android's lock be rolled back. A
     * bare policy is neither. */
    @Synchronized
    fun apply(context: Context, policy: PolicyResponse?, fromSync: Boolean = false, parentChoice: Boolean = false, fromServer: Boolean = false) {
        // The app list loads once when this pass ends, not once per package it hides or suspends (the provisioning
        // storm's follow-up, `ui/home/AppLoads.kt`'s `appLoadHeld`; at most 10 s if a pass hangs).
        com.kidslauncher.mdm.ui.home.AppLoadHold.hold(android.os.SystemClock.elapsedRealtime())
        try {
            applyHeld(context, policy, fromSync, parentChoice, fromServer)
        } finally {
            com.kidslauncher.mdm.ui.home.AppLoadHold.release(android.os.SystemClock.elapsedRealtime())
        }
    }

    /** [apply]'s pass, under its lock and its hold of the app-list loads. */
    private fun applyHeld(context: Context, policy: PolicyResponse?, fromSync: Boolean, parentChoice: Boolean, fromServer: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

        // While a locally-entered offline override is active, or a parent has turned on the
        // PIN-gated "pause all restrictions" switch in Settings, everything is released - the
        // plan below treats an active override like "no policy", and so does every restriction
        // further down.
        val overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()

        enforceDefaultHome(dpm, admin, context)
        // Design 114 §7.1: Vibb's own fine and coarse location, always, whatever Locate says (the Wi-Fi page's scan and
        // the sign-in window's network name need them; Off still never asks LocationManager for a fix). Background
        // location keeps following Locate and the zones. Written only while not granted; in a window too.
        for (permission in OWN_LOCATION_PERMISSIONS) QuickControls.selfGrantPermission(context, dpm, admin, permission)
        // Never lifted (QA #2) - the blocker passes links on to Play whenever Play is open.
        enforcePlayLinkBlocker(dpm, admin, context)
        // The call log opens the phone book (design 12), never lifted either.
        enforceCallLogPin(dpm, admin, context)
        // Design 102 §3.2: Android's Wi-Fi sign-in goes to Vibb's entry, never to the stock login app. Off is the disabled
        // entry (DONT_KILL_APP, PortalRuntime.setEntryEnabled), not a cleared preferred activity: a disabled component's
        // persistent preferred activity is skipped, and Android's own handler is then the only one.
        enforceCaptivePortalPin(dpm, admin, context)
        com.kidslauncher.mdm.portal.PortalRuntime.setEntryEnabled(context, com.kidslauncher.mdm.logins.portalPolicyOf(policy).enabled)
        // Design 108 §5.2 (L3), the same pattern: `unifiedpush://link` always goes to Vibb's Link Activity, whatever UP's
        // state; whether it is used is the component's enabled state, which follows the policy's `unified_push.on` with
        // the registration receiver's (disabled: no app sees Vibb One as a distributor). Not a restriction: never lifted.
        // Code QA M3: switched only by a policy carrying the parent's choice (fresh, or a cache that decodes); a fallback
        // plan or a bare policy leaves them as they are - a disabled receiver makes the connector drop Vibb One, and each
        // Element X account would need its manual switch again.
        enforceUpLinkPin(dpm, admin, context)
        if (parentChoice) {
            com.kidslauncher.mdm.push.up.UpRuntime.setComponentsEnabled(context, com.kidslauncher.mdm.push.up.UpView.of(policy).on)
        }
        // Design 110 §6.2, the same pattern: the map links go to Vibb's handler whatever the switch (a PPA, so no chooser
        // once a browser is there too); whether it is used is the handler's enabled state, switched only by a policy
        // carrying the parent's choice. Not a restriction: never lifted.
        enforceMapLinkPin(dpm, admin, context)
        if (parentChoice) {
            com.kidslauncher.mdm.map.MapLinksRuntime.setEnabled(context, com.kidslauncher.mdm.map.mapLinksOn(policy))
        }
        // Design 119 §4.1 (L4): the link gate's catch-all for http and https, after the Play link blocker's and the map
        // handler's (insertion order among equal priorities). Its use is the gate's enabled state (after the loop).
        enforceLinkGatePin(dpm, admin, context)
        // Android's Blocked numbers page goes to Vibb's gate, the same pattern: its use is the gate's enabled state
        // (BlockedNumbersRuntime: on while managed). Never lifted, as the call rules.
        enforceBlockedNumbersPin(dpm, admin, context)

        // Hardening that the server switched off is cleared first, before anything below can
        // throw - "Block USB debugging" off is how a parent gets adb back (QA step 4 #8). The
        // restrictions that stay on are (re)set at the end, after the always-on VPN.
        CallPolicyStore.ensureLoaded(context)
        val managedForHardening = hardeningManaged(policy?.allowlist, CallPolicyStore.state.managed)
        // The VPN's shape (design 95 rev. 3): the filter, Bare Vibb's sink on cellular (the parent's choice, or a roaming
        // session that outlived the roaming lock), the pass shape that stands ready for it - all lifted by the override
        // and the pause. Only true-by-default (filtering on) with no policy yet, as before. Design 97: "I utlandet"'s
        // abroad sink (lifted too) and the kid's own Off (no lift, D4).
        val vpnFilterEnabled = if (overrideActive) false else (policy?.vpnFilterEnabled ?: true)
        val abroad = abroadChoice(policy)
        val vpnInputs = VpnInputs(
            filterOn = vpnFilterEnabled,
            parentSink = parentSinkOn(policy?.appsMobile, overrideActive),
            roamingLocked = roamingLocked(abroad, overrideActive),
            abroadArmed = abroadArmed(abroad, overrideActive),
            kidOff = MobileNetworkRuntime.kidOff(policy?.quickControlsMask),
        )
        val bestNetNow = MobileNetworkRuntime.bestNet(context)
        val vpnShapeNow = applyShape(vpnInputs, bestNetNow, MobileNetworkRuntime.storedShape())
        MobileNetworkRuntime.holdSinkReason(vpnInputs, bestNetNow, vpnShapeNow)
        // Security review S-L11: DISALLOW_CONFIG_VPN also while the Web filter is on (a filter-only phone) - lifted
        // with the filter by the override and the pause, as the filter is. Design 95 §2.2: and with the sink and the
        // pass shape, lifted with them.
        val hardening = withFilterVpnLock(
            hardeningPlan(policy?.hardening, managedForHardening), policy?.hardening,
            filterOn = policy != null && vpnConfigLocked(vpnShapeNow),
        )
        clearHardening(context, dpm, admin, hardening)
        // Design 64 step 2: an unmanaged phone (un-enrolled from the server's side, or never
        // managed) keeps no zone fence, passive feed, motion series or queued fix. Never throws.
        if (!managedForHardening) com.kidslauncher.mdm.zones.ZoneWatch.release(context)
        // Android's backup to Google stays off while managed (google-account runbook) - early, so
        // nothing that throws below can skip it. Like the hardening, never lifted by the override
        // or the pause, and nothing ever switches it on. Catches and logs everything itself.
        BackupService.enforce(dpm, admin, managedForHardening)
        // Dagen (design 69 §2.4): Vibb One reads (and writes the kid's own events into) the calendar provider.
        if (managedForHardening) com.kidslauncher.mdm.calendar.CalendarStore.grantPermissions(context, dpm, admin)

        // Calls: never lifted by an override or pause. The dialer role first - whether our dialer
        // is in place decides the outgoing-call restriction below.
        CallPolicyStore.ensureLoaded(context)
        // Design 77 D2: [callState] is the whole policy (managed while either channel is); the calls channel alone
        // decides the dialer role and the call restrictions, the SMS channel the SMS role and DISALLOW_SMS.
        val callState = CallPolicyStore.state
        val callsState = callState.forCalls()
        val smsMode = callState.smsMode
        // Design 119 §4.1 (D2): the link gate while managed and no free browser is allowed - not a restriction, so a pause
        // leaves it on; its DISALLOW_CONFIG_DEFAULT_APPS (M5) is one, lifted by the pause and the override (D4).
        val gateOn = com.kidslauncher.mdm.logins.LinkGateRuntime.gateWanted(
            context, effectiveAllowlist(policy?.allowlist, WindowRelock.blockedPackages(context)),
        )
        val gateLock = gateOn && !overrideActive
        applyDialerRole(context, dpm, admin, callsState, smsMode, gateLock)
        // D1: the SMS role is taken before the plan (with it ours, DISALLOW_SMS stays off); handed back after the
        // package loop, once the previous holder is unhidden (design 09 A6).
        applySmsRole(context, dpm, admin, callsState, smsMode, gateLock, allowRelease = false)
        // Inside a sync the status report right after this tells the server anyway (qa-09-code #10).
        if (!fromSync) signalRoleChange(context)
        // Design 98 §3.6: the assistant role read on every pass; a change asks for one report.
        if (!fromSync) com.kidslauncher.mdm.assistant.AssistantSetup.roleMaybeChanged(context)

        // A time rule or the used-up budget: suspend everything but our own package, the system
        // dialer and the lock's usable apps (see computeEnforcementPlan), from the same decision
        // the lock screen uses. A rule without calls (school) also turns managed calls off.
        val lockDecidedAt = android.os.SystemClock.elapsedRealtime()
        val lock = TimeRulesRuntime.currentLock(context, policy, overrideActive)
        val scheduleLocked = lock.locked
        // A silent rule's Do Not Disturb (server 0121) from the same lock: off under the override, the pause and a lift.
        com.kidslauncher.mdm.timerules.ZenRuleRuntime.follow(context, lock, lockDecidedAt)
        // The user, 2026-11-20: a pause, the override or a lift that allows calls again releases what waited for the rule.
        com.kidslauncher.mdm.timerules.RuleWaitingNotices.release(context)
        val timePolicy = KidModeEnforcer.timePolicyOf(policy)

        // The update fence (step 11): the one release rule runs on every pass, and while the fence
        // is up the packages it holds are never unsuspended here (qa-11-design.md #5).
        val fenceHeld = UpdateFence.duringApply(context, policy, managed = policy?.allowlist != null || callState.managed)

        val ownPackage = context.packageName
        val pm = context.packageManager
        val installedPackages = controllablePackages(pm)
        // Design 24b §1.6: apps installed or re-signed in a parent-PIN window that failed the
        // relock's signer check count as not allowlisted - hidden, never pinned, not in the lists.
        val allowlist = effectiveAllowlist(policy?.allowlist, WindowRelock.blockedPackages(context))
        val (enabledImes, systemImes) = inputMethodsWithSystem(context)
        var playState = PlayRuntime.state(context)
        if (playState.installMode && scheduleLocked) {
            // A time rule or the used-up budget began during install mode: it ends (QA #3).
            PlayRuntime.cancelInstallMode(context)
            playState = playState.copy(installMode = false)
        }
        // Cached for the lock's SOS yield (qa-25b-25f L3: never resolved at a lock stop).
        val sos = emergencySosPackage(context)
        com.kidslauncher.mdm.lock.PinLockRuntime.sosPackage = sos
        // Design 102 §4.1 (Q102-M3): the browsers' managed config before the plan and the hide/unhide loop - Chrome's login
        // role is unhidden and pinned only once its lockdown is written and read back; the pause and the override lift
        // the lockdown (D9), whose end is a tightening.
        val loginRole = com.kidslauncher.mdm.logins.loginRoleOf(policy?.loginBrowser, allowlist)
        val browser = BrowserConfig.apply(
            context, dpm, admin, vpnFilterEnabled, loginRole, lockdownLifted = overrideActive, managed = managedForHardening,
        )
        // Design 119 §4.4: a window that is over ends here (stale, timeout, left), and one whose rule no longer holds -
        // a time rule, the role off or not ready, the gate off, Play's update window, a pause's start (code QA M2: its
        // hide before the loop unhides every app) or end - ends `rule`; then the plan reads it.
        com.kidslauncher.mdm.logins.LoginWindow.duringApply(
            context,
            ruleEnds = com.kidslauncher.mdm.logins.loginWindowRuleEnds(
                timeLocked = scheduleLocked, roleReady = browser.ready, gateOn = gateOn, updateWindow = playState.updateWindow,
                overrideNow = overrideActive, overrideBefore = lastEnforcedOverride,
            ),
        )
        // Design 122: a Bluetooth pairing window that is over closes, and every one under a time rule, a pause or the
        // override.
        BtPairingWindow.duringApply(context, ruleEnds = scheduleLocked || overrideActive)
        val loginWindowOpen = com.kidslauncher.mdm.logins.LoginWindow.isOpen(context)
        // The role's Chrome (the plan's `roleApp`): its hide step re-reads the window under the window's lock (M3).
        val roleChrome = browser.ready && allowlist != null && !overrideActive && com.kidslauncher.mdm.logins.CHROME_PACKAGE !in allowlist
        val plan = computeEnforcementPlan(
            allowlist = allowlist,
            kioskDesired = policy?.kioskDesired == true,
            serverLockTaskFeatures = policy?.lockTaskFeatures ?: 0,
            overrideActive = overrideActive,
            controllable = installedPackages,
            ownPackage = ownPackage,
            systemDialer = systemDialerPackage(context),
            callState = withTimeRule(callsState, lock.callsBlocked),
            smsMode = smsMode,
            ourSmsRoleActive = SmsRuntime.roleHeld(context),
            ourDialerActive = CallSystem.dialerRoleHeld(context),
            smsPackages = smsPackages(CallSystem.defaultSmsPackage(context)),
            scheduleLocked = scheduleLocked,
            alarmApps = alarmAppPackages(context),
            inputMethods = keptInputMethods(enabledImes, systemImes, allowlist),
            lockUsableApps = lock.usableApps,
            // Design 105 §2.1: a rule (not the used-up budget alone) keeps the clocks it doesn't leave usable unpinned.
            ruleLocked = lock.rules.isNotEmpty(),
            ruleBlocksCalls = lock.callsBlocked,
            timeRulesSet = timePolicy != null && (timePolicy.rules.isNotEmpty() || hasBudget(timePolicy)),
            budgetSet = timePolicy != null && hasBudget(timePolicy),
            playState = playState,
            blockActivityStart = policy?.blockActivityStart == true,
            lockTaskHelpers = if (policy?.blockActivityStart == true) resolveLockTaskHelpers(context) else emptySet(),
            recentsPackage = systemRecentsPackage(context),
            emergencySos = sos,
            webViewProvider = webViewProviderPackage(),
            loginBrowser = com.kidslauncher.mdm.logins.CHROME_PACKAGE.takeIf { browser.ready },
            loginWindowOpen = loginWindowOpen,
            homeCandidates = UpdateFence.homeCandidates(pm),
            androidAutoInstalled = installedEvenHidden(pm, ANDROID_AUTO),
            allowAndroidAuto = policy?.allowAndroidAuto == true,
        )

        // Set before the loop below can release the dialer, so its keypad is never usable for
        // ordinary numbers in between; with calls managed it's lifted only while our own dialer
        // (redirection + in-call services) screens outgoing calls - see
        // EnforcementPlan.restrictOutgoingCalls. Emergency calls are exempt from this restriction.
        setRestriction(dpm, admin, UserManager.DISALLOW_OUTGOING_CALLS, plan.restrictOutgoingCalls)
        // SMS off, or no filter of ours in place (design 77 D1/D15): no SMS in or out. The SMS apps are suspended by
        // the plan too, for RCS. Not lifted by an override.
        setRestriction(dpm, admin, UserManager.DISALLOW_SMS, plan.restrictSms)
        // One read of every third-party app's requested permissions for the call pass here and the access pass after the
        // loop (`MATCH_UNINSTALLED_PACKAGES`: hiding doesn't change what an app requests); `null` if it failed.
        val requested = thirdPartyRequested(context)
        applyCallPermissions(context, dpm, admin, plan.denyCallPermissions, allowlist, requested)
        // Design 77 §2.5, §8.6: in "Styrt av Vibb", and with the rules unknown (QA M1), no other app receives, reads or
        // sends SMS. Never lifted by an
        // override or the pause (the call rules' exception).
        applySmsPermissions(context, dpm, admin, smsPermissionsDenied(smsMode), requested)

        lastPlanSuspend = plan.suspend
        lastPlanHide = plan.hide
        lastNeverRestrict = plan.neverRestrict
        PlatformRefusals.storePlan(context, plan.suspend, plan.hide)
        // What the platform refused on this boot because the package is exempt (Android 17's Settings): not asked again
        // while it still is (QA L1).
        val refused = PlatformRefusals.stillExempt(context, PlatformRefusals.now(context))
        // Every app's playing sessions, read at most once per pass and only once a package is about to be suspended
        // (2026-11-04: each is paused before its suspension; without listener access none).
        val playingSessions by lazy { com.kidslauncher.mdm.music.MusicSessions.playingSessions(context) }
        // The plan's packages outside the controllable set (Android Auto, the other Home apps) are visited too.
        for (packageName in installedPackages + plan.extraPackages) {
            if (packageName == ownPackage) continue

            // Only apps that aren't allowed at all are hidden; the schedule lock only suspends
            // (hiding broadcasts PACKAGE_REMOVED and drops alarms/jobs - QA step 4 #2).
            val shouldBeHidden = packageName in plan.hide
            val currentlySuspended = try {
                pm.isPackageSuspended(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            // Read after the current state: the PIN lock's camera lock records a package before it
            // suspends it, so a camera it just suspended is never undone here (and neither is a
            // package the update fence holds).
            // Map links' step 2 holds the apps holding Vibb One's link hosts suspended while its page is open in a pause.
            val shouldBeSuspended = suspendTarget(
                packageName, plan.suspend, fenceHeld + com.kidslauncher.mdm.lock.CameraLock.held + com.kidslauncher.mdm.map.MapLinkStep.held(),
            )
            // Checked separately, every cycle: the two states can disagree (an older build, a
            // policy applied before an exemption, the lock ending), e.g. the system dialer left
            // hidden must be released outright.
            val currentlyHidden = isHidden(dpm, admin, packageName)
            // A restriction the platform refused as exempt on this boot and build isn't asked for again (compat-fix-1).
            val suspendCall = restrictCallDue(shouldBeSuspended, currentlySuspended, packageName, refused.suspend)
            val hideCall = restrictCallDue(shouldBeHidden, currentlyHidden, packageName, refused.hide)
            if (!suspendCall && !hideCall) continue
            // The system's callbacks about this package after the pass are its trail (`AppLoadHold`: 3 s quiet / 30 s).
            com.kidslauncher.mdm.ui.home.AppLoadHold.touch(packageName)

            try {
                // Both calls can fail *without* throwing - setPackagesSuspended returns the
                // subset of package names it couldn't act on (some privileged/protected system
                // packages silently refuse suspension by platform policy) and
                // setApplicationHidden returns a plain boolean. Discarding these previously made
                // that failure mode invisible - confirmed live with a carrier-privileged /product-
                // partition system app that stayed reachable despite being correctly excluded
                // from the allowlist, with nothing in logs to explain why.
                if (suspendCall) {
                    // A playing session is paused first (design 21 §3; every app's since 2026-11-04).
                    val toPause = if (shouldBeSuspended && !currentlySuspended) playingSessions else emptyMap()
                    com.kidslauncher.mdm.music.MusicSessions.pauseBeforeSuspend(
                        com.kidslauncher.mdm.music.sessionsToPause(packageName, shouldBeSuspended, currentlySuspended, toPause),
                    )
                    val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)
                    // An exempt package's refusal is recorded and logged once by PlatformRefusals instead.
                    if (!notSuspended.isNullOrEmpty() && !(shouldBeSuspended && PlatformRefusals.refused(context, packageName, PlatformRefusals.Call.SUSPEND))) {
                        Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName (setPackagesSuspended)")
                    }
                    // The Play Store is the package verifier on GMS phones and can't be suspended
                    // there (B4) - reported, so the server can say Play is blocked only in kiosk.
                    if (packageName == PLAY_STORE && shouldBeSuspended) {
                        PlayRuntime.recordStoreSuspendable(context, notSuspended.isNullOrEmpty())
                    }
                }
                if (hideCall) {
                    val hiddenOk = if (roleChrome && packageName == com.kidslauncher.mdm.logins.CHROME_PACKAGE) {
                        com.kidslauncher.mdm.logins.LoginWindow.chromeStep(context, dpm, admin, playState.updateWindow)
                    } else if (packageName == com.kidslauncher.mdm.assistant.SETTINGS_PACKAGE) {
                        // Design 122: an armed or open Bluetooth pairing window keeps Settings unhidden (its lock).
                        BtPairingWindow.settingsHideStep(dpm, admin, shouldBeHidden)
                    } else {
                        dpm.setApplicationHidden(admin, packageName, shouldBeHidden)
                    }
                    if (!hiddenOk && !(shouldBeHidden && PlatformRefusals.refused(context, packageName, PlatformRefusals.Call.HIDE))) {
                        Log.w(LOG_TAG, "Platform refused to ${if (shouldBeHidden) "hide" else "unhide"} $packageName (setApplicationHidden)")
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Failed to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName",
                    e
                )
            }
        }
        // Design 68 §3.2, design 127 §4.1: after the loop - a hidden app probably can't be denied (the admin path reads
        // the package without MATCH_UNINSTALLED_PACKAGES), and the pass that unhides it comes first.
        if (requested != null) applyAccessPermissions(context, dpm, admin, requested, policy, overrideActive)
        // Design 09 A6: the SMS role goes back only now, with the previous holder unhidden by the loop.
        applySmsRole(context, dpm, admin, callsState, smsMode, gateLock, allowRelease = true)
        // Android's blocked-numbers list cleared while managed (the roles are settled now), and watched from here on.
        com.kidslauncher.mdm.calls.BlockedNumbersRuntime.afterApply(context)
        // Design 119 §4.1 (M1): Chrome hidden by the loop first, then the gate switched - enabling it changes the role
        // service's package hash, so the controller drops a hidden holder and grants the gate -, then the repair.
        com.kidslauncher.mdm.logins.LinkGateRuntime.afterLoop(
            context, dpm, admin, gateOn, pauseEnded = lastEnforcedOverride == true && !overrideActive,
        )
        lastEnforcedLockKey = lock.key()
        lastEnforcedPlayState = playState
        lastEnforcedOverride = overrideActive
        lastChromeUpdateUnhidden = roleChrome && playState.updateWindow
        if (appliedListeners.isNotEmpty()) mainHandler.post { appliedListeners.forEach { it() } }
        // Design 24b §1.5, after hiding (a hidden package's services leave the enabled lists): the
        // accessibility list while apps are managed (in windows too: QA 24b High 1), the keyboard
        // list and no hotspot or tethering while also no window is open. Setting the tethering
        // restriction untethers.
        permittedListsOk = applyPermittedLists(dpm, admin, plan.permittedAccessibility, plan.permittedInputMethods)
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_TETHERING, plan.restrictTethering)
        // The PIN lock's camera targets for this phone now (no PackageManager work at screen-off).
        try {
            com.kidslauncher.mdm.lock.CameraLock.refreshTargets(context, installedPackages.toSet())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Camera lock targets failed", e)
        }

        // Handy's PIN lock (step 10) first - whether it is LOCKED changes the lock-task setting
        // below (featuresWhileLocked / lockTaskWhileLocked), so a sync can't undo the lock.
        val managed = policy?.allowlist != null || callState.managed
        com.kidslauncher.mdm.lock.PinLockRuntime.configure(context, dpm, admin, policy, managed, parentChoice, fromServer)
        // The boot cover (design 16b, switch off by default): its PPA next to HomeActivity's while
        // wanted, the component off when not - enabled only at shutdown (and, with Android's lock as the
        // gate, at the locked boot: design 59 F3 - the gate as configure above just left it; F3C-4).
        try {
            com.kidslauncher.mdm.lock.BootCover.applyPolicy(
                context, dpm, admin, policy?.bootCover == true, managed,
                androidGate = com.kidslauncher.mdm.lock.AndroidLock.gateNow(context),
                parentChoice = parentChoice,
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover policy failed", e)
        }
        // Kiosk pinning, lock-task features, the status bar backstop and DISALLOW_CREATE_WINDOWS
        // (budget, or the PIN lock) - one place shared with the lock's fast path.
        com.kidslauncher.mdm.lock.LockTaskChrome.applyPlan(
            context, plan.kioskPackages, plan.lockTaskFeatures, plan.restrictCreateWindows,
            recentsPin = plan.recentsPin,
            pinLockHelpers = { resolvePinLockHelpers(context) },
            ruleClockPins = plan.ruleClockPins,
        )

        // The keyguard features (B2's camera bit) are written by the PIN lock's configure above - the one
        // keyguard writer (design 32 step 2, lock/KeyguardCover).

        // Auto-lock: the parent's screen timeout (emulator run 2026-10-06), lifted by the override.
        ScreenTimeout.apply(
            context, dpm, admin,
            screenTimeoutAction(
                hardeningManaged(policy?.allowlist, callState.managed), policy?.screenTimeoutSeconds, overrideActive,
            ),
        )

        applyDateTimeLock(dpm, admin, plan.lockDateTime)

        // One clock format on the whole phone (design 34 P12): TIME_12_24 through the "Modify system
        // settings" app-op, not a restriction - the override and the pause change nothing.
        ClockFormatSetting.apply(context, policy?.launcherUi)

        // Vibb music's volume cap (design 21 §3): while managed, lifted by the override and the pause.
        com.kidslauncher.mdm.music.VolumeCap.refresh(context, policy, managedForHardening, overrideActive)

        // Security review S-L5: no Bluetooth file transfer (OPP) to or from a friend's phone or a PC while managed -
        // audio devices are untouched. Lifted by the override and the pause, like every restriction of ours.
        setRestriction(dpm, admin, UserManager.DISALLOW_BLUETOOTH_SHARING, bluetoothSharingRestricted(managedForHardening, overrideActive))
        // Design 94: data roaming off and locked - since design 97 under "I utlandet"'s Ingen data, the parent's explicit
        // choice per phone, so whether or not the phone is managed in hardening's sense; lifted by the override and the
        // pause. Then the relock of a SIM that still reads roaming on (§2.4.2). Vibb never turns roaming on (D9).
        MobileNetworkRuntime.applyDataRoaming(context, dpm, admin, vpnInputs.roamingLocked)
        // Design 95 rev. 3 item 3: Android's mobile-network settings locked (the parent's switch, on by default), so
        // mobile data the parent turned off in Android stays off; lifted by the override and the pause.
        MobileNetworkRuntime.applyMobileSettingsLock(dpm, admin, mobileSettingsLocked(policy?.mobileSettingsLocked, overrideActive))
        // Design 126 A5: "Blokker 2G" (the parent's switch, on by default), lifted by the override and the pause - its
        // failure is "no network", which only an on-phone lift can undo - and abroad by itself only when the parent
        // turned on "Tillat 2G i utlandet" (off by default, the user's decision of 2026-11-22).
        MobileNetworkRuntime.applyCellular2g(context, dpm, admin, policy?.block2g, policy?.allow2gAbroad, overrideActive)

        // Same "fully open" treatment as everything else while an override is active - confirmed
        // live this needs to include the VPN filter too: the whole point of the offline-override PIN
        // and the pause-restrictions kill-switch is a guaranteed working, unblocked device when
        // something's wrong, which can include the VPN/filter itself misbehaving. An earlier version
        // of this deliberately excluded vpnFilterEnabled from that (reasoning: an emergency escape
        // hatch for access restrictions shouldn't silently override a parent's separate content-
        // filtering choice) - wrong in practice, reverted. Only true-by-default (filtering on) when
        // no override is active AND no policy has ever been fetched, which has no admin choice yet
        // to respect.
        applyVpnRestrictions(context, vpnInputs, policy?.quickControlsMask, vpnShapeNow)
        // The browsers' own secure DNS off with the filter (design 67 §2.1), lifted with it: BrowserConfig, before the
        // plan (design 102 §4.1).

        applyPrivateDnsLock(dpm, admin)

        applySideloadRestriction(dpm, admin, blockSideloading = !overrideActive)

        // Last, after the always-on VPN is in place (DISALLOW_CONFIG_VPN). Not lifted by the
        // override or pause - see hardeningPlan.
        applyHardening(context, dpm, admin, hardening, policy?.locationPolicy)

        // Notification auto-cancel (step 11): the rule's inputs, resolved here off the main thread.
        try {
            com.kidslauncher.mdm.badges.NotificationRuleRuntime.refresh(
                context, policy, overrideActive, lock.usableApps, callState, essentialNotificationPackages(context, callState),
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Notification rule refresh failed", e)
        }
        // Design 98 §3: a default-apps window that is over closes for good (HOME re-seated, the roles compared), a Wi-Fi
        // step left by a pause that ended ends, and the setup notification follows. Never throws.
        com.kidslauncher.mdm.assistant.AssistantSetup.afterApply(context)
    }

    /**
     * Packages whose notifications always stay (step 11, qa-11-design.md #11), resolved on this
     * phone: the system and default dialer, the emergency dialer and Telecom, every receiver of
     * `SMS_CB_RECEIVED` (emergency alerts, whatever the package is called here), the SMS app
     * while SMS is on, the alarm/clock app and the keyboards. The pure rule adds the fixed ones
     * (`android`, SystemUI, `com.android.phone`, the known cell-broadcast names).
     */
    private fun essentialNotificationPackages(context: Context, callState: CallPolicyState): Set<String> {
        val pm = context.packageManager
        val (resolved, _) = resolveHelpers(context)
        // Cell broadcast: CellBroadcastService hands emergency alerts to the receiver of
        // ACTION_SMS_EMERGENCY_CB_RECEIVED (system API - the literal), so an OEM-named alert app
        // is found there too (qa-11-code #7). System apps only.
        val cellBroadcast = listOf(android.provider.Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION, ACTION_SMS_EMERGENCY_CB_RECEIVED)
            .flatMap { action ->
                try {
                    pm.queryBroadcastReceivers(Intent(action), PackageManager.MATCH_SYSTEM_ONLY).mapNotNull { it.activityInfo?.packageName }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        val defaultDialer = try {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (e: Exception) {
            null
        }
        val smsOn = when (callState) {
            is CallPolicyState.Managed -> callState.rules.smsEnabled
            CallPolicyState.Unmanaged -> true
            CallPolicyState.UnknownFailClosed -> false
        }
        return setOfNotNull(
            systemDialerPackage(context),
            defaultDialer,
            resolved[HelperKind.EMERGENCY_DIALER]?.packageName,
            resolved[HelperKind.TELECOM]?.packageName,
            resolved[HelperKind.CELL_BROADCAST]?.packageName,
            CallSystem.defaultSmsPackage(context).takeIf { smsOn },
        ) + alarmAppPackages(context) + cellBroadcast + inputMethodPackages(context)
    }

    /**
     * Sets or clears each [HardeningRestriction] as [plan] says. Location is turned on (or off, for
     * the parent's location "off", [systemLocationTarget]) before its setting is locked (Find my device needs it; WifiNetworksActivity then finds it on and leaves
     * it on). Clearing only ever removes what a device owner set - restrictions the platform or
     * OEM set themselves are untouched.
     */
    private fun applyHardening(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        plan: HardeningPlan,
        locationPolicy: com.kidslauncher.mdm.server.dto.LocationPolicy?,
    ) {
        // Location "off" from the parent switches system location off (and the CONFIG_LOCATION
        // switch below then keeps it off); otherwise that switch turns it on first.
        systemLocationTarget(locationPolicy, plan.forceLocationOn)?.let { wanted ->
            if (QuickControls.isLocationEnabled(context) != wanted) QuickControls.setLocationEnabled(dpm, admin, wanted)
        }
        for ((restriction, set) in plan.restrictions) {
            // Design 98 §2.4: the parent's "Blokker flymodus" going on ends the kid's airplane mode - `off_by: parent`.
            if (set && restriction == HardeningRestriction.AIRPLANE_MODE && com.kidslauncher.mdm.assistant.AirplaneRuntime.isOn(context) &&
                !com.kidslauncher.mdm.assistant.AirplaneRuntime.restricted(context)
            ) {
                com.kidslauncher.mdm.assistant.AirplaneRuntime.vibbEnding(com.kidslauncher.mdm.assistant.AirplaneBy.PARENT)
            }
            if (set) restriction.userManagerKey()?.let { setRestriction(dpm, admin, it, true) }
        }
        if (plan.screenCaptureDisabled) syncScreenCapture(dpm, admin, true)
        clearHardening(context, dpm, admin, plan)
        // Snarveier's Flymodus dims with "Blokker flymodus" (design 99): no broadcast an app can hear says a device
        // owner's restriction changed, and the policy write the panel listens for comes before this pass (emulator
        // check, 2026-11-15: it stayed undimmed until another signal).
        com.kidslauncher.mdm.shortcuts.ShortcutsRuntime.requestRefresh()
    }

    /** `setScreenCaptureDisabled` only when it differs ([screenCaptureWrite]); a failure is logged
     * and the next pass retries. */
    private fun syncScreenCapture(dpm: DevicePolicyManager, admin: ComponentName, disabled: Boolean) {
        try {
            val write = screenCaptureWrite(dpm.getScreenCaptureDisabled(admin), disabled) ?: return
            dpm.setScreenCaptureDisabled(admin, write)
            Log.i(LOG_TAG, "Screen capture ${if (write) "disabled" else "enabled"} by policy")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the screen capture policy: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Lifts the restrictions [plan] has off, in [hardeningClearSteps] order, each step on its
     * own (one failing doesn't stop the rest). `DISALLOW_SET_WALLPAPER` is only lifted once
     * nothing of ours can show on the system wallpaper any more (navy back, or cleared) - else it
     * stays until a later pass manages the reset (qa-08-code.md #1, QA 08 #2).
     */
    private fun clearHardening(context: Context, dpm: DevicePolicyManager, admin: ComponentName, plan: HardeningPlan) {
        if (!plan.screenCaptureDisabled) syncScreenCapture(dpm, admin, false)
        var wallpaperClean = true
        for (step in hardeningClearSteps(plan)) {
            try {
                when (step) {
                    ClearStep.ResetWallpaper -> wallpaperClean = WallpaperApplier.resetIfOurs(context)
                    is ClearStep.Clear -> {
                        if (step.restriction == HardeningRestriction.SET_WALLPAPER && !wallpaperClean) {
                            Log.w(LOG_TAG, "Our wallpaper couldn't be reset - DISALLOW_SET_WALLPAPER stays for now")
                        } else {
                            step.restriction.userManagerKey()?.let { setRestriction(dpm, admin, it, false) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Clearing hardening step $step failed", e)
            }
        }
    }

    /** `null` for a restriction this Android doesn't have ([hardeningKeyApplies]; design 126's
     * `DISALLOW_ASSIST_CONTENT` on 14): neither set nor cleared. */
    private fun HardeningRestriction.userManagerKey(): String? {
        if (!hardeningKeyApplies(this, Build.VERSION.SDK_INT)) return null
        return when (this) {
            HardeningRestriction.FACTORY_RESET -> UserManager.DISALLOW_FACTORY_RESET
            HardeningRestriction.ADD_USER -> UserManager.DISALLOW_ADD_USER
            HardeningRestriction.MODIFY_ACCOUNTS -> UserManager.DISALLOW_MODIFY_ACCOUNTS
            HardeningRestriction.CONFIG_VPN -> UserManager.DISALLOW_CONFIG_VPN
            HardeningRestriction.USB_FILE_TRANSFER -> UserManager.DISALLOW_USB_FILE_TRANSFER
            HardeningRestriction.DEBUGGING_FEATURES -> UserManager.DISALLOW_DEBUGGING_FEATURES
            HardeningRestriction.SAFE_BOOT -> UserManager.DISALLOW_SAFE_BOOT
            HardeningRestriction.CONFIG_LOCATION -> UserManager.DISALLOW_CONFIG_LOCATION
            HardeningRestriction.AIRPLANE_MODE -> UserManager.DISALLOW_AIRPLANE_MODE
            HardeningRestriction.SET_WALLPAPER -> UserManager.DISALLOW_SET_WALLPAPER
            HardeningRestriction.CONFIG_LOCALE -> UserManager.DISALLOW_CONFIG_LOCALE
            HardeningRestriction.CONTENT_CAPTURE -> UserManager.DISALLOW_CONTENT_CAPTURE
            HardeningRestriction.CONTENT_SUGGESTIONS -> UserManager.DISALLOW_CONTENT_SUGGESTIONS
            HardeningRestriction.ASSIST_CONTENT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) UserManager.DISALLOW_ASSIST_CONTENT else null
        }
    }

    /**
     * While calls are managed: our own call permissions are self-granted (READ_CONTACTS so
     * screening sees every number, CALL_PHONE for the phone book, READ_PHONE_STATE, READ_CALL_LOG
     * for the callback window - the dialer role grants most of them too), and every third-party
     * app's CALL_PHONE/ANSWER_PHONE_CALLS is denied ("blocked by admin"), and READ_PHONE_STATE is
     * granted to the allowed ones of them that ask for it ([phoneStateGrants], design 57 (d) - the
     * denial greys their whole Phone group). Unmanaged, all of it goes back to DEFAULT. Grant states
     * are only written when they differ (re-setting re-notifies, see CLAUDE.md).
     */
    private fun applyCallPermissions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        deny: Boolean,
        allowlist: List<String>?,
        requested: Map<String, List<String>>?,
    ) {
        if (deny) {
            for (permission in OWN_CALL_PERMISSIONS) {
                QuickControls.selfGrantPermission(context, dpm, admin, permission)
            }
        }
        requested ?: return
        val denial = if (deny) DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        for (packageName in callPermissionTargets(requested, context.packageName)) {
            for (permission in requested[packageName].orEmpty().filter { it in CALL_PERMISSIONS }) {
                setGrantState(dpm, admin, packageName, permission, denial)
            }
        }
        for ((packageName, wanted) in phoneStateGrants(requested, context.packageName, allowlist, deny)) {
            setGrantState(dpm, admin, packageName, READ_PHONE_STATE, wanted)
        }
    }

    /** Every non-system package's requested permissions (hidden ones too), or `null` when the list can't be read. */
    private fun thirdPartyRequested(context: Context): Map<String, List<String>>? = try {
        context.packageManager.getInstalledPackages(
            PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong())
        ).filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0 }
            .associate { it.packageName to it.requestedPermissions.orEmpty().toList() }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't list installed packages for their permissions", e)
        null
    }

    /** Written only when the state differs ([grantWrite], the rule above). */
    private fun setGrantState(dpm: DevicePolicyManager, admin: ComponentName, packageName: String, permission: String, wanted: Int) {
        try {
            val write = grantWrite(dpm.getPermissionGrantState(admin, packageName, permission), wanted) ?: return
            writeGrantState(dpm, admin, packageName, permission, write)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set $permission for $packageName", e)
        }
    }

    /** `setPermissionGrantState`, its `false` (refused without a throw) logged and returned. */
    private fun writeGrantState(dpm: DevicePolicyManager, admin: ComponentName, packageName: String, permission: String, state: Int): Boolean {
        val written = dpm.setPermissionGrantState(admin, packageName, permission, state)
        if (!written) {
            Log.w(LOG_TAG, "Platform refused $permission = $state for $packageName (setPermissionGrantState)")
        }
        return written
    }

    /** A denial waits for a call's end ([accessGrants]); [com.kidslauncher.mdm.lock.BootHome.callMayHaveEnded]
     * applies again once no call is live. Memory only: a new process's first apply decides again. */
    @Volatile
    var accessDeferred: Boolean = false

    /** Design 127 §3.2: the (package, key) pairs whose parent's off Android refused in this process's last pass
     * ([accessRefusedAfter]), the report's `refused`. Memory only. */
    @Volatile
    var accessRefused: Set<AppKey> = emptySet()

    /**
     * Design 127 §4.1 (design 68 §3.2 widened): the parent's Kamera, Posisjon, Mikrofon and Kontakter off per app
     * ([accessGrants]) for [requested] (third-party apps and their requested permissions), with the policy's
     * `app_permissions` ([appPermissionsOf]; `null` = touch nothing but a window's lift), the window ([windowOpen]: the
     * pause or the override lift it), the live call ([WindowRelock.liveCall]) and which denied targets hold their
     * permission now. Records [accessRefused]. Returns whether a denial was deferred. Never throws.
     */
    private fun accessPass(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        requested: Map<String, List<String>>,
        policy: PolicyResponse?,
        windowOpen: Boolean,
    ): Boolean = try {
        val pm = context.packageManager
        // A hidden app (not allowed) is skipped: Android can't grant-state it (it logged "Platform refused" at every
        // apply - the code QA's N1); the apply that unhides it comes after the loop, so it is denied then.
        val visible = requested.filter { (pkg, perms) ->
            perms.none { AccessKey.ofPermission(it) != null } || !isHidden(dpm, admin, pkg)
        }
        val sameSigner = { pkg: String -> com.kidslauncher.mdm.music.MusicTrust.sameSigner(context, pkg) }
        val targets = accessTargets(visible, context.packageName, sameSigner)
        val denied = appPermissionsOf(policy?.appPermissions)
        val current = targets.keys.associateWith { dpm.getPermissionGrantState(admin, it.packageName, it.permission) }
        val held = targets.filterTo(LinkedHashMap()) { (grant, key) -> key in denied?.get(grant.packageName).orEmpty() }
            .keys.filterTo(mutableSetOf()) { pm.checkPermission(it.permission, it.packageName) == PackageManager.PERMISSION_GRANTED }
        val callLive = !windowOpen && held.isNotEmpty() && WindowRelock.liveCall(context)
        val plan = accessGrants(visible, context.packageName, denied, windowOpen, callLive, held, current, sameSigner)
        val failed = mutableSetOf<AppKey>()
        for ((grant, state) in plan.writes) {
            val written = try {
                writeGrantState(dpm, admin, grant.packageName, grant.permission, state)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't set ${grant.permission} for ${grant.packageName}", e)
                false
            }
            if (!written && state == GRANT_STATE_DENIED) failed += AppKey(grant.packageName, targets.getValue(grant))
        }
        accessRefused = accessRefusedAfter(
            accessRefused,
            targets.mapTo(mutableSetOf()) { (grant, key) -> AppKey(grant.packageName, key) },
            plan.waiting.mapTo(mutableSetOf()) { AppKey(it.packageName, targets.getValue(it)) },
            failed,
        )
        if (plan.deferred) Log.i(LOG_TAG, "An app's access off waits for the call's end")
        plan.deferred
    } catch (e: Exception) {
        Log.w(LOG_TAG, "App access permissions failed", e)
        false
    }

    private fun applyAccessPermissions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        requested: Map<String, List<String>>,
        policy: PolicyResponse?,
        overrideActive: Boolean,
    ) {
        accessDeferred = accessPass(context, dpm, admin, requested, policy, overrideActive)
    }

    /**
     * Design 127 §4.1: [accessGrants] for one just-installed or updated package, so an app the parent switched a key
     * off for (or an update that adds one of its permissions) is denied before its first start - whether or not calls
     * or apps are managed. Called from [enforceOnNewPackage] and on every package change; a hidden package is left to
     * the apply that unhides it. Under [apply]'s lock (the code QA's N2), so an install callback at a window's edge
     * can't write DENIED between the window's DEFAULT and the next apply. Never throws; never on the main thread.
     */
    @Synchronized
    fun enforceAccessOnPackage(context: Context, packageName: String) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName) || !accessTarget(packageName, context.packageName) { com.kidslauncher.mdm.music.MusicTrust.sameSigner(context, it) }) return
            val info = context.packageManager.getPackageInfo(
                packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
            if ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0) return
            val requested = mapOf(packageName to info.requestedPermissions.orEmpty().toList())
            val windowOpen = OfflineOverride.isActive() || RestrictionsPause.isActive()
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            if (accessPass(context, dpm, admin, requested, currentPolicyDecision().policy, windowOpen)) accessDeferred = true
        } catch (e: PackageManager.NameNotFoundException) {
            // Hidden or gone: the apply that unhides it denies it.
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't check the access of $packageName", e)
        }
    }

    /**
     * Makes us the default dialer while calls are managed ([dialerRoleAction]):
     * `DevicePolicyManager.setDefaultDialerApplication` (device owner, no prompt). If that throws,
     * the error is reported to the server and HomeActivity falls back to the system's role
     * prompt once a day ([com.kidslauncher.mdm.calls.shouldPromptForRole]). When calls are not
     * managed (calls `unmanaged`, or no `call_policy` on a phone whose calls never were),
     * a role we took is handed back to the system dialer, so the phone is never left with our
     * dialer and no rules.
     */
    private fun applyDialerRole(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        state: CallPolicyState,
        smsMode: ChannelMode?,
        gateLock: Boolean,
    ) {
        // For the phone book's call-log pass-on, which runs on the main thread (qa-12-code #4).
        systemDialerPackage(context)?.let { CallPrefs.systemDialer(context, it) }
        val held = CallSystem.dialerRoleHeld(context)
        val action = dialerRoleAction(state, held, CallPrefs.dialerRoleTakenByUs(context))
        // Before the role changes hands: our own role-grantable permissions become POLICY_FIXED,
        // so the hand-back's revocation skips them and doesn't kill our process (QA 09 #6).
        if (action != RoleAction.NONE || state.managed) {
            fixOwnPermissions(context, dpm, admin, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            CallPrefs.ownPermissionsFixed(context, true)
        }
        // Changing the default dialer with DISALLOW_CONFIG_DEFAULT_APPS set may be refused.
        if (action != RoleAction.NONE) setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_DEFAULT_APPS, false)
        when (action) {
            RoleAction.TAKE -> try {
                dpm.setDefaultDialerApplication(context.packageName)
                CallPrefs.dialerRoleTakenByUs(context, true)
                CallPrefs.lastError(context, null)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "setDefaultDialerApplication failed", e)
                CallPrefs.lastError(context, "setDefaultDialerApplication: ${e.javaClass.simpleName}: ${e.message}".take(300))
            }
            RoleAction.RELEASE -> {
                val systemDialer = systemDialerPackage(context)
                try {
                    if (systemDialer != null) dpm.setDefaultDialerApplication(systemDialer)
                    CallPrefs.dialerRoleTakenByUs(context, false)
                    // Telecom tells the system dialer about missed calls from now on (QA 12 #8).
                    MissedCallNotifier.cancelOurs(context)
                    CallPrefs.lastError(context, null)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Handing the dialer role back to $systemDialer failed", e)
                    CallPrefs.lastError(context, "release dialer role: ${e.javaClass.simpleName}: ${e.message}".take(300))
                }
            }
            RoleAction.NONE -> if (held || !state.managed) CallPrefs.lastError(context, null)
        }
        // Re-set in the same pass (QA 09 #11).
        // Design 77: the SMS role's lock counts too (lockDefaultAppsFor).
        setRestriction(
            dpm, admin, UserManager.DISALLOW_CONFIG_DEFAULT_APPS,
            lockDefaultAppsFor(state.managed, CallSystem.dialerRoleHeld(context), smsMode, SmsRuntime.roleHeld(context), gateLock) &&
                !defaultAppsLockLifted(context),
        )
        val heldAfter = CallSystem.dialerRoleHeld(context)
        // Handed back: our fixed permissions return to DEFAULT (no revoke, no kill) - qa-09-code #4. Not while the SMS
        // role is ours or SMS is managed (design 77 §8.3): its hand-back would kill us otherwise.
        val smsHolds = smsMode == ChannelMode.MANAGED || SmsRuntime.roleHeld(context)
        if (shouldResetOwnPermissions(state.managed || smsHolds, heldAfter, CallPrefs.ownPermissionsFixed(context))) {
            fixOwnPermissions(context, dpm, admin, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            CallPrefs.ownPermissionsFixed(context, false)
        }
        // A role change can leave another app's screen (the system dialer, a camera) on top of
        // Home in kiosk: bring Home back once, if the role really changed and no call is on
        // (device owner + HOME may start from the background, B2, qa-09-code #3).
        if (bringHomeAfterRoleChange(held, heldAfter, LauncherPreferences.mdm().kioskEnabled(), inCall(context))) {
            bringHomeToFront(context)
        }
    }

    /**
     * Design 77 D1: we are the default SMS app while SMS is "Styrt av Vibb" ([smsRoleAction]): taken with
     * `setDefaultSmsApplication` (device owner, no prompt) and checked with RoleManager afterwards - the call returns
     * silently when it fails (design 09 A1). "Ikke styrt" hands back a role we took, to the holder before us
     * ([smsRoleReturnTo]) - only with [allowRelease], after the package loop unhid it (design 09 A6). Our own SMS
     * permissions are fixed by policy before the role changes hands (§8.3) and again once it is ours.
     */
    private fun applySmsRole(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        callsState: CallPolicyState,
        smsMode: ChannelMode?,
        gateLock: Boolean,
        allowRelease: Boolean,
    ) {
        val held = SmsRuntime.roleHeld(context)
        val action = smsRoleAction(smsMode, held, SmsPrefs.takenByUs(context))
        if (action == RoleAction.NONE || (action == RoleAction.RELEASE && !allowRelease)) {
            if (held && smsMode == ChannelMode.MANAGED) fixOwnPermissions(context, dpm, admin, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            signalSmsRoleChange(context, held)
            return
        }
        fixOwnPermissions(context, dpm, admin, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        CallPrefs.ownPermissionsFixed(context, true)
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_DEFAULT_APPS, false)
        try {
            if (action == RoleAction.TAKE) {
                SmsPrefs.previousHolder(context, CallSystem.defaultSmsPackage(context)?.takeIf { it != context.packageName })
                dpm.setDefaultSmsApplication(admin, context.packageName)
                if (SmsRuntime.roleHeld(context)) {
                    SmsPrefs.takenByUs(context, true)
                    SmsPrefs.lastError(context, null)
                    // The role granted our SMS permissions: fixed by policy now, so a hand-back never revokes them.
                    fixOwnPermissions(context, dpm, admin, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                } else {
                    SmsPrefs.lastError(context, "setDefaultSmsApplication: role not held afterwards")
                }
            } else {
                val installed = smsCandidates(context)
                val target = smsRoleReturnTo(SmsPrefs.previousHolder(context), context.packageName, installed)
                if (target == null) {
                    SmsPrefs.lastError(context, "release sms role: no SMS app to hand it to")
                } else {
                    dpm.setDefaultSmsApplication(admin, target)
                    if (!SmsRuntime.roleHeld(context)) {
                        SmsPrefs.takenByUs(context, false)
                        SmsPrefs.lastError(context, null)
                    } else {
                        SmsPrefs.lastError(context, "release sms role: still held after handing it to $target")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The SMS role change ($action) failed", e)
            SmsPrefs.lastError(context, "sms role $action: ${e.javaClass.simpleName}: ${e.message}".take(300))
        }
        val heldAfter = SmsRuntime.roleHeld(context)
        setRestriction(
            dpm, admin, UserManager.DISALLOW_CONFIG_DEFAULT_APPS,
            lockDefaultAppsFor(callsState.managed, CallSystem.dialerRoleHeld(context), smsMode, heldAfter, gateLock) &&
                !defaultAppsLockLifted(context),
        )
        signalSmsRoleChange(context, heldAfter)
    }

    /** `DISALLOW_CONFIG_DEFAULT_APPS` lifted for a role's own page: design 98's pick window, or design 119's browser
     * role dialog (`vibb_browser`, M5) - Android's role request refuses under the restriction. */
    private fun defaultAppsLockLifted(context: Context): Boolean =
        com.kidslauncher.mdm.assistant.AssistantSetup.assistantPickOpen(context) ||
            com.kidslauncher.mdm.logins.BrowserRoleStep.isOpen(context)

    /** The installed packages that may take the SMS role back (the known SMS apps and the one before us). */
    private fun smsCandidates(context: Context): Set<String> {
        val pm = context.packageManager
        return (KNOWN_SMS_PACKAGES + listOfNotNull(SmsPrefs.previousHolder(context))).filterTo(mutableSetOf()) {
            try {
                pm.getPackageInfo(it, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /** B1 for the SMS role: a change since the server last heard asks for a sync (coalesced). */
    private fun signalSmsRoleChange(context: Context, held: Boolean) {
        // Design 77 §6.7 (code QA of step 2, L4): Home's tile and an open thread's compose bar follow the role at once.
        if (com.kidslauncher.mdm.sms.SmsHome.summary.roleHeld != held) com.kidslauncher.mdm.sms.SmsHome.refresh(context)
        if (SmsPrefs.roleSignalled(context) == held) return
        SmsPrefs.roleSignalled(context, held)
        try {
            SyncRunner.request(context, "role-changed")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't request a sync after the SMS role changed", e)
        }
    }

    /**
     * Design 77 §2.5, §8.6: while [managed] ([smsPermissionsDenied]: "Styrt av Vibb", or the rules unknown) every other
     * app's SMS permissions are DENIED by policy ([smsPermissionTargets]: the third-party requesters in [requested], and
     * Play services per [DENY_PLAY_SERVICES_SMS]); otherwise back to DEFAULT, a grant we took away given back first
     * ([smsPermissionPass], QA L8). Hard-restricted permissions: whether a device owner may set them is step 0's check -
     * a refusal is logged by [setGrantState] and changes nothing else.
     */
    private fun applySmsPermissions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        managed: Boolean,
        requested: Map<String, List<String>>?,
    ) {
        requested ?: return
        val playServices = try {
            context.packageManager.getPackageInfo(
                PLAY_SERVICES, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions.orEmpty().toList()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        // Every requester is listed (Play services too), so switching the GMS rule off puts it back to DEFAULT. QA L8:
        // what we denied is recorded, so leaving the denial gives a grant back instead of leaving it revoked.
        val pm = context.packageManager
        val pass = smsPermissionPass(
            smsPermissionTargets(requested, playServices, context.packageName, denyPlayServices = true),
            deny = { managed && (it != PLAY_SERVICES || DENY_PLAY_SERVICES_SMS) },
            granted = { pkg, permission ->
                dpm.getPermissionGrantState(admin, pkg, permission) != DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED &&
                    pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
            },
            recorded = SmsPrefs.deniedPermissions(context),
        )
        for (write in pass.writes) setGrantState(dpm, admin, write.packageName, write.permission, write.state)
        SmsPrefs.deniedPermissions(context, pass.record)
    }

    /** Any call at all; unknown counts as "in a call" (then Home stays where it is). */
    private fun inCall(context: Context): Boolean = try {
        context.getSystemService(TelecomManager::class.java)?.isInCall != false
    } catch (e: Exception) {
        true
    }

    /** A typed HOME start (design 16, QA #1), never an explicit component. */
    private fun bringHomeToFront(context: Context) {
        com.kidslauncher.mdm.lock.HomeFront.bringAfterUnlock(context, "a role change")
    }

    /** See [ownPermissionsToFix]: every role-grantable permission our manifest requests, set to
     * [grantState] (GRANTED = fixed by policy, DEFAULT = released, never DENIED - that would kill us). */
    private fun fixOwnPermissions(context: Context, dpm: DevicePolicyManager, admin: ComponentName, grantState: Int) {
        val requested = try {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions.orEmpty().toList()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read our own requested permissions", e)
            return
        }
        for (permission in ownPermissionsToFix(requested)) {
            if (grantState == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                QuickControls.fixOwnPermission(context, dpm, admin, permission)
            } else {
                try {
                    if (dpm.getPermissionGrantState(admin, context.packageName, permission) != grantState) {
                        dpm.setPermissionGrantState(admin, context.packageName, permission, grantState)
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't release own permission $permission", e)
                }
            }
        }
    }

    /**
     * B1: when a call role changed since the server last heard (a TAKE/RELEASE here, or the
     * platform/the parent changing it), ask for a sync so the calls page sees it now rather than
     * with the next sync. Coalesced by [SyncRunner]; once per change ([roleReportNeeded]).
     */
    private fun signalRoleChange(context: Context) {
        val now = CallSystem.roles(context)
        if (!roleReportNeeded(CallPrefs.rolesSignalled(context), now)) return
        CallPrefs.rolesSignalled(context, now)
        try {
            SyncRunner.request(context, "role-changed")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't request a sync after a role change", e)
        }
    }

    /**
     * The permitted accessibility services and keyboards (design 24b §1.5), set only when they
     * differ. `false` when AOSP refused one (an enabled non-system service outside the list - the
     * last list stays) or it threw; the relock reports it.
     */
    private fun applyPermittedLists(dpm: DevicePolicyManager, admin: ComponentName, accessibility: List<String>?, inputMethods: List<String>?): Boolean {
        var ok = true
        try {
            if (permittedListChanged(dpm.getPermittedAccessibilityServices(admin), accessibility) &&
                !dpm.setPermittedAccessibilityServices(admin, accessibility)
            ) {
                Log.w(LOG_TAG, "setPermittedAccessibilityServices refused - an enabled service is outside the list")
                ok = false
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the permitted accessibility services", e)
            ok = false
        }
        try {
            if (permittedListChanged(dpm.getPermittedInputMethods(admin), inputMethods) &&
                !dpm.setPermittedInputMethods(admin, inputMethods)
            ) {
                Log.w(LOG_TAG, "setPermittedInputMethods refused - an enabled keyboard is outside the list")
                ok = false
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the permitted input methods", e)
            ok = false
        }
        return ok
    }

    /**
     * The system helpers to pin with the kiosk app block (B4, QA 09 #2), resolved from intents on
     * this phone - package names differ per device. Only system apps count; for each intent the
     * first match that isn't forbidden (Settings, the camera) or Play wins ([firstHelper]), so a
     * forbidden first match doesn't hide the real helper (qa-09-code #6). The system dialer is
     * pinned by the plan itself. The result is logged for the device checks.
     */
    internal fun resolveLockTaskHelpers(context: Context): Set<String> {
        val (resolved, forbidden) = resolveHelpers(context)
        val helpers = lockTaskHelpers(resolved, forbidden)
        Log.i(
            LOG_TAG,
            "Kiosk app block helpers: $resolved -> $helpers (forbidden $forbidden); credential selector config: " +
                CREDENTIAL_CONFIGS.joinToString { "$it=${systemStringRaw(it)}" },
        )
        // Design 16d: the recents provider on its own line for the device checks (Jelly Star).
        val recents = resolved[HelperKind.RECENTS]
        Log.i(
            LOG_TAG,
            "Recents provider (config_recentsComponentName): ${recents?.packageName ?: "unknown"}" +
                (recents?.let { ", system ${it.system}, ${if (it.packageName in helpers) "pinned" else "not pinned"}" } ?: ""),
        )
        return helpers
    }

    /**
     * Design 26c §6.3: the system WebView's package, read once per apply (off main) and never
     * hidden or suspended ([computeEnforcementPlan]'s never-restrict set): the music app's Spotify
     * player is a WebView, and a suspended provider breaks every WebView. `null` if it can't be read.
     */
    internal fun webViewProviderPackage(): String? = try {
        android.webkit.WebView.getCurrentWebViewPackage()?.packageName
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't look up the WebView provider", e)
        null
    }

    /**
     * Design 16d: the recents provider - [recentsComponent]'s package when [trustedRecentsProvider]
     * accepts it (a system app with a HOME activity or that recents activity, never SystemUI;
     * qa-16d-code #5), else `null`. Pinned with the kiosk block (and out of the list while LOCKED),
     * never hidden or suspended by enforcement ([computeEnforcementPlan]'s never-restrict set,
     * [shouldSuspendNewPackage], the camera lock); the update fence is the only thing that suspends
     * it, for the minute of our self-update.
     */
    internal fun systemRecentsPackage(context: Context): String? {
        val component = recentsComponent() ?: return null
        val pkg = component.packageName
        val pm = context.packageManager
        val hasHome = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg),
                PackageManager.ResolveInfoFlags.of(0),
            ).isNotEmpty()
        } catch (e: Exception) {
            false
        }
        val hasRecents = try {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
            true
        } catch (e: Exception) {
            false
        }
        return trustedRecentsProvider(pkg, helperInfo(context, pkg)?.system == true, hasHome, hasRecents)
    }

    /** The recents provider the kiosk pins with the app block, as the plan would ([HelperKind.RECENTS]
     * through [lockTaskHelpers]) - for LockTaskChrome's passes before the first apply. */
    internal fun resolveRecentsPin(context: Context): String? {
        val (resolved, forbidden) = resolveHelpers(context)
        return lockTaskHelpers(mapOf(HelperKind.RECENTS to resolved[HelperKind.RECENTS]), forbidden).firstOrNull()
    }

    /**
     * The system packages handy's PIN lock pins while LOCKED with the kiosk off (step 10,
     * [pinLockHelpers]): emergency dialer, Telecom, the system dialer and the clock app.
     */
    internal fun resolvePinLockHelpers(context: Context): Set<String> {
        val (resolved, forbidden) = resolveHelpers(context)
        val helpers = pinLockHelpers(
            emergencyDialer = resolved[HelperKind.EMERGENCY_DIALER],
            telecom = resolved[HelperKind.TELECOM],
            systemDialer = helperInfo(context, systemDialerPackage(context)),
            alarmApp = null,
            alarmApps = alarmAppPackages(context).mapNotNull { helperInfo(context, it) },
            forbidden = forbidden,
            ourDialerHeld = CallSystem.dialerRoleHeld(context),
            emergencySos = resolved[HelperKind.EMERGENCY_SOS],
            clockApp = com.kidslauncher.mdm.lock.ClockApp.PACKAGE.takeIf { com.kidslauncher.mdm.lock.ClockApp.installed(context) },
        )
        Log.i(LOG_TAG, "PIN lock helpers (kiosk off): $helpers")
        return helpers
    }

    /**
     * What a VoIP call over the lock needs pinned besides its app with the kiosk off (design 17,
     * QA #6): the permission controller (the call app's microphone/camera prompts). System only.
     */
    internal fun resolveVoipHelpers(context: Context): Set<String> {
        val (resolved, forbidden) = resolveHelpers(context)
        return lockTaskHelpers(mapOf(HelperKind.PERMISSION_CONTROLLER to resolved[HelperKind.PERMISSION_CONTROLLER]), forbidden)
    }

    private fun helperInfo(context: Context, pkg: String?): ResolvedHelper? = pkg?.let {
        try {
            val flags = context.packageManager.getApplicationInfo(it, PackageManager.MATCH_UNINSTALLED_PACKAGES).flags
            ResolvedHelper(it, (flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveHelpers(context: Context): Pair<Map<HelperKind, ResolvedHelper?>, Set<String>> {
        val pm = context.packageManager
        fun info(pkg: String?): ResolvedHelper? = helperInfo(context, pkg)
        fun matches(intent: Intent): List<ResolvedHelper> = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
                .mapNotNull { info(it.activityInfo?.packageName) }
        } catch (e: Exception) {
            emptyList()
        }
        fun firstPackage(intent: Intent): String? = matches(intent).firstOrNull { it.system }?.packageName
        val forbidden = setOfNotNull(
            firstPackage(Intent(Settings.ACTION_SETTINGS)),
            firstPackage(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)),
            firstPackage(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        )
        fun activity(intent: Intent): ResolvedHelper? = firstHelper(matches(intent), forbidden)
        val roleRequest = try {
            context.getSystemService(android.app.role.RoleManager::class.java)
                ?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
        } catch (e: Exception) {
            null
        }
        val cellBroadcast = try {
            pm.queryBroadcastReceivers(Intent(android.provider.Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION), PackageManager.MATCH_SYSTEM_ONLY)
                .map { it.activityInfo.packageName }
                .sortedByDescending { it.contains("cellbroadcast") }
                .firstNotNullOfOrNull { firstHelper(listOfNotNull(info(it)), forbidden) }
        } catch (e: Exception) {
            null
        }
        // The "open with" screen: what an ambiguous implicit intent resolves to (qa-09-code #5).
        val resolver = listOf(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.org/")),
        ).firstNotNullOfOrNull { intent ->
            try {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                    ?.takeIf { it.name.contains("Resolver") }?.packageName
            } catch (e: Exception) {
                null
            }
        }
        val resolved = mapOf(
            HelperKind.EMERGENCY_DIALER to EmergencyDialer.ACTIONS.firstNotNullOfOrNull { activity(Intent(it)) },
            HelperKind.TELECOM to activity(Intent(Intent.ACTION_CALL, android.net.Uri.fromParts("tel", "112", null))),
            HelperKind.PERMISSION_CONTROLLER to (
                activity(Intent("android.content.pm.action.REQUEST_PERMISSIONS"))
                    ?: roleRequest?.let { activity(it) }
                ),
            HelperKind.CHOOSER to activity(Intent(Intent.ACTION_CHOOSER)),
            HelperKind.DOCUMENTS to activity(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")),
            HelperKind.PHOTO_PICKER to activity(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)),
            HelperKind.CELL_BROADCAST to cellBroadcast,
            HelperKind.RESOLVER to firstHelper(listOfNotNull(info(resolver)), forbidden),
            // Design 16d: the framework's Recents provider, never a fixed name, only when trusted
            // (systemRecentsPackage); `lockTaskHelpers` also drops it when forbidden or Play.
            HelperKind.RECENTS to systemRecentsPackage(context)?.let { ResolvedHelper(it, system = true) },
            HelperKind.EMERGENCY_SOS to info(emergencySosPackage(context)),
            HelperKind.CREDENTIAL_SELECTOR to info(credentialSelector(context))?.takeIf { it.packageName !in forbidden },
            // media-provider-helper: the provider's own package (its delete/trash/write confirmation), from the
            // platform's provider table; `lockTaskHelpers` keeps it only as a system app, never forbidden or Play.
            HelperKind.MEDIA_PROVIDER to info(mediaProviderPackage(pm)),
        )
        return resolved to forbidden
    }

    /** [HelperKind.MEDIA_PROVIDER]: the package holding `MediaStore.AUTHORITY`, system apps only; `null` when none
     * resolves or the lookup fails. */
    private fun mediaProviderPackage(pm: PackageManager): String? = try {
        pm.resolveContentProvider(
            android.provider.MediaStore.AUTHORITY,
            PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
        )?.packageName
    } catch (e: Exception) {
        null
    }

    /** The framework's two Credential Manager selector configs: the OEM's (Android 15+) and the platform's. */
    private val CREDENTIAL_CONFIGS = listOf("config_oemCredentialManagerDialogComponent", "config_credentialManagerDialogComponent")

    /** A framework string resource by name, `null` when the resource is missing or unreadable. */
    private fun systemString(name: String): String? = try {
        val res = android.content.res.Resources.getSystem()
        res.getIdentifier(name, "string", "android").takeIf { it != 0 }?.let { res.getString(it) }
    } catch (e: Exception) {
        null
    }

    /** [systemString] for the log, with why it is missing (passkeys on the Jelly Star, 2026-11-20). */
    private fun systemStringRaw(name: String): String = try {
        val res = android.content.res.Resources.getSystem()
        when (val id = res.getIdentifier(name, "string", "android")) {
            0 -> "<no resource>"
            else -> "\"${res.getString(id)}\""
        }
    } catch (e: Exception) {
        "<unreadable: ${e.javaClass.simpleName}>"
    }

    /** [HelperKind.CREDENTIAL_SELECTOR]: the framework's two config strings, read as AOSP's `IntentFactory` reads them,
     * with AOSP's own selector when neither names a usable component ([credentialSelectorPackage]). */
    private fun credentialSelector(context: Context): String? {
        val oem = systemString(CREDENTIAL_CONFIGS[0])?.takeIf { it.isNotBlank() }
        val oemEnabled = oem?.let { ComponentName.unflattenFromString(it) }?.let { component ->
            try {
                val pm = context.packageManager
                val declared = pm.getActivityInfo(component, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DISABLED_COMPONENTS).enabled
                when (pm.getComponentEnabledSetting(component)) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> declared
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        } ?: false
        return credentialSelectorPackage(oem, oemEnabled, systemString(CREDENTIAL_CONFIGS[1])) { pkg ->
            helperInfo(context, pkg)?.system == true
        }
    }

    /** See [EnforcementPlan.lockDateTime]. Automatic time is turned on first, so a clock that
     * was already wrong gets corrected before it's locked. */
    private fun applyDateTimeLock(dpm: DevicePolicyManager, admin: ComponentName, lock: Boolean) {
        if (lock) {
            try {
                dpm.setAutoTimeEnabled(admin, true)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to turn on automatic time", e)
            }
            // The rules run on the local zone too (QA step 6 #3).
            try {
                dpm.setAutoTimeZoneEnabled(admin, true)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to turn on automatic time zone", e)
            }
        }
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_DATE_TIME, lock)
    }

    private fun isHidden(dpm: DevicePolicyManager, admin: ComponentName, packageName: String): Boolean =
        try {
            dpm.isApplicationHidden(admin, packageName)
        } catch (e: Exception) {
            false
        }

    /**
     * Sets Android's always-on-VPN requirement on the launcher's own package via
     * [DevicePolicyManager.setAlwaysOnVpnPackage] - [KidVpnService] is now the device's only VPN
     * (the standalone Tailscale app and its managed-config/exit-node plumbing are retired; tsnet is
     * embedded directly, see [TsnetClient], and doesn't register as a VpnService at all). This is
     * what makes Android auto-start/restart the service as needed, independent of anything this app
     * does itself.
     *
     * Design 95 rev. 3: [MobileNetworkRuntime.writeAlwaysOn] is now the one writer ([alwaysOnFor]); lockdown is on
     * only in the sink shape (Bare Vibb on cellular), whose routes take everything. In the filter and pass shapes
     * lockdown stays off for the reason below.
     *
     * Lockdown is deliberately NOT enabled for the filter shape,
     * because it's actively wrong for this VPN's design, not just risky. Confirmed live: with
     * lockdown on, once this VPN becomes the system default network, `dumpsys connectivity` showed
     * its routes as only the one fake-DNS-server address plus an explicit `::/0 unreachable` -
     * [KidVpnService] deliberately never adds a general/default route (see that class's doc comment
     * on why: it's what makes the "everything else flows over the real network untouched" design
     * work at all when NOT locked down). Lockdown forces every app's traffic onto this network
     * regardless of what routes it declares, so with no default route to fall back to, general
     * internet connectivity broke device-wide - not a hypothetical, reproduced on the very first
     * live test of this code. This is the exact same failure mode as the old
     * Tailscale-without-an-exit-node bug (github.com/tailscale/tailscale#12925) that motivated
     * gating lockdown on an exit node being configured for that VPN - except here there's no
     * equivalent "configure a broader route" escape hatch to gate on, since narrow routing is
     * permanent by design, not a transient unconfigured state. Without lockdown, Android's
     * always-on designation still auto-restarts the service and still blocks a kid from disabling
     * it via Settings (both Device-Owner-enforced); the only thing lost is that DNS briefly goes
     * unfiltered through the OS's normal path if the service is ever down, which is an acceptable
     * gap next to bricking the device's entire network.
     *
     * [inputs]' `filterOn` is [PolicyResponse.vpnFilterEnabled] - a per-device admin toggle for the
     * filter itself (independent of the lockdown discussion above). When off, both the always-on
     * designation and the running service are torn down; when on, both are (re)established. Also
     * caches the value so [com.kidslauncher.mdm.Application.onCreate]'s cold-start
     * [KidVpnService.start] call - which runs before any policy has ever been fetched - knows
     * whether to start the service at all, rather than always starting and then immediately
     * stopping it again once this function runs on the first sync.
     */
    private fun applyVpnRestrictions(context: Context, inputs: VpnInputs, quickControlsMask: Long?, shape: VpnShape) {
        MobileNetworkRuntime.storeInputs(inputs, quickControlsMask)
        try {
            // The single owner (design 95 §2.2): the decided shape is recorded, always-on written for it alone - but
            // leaving the sink with the tunnel up, lockdown off is the tunnel switch's last step (design 120 fix A).
            val keepLockdown = applyLeavesLockdownToSwitch(shape, MobileNetworkRuntime.lockdownOurs(context), KidVpnService.liveTunnel().first)
            MobileNetworkRuntime.writeAlwaysOn(context, shape, keepLockdown)
            // Code QA L2: keep this start - the service's reshape() then writes its own decision last, so the two
            // writers of always-on settle on the service's view of the network.
            if (shape != VpnShape.NONE) KidVpnService.start(context) else KidVpnService.stop(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply the VPN's shape", e)
        }
    }

    /**
     * Locks Android's system-wide Private DNS to Opportunistic (never a specific host - the retired
     * DoT-to-Pi approach's `setGlobalPrivateDnsModeSpecifiedHost` call lived here previously, see
     * this repo's CLAUDE.md) and prevents it being switched away via
     * [UserManager.DISALLOW_CONFIG_PRIVATE_DNS]. Unconditional on every `apply()` call, not gated on
     * any policy field - closes the one gap [KidVpnService]'s DNS filtering can't otherwise cover on
     * its own: a kid manually switching Private DNS to Strict mode against some other resolver would
     * produce encrypted DoT traffic on port 853 that this app can't inspect, silently bypassing
     * filtering entirely. Opportunistic mode, by contrast, only *attempts* DoT and transparently
     * falls back to plain port-53 DNS if that fails - which is exactly what happens against
     * [KidVpnService]'s own fake DNS server, since it deliberately doesn't answer on port 853 (see
     * that class's doc comment on why it must stay DoT-silent for this to work).
     */
    private fun applyPrivateDnsLock(
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        try {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply Private DNS lock", e)
        }
    }

    /**
     * Blocks installing apps from outside the device's trusted app store (sideloaded APKs - e.g.
     * one downloaded via an allowlisted browser). Not gated on any policy field, but - unlike
     * [applyPrivateDnsLock], which this was originally modeled on - IS lifted while an offline
     * override or the pause-restrictions kill-switch is active, via [blockSideloading]. Confirmed
     * live this needed to be the standing rule for every restriction added here going forward, not
     * just this one: the whole point of those overrides is a guaranteed, fully-unblocked device
     * when something's wrong, and there's no such thing as a restriction a parent can't reach
     * through their own offline PIN if they need to. Doesn't affect the device's own app store
     * (Play Store / GrapheneOS's, if present) installing or updating apps, nor this app's own
     * Device-Owner `PackageInstaller`-based tracked-app pushes (see `AppInstaller.kt`) - both go
     * through a privileged install path this restriction doesn't gate at all, only the
     * user-facing "install unknown apps" permission a browser/file manager would otherwise need.
     * Sets both the plain and device-wide ("_GLOBALLY") restrictions together since the public
     * docs don't clearly distinguish which one a Device Owner on a single-user device (no separate
     * work profile) actually needs to enforce this - costs nothing to set both.
     *
     * Doesn't, on its own, stop a kid from *opening* an already-installed app that isn't on the
     * allowlist - that's [enforceOnNewPackage]'s job for anything installed after this policy
     * first applied, same as the regular suspend/hide loop above for anything already present
     * ([enforceOnNewPackage] already respects the same overrides independently, since it runs
     * outside this function entirely - see its own doc comment).
     */
    private fun applySideloadRestriction(dpm: DevicePolicyManager, admin: ComponentName, blockSideloading: Boolean) {
        setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, blockSideloading)
        setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, blockSideloading)
    }

    /**
     * Suspends/hides a single newly-installed package immediately if it's not on the current
     * allowlist, rather than waiting for the next full [apply] cycle (up to the 5-minute periodic-
     * sync backstop). Called from [com.kidslauncher.mdm.Application]'s `LauncherApps.Callback
     * .onPackageAdded`, which fires the instant Android finishes installing anything - on-device,
     * no network round-trip, regardless of whether the install came from an app store or (if
     * [applySideloadRestriction] hasn't been set, or the app was already present before it was) a
     * sideloaded APK. Deliberately narrower than a full [apply] pass: only this one package needs
     * checking, so there's no reason to re-touch every other controllable package's state on every
     * single install event.
     */
    fun enforceOnNewPackage(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        // The Play Store (re)installed or updated: the full plan decides its suspension.
        if (packageName == PLAY_STORE) {
            apply(context, currentPolicyDecision())
            return false
        }
        // Android Auto and the other Home apps: the full plan decides too (planOwnsAddedPackage).
        val pm = context.packageManager
        if (planOwnsAddedPackage(packageName, UpdateFence.homeCandidates(pm), context.packageName, controllablePackages(pm).toSet())) {
            apply(context, currentPolicyDecision())
            return false
        }

        // Fails closed: with no usable cached policy on a phone that has had one, the
        // last-enforced plan (or nothing allowed) decides - see shouldSuspendNewPackage.
        val decision = currentPolicyDecision()
        val overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()
        val lock = TimeRulesRuntime.currentLock(context, decision.policy, overrideActive)
        val relockBlocked = WindowRelock.blockedPackages(context)
        val suspend = shouldSuspendNewPackage(
            packageName = packageName,
            decision = decision,
            overrideActive = overrideActive,
            ownPackage = context.packageName,
            systemDialer = systemDialerPackage(context),
            scheduleLocked = lock.locked,
            lockUsableApps = lock.usableApps,
            recentsPackage = systemRecentsPackage(context),
            relockBlocked = relockBlocked,
            emergencySos = emergencySosPackage(context),
        )
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        // A newly installed app can't sneak in its own calls while calls are managed.
        CallPolicyStore.ensureLoaded(context)
        if (CallPolicyStore.callsState.managed) {
            try {
                val info = context.packageManager.getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
                val isSystem = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (!isSystem && packageName != context.packageName) {
                    val requested = info.requestedPermissions.orEmpty().toList()
                    for (permission in requested.filter { it in CALL_PERMISSIONS }) {
                        setGrantState(dpm, admin, packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
                    }
                    // Design 57 (d) at once for an install already allowlisted, by apply's rule and allowlist.
                    val allowlist = effectiveAllowlist(decision.policy?.allowlist, relockBlocked)
                    phoneStateGrants(mapOf(packageName to requested), context.packageName, allowlist, denyCalls = true)[packageName]
                        ?.let { setGrantState(dpm, admin, packageName, READ_PHONE_STATE, it) }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't check call permissions of $packageName", e)
            }
        }
        // Design 68, 127: the access rules for this package, managed calls or not, before it can be hidden below.
        enforceAccessOnPackage(context, packageName)
        if (!suspend) return false
        // Design 102 §4.1: Chrome unhidden for its login role is the apply's own doing, not a new app - left to the plan
        // (the emulator check, 2026-11-15: the unhide's onPackageAdded hid it again and asked a sync, every 0.4 s).
        val loginRole = com.kidslauncher.mdm.logins.loginRoleOf(
            decision.policy?.loginBrowser,
            effectiveAllowlist(decision.policy?.allowlist, relockBlocked),
        )
        // Design 119 §4.2: outside a login window that Chrome is hidden again here, under the window's lock (M3).
        if (packageName == com.kidslauncher.mdm.logins.CHROME_PACKAGE && BrowserConfig.roleReady(context, loginRole)) {
            com.kidslauncher.mdm.logins.LoginWindow.chromeAppeared(
                context, dpm, ComponentName(context, MdmDeviceAdminReceiver::class.java),
                updateWindow = PlayRuntime.updateWindowActive(context),
            )
            return false
        }
        // Hidden only if not allowed at all - the schedule lock alone just suspends.
        val hide = shouldSuspendNewPackage(
            packageName, decision, overrideActive, context.packageName, systemDialerPackage(context),
            recentsPackage = systemRecentsPackage(context), relockBlocked = relockBlocked,
            emergencySos = emergencySosPackage(context),
        )

        try {
            val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            if (!notSuspended.isNullOrEmpty()) {
                Log.w(LOG_TAG, "Platform refused to suspend newly-installed $packageName")
            }
            val hiddenOk = !hide || dpm.setApplicationHidden(admin, packageName, true)
            if (!hiddenOk) {
                Log.w(LOG_TAG, "Platform refused to hide newly-installed $packageName")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to suspend newly-installed $packageName", e)
        }
        return true
    }

    private fun setRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String,
        enabled: Boolean,
    ) {
        try {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction)
            } else {
                dpm.clearUserRestriction(admin, restriction)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to ${if (enabled) "add" else "clear"} restriction $restriction", e)
        }
    }

    /**
     * The regular "set as default launcher" flow ([com.kidslauncher.mdm.setDefaultHomeScreen]) is
     * just a user-revocable preference - a reinstall/reboot race, or a kid holding down the home
     * button, can fall back to another HOME-capable app (e.g. the OS's own stock launcher) if one
     * is installed. As device owner we can pin this unconditionally instead.
     */
    /**
     * `market://` and `https://play.google.com/store/...` links open [PlayLinkBlockedActivity]
     * (handy step 7, §4): persistent preferred activities, set on every [apply] and cleared only by `HomePin`'s
     * re-seat, which adds every PPA of ours back in the same pass (design 59d step M, `PlayInvariantsTest`) - any
     * other clear would drop the HOME pin too (QA #2). The activity declares the same filters
     * (AOSP ignores a preferred activity that doesn't match its own filters) and passes the link
     * on to Play whenever Play isn't suspended. Explicit intents bypass this; the Play Store's
     * suspension covers them.
     */
    private fun enforcePlayLinkBlocker(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        val blocker = ComponentName(context, PlayLinkBlockedActivity::class.java)
        val market = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("market")
        }
        val web = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
            addDataAuthority("play.google.com", null)
            addDataPath("/store", android.os.PatternMatcher.PATTERN_PREFIX)
        }
        for (filter in listOf(market, web)) {
            try {
                dpm.addPersistentPreferredActivity(admin, filter, blocker)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to set the Play link blocker", e)
            }
        }
    }

    /**
     * `VIEW vnd.android.cursor.dir/calls` - the call log, which Telecom's own missed-call
     * notification and other apps open - goes to the phone book (design 12) through its look-less entry
     * [PhoneBookEntryActivity] (2026-11-16: the page itself is two classes, one per look): the system
     * dialer's call log is its full UI with a keypad, and a chooser would be just as bad. Set on
     * every [apply] next to the Play link blocker and cleared only by `HomePin`'s re-seat, which adds every PPA of
     * ours back in the same pass (design 59d step M, `PlayInvariantsTest`); the entry declares the same filter and passes the intent on to the
     * system dialer while calls are unmanaged. An older build's pin on `PhoneBookActivity` matches nothing any more (the
     * page has no filters), so Android skips it; on 16 the policy engine replaced it with this one (seen on the emulator). A privileged dialer whose call-log filter has a
     * priority above 0 still wins (PackageManager picks it before reading persistent preferred
     * activities) - device check on the Jelly Star.
     */
    private fun enforceCallLogPin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        try {
            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType(CALL_LOG_TYPE)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, PhoneBookEntryActivity::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the call-log pin", e)
        }
    }

    /**
     * Design 59d step M, for `lock/HomePin` only (it holds its own lock and adds the boot cover's PPA last): our
     * package's PPAs cleared and added back in order - HomeActivity's HOME first, then the Play link blocker, the
     * call-log pin, the captive portal's, the UnifiedPush link's (design 108), the map handler's (design 110) and the link
     * gate's last (design 119 L4: a catch-all added first could shadow the others). Not `@Synchronized` (the take-over calls it on the main thread while an apply may run). Never throws.
     */
    internal fun reseatPreferredActivities(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to clear our persistent preferred activities", e)
        }
        enforceDefaultHome(dpm, admin, context)
        enforcePlayLinkBlocker(dpm, admin, context)
        enforceCallLogPin(dpm, admin, context)
        enforceCaptivePortalPin(dpm, admin, context)
        enforceUpLinkPin(dpm, admin, context)
        enforceMapLinkPin(dpm, admin, context)
        enforceLinkGatePin(dpm, admin, context)
        enforceBlockedNumbersPin(dpm, admin, context)
    }

    /**
     * Design 102 §3.2 (Q102-M5): `android.net.conn.CAPTIVE_PORTAL` goes to [com.kidslauncher.mdm.portal.CaptivePortalEntry]
     * - set on every [apply] and in `HomePin`'s re-seat with the other PPAs of ours, so a re-seat never leaves a moment
     * where Android shows its chooser (the kid could pick the stock login app, which is unfiltered, M5-M6). Whether it
     * is used is the entry's enabled state (Part A's switch); the stock login app is never pinned in the kiosk.
     */
    private fun enforceCaptivePortalPin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        try {
            val filter = IntentFilter(android.net.ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, com.kidslauncher.mdm.portal.CaptivePortalEntry::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the Wi-Fi sign-in's handler", e)
        }
    }

    /**
     * Design 108 §5.2 (L3): `VIEW unifiedpush://link` goes to [com.kidslauncher.mdm.push.up.UpLinkActivity] - set on
     * every [apply] and in `HomePin`'s re-seat with the other PPAs of ours (the fifth: HOME, the Play link blocker, the
     * call-log pin, the captive portal, this one; the boot cover's is `HomePin`'s own, added last), so a re-seat never
     * leaves a moment where Android shows its chooser and the kid could pick another distributor. Whether it is used is
     * the activity's enabled state: with UP off it is disabled and the link resolves as if Vibb weren't there.
     */
    private fun enforceUpLinkPin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        try {
            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme(com.kidslauncher.mdm.push.up.UpSpec.LINK_SCHEME)
                addDataAuthority(com.kidslauncher.mdm.push.up.UpSpec.LINK_HOST, null)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, com.kidslauncher.mdm.push.up.UpLinkActivity::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the UnifiedPush link's handler", e)
        }
    }

    /**
     * Design 110 §6.2: Google Maps and Apple Maps links go to [com.kidslauncher.mdm.map.MapLinkActivity] - two filters
     * (every path on the map hosts, `/maps…` on the general ones; [com.kidslauncher.mdm.map.MapLinksRuntime.preferredFilters]),
     * set on every [apply] and in `HomePin`'s re-seat with the other PPAs of ours (the sixth). A PPA can't beat an app
     * Android approved for the host (Play services' system-configured short links, a verified Google Maps), which is
     * what the two steps on the phone are for. With the switch off the handler is disabled and the links resolve as if
     * Vibb weren't there.
     */
    private fun enforceMapLinkPin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        val handler = com.kidslauncher.mdm.map.MapLinksRuntime.component(context)
        for (filter in com.kidslauncher.mdm.map.MapLinksRuntime.preferredFilters()) {
            try {
                dpm.addPersistentPreferredActivity(admin, filter, handler)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to set the map links' handler", e)
            }
        }
    }

    /**
     * Design 119 §4.1 (L4): `VIEW` http and https with no host go to [com.kidslauncher.mdm.logins.LinkGateActivity] - set
     * on every [apply] and last in `HomePin`'s re-seat (the seventh): Android keeps insertion order among equal
     * priorities, so it goes after the Play link blocker's and the map handler's, which it could shadow otherwise. It
     * matters only in a pause with no browser-role holder; it can't beat an app Android approved for a host. Whether it is
     * used is the gate's enabled state ([com.kidslauncher.mdm.logins.LinkGateRuntime.afterLoop]).
     */
    private fun enforceLinkGatePin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        try {
            dpm.addPersistentPreferredActivity(
                admin, com.kidslauncher.mdm.logins.LinkGateRuntime.preferredFilter(),
                com.kidslauncher.mdm.logins.LinkGateRuntime.component(context),
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the link gate's handler", e)
        }
    }

    /**
     * `android.telecom.action.MANAGE_BLOCKED_NUMBERS` goes to [com.kidslauncher.mdm.calls.BlockedNumbersGate] - set on
     * every [apply] and in `HomePin`'s re-seat with the other PPAs of ours (the eighth; it shares no filter with them).
     * While managed the gate is enabled and closes at once; disabled while unmanaged, Android skips the pin.
     */
    private fun enforceBlockedNumbersPin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        try {
            val filter = IntentFilter(MANAGE_BLOCKED_NUMBERS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, com.kidslauncher.mdm.calls.BlockedNumbersGate::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the blocked numbers' handler", e)
        }
    }

    private fun enforceDefaultHome(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        try {
            dpm.addPersistentPreferredActivity(
                admin, filter, ComponentName(context, HomeActivity::class.java)
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set persistent preferred HOME activity", e)
        }
    }
}
