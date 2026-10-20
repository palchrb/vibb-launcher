package com.kidslauncher.mdm.lock

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.util.Log

private const val LOG_TAG = "ClockActions"

/**
 * The ended timer's "Stopp" (design 42, the coordinator's decision of 2026-10-20): Fossify Clock's
 * timer-end notification has one action, "Dismiss", a broadcast to its HideTimerReceiver (it hides
 * the notification and silences the timer). The lock fires exactly that and nothing else: only an
 * intent that is not an activity ([clockStopAction] chose it; checked again here), sent with
 * background activity starts denied, so even a broadcast whose receiver tried to start an activity
 * opens nothing over the lock. The one send in the glance code (`LockGlanceGuardTest`); it never
 * throws.
 */
object ClockActions {
    fun stop(context: Context, intent: PendingIntent?) {
        if (intent == null || intent.isActivity || !intent.isBroadcast) return
        try {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
                .toBundle()
            intent.send(context, 0, null, null, null, null, options)
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Timer stop failed: ${e.javaClass.simpleName}")
        }
    }
}
