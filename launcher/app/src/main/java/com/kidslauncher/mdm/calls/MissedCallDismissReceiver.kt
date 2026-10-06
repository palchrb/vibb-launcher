package com.kidslauncher.mdm.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The missed-call notification's delete intent (qa-12-code #7): the kid swiped it away or cleared
 * all, so the calls it showed are dealt with - marked read (`new = 0`), as Telecom's own
 * notification and AOSP Dialer do; otherwise they would come back at the next boot. Never sent for
 * the tap (auto-cancel) or for our own cancel. The badges keep their own seen model.
 *
 * Only our own PendingIntent reaches it (explicit, not exported); not direct-boot-aware.
 */
class MissedCallDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val upTo = intent.getLongExtra(EXTRA_UP_TO_ID, 0L)
        val pending = goAsync()
        MissedCallNotifier.swiped(context, upTo) { pending.finish() }
    }

    companion object {
        /** The newest unread missed call `_id` the swiped notification covered. */
        const val EXTRA_UP_TO_ID = "com.kidslauncher.mdm.extra.MISSED_UP_TO_ID"
    }
}
