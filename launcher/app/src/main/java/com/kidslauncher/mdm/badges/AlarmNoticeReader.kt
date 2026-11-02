package com.kidslauncher.mdm.badges

import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidslauncher.mdm.lock.AlarmNotices
import com.kidslauncher.mdm.lock.ringingAlarmShape

private const val LOG_TAG = "AlarmNoticeReader"

/**
 * Bug 2026-11-02 (a2): the notification listener's reader of a ringing alarm, wired in `BadgeListenerService` like
 * [ClockReader]: of every notification only its key, package, category, the ongoing flag and whether it has a
 * full-screen intent ([ringingAlarmShape]) - never a title, text, intent or extra. Whose it may be (the lock's clock
 * apps) is the lock's question ([ringing]). Kept in memory as the pure [AlarmNotices]; nothing logged but the
 * exception class. Main thread (the listener's callbacks and the lock's checks); every entry point catches everything,
 * so a fault here never reaches the lock's crash guard. Without the listener: nothing kept, the lock's fixed window
 * after the alarm's time decides alone.
 */
object AlarmNoticeReader {
    @Volatile
    private var notices = AlarmNotices()

    fun connected(active: List<StatusBarNotification>) = guarded {
        notices = notices.swept(active.map { Triple(it.key, it.packageName, shape(it)) }, SystemClock.elapsedRealtime())
    }

    fun disconnected() = guarded { notices = AlarmNotices() }

    fun posted(sbn: StatusBarNotification) = guarded {
        notices = notices.posted(sbn.key, sbn.packageName, shape(sbn), SystemClock.elapsedRealtime())
    }

    fun removed(sbn: StatusBarNotification) = guarded { notices = notices.removed(sbn.key) }

    /** One of [clockPackages] has a ringing alarm's notification up ([AlarmNotices.ringingNow]). */
    fun ringing(clockPackages: Set<String>): Boolean = try {
        notices.ringingNow(clockPackages, SystemClock.elapsedRealtime())
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Alarm read failed: ${e.javaClass.simpleName}")
        false
    }

    /** The lock resumed after yielding to [yieldedTo] ([AlarmNotices.afterResume]). */
    fun lockResumed(yieldedTo: String?) = guarded { notices = notices.afterResume(yieldedTo) }

    private fun shape(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        return ringingAlarmShape(n.category, sbn.isOngoing, n.fullScreenIntent != null)
    }

    private inline fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Alarm notice failed: ${e.javaClass.simpleName}")
        }
    }
}
