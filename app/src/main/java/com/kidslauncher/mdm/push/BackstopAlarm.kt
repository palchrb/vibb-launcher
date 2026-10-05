package com.kidslauncher.mdm.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LEGACY_LOCATION_POLICY
import com.kidslauncher.mdm.server.currentPolicyDecision

private const val LOG_TAG = "BackstopAlarm"
private const val ACTION_BACKSTOP = "com.kidslauncher.mdm.action.BACKSTOP_SYNC"

/** Not TimeRuleAlarm's request code (0), so the two never replace each other. */
private const val REQUEST_CODE = 7

/**
 * The backstop sync (handy step 7, design 07 §2): one inexact while-idle alarm on elapsed
 * realtime, [backstopDelayMs] ahead (30 min; 15 while on SSE with the stream down; sooner when
 * the parent's interval location policy wants a fix). While-idle alarms fire in Doze (about once
 * per 9 minutes per app at most), unlike JobScheduler/WorkManager, which only run in maintenance
 * windows - WorkManager was removed for hours-long overnight gaps (v0.8.0). Re-armed after every
 * sync and whenever the anchor service starts (process start after unlock, boot, update).
 */
object BackstopAlarm {
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BackstopReceiver::class.java).setAction(ACTION_BACKSTOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Elapsed-realtime target of the armed alarm (0 = none known in this process). */
    @Volatile
    private var armedAt = 0L

    /**
     * [afterSync] (a sync just ran, or the anchor starts): arm at the computed delay. Otherwise
     * (the SSE stream went down) only ever move the alarm earlier - a stream that keeps dropping
     * must not keep pushing the backstop out until it never fires.
     */
    fun schedule(context: Context, afterSync: Boolean = true) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val policy = currentPolicyDecision().policy
            val locationPolicy = policy?.let { it.locationPolicy ?: LEGACY_LOCATION_POLICY }
            val sinceLastFix = System.currentTimeMillis() - LauncherPreferences.mdm().lastActiveLocationFetchAtMs()
            val delay = backstopDelayMs(PushState.lastDecision.transport, PushState.sseConnected, locationPolicy, sinceLastFix)
            val target = SystemClock.elapsedRealtime() + delay
            val armed = armedAt
            if (!afterSync && armed > SystemClock.elapsedRealtime() && armed <= target) return
            armedAt = target
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, target, pendingIntent(context))
            Log.i(LOG_TAG, "Next backstop sync in ${delay / 60_000} min")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't schedule the backstop sync", e)
        }
    }
}

/** Only our own alarm reaches it (explicit component, not exported). */
class BackstopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BACKSTOP) return
        // The wake lock is taken inside, before the service start: the alarm's own wake lock
        // ends when onReceive returns.
        SyncRunner.request(context.applicationContext, "backstop")
    }
}
