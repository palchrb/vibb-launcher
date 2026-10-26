package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

private const val LOG_TAG = "BrowserDoh"

/** The status report's capability once design 67's bypass fixes are in (the browsers' managed
 * config, the built-in secure-DNS hosts, Firefox's canary): the server stops asking for an update. */
const val DNS_BYPASS_CAPABILITY = "dns_bypass_v1"

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

/** [browserDohPlan]'s glue, from `AppEnforcer.apply` right after the VPN's own restrictions. */
object BrowserDoh {
    fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName, filterOn: Boolean) {
        // Only the device owner writes a managed config: nothing to do (and nothing to log) otherwise.
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        // Hidden (unallowed) browsers count: MATCH_UNINSTALLED_PACKAGES sees them.
        val installed = BROWSER_DOH_PACKAGES.filter { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }.toSet()
        for ((pkg, values) in browserDohPlan(filterOn, installed)) {
            try {
                val desired = Bundle().apply { values.forEach(::putString) }
                if (!sameBundle(dpm.getApplicationRestrictions(admin, pkg), desired)) {
                    dpm.setApplicationRestrictions(admin, pkg, desired)
                    Log.i(LOG_TAG, "Secure DNS ${if (filterOn) "off" else "released"} for $pkg")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't write $pkg's managed config: ${e.javaClass.simpleName}")
            }
        }
    }
}
