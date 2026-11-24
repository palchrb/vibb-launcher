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
import android.os.SystemClock
import android.os.UserManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.util.concurrent.Executors
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

    /** rc.1's record. Design 97 code QA L2: its third field is rc.1's own reading of the served `data_roaming` - the
     * derived `"off"` under Ingen data and Bare Vibb, so `roamingLocked || abroadArmed` -, so an rc.1 launcher after a
     * rollback keeps a sink abroad as it would have itself; this launcher reads `roamingLocked` from the second record. */
    fun encodeInputs(inputs: VpnInputs): String =
        listOf(inputs.filterOn, inputs.parentSink, inputs.roamingLocked || inputs.abroadArmed)
            .joinToString(",") { if (it) "1" else "0" }

    /** `null` for an absent or unreadable record. */
    fun decodeInputs(raw: String?): VpnInputs? {
        val parts = raw?.split(",")?.takeIf { it.size == 3 && it.all { p -> p == "0" || p == "1" } } ?: return null
        return VpnInputs(parts[0] == "1", parts[1] == "1", parts[2] == "1")
    }

    /** Design 97 (Q97-M3): the second record, `abroadArmed,kidOff,roamingLocked` (code QA L2: the roaming lock itself,
     * as the first record carries rc.1's reading of it). */
    fun encodeInputsR4(inputs: VpnInputs): String =
        listOf(inputs.abroadArmed, inputs.kidOff, inputs.roamingLocked).joinToString(",") { if (it) "1" else "0" }

    /** [base] with the second record's inputs; an absent or unreadable record (the first start after the upgrade from
     * rc.1) reads both false with the first record's `roamingLocked` - rc.1's own rule until the first `apply()`. */
    fun withInputsR4(base: VpnInputs, raw: String?): VpnInputs {
        val parts = raw?.split(",")?.takeIf { it.size == 3 && it.all { p -> p == "0" || p == "1" } } ?: return base
        return base.copy(abroadArmed = parts[0] == "1", kidOff = parts[1] == "1", roamingLocked = parts[2] == "1")
    }

    /** Code QA L4: `apply()`'s write of the inputs and the kid's tap don't interleave. */
    private val inputsLock = Any()

    /** The inputs `apply()` last wrote; before any (a launcher from before this round), the cached filter switch alone
     * - a failed read counts the filter on, as `KidVpnService.filterWanted` always did. */
    fun storedInputs(): VpnInputs = try {
        val mdm = LauncherPreferences.mdm()
        val base = decodeInputs(mdm.vpnInputs()) ?: VpnInputs(filterOn = mdm.vpnFilterEnabled(), parentSink = false, roamingLocked = false)
        withInputsR4(base, mdm.vpnInputsR4())
    } catch (e: Exception) {
        VpnInputs(filterOn = true, parentSink = false, roamingLocked = false)
    }

    /** Both records: rc.1's as it reads it (a rollback), and design 97's second one. Code QA L4: the kid's Off is read
     * again here ([mask], the applied policy's `quick_controls_mask`), under the lock the kid's tap writes under, so a tap
     * between `apply()`'s read and this write isn't overwritten. */
    fun storeInputs(inputs: VpnInputs, mask: Long?) = synchronized(inputsLock) {
        val fresh = inputs.copy(kidOff = kidOff(mask))
        val mdm = LauncherPreferences.mdm()
        mdm.vpnFilterEnabled(fresh.filterOn)
        mdm.vpnInputs(encodeInputs(fresh))
        mdm.vpnInputsR4(encodeInputsR4(fresh))
    }

    /** Design 97 §4.3: the kid's row now, from the cached policy (null without an accepted one: [shown] false). */
    fun kidRowNow(context: Context, policy: com.kidslauncher.mdm.server.dto.PolicyResponse?): KidMobileRow {
        val p = policy?.takeIf { it.quickControlsMask and QuickControlFeature.MOBILE_DATA != 0L } ?: return KidMobileRow.HIDDEN
        return kidMobileRow(
            shown = true,
            kidOff = runCatching { LauncherPreferences.mdm().kidMobileOff() }.getOrDefault(false),
            lift = OfflineOverride.isActive() || RestrictionsPause.isActive(),
            parentVibb = parentSinkOn(p.appsMobile, overrideActive = false),
            where = whereNow(context),
            choice = abroadChoice(p),
            dataEnabled = runCatching { dataTelephony(context)?.isDataEnabled }.getOrNull(),
        )
    }

    /**
     * Design 97 §4.3, the kid's tap: the choice stored and written into the VPN's second record before the shape is
     * re-decided, so the service, `apply()` and [sinkWanted] read one source (Q97-M3); then the VPN reshapes and a sync
     * reports it. Only while the row is shown, so the bit is set.
     */
    fun setKidOff(context: Context, off: Boolean) {
        val inputs = synchronized(inputsLock) {
            val mdm = LauncherPreferences.mdm()
            mdm.kidMobileOff(off)
            storedInputs().copy(kidOff = off).also { mdm.vpnInputsR4(encodeInputsR4(it)) }
        }
        // A shape other than NONE needs the service: it decides from the stored inputs (serviceShape) and writes
        // always-on for it; a NONE it stops by itself.
        if (vpnShape(inputs, bestNet(context)) != VpnShape.NONE || storedShape() != VpnShape.NONE) KidVpnService.start(context)
        CommandListenerService.requestSync(context, "kid mobile data")
    }

    /** Design 97 §4.3: the kid's Off counts while the row is shown ([mask] bit 32); no lift touches it (D4). */
    fun kidOff(mask: Long?): Boolean =
        mask != null && mask and QuickControlFeature.MOBILE_DATA != 0L &&
            runCatching { LauncherPreferences.mdm().kidMobileOff() }.getOrDefault(false)

    fun storedShape(): VpnShape? = runCatching { VpnShape.of(LauncherPreferences.mdm().vpnShape()) }.getOrNull()

    /** Design 97 code QA L1: the reason a sink was last decided for with a network seen ([sinkReasonToHold]), which a
     * held sink keeps naming ([reportedSink]). Written only on a change. Never throws. */
    fun holdSinkReason(inputs: VpnInputs, net: BestNet?, shape: VpnShape) {
        val reason = sinkReasonToHold(inputs, net, shape) ?: return
        runCatching {
            val mdm = LauncherPreferences.mdm()
            if (mdm.vpnSinkReason() != reason.wire) mdm.vpnSinkReason(reason.wire)
        }
    }

    private fun heldSinkReason(): SinkReason? =
        runCatching { LauncherPreferences.mdm().vpnSinkReason() }.getOrNull()?.let { wire -> SinkReason.entries.firstOrNull { it.wire == wire } }

    /**
     * The single owner of always-on (design 95 §2.2, [alwaysOnFor]): records [shape] as the decision, then writes what
     * it needs - only on a change, so a sync that decides the same shape writes nothing. With [keepLockdown] (`apply()`
     * leaving the sink while the tunnel is up, [applyLeavesLockdownToSwitch]) a lockdown that is on stays on: the
     * tunnel's switch turns it off last (design 120 fix A). Never throws.
     */
    fun writeAlwaysOn(context: Context, shape: VpnShape, keepLockdown: Boolean = false) {
        // Design 115 (code QA L1): app push is told only of a real change of the decision or of Android's always-on.
        var changed = storedShape() != shape
        runCatching { LauncherPreferences.mdm().vpnShape(shape.wire) }
        val wanted = alwaysOnFor(shape)
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            val pkg = if (wanted.ownPackage) context.packageName else null
            val current = dpm.getAlwaysOnVpnPackage(admin)
            val currentLockdown = current != null && dpm.isAlwaysOnVpnLockdownEnabled(admin)
            // Design 129 Q9: rkpd outside the sink reaches the network only through lockdown's allow list.
            val allow = lockdownAllowList(wanted.lockdown, Rkpd.packages(context))
            val currentAllow = if (currentLockdown) dpm.getAlwaysOnVpnLockdownWhitelist(admin).orEmpty().toSet() else emptySet()
            if (current == pkg && currentLockdown == wanted.lockdown && currentAllow == allow) return
            if (keepLockdown && currentLockdown && !wanted.lockdown) {
                Log.i(LOG_TAG, "Always-on: lockdown stays on until the tunnel's switch (${shape.wire})")
                return
            }
            changed = true
            dpm.setAlwaysOnVpnPackage(admin, pkg, wanted.lockdown, allow)
            Log.i(LOG_TAG, "Always-on: ${pkg ?: "none"}, lockdown ${wanted.lockdown} (${shape.wire})")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set always-on for ${shape.wire}: ${e.javaClass.simpleName}")
        } finally {
            // Design 115 §2.4 (QA L2): after the decision and Android's always-on are both written, app push looks at
            // its hold again (on its own thread) - only when one of them changed.
            if (changed) com.kidslauncher.mdm.push.up.UpRuntime.shapeChanged()
        }
    }

    /** Design 115 (QA L1): Android's always-on VPN is ours with lockdown - what cuts every other app while no tunnel is
     * up. Device owner only; `false` on any doubt. */
    fun lockdownOurs(context: Context): Boolean = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            false
        } else {
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            dpm.getAlwaysOnVpnPackage(admin) == context.packageName && dpm.isAlwaysOnVpnLockdownEnabled(admin)
        }
    } catch (e: Exception) {
        false
    }

    // ---- the network ---------------------------------------------------------------------------------------------

    /** [BestNet] from a network's capabilities. A VPN's carry its underlying network's transports. Design 97: on
     * cellular, [BestNet.abroad] from telephony ([whereNow]): registration comes before the data attach. [BestNet.unsure]
     * while `where` is unknown or the network is suspended (out of service - measured on the emulator: the network's
     * capabilities lose `NOT_SUSPENDED` and gain `NOT_ROAMING` in one update, with a stale operator). The capability, not
     * `getDataState()`, which lags the callback by some ms both ways (measured), and no permission is needed. */
    fun bestNetOf(context: Context, caps: NetworkCapabilities): BestNet {
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val where = if (cellular) whereNow(context) else null
        val suspended = cellular && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        return BestNet(
            cellular = cellular,
            roaming = cellular && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            abroad = where == Where.ABROAD,
            unsure = where == Where.UNKNOWN || suspended,
        )
    }

    /** The launcher's default network - outside the VPN in every shape, so the physical one. `null` = none. */
    fun bestNet(context: Context): BestNet? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let { bestNetOf(context, it) }
    } catch (e: Exception) {
        null
    }

    /** Design 116 §3.1 (QA M1): the web filter's state now - the stored inputs' switch and the live service's tunnel and
     * shape ([KidVpnService.liveTunnel]), never the persisted shape, which keeps a dead tunnel's on purpose. */
    fun vpnFilterStateNow(): VpnFilterState {
        val (tunnelUp, shape) = KidVpnService.liveTunnel()
        return vpnFilterState(storedInputs().filterOn, tunnelUp, shape)
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

    // ---- Blokker 2G (design 126 A5) -------------------------------------------------------------------------------

    private const val PREFS_2G = "mobile_network_2g"
    private const val KEY_2G_WHERE = "block_2g_where"

    /** Code QA M1: the pass, the service-state listener and the hold-down's re-check read the switch and the lifts,
     * decide and write under this one lock. */
    private val lock2g = Any()

    /** The parent's switches as the last pass read them: "Blokker 2G" and "Tillat 2G i utlandet". */
    private var block2gValue: Boolean? = null
    private var allow2gAbroadValue: Boolean? = null

    /** Code QA L2: what the last decision applied - the report's `cellular_2g`. */
    private var applied2g: Cellular2g? = null

    /** Code QA L1: when an abroad lift last cleared the restriction (elapsed realtime), for [reblockWaitMs]. */
    private var abroadLiftAtMs: Long? = null
    private var recheckScheduled = false

    private class Listener2g(val tm: TelephonyManager, val callback: TelephonyCallback, val subId: Int)

    private var serviceListener: Listener2g? = null
    private var subWatcher: Pair<TelephonyManager, TelephonyCallback>? = null
    private val executor2g by lazy { Executors.newSingleThreadScheduledExecutor() }

    private fun prefs2g(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_2G, Context.MODE_PRIVATE)

    /** §3.6.1: the kept where ([where2gKept]) from a fresh read, stored in device-protected prefs (a reboot abroad keeps
     * it) only when it moved. `null` = never read (counts as home). Never throws. */
    private fun where2gNow(context: Context): Where? = try {
        val prefs = prefs2g(context)
        val last = prefs.getString(KEY_2G_WHERE, null)?.let { wire -> Where.entries.firstOrNull { it.wire == wire } }
        val kept = where2gKept(last, whereNow(context))
        if (kept != null && kept != last) prefs.edit().putString(KEY_2G_WHERE, kept.wire).apply()
        kept
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read where for 2G: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Design 126 A5: `DISALLOW_CELLULAR_2G` as [cellular2gBlocked] says - written only on a change - and the service-state
     * listener (§3.6.3) on the default data SIM while the parent's switch and "Tillat 2G i utlandet" ([allowAbroad]) are
     * both on, so a crossing between two passes lifts or sets it; with the abroad lift off nothing depends on where the
     * phone is, so nothing listens. The parent's explicit per-phone switch, so whether or not the phone is managed in
     * hardening's sense, as roaming.
     */
    fun applyCellular2g(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        value: Boolean?,
        allowAbroad: Boolean?,
        overrideActive: Boolean,
    ) {
        synchronized(lock2g) {
            block2gValue = value
            allow2gAbroadValue = allowAbroad
            decide2g(context, dpm, admin, value, allowAbroad, overrideActive)
            if (listens2g()) registerServiceListener(context) else unregisterServiceListener()
        }
    }

    /** Under [lock2g]: the service-state listener matters only while the abroad lift can happen. */
    private fun listens2g(): Boolean = block2gValue == true && allow2gAbroadValue == true

    /** Under [lock2g]. `true` when the restriction changed. A re-block within [REBLOCK_HOLD_MS] of an abroad lift waits
     * (code QA L1): the report keeps saying `abroad` and a re-check runs when the hold is over. The parent turning
     * "Tillat 2G i utlandet" off blocks at once (no border flip-flop to damp). */
    private fun decide2g(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        value: Boolean?,
        allowAbroad: Boolean?,
        overrideActive: Boolean,
    ): Boolean {
        val state = cellular2gState(value, overrideActive, where2gNow(context), allowAbroad)
        val wanted = state == Cellular2g.BLOCKED
        val now = restricted(context, UserManager.DISALLOW_CELLULAR_2G)
        val nowMs = SystemClock.elapsedRealtime()
        if (wanted && !now && allowAbroad == true) {
            val wait = reblockWaitMs(abroadLiftAtMs, nowMs)
            if (wait > 0) {
                applied2g = Cellular2g.ABROAD
                scheduleRecheck(context, wait)
                return false
            }
        }
        applied2g = state
        if (wanted == now) return false
        setRestriction(dpm, admin, UserManager.DISALLOW_CELLULAR_2G, wanted)
        if (!wanted && state == Cellular2g.ABROAD) abroadLiftAtMs = nowMs
        Log.i(LOG_TAG, "2G ${if (wanted) "blocked" else "allowed"} (${state.wire})")
        return true
    }

    /** Between passes (the listener, the hold's end, a new data SIM): the switch the last pass read, the lifts read now -
     * all under [lock2g]. A change asks for a sync, whose report says why (`cellular_2g`, the page's "Av i utlandet"). */
    private fun recheck2g(app: Context) {
        try {
            val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(app.packageName)) return
            val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
            val changed = synchronized(lock2g) {
                val lift = OfflineOverride.isActive() || RestrictionsPause.isActive()
                decide2g(app, dpm, admin, block2gValue, allow2gAbroadValue, lift)
            }
            if (changed) CommandListenerService.requestSync(app, "2g")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "2G re-check failed: ${e.javaClass.simpleName}")
        }
    }

    /** Under [lock2g]: one re-check at the hold's end. */
    private fun scheduleRecheck(context: Context, waitMs: Long) {
        if (recheckScheduled) return
        recheckScheduled = true
        val app = context.applicationContext
        executor2g.schedule({
            synchronized(lock2g) { recheckScheduled = false }
            recheck2g(app)
        }, waitMs + 500, TimeUnit.MILLISECONDS)
    }

    /** Under [lock2g]. Code QA M2: on the default data SIM, registered again when that changes (a SIM put in after
     * setup, a swap, the data SIM switched) - checked every pass and on `ActiveDataSubscriptionIdListener`. */
    private fun registerServiceListener(context: Context) {
        val app = context.applicationContext
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val current = serviceListener
        if (current != null && current.subId == subId) return
        current?.let { runCatching { it.tm.unregisterTelephonyCallback(it.callback) } }
        serviceListener = null
        if (subWatcher == null) {
            // The active data SIM changing (a SIM put in, a swap, the data SIM switched): listen on it instead.
            val base = app.getSystemService(TelephonyManager::class.java)
            val watcher = object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
                override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                    synchronized(lock2g) { if (listens2g()) registerServiceListener(app) }
                    recheck2g(app)
                }
            }
            try {
                base?.registerTelephonyCallback(executor2g, watcher)
                if (base != null) subWatcher = base to watcher
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't watch the data SIM: ${e.javaClass.simpleName}")
            }
        }
        val tm = dataTelephony(app) ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
            override fun onServiceStateChanged(serviceState: ServiceState) = recheck2g(app)
        }
        try {
            tm.registerTelephonyCallback(executor2g, callback)
            serviceListener = Listener2g(tm, callback, subId)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't listen to the service state: ${e.javaClass.simpleName}")
        }
    }

    /** Under [lock2g]. */
    private fun unregisterServiceListener() {
        serviceListener?.let { runCatching { it.tm.unregisterTelephonyCallback(it.callback) } }
        serviceListener = null
        subWatcher?.let { (tm, watcher) -> runCatching { tm.unregisterTelephonyCallback(watcher) } }
        subWatcher = null
    }

    /** Code QA L2: the report's `cellular_2g` - what the last decision applied; before any in this process, Android's
     * restriction as it is (`blocked`) or `off`. */
    private fun reported2g(context: Context): Cellular2g = synchronized(lock2g) { applied2g }
        ?: if (restricted(context, UserManager.DISALLOW_CELLULAR_2G)) Cellular2g.BLOCKED else Cellular2g.OFF

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

    fun dataTelephony(context: Context): TelephonyManager? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm else tm.createForSubscriptionId(sub)
    }

    private fun restricted(context: Context, restriction: String): Boolean =
        runCatching { context.getSystemService(UserManager::class.java)?.hasUserRestriction(restriction) == true }.getOrDefault(false)

    /** Design 97 §4.1: [whereOf] on the default data SIM ([tm]); none of the reads needs a permission. */
    fun whereNow(context: Context, tm: TelephonyManager? = dataTelephony(context)): Where = try {
        val airplane = android.provider.Settings.Global.getInt(
            context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0,
        ) != 0
        whereOf(
            airplane = airplane,
            roaming = runCatching { tm?.isNetworkRoaming == true }.getOrDefault(false),
            network = runCatching { tm?.networkOperator }.getOrNull(),
            simOperator = runCatching { tm?.simOperator }.getOrNull(),
            netCc = runCatching { tm?.networkCountryIso }.getOrNull(),
            simCc = runCatching { tm?.simCountryIso }.getOrNull(),
            inService = inService(tm),
        )
    } catch (e: Exception) {
        Where.UNKNOWN
    }

    /** In service on the data SIM: voice in service, or a mobile data registration (a data-only network). `null` when
     * unreadable (no `READ_PHONE_STATE`, no service state yet). */
    // `isRegistered` is deprecated from Android 16 for `getNetworkRegistrationState()`, which minSdk 34 lacks; it still works.
    @Suppress("DEPRECATION")
    private fun inService(tm: TelephonyManager?): Boolean? = runCatching {
        val state = tm?.serviceState ?: return@runCatching null
        state.state == android.telephony.ServiceState.STATE_IN_SERVICE ||
            state.networkRegistrationInfoList.any {
                it.transportType == android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                    it.domain and android.telephony.NetworkRegistrationInfo.DOMAIN_PS != 0 && it.isRegistered
            }
    }.getOrNull()

    /** Design 94 §5's `data_roaming`; `null` (left out) when telephony can't be read at all. */
    fun dataRoamingReport(context: Context): DataRoamingReport? = try {
        val tm = dataTelephony(context)
        val roaming = runCatching { tm?.isNetworkRoaming == true }.getOrDefault(false)
        val networkOperator = runCatching { tm?.networkOperator }.getOrNull()
        val where = whereNow(context, tm)
        val (country, network) = roamingPlace(
            roaming || where == Where.ABROAD, runCatching { tm?.networkCountryIso }.getOrNull(), networkOperator,
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
            whereIs = where.wire,
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Roaming report failed: ${e.javaClass.simpleName}")
        null
    }

    /** Design 95 rev. 3's `mobile_data`; design 97's `kid` while the row is shown in the cached policy. */
    fun mobileDataReport(context: Context): MobileDataReport = MobileDataReport(
        enabled = runCatching { dataTelephony(context)?.isDataEnabled }.getOrNull(),
        sink = reportedSink(storedInputs(), bestNet(context), storedShape(), heldSinkReason()).wire,
        settingsLocked = restricted(context, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS),
        kid = (cachedPolicy() as? CachedPolicy.Ok)?.policy?.quickControlsMask
            ?.takeIf { it and QuickControlFeature.MOBILE_DATA != 0L }
            ?.let { if (runCatching { LauncherPreferences.mdm().kidMobileOff() }.getOrDefault(false)) "off" else "on" },
        cellular2g = reported2g(context).wire,
    )
}
