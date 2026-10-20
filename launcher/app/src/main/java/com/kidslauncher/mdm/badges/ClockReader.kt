package com.kidslauncher.mdm.badges

import android.app.Notification
import android.app.PendingIntent
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidslauncher.mdm.lock.ClockAction
import com.kidslauncher.mdm.lock.ClockApp
import com.kidslauncher.mdm.lock.ClockKind
import com.kidslauncher.mdm.lock.ClockTile
import com.kidslauncher.mdm.lock.clockKind
import com.kidslauncher.mdm.lock.clockShown
import com.kidslauncher.mdm.lock.clockStopAction

private const val LOG_TAG = "ClockReader"

/**
 * The notification listener's reader for the lock's timer tile (design 42 §1.4), wired in
 * `BadgeListenerService` like [VoipCallReader]: of [ClockApp.PACKAGE]'s notifications, only while its
 * pinned signer is verified and only on its three channels ([clockKind]: a running timer, the
 * stopwatch, a timer that ended), the title and the text - parsed and cleaned by the pure
 * [ClockTile.parse]. Of an ended timer also its one action's intent when [clockStopAction] allows
 * it (a broadcast, never an activity), kept for the tile's "Stopp" (`lock/ClockActions`). Never an
 * activity intent, a content or full-screen intent, a view or any other package. [GlanceReader]
 * never sees these. Main thread (the listener's callbacks), memory only, nothing logged but the
 * exception class; every entry point catches everything, so a fault here never reaches the lock's
 * crash guard (`LockGlanceGuardTest`).
 */
object ClockReader {
    private class Entry(val tile: ClockTile, val receivedAtMs: Long, val stop: PendingIntent?)

    private val entries = LinkedHashMap<String, Entry>()
    private var shown: Entry? = null

    /** The tile shown (an ended timer, else a running one, else the stopwatch), when it arrived
     * (elapsed realtime) and the ended timer's "Stopp" intent. */
    val current: ClockTile? get() = shown?.tile
    val receivedAtMs: Long get() = shown?.receivedAtMs ?: 0L
    val stopIntent: PendingIntent? get() = shown?.stop

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun connected(active: List<StatusBarNotification>) = guarded {
        entries.clear()
        active.forEach { sbn -> read(sbn)?.let { entries[sbn.key] = it } }
        changed()
    }

    fun disconnected() = guarded {
        entries.clear()
        changed()
    }

    fun posted(sbn: StatusBarNotification) = guarded {
        val entry = read(sbn)
        if (entry == null && entries.remove(sbn.key) == null) return@guarded
        if (entry != null) entries[sbn.key] = entry
        changed()
    }

    fun removed(sbn: StatusBarNotification) = guarded {
        if (entries.remove(sbn.key) != null) changed()
    }

    private fun read(sbn: StatusBarNotification): Entry? {
        val n = sbn.notification ?: return null
        val kind = clockKind(sbn.packageName, ClockApp.verified, n.channelId) ?: return null
        val extras = n.extras
        val tile = ClockTile.parse(kind, extras?.getCharSequence(Notification.EXTRA_TITLE), extras?.getCharSequence(Notification.EXTRA_TEXT))
        val intents = if (kind == ClockKind.ENDED) n.actions.orEmpty().map { it.actionIntent } else emptyList()
        val stop = clockStopAction(kind, intents.map { ClockAction(it?.isActivity != false, it?.isBroadcast == true) })?.let { intents[it] }
        return Entry(tile, SystemClock.elapsedRealtime(), stop)
    }

    private fun changed() {
        val best = clockShown(entries.values.map { it.tile.kind })
        val next = entries.values.firstOrNull { it.tile.kind == best }
        if (next === shown) return
        shown = next
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
