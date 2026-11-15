package com.kidslauncher.mdm.assistant

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "AirplaneRuntime"
private const val PREFS = "airplane_mode"
private const val KEY_PERIODS = "periods"
private const val KEY_LAST_ERROR = "last_error"
private const val KEY_LAST_ERROR_AT = "last_error_at_ms"
private const val KEY_LAST_ON_START = "last_on_start_ms"
private const val KEY_KID_ON_START = "kid_on_start_ms"
private const val KEY_WIFI_KEPT = "wifi_kept"
private const val KEY_APPS_CHANGED = "default_apps_changed"
private const val KEY_ROUTE_OPEN = "airplane_route_open"

/** `TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED` and its extras (system API, so spelled here). */
private const val ACTION_ECBM_CHANGED = "android.intent.action.EMERGENCY_CALLBACK_MODE_CHANGED"
private const val EXTRA_IN_ECBM = "android.telephony.extra.PHONE_IN_ECM_STATE"
private const val EXTRA_IN_ECBM_LEGACY = "phoneinECMState"

/** §5.1: an error older than this isn't reported. */
private const val ERROR_REPORT_MS = 24 * 60 * 60_000L

/** §4.1: after an Off, the sync waits at most this long for a validated network. */
private const val NETWORK_WAIT_MS = 60_000L

/** Code QA L3: a row's render reuses the last emergency check this long (elapsed); the route always reads it fresh. */
private const val EMERGENCY_ROW_CACHE_MS = 5_000L

/**
 * Design 98: Flymodus's state on the phone - the periods (`AirplaneLog`, §5.1), the last error, Wi-Fi in airplane mode,
 * the moved default apps, the route's DE marker - all in device-protected prefs, so the edge receiver works before the
 * first unlock. The edge receiver writes synchronously ([onAirplaneChanged]), so the report an emergency call's sync
 * sends already carries the closed period.
 */
object AirplaneRuntime {
    /** Vibb's own Off is running ([AirplaneRoute.turnOff]: `kid`; the parent's switch going on: `parent`), with the
     * elapsed time it started - an edge in that window is Vibb's doing. */
    @Volatile
    private var vibbOff: Pair<String, Long>? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Something a row shows changed: the listeners run on the main thread. */
    fun changed() {
        main.post { listeners.forEach { runCatching(it) } }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (e: Exception) {
        false
    }

    fun roleHeld(context: Context): Boolean = try {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    } catch (e: Exception) {
        false
    }

    /** The parent's "Blokker flymodus" (or Vibb's own Off, for its second) - `DISALLOW_AIRPLANE_MODE` as Android has it. */
    fun restricted(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.hasUserRestriction(UserManager.DISALLOW_AIRPLANE_MODE) == true
    } catch (e: Exception) {
        false
    }

    /** Emergency Callback Mode, from its sticky broadcast (best effort, as `SelfUpdate` reads it). */
    fun ecbm(context: Context): Boolean = try {
        val sticky = ContextCompat.registerReceiver(context, null, IntentFilter(ACTION_ECBM_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        sticky?.getBooleanExtra(EXTRA_IN_ECBM, false) == true || sticky?.getBooleanExtra(EXTRA_IN_ECBM_LEGACY, false) == true
    } catch (e: Exception) {
        false
    }

    /** D3: a turn-on is refused now (ECBM, an emergency call in the last 10 min). Reads the call log: never on the main
     * thread ([rowState] for the rows). */
    fun emergencyNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = runCatching { com.kidslauncher.mdm.calls.CallSystem.lastEmergencyCallMs(context, nowMs) }.getOrNull()
        val blocks = emergencyBlocks(ecbm(context), last, nowMs)
        emergencySeen = blocks to android.os.SystemClock.elapsedRealtime()
        return blocks
    }

    /** The last [emergencyNow] and when (elapsed). */
    @Volatile
    private var emergencySeen: Pair<Boolean, Long>? = null

    private val emergencyReading = AtomicBoolean(false)

    /**
     * Code QA L3: [emergencyNow] for a row's render (main thread) - the last value read, at most
     * [EMERGENCY_ROW_CACHE_MS] old; an older or missing one is read again on a thread of its own (one at a time) and the
     * rows drawn again if it changed. Only a refused turn-on's line rests on it: the route reads [emergencyNow] itself.
     */
    private fun emergencyForRow(context: Context): Boolean {
        val seen = emergencySeen
        val fresh = seen != null && android.os.SystemClock.elapsedRealtime() - seen.second < EMERGENCY_ROW_CACHE_MS
        if (!fresh && emergencyReading.compareAndSet(false, true)) {
            val app = context.applicationContext
            Thread {
                try {
                    if (emergencyNow(app) != (seen?.first ?: false)) changed()
                } finally {
                    emergencyReading.set(false)
                }
            }.start()
        }
        return seen?.first == true
    }

    /**
     * The kid's Flymodus row ([airplaneRow] through [row], given the error to show): the stored error, or the emergency
     * refusal while one holds - looked at only when the row would show Off, the cool-down or a failure (code QA L3: no
     * call-log query for a hidden row, an on or busy one, and none on the main thread).
     */
    fun rowState(context: Context, nowMs: Long, row: (Pair<String, Long>?) -> AirplaneRowState): AirplaneRowState {
        val state = row(lastError(context))
        if (state != AirplaneRowState.OFF && state != AirplaneRowState.COOLDOWN && state != AirplaneRowState.FAILED) return state
        return if (emergencyForRow(context)) row(AirplaneError.EMERGENCY to nowMs) else state
    }

    fun periods(context: Context): List<AirplanePeriod> = try {
        prefs(context).getString(KEY_PERIODS, null)
            ?.let { ServerJson.decodeFromString(ListSerializer(AirplanePeriod.serializer()), it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun storePeriods(context: Context, periods: List<AirplanePeriod>) {
        prefs(context).edit().putString(KEY_PERIODS, ServerJson.encodeToString(ListSerializer(AirplanePeriod.serializer()), periods)).commit()
    }

    /** The last refused or failed turn-on and when (wall ms). */
    fun lastError(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val error = p.getString(KEY_LAST_ERROR, null) ?: return null
        return error to p.getLong(KEY_LAST_ERROR_AT, 0)
    }

    fun recordError(context: Context, error: String?) {
        val edit = prefs(context).edit()
        if (error == null) edit.remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_AT) else edit.putString(KEY_LAST_ERROR, error).putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
        edit.apply()
        changed()
    }

    fun lastOnStartMs(context: Context): Long? = prefs(context).getLong(KEY_LAST_ON_START, -1).takeIf { it >= 0 }

    /** The cool-down's start (§2.2 step 1): before the report-only post. */
    fun recordOnStart(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_ON_START, atMs).commit()
    }

    /** §5.1's attribution: the kid's route shows its session at [atMs] (code QA L2: right before the window, after the
     * report-only post, so a slow post can't push the edge past [KID_ON_WINDOW_MS]); `null` - the parent's setup step -
     * clears it, so that edge is `other`. */
    fun recordKidOnStart(context: Context, atMs: Long?) {
        val edit = prefs(context).edit()
        if (atMs == null) edit.remove(KEY_KID_ON_START) else edit.putLong(KEY_KID_ON_START, atMs)
        edit.commit()
    }

    private fun kidOnStartMs(context: Context): Long? = prefs(context).getLong(KEY_KID_ON_START, -1).takeIf { it >= 0 }

    /** §3.5: Wi-Fi stays on in airplane mode, as this phone last saw it; `null` never set up. */
    fun wifiKept(context: Context): Boolean? = prefs(context).let { if (it.contains(KEY_WIFI_KEPT)) it.getBoolean(KEY_WIFI_KEPT, false) else null }

    fun storeWifiKept(context: Context, kept: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_KEPT, kept).apply()
        changed()
    }

    /** §3.2: the roles the default-apps window moved and couldn't take back. */
    fun defaultAppsChanged(context: Context): List<String> = try {
        prefs(context).getString(KEY_APPS_CHANGED, null)
            ?.let { ServerJson.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun storeDefaultAppsChanged(context: Context, roles: List<String>) {
        prefs(context).edit().putString(KEY_APPS_CHANGED, ServerJson.encodeToString(ListSerializer(String.serializer()), roles)).apply()
    }

    /** §2.2 step 4: the route's window is open in this boot ([bootCount]); cleared last at its close. */
    fun markRouteOpen(context: Context, bootCount: Int) {
        prefs(context).edit().putInt(KEY_ROUTE_OPEN, bootCount).commit()
    }

    fun clearRouteOpen(context: Context) {
        prefs(context).edit().remove(KEY_ROUTE_OPEN).commit()
    }

    fun routeMarked(context: Context): Boolean = prefs(context).contains(KEY_ROUTE_OPEN)

    /** Vibb's own Off ([by] `kid`) or the parent's switch ([by] `parent`) is about to end airplane mode; `null` when done. */
    fun vibbEnding(by: String?) {
        vibbOff = by?.let { it to android.os.SystemClock.elapsedRealtime() }
    }

    private fun vibbOffNow(): String? = vibbOff?.takeIf { android.os.SystemClock.elapsedRealtime() - it.second < 10_000 }?.first

    /** `ACTION_AIRPLANE_MODE_CHANGED`: the period opened or closed, synchronously (`commit`), then the rows. */
    fun onAirplaneChanged(context: Context, on: Boolean) {
        val now = System.currentTimeMillis()
        val by = if (on) {
            onByOf(kidOnStartMs(context), now)
        } else {
            val placed = runCatching { com.kidslauncher.mdm.calls.CallPrefs.recordedEmergencyAtMs(context) }.getOrNull()
            offByOf(vibbOffNow(), placed, now, ecbm(context))
        }
        storePeriods(context, periodsAfterEdge(periods(context), on, now, by, now))
        Log.i(LOG_TAG, "Airplane mode ${if (on) "on" else "off"} by $by")
        AirplaneRoute.airplaneEdge(on)
        changed()
        if (!on) syncOnNetwork(context)
    }

    /** At process start and boot (M7): a period the phone missed is opened or closed as `other`. */
    fun reconcile(context: Context) {
        val on = isOn(context)
        val periods = periods(context)
        val open = periods.lastOrNull()?.offMs == null && periods.isNotEmpty()
        if (on != open) {
            val now = System.currentTimeMillis()
            storePeriods(context, periodsAfterEdge(periods, on, now, AirplaneBy.OTHER, now))
        }
    }

    /** §4.1: after an Off, the first validated network (at most a minute) asks for a sync, so the parent's page follows. */
    private fun syncOnNetwork(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            @Volatile
            var done = false

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (done || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                done = true
                runCatching { cm.unregisterNetworkCallback(this) }
                com.kidslauncher.mdm.push.SyncRunner.request(app, "airplane_off")
            }
        }
        try {
            // Not the default network: under the always-on VPN that is the VPN, which reads validated at once (measured
            // on jelly36) - a real network (NOT_VPN is in a request's defaults) with validated internet.
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            cm.registerNetworkCallback(request, callback)
            main.postDelayed({
                if (!callback.done) {
                    callback.done = true
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }, NETWORK_WAIT_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't wait for a network: ${e.javaClass.simpleName}")
        }
    }

    /** §5.1: `airplane_mode` for the status report. */
    fun report(context: Context, goingOn: Boolean): AirplaneModeReport? = try {
        val now = System.currentTimeMillis()
        val error = lastError(context)?.takeIf { (word, at) -> word != AirplaneError.COOLDOWN && now - at in 0 until ERROR_REPORT_MS }?.first
        AirplaneModeReport(
            on = isOn(context),
            goingOn = goingOn,
            assistant = roleHeld(context),
            wifiKept = wifiKept(context),
            lastError = error,
            defaultAppsChanged = defaultAppsChanged(context),
            periods = prunedPeriods(periods(context), now),
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Airplane report failed: ${e.javaClass.simpleName}")
        null
    }

    /** The edge receiver, registered by the anchor service for the process. */
    class EdgeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return
            onAirplaneChanged(context, intent.getBooleanExtra("state", isOn(context)))
        }
    }

    @Volatile
    private var edgeRegistered = false

    /** Registers [EdgeReceiver] once for the process (the anchor service's start). */
    fun registerEdges(context: Context) {
        if (edgeRegistered) return
        edgeRegistered = true
        try {
            // Exported: an emergency call's end of airplane mode is sent by com.android.phone (the radio uid), which a
            // not-exported receiver never gets (measured on jelly36: the 112 call's edge was missed). The action is a
            // protected broadcast - only the platform sends it.
            ContextCompat.registerReceiver(
                context.applicationContext, EdgeReceiver(), IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            edgeRegistered = false
            Log.w(LOG_TAG, "Couldn't watch airplane mode: ${e.javaClass.simpleName}")
        }
    }
}
