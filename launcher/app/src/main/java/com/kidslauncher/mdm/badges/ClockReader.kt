package com.kidslauncher.mdm.badges

import android.app.Notification
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidslauncher.mdm.lock.ClockApp
import com.kidslauncher.mdm.lock.ClockTile
import com.kidslauncher.mdm.lock.clockCandidate

private const val LOG_TAG = "ClockReader"

/**
 * The notification listener's reader for the lock's timer tile (design 42 §1.4), wired in
 * `BadgeListenerService` like [VoipCallReader]: of [ClockApp.PACKAGE]'s ongoing notifications, and
 * only while its pinned signer is verified ([clockCandidate]), the title and the text - parsed and
 * cleaned by the pure [ClockTile.parse]; never an action, an intent, a view or any other package.
 * [GlanceReader] never sees these (it drops ongoing notifications). Main thread (the listener's
 * callbacks), memory only, nothing logged but the exception class; every entry point catches
 * everything, so a fault here never reaches the lock's crash guard (`ClockReaderGuardTest`).
 */
object ClockReader {
    /** The timer shown, its notification's key and when it arrived (elapsed realtime). */
    var current: ClockTile? = null
        private set
    var receivedAtMs = 0L
        private set
    private var key: String? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun connected(active: List<StatusBarNotification>) = guarded {
        val timer = active.firstOrNull(::isTimer)
        if (timer == null) set(null, null) else set(timer.key, read(timer))
    }

    fun disconnected() = guarded { set(null, null) }

    fun posted(sbn: StatusBarNotification) = guarded {
        when {
            isTimer(sbn) -> set(sbn.key, read(sbn))
            sbn.key == key -> set(null, null)
        }
    }

    fun removed(sbn: StatusBarNotification) = guarded {
        if (sbn.key == key) set(null, null)
    }

    private fun isTimer(sbn: StatusBarNotification): Boolean =
        clockCandidate(sbn.packageName, ClockApp.verified, (sbn.notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0)

    private fun read(sbn: StatusBarNotification): ClockTile {
        val extras = sbn.notification.extras
        return ClockTile.parse(extras?.getCharSequence(Notification.EXTRA_TITLE), extras?.getCharSequence(Notification.EXTRA_TEXT))
    }

    private fun set(newKey: String?, tile: ClockTile?) {
        if (tile == current && newKey == key) return
        key = newKey
        current = tile
        receivedAtMs = SystemClock.elapsedRealtime()
        listeners.toList().forEach { listener -> guarded { listener() } }
    }

    private inline fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Clock tile failed: ${e.javaClass.simpleName}")
        }
    }
}
