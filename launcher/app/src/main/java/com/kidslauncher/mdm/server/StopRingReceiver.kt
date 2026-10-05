package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the "Stop Ringing" action button on [com.kidslauncher.mdm.RING_NOTIFICATION_ID]'s
 * notification (see [LocateCommands.ring]) - not externally reachable, only ever triggered via
 * that notification's own `PendingIntent`.
 */
class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LocateCommands.stopRingAndRestore(context)
    }
}
