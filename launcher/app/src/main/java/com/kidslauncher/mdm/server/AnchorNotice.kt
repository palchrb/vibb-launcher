package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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

/**
 * The anchor's time part (design r13 §2.2, design 100 §2.2): the time left today and the request, computed here and
 * handed to [AnchorNotification], the one poster of id 1002 - which shows it in the Snarveier panel while that is
 * shown, else in the plain notice built here ([build]). Nothing here posts.
 */
object AnchorNotice {
    /** r13's words for the time part's text: "1 t 20 min igjen i dag", "Skjermtiden er brukt opp i dag" - the plain
     * notice's title and the panel's time line (design 100 §3.1). */
    fun timeText(context: Context, text: TimeLeftText): String = when (text) {
        is TimeLeftText.Left -> context.getString(
            R.string.anchor_time_left,
            durationText(text.minutes, context.getString(R.string.duration_unit_hours), context.getString(R.string.duration_unit_minutes)),
        )
        TimeLeftText.UsedUp -> context.getString(R.string.anchor_time_used_up)
    }

    /** The plain notice for [notice] (Snarveier not shown): silent, ongoing, no time, the MIN channel, the Vibb mark
     * (design 100 §4); no `FOREGROUND_SERVICE_IMMEDIATE` (§2.3). The action asks for the unlock first from Android's
     * keyguard (`setAuthenticationRequired`). "Spurt kl. 14:02" is the content text, never a button. */
    fun build(context: Context, notice: TimeLeftNotice): Notification {
        val title = when (val text = notice.text) {
            null -> context.getString(R.string.notification_listener_title)
            else -> timeText(context, text)
        }
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_stat_vibb)
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

    /** The notice for [snap] (`recheck`'s, the tap's), handed to the poster. */
    fun refresh(context: Context, snap: TimeRulesRuntime.Snapshot) =
        refresh(context, snap.budget, snap.policy, snap.ledger?.day, snap.clocks.wallMs)

    /** The same from the parts the screen-time tracker has computed anyway (its minute tick): no snapshot. The poster
     * posts it only while the anchor runs, and only a changed view; Snarveier's Mer tid follows the same notice. */
    @Synchronized
    fun refresh(context: Context, budget: BudgetUse?, policy: TimePolicy?, day: String?, nowMs: Long) {
        AnchorNotification.notice(context, noticeFor(context, budget, policy, day, nowMs))
    }

    /** A fresh snapshot, then [refresh] (the tap, the server's answer, the anchor's start, the pause's start). */
    fun refreshNow(context: Context) {
        if (!CommandListenerService.isRunning) return
        refresh(context, TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy))
    }

    /** The notice from a fresh snapshot, nothing handed over (the poster's first view in a process). */
    fun noticeNow(context: Context): TimeLeftNotice {
        val snap = TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy)
        return noticeFor(context, snap.budget, snap.policy, snap.ledger?.day, snap.clocks.wallMs)
    }

    /** The plain notice (the poster's `startForeground` fallback, [AnchorNotification.forStart]). */
    fun plain(context: Context): Notification = build(context, PLAIN_NOTICE)
}
