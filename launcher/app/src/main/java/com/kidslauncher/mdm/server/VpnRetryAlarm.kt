package com.kidslauncher.mdm.server

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.SyncRunner

private const val LOG_TAG = "VpnRetryAlarm"
private const val ACTION_VPN_RETRY = "com.kidslauncher.mdm.action.VPN_RETRY"

/** Not BackstopAlarm's (7) nor any other alarm's code, so none replaces another. */
private const val REQUEST_CODE = 116

/** The alarm's delay: the backstop sync's 30 minutes made 5 (design 116 §3.1). */
private const val RETRY_DELAY_MS = 5 * 60_000L

/**
 * Design 116 §3.1 (S-M2): the web filter's retry that survives deep sleep - one inexact while-idle alarm on elapsed
 * realtime ([BackstopAlarm]'s pattern, its own request code), armed while the filter is down ([vpnRetryAlarmAction]).
 * The service's own `Handler` chain (5 s doubling to 5 min) is the fast path while the phone is awake; the backstop
 * sync every 30 min stays beside both. While-idle alarms fire in Doze within Android's while-idle quota; one every 5
 * minutes is well inside it. No permission: inexact.
 */
object VpnRetryAlarm {
    /** Armed in this process (a new process re-arms on its first check: setting the same alarm again replaces it). */
    @Volatile
    var armed = false
        private set

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, VpnRetryReceiver::class.java).setAction(ACTION_VPN_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun arm(context: Context) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RETRY_DELAY_MS,
                pendingIntent(context),
            )
            armed = true
            Log.i(LOG_TAG, "Filter down: a retry in ${RETRY_DELAY_MS / 60_000} min")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the filter's retry: ${e.javaClass.simpleName}")
        }
    }

    fun cancel(context: Context) {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the filter's retry: ${e.javaClass.simpleName}")
        }
        armed = false
    }

    /** The alarm went off: it is no longer armed. */
    internal fun fired() {
        armed = false
    }
}

/** Only our own alarm reaches it (explicit component, not exported): always-on written for the stored shape and the
 * service started - `applyVpnRestrictions`' path from the stored inputs, without a sync - then the state checked, which
 * re-arms while it is still down. */
class VpnRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_VPN_RETRY) return
        val app = context.applicationContext
        VpnRetryAlarm.fired()
        MobileNetworkRuntime.storedShape()?.let { MobileNetworkRuntime.writeAlwaysOn(app, it) }
        try {
            KidVpnService.start(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't start the VPN service: ${e.javaClass.simpleName}")
        }
        VpnFilterWatch.check(app, VpnFilterEvent.CHECK)
    }
}

/**
 * Design 116 §3.1: the filter's state as the phone sees it now ([MobileNetworkRuntime.vpnFilterStateNow]), its down
 * period (stored with this boot's count, never measured across a boot or two clocks), the retry alarm and the sync
 * that tells the server - each a pure rule of [MobileNetwork]'s. Called by the service's retries and its failures, the
 * alarm, `onRevoke`, and the status report.
 */
object VpnFilterWatch {
    /** This process's start in elapsed realtime: its first minute starts no period ([VPN_PROCESS_GRACE_MS]). */
    private val processStartMs: Long = android.os.Process.getStartElapsedRealtime()

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        // A period then ends only when elapsed realtime runs back past its start.
        -1
    }

    /** The state now, the stored period moved by [event], the alarm armed or cancelled, and - unless [reporting], when
     * the report itself carries it - a sync asked for once per period past [VPN_REPORT_AFTER_MS] and once at the first
     * `UP` after a reported `down`. Never throws. */
    @Synchronized
    fun check(context: Context, event: VpnFilterEvent, reporting: Boolean = false): VpnFilterReport? = try {
        val app = context.applicationContext
        val state = MobileNetworkRuntime.vpnFilterStateNow()
        val now = SystemClock.elapsedRealtime()
        val mdm = LauncherPreferences.mdm()
        val stored = VpnDownPeriod.of(mdm.vpnDownPeriod())
        val period = vpnDownPeriod(stored, event, state, bootCount(app), now, processStartMs)
        if (period != stored) mdm.vpnDownPeriod(period?.id ?: "")
        val report = vpnFilterReport(state, period, now)
        when (vpnRetryAlarmAction(state, VpnRetryAlarm.armed)) {
            VpnAlarmAction.ARM -> VpnRetryAlarm.arm(app)
            VpnAlarmAction.CANCEL -> VpnRetryAlarm.cancel(app)
            VpnAlarmAction.KEEP -> Unit
        }
        val synced = period != null && mdm.vpnDownSynced() == period.id
        if (vpnFilterSyncDue(report.downMs, synced, state, mdm.vpnFilterReported()) == VpnSyncAction.REQUEST) {
            period?.let { mdm.vpnDownSynced(it.id) }
            mdm.vpnFilterReported(state.wire)
            if (!reporting) {
                Log.i(LOG_TAG, "Filter ${state.wire}: telling the server")
                SyncRunner.request(app, "vpn_filter")
            }
        }
        report
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't check the filter: ${e.javaClass.simpleName}")
        null
    }

    /** The status report's `vpn_filter`, computed now (QA M1), and recorded as what the server was last told. */
    fun report(context: Context): VpnFilterReport? = check(context, VpnFilterEvent.CHECK, reporting = true)?.also {
        runCatching { LauncherPreferences.mdm().vpnFilterReported(it.state) }
    }
}
