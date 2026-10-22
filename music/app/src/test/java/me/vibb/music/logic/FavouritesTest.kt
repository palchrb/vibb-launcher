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
    fun `the Favoritter tile - the sentinel id, the entries holding a heart, a re-tap or Alle shows all`() {
        assertEquals(-1L, FAVOURITES_CATEGORY)
        val favs = favouriteEntryKeys(mapOf(a.key to setOf("y"), b.key to setOf("z"), c.key to emptySet()))
        assertEquals(setOf(a.key, b.key), favs)
        assertEquals(listOf(a, b), shownEntries(entries, FAVOURITES_CATEGORY, favs))
        assertEquals(FAVOURITES_CATEGORY, effectiveCategory(entries, FAVOURITES_CATEGORY, favs))
        assertNull("the last heart cleared: the selection falls back to all", effectiveCategory(entries, FAVOURITES_CATEGORY, emptySet()))
        assertEquals(entries, shownEntries(entries, FAVOURITES_CATEGORY, emptySet()))
        assertNull(tapCategory(FAVOURITES_CATEGORY, FAVOURITES_CATEGORY))
        assertEquals(FAVOURITES_CATEGORY, tapCategory(10, FAVOURITES_CATEGORY))
        assertEquals("a real category is untouched by the hearts", listOf(a, c), shownEntries(entries, 10, favs))
        // code QA 4: the tile filters like every category, one entry or many - no category tile starts sound
        assertEquals(listOf(a), shownEntries(entries, FAVOURITES_CATEGORY, setOf(a.key)))
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("onCategory = vm::pickCategory,"))
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
        assertTrue(root.contains("favourite = { item -> if (canFavourite(n.entry, item.key)) s.favourite(n.entry, item.key) else null }"))
    }

    // design r8 §1: option C - under "Favoritter" a cover tap plays only the entry's hearted items

    @Test
    fun `r8 - the scope is the hearts the listing still names, the same in both views`() {
        val s = snapshot(library(entries), items(1, listOf("x", "y", "z"), gone = setOf("z")))
            .copy(favourites = mapOf(a.key to setOf("z", "x", "dropped")))
        assertEquals("in the listing's order; a withdrawn item stays, a dropped one waits for the prune", listOf("x", "z"), s.heartsListed(a).toList())
        assertEquals(s.heartsListed(a), s.forRenderer(true).heartsListed(a))
        assertEquals(emptySet<String>(), s.heartsListed(b))
    }

    @Test
    fun `r8 - favouritesScope keeps the scope in the given order and starts at the bookmark in it, else the first at its place`() {
        val flipped = listOf("e5", "e4", "e3", "e2", "e1")
        val scope = setOf("e2", "e4", "gone")
        val pos = { k: String -> if (k == "e4") ItemPosition(90_000, false) else null }
        assertEquals("a flipped order stays flipped; a key the order lacks drops", listOf("e4", "e2"), favouritesScope(flipped, scope, null, true, pos).keys)
        assertEquals("the bookmark when it is in the scope", Bookmark("e2", 30_000), favouritesScope(flipped, scope, Bookmark("e2", 30_000), true, pos).bookmark)
        assertEquals("else the first item at its own place", Bookmark("e4", 90_000), favouritesScope(flipped, scope, Bookmark("e3", 30_000), true, pos).bookmark)
        assertEquals("a done first item from 0", Bookmark("e4", 0), favouritesScope(flipped, scope, null, true) { ItemPosition(600_000, true) }.bookmark)
        assertNull("resume off: no bookmark, the top", favouritesScope(flipped, scope, Bookmark("e3", 30_000), false, pos).bookmark)
        assertEquals("nothing of the scope in the order", FavouritesScope(emptyList(), null), favouritesScope(flipped, setOf("x"), Bookmark("e3", 1), true, pos))
        // through the unchanged coverTapPlan: the hearted run, at e4's place
        val from = favouritesScope(flipped, scope, Bookmark("e3", 30_000), true, pos)
        assertEquals(QueuePlan(listOf("e4", "e2"), 0, 90_000), coverTapPlan(from.keys, true, from.bookmark, shuffle = false))
        // no scope: the whole entry, the bookmark as it is
        assertEquals(FavouritesScope(flipped, Bookmark("e3", 30_000)), coverTapScope(flipped, null, Bookmark("e3", 30_000), true, pos))
    }

    @Test
    fun `r8 - the Storytel wait - a bookmark on a hearted book not on the phone waits, on an unhearted one it doesn't`() {
        val series = entry(7, Source.STORYTEL, target = "storytel:series:26175", cache = -1)
        val mark = Bookmark("b3", 120_000)
        val onPhone = listOf("b1")
        val listed = listOf("b1", "b2", "b3")
        assertTrue(coverTapAwaitsBook(series, bookmarkIn(mark, setOf("b1", "b3")), onPhone, listed))
        assertFalse("its tap plays the hearted b1", coverTapAwaitsBook(series, bookmarkIn(mark, setOf("b1")), onPhone, listed))
        assertTrue("Alle: the whole entry waits, as before", coverTapAwaitsBook(series, bookmarkIn(mark, null), onPhone, listed))
    }

    @Test
    fun `r8 - the service keeps the scope - the same-tile rule, both row writers, restore and the queue's end`() {
        val service = moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        fun body(start: String) = service.substringAfter(start).substringBefore("\n    }\n")
        val tap = body("private suspend fun playEntry(entryId: Long, favourites: Boolean) {")
        assertTrue("a tap with the loaded queue's scope unpauses", tap.contains("if ((current.favourites != null) == favourites) {"))
        assertTrue("the other scope: the place lands before the plan", tap.indexOf("renderer.saveNow(force = true)") in 0 until tap.indexOf("loadForRenderer()"))
        assertTrue(service.contains("Commands.PLAY_ENTRY -> playEntry(args.getLong(Commands.ARG_ENTRY), args.getBoolean(Commands.ARG_FAVOURITES))"))
        assertTrue(body("private fun saveBookmark(").contains("a.shuffled, now, a.storedScope())"))
        assertTrue(moduleFile("src/main/java/me/vibb/music/playback/LocalRenderer.kt").readText().contains("a.shuffled, now, a.storedScope())"))
        assertTrue(body("private fun queueEnded() {").contains("if (held == null) store.db.clearPartialPositions(entryKey) else store.db.clearPartialPositionsOf(entryKey, held)"))
        assertTrue(body("private fun publish() {").contains("favourites = a?.favourites,"))
        assertTrue(body("private suspend fun adoptedQueue(").contains("adoptPlan(entry, scoped(snap.order(entry), favourites), stored, row?.shuffled == true, uri)"))
        assertTrue(body("private suspend fun activeFor(").contains("val order = scoped(snap.order(entry), favourites)"))
    }
}
