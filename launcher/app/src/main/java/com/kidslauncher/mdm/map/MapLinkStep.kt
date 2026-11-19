package com.kidslauncher.mdm.map

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.play.PLAY_NEVER_RESTRICT
import com.kidslauncher.mdm.play.PLAY_STORE
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause

private const val TAG = "MapLinkStep"

/** The other apps that hold any of [ours] verified (`system_configured` included), from each app's host states
 * ([DomainVerificationUserState.getHostToStateMap]); never one in [never]. */
fun stepHolders(ours: Set<String>, states: Map<String, Map<String, Int>>, never: Set<String>): Set<String> =
    states.filter { (pkg, hosts) ->
        pkg !in never && hosts.any { (host, state) -> host in ours && state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED }
    }.keys

/** How many of our hosts ([ours], host to our state) the parent can select in step 2: one already selected or verified,
 * or one no app in [unsuspendable] holds verified ([states] of those apps). */
fun selectableHosts(ours: Map<String, Int>, states: Map<String, Map<String, Int>>, unsuspendable: Set<String>): Int =
    ours.count { (host, state) ->
        state != DomainVerificationUserState.DOMAIN_STATE_NONE || states.none { (pkg, hosts) ->
            pkg in unsuspendable && hosts[host] == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
    }

/**
 * Step 2 (Vibb One's links), the emulator check's finding 1 (2026-11-19). Settings' "Add link" greys out every host
 * another app holds verified: in a pause Google Maps, the Google app, Google Calendar and Google Photos are all there,
 * `system_configured` for `maps.google.*`, `www.google.*`, `google.com` and `goo.gl`. A suspended app holds none (its
 * approval drops below the parent's selection); a hidden one still does, and Android 14's dialog then never loads. So
 * while Vibb One's own "Open by default" page is open in a pause, the apps holding its hosts are suspended, and given
 * back when the page returns, when the pause ends or after [MAX_MS]. The parent's selection stays when they come back,
 * after a reboot and when Google Maps is reinstalled or updated: outside a pause they are hidden, so Vibb One is the
 * only approved app for a plain tap.
 */
object MapLinkStep {
    /** The longest the holders stay suspended for one page. */
    const val MAX_MS = 15 * 60_000L
    private const val PREFS = "map_link_step"
    private const val KEY_REFUSED = "refused"

    @Volatile
    private var holding: Set<String> = emptySet()

    @Volatile
    private var since = 0L
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private fun windowOpen() = RestrictionsPause.isActive() || OfflineOverride.isActive()

    /** What the step holds suspended now: [AppEnforcer] never lifts these. Empty outside a pause and once expired. */
    fun held(): Set<String> = holding.takeIf { it.isNotEmpty() && SystemClock.elapsedRealtime() - since < MAX_MS && windowOpen() }.orEmpty()

    /** The apps the step never suspends: the enforcer's never-restrict set (Play services, the dialer, the WebView, the
     * keyboards ...), the Play Store (its `play.google.com` is the Play link blocker's, and Android refuses it as the
     * package verifier) and the ones Android refused at the last step. */
    fun unsuspendable(context: Context): Set<String> =
        (AppEnforcer.lastNeverRestrict ?: PLAY_NEVER_RESTRICT) + PLAY_STORE + refused(context) + context.packageName

    private fun refused(context: Context): Set<String> = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_REFUSED, null).orEmpty().toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Before Vibb One's page opens, off the main thread: the holders suspended (only by the device owner, in a pause). */
    fun begin(context: Context) {
        if (!windowOpen()) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = admin(context)
        val domains = context.getSystemService(DomainVerificationManager::class.java) ?: return
        try {
            val ours = domains.getDomainVerificationUserState(context.packageName)?.hostToStateMap?.keys.orEmpty()
            val never = unsuspendable(context)
            val states = context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                .map { it.packageName }.filter { it !in never }
                .mapNotNull { pkg -> hostStates(domains, pkg)?.let { pkg to it } }.toMap()
            val holders = stepHolders(ours, states, never)
            if (holders.isEmpty()) return
            synchronized(AppEnforcer) {
                val refusedNow = dpm.setPackagesSuspended(admin, holders.toTypedArray(), true).orEmpty().toSet()
                holding = holding + holders - refusedNow
                since = SystemClock.elapsedRealtime()
                if (refusedNow.isNotEmpty()) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putStringSet(KEY_REFUSED, refused(context) + refusedNow).apply()
                }
                Log.i(TAG, "Holding ${holders.size - refusedNow.size} app(s) suspended for step 2, ${refusedNow.size} refused")
            }
            val app = context.applicationContext
            val token = since
            handler.postDelayed({ if (since == token) Thread { end(app) }.start() }, MAX_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't hold the link holders for step 2: ${e.javaClass.simpleName}")
        }
    }

    /** When the page returns, the pause ends or [MAX_MS] passed, off the main thread: the held apps back, except those
     * the enforcer's last plan or the PIN lock's camera suspends. Never throws. */
    fun end(context: Context) {
        try {
            synchronized(AppEnforcer) {
                val was = holding
                if (was.isEmpty()) return
                holding = emptySet()
                val back = was - AppEnforcer.lastPlanSuspend.orEmpty() - com.kidslauncher.mdm.lock.CameraLock.held
                if (back.isEmpty()) return
                val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
                dpm.setPackagesSuspended(admin(context), back.toTypedArray(), false)
                Log.i(TAG, "Gave ${back.size} app(s) back after step 2")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't give the link holders back: ${e.javaClass.simpleName}")
        }
    }

    private fun admin(context: Context) = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    /** An app's host states, or null for one with no web links or not visible. */
    fun hostStates(domains: DomainVerificationManager, pkg: String): Map<String, Int>? = try {
        domains.getDomainVerificationUserState(pkg)?.hostToStateMap?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
