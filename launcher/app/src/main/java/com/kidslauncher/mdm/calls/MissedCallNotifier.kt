package com.kidslauncher.mdm.calls

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * shown), the newest `_id` a post alerted for, and the bound of our last mark-read + Telecom reset. */
private const val PREFS = "missed_call_notice"
private const val KEY_SHOWN_UP_TO = "shown_up_to_id"
private const val KEY_ALERTED_UP_TO = "alerted_up_to_id"
private const val KEY_RESET_UP_TO = "reset_up_to_id"

/** After our call ends: Telecom writes the call log asynchronously, so we look twice (qa-12-code #6). */
private val AFTER_CALL_DELAYS_MS = longArrayOf(3_000L, 10_000L)

/**
 * Our missed-call notification (design 12-missed-calls.md with the decisions after QA review and
 * qa-12-code.md), channel `launcher:missed_calls`, id 1006. Every decision is in
 * MissedCallNotice.kt; this object reads the call log, posts, cancels and marks read - all on one
 * background thread, in order.
 *
 * - [onBroadcast] (from [MissedCallReceiver]) and [afterCall] (our in-call service, a call ended)
 *   recompute; requests that queue up while one is waiting run once (a boot re-send is a burst).
 * - [dismiss]: the phone book or a contact sheet opened (also the notification's tap, which opens
 *   the phone book) - ours goes and the calls it covered are marked read.
 * - [swiped]: the notification's delete intent ([MissedCallDismissReceiver]) - the same for the
 *   range it showed.
 * - [cancelOurs]: the dialer role handed back.
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

    /** Telecom's broadcast: [count] 0 = its count was cleared, anything else = look at the call log
     * again. [done] finishes the receiver's `goAsync` once the work for it has run. */
    fun onBroadcast(context: Context, count: Int, done: () -> Unit) {
        if (count == 0) {
            val app = context.applicationContext
            runThenDone(done) {
                val prefs = prefs(app)
                // Only cancels, never calls Telecom; not when ours shows calls newer than the range
                // our own last reset covered - then this is that reset's (late) echo (qa-12-code #2).
                if (zeroCountCancels(prefs.getLong(KEY_SHOWN_UP_TO, 0L), prefs.getLong(KEY_RESET_UP_TO, 0L))) {
                    cancelNotification(app)
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
        for (delay in AFTER_CALL_DELAYS_MS) {
            executor.schedule({ recompute(app, mayPost = false) }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** The kid opened the phone book or a contact (or tapped ours): it goes, and the missed calls
     * it covered are marked read (QA #3). */
    fun dismiss(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        runThenDone(done) {
            cancelNotification(app)
            val shownUpTo = prefs(app).getLong(KEY_SHOWN_UP_TO, 0L)
            if (shownUpTo > 0) {
                markReadAndResetTelecom(app, shownUpTo)
                prefs(app).edit().putLong(KEY_SHOWN_UP_TO, 0L).commit()
            }
        }
    }

    /** The kid swiped ours away (qa-12-code #7): the range it showed is marked read. A newer post
     * since then stays, and keeps its own range. */
    fun swiped(context: Context, swipedUpToId: Long, done: () -> Unit) {
        val app = context.applicationContext
        runThenDone(done) {
            if (swipedUpToId <= 0) return@runThenDone
            markReadAndResetTelecom(app, swipedUpToId)
            if (swipeClearsShown(swipedUpToId, prefs(app).getLong(KEY_SHOWN_UP_TO, 0L))) {
                lastPosted = null
                prefs(app).edit().putLong(KEY_SHOWN_UP_TO, 0L).commit()
            }
        }
    }

    /** Only ours goes - nothing is marked read and Telecom isn't told (the dialer role is handed
     * back: we may not call it any more); its range is forgotten, so a later phone-book visit
     * doesn't mark it read (qa-12-code #5). */
    fun cancelOurs(context: Context) {
        val app = context.applicationContext
        runThenDone(null) {
            cancelNotification(app)
            prefs(app).edit().putLong(KEY_SHOWN_UP_TO, 0L).commit()
        }
    }

    private fun runThenDone(done: (() -> Unit)?, work: () -> Unit) {
        executor.execute {
            try {
                work()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Missed-call notification work failed", e)
            } finally {
                done?.invoke()
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
        // The bound first, then the log: every unread row up to it has been looked at.
        val unread = MissedCallsRepo.unreadMissed(context)
        val log = if (rules != null) MissedCallsRepo.recentLog(context) else emptyList()
        val notice = if (rules == null) {
            // Fail-closed, or unmanaged with our dialer role: a plain count (qa-12-code #1).
            plainMissedCallNotice(unread)
        } else {
            val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(context, it) }
            missedCallNotice(log, view.contacts, rules.defaultCc, MissedCallsRepo.seenMarks(context), unread.newestId)
        }
        val prefs = prefs(context)
        val step = noticeStep(
            notice = notice,
            mayMarkRead = rules?.callsEnabled == true,
            mayPost = mayPost,
            shown = shown(context),
            lastPosted = lastPosted,
            unreadUpToId = unread.newestId,
            alertedUpToId = prefs.getLong(KEY_ALERTED_UP_TO, 0L),
            newestLoggedId = maxOf(log.maxOfOrNull { it.id } ?: 0L, unread.newestId),
        )
        when (step) {
            NoticeStep.Keep -> Unit
            is NoticeStep.Clear -> {
                cancelNotification(context)
                prefs.edit().putLong(KEY_SHOWN_UP_TO, 0L).commit()
                step.markReadUpToId?.let { markReadAndResetTelecom(context, it) }
            }
            is NoticeStep.Post -> {
                if (post(context, step.notice, step.alert, unread.newestId)) {
                    lastPosted = step.notice
                    prefs.edit()
                        .putLong(KEY_SHOWN_UP_TO, unread.newestId)
                        .putLong(KEY_ALERTED_UP_TO, if (step.alert) step.notice.newestId else prefs.getLong(KEY_ALERTED_UP_TO, 0L))
                        .commit()
                }
            }
        }
    }

    /** [upToId]: the range this post covers, for the swipe's delete intent. */
    private fun post(context: Context, notice: MissedCallNotice, alert: Boolean, upToId: Long): Boolean {
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
        // Sent only for a swipe or "Clear all" - never for the tap (auto-cancel) or our own cancel.
        val swiped = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MissedCallDismissReceiver::class.java).putExtra(MissedCallDismissReceiver.EXTRA_UP_TO_ID, upToId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_MISSED_CALLS)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(title)
            .setContentText(text.text)
            .setWhen(notice.lastAtMs)
            .setShowWhen(notice.lastAtMs > 0)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setDeleteIntent(swiped)
            .setAutoCancel(true)
            // A boot re-send or an update after a call: shown again without a sound (the platform
            // builder has no setSilent; compat puts it in a group that doesn't alert).
            .setSilent(!alert)
            .build()
        return try {
            createMissedCallChannel(context)
            context.getSystemService(NotificationManager::class.java).notify(MISSED_CALL_NOTIFICATION_ID, notification)
            Log.i(LOG_TAG, "Missed-call notification posted (alert=$alert, named=${notice.names.isNotEmpty()})")
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
     * count - it answers with a count-0 broadcast, which never calls it again. The range is kept,
     * so that answer can be told apart from a clear that concerns a newer post (qa-12-code #2). */
    private fun markReadAndResetTelecom(context: Context, upToId: Long) {
        MissedCallsRepo.markMissedRead(context, upToId)
        val prefs = prefs(context)
        prefs.edit().putLong(KEY_RESET_UP_TO, maxOf(upToId, prefs.getLong(KEY_RESET_UP_TO, 0L))).commit()
        try {
            context.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Not the default dialer (any more): nothing of Telecom's to reset.
            Log.w(LOG_TAG, "cancelMissedCallsNotification failed", e)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
