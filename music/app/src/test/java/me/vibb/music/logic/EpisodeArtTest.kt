package me.vibb.music.logic

import me.vibb.music.Fixtures
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
import me.vibb.music.sync.downloadPlan
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
    fun `the now window - the current item and five each side, wrapping as previous and next`() {
        val q = (0 until 20).toList()
        assertEquals(listOf(0, 1, 19, 2, 18, 3, 17, 4, 16, 5, 15), nowWindow(q, 0))
        assertEquals(listOf(5, 6, 4, 7, 3, 8, 2, 9, 1, 10, 0), nowWindow(q, 5))
        assertEquals("the item and five each side", 2 * NOW_REACH + 1, nowWindow(q, 5).size)
        assertEquals("ten items: each once", 10, nowWindow((0 until 10).toList(), 0).size)
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
    fun `at most 2000 on the wire, the now list first, the missing ones only - keep has no cap`() {
        val many = (0 until 2100).map { art("k$it") }
        val w = wantedArt(listOf(a), many, emptyList(), emptyList(), emptySet(), emptySet())
        assertEquals(listOf(a), w.now)
        assertEquals(WANT_ART_MAX - 1, w.later.size)
        assertEquals(many.take(WANT_ART_MAX - 1), w.later)
        assertEquals("the prune keeps everything wanted", 2101, w.keep.size)
        // The first 1500 arrived: the next send names the rest.
        val next = wantedArt(listOf(a), many, emptyList(), emptyList(), held = many.take(1500).toSet() + a, libraryCovers = emptySet())
        assertEquals(many.drop(1500), next.later)
    }

    @Test
    fun `over the library - the keep set's art, the now window and the queue's next items, Nytt`() {
        val keys = (1..30).map { "k$it" } // oldest first; RSS plays newest first
        val pics = keys.associateWith { art(it) }
        val lib = library(listOf(entry(1, cache = 2), entry(2, cache = 0)))
        val snap = snapshot(
            lib,
            items(1, keys, art = pics) + items(2, listOf("x1", "x2"), art = mapOf("x2" to a)),
            bookmarks = mapOf("e1" to ("k20" to 1000L)),
            news = listOf(NewsRow(2, "x2", 1)),
        )
        val queue = snap.order(lib.entries[0])
        val w = artWant(snap, queue, queue.indexOfFirst { it.key == "k20" }, held = setOf(pics.getValue("k30")), recent = emptyList())
        // The window around k20 in newest-first order (±5), then what auto-advance reaches: k14 down to k1.
        val window = listOf("k20", "k19", "k21", "k18", "k22", "k17", "k23", "k16", "k24", "k15", "k25")
        assertEquals((window + (14 downTo 1).map { "k$it" }).map { pics.getValue(it) }, w.now)
        // The keep set (newest two and the bookmark + next) less now and what is held, then Nytt.
        assertEquals(listOf(pics.getValue("k29"), a), w.later)
        assertTrue("a kept picture held on the phone stays", pics.getValue("k30") in w.keep)
        assertFalse("off Wi-Fi only the keep set is fetched later", pics.getValue("k27") in w.later || pics.getValue("k27") in w.now)
        assertTrue("but every picture of a ticked entry is kept (2026-10-19)", pics.getValue("k27") in w.keep)
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
        assertFalse("book 3 isn't fetched off Wi-Fi", pics.getValue("b3") in w.later)
    }

    @Test
    fun `ahead comes last in later, deduplicated, within the cap`() {
        val w = wantedArt(listOf(a), listOf(b), listOf(c), emptyList(), held = setOf(e), libraryCovers = emptySet(), ahead = listOf(d, b, a, e, f))
        assertEquals(listOf(a), w.now)
        assertEquals(listOf(b, c, d, f), w.later)
        assertTrue("a held one ahead is kept", e in w.keep)
        val many = (0 until 700).map { art("s$it") }
        val capped = wantedArt(emptyList(), listOf(a), emptyList(), emptyList(), emptySet(), emptySet(), ahead = many)
        assertEquals(listOf(a) + many.take(WANT_ART_MAX - 1), capped.later)
    }

    /** A Spotify list-mode entry keeps nothing: its next tracks' pictures come ahead of play. */
    @Test
    fun `a Spotify list entry wants its next tracks' art from where a tap starts it`() {
        val tracks = (1..80).map { "t$it" }
        val pics = tracks.associateWith { art(it) }
        fun spotify(id: Long, mode: String) =
            entry(id, Source.SPOTIFY, target = "spotify:playlist:FAKEid$id", order = "oldest_first", cache = 0).copy(spotifyMode = mode)
        val list = spotify(8, "list")
        val context = spotify(9, "context")
        val rss = entry(1, cache = 0)
        val lib = library(listOf(rss, list, context))
        val rows = items(8, tracks, art = pics) + items(9, listOf("c1"), art = mapOf("c1" to a)) + items(1, listOf("r1"), art = mapOf("r1" to b))
        // Unstarted: its first 50 in play order. The context entry's one item is given a picture here
        // (its synthetic item has none), so only the context-mode skip keeps it out.
        val base = snapshot(lib, rows)
        val withContextArt = base.copy(listed = base.listed + (9L to base.listed.getValue(9L).map { it.copy(artHash = a) }))
        val fresh = artWant(withContextArt, emptyList(), -1, held = emptySet(), recent = emptyList())
        assertEquals("streaming's: in now (2026-10-19)", tracks.take(SPOTIFY_ART_AHEAD).map { pics.getValue(it) }, fresh.now)
        assertFalse("a context entry and an RSS entry at depth 0 fetch nothing off Wi-Fi", a in fresh.later + fresh.now || b in fresh.later + fresh.now)
        assertFalse("a context entry's one item has no picture of its own", a in fresh.keep)
        // Bookmarked on t60: t60..t80, then round to t1..t29, as the rotated queue plays.
        val started = artWant(snapshot(lib, rows, bookmarks = mapOf(list.key to ("t60" to 5_000L))), emptyList(), -1, held = emptySet(), recent = emptyList())
        val expected = (tracks.drop(59) + tracks.take(59)).take(SPOTIFY_ART_AHEAD)
        assertEquals(expected.map { pics.getValue(it) }, started.now)
        // Resume off: always from the first.
        val noResume = artWant(snapshot(library(listOf(list.copy(resume = false))), rows, bookmarks = mapOf(list.key to ("t60" to 5_000L))), emptyList(), -1, emptySet(), emptyList())
        assertEquals(fresh.now, noResume.now)
    }

    /** Design 30 §1.11 (L3): a kept episode's picture stays as long as its audio - a played extra's too. */
    @Test
    fun `an extra's picture is kept`() {
        val keys = (1..10).map { "k$it" }
        val pics = keys.associateWith { art(it) }
        val e = entry(1, cache = 2)
        val extra = Fixtures.done(1, "k4").copy(playedAt = 1_000L)
        val snap = snapshot(library(listOf(e)), items(1, keys, art = pics), downloads = listOf(extra))
        val rows = listOf(extra)
        val plan = downloadPlan(snap, rows, 2_000L)
        assertEquals(listOf("k4"), plan.extras.map { it.itemKey })
        val w = artWant(snap, emptyList(), -1, held = emptySet(), recent = emptyList(), extras = plan.extras)
        assertTrue(pics.getValue("k4") in w.keep)
        assertTrue("fetched like the keep sets' art, later", pics.getValue("k4") in w.later)
        assertFalse("without it, not fetched off Wi-Fi", pics.getValue("k4") in artWant(snap, emptyList(), -1, held = emptySet(), recent = emptyList()).later)
        // The glue: the want set over the plan's extras, as the sync and the player compute it.
        val glue = moduleFile("src/main/java/me/vibb/music/sync/ArtPlan.kt").readText()
        assertTrue(glue.contains("val extras = downloadPlan(snap, store.db.downloads(), System.currentTimeMillis(), PlayerHub.heldLocal).extras"))
        assertTrue(glue.contains("return artWant(snap, queue, index, store.coverHashes(), store.recentArt(), extras, store.listArt()?.hashes.orEmpty(), unmetered)"))
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
        // Design 26c's getSpotifyToken was appended after it (design 26c §8 item 2).
        assertEquals(listOf("wantArt", "getSpotifyToken"), methods.takeLast(2))
        assertTrue(aidl.contains("oneway void wantArt(in String[] now, in String[] later);"))
    }

    // Design 39 §2: the list's pictures.

    private fun pi(key: String, art: String?) = me.vibb.music.model.PlayItem(1, key, key, "https://cdn.example.org/$key.mp3", false, 1000, artHash = art)

    @Test
    fun `d39 - rows show pictures only where the pictures say something - the carousel's rule`() {
        val cover = art("cover")
        assertFalse("one item", rowPictures(listOf(pi("x", a)), cover))
        assertFalse("all the series cover", rowPictures(listOf(pi("x", null), pi("y", null), pi("z", cover)), cover))
        assertTrue("one own picture and the cover", rowPictures(listOf(pi("x", a), pi("y", null)), cover))
        assertFalse("one own picture for all (an album's one cover)", rowPictures(listOf(pi("x", a), pi("y", a), pi("z", a)), null))
        assertTrue("two own pictures", rowPictures(listOf(pi("x", a), pi("y", b)), null))
    }

    @Test
    fun `d39 - the list's window is the visible rows and 10 each side, clamped`() {
        val shown = (0 until 100).map { art("r$it") }
        assertEquals(shown.subList(30, 61), listArtWindow(shown, 40, 50))
        assertEquals("clamped at the top", shown.subList(0, 16), listArtWindow(shown, 0, 5))
        assertEquals("clamped at the end", shown.subList(85, 100), listArtWindow(shown, 95, 99))
        assertEquals(emptyList<String?>(), listArtWindow(emptyList(), 0, 0))
        assertEquals(listOf(null, a), listArtWindow(listOf(null, a), 0, 1, margin = 0))
    }

    @Test
    fun `d39 - the held list - the window first, own hashes only, at most 60, another entry starts empty`() {
        val first = listArtAfter(null, 7, listOf(a, null, b, "nope", c), libraryCovers = setOf(c))
        assertEquals(ListArt(7, listOf(a, b)), first)
        assertEquals("the window first, then what was held", listOf(d, a, b), listArtAfter(first, 7, listOf(d, a)).hashes)
        assertEquals("another entry's list starts empty", listOf(d), listArtAfter(first, 8, listOf(d)).hashes)
        val many = (0 until 80).map { art("w$it") }
        val capped = listArtAfter(listArtAfter(null, 7, many.take(40)), 7, many.drop(40))
        assertEquals(LIST_ART_MAX, capped.hashes.size)
        assertEquals("the newest window first", many.drop(40) + many.take(20), capped.hashes)
        assertEquals(capped, ListArt.decode(capped.encode()))
        assertEquals(null, ListArt.decode("junk\n$a"))
        assertEquals(null, ListArt.decode(null))
        assertTrue(listArtMissing(listOf(null, a), held = setOf(b), libraryCovers = emptySet()))
        assertFalse("nothing lacking: no want set per scroll", listArtMissing(listOf(null, a, c), held = setOf(a), libraryCovers = setOf(c)))
    }

    @Test
    fun `d39 - the list goes after now and before the kept, and never pushes a kept picture out of keep (QA L2)`() {
        val w = wantedArt(listOf(a), kept = listOf(b, c), news = emptyList(), recent = emptyList(), held = emptySet(), libraryCovers = emptySet(), list = listOf(d, b))
        assertEquals(listOf(a), w.now)
        assertEquals("the wire: the list, then the keep sets", listOf(d, b, c), w.later)
        val kept = (0 until 2100).map { art("k$it") }
        val list = (0 until 60).map { art("l$it") }
        val full = wantedArt(emptyList(), kept, emptyList(), emptyList(), emptySet(), emptySet(), list = list)
        assertEquals(WANT_ART_MAX, full.later.size)
        assertEquals("the list first on the wire", list, full.later.take(60))
        assertTrue("every kept one still in keep", full.keep.containsAll(kept))
        assertTrue("and the list", full.keep.containsAll(list))
        assertEquals(2100 + 60, full.keep.size)
    }

    @Test
    fun `d39 - the send rule - a downloaded episode plays, the list scrolls, the list's pictures are sent`() {
        assertTrue("forced, an empty now, a new later hash", wantArtSend(emptyList(), listOf(a, b), sentNow = listOf(c), sentLater = setOf(a), force = true))
        assertFalse("forced with nothing new", wantArtSend(listOf(c), listOf(a), sentNow = null, sentLater = setOf(a, b), force = true))
        assertTrue("forced, never sent before", wantArtSend(emptyList(), listOf(a), null, null, force = true))
        assertFalse("not forced: an empty now sends nothing (today's rule)", wantArtSend(emptyList(), listOf(a), null, null, force = false))
        assertFalse("not forced: the same now", wantArtSend(listOf(a), listOf(b), listOf(a), emptySet(), force = false))
        assertTrue("not forced: a new now", wantArtSend(listOf(c), emptyList(), listOf(a), setOf(b), force = false))
    }

    @Test
    fun `qa-39-code L1 - LiveBridge sends through wantArtSend, and the list's rows get the picture rule`() {
        val bridge = moduleFile("src/main/java/me/vibb/music/bridge/Bridge.kt").readText()
        assertTrue(bridge.contains("if (!wantArtSend(now, later, sentNow, sentLater, force)) return"))
        assertTrue(bridge.contains("sentLater = later.toSet()"))
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("val pictures = !isSpotifyContext(n.entry) && rowPictures(shown, n.entry.cover)"))
        assertTrue(root.contains("pictures = pictures,"))
    }

    @Test
    fun `d39 - the want set reads the list - every sender carries it, and the prune keeps it`() {
        val plan = moduleFile("src/main/java/me/vibb/music/sync/ArtPlan.kt").readText()
        assertTrue(plan.contains("artWant(snap, queue, index, store.coverHashes(), store.recentArt(), extras, store.listArt()?.hashes.orEmpty(), unmetered)"))
        val sync = moduleFile("src/main/java/me/vibb/music/sync/Sync.kt").readText()
        assertTrue("the prune's keep is the want set's", sync.contains("val want = ArtWants.compute(context)\n        if (lib != null) store.pruneCovers(lib.covers, want.keep)"))
    }

    // The addendum of 2026-10-19: all pictures ASAP on Wi-Fi; the playing episode's picture at once.

    @Test
    fun `asap - on Wi-Fi every picture of every ticked entry is wanted in play order, now and the keep sets first`() {
        val keys = (1..300).map { "k$it" } // oldest first; RSS plays newest first
        val pics = keys.associateWith { art(it) }
        val other = (1..5).map { "o$it" }
        val otherPics = other.associateWith { art(it) }
        val lib = library(listOf(entry(1, cache = 2), entry(2, order = "oldest_first", cache = 0)))
        val snap = snapshot(lib, items(1, keys, art = pics) + items(2, other, art = otherPics), bookmarks = mapOf("e1" to ("k100" to 1L)))
        val queue = snap.order(lib.entries[0])
        val at = queue.indexOfFirst { it.key == "k100" }
        val held = setOf(pics.getValue("k5"))
        val metered = artWant(snap, queue, at, held = held, recent = emptyList())
        val wifi = artWant(snap, queue, at, held = held, recent = emptyList(), unmetered = true)
        assertEquals("the now list is the same", metered.now, wifi.now)
        assertEquals("the window (11) and the next 50 the queue reaches, less the 5 in both", 56, wifi.now.size)
        assertEquals("the keep sets first, as off Wi-Fi", metered.later, wifi.later.take(metered.later.size))
        val newestFirst = keys.reversed().map { pics.getValue(it) }
        val expectedRest = (newestFirst + other.map { otherPics.getValue(it) }).filter { it !in wifi.now && it !in metered.later && it !in held }
        assertEquals("then every item of every entry in play order, less the held", expectedRest, wifi.later.drop(metered.later.size))
        assertEquals("all 305 pictures less the held one go", 304, wifi.now.size + wifi.later.size)
        assertTrue("off Wi-Fi far fewer", metered.later.size < 10)
        assertEquals("the prune keeps the same on both networks", metered.keep, wifi.keep)
        assertTrue(wifi.keep.containsAll(pics.values + otherPics.values))
        assertTrue("a Wi-Fi sync is asked for off Wi-Fi", wifiSyncWanted(metered, wifi))
        assertFalse("not once everything is there", wifiSyncWanted(wifi, wifi))
    }

    @Test
    fun `streaming's now - the window, the queue's next items without a wrap, then Spotify's look-ahead`() {
        val q = (0 until 100).toList()
        val now = nowArt(q, 10, listOf("s1", null)) { "i$it" }
        val window = nowWindow(q, 10).map { "i$it" }
        assertEquals(window, now.take(11))
        assertEquals((11..60).map { "i$it" }, now.drop(11).take(50))
        assertEquals(listOf("s1", null), now.takeLast(2))
        assertEquals("at the end nothing wraps ahead", listOf("i99", "i0", "i98", "i1", "i97", "i2", "i96", "i3", "i95", "i4", "i94"), nowArt(q, 99, emptyList()) { "i$it" })
        assertEquals("no queue: Spotify's only", listOf("s1"), nowArt(emptyList<Int>(), -1, listOf("s1")) { "i$it" })
        assertTrue(WANT_ART_NOW_MAX >= 11 + NOW_AHEAD + SPOTIFY_ART_AHEAD)
    }

    @Test
    fun `asap - the player sends at once, a run of skips once more at the end of the gap`() {
        assertEquals("the first item change: at once", 0L, wantArtDelay(null, 10_000L))
        assertEquals("long after the last send: at once", 0L, wantArtDelay(1_000L, 10_000L))
        assertEquals("a skip 500 ms after a send: the rest of the gap", WANT_ART_GAP_MS - 500, wantArtDelay(10_000L, 10_500L))
        assertEquals("a clock that went back: at once", 0L, wantArtDelay(10_000L, 9_000L))
        val service = moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        assertTrue(service.contains("handler.postDelayed(wantArtTick, wantArtDelay(wantArtAt, android.os.SystemClock.elapsedRealtime()))"))
        assertFalse("no fixed 2 s wait", service.contains("handler.postDelayed(wantArtTick, 2_000)"))
    }

    @Test
    fun `asap - a listing applied while playing re-sends the want set with its new pictures`() {
        // The launcher nudges on a new listing; the sync applies it, then computes and sends the want set.
        val sync = moduleFile("src/main/java/me/vibb/music/sync/Sync.kt").readText()
        val applied = sync.indexOf("applyListing(store, entry, listing)")
        val computed = sync.indexOf("val want = ArtWants.compute(context)")
        assertTrue(applied in 0 until computed)
        assertTrue(computed < sync.indexOf("BridgeSync.wantArt(context, want)"))
        // The playing item's new hash is in `now` (the window reads the stored items).
        val keys = (1..3).map { "k$it" }
        val lib = library(listOf(entry(1, cache = 0)))
        val before = snapshot(lib, items(1, keys))
        val after = snapshot(lib, items(1, keys, art = mapOf("k2" to a)))
        assertEquals(emptyList<String>(), artWant(before, before.order(lib.entries[0]), 1, emptySet(), emptyList()).now)
        assertEquals(listOf(a), artWant(after, after.order(lib.entries[0]), 1, emptySet(), emptyList()).now)
    }

    @Test
    fun `hashes are 64 hex`() {
        assertTrue(isArtHash(a))
        assertFalse(isArtHash("AB".repeat(32)))
        assertFalse(isArtHash(a.drop(1)))
        assertFalse(isArtHash(null))
    }
}
