package com.kidslauncher.mdm.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.util.Log

private const val LOG_TAG = "MissedCallReceiver"

/**
 * `TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION` (design 12-missed-calls.md). Telecom
 * (`MissedCallNotifierImpl`) checks whether the default dialer - us, while calls are managed - has
 * a receiver for it; if so it sends this broadcast instead of posting its own notification, after
 * each missed call has been written to the call log, once per unread missed call at boot (a
 * burst, counts 1..N in any order), and with count 0 when its count is cleared.
 *
 * A protected broadcast that Telecom sends with READ_PHONE_STATE as the receiver permission (we
 * hold it): exported without `android:permission`, as AOSP Dialer (QA #7). Not direct-boot-aware:
 * before the first unlock Telecom finds no receiver and posts its own notification; the call-log
 * pin is the backstop for its tap (QA #5).
 *
 * The count is only a trigger (QA #2): what ours says comes from the call log. The number extra is
 * never read.
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, -1)
        Log.i(LOG_TAG, "Missed calls from Telecom: count $count")
        val pending = goAsync()
        MissedCallNotifier.onBroadcast(context, count) { pending.finish() }
    }
}
