package com.kidslauncher.mdm.timerules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The anchor notification's "Be om mer tid" (design r13 §2.4). */
const val ACTION_ASK_TIME = "com.kidslauncher.mdm.action.ASK_TIME"

/**
 * "Be om mer tid" in the anchor's notification: our own explicit, immutable PendingIntent only - not exported (as
 * `InstallModeReceiver`, `WindowRelockReceiver`). Android asks for the unlock first when tapped on its keyguard
 * (`setAuthenticationRequired`). The one request path ([TimeAsk.askNow], design 100 §8.4) writes a PENDING record when
 * `askStep` says so; then the notice loses its action at once ("Spurt kl. 14:02 · sender …") and the sync posts the
 * request. Anything else does nothing (a stale notification).
 */
class TimeAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ASK_TIME) return
        TimeAsk.askNow(context)
    }
}
