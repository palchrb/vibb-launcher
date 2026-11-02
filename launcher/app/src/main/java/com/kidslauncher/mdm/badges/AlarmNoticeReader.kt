package com.kidslauncher.mdm.badges

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidslauncher.mdm.lock.AlarmNotices
import com.kidslauncher.mdm.lock.VisibleSender
import com.kidslauncher.mdm.lock.ringingAlarmShape

private const val LOG_TAG = "AlarmNoticeReader"

/**
 * Bug 2026-11-02 (a2): the notification listener's reader of a ringing alarm, wired in `BadgeListenerService` like
 * [ClockReader]: of every notification only its key, package, category, the ongoing flag and whether it has a
 * full-screen intent ([ringingAlarmShape]) - never a title, text, action or extra. Of a ringing-shaped one also that
 * full-screen intent (the PendingIntent alone), which only [sendScreen] sends: the resumed lock bringing a still-ringing
 * alarm's screen back over itself (the gap closed 2026-11-02). Whose it may be (the lock's clock apps) is the lock's
 * question. Kept in memory as the pure [AlarmNotices]; nothing logged but the exception class. Main thread (the
 * listener's callbacks and the lock's checks); every entry point catches everything, so a fault here never reaches
 * the lock's crash guard. Without the listener: nothing kept, the lock's fixed window after the alarm's time decides
 * alone, and nothing is sent.
 */
object AlarmNoticeReader {
    @Volatile
    private var notices = AlarmNotices()

    /** The ringing-shaped notifications' full-screen intents, by key. */
    private val screens = HashMap<String, PendingIntent>()

    /** The listener's `getActiveNotifications(keys)` while it is connected; null = no answer. */
    private var lookup: ((Array<String>) -> List<StatusBarNotification>?)? = null

    /** The listener connected: its lookup of active notifications by key (before its sweep). */
    fun attach(activeByKeys: (Array<String>) -> List<StatusBarNotification>?) = guarded {
        lookup = activeByKeys
    }

    fun connected(active: List<StatusBarNotification>) = guarded {
        notices = notices.swept(active.map { Triple(it.key, it.packageName, shape(it)) }, SystemClock.elapsedRealtime())
        screens.clear()
        active.forEach { sbn -> screen(sbn)?.let { screens[sbn.key] = it } }
    }

    fun disconnected() = guarded {
        lookup = null
        notices = AlarmNotices()
        screens.clear()
    }

    fun posted(sbn: StatusBarNotification) = guarded {
        notices = notices.posted(sbn.key, sbn.packageName, shape(sbn), SystemClock.elapsedRealtime())
        val screen = screen(sbn)
        if (screen != null) screens[sbn.key] = screen else screens.remove(sbn.key)
    }

    fun removed(sbn: StatusBarNotification) = guarded {
        notices = notices.removed(sbn.key)
        screens.remove(sbn.key)
    }

    /** One of [clockPackages] has a ringing alarm's notification up ([AlarmNotices.ringingNow]). */
    fun ringing(clockPackages: Set<String>): Boolean = try {
        notices.ringingNow(clockPackages, SystemClock.elapsedRealtime())
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Alarm read failed: ${e.javaClass.simpleName}")
        false
    }

    /** The lock resumed after yielding to [yieldedTo] ([AlarmNotices.afterResume]). */
    fun lockResumed(yieldedTo: String?) = guarded { notices = notices.afterResume(yieldedTo) }

    /**
     * The ringing alarm whose screen the lock may send again ([AlarmNotices.screenKey]), after asking the listener
     * which of the kept keys are still active ([AlarmNotices.reconciled]; no listener or no answer: none).
     */
    fun screenKey(clockPackages: Set<String>): String? = try {
        val kept = notices.ringing.keys
        val active = if (kept.isEmpty()) null else lookup?.invoke(kept.toTypedArray())
        if (active == null) {
            null
        } else {
            notices = notices.reconciled(active.mapTo(mutableSetOf()) { it.key })
            screens.keys.retainAll(notices.ringing.keys)
            notices.screenKey(clockPackages, SystemClock.elapsedRealtime())?.takeIf { it in screens }
        }
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Alarm screen read failed: ${e.javaClass.simpleName}")
        null
    }

    /** Sends [key]'s full-screen intent from the resumed lock ([context], its visible window: [VisibleSender]) and
     * counts it ([AlarmNotices.resent]). A cancelled intent (re-posted meanwhile) is a counted try that went nowhere. */
    fun sendScreen(context: Context, key: String): Boolean {
        val intent = screens[key] ?: return false
        val sent = try {
            intent.send(context, 0, null, null, null, null, VisibleSender.options())
            true
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Alarm screen not sent: ${e.javaClass.simpleName}")
            false
        }
        guarded { notices = notices.resent(key, sent) }
        return sent
    }

    private fun shape(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        return ringingAlarmShape(n.category, sbn.isOngoing, n.fullScreenIntent != null)
    }

    private fun screen(sbn: StatusBarNotification): PendingIntent? = if (shape(sbn)) sbn.notification?.fullScreenIntent else null

    private inline fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Alarm notice failed: ${e.javaClass.simpleName}")
        }
    }
}
