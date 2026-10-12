package me.vibb.music.sonos

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vibb.music.data.Store
import me.vibb.music.logic.resumeFrom
import me.vibb.music.playback.place
import me.vibb.music.playback.writePlace

/*
 * The suspension edge without a Sonos renderer in the player service (design 26a §6.1, QA H4): the
 * launcher suspended the app (a time rule) while a Sonos room may still play for us and nothing
 * drives it - the process died or never started the player, or the service runs on the phone with
 * the room still recorded (an unanswered look at start). A small standalone path over SonosHome and
 * SonosTransport: a destroyed service's stop finished first ([SonosTail]), the live classify
 * (retried while the looks' budget lasts, no group or volume read), the bookmark from it, Stop and
 * the park ([suspensionStop]); renderer.json back to the phone only on
 * a definite answer - after none the room stays recorded for the next look (qa-26a-step3b-code M1).
 * The receiver runs it on IO within its broadcast budget.
 */
object SonosSuspension {
    /** How long the receiver waits for a destroyed service's tail (its budget is ~9 s). */
    private const val TAIL_WAIT_MS = 5_000L

    /**
     * The looks end this long after the path started (the receiver's budget is 9 s): what is left
     * then is the Stop's and the park's (correction 2026-10-12: a slow speaker's first look is
     * retried inside it). After a long tail wait the one look still gets its 1.5 s.
     */
    private const val LOOKS_UNTIL_MS = 6_500L

    suspend fun standalone(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val t0 = SystemClock.elapsedRealtime()
        // the tail's own Stop leaves our uri on a stopped speaker: the look below parks it (OURS_STOPPED)
        SonosTail.join(TAIL_WAIT_MS)
        val home = SonosHome(app)
        try {
            val choice = home.choice()
            if (!choice.sonos) return@withContext
            val store = Store.get(app)
            val verdict = if (home.awaitWifi(SonosRenderer.WIFI_WAIT_MS)) {
                val clock: () -> Long = SystemClock::elapsedRealtime
                val transport = SonosTransport(SonosSession(clock, SonosHome::log), home.speakers, home.nrk, SonosClock(clock), log = SonosHome::log)
                val ofEntry = entryMatcher(store, choice)
                val lookMs = maxOf(SonosController.LIVE_BUDGET_MS, LOOKS_UNTIL_MS - (clock() - t0))
                suspensionStop(transport, choice, SonosController.LIVE_BUDGET_MS, ofEntry, lookMs, SonosClock(clock)) { live -> bookmark(app, store, choice, live) }
            } else {
                RoomVerdict.NO_ANSWER
            }
            SonosHome.log("sonos: suspended without a room renderer - $verdict")
            if (verdict != RoomVerdict.NO_ANSWER) home.write(RendererChoice.local(choice))
        } finally {
            home.release()
        }
    }

    /** With no built uri stored yet: whether a speaker's uri is one of the stored entry's items. */
    internal suspend fun entryMatcher(store: Store, choice: RendererChoice): (String) -> Boolean {
        if (!choice.uri.isNullOrEmpty()) return { false }
        val snap = store.snapshot()
        val entry = snap.entries.firstOrNull { it.id == choice.entryId } ?: return { false }
        val order = snap.order(entry)
        return { uri -> adoptIndex(entry, order, uri) != null }
    }

    /**
     * The place the classify measured, written the one way a place is written ([writePlace]: the
     * item's position, the entry's bookmark, the stored queue's row when it is on that item) -
     * through [suspensionBookmark]'s rules, never more than was measured.
     */
    private suspend fun bookmark(context: Context, store: Store, choice: RendererChoice, live: Fields) {
        val snap = store.snapshot()
        val entry = snap.entries.firstOrNull { it.id == choice.entryId } ?: return
        // no built uri stored yet: the item the speaker plays, found by its uri in the entry
        val itemKey = if (choice.uri.isNullOrEmpty()) {
            val order = snap.order(entry)
            adoptIndex(entry, order, live.uri.orEmpty())?.let { order[it].key }
        } else {
            choice.itemKey
        } ?: return
        val savedS = resumeFrom(snap.positions[entry.key]?.get(itemKey)) / 1000.0
        val w = suspensionBookmark(itemKey, live, savedS, SystemClock.elapsedRealtime()) ?: return
        val now = System.currentTimeMillis()
        val row = store.db.nowPlaying()
            ?.takeIf { it.entryId == entry.id && it.keys.split('\n').getOrNull(it.index) == w.itemKey }
            ?.copy(posMs = w.posMs, updatedAt = now)
        writePlace(context, store, place(entry.key, w.itemKey, w.posMs, w.durationMs, now, row))
    }
}
