package com.kidslauncher.mdm.server

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.provider.AlarmClock
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
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.RoleAction
import com.kidslauncher.mdm.calls.RoleSnapshot
import com.kidslauncher.mdm.calls.dialerRoleAction
import com.kidslauncher.mdm.calls.roleReportNeeded
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.calls.lockDefaultApps
import com.kidslauncher.mdm.calls.managed
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
 * The package of the system's Recents activity (`config_recentsComponentName`, e.g. Pixel's
 * quickstep inside the stock launcher), for [kioskFeatures] (design 16, QA #5(b)). `null` when the
 * platform doesn't say - then OVERVIEW is dropped with the app block.
 */
internal fun recentsPackage(): String? = try {
    val res = android.content.res.Resources.getSystem()
    val id = res.getIdentifier("config_recentsComponentName", "string", "android")
    if (id == 0) null else ComponentName.unflattenFromString(res.getString(id))?.packageName
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read the recents component", e)
    null
}

/**
 * The default alarm/clock app: the one holding the next alarm, else the resolver of
 * `AlarmClock.ACTION_SHOW_ALARMS`. The schedule lock doesn't suspend it, so an alarm set inside
 * bedtime still rings (QA step 4 #2). `null` if there's none or only a chooser.
 */
internal fun alarmAppPackage(context: Context): String? = try {
    val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent?.creatorPackage
    next ?: context.packageManager
        .resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName?.takeIf { it != "android" }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't look up the alarm app", e)
    null
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

    /** The packages the last [apply] in this process wanted suspended (`null` = none ran yet) -
     * the PIN lock's camera release never lifts one of these (CameraLock). */
    @Volatile
    var lastPlanSuspend: Set<String>? = null
        private set

    /**
     * Synchronized: the sync, the pause switch, the offline override and the schedule re-check
     * ([reevaluateLockReasonFromCache]) can all call this from different threads, and two passes
     * interleaving their suspend/unsuspend loops could leave a mix of both. Never call it on the
     * main thread (it can wait for a running pass, and starting the VPN reads files).
     */
    @Synchronized
    fun apply(context: Context, policy: PolicyResponse?, fromSync: Boolean = false) {
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
        // Never lifted (QA #2) - the blocker passes links on to Play whenever Play is open.
        enforcePlayLinkBlocker(dpm, admin, context)
        // The call log opens the phone book (design 12), never lifted either.
        enforceCallLogPin(dpm, admin, context)

        // Hardening that the server switched off is cleared first, before anything below can
        // throw - "Block USB debugging" off is how a parent gets adb back (QA step 4 #8). The
        // restrictions that stay on are (re)set at the end, after the always-on VPN.
        CallPolicyStore.ensureLoaded(context)
        val managedForHardening = hardeningManaged(policy?.allowlist, CallPolicyStore.state.managed)
        val hardening = hardeningPlan(policy?.hardening, managedForHardening)
        clearHardening(context, dpm, admin, hardening)
        // Android's backup to Google stays off while managed (google-account runbook) - early, so
        // nothing that throws below can skip it. Like the hardening, never lifted by the override
        // or the pause, and nothing ever switches it on. Catches and logs everything itself.
        BackupService.enforce(dpm, admin, managedForHardening)

        // Calls: never lifted by an override or pause. The dialer role first - whether our dialer
        // is in place decides the outgoing-call restriction below.
        CallPolicyStore.ensureLoaded(context)
        val callState = CallPolicyStore.state
        applyDialerRole(context, dpm, admin, callState)
        // Inside a sync the status report right after this tells the server anyway (qa-09-code #10).
        if (!fromSync) signalRoleChange(context)

        // A time rule or the used-up budget: suspend everything but our own package, the system
        // dialer and the lock's usable apps (see computeEnforcementPlan), from the same decision
        // the lock screen uses. A rule without calls (school) also turns managed calls off.
        val lock = TimeRulesRuntime.currentLock(context, policy, overrideActive)
        val scheduleLocked = lock.locked
        val timePolicy = KidModeEnforcer.timePolicyOf(policy)

        // The update fence (step 11): the one release rule runs on every pass, and while the fence
        // is up the packages it holds are never unsuspended here (qa-11-design.md #5).
        val fenceHeld = UpdateFence.duringApply(context, policy, managed = policy?.allowlist != null || callState.managed)

        val ownPackage = context.packageName
        val pm = context.packageManager
        val installedPackages = controllablePackages(pm)
        var playState = PlayRuntime.state(context)
        if (playState.installMode && scheduleLocked) {
            // A time rule or the used-up budget began during install mode: it ends (QA #3).
            PlayRuntime.cancelInstallMode(context)
            playState = playState.copy(installMode = false)
        }
        val plan = computeEnforcementPlan(
            allowlist = policy?.allowlist,
            kioskDesired = policy?.kioskDesired == true,
            serverLockTaskFeatures = policy?.lockTaskFeatures ?: 0,
            overrideActive = overrideActive,
            controllable = installedPackages,
            ownPackage = ownPackage,
            systemDialer = systemDialerPackage(context),
            callState = withTimeRule(callState, lock.callsBlocked),
            ourDialerActive = CallSystem.dialerRoleHeld(context),
            smsPackages = smsPackages(CallSystem.defaultSmsPackage(context)),
            scheduleLocked = scheduleLocked,
            alarmApp = alarmAppPackage(context),
            inputMethods = inputMethodPackages(context),
            lockUsableApps = lock.usableApps,
            ruleBlocksCalls = lock.callsBlocked,
            timeRulesSet = timePolicy != null && (timePolicy.rules.isNotEmpty() || hasBudget(timePolicy)),
            budgetSet = timePolicy != null && hasBudget(timePolicy),
            playState = playState,
            blockActivityStart = policy?.blockActivityStart == true,
            lockTaskHelpers = if (policy?.blockActivityStart == true) resolveLockTaskHelpers(context) else emptySet(),
            recentsPackage = recentsPackage(),
        )

        // Set before the loop below can release the dialer, so its keypad is never usable for
        // ordinary numbers in between; with calls managed it's lifted only while our own dialer
        // (redirection + in-call services) screens outgoing calls - see
        // EnforcementPlan.restrictOutgoingCalls. Emergency calls are exempt from this restriction.
        setRestriction(dpm, admin, UserManager.DISALLOW_OUTGOING_CALLS, plan.restrictOutgoingCalls)
        // SMS off (or rules unknown): no SMS in or out. The SMS apps are suspended by the plan too,
        // for RCS. Not lifted by an override.
        setRestriction(dpm, admin, UserManager.DISALLOW_SMS, plan.restrictSms)
        applyCallPermissions(context, dpm, admin, plan.denyCallPermissions)

        lastPlanSuspend = plan.suspend
        for (packageName in installedPackages) {
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
            val shouldBeSuspended = suspendTarget(packageName, plan.suspend, fenceHeld + com.kidslauncher.mdm.lock.CameraLock.held)
            // Checked separately, every cycle: the two states can disagree (an older build, a
            // policy applied before an exemption, the lock ending), e.g. the system dialer left
            // hidden must be released outright.
            val currentlyHidden = isHidden(dpm, admin, packageName)
            if (shouldBeSuspended == currentlySuspended && shouldBeHidden == currentlyHidden) continue

            try {
                // Both calls can fail *without* throwing - setPackagesSuspended returns the
                // subset of package names it couldn't act on (some privileged/protected system
                // packages silently refuse suspension by platform policy) and
                // setApplicationHidden returns a plain boolean. Discarding these previously made
                // that failure mode invisible - confirmed live with a carrier-privileged /product-
                // partition system app that stayed reachable despite being correctly excluded
                // from the allowlist, with nothing in logs to explain why.
                if (shouldBeSuspended != currentlySuspended) {
                    val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)
                    if (!notSuspended.isNullOrEmpty()) {
                        Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName (setPackagesSuspended)")
                    }
                    // The Play Store is the package verifier on GMS phones and can't be suspended
                    // there (B4) - reported, so the server can say Play is blocked only in kiosk.
                    if (packageName == PLAY_STORE && shouldBeSuspended) {
                        PlayRuntime.recordStoreSuspendable(context, notSuspended.isNullOrEmpty())
                    }
                }
                if (shouldBeHidden != currentlyHidden) {
                    val hiddenOk = dpm.setApplicationHidden(admin, packageName, shouldBeHidden)
                    if (!hiddenOk) {
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
        lastEnforcedLockKey = lock.key()
        lastEnforcedPlayState = playState
        // The PIN lock's camera targets for this phone now (no PackageManager work at screen-off).
        try {
            com.kidslauncher.mdm.lock.CameraLock.refreshTargets(context, installedPackages.toSet())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Camera lock targets failed", e)
        }

        // Handy's PIN lock (step 10) first - whether it is LOCKED changes the lock-task setting
        // below (featuresWhileLocked / lockTaskWhileLocked), so a sync can't undo the lock.
        val managed = policy?.allowlist != null || callState.managed
        com.kidslauncher.mdm.lock.PinLockRuntime.configure(context, dpm, admin, policy, managed)
        // Kiosk pinning, lock-task features, the status bar backstop and DISALLOW_CREATE_WINDOWS
        // (budget, or the PIN lock) - one place shared with the lock's fast path.
        com.kidslauncher.mdm.lock.LockTaskChrome.applyPlan(
            context, plan.kioskPackages, plan.lockTaskFeatures, plan.restrictCreateWindows,
            pinLockHelpers = { resolvePinLockHelpers(context) },
        )

        applyKeyguardFeatures(dpm, admin, managed = managed)

        // Auto-lock: the parent's screen timeout (emulator run 2026-10-06), lifted by the override.
        ScreenTimeout.apply(
            context, dpm, admin,
            screenTimeoutAction(
                hardeningManaged(policy?.allowlist, callState.managed), policy?.screenTimeoutSeconds, overrideActive,
            ),
        )

        applyDateTimeLock(dpm, admin, plan.lockDateTime)

        clearRadioRestrictions(dpm, admin)

        // Same "fully open" treatment as everything else while an override is active - confirmed
        // live this needs to include the VPN filter too: the whole point of the offline-override PIN
        // and the pause-restrictions kill-switch is a guaranteed working, unblocked device when
        // something's wrong, which can include the VPN/filter itself misbehaving. An earlier version
        // of this deliberately excluded vpnFilterEnabled from that (reasoning: an emergency escape
        // hatch for access restrictions shouldn't silently override a parent's separate content-
        // filtering choice) - wrong in practice, reverted. Only true-by-default (filtering on) when
        // no override is active AND no policy has ever been fetched, which has no admin choice yet
        // to respect.
        val vpnFilterEnabled = if (overrideActive) false else (policy?.vpnFilterEnabled ?: true)
        applyVpnRestrictions(context, dpm, admin, vpnFilterEnabled)

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
            alarmAppPackage(context),
            CallSystem.defaultSmsPackage(context).takeIf { smsOn },
        ) + cellBroadcast + inputMethodPackages(context)
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
            if (set) setRestriction(dpm, admin, restriction.userManagerKey(), true)
        }
        clearHardening(context, dpm, admin, plan)
    }

    /**
     * Lifts the restrictions [plan] has off, in [hardeningClearSteps] order, each step on its
     * own (one failing doesn't stop the rest). `DISALLOW_SET_WALLPAPER` is only lifted once
     * nothing of ours can show on the system wallpaper any more (navy back, or cleared) - else it
     * stays until a later pass manages the reset (qa-08-code.md #1, QA 08 #2).
     */
    private fun clearHardening(context: Context, dpm: DevicePolicyManager, admin: ComponentName, plan: HardeningPlan) {
        var wallpaperClean = true
        for (step in hardeningClearSteps(plan)) {
            try {
                when (step) {
                    ClearStep.ResetWallpaper -> wallpaperClean = WallpaperApplier.resetIfOurs(context)
                    is ClearStep.Clear -> {
                        if (step.restriction == HardeningRestriction.SET_WALLPAPER && !wallpaperClean) {
                            Log.w(LOG_TAG, "Our wallpaper couldn't be reset - DISALLOW_SET_WALLPAPER stays for now")
                        } else {
                            setRestriction(dpm, admin, step.restriction.userManagerKey(), false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Clearing hardening step $step failed", e)
            }
        }
    }

    private fun HardeningRestriction.userManagerKey(): String = when (this) {
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
    }

    /**
     * While calls are managed: our own call permissions are self-granted (READ_CONTACTS so
     * screening sees every number, CALL_PHONE for the phone book, READ_PHONE_STATE, READ_CALL_LOG
     * for the callback window - the dialer role grants most of them too), and every third-party
     * app's CALL_PHONE/ANSWER_PHONE_CALLS is denied ("blocked by admin"). Unmanaged, those go back
     * to DEFAULT. Grant states are only written when they differ (re-setting re-notifies, see
     * CLAUDE.md).
     */
    private fun applyCallPermissions(context: Context, dpm: DevicePolicyManager, admin: ComponentName, deny: Boolean) {
        if (deny) {
            for (permission in OWN_CALL_PERMISSIONS) {
                QuickControls.selfGrantPermission(context, dpm, admin, permission)
            }
        }
        val requested = try {
            context.packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong())
            ).filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0 }
                .associate { it.packageName to it.requestedPermissions.orEmpty().toList() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't list installed packages for call permissions", e)
            return
        }
        for (packageName in callPermissionTargets(requested, context.packageName)) {
            setCallPermissions(dpm, admin, packageName, requested[packageName].orEmpty(), deny)
        }
    }

    private fun setCallPermissions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        requested: Collection<String>,
        deny: Boolean,
    ) {
        val wanted = if (deny) DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        for (permission in requested.filter { it in CALL_PERMISSIONS }) {
            try {
                if (dpm.getPermissionGrantState(admin, packageName, permission) != wanted) {
                    dpm.setPermissionGrantState(admin, packageName, permission, wanted)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't set $permission for $packageName", e)
            }
        }
    }

    /**
     * Makes us the default dialer while calls are managed ([dialerRoleAction]):
     * `DevicePolicyManager.setDefaultDialerApplication` (device owner, no prompt). If that throws,
     * the error is reported to the server and HomeActivity falls back to the system's role
     * prompt once a day ([com.kidslauncher.mdm.calls.shouldPromptForRole]). When calls are not
     * managed (an explicit `managed: false`, or an old server on a phone whose calls never were),
     * a role we took is handed back to the system dialer, so the phone is never left with our
     * dialer and no rules.
     */
    private fun applyDialerRole(context: Context, dpm: DevicePolicyManager, admin: ComponentName, state: CallPolicyState) {
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
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_DEFAULT_APPS, lockDefaultApps(state, CallSystem.dialerRoleHeld(context)))
        val heldAfter = CallSystem.dialerRoleHeld(context)
        // Handed back: our fixed permissions return to DEFAULT (no revoke, no kill) - qa-09-code #4.
        if (shouldResetOwnPermissions(state.managed, heldAfter, CallPrefs.ownPermissionsFixed(context))) {
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

    /** Any call at all; unknown counts as "in a call" (then Home stays where it is). */
    private fun inCall(context: Context): Boolean = try {
        context.getSystemService(TelecomManager::class.java)?.isInCall != false
    } catch (e: Exception) {
        true
    }

    /** A typed HOME start (design 16, QA #1), never an explicit component. */
    private fun bringHomeToFront(context: Context) {
        com.kidslauncher.mdm.lock.HomeFront.bring(context, "a role change")
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
        val now = RoleSnapshot(CallSystem.dialerRoleHeld(context), CallSystem.redirectionRoleHeld(context))
        if (!roleReportNeeded(CallPrefs.rolesSignalled(context), now)) return
        CallPrefs.rolesSignalled(context, now)
        try {
            SyncRunner.request(context, "role-changed")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't request a sync after a role change", e)
        }
    }

    /** See [keyguardDisabledFeatures] - set only when it differs. */
    private fun applyKeyguardFeatures(dpm: DevicePolicyManager, admin: ComponentName, managed: Boolean) {
        try {
            val current = dpm.getKeyguardDisabledFeatures(admin)
            val wanted = keyguardDisabledFeatures(current, managed)
            if (wanted != current) dpm.setKeyguardDisabledFeatures(admin, wanted)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the keyguard camera feature", e)
        }
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
        Log.i(LOG_TAG, "Kiosk app block helpers: $resolved -> $helpers (forbidden $forbidden)")
        return helpers
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
            alarmApp = helperInfo(context, alarmAppPackage(context)),
            forbidden = forbidden,
            ourDialerHeld = CallSystem.dialerRoleHeld(context),
        )
        Log.i(LOG_TAG, "PIN lock helpers (kiosk off): $helpers")
        return helpers
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
        )
        return resolved to forbidden
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
     * WiFi/Bluetooth restriction *levels* (open/restricted/disabled, independent of the always-on
     * kiosk allowlist) were retired as an admin-configurable policy - confirmed in practice not
     * worth the UI complexity. This unconditionally clears the four restrictions that feature used
     * to set, every `apply()` cycle same as before, rather than just deleting the code outright -
     * a device that already had "restricted" or "disabled" saved from before this change needs
     * those actively lifted, not just abandoned in whatever state they were last left in.
     */
    private fun clearRadioRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        setRestriction(dpm, admin, UserManager.DISALLOW_CHANGE_WIFI_STATE, false)
        setRestriction(dpm, admin, UserManager.DISALLOW_ADD_WIFI_CONFIG, false)
        setRestriction(dpm, admin, UserManager.DISALLOW_BLUETOOTH, false)
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_BLUETOOTH, false)
    }

    /**
     * Sets Android's always-on-VPN requirement on the launcher's own package via
     * [DevicePolicyManager.setAlwaysOnVpnPackage] - [KidVpnService] is now the device's only VPN
     * (the standalone Tailscale app and its managed-config/exit-node plumbing are retired; tsnet is
     * embedded directly, see [TsnetClient], and doesn't register as a VpnService at all). This is
     * what makes Android auto-start/restart the service as needed, independent of anything this app
     * does itself.
     *
     * Lockdown is deliberately NOT enabled here - unconditional, not gated on any policy field,
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
     * [vpnFilterEnabled] is [PolicyResponse.vpnFilterEnabled] - a per-device admin toggle for the
     * filter itself (independent of the lockdown discussion above). When off, both the always-on
     * designation and the running service are torn down; when on, both are (re)established. Also
     * caches the value so [com.kidslauncher.mdm.Application.onCreate]'s cold-start
     * [KidVpnService.start] call - which runs before any policy has ever been fetched - knows
     * whether to start the service at all, rather than always starting and then immediately
     * stopping it again once this function runs on the first sync.
     */
    private fun applyVpnRestrictions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        vpnFilterEnabled: Boolean,
    ) {
        LauncherPreferences.mdm().vpnFilterEnabled(vpnFilterEnabled)
        try {
            if (vpnFilterEnabled) {
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
                KidVpnService.start(context)
            } else {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
                KidVpnService.stop(context)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply VPN filter enabled state", e)
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
            apply(context, currentPolicyDecision().policy)
            return false
        }

        // Fails closed: with no usable cached policy on a phone that has had one, the
        // last-enforced plan (or nothing allowed) decides - see shouldSuspendNewPackage.
        val decision = currentPolicyDecision()
        val overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()
        val lock = TimeRulesRuntime.currentLock(context, decision.policy, overrideActive)
        val suspend = shouldSuspendNewPackage(
            packageName = packageName,
            decision = decision,
            overrideActive = overrideActive,
            ownPackage = context.packageName,
            systemDialer = systemDialerPackage(context),
            scheduleLocked = lock.locked,
            lockUsableApps = lock.usableApps,
        )
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        // A newly installed app can't sneak in its own calls while calls are managed.
        CallPolicyStore.ensureLoaded(context)
        if (CallPolicyStore.state.managed) {
            try {
                val info = context.packageManager.getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
                val isSystem = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (!isSystem && packageName != context.packageName) {
                    setCallPermissions(dpm, admin, packageName, info.requestedPermissions.orEmpty().toList(), deny = true)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't check call permissions of $packageName", e)
            }
        }
        if (!suspend) return false
        // Hidden only if not allowed at all - the schedule lock alone just suspends.
        val hide = shouldSuspendNewPackage(packageName, decision, overrideActive, context.packageName, systemDialerPackage(context))

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
     * (handy step 7, §4): persistent preferred activities, set on every [apply] and never cleared
     * - clearing ours would drop the HOME pin too (QA #2). The activity declares the same filters
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
     * notification and other apps open - goes to [PhoneBookActivity] (design 12): the system
     * dialer's call log is its full UI with a keypad, and a chooser would be just as bad. Set on
     * every [apply] next to the Play link blocker and never cleared (that would drop the HOME pin,
     * `PlayInvariantsTest`); the activity declares the same filter and passes the intent on to the
     * system dialer while calls are unmanaged. A privileged dialer whose call-log filter has a
     * priority above 0 still wins (PackageManager picks it before reading persistent preferred
     * activities) - device check on the Jelly Star.
     */
    private fun enforceCallLogPin(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        try {
            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType(CALL_LOG_TYPE)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, PhoneBookActivity::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set the call-log pin", e)
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
