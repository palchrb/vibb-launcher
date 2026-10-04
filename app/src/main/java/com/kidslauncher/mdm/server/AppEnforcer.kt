package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.telecom.TelecomManager
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallPrefs
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.RoleAction
import com.kidslauncher.mdm.calls.dialerRoleAction
import com.kidslauncher.mdm.calls.lockDefaultApps
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.HomeActivity

private const val LOG_TAG = "AppEnforcer"

/** Self-granted while calls are managed - see [AppEnforcer.applyCallPermissions]. */
private val OWN_CALL_PERMISSIONS = listOf(
    android.Manifest.permission.READ_CONTACTS,
    android.Manifest.permission.CALL_PHONE,
    android.Manifest.permission.READ_PHONE_STATE,
    android.Manifest.permission.READ_CALL_LOG,
)

/** applicationId of the kids-mdm-browser fork - see [AppEnforcer.applyBrowserPolicy]. */
private const val BROWSER_PACKAGE_NAME = "com.kidsmdm.browser"

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

    fun apply(context: Context, policy: PolicyResponse?) {
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

        // Calls: never lifted by an override or pause. The dialer role first - whether our dialer
        // is in place decides the outgoing-call restriction below.
        CallPolicyStore.ensureLoaded(context)
        val callState = CallPolicyStore.state
        applyDialerRole(context, dpm, admin, callState)

        val ownPackage = context.packageName
        val pm = context.packageManager
        val installedPackages = controllablePackages(pm)
        val plan = computeEnforcementPlan(
            allowlist = policy?.allowlist,
            kioskDesired = policy?.kioskDesired == true,
            serverLockTaskFeatures = policy?.lockTaskFeatures ?: 0,
            overrideActive = overrideActive,
            controllable = installedPackages,
            ownPackage = ownPackage,
            systemDialer = systemDialerPackage(context),
            callState = callState,
            ourDialerActive = CallSystem.dialerRoleHeld(context),
            smsPackages = smsPackages(CallSystem.defaultSmsPackage(context)),
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

        for (packageName in installedPackages) {
            if (packageName == ownPackage) continue

            val shouldBeSuspended = packageName in plan.suspend
            val currentlySuspended = try {
                pm.isPackageSuspended(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            // The system dialer may still be hidden or suspended from an older build (or from a
            // policy applied before it was exempt) - release it outright rather than trusting
            // that its suspended and hidden states agree.
            val mustRelease = packageName in plan.neverRestrict &&
                (currentlySuspended || isHidden(dpm, admin, packageName))
            if (shouldBeSuspended == currentlySuspended && !mustRelease) continue

            try {
                // Both calls can fail *without* throwing - setPackagesSuspended returns the
                // subset of package names it couldn't act on (some privileged/protected system
                // packages silently refuse suspension by platform policy) and
                // setApplicationHidden returns a plain boolean. Discarding these previously made
                // that failure mode invisible - confirmed live with a carrier-privileged /product-
                // partition system app that stayed reachable despite being correctly excluded
                // from the allowlist, with nothing in logs to explain why.
                val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)
                if (!notSuspended.isNullOrEmpty()) {
                    Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName (setPackagesSuspended)")
                }
                val hiddenOk = dpm.setApplicationHidden(admin, packageName, shouldBeSuspended)
                if (!hiddenOk) {
                    Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "hide" else "unhide"} $packageName (setApplicationHidden)")
                }
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Failed to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName",
                    e
                )
            }
        }

        applyKioskState(dpm, admin, plan.kioskPackages, plan.lockTaskFeatures)

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

        applyBrowserPolicy(dpm, admin, context, locked = !overrideActive)
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
        val held = CallSystem.dialerRoleHeld(context)
        val action = dialerRoleAction(state, held, CallPrefs.dialerRoleTakenByUs(context))
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
                    CallPrefs.lastError(context, null)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Handing the dialer role back to $systemDialer failed", e)
                    CallPrefs.lastError(context, "release dialer role: ${e.javaClass.simpleName}: ${e.message}".take(300))
                }
            }
            RoleAction.NONE -> if (held || !state.managed) CallPrefs.lastError(context, null)
        }
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_DEFAULT_APPS, lockDefaultApps(state, CallSystem.dialerRoleHeld(context)))
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
     * Pins [kioskPackages] (from [computeEnforcementPlan]: the allowlist plus our own package,
     * only when the server wants kiosk mode), or unpins when it's `null`. An empty allowlist pins
     * only our own package - safe, since it's HOME and holds LockActivity/Settings. There is no
     * on-device switch for this; the admin site is the only source of truth.
     */
    private fun applyKioskState(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        kioskPackages: Set<String>?,
        lockTaskFeatures: Int,
    ) {
        val mdm = LauncherPreferences.mdm()

        if (kioskPackages == null) {
            try {
                dpm.setLockTaskPackages(admin, emptyArray())
                mdm.kioskEnabled(false)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to unpin kiosk state", e)
            }
            return
        }

        // Order matters here, and it didn't before: setLockTaskFeatures (which carries the
        // keyguard-safe bit) must be verified *before* setLockTaskPackages ever pins the device,
        // and a failure here must skip pinning entirely rather than continue on with whatever
        // feature set the platform already had. The previous version called both DPM calls back
        // to back inside one try/catch that just logged and swallowed either failure - if
        // setLockTaskPackages succeeded but setLockTaskFeatures then threw (silently, for any
        // device-specific reason), the device would end up pinned with Android's own default
        // (non-keyguard) feature set. That's the exact condition that caused a real, unrecoverable-
        // except-via-hardware-recovery-mode boot deadlock before (GrapheneOS's auto-reboot, or any
        // plain reboot, re-locks storage pre-decrypt with no keyguard reachable and no launcher
        // resolvable either - see kid-phone-server's CLAUDE.md for the full incident). Failing
        // closed to "not pinned this cycle" is safe either way, since apply() re-runs every ~2
        // minutes (or sooner via SSE push) and will retry. [lockTaskFeatures] always includes
        // LOCK_TASK_FEATURE_KEYGUARD (see computeEnforcementPlan), whatever the server sent.
        if (!applyLockTaskFeaturesVerified(dpm, admin, lockTaskFeatures)) {
            Log.w(LOG_TAG, "Refusing to pin kiosk this cycle - lock task features never verified")
            return
        }

        try {
            dpm.setLockTaskPackages(admin, kioskPackages.toTypedArray())
            mdm.kioskEnabled(true)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to pin kiosk packages", e)
        }
    }

    /**
     * setLockTaskFeatures is a cheap Binder call - one immediate retry after a failed
     * write-then-verify is trivial insurance against a transient platform hiccup, not a real
     * performance concern. Reads back [DevicePolicyManager.getLockTaskFeatures] rather than
     * trusting the setter not to throw, since a silent mismatch is exactly what would otherwise
     * let a device get pinned without the keyguard bit actually applied.
     */
    private fun applyLockTaskFeaturesVerified(dpm: DevicePolicyManager, admin: ComponentName, features: Int): Boolean {
        repeat(2) { attempt ->
            try {
                dpm.setLockTaskFeatures(admin, features)
                if (dpm.getLockTaskFeatures(admin) == features) return true
                Log.w(
                    LOG_TAG,
                    "setLockTaskFeatures didn't verify on attempt ${attempt + 1} " +
                        "(wanted $features, got ${dpm.getLockTaskFeatures(admin)})"
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "setLockTaskFeatures threw on attempt ${attempt + 1}", e)
            }
        }
        return false
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
     * Pushes a Chrome enterprise-policy bundle to the kids-mdm-browser fork via Android's
     * managed-configuration mechanism (`DevicePolicyManager.setApplicationRestrictions` ->
     * Chrome's built-in `AppRestrictionsProvider` on the browser side - no browser-side patch
     * needed for this to take effect, only for it to *not* be strippable by the kid). A no-op if
     * the browser isn't installed (`NameNotFoundException`) - safe to call unconditionally on
     * every [apply] cycle, same tolerance pattern as every other per-package call in this file.
     *
     * Scope is deliberately narrow: DNS-based blocking is handled elsewhere (KidVpnService's
     * on-device filter / the server's DNS blocklist), so this only closes the browser-side gaps
     * that would otherwise route around it or hide activity from the history journal -
     * Secure DNS (would bypass the DNS filter entirely), Incognito/Guest mode (would hide
     * browsing from [performBrowserHistorySync]), developer tools and extension installs
     * (both plausible tamper vectors on a kid's device), and the browser's own proxy settings
     * (another potential bypass route). Same "fully open" treatment as every other restriction
     * in [apply] while an override is active - [locked] is false in that case and every value
     * below reverts to Chrome's un-managed default.
     *
     * Policy keys/types follow https://chromeenterprise.google/policies/ as of Chrome 151;
     * verify against `chrome://policy` on the actual device after the first build, since Android
     * app-restriction bundle typing (plain values vs. JSON-encoded strings for object/array
     * policies) isn't unit-testable from here.
     */
    private fun applyBrowserPolicy(dpm: DevicePolicyManager, admin: ComponentName, context: Context, locked: Boolean) {
        try {
            context.packageManager.getApplicationInfo(BROWSER_PACKAGE_NAME, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }

        val bundle = if (!locked) {
            Bundle()
        } else {
            Bundle().apply {
                putString("DnsOverHttpsMode", "off")
                putInt("IncognitoModeAvailability", 1) // 1 = Disabled
                putBoolean("BrowserGuestModeEnabled", false)
                putInt("DeveloperToolsAvailability", 2) // 2 = DeveloperToolsDisallowed
                putStringArray("ExtensionInstallBlocklist", arrayOf("*"))
                // Object-valued policy - Chrome's Android provider expects these JSON-encoded,
                // unlike the scalar policies above (Android restriction bundles have no native
                // nested-object type).
                putString("ProxySettings", """{"ProxyMode":"system"}""")
            }
        }

        try {
            dpm.setApplicationRestrictions(admin, BROWSER_PACKAGE_NAME, bundle)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set browser app restrictions", e)
        }
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
    fun enforceOnNewPackage(context: Context, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        // Fails closed: with no usable cached policy on a phone that has had one, the
        // last-enforced plan (or nothing allowed) decides - see shouldSuspendNewPackage.
        val decision = currentPolicyDecision()
        val suspend = shouldSuspendNewPackage(
            packageName = packageName,
            decision = decision,
            overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive(),
            ownPackage = context.packageName,
            systemDialer = systemDialerPackage(context),
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
        if (!suspend) return

        try {
            val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            if (!notSuspended.isNullOrEmpty()) {
                Log.w(LOG_TAG, "Platform refused to suspend newly-installed $packageName")
            }
            val hiddenOk = dpm.setApplicationHidden(admin, packageName, true)
            if (!hiddenOk) {
                Log.w(LOG_TAG, "Platform refused to hide newly-installed $packageName")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to suspend newly-installed $packageName", e)
        }
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
