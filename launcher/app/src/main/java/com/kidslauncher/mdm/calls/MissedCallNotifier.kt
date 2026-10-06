package com.kidslauncher.mdm.calls

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kidslauncher.mdm.MISSED_CALL_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_MISSED_CALLS
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.createMissedCallChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MissedCallNotifier"

/** CE prefs: the newest unread missed call `_id` covered by the notification on screen (0 = none
 * shown), and the newest `_id` a post alerted for. */
private const val PREFS = "missed_call_notice"
private const val KEY_SHOWN_UP_TO = "shown_up_to_id"
private const val KEY_ALERTED_UP_TO = "alerted_up_to_id"

/** After our call ends: Telecom writes the call log asynchronously, then we look again. */
private const val AFTER_CALL_DELAY_MS = 3_000L

/** Telecom answers our own `cancelMissedCallsNotification()` with a count-0 broadcast. */
private const val ECHO_WINDOW_MS = 5_000L

/**
 * Our missed-call notification (design 12-missed-calls.md with the decisions after QA review),
 * channel `launcher:missed_calls`, id 1006. Every decision is in MissedCallNotice.kt; this object
 * reads the call log, posts, cancels and marks read - all on one background thread, in order.
 *
 * - [onBroadcast] (from [MissedCallReceiver]) and [afterCall] (our in-call service, a call ended)
 *   recompute; requests that queue up while one is waiting run once (a boot re-send is a burst).
 * - [dismiss]: the phone book or a contact sheet opened (also the notification's tap, which opens
 *   the phone book) - ours goes and the calls it covered are marked read.
 * - [cancelOurs]: Telecom's count 0, the dialer role handed back.
 */
object MissedCallNotifier {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "missed-calls").apply { isDaemon = true }
    }

    private val lock = Any()
    private var queued = false
    private var queuedMayPost = false
    private val waiting = mutableListOf<() -> Unit>()

    /** What ours shows now (only this thread writes it); `null` after a cancel or a restart. */
    @Volatile
    private var lastPosted: MissedCallNotice? = null

    /** `elapsedRealtime` of our last `cancelMissedCallsNotification()`. */
    @Volatile
    private var telecomCancelledAt = Long.MIN_VALUE / 2

    /** Telecom's broadcast: [count] 0 = clear, anything else = look at the call log again. [done]
     * finishes the receiver's `goAsync` once the work for it has run. */
    fun onBroadcast(context: Context, count: Int, done: () -> Unit) {
        if (count == 0) {
            val app = context.applicationContext
            executor.execute {
                try {
                    // Our own cancel's answer: ours is already gone and a newer post must stay.
                    if (SystemClock.elapsedRealtime() - telecomCancelledAt > ECHO_WINDOW_MS) cancelNotification(app)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't cancel the missed-call notification", e)
                } finally {
                    done()
                }
            }
        } else {
            recompute(context, mayPost = true, done = done)
        }
    }

    /** Our call ended: once the call log has it, update or clear a notification on screen (a
     * call back deals with the missed calls) - never a new one; that is the broadcast's job. */
    fun afterCall(context: Context) {
        val app = context.applicationContext
        executor.schedule({ recompute(app, mayPost = false) }, AFTER_CALL_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** The kid opened the phone book or a contact (or tapped ours): it goes, and the missed calls
     * it covered are marked read (QA #3). */
    fun dismiss(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                cancelNotification(app)
                val shownUpTo = prefs(app).getLong(KEY_SHOWN_UP_TO, 0L)
                if (shownUpTo > 0) {
                    markReadAndResetTelecom(app, shownUpTo)
                    prefs(app).edit().putLong(KEY_SHOWN_UP_TO, 0L).commit()
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't dismiss the missed-call notification", e)
            }
        }
    }

    /** Only ours goes - nothing is marked read and Telecom isn't told (the dialer role is handed
     * back: we may not call it any more). */
    fun cancelOurs(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                cancelNotification(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't cancel the missed-call notification", e)
            }
        }
    }

    private fun recompute(context: Context, mayPost: Boolean, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(lock) {
            if (done != null) waiting += done
            queuedMayPost = queuedMayPost || mayPost
            if (queued) return
            queued = true
        }
        executor.execute {
            val callbacks: List<() -> Unit>
            val post: Boolean
            synchronized(lock) {
                queued = false
                post = queuedMayPost
                queuedMayPost = false
                callbacks = waiting.toList()
                waiting.clear()
            }
            try {
                recomputeNow(app, post)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Missed-call recompute failed", e)
            } finally {
                callbacks.forEach { it() }
            }
        }
    }

    private fun recomputeNow(context: Context, mayPost: Boolean) {
        // The in-call service runs before the first unlock too: no call log, no CE prefs then.
        if (!CallPolicyStore.userUnlocked(context)) return
        CallPolicyStore.ensureLoaded(context)
        val state = CallPolicyStore.effectiveState()
        val rules = (state as? CallPolicyState.Managed)?.rules
        val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(context, it) }
        // The bound first, then the log: every unread row up to it has been looked at.
        val unreadUpTo = MissedCallsRepo.newestUnreadMissedId(context)
        val log = MissedCallsRepo.recentLog(context)
        val notice = if (rules == null) {
            null
        } else {
            missedCallNotice(log, view.contacts, rules.defaultCc, MissedCallsRepo.seenMarks(context), unreadUpTo)
        }
        val prefs = prefs(context)
        val step = noticeStep(
            notice = notice,
            rulesKnown = rules != null,
            mayPost = mayPost,
            shown = shown(context),
            lastPosted = lastPosted,
            unreadUpToId = unreadUpTo,
            alertedUpToId = prefs.getLong(KEY_ALERTED_UP_TO, 0L),
            newestLoggedId = log.maxOfOrNull { it.id } ?: 0L,
        )
        when (step) {
            NoticeStep.Keep -> Unit
            is NoticeStep.Clear -> {
                cancelNotification(context)
                prefs.edit().putLong(KEY_SHOWN_UP_TO, 0L).commit()
                step.markReadUpToId?.let { markReadAndResetTelecom(context, it) }
            }
            is NoticeStep.Post -> {
                if (post(context, step.notice, step.alert)) {
                    lastPosted = step.notice
                    prefs.edit()
                        .putLong(KEY_SHOWN_UP_TO, unreadUpTo)
                        .putLong(KEY_ALERTED_UP_TO, if (step.alert) step.notice.newestId else prefs.getLong(KEY_ALERTED_UP_TO, 0L))
                        .commit()
                }
            }
        }
    }

    private fun post(context: Context, notice: MissedCallNotice, alert: Boolean): Boolean {
        val text = noticeText(notice)
        val res = context.resources
        val title = when (text.title) {
            NoticeTitle.ONE_CALL_FROM -> context.getString(R.string.missed_call_from, text.name)
            NoticeTitle.CALLS_FROM -> res.getQuantityString(R.plurals.missed_calls_from, text.count, text.count, text.name)
            NoticeTitle.CALLS -> res.getQuantityString(R.plurals.missed_calls_count, text.count, text.count)
        }
        val open = PendingIntent.getActivity(
            context, 0, PhoneBookActivity.missedCallsIntent(context, notice.contactNumber),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_MISSED_CALLS)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(title)
            .setContentText(text.text)
            .setWhen(notice.lastAtMs)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            // A boot re-send or an update after a call: shown again without a sound (the platform
            // builder has no setSilent; compat puts it in a group that doesn't alert).
            .setSilent(!alert)
            .build()
        return try {
            createMissedCallChannel(context)
            context.getSystemService(NotificationManager::class.java).notify(MISSED_CALL_NOTIFICATION_ID, notification)
            Log.i(LOG_TAG, "Missed-call notification posted (alert=$alert)")
            true
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "No permission to post the missed-call notification", e)
            false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the missed-call notification", e)
            false
        }
    }

    private fun shown(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == MISSED_CALL_NOTIFICATION_ID }
    } catch (e: Exception) {
        false
    }

    private fun cancelNotification(context: Context) {
        lastPosted = null
        context.getSystemService(NotificationManager::class.java).cancel(MISSED_CALL_NOTIFICATION_ID)
    }

    /** QA #1: the log first (Telecom skips it for a dialer with the receiver), then Telecom's own
     * count - it answers with a count-0 broadcast, which never calls it again. */
    private fun markReadAndResetTelecom(context: Context, upToId: Long) {
        MissedCallsRepo.markMissedRead(context, upToId)
        try {
            telecomCancelledAt = SystemClock.elapsedRealtime()
            context.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Not the default dialer (any more): nothing of Telecom's to reset.
            Log.w(LOG_TAG, "cancelMissedCallsNotification failed", e)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
