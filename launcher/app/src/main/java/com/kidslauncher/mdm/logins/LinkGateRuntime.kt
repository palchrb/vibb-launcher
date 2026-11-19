package com.kidslauncher.mdm.logins

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

private const val LOG_TAG = "LinkGate"
private const val PREFS = "link_gate"
private const val KEY_NUDGED_MS = "role_nudged_ms"

/**
 * Design 119 §4.1: Vibb One as the phone's browser - the gate's component (installed disabled, enabled by the apply
 * while managed and no free browser is allowed, D2), Android's browser role, the repair (M1), the gate's persistent
 * preferred activity (L4), and §4.3's decision for a tapped link ([decide], shared with Meldinger).
 */
object LinkGateRuntime {
    /** A host no app declares: whoever takes it takes any web link - a full browser. */
    private val PROBE: Uri = Uri.parse("https://vibb-probe.invalid/")

    fun component(context: Context) = ComponentName(context, LinkGateActivity::class.java)

    /** The no-op alias whose flip changes the role service's package hash (M1). */
    private fun nudgeComponent(context: Context) = ComponentName(context.packageName, "com.kidslauncher.mdm.logins.RoleNudge")

    /** The gate's component is enabled (the apply's last decision): the setup step `vibb_browser` applies. */
    fun enabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Exception) {
        false
    }

    /** Vibb One holds Android's browser role. */
    fun roleHeld(context: Context): Boolean = try {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    } catch (e: Exception) {
        false
    }

    /** Every visible full browser but Vibb One (a lookup with no package keeps only the browsers once one exists;
     * `MATCH_ALL` asks for all of them, a suspended one included); with [includeHidden] a hidden one too
     * (`MATCH_UNINSTALLED_PACKAGES`). */
    fun browsers(context: Context, includeHidden: Boolean = false): List<String> = try {
        val flags = PackageManager.MATCH_ALL.toLong() or (if (includeHidden) PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() else 0L)
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, PROBE).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.ResolveInfoFlags.of(flags),
        ).map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }
    } catch (e: Exception) {
        emptyList()
    }

    /** §4.1 (D2): the gate wanted - managed ([allowlist] not `null`) and no full browser among the allowed apps. Read
     * before the package loop, so a browser ticked in this very policy is still hidden: the hidden ones count too (code
     * QA L1), and the gate, the role and `DISALLOW_CONFIG_DEFAULT_APPS` follow the parent's choice on this apply. */
    fun gateWanted(context: Context, allowlist: List<String>?): Boolean {
        if (allowlist == null) return false
        val allowed = allowlist.toSet()
        return linkGateOn(managed = true, freeBrowsers = browsers(context, includeHidden = true).filter { it in allowed })
    }

    /**
     * Code QA L2: a login window's conditions read again under [LoginWindow]'s lock, right before it unhides Chrome -
     * the role ready (its stored state: an apply turning the role off writes it before its window check), no lock, no
     * pause or override, not Play's update window, the gate on. Cheap reads only; [role] is read before the lock.
     */
    fun windowStillAllowed(context: Context, role: LoginRole): Boolean =
        com.kidslauncher.mdm.server.BrowserConfig.roleReady(context, role) &&
            com.kidslauncher.mdm.preferences.LauncherPreferences.mdm().lockReason() == com.kidslauncher.mdm.server.LockReason.NONE &&
            !com.kidslauncher.mdm.server.RestrictionsPause.isActive() && !com.kidslauncher.mdm.server.OfflineOverride.isActive() &&
            !com.kidslauncher.mdm.play.PlayRuntime.updateWindowActive(context) && enabled(context)

    /** The login role as the gate reads it (the last accepted policy). */
    fun loginRole(): LoginRole {
        val policy = com.kidslauncher.mdm.server.currentPolicyDecision().policy
        return loginRoleOf(policy?.loginBrowser, policy?.allowlist)
    }

    /**
     * After the apply's package loop (M1: Chrome hidden first, then the gate enabled, in the same apply): the gate
     * switched, then the repair - a no-op component flipped once when the gate is on, Chrome hidden and the role not
     * Vibb One's, at most once an hour, at once when a pause just ended ([roleRepairDue]). Never throws.
     */
    fun afterLoop(context: Context, dpm: DevicePolicyManager, admin: ComponentName, gateOn: Boolean, pauseEnded: Boolean) {
        val pm = context.packageManager
        val wasOn = enabled(context)
        try {
            val state = if (gateOn) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component(context)) != state) {
                pm.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
                Log.i(LOG_TAG, "The link gate ${if (gateOn) "enabled" else "disabled"}")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't switch the link gate: ${e.javaClass.simpleName}")
            return
        }
        val chromeVisible = try {
            !dpm.isApplicationHidden(admin, CHROME_PACKAGE) && installed(pm, CHROME_PACKAGE)
        } catch (e: Exception) {
            false
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_NUDGED_MS, 0L).takeIf { it > 0L }
        if (!roleRepairDue(gateOn, justEnabled = gateOn && !wasOn, chromeVisible, roleHeld(context), last, now, pauseEnded)) return
        try {
            // One flip, kept (enabled and disabled in turn at each nudge): the role service compares the hash when the
            // broadcast reaches it, and a flip and its undo before then would compare equal.
            val nudge = nudgeComponent(context)
            val next = if (pm.getComponentEnabledSetting(nudge) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            pm.setComponentEnabledSetting(nudge, next, PackageManager.DONT_KILL_APP)
            prefs.edit().putLong(KEY_NUDGED_MS, now).apply()
            Log.i(LOG_TAG, "The browser role isn't Vibb One's: nudged the role service")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't nudge the role service: ${e.javaClass.simpleName}")
        }
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** L4: the gate's persistent preferred activity's filter - `VIEW`, http and https, no host. Added after the Play link
     * blocker's and the map handler's (insertion order among equal priorities). */
    fun preferredFilter(): IntentFilter = IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addDataScheme("http")
        addDataScheme("https")
    }

    /**
     * §4.3's inputs for [url], read off the main thread, and the plan's step. [fromMessages]: Meldinger's link (D3).
     * [keyguardLocked] the gate's own reading (Meldinger is in front, so never locked by the keyguard).
     */
    fun decide(context: Context, url: String, fromMessages: Boolean, keyguardLocked: Boolean): GateStep {
        val decision = com.kidslauncher.mdm.server.currentPolicyDecision()
        val policy = decision.policy
        val allowlist = policy?.allowlist
        val own = context.packageName
        val locked = keyguardLocked ||
            com.kidslauncher.mdm.preferences.LauncherPreferences.mdm().lockReason() != com.kidslauncher.mdm.server.LockReason.NONE
        if (locked) return GateStep.Nothing
        val paused = com.kidslauncher.mdm.server.RestrictionsPause.isActive() || com.kidslauncher.mdm.server.OfflineOverride.isActive()
        // Code QA L4 (design 110): an intent filter's scheme is lowercase ("HTTPS://" matches nothing).
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
        // Only what the step needs is read: the per-package lookup is one query per allowed app.
        val found = if (paused) null else LinkLookup.find(context, view, allowlist, own)
        val allowed = if (fromMessages) com.kidslauncher.mdm.ui.messages.messageLinkAllowed(allowlist, own) else allowlist?.toSet()
        val role = loginRoleOf(policy?.loginBrowser, allowlist)
        return linkGatePlan(
            GateInput(
                locked = false,
                paused = paused,
                pauseBrowser = if (paused) usableBrowser(context) else null,
                mapLink = com.kidslauncher.mdm.map.MapLinkForms.isMapLink(url) && com.kidslauncher.mdm.map.MapLinksRuntime.on(context),
                roleReady = com.kidslauncher.mdm.server.BrowserConfig.roleReady(context, role),
                roleHeld = roleHeld(context),
                fromMessages = fromMessages,
                loginMatch = loginLinkMatchesAny(loginHostsOf(policy?.loginBrowser).orEmpty(), url),
                target = found?.let { linkTargetOf(it.approved, it.declaring, allowed, false, own, it.browsers.toSet()) },
                freeBrowser = found?.let { freeBrowserOf(it.browsers, allowed, own) },
                updateWindow = com.kidslauncher.mdm.play.PlayRuntime.updateWindowActive(context),
            ),
        )
    }

    /** In a pause (§4.3 step 2): the first visible browser that isn't suspended (every app is unhidden then). */
    private fun usableBrowser(context: Context): String? = browsers(context).firstOrNull { pkg ->
        try {
            val info = context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            info.enabled && info.flags and ApplicationInfo.FLAG_SUSPENDED == 0
        } catch (e: Exception) {
            false
        }
    }

    /** The keyguard is up (§4.3 step 1). */
    fun keyguardLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}
