package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val LOG_TAG = "BrowserConfig"

/** Chromium's policy (`DnsOverHttpsMode`, Android 85+): `off` turns the browser's own secure DNS off. */
const val DOH_MODE_KEY = "DnsOverHttpsMode"
const val DOH_MODE_OFF = "off"

/**
 * The browsers that get [DOH_MODE_KEY] (design 67 §2.1): a fixed list, not the restrictions schema
 * (a fork reads Chrome's keys without declaring them). A browser that ignores the bundle takes no
 * harm; which read it is the E3 table in `launcher/CLAUDE.md`. Not Cromite (its patch drops the
 * provider) nor Firefox (the canary, §2.3).
 */
val BROWSER_DOH_PACKAGES: List<String> = listOf(
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "com.microsoft.emmx",
    "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
    "com.vivaldi.browser",
    "com.sec.android.app.sbrowser",
    "com.kiwibrowser.browser",
    // Chromium-based with its own DoH setting; whether it reads the policy is E3's (qa-67-code L6).
    "com.opera.browser",
)

/**
 * The managed config each installed listed browser should have: [DOH_MODE_KEY] = `off` (a String)
 * while the Web filter is on, nothing while it is off (the override, the pause or the parent's
 * switch - the same `vpnFilterEnabled` as the VPN). Not tied to the allowlist: a browser the parent
 * allows later starts with its secure DNS already off. Pure; BrowserDohTest.
 */
fun browserDohPlan(filterOn: Boolean, installed: Set<String>): Map<String, Map<String, String>> =
    BROWSER_DOH_PACKAGES.filter { it in installed }
        .associateWith { if (filterOn) mapOf(DOH_MODE_KEY to DOH_MODE_OFF) else emptyMap() }

/** Two managed-config bundles hold the same keys and values (written only when they differ). */
@Suppress("DEPRECATION") // Bundle.get: the values are plain booleans, numbers and strings
internal fun sameBundle(a: Bundle, b: Bundle): Boolean =
    a.keySet() == b.keySet() && a.keySet().all { a.get(it) == b.get(it) }

/**
 * Design 102 §4.1: the browsers' managed config - design 67's secure DNS off with the filter, and Chrome's login role's
 * lockdown ([com.kidslauncher.mdm.logins.browserConfigPlan]) - the one owner of those bundles. From `AppEnforcer.applyHeld`
 * **before** the plan and the hide/unhide loop: for the role's package it writes the bundle, reads it back typed, and
 * says the role is ready only on a match ([Result.ready]); a throw, a mismatch or Chrome absent leaves Chrome hidden and
 * unpinned (the plan isn't told), with `failed` and the reason. Each tightening hides Chrome (its process and tabs end,
 * Q102-M4); the plan then unhides it. The role's first turn-on clears Chrome's data once (D13).
 */
object BrowserConfig {
    private const val PREFS = "login_browser"
    private const val KEY_WRITTEN = "written_hosts"
    private const val KEY_WRITTEN_MS = "written_ms"
    private const val KEY_CLEARED = "cleared"
    private const val KEY_CLEARED_MS = "cleared_ms"
    private const val KEY_CLEARING_MS = "clearing_ms"
    private const val KEY_STATE = "state"
    private const val KEY_REASON = "reason"

    /** The role's outcome this pass: [ready] = Chrome may be unhidden and pinned for the role. */
    data class Result(val ready: Boolean)

    fun apply(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        filterOn: Boolean,
        role: com.kidslauncher.mdm.logins.LoginRole,
        lockdownLifted: Boolean,
    ): Result {
        // Only the device owner writes a managed config: nothing to do (and nothing to log) otherwise.
        if (!dpm.isDeviceOwnerApp(context.packageName)) return Result(false)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Hidden (unallowed) browsers count: MATCH_UNINSTALLED_PACKAGES sees them.
        val installed = BROWSER_DOH_PACKAGES.filter { pkg -> installed(context, pkg) }.toSet()
        val roleOn = role is com.kidslauncher.mdm.logins.LoginRole.On
        // A ticked Chrome (a free app) forgets the clear's record: the next turn-on clears again (D13).
        if (!roleOn && com.kidslauncher.mdm.logins.CHROME_PACKAGE in installed && chromeFree()) {
            prefs.edit().putBoolean(KEY_CLEARED, false).apply()
        }
        val lockdown = (role as? com.kidslauncher.mdm.logins.LoginRole.On)?.hosts?.takeIf { !lockdownLifted }
        var ready = false
        val chromeInstalled = com.kidslauncher.mdm.logins.CHROME_PACKAGE in installed
        // The role off (or another package) after its lockdown was in force: Chrome is hidden first - its tabs end -,
        // and only then is its bundle loosened (code QA L3); the plan then hides it or, ticked, unhides it.
        if (!roleOn && chromeInstalled && prefs.getString(KEY_WRITTEN, null) != null) {
            // Design 119 §4.4: an open login window ends first (`rule`), its record gone before Chrome hides.
            com.kidslauncher.mdm.logins.LoginWindow.eventNow(context, com.kidslauncher.mdm.logins.WindowEvent.RULE)
            try {
                dpm.setApplicationHidden(admin, com.kidslauncher.mdm.logins.CHROME_PACKAGE, true)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't hide Chrome before loosening its config: ${e.javaClass.simpleName}")
            }
        }
        for ((pkg, values) in com.kidslauncher.mdm.logins.browserConfigPlan(filterOn, lockdown, installed)) {
            val chromeRole = pkg == com.kidslauncher.mdm.logins.CHROME_PACKAGE && roleOn
            try {
                if (chromeRole && com.kidslauncher.mdm.logins.chromeClearDue(roleOn, prefs.getBoolean(KEY_CLEARED, false))) {
                    // D13 first and on its own (code QA L5): the role waits - Chrome stays hidden - until the clear has
                    // reported success, which is recorded then and asks for the next apply; a failure is tried again.
                    val clearing = prefs.getLong(KEY_CLEARING_MS, 0L)
                    if (System.currentTimeMillis() - clearing !in 0..CLEAR_WAIT_MS) {
                        prefs.edit().putLong(KEY_CLEARING_MS, System.currentTimeMillis()).apply()
                        clearChrome(context, dpm, admin, prefs)
                    }
                    continue
                }
                val desired = bundleOf(values)
                if (!sameBundle(dpm.getApplicationRestrictions(admin, pkg), desired)) {
                    dpm.setApplicationRestrictions(admin, pkg, desired)
                    Log.i(LOG_TAG, "Managed config written for $pkg")
                }
                if (chromeRole) {
                    // Read back and compared typed: only a match lets the plan unhide Chrome (Q102-M3).
                    if (!sameBundle(dpm.getApplicationRestrictions(admin, pkg), desired)) {
                        state(prefs, "failed", "readback")
                        continue
                    }
                    // The allowlist really written, the captcha set included (code QA L2): a captcha path gone in an
                    // update is a tightening too.
                    val written = lockdown?.let { com.kidslauncher.mdm.logins.chromeAllowlist(it) }
                    val old = prefs.getString(KEY_WRITTEN, null)?.let(::hostsOf)
                    if (com.kidslauncher.mdm.logins.chromeTightened(old, written)) {
                        // Hiding stops the package: an open tab of the looser phase ends. Design 119: a login window
                        // ends first (`tightened`); Chrome stays hidden until the next one.
                        com.kidslauncher.mdm.logins.LoginWindow.eventNow(context, com.kidslauncher.mdm.logins.WindowEvent.TIGHTENED)
                        dpm.setApplicationHidden(admin, pkg, true)
                        Log.i(LOG_TAG, "Chrome's lockdown tightened: its tabs ended")
                    }
                    prefs.edit()
                        .putString(KEY_WRITTEN, written?.let { hosts -> kotlinx.serialization.json.JsonArray(hosts.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString() })
                        .putLong(KEY_WRITTEN_MS, System.currentTimeMillis())
                        .apply()
                    state(prefs, "on", null)
                    ready = true
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't write $pkg's managed config: ${e.javaClass.simpleName}")
                if (chromeRole) state(prefs, "failed", "write")
            }
        }
        when {
            role is com.kidslauncher.mdm.logins.LoginRole.WrongPackage -> state(prefs, "failed", "package")
            roleOn && !chromeInstalled -> state(prefs, "absent", null)
            !roleOn -> {
                state(prefs, "off", null)
                // The role off: the next turn-on is a tightening again.
                prefs.edit().remove(KEY_WRITTEN).apply()
            }
        }
        return Result(ready)
    }

    /** The login role holds now: the policy asks it and its lockdown was last written and read back (`on`) - so a
     * Chrome unhidden by the apply is the role's, which [AppEnforcer.enforceOnNewPackage] leaves alone. */
    fun roleReady(context: Context, role: com.kidslauncher.mdm.logins.LoginRole): Boolean =
        role is com.kidslauncher.mdm.logins.LoginRole.On &&
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null) == "on"

    /** The report's `login_browser` (§6.2; design 119 §7: `browser_role` - `null` while the link gate is off -, the
     * reason `role` when the role is on but Vibb One isn't Android's browser (fail closed: no window opens), the
     * stopped hosts and the last window). */
    fun report(context: Context): com.kidslauncher.mdm.portal.LoginBrowserReport {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val browserRole = if (com.kidslauncher.mdm.logins.LinkGateRuntime.enabled(context)) {
            com.kidslauncher.mdm.logins.LinkGateRuntime.roleHeld(context)
        } else {
            null
        }
        val stored = prefs.getString(KEY_STATE, "off") ?: "off"
        val (state, reason) = com.kidslauncher.mdm.logins.reportedRoleState(stored, prefs.getString(KEY_REASON, null), browserRole)
        val version = try {
            context.packageManager.getPackageInfo(
                com.kidslauncher.mdm.logins.CHROME_PACKAGE,
                PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()),
            ).versionName
        } catch (e: Exception) {
            null
        }
        return com.kidslauncher.mdm.portal.LoginBrowserReport(
            packageName = com.kidslauncher.mdm.logins.CHROME_PACKAGE,
            state = state,
            versionName = version,
            writtenMs = prefs.getLong(KEY_WRITTEN_MS, 0L).takeIf { it > 0L },
            reason = reason,
            clearedMs = prefs.getLong(KEY_CLEARED_MS, 0L).takeIf { it > 0L },
            browserRole = browserRole,
            // Code QA L8: the stopped hosts only while the role is on - the PWA shows them only then.
            refused = if (stored == "on") com.kidslauncher.mdm.logins.LoginWindow.refused(context) else emptyList(),
            lastWindow = com.kidslauncher.mdm.logins.LoginWindow.lastWindow(context),
        )
    }

    private fun state(prefs: android.content.SharedPreferences, state: String, reason: String?) {
        prefs.edit().putString(KEY_STATE, state).putString(KEY_REASON, reason).apply()
    }

    private fun hostsOf(json: String): List<String>? = try {
        ServerJson.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
    } catch (e: Exception) {
        null
    }

    /** Chrome is a free app now: the last accepted policy ticks it. */
    private fun chromeFree(): Boolean = try {
        currentPolicyDecision().policy?.allowlist?.contains(com.kidslauncher.mdm.logins.CHROME_PACKAGE) == true
    } catch (e: Exception) {
        false
    }

    /** D13: Chrome's history and web sign-ins from a free phase go, once (asynchronous; the bundle is the device
     * owner's, not Chrome's data, and stays). */
    private fun clearChrome(context: Context, dpm: DevicePolicyManager, admin: ComponentName, prefs: android.content.SharedPreferences) {
        val app = context.applicationContext
        dpm.clearApplicationUserData(admin, com.kidslauncher.mdm.logins.CHROME_PACKAGE, context.mainExecutor) { pkg, ok ->
            Log.i(LOG_TAG, "Cleared $pkg's data: $ok")
            val edit = prefs.edit().remove(KEY_CLEARING_MS)
            if (ok) edit.putBoolean(KEY_CLEARED, true).putLong(KEY_CLEARED_MS, System.currentTimeMillis())
            edit.apply()
            // The role's bundle and Chrome's unhiding come with the next apply.
            com.kidslauncher.mdm.push.SyncRunner.request(app, "login_browser_cleared")
        }
    }

    /** A clear that never reported is asked again after this long. */
    private const val CLEAR_WAIT_MS = 120_000L

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** A plan's values in their own Bundle types: a String, an Int or a Boolean (Chrome's STRING, CHOICE, BOOLEAN). */
    fun bundleOf(values: Map<String, Any>): Bundle = Bundle().apply {
        for ((key, value) in values) {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                else -> error("unsupported type for $key")
            }
        }
    }
}
