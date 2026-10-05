package com.kidslauncher.mdm.timerules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val LOG_TAG = "TimeRuleAlarm"
private const val ACTION_BOUNDARY = "com.kidslauncher.mdm.action.TIME_RULE_BOUNDARY"

/**
 * One alarm at the next time-rule boundary (handy step 6) instead of polling the schedule every
 * minute: a rule starting or ending, local midnight while a budget is set, a lift running out.
 * `setExactAndAllowWhileIdle` with `USE_EXACT_ALARM` (granted at install; we are not a Play app);
 * if exact alarms are refused, an inexact while-idle alarm. Re-armed by [TimeRulesRuntime.recheck]
 * after every evaluation, so a missed or late alarm is corrected on the next screen-on.
 */
object TimeRuleAlarm {
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimeRuleReceiver::class.java).setAction(ACTION_BOUNDARY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pendingIntent(context)
            val next = TimeRulesRuntime.nextBoundary(context)
            if (next == null) {
                alarms.cancel(pi)
                return
            }
            // A second late, so the evaluation lands inside the new period, not on its edge.
            val at = next.toEpochMilli() + 1_000L
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i(LOG_TAG, "Next time-rule boundary at $next")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't schedule the time-rule alarm", e)
        }
    }
}

/**
 * The boundary alarm, plus the system broadcasts after which the next boundary may have moved:
 * the clock or time zone changed, or the phone booted (alarms don't survive a reboot). Exported
 * for the system broadcasts (all protected - only the system can send them); anything else that
 * reaches it only triggers a harmless re-check. Not direct-boot-aware: before the first unlock the
 * call path reads the boot copy of the rules itself.
 */
class TimeRuleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BOUNDARY, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED ->
                TimeRulesRuntime.recheck(context.applicationContext)
            else -> Log.w(LOG_TAG, "Ignoring ${intent.action}")
        }
    }
}
