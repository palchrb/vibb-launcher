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
 * throws. Since design 105 §3 also the time-rule screen's alarm buttons ([alarmRowActions]): a ringing alarm's Dismiss
 * (a broadcast) or Snooze (a service) - so a service is sent too, never an activity.
 */
object ClockActions {
    /** Returns whether the send went out (code QA of design 105: a refused or failed send is no stop). */
    fun stop(context: Context, intent: PendingIntent?): Boolean {
        if (intent == null || intent.isActivity || !(intent.isBroadcast || intent.isService || intent.isForegroundService)) return false
        return try {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
                .toBundle()
            intent.send(context, 0, null, null, null, null, options)
            true
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Clock stop failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
