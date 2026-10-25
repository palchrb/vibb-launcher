package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kidslauncher.mdm.COMMAND_LISTENER_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.timerules.ACTION_ASK_TIME
import com.kidslauncher.mdm.timerules.AskPart
import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.maxLiftId
import com.kidslauncher.mdm.timerules.PLAIN_NOTICE
import com.kidslauncher.mdm.timerules.TimeAskReceiver
import com.kidslauncher.mdm.timerules.TimeLeftNotice
import com.kidslauncher.mdm.timerules.TimeLeftText
import com.kidslauncher.mdm.timerules.TimeRequests
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.timeLeftNotice
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.durationText

private const val LOG_TAG = "AnchorNotice"

/**
 * The anchor's one notification (design r13 §2.2-2.3): the plain "Vibb One", or with a budget today the time left as
 * its title and one action, "Be om mer tid". The same id, channel and icon as ever, so it can never become a second
 * notification. Posted only while the anchor runs ([CommandListenerService.isRunning]) and only when the notice or the
 * app's locale differ from the last posted ([lastPosted], reset by each new anchor in `onCreate`).
 */
object AnchorNotice {
    /** The notice and the locale last posted; `null` after a new anchor's `startForeground`. Main thread. */
    private var lastPosted: Pair<TimeLeftNotice, String>? = null

    /** A new anchor posted its own plain notification (`onCreate`): that is what is up now (no second post). */
    fun reset(context: Context) {
        lastPosted = PLAIN_NOTICE to locale(context)
    }

    private fun locale(context: Context): String = context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()

    /** The notification for [notice]: silent, ongoing, no time, the MIN channel; the action asks for the unlock first
     * from Android's keyguard (`setAuthenticationRequired`). "Spurt kl. 14:02" is the content text, never a button. */
    fun build(context: Context, notice: TimeLeftNotice): Notification {
        val title = when (val text = notice.text) {
            null -> context.getString(R.string.notification_listener_title)
            is TimeLeftText.Left -> context.getString(
                R.string.anchor_time_left,
                durationText(text.minutes, context.getString(R.string.duration_unit_hours), context.getString(R.string.duration_unit_minutes)),
            )
            TimeLeftText.UsedUp -> context.getString(R.string.anchor_time_used_up)
        }
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        when (val ask = notice.ask) {
            AskPart.Action -> builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.anchor_ask_more_time), askIntent(context))
                    .setAuthenticationRequired(true)
                    .build(),
            )
            is AskPart.Asked -> builder.setContentText(context.getString(R.string.anchor_asked_at, ClockFormat.formatTime(context, ask.atMs)))
            AskPart.None -> {}
        }
        return builder.build()
    }

    /** The action's broadcast, explicit to our own non-exported receiver. */
    private fun askIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimeAskReceiver::class.java).setAction(ACTION_ASK_TIME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The notice for [budget] (today's use), [policy] (its lifts), the ledger's [day] at [nowMs]: the pause or the
     * override, and the request's state from its DE prefs - no snapshot (qa-r13-rule-card-code Q-5). */
    fun noticeFor(context: Context, budget: BudgetUse?, policy: TimePolicy?, day: String?, nowMs: Long): TimeLeftNotice =
        timeLeftNotice(
            budget,
            OfflineOverride.isActive() || RestrictionsPause.isActive(),
            TimeRequests.state(context, day, nowMs, maxLiftId(policy)),
        )

    /** Posts the notice for [snap] (`recheck`'s, the tap's) while the anchor runs and it changed. */
    fun refresh(context: Context, snap: TimeRulesRuntime.Snapshot) =
        refresh(context, snap.budget, snap.policy, snap.ledger?.day, snap.clocks.wallMs)

    /** The same from the parts the screen-time tracker has computed anyway (its minute tick): no snapshot. */
    @Synchronized
    fun refresh(context: Context, budget: BudgetUse?, policy: TimePolicy?, day: String?, nowMs: Long) {
        if (!CommandListenerService.isRunning) return
        post(context, noticeFor(context, budget, policy, day, nowMs))
    }

    /** A fresh snapshot, then [refresh] (the tap, the server's answer, the anchor's start, the pause's start). */
    fun refreshNow(context: Context) {
        if (!CommandListenerService.isRunning) return
        refresh(context, TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy))
    }

    @Synchronized
    private fun post(context: Context, notice: TimeLeftNotice) {
        val key = notice to locale(context)
        if (key == lastPosted) return
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(COMMAND_LISTENER_NOTIFICATION_ID, build(context, notice))
            lastPosted = key
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the notice: ${e.javaClass.simpleName}")
        }
    }

    /** The plain notice (for `startForeground`). */
    fun plain(context: Context): Notification = build(context, PLAIN_NOTICE)
}
