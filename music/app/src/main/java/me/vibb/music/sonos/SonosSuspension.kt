package me.vibb.music.sonos

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vibb.music.data.BookmarkRow
import me.vibb.music.data.PositionRow
import me.vibb.music.data.Store
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.save

/*
 * The suspension edge without the player service (design 26a §6.1, QA H4): the launcher suspended
 * the app (a time rule) while a Sonos room may still play for us and no service is up to stop it
 * (the process died, or never started the player). A small standalone path over SonosHome and
 * SonosTransport: the one live classify, the bookmark from it, Stop and the park
 * ([suspensionStop]); renderer.json back to the phone. The receiver runs it on IO within its
 * broadcast budget.
 */
object SonosSuspension {
    suspend fun standalone(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val home = SonosHome(app)
        try {
            val choice = home.choice()
            if (!choice.sonos) return@withContext
            if (home.awaitWifi(SonosRenderer.WIFI_WAIT_MS)) {
                val clock: () -> Long = SystemClock::elapsedRealtime
                val transport = SonosTransport(SonosSession(clock, SonosHome::log), home.speakers, home.nrk, SonosClock(clock), log = SonosHome::log)
                val stopped = suspensionStop(transport, choice, SonosController.LIVE_BUDGET_MS) { live -> bookmark(app, choice, live) }
                SonosHome.log("sonos: suspended with no player - ${if (stopped) "our stream stopped and parked" else "nothing of ours playing"}")
            } else {
                SonosHome.log("sonos: suspended with no player and no Wi-Fi - the speaker is out of reach")
            }
            home.write(RendererChoice.local(choice))
        } finally {
            home.release()
        }
    }

    /**
     * The place the classify measured, written as the player writes it (`Bookmarks.save`: the item's
     * position, the entry's bookmark, the stored queue's position when it is on that item) - through
     * [suspensionBookmark]'s rules, never more than was measured.
     */
    private suspend fun bookmark(context: Context, choice: RendererChoice, live: Fields) {
        val store = Store.get(context)
        val snap = store.snapshot()
        val entry = snap.entries.firstOrNull { it.id == choice.entryId } ?: return
        val itemKey = choice.itemKey ?: return
        val savedS = resumeFrom(snap.positions[entry.key]?.get(itemKey)) / 1000.0
        val w = suspensionBookmark(itemKey, live, savedS, SystemClock.elapsedRealtime()) ?: return
        val (rec, mark) = save(w.itemKey, w.posMs, w.durationMs)
        val now = System.currentTimeMillis()
        store.db.upsertPosition(PositionRow(entry.key, w.itemKey, rec.posMs, rec.done, now))
        store.db.upsertBookmark(BookmarkRow(entry.key, mark.itemKey, mark.posMs, now))
        store.db.nowPlaying()?.takeIf { row -> row.entryId == entry.id && row.keys.split('\n').getOrNull(row.index) == w.itemKey }?.let { row ->
            store.db.upsertNowPlaying(row.copy(posMs = w.posMs, updatedAt = now))
        }
    }
}
