package me.vibb.music.logic

import me.vibb.music.Fixtures.art
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.bridge.listingsWanted
import me.vibb.music.data.MusicDb
import me.vibb.music.data.NewsRow
import me.vibb.music.data.REAPPLY_LISTINGS_SQL
import me.vibb.music.model.Source
import me.vibb.music.sync.artWant
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Per-episode art on the phone (design 27 §2): the want set (vibb's "art for the kept episodes" and
 * the lazy fetch of what is shown), the prune, and the Room migration that re-applies the listings.
 */
class EpisodeArtTest {
    private val a = art("a")
    private val b = art("b")
    private val c = art("c")
    private val d = art("d")
    private val e = art("e")
    private val f = art("f")

    @Test
    fun `the now window - the current item and two each side, wrapping as previous and next`() {
        val q = (0 until 10).toList()
        assertEquals(listOf(0, 1, 9, 2, 8), nowWindow(q, 0))
        assertEquals(listOf(5, 6, 4, 7, 3), nowWindow(q, 5))
        assertEquals("a short queue lists each once", listOf(0, 1), nowWindow(listOf(0, 1), 0))
        assertEquals(listOf(1, 0), nowWindow(listOf(0, 1), 1))
        assertEquals(emptyList<Int>(), nowWindow(q, -1))
        assertEquals(listOf(3, 4, 2), nowWindow(q, 3, reach = 1))
    }

    @Test
    fun `the want set - now first, held ones left out but kept, library covers never, deduplicated`() {
        val w = wantedArt(
            now = listOf(a, null, b, a),
            kept = listOf(c, b, null, d, "not-a-hash"),
            news = listOf(e, c),
            recent = listOf(f),
            held = setOf(b, d),
            libraryCovers = setOf(e),
        )
        assertEquals(listOf(a), w.now)
        assertEquals(listOf(c), w.later)
        assertEquals("the prune's universe: everything wanted, held or not, and the recent", setOf(a, b, c, d, f), w.keep)
    }

    @Test
    fun `at most 600 wanted in all, the now list first`() {
        val many = (0 until 700).map { art("k$it") }
        val w = wantedArt(listOf(a), many, emptyList(), emptyList(), emptySet(), emptySet())
        assertEquals(listOf(a), w.now)
        assertEquals(WANT_ART_MAX - 1, w.later.size)
        assertEquals(many.take(WANT_ART_MAX - 1), w.later)
        assertEquals(WANT_ART_MAX, w.keep.size)
    }

    @Test
    fun `over the library - the keep set's art, the now window, Nytt`() {
        val keys = (1..10).map { "k$it" } // oldest first; RSS plays newest first
        val pics = keys.associateWith { art(it) }
        val lib = library(listOf(entry(1, cache = 2), entry(2, cache = 0)))
        val snap = snapshot(
            lib,
            items(1, keys, art = pics) + items(2, listOf("x1", "x2"), art = mapOf("x2" to a)),
            bookmarks = mapOf("e1" to ("k3" to 1000L)),
            news = listOf(NewsRow(2, "x2", 1)),
        )
        val queue = snap.order(lib.entries[0])
        val w = artWant(snap, queue, queue.indexOfFirst { it.key == "k3" }, held = setOf(pics.getValue("k10")), recent = emptyList())
        // The window around k3 in newest-first order: k3, k2, k4, k1, k5.
        assertEquals(listOf("k3", "k2", "k4", "k1", "k5").map { pics.getValue(it) }, w.now)
        // The keep set (newest two and the bookmark + next) less the window and what is held, then Nytt.
        assertEquals(listOf(pics.getValue("k9"), a), w.later)
        assertTrue("a kept picture held on the phone stays", pics.getValue("k10") in w.keep)
        assertFalse(pics.getValue("k6") in w.keep)
    }

    @Test
    fun `a Storytel series wants its kept books' art in download order, not the newest (26b step 4a)`() {
        // Five books in reading order, the first two kept (cache 2), the bookmark on book 4: the download
        // plan keeps 1, 2, 4 and 5 and fetches 4 first, then reading order - the art follows that rule.
        val books = listOf("b1", "b2", "b3", "b4", "b5")
        val pics = books.associateWith { art(it) }
        val series = entry(7, Source.STORYTEL, target = "storytel:series:26175", cache = 2)
        val snap = snapshot(
            library(listOf(series)),
            items(7, books, gone = books.toSet(), art = pics),
            bookmarks = mapOf(series.key to ("b4" to 1000L)),
        )
        val w = artWant(snap, emptyList(), -1, held = emptySet(), recent = emptyList())
        assertEquals(listOf("b4", "b1", "b2", "b5").map { pics.getValue(it) }, w.later)
        assertFalse("book 3 isn't kept", pics.getValue("b3") in w.keep)
    }

    @Test
    fun `the prune - only what is neither a library cover nor in the universe`() {
        assertEquals(sortedSetOf(c, "junk"), coversToDelete(setOf(a, b, c, "junk"), setOf(a), setOf(b)))
        assertEquals("nothing on the phone", emptySet<String>(), coversToDelete(emptySet(), setOf(a), setOf(b)))
    }

    @Test
    fun `the prune runs only on a parsed library, after the listings are applied`() {
        val sync = moduleFile("src/main/java/me/vibb/music/sync/Sync.kt").readText()
        assertTrue(sync.contains("if (lib != null) store.pruneCovers(lib.covers, want.keep)"))
        assertTrue(sync.indexOf("applyListing(store, entry, listing)") < sync.indexOf("store.pruneCovers("))
    }

    @Test
    fun `the recent pictures - newest first, at most 20`() {
        assertEquals(listOf(b, a), recentArt(listOf(a), listOf(b, null, "x")))
        assertEquals(listOf(a, b), recentArt(listOf(b, a), listOf(a)))
        val full = (0 until 30).map { art("r$it") }
        assertEquals(RECENT_ART_MAX, recentArt(full, listOf(a)).size)
        assertEquals(a, recentArt(full, listOf(a)).first())
    }

    // QA H1: the stored listings are re-applied after the update.

    @Test
    fun `the migration empties every listing version, so the next sync pulls and re-applies them`() {
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") executed += args[0] as String
            null
        } as SupportSQLiteDatabase
        MusicDb.ReapplyListings().onPostMigrate(db)
        assertEquals(listOf("UPDATE listings SET version = ''"), executed)
        assertEquals(REAPPLY_LISTINGS_SQL, executed.single())
        // An empty version is never served, so every listing the launcher holds is wanted again.
        assertEquals(listOf(1L, 2L), listingsWanted(mapOf(1L to "0123456789abcdef", 2L to "fedcba9876543210", 3L to null), mapOf(1L to "", 2L to "")))
    }

    @Test
    fun `the schema is exported - version 2 adds items artHash by an auto migration`() {
        val v2 = moduleFile("schemas/me.vibb.music.data.MusicDb/2.json").readText()
        assertTrue(v2.contains("\"version\": 2"))
        assertTrue(v2.contains("`artHash` TEXT"))
        val db = moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 1, to = 2, spec = MusicDb.ReapplyListings::class)"))
    }

    @Test
    fun `an older launcher drops wantArt without an error - it is oneway and appended last`() {
        val aidl = moduleFile("src/main/aidl/me/vibb/bridge/IMusicBridge.aidl").readText()
        val methods = Regex("^\\s+(oneway )?[\\w\\[\\]]+ (\\w+)\\(", RegexOption.MULTILINE).findAll(aidl).map { it.groupValues[2] }.toList()
        assertEquals("wantArt", methods.last())
        assertTrue(aidl.contains("oneway void wantArt(in String[] now, in String[] later);"))
    }

    @Test
    fun `hashes are 64 hex`() {
        assertTrue(isArtHash(a))
        assertFalse(isArtHash("AB".repeat(32)))
        assertFalse(isArtHash(a.drop(1)))
        assertFalse(isArtHash(null))
    }
}
