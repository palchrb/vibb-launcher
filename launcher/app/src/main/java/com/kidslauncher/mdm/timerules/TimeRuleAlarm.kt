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
 * after every evaluation, so a missed or late alarm is corrected on the next screen-on. Since
 * step 7 it also wakes for install mode's end and the Play update window's edges.
 */
object TimeRuleAlarm {
    /** Security review S-L7: the alarm targets the non-exported [TimeRuleAlarmReceiver]; no other app can send it. */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimeRuleAlarmReceiver::class.java).setAction(ACTION_BOUNDARY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** An alarm an older build set on the exported receiver: cancelled once, at the next schedule (it would only
     * reach a receiver that ignores it now). */
    @Volatile
    private var legacyCancelled = false

    private fun cancelLegacy(context: Context, alarms: AlarmManager) {
        if (legacyCancelled) return
        legacyCancelled = true
        PendingIntent.getBroadcast(
            context, 0, Intent(context, TimeRuleReceiver::class.java).setAction(ACTION_BOUNDARY),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { alarms.cancel(it); it.cancel() }
    }

    fun schedule(context: Context) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { cancelLegacy(context, alarms) }
            val pi = pendingIntent(context)
            // Also the Play edges (handy step 7): install mode's end and the nightly update
            // window's start/end, so the plan follows them with the screen off.
            val next = listOfNotNull(
                TimeRulesRuntime.nextBoundary(context),
                com.kidslauncher.mdm.play.PlayRuntime.nextEdge(context),
            ).minOrNull()
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
 * The system broadcasts after which the next boundary may have moved: the clock or time zone changed, or the phone
 * booted (alarms don't survive a reboot). Exported for them (all protected - only the system can send them); since
 * security review S-L7 it ignores everything else, our own boundary action included ([timeRuleSystemAction]), so no
 * other app can make the phone re-check at will. Not direct-boot-aware: before the first unlock the call path reads
 * the boot copy of the rules itself.
 */
class TimeRuleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (timeRuleSystemAction(intent.action)) {
            TimeRuleWake.RECHECK -> TimeRulesRuntime.recheck(context.applicationContext)
            TimeRuleWake.BOOT -> TimeRulesRuntime.recheck(context.applicationContext, com.kidslauncher.mdm.server.RelockTrigger.BOOT)
            null -> Log.w(LOG_TAG, "Ignoring ${intent.action}")
        }
    }
}

/** The boundary alarm (security review S-L7): not exported, reached only through [TimeRuleAlarm]'s own PendingIntent. */
class TimeRuleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_BOUNDARY) TimeRulesRuntime.recheck(context.applicationContext)
    }
}

enum class TimeRuleWake { RECHECK, BOOT }

/** What the exported [TimeRuleReceiver] does for [action]: only the protected system broadcasts - never the boundary
 * action, which only the non-exported [TimeRuleAlarmReceiver] takes (S-L7). */
fun timeRuleSystemAction(action: String?): TimeRuleWake? = when (action) {
    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> TimeRuleWake.RECHECK
    Intent.ACTION_BOOT_COMPLETED -> TimeRuleWake.BOOT
    else -> null
}
