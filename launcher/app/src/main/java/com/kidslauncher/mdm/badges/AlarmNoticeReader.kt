package com.kidslauncher.mdm.badges

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidslauncher.mdm.lock.AlarmNotices
import com.kidslauncher.mdm.lock.AlarmPost
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.lock.VisibleSender
import com.kidslauncher.mdm.lock.ringingAlarmShape

private const val LOG_TAG = "AlarmNoticeReader"

/**
 * Bug 2026-11-02 (a2): the notification listener's reader of a ringing alarm, wired in `BadgeListenerService` like
 * [ClockReader]: of every notification only its key, package, category, `when` and whether it has a full-screen
 * intent ([ringingAlarmShape]) - never a title, text, action or extra. Of a ringing-shaped one of the lock's clock apps
 * ([PinLockRuntime.clockPackages]) also that full-screen intent (the PendingIntent alone), which only [sendScreen]
 * sends - an activity intent the clock app created: the resumed lock bringing a still-ringing alarm's screen back over
 * itself (the gap closed 2026-11-02), and since E7's M1 the time-rule screen bringing it up after an unlock, or on the
 * kid's tap of its alarm row ([openable], [addListener]). Whose a ring may be (the lock's clock apps) is the lock's
 * question. Kept in memory as the pure [AlarmNotices]; nothing logged but the exception class. Main thread (the
 * listener's callbacks and the screens' checks); every entry point catches everything, so a fault here never reaches
 * the lock's crash guard. Without the listener: nothing kept, the lock's fixed window after the alarm's time decides
 * alone, and nothing is sent.
 */
object AlarmNoticeReader {
    @Volatile
    private var notices = AlarmNotices()

    /** The lock's clock apps' ringing-shaped notifications' full-screen intents, by key. */
    private val screens = HashMap<String, PendingIntent>()

    /** The listener's `getActiveNotifications(keys)` while it is connected; null = no answer. */
    private var lookup: ((Array<String>) -> List<StatusBarNotification>?)? = null

    /** Told when the set of kept ringing screens changes (the time-rule screen's alarm row). Main thread. */
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { if (listener !in listeners) listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** The listener connected: its lookup of active notifications by key (before its sweep). */
    fun attach(activeByKeys: (Array<String>) -> List<StatusBarNotification>?) = guarded {
        lookup = activeByKeys
    }

    fun connected(active: List<StatusBarNotification>) = guarded {
        notices = notices.swept(active.map { post(it) }, SystemClock.elapsedRealtime())
        tellingChanges {
            screens.clear()
            active.forEach { sbn -> screen(sbn)?.let { screens[sbn.key] = it } }
        }
    }

    fun disconnected() = guarded {
        lookup = null
        notices = AlarmNotices()
        tellingChanges { screens.clear() }
    }

    fun posted(sbn: StatusBarNotification) = guarded {
        notices = notices.posted(post(sbn), SystemClock.elapsedRealtime())
        val screen = screen(sbn)
        tellingChanges { if (screen != null) screens[sbn.key] = screen else screens.remove(sbn.key) }
    }

    fun removed(sbn: StatusBarNotification) = guarded {
        notices = notices.removed(sbn.key)
        tellingChanges { screens.remove(sbn.key) }
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

    /** Revision R3: the clock set is known (or Fossify newly verified) - the ringing keys of the clock apps whose screen
     * isn't kept ([AlarmNotices.screensMissing]) are asked for again and their full-screen intents kept. */
    fun clocksKnown() = guarded {
        val missing = notices.screensMissing(screens.keys, PinLockRuntime.clockPackages())
        if (missing.isEmpty()) return@guarded
        lookup?.invoke(missing.toTypedArray())?.forEach { sbn -> screen(sbn)?.let { screens[sbn.key] = it } }
    }

    /** A re-send of [key]'s screen brought nothing up ([AlarmNotices.spent]). */
    fun sendCameToNothing(key: String) = guarded { notices = notices.spent(key) }

    /** E7's M1: a screen-on - the re-send count starts afresh ([AlarmNotices.screenOn]). */
    fun screenOn() = guarded { notices = notices.screenOn() }

    /** E7's M1: one of [clockPackages] has a ringing alarm whose screen is kept - the time-rule screen's alarm row (no
     * listener lookup here; the tap's [screenKey] asks). */
    fun openable(clockPackages: Set<String>): Boolean = try {
        notices.screenKey(clockPackages, capped = false)?.let { it in screens } == true
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Alarm screen read failed: ${e.javaClass.simpleName}")
        false
    }

    /**
     * The ringing alarm whose screen may be sent again ([AlarmNotices.screenKey]; [capped] for the screens' own sends,
     * not for the kid's tap), after asking the listener which of the kept keys are still active
     * ([AlarmNotices.reconciled]; no listener or no answer: none).
     */
    fun screenKey(clockPackages: Set<String>, capped: Boolean = true): String? = try {
        val kept = notices.ringing.keys
        val active = if (kept.isEmpty()) null else lookup?.invoke(kept.toTypedArray())
        if (active == null) {
            null
        } else {
            notices = notices.reconciled(active.mapTo(mutableSetOf()) { it.key })
            tellingChanges { screens.keys.retainAll(notices.ringing.keys) }
            notices.screenKey(clockPackages, capped)?.takeIf { it in screens }
        }
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Alarm screen read failed: ${e.javaClass.simpleName}")
        null
    }

    /** Sends [key]'s full-screen intent from the resumed lock ([context], its visible window: [VisibleSender]) - only an
     * activity intent one of [clockPackages] created (code QA N1) - and counts it ([AlarmNotices.resent]). A cancelled
     * intent (re-posted meanwhile) or a refused one is a counted try that went nowhere. */
    fun sendScreen(context: Context, key: String, clockPackages: Set<String>): Boolean {
        val intent = screens[key] ?: return false
        val sent = try {
            if (!intent.isActivity || intent.creatorPackage !in clockPackages) {
                Log.i(LOG_TAG, "Alarm screen not sent: not the clock's activity")
                false
            } else {
                intent.send(context, 0, null, null, null, null, VisibleSender.options())
                true
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Alarm screen not sent: ${e.javaClass.simpleName}")
            false
        }
        guarded { notices = notices.resent(key, sent, SystemClock.elapsedRealtime()) }
        return sent
    }

    private fun shape(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        return ringingAlarmShape(n.category, n.fullScreenIntent != null)
    }

    private fun post(sbn: StatusBarNotification) = AlarmPost(sbn.key, sbn.packageName, shape(sbn), sbn.notification?.`when` ?: 0L)

    /** Kept only of the lock's clock apps (code QA N2). */
    private fun screen(sbn: StatusBarNotification): PendingIntent? =
        if (shape(sbn) && sbn.packageName in PinLockRuntime.clockPackages()) sbn.notification?.fullScreenIntent else null

    /** Runs [change] on [screens]; when their keys changed, every listener is told (each caught alone). */
    private inline fun tellingChanges(change: () -> Unit) {
        val before = screens.keys.toSet()
        change()
        if (screens.keys != before) listeners.toList().forEach { listener -> guarded { listener() } }
    }

    private inline fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Alarm notice failed: ${e.javaClass.simpleName}")
        }
    }
}
