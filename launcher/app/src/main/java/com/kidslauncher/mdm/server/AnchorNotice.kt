package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.timerules.ACTION_ASK_TIME
import com.kidslauncher.mdm.timerules.AskNote
import com.kidslauncher.mdm.timerules.AskPart
import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.PLAIN_NOTICE
import com.kidslauncher.mdm.timerules.RequestState
import com.kidslauncher.mdm.timerules.TimeAskReceiver
import com.kidslauncher.mdm.timerules.TimeLeftText
import com.kidslauncher.mdm.timerules.TimePart
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRequests
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.timePart
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
        is TimeLeftText.GroupLeft -> context.getString(
            R.string.anchor_group_left,
            text.name,
            durationText(text.minutes, context.getString(R.string.duration_unit_hours), context.getString(R.string.duration_unit_minutes)),
        )
    }

    /** Design 139 Q4 (b): the app group whose last minutes the title names now ([refreshGroups]), `null` for none. */
    @Volatile
    private var groupWarning: TimeLeftText.GroupLeft? = null

    /** Q4 (b), after each group fold: the warning for [states] with [onScreen] at the fold; a change posts the notice
     * again (r13's one notification, no sound, no second notice). */
    fun refreshGroups(context: Context, states: List<com.kidslauncher.mdm.timerules.GroupState>, onScreen: Set<String>) {
        val warning = com.kidslauncher.mdm.timerules.groupWarningText(states, onScreen)
        if (warning == groupWarning) return
        groupWarning = warning
        refreshNow(context)
    }

    /** The note line's words (design 100 §8.4, §14.3): "Spurt kl. 14:02 · venter på svar", "Pappa svarte nei kl.
     * 14:10", "Mamma ga deg 30 min" (D11: "Svaret ble nei kl. 14:10", "Du fikk 30 min mer" without a display name);
     * `null` for none. The panel's note line, the plain notice's text and the time-rule screen's block. */
    fun noteText(context: Context, note: AskNote): String? {
        // A time in a line of text: with AM/PM in 12 h ("Spurt kl. 7:35" alone is either half of the day; 2026-11-15).
        fun clock(atMs: Long) = ClockFormat.formatClock(context, atMs)
        fun minutes(m: Int) =
            durationText(m, context.getString(R.string.duration_unit_hours), context.getString(R.string.duration_unit_minutes))
        return when (note) {
            is AskNote.Sending -> context.getString(if (note.offline) R.string.ask_note_offline else R.string.ask_note_sending, clock(note.atMs))
            is AskNote.Waiting -> context.getString(R.string.ask_note_waiting, clock(note.atMs))
            is AskNote.No -> note.by?.let { context.getString(R.string.ask_note_no, it, clock(note.atMs)) }
                ?: context.getString(R.string.ask_note_no_unnamed, clock(note.atMs))
            is AskNote.Given -> note.by?.let { context.getString(R.string.ask_note_given, it, minutes(note.minutes)) }
                ?: context.getString(R.string.ask_note_given_unnamed, minutes(note.minutes))
            AskNote.None -> null
        }
    }

    /** The plain notice for [time] (Snarveier not shown): silent, ongoing, no time, the MIN channel, the Vibb mark
     * (design 100 §4); no `FOREGROUND_SERVICE_IMMEDIATE` (§2.3). The action, while the kid may ask, asks for the unlock
     * first from Android's keyguard (`setAuthenticationRequired`). The note ("Spurt kl. 14:02 · venter på svar", the
     * parent's answer) is the content text, never a button. */
    fun build(context: Context, time: TimePart): Notification {
        val title = when (val text = time.text) {
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
        if (time.ask == AskPart.Action) {
            builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.anchor_ask_more_time), askIntent(context))
                    .setAuthenticationRequired(true)
                    .build(),
            )
        }
        noteText(context, time.note)?.let { builder.setContentText(it) }
        return builder.build()
    }

    /** The action's broadcast, explicit to our own non-exported receiver. */
    private fun askIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimeAskReceiver::class.java).setAction(ACTION_ASK_TIME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The time part for [budget] (today's use), [policy] (its answer and lifts), the ledger's [day] at [nowMs], for
     * the shade: the pause or the override, and the request's state from its DE prefs - no snapshot
     * (qa-r13-rule-card-code Q-5). */
    fun noticeFor(context: Context, budget: BudgetUse?, policy: TimePolicy?, day: String?, nowMs: Long): TimePart =
        com.kidslauncher.mdm.timerules.withGroupWarning(
            timePart(
                budget,
                OfflineOverride.isActive() || RestrictionsPause.isActive(),
                TimeRequests.state(context, day, policy),
                policy,
                nowMs,
            ),
            groupWarning,
        )

    /** The notice for [snap] (`recheck`'s, the tap's), handed to the poster. */
    fun refresh(context: Context, snap: TimeRulesRuntime.Snapshot) =
        refresh(context, snap.budget, snap.policy, snap.ledger?.day, snap.clocks.wallMs)

    /** The same from the parts the screen-time tracker has computed anyway (its minute tick): no snapshot. The poster
     * posts it only while the anchor runs, and only a changed view; Snarveier's Mer tid follows the same notice. A
     * changed time part or request state also tells the listeners ([addListener], design 100 §8.4). */
    @Synchronized
    fun refresh(context: Context, budget: BudgetUse?, policy: TimePolicy?, day: String?, nowMs: Long) {
        val state = TimeRequests.state(context, day, policy)
        val time = com.kidslauncher.mdm.timerules.withGroupWarning(
            timePart(budget, OfflineOverride.isActive() || RestrictionsPause.isActive(), state, policy, nowMs),
            groupWarning,
        )
        AnchorNotification.notice(context, time)
        val seen = time to state
        if (seen != lastSeen) {
            lastSeen = seen
            dispatch()
        }
    }

    /** A fresh snapshot, then [refresh] (the tap, the server's answer, the anchor's start, the pause's start). Computed
     * and dispatched to the listeners whether the anchor runs or not (Q100-L3): only the post needs the anchor, and
     * the poster checks that itself. */
    fun refreshNow(context: Context) {
        refresh(context, TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy))
    }

    /** The last time part and request state [refresh] computed: a change is told to the listeners. */
    private var lastSeen: Pair<TimePart, RequestState>? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The time-rule screen follows the request (design 100 §8.4): [listener] runs on the main thread after a changed
     * time part or request state, whatever thread [refresh] ran on (Q100-H1: a Mer tid tap in the shade runs it on
     * the tap thread). It re-reads everything itself; nothing is passed. */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun dispatch() {
        main.post {
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {
                    android.util.Log.w("AnchorNotice", "Listener failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** The notice from a fresh snapshot, nothing handed over (the poster's first view in a process). */
    fun noticeNow(context: Context): TimePart {
        val snap = TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy)
        return noticeFor(context, snap.budget, snap.policy, snap.ledger?.day, snap.clocks.wallMs)
    }

    /** The plain notice with [time] - the poster's `startForeground` fallback ([AnchorNotification.forStart]): the
     * time part it has cached, else the plain "Vibb One" (code QA L5). */
    fun plain(context: Context, time: TimePart = PLAIN_NOTICE): Notification = build(context, time)
}
