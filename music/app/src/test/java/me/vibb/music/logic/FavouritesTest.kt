package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.buildSnapshot
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 50 §1.5: the kid's hearts (ours, not vibb's), keyed by entry key + item key, phone-only. */
class FavouritesTest {
    private val a = entry(1, category = 10)
    private val b = entry(2, category = 20)
    private val c = entry(3, category = 10)
    private val entries = listOf(a, b, c)

    @Test
    fun `set and unset by entry key - the snapshot reads them, an id change keeps them`() {
        val s = snapshot(library(entries), items(1, listOf("x", "y")) + items(2, listOf("z")))
        val hearts = mapOf(a.key to setOf("y"))
        val with = buildSnapshot(s.library, items(1, listOf("x", "y")) + items(2, listOf("z")), emptyList(), emptyList(), emptySet(), emptyMap(), emptyList(), emptyList(), emptySet(), favourites = hearts)
        assertTrue(with.favourite(a, "y"))
        assertFalse(with.favourite(a, "x"))
        assertFalse("another entry's item of the same key", with.favourite(b, "y"))
        // the entry re-imported under a new id keeps its key, so its hearts
        assertTrue(with.favourite(a.copy(id = 99), "y"))
        assertFalse("unset: no row", s.favourite(a, "y"))
    }

    @Test
    fun `the Favoritter chip - the sentinel id, while a heart is listed, a re-tap or Alle shows all`() {
        assertEquals(-1L, FAVOURITES_CATEGORY)
        val favs = setOf(a.key, b.key)
        assertEquals(FAVOURITES_CATEGORY, effectiveCategory(entries, FAVOURITES_CATEGORY, favs))
        assertEquals("r12: a heart in an entry not shown here (a book not on the phone) keeps the chip", FAVOURITES_CATEGORY, effectiveCategory(entries, FAVOURITES_CATEGORY, setOf("elsewhere")))
        assertNull("the last heart cleared: the selection falls back to all", effectiveCategory(entries, FAVOURITES_CATEGORY, emptySet()))
        assertNull(tapCategory(FAVOURITES_CATEGORY, FAVOURITES_CATEGORY))
        assertEquals(FAVOURITES_CATEGORY, tapCategory(10, FAVOURITES_CATEGORY))
        assertEquals("a real category is untouched by the hearts", listOf(a, c), shownEntries(entries, 10, favs))
        // r12 §3.1: under the chip one tile plays every heart; no entry tile, and a category tile never starts sound
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("onCategory = vm::pickCategory,"))
        assertTrue(root.contains("entries = if (inFavourites) emptyList() else shown.map {"))
        assertTrue(root.contains("favourites = if (inFavourites) favouritesUi(s, order, net) else null,"))
        assertTrue(root.contains("favouritesTile = order.isNotEmpty(),"))
    }

    @Test
    fun `the prune - only what a parsed listing no longer names, a gone entry's only when every entry was read`() {
        val hearts = mapOf(a.key to setOf("x", "y", "gone"), b.key to setOf("z"), "e-old" to setOf("q"))
        assertEquals(listOf("gone"), favouritesToPrune(hearts, a.key, setOf("x", "y")))
        assertEquals("a withdrawn item still listed (kept with its copy) keeps its heart", emptyList<String>(), favouritesToPrune(hearts, a.key, setOf("x", "y", "gone")))
        assertEquals(emptyList<String>(), favouritesToPrune(hearts, "unknown", emptySet()))
        assertEquals(setOf("e-old"), favouriteEntriesGone(hearts, entries, allRead = true))
        assertEquals("an entry the app couldn't read keeps everything", emptySet<String>(), favouriteEntriesGone(hearts, entries, allRead = false))
        val sync = moduleFile("src/main/java/me/vibb/music/sync/Sync.kt").readText()
        assertTrue("on a parsed listing", sync.substringAfter("private suspend fun applyListing(").substringBefore("\n    }\n").contains("pruneFavourites(dao, entry.key, listing.items.map { it.key }.toSet())"))
    }

    @Test
    fun `no heart on a context's synthetic item, a heart on every other row`() {
        val ctx = entry(7, Source.SPOTIFY, target = "spotify:artist:FAKEartist000000000001", cache = 0).copy(spotifyMode = "context")
        val list = entry(8, Source.SPOTIFY, target = "spotify:playlist:FAKEplaylist0000000001", cache = 0).copy(spotifyMode = "list")
        assertFalse(canFavourite(ctx, SPOTIFY_CONTEXT_KEY))
        assertTrue(canFavourite(list, "FAKEtrack0000000000001"))
        assertTrue(canFavourite(a, "x"))
        val s = snapshot(library(listOf(a)), items(1, listOf("x", "y")))
        val rows = listRows(s.order(a), "x", emptySet(), emptySet(), setOf("y"))
        assertEquals(mapOf("x" to false, "y" to true), rows.associate { it.item.key to it.favourite })
        assertEquals("no hearts asked: none drawn", listOf(null, null), listRows(s.order(a), "x", emptySet(), emptySet()).map { it.favourite })
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("s.favourites[n.entry.key].orEmpty().takeIf { !isSpotifyContext(n.entry) }"))
        assertTrue(root.contains("favourite = { item -> n.entryOf(item).let { e -> if (canFavourite(e, item.key)) s.favourite(e, item.key) else null } }"))
    }


    // design r12 §3: one "Favoritter" tile, every heart of every entry in one queue (option C is gone)

    @Test
    fun `r12 - the service - a run's end hands over before any clear_state, no bookmark in a run, the same-tile rules, the commands`() {
        val service = moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        fun body(start: String) = service.substringAfter(start).substringBefore("\n    }\n")
        val ended = body("private fun queueEnded() {")
        assertTrue("a favourites run's end hands over first", ended.indexOf("runEnded(ran, m, held)") in 0 until ended.indexOf("store.db.deleteBookmark(entryKey)"))
        assertTrue(ended.substringBefore("endSleepAtEnd(SleepEvent.QUEUE_ENDED)").contains("return"))
        // its end clears only the stored queue: no bookmark, no partial positions
        val end = body("private fun mixEnded() {")
        assertTrue(end.contains("store.db.clearNowPlaying()"))
        assertFalse(end.contains("Bookmark") || end.contains("clearPartialPositions"))
        // the row writers store the queue and write no bookmark for a run
        val local = moduleFile("src/main/java/me/vibb/music/playback/LocalRenderer.kt").readText()
        assertTrue(local.contains("a.shuffled, now, a.storedScope(), a.storedMix())"))
        assertTrue(local.contains("val place = place(a.entry.key, item.key, posMs, durationMs, now, stored, bookmark = a.mix == null)"))
        assertTrue(local.contains("place.bookmark?.let { store.db.upsertBookmark(it) }"))
        val save = body("private fun saveBookmark(")
        assertTrue(save.contains("BookmarkRow(a.entry.key, item.key, 0, now).takeIf { a.mix == null }"))
        assertTrue(save.contains("a.shuffled, now, a.storedScope(), a.storedMix())"))
        // r12 §0: a tap on the playing run's own entry starts that entry - it never unpauses the run
        assertTrue(body("private suspend fun playEntry(entryId: Long) {").contains("if (mix == null) {"))
        // the session's next/previous count the favourites queue (the lock tile, a headset, the notification)
        assertTrue(service.contains("override fun hasNextMediaItem(): Boolean = (mixOf(active)?.items?.size ?: mediaItemCount) > 1"))
        assertTrue(body("private fun skipNext() {").contains("if (mixOf(a) != null) return mixLater(moves = true) { mixStep(forward = true) }"))
        // (Commands builds SessionCommands with Android Bundles: read, not loaded)
        val hub = moduleFile("src/main/java/me/vibb/music/playback/PlayerHub.kt").readText()
        assertTrue(hub.contains("val PLAYS: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, PLAY_FAVOURITES, RESUME, ROOM_JOIN)"))
        assertTrue(hub.contains("val MOVES: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, PLAY_FAVOURITES, RESTORE, SKIP_TO)"))
        assertTrue(hub.contains("PLAY_ENTRY, PLAY_ITEM, PLAY_FAVOURITES, RESUME, RESTORE, SHUFFLE,"))
        assertTrue(service.contains("Commands.PLAY_FAVOURITES -> playFavourites()"))
        // every queue that isn't a run leaves the favourites queue: start takes the queue's own
        assertTrue(body("private fun start(a: Active, plan: QueuePlan, play: Boolean = true, newChoice: Boolean = false) {").contains("mix = a.mix"))
        // a run's step outside it keeps the play state; the next favourite is never started while suspended
        assertTrue(body("private suspend fun mixGoTo(").contains("startRun(m, found, play && playAllowed(suspendedNow())"))
        assertTrue(body("private suspend fun mixMoveOn(").contains("end.play && playAllowed(suspendedNow())"))
        // qa-r12-music-code Q-1/Q-6: the service's own pauses - a move-on past a run, a run that loads paused - never the
        // kid's (a USER pause cancels the sleep timer); Q-2: a held run end disarms the renderer's "Til slutten" first
        assertTrue(body("private fun mixPastRun(").contains("selfPause()"))
        assertTrue(body("private fun selfPause() {").contains("selfPausing = true\n        renderer.pause()\n        selfPausing = false"))
        assertTrue(body("private suspend fun startRun(").contains("if (!play && renderer.kind.onPhone && renderer.player.playWhenReady) selfPause()"))
        assertTrue(ended.indexOf("renderer.pauseAtItemEnd(null)") in 0 until ended.indexOf("sleepAtEndReached()"))
        // Q-3: a refused start keeps the old run and the move-on passes it; Q-5: RESUME and the tile see a stale run
        assertTrue(body("private suspend fun startRun(").contains("m.run = oldRun"))
        assertTrue(body("private suspend fun mixMoveOn(").contains("refused += end.index"))
        assertTrue(body("private suspend fun resumeLast() {").contains("if (!restartIfStale()) renderer.play()"))
        assertTrue(body("private suspend fun playFavourites() {").contains("if (!restartIfStale()) renderer.play()"))
        // Q-4: a favourites row stays one on every rebuild - restore, adopt, "Spill av på"
        assertEquals(3, Regex("""snap\.storedFavourites\(""").findAll(service).count())
        assertFalse(service.contains("decodeMix("))
        // option C is gone with its flag; its scope plumbing carries a run now
        assertFalse(service.contains("ARG_FAVOURITES"))
        assertTrue(body("private suspend fun startRun(").contains("queueFor(snap, rows, entry, plan, shuffled = false, queueKind(renderer.kind, entry), run.keys.toSet())"))
    }

    @Test
    fun `r12 - the stored queue - flippedNowPlaying leaves a run's row alone, a heart carries its time`() {
        val row = me.vibb.music.data.NowPlayingRow(0, 1, "x\ny", 0, 5_000, null, false, 0, favourites = "x\ny", mix = encodeMix(listOf(MixRef(a.key, "x"), MixRef(a.key, "y"))))
        assertNull(me.vibb.music.data.flippedNowPlaying(row, 1, listOf("y", "x"), 1))
        assertTrue("an entry's row still flips", me.vibb.music.data.flippedNowPlaying(row.copy(mix = null), 1, listOf("y", "x"), 1) != null)
        val vm = moduleFile("src/main/java/me/vibb/music/ui/MusicViewModel.kt").readText()
        assertTrue(vm.contains("store.db.insertFavourite(FavouriteRow(entry.key, itemKey, now))"))
    }
}
