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
import com.kidslauncher.mdm.lock.clockStale
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
 *
 * Fix 2026-10-21 (the empty "Timer" card, the stopwatch hidden behind it): a group summary - Android
 * 16 autogroups an app's second ungrouped notification under a system-made summary on a child's
 * channel, with no title or text - and a blank tile are never kept; and on each of the clock app's
 * posts and removals, and when the glance page shows ([reconcile]), the kept keys are checked against
 * the listener's active notifications ([attach]: asked for exactly those keys), so a removal missed
 * for any reason no longer keeps a tile up until the next sweep.
 */
object ClockReader {
    private class Entry(val tile: ClockTile, val receivedAtMs: Long, val stop: PendingIntent?)

    private val entries = LinkedHashMap<String, Entry>()
    private var shown: Entry? = null

    /** The listener's `getActiveNotifications(keys)` while it is connected: only the kept keys are
     * ever asked for (all of [ClockApp.PACKAGE]); null = no answer. */
    private var lookup: ((Array<String>) -> List<StatusBarNotification>?)? = null

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

    /** The listener connected: its lookup of active notifications by key (before its sweep). */
    fun attach(activeByKeys: (Array<String>) -> List<StatusBarNotification>?) = guarded {
        lookup = activeByKeys
    }

    fun connected(active: List<StatusBarNotification>) = guarded {
        entries.clear()
        active.forEach { sbn -> read(sbn)?.let { entries[sbn.key] = it } }
        changed()
    }

    /** The listener is gone: nothing kept, nothing to ask. */
    fun disconnected() = guarded {
        lookup = null
        entries.clear()
        changed()
    }

    /** The clock app lost its pinned signer (`ClockApp.refresh`): the tile goes at once. */
    fun unverified() = guarded {
        entries.clear()
        changed()
    }

    fun posted(sbn: StatusBarNotification) = guarded {
        if (sbn.packageName != ClockApp.PACKAGE) return@guarded
        val entry = read(sbn)
        val touched = if (entry != null) {
            entries[sbn.key] = entry
            true
        } else {
            entries.remove(sbn.key) != null
        }
        if (dropStale() || touched) changed()
    }

    fun removed(sbn: StatusBarNotification) = guarded {
        if (sbn.packageName != ClockApp.PACKAGE) return@guarded
        val gone = entries.remove(sbn.key) != null
        if (dropStale() || gone) changed()
    }

    /** The glance page shows (`GlancePanel`): what the listener no longer has goes before it is drawn. */
    fun reconcile() = guarded {
        if (dropStale()) changed()
    }

    /** Drops the kept entries the listener no longer has active ([clockStale]); true when any went.
     * No listener or no answer: everything stays (the next event or sweep decides). */
    private fun dropStale(): Boolean {
        if (entries.isEmpty()) return false
        val active = lookup?.invoke(entries.keys.toTypedArray()) ?: return false
        val stale = clockStale(entries.keys, active.filter { it.packageName == ClockApp.PACKAGE }.map { it.key })
        stale.forEach { entries.remove(it) }
        return stale.isNotEmpty()
    }

    private fun read(sbn: StatusBarNotification): Entry? {
        val n = sbn.notification ?: return null
        val kind = clockKind(sbn.packageName, ClockApp.verified, n.channelId) ?: return null
        // A group summary is no timer (the system's autogroup summary: a child's channel, no title, no text).
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras
        val tile = ClockTile.parse(kind, extras?.getCharSequence(Notification.EXTRA_TITLE), extras?.getCharSequence(Notification.EXTRA_TEXT))
        if (tile.blank) return null
        val intents = if (kind == ClockKind.ENDED) n.actions.orEmpty().map { it.actionIntent } else emptyList()
        val stop = clockStopAction(kind, intents.map { ClockAction(it?.isActivity != false, it?.isBroadcast == true) })?.let { intents[it] }
        return Entry(tile, SystemClock.elapsedRealtime(), stop)
    }

    private fun changed() {
        val best = clockShown(entries.values.map { it.tile })
        val next = entries.values.firstOrNull { it.tile === best }
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
