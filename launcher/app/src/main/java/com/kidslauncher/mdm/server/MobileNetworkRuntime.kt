package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.UserManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MobileNetwork"

/** `UserManager.ACTION_USER_RESTRICTIONS_CHANGED` (a `@SystemApi` constant, so spelled here): UserManagerService sends
 * it to registered receivers once a restriction change has been applied. */
private const val ACTION_USER_RESTRICTIONS_CHANGED = "android.os.action.USER_RESTRICTIONS_CHANGED"

/** How long the relock waits for Android to apply the cleared restriction (design 94 §2.4.2). */
private const val RELOCK_WAIT_MS = 5_000L

/**
 * The Android side of the round "Mobilnett" (design 94, design 95 rev. 3): the restrictions, the VPN's persisted
 * inputs and shape and the one writer of always-on ([writeAlwaysOn]), the network as the launcher sees it, and the
 * report's two objects. The rules are [MobileNetwork]'s.
 */
object MobileNetworkRuntime {
    /** Relocks tried per subscription since this process started (design 94 §2.4.2, [dataRoamingRelock]) - a process
     * restart may relock once more, which costs a fraction of a second. */
    private val relockTries = mutableMapOf<Int, Int>()

    // ---- the VPN's inputs and shape ------------------------------------------------------------------------------

    fun encodeInputs(inputs: VpnInputs): String =
        listOf(inputs.filterOn, inputs.parentSink, inputs.roamingLocked).joinToString(",") { if (it) "1" else "0" }

    /** `null` for an absent or unreadable record. */
    fun decodeInputs(raw: String?): VpnInputs? {
        val parts = raw?.split(",")?.takeIf { it.size == 3 && it.all { p -> p == "0" || p == "1" } } ?: return null
        return VpnInputs(parts[0] == "1", parts[1] == "1", parts[2] == "1")
    }

    /** The inputs `apply()` last wrote; before any (a launcher from before this round), the cached filter switch alone
     * - a failed read counts the filter on, as `KidVpnService.filterWanted` always did. */
    fun storedInputs(): VpnInputs = try {
        val mdm = LauncherPreferences.mdm()
        decodeInputs(mdm.vpnInputs()) ?: VpnInputs(filterOn = mdm.vpnFilterEnabled(), parentSink = false, roamingLocked = false)
    } catch (e: Exception) {
        VpnInputs(filterOn = true, parentSink = false, roamingLocked = false)
    }

    fun storeInputs(inputs: VpnInputs) {
        val mdm = LauncherPreferences.mdm()
        mdm.vpnFilterEnabled(inputs.filterOn)
        mdm.vpnInputs(encodeInputs(inputs))
    }

    fun storedShape(): VpnShape? = runCatching { VpnShape.of(LauncherPreferences.mdm().vpnShape()) }.getOrNull()

    /**
     * The single owner of always-on (design 95 §2.2, [alwaysOnFor]): records [shape] as the decision, then writes what
     * it needs - only on a change, so a sync that decides the same shape writes nothing. Never throws.
     */
    fun writeAlwaysOn(context: Context, shape: VpnShape) {
        runCatching { LauncherPreferences.mdm().vpnShape(shape.wire) }
        val wanted = alwaysOnFor(shape)
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            val pkg = if (wanted.ownPackage) context.packageName else null
            val current = dpm.getAlwaysOnVpnPackage(admin)
            val currentLockdown = current != null && dpm.isAlwaysOnVpnLockdownEnabled(admin)
            if (current == pkg && currentLockdown == wanted.lockdown) return
            dpm.setAlwaysOnVpnPackage(admin, pkg, wanted.lockdown, emptySet())
            Log.i(LOG_TAG, "Always-on: ${pkg ?: "none"}, lockdown ${wanted.lockdown} (${shape.wire})")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set always-on for ${shape.wire}: ${e.javaClass.simpleName}")
        }
    }

    // ---- the network ---------------------------------------------------------------------------------------------

    /** [BestNet] from a network's capabilities. A VPN's carry its underlying network's transports. */
    fun bestNetOf(caps: NetworkCapabilities): BestNet {
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return BestNet(cellular = cellular, roaming = cellular && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
    }

    /** The launcher's default network - outside the VPN in every shape, so the physical one. `null` = none. */
    fun bestNet(context: Context): BestNet? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let(::bestNetOf)
    } catch (e: Exception) {
        null
    }

    /** The sink is wanted now: what `AppDownloads` holds the launcher's own downloads for (design 95 §2.4). */
    fun sinkWanted(context: Context): Boolean = sinkReason(storedInputs(), bestNet(context)) != SinkReason.OFF

    // ---- the restrictions ----------------------------------------------------------------------------------------

    /**
     * Design 94 §3 and §2.4.2: `DISALLOW_DATA_ROAMING` as [locked] says; while locked, a SIM that still reads roaming on
     * (only an absent per-SIM key on a multi-SIM phone can) gets the restriction cleared, applied, and set again, so
     * Android writes `0` for it. Runs off the main thread (`apply()`); waits at most [RELOCK_WAIT_MS], only then.
     */
    fun applyDataRoaming(context: Context, dpm: DevicePolicyManager, admin: ComponentName, locked: Boolean) {
        setRestriction(dpm, admin, UserManager.DISALLOW_DATA_ROAMING, locked)
        if (!locked) return
        val readings = try {
            roamingReadings(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the SIMs' roaming: ${e.javaClass.simpleName}")
            return
        }
        val subs = synchronized(relockTries) {
            dataRoamingRelock(true, readings, relockTries).onEach { relockTries[it] = (relockTries[it] ?: 0) + 1 }
        }
        if (subs.isEmpty()) return
        Log.i(LOG_TAG, "Roaming still on for ${subs.size} SIM(s) under the lock: relocking")
        // Two waits: for the clear to be applied (else the add is no change and writes nothing), then for the add, so
        // the SIMs can be read back (code QA L5).
        val changes = java.util.concurrent.LinkedBlockingQueue<Unit>()
        val thread = HandlerThread("roaming-relock").apply { start() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changes.offer(Unit)
            }
        }
        var cleared = false
        try {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_USER_RESTRICTIONS_CHANGED), null,
                Handler(thread.looper), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            setRestriction(dpm, admin, UserManager.DISALLOW_DATA_ROAMING, false)
            cleared = changes.poll(RELOCK_WAIT_MS, TimeUnit.MILLISECONDS) != null
            if (!cleared) Log.w(LOG_TAG, "The clear wasn't applied in time")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Relock wait failed: ${e.javaClass.simpleName}")
        } finally {
            // Set again whatever happened: the lock never stays off.
            setRestriction(dpm, admin, UserManager.DISALLOW_DATA_ROAMING, true)
        }
        try {
            if (cleared && changes.poll(RELOCK_WAIT_MS, TimeUnit.MILLISECONDS) != null) {
                // Applied in time: done for this process, whatever the read says (the report shows a SIM still on).
                synchronized(relockTries) { subs.forEach { relockTries[it] = RELOCK_TRIES } }
            }
            val still = runCatching { roamingReadings(context) }.getOrNull()?.filter { (sub, on) -> sub in subs && on == true }
            if (still.isNullOrEmpty()) {
                Log.i(LOG_TAG, "Relocked: roaming reads off on every SIM")
            } else {
                Log.w(LOG_TAG, "Roaming still reads on for ${still.size} SIM(s) after the relock")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Relock read-back failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            thread.quitSafely()
        }
    }

    /** Design 95 rev. 3 item 3: `DISALLOW_CONFIG_MOBILE_NETWORKS`. */
    fun applyMobileSettingsLock(dpm: DevicePolicyManager, admin: ComponentName, locked: Boolean) {
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, locked)
    }

    /** Each active SIM's `isDataRoamingEnabled`, from the slots - no permission needed (design 94 §2.4.2). */
    private fun roamingReadings(context: Context): Map<Int, Boolean?> {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return emptyMap()
        return (0 until tm.activeModemCount)
            .map { SubscriptionManager.getSubscriptionId(it) }
            .filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            .associateWith { sub -> runCatching { tm.createForSubscriptionId(sub).isDataRoamingEnabled }.getOrNull() }
    }

    private fun setRestriction(dpm: DevicePolicyManager, admin: ComponentName, restriction: String, on: Boolean) {
        try {
            if (on) dpm.addUserRestriction(admin, restriction) else dpm.clearUserRestriction(admin, restriction)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't ${if (on) "add" else "clear"} $restriction: ${e.javaClass.simpleName}")
        }
    }

    // ---- the report ----------------------------------------------------------------------------------------------

    private fun dataTelephony(context: Context): TelephonyManager? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm else tm.createForSubscriptionId(sub)
    }

    private fun restricted(context: Context, restriction: String): Boolean =
        runCatching { context.getSystemService(UserManager::class.java)?.hasUserRestriction(restriction) == true }.getOrDefault(false)

    /** Design 94 §5's `data_roaming`; `null` (left out) when telephony can't be read at all. */
    fun dataRoamingReport(context: Context): DataRoamingReport? = try {
        val tm = dataTelephony(context)
        val roaming = runCatching { tm?.isNetworkRoaming == true }.getOrDefault(false)
        val (country, network) = roamingPlace(
            roaming,
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.networkOperator }.getOrNull(),
        )
        val net = bestNet(context)
        DataRoamingReport(
            locked = restricted(context, UserManager.DISALLOW_DATA_ROAMING),
            roaming = roaming,
            country = country,
            network = network,
            enabled = runCatching { tm?.isDataRoamingEnabled }.getOrNull(),
            dataActive = net?.cellular == true && net.roaming,
            modems = runCatching { context.getSystemService(TelephonyManager::class.java)?.activeModemCount }.getOrNull(),
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Roaming report failed: ${e.javaClass.simpleName}")
        null
    }

    /** Design 95 rev. 3's `mobile_data`. */
    fun mobileDataReport(context: Context): MobileDataReport = MobileDataReport(
        enabled = runCatching { dataTelephony(context)?.isDataEnabled }.getOrNull(),
        sink = reportedSink(storedInputs(), bestNet(context), storedShape()).wire,
        settingsLocked = restricted(context, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS),
    )
}
