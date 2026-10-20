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
        // one entry with hearts: the tap opens it; more, the tile filters
        assertEquals(a.id, favouritesTapOpens(entries, setOf(a.key)))
        assertNull(favouritesTapOpens(entries, favs))
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
}
