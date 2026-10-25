package com.kidslauncher.mdm.timerules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.AnchorNotice
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.currentPolicyDecision

/** The anchor notification's "Be om mer tid" (design r13 §2.4). */
const val ACTION_ASK_TIME = "com.kidslauncher.mdm.action.ASK_TIME"

private const val LOG_TAG = "TimeAskReceiver"

/**
 * "Be om mer tid" in the anchor's notification: our own explicit, immutable PendingIntent only - not exported (as
 * `InstallModeReceiver`, `WindowRelockReceiver`). Android asks for the unlock first when tapped on its keyguard
 * (`setAuthenticationRequired`). [TimeRequests.ask] writes a PENDING record when `askStep` says so; then the notice
 * loses its action at once and the sync posts the request. Anything else does nothing (a stale notification).
 */
class TimeAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ASK_TIME) return
        val app = context.applicationContext
        try {
            val snap = TimeRulesRuntime.snapshot(app, currentPolicyDecision().policy)
            val windowOpen = OfflineOverride.isActive() || RestrictionsPause.isActive()
            val asked = TimeRequests.ask(app, LauncherPreferences.mdm().enrolled(), windowOpen, snap)
            AnchorNotice.refresh(app, snap)
            if (asked) {
                // The 30 minutes' end is a boundary (R13-8): armed now, not only by the sync's re-check (Q-4).
                TimeRuleAlarm.schedule(app)
                SyncRunner.request(app, "time_request")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The request for more time failed: ${e.javaClass.simpleName}")
        }
    }
}
