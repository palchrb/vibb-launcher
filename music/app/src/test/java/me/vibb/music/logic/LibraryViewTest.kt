package me.vibb.music.logic

import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.NewsRow
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.sync.NewNotifier
import me.vibb.music.ui.NowView
import me.vibb.music.ui.entryUi
import me.vibb.music.ui.playerNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library screen's words and rows (design 21 §4.2). */
class LibraryViewTest {
    private val order = listOf("e5", "e4", "e3", "e2", "e1")

    @Test
    fun `hints - started, not started, from the start`() {
        val podcast = entry(1)
        assertEquals(Hint.Started(Kind.PODCAST, 3, 5), hint(podcast, order, Bookmark("e3", 0)))
        assertEquals(Hint.Started(Kind.PODCAST, 1, 5), hint(podcast, order, Bookmark("e5", 10_000)))
        assertEquals(Hint.NotStarted(Kind.PODCAST), hint(podcast, order, null))
        assertEquals(Hint.NotStarted(Kind.PODCAST), hint(podcast, order, Bookmark("e5", 0)))
        assertEquals(Hint.FromStart(Kind.OWN), hint(entry(2, Source.OWN), order, Bookmark("e3", 0)))
    }

    @Test
    fun `the overall progress counts items before plus the fraction of this one`() {
        assertEquals(0.5f, overallProgress(order, Bookmark("e3", 300_000), 600_000), 0.001f)
        assertEquals(0f, overallProgress(order, null, 600_000), 0f)
    }

    @Test
    fun `NY shows while the entry has visible items in Nytt`() {
        val e = entry(1)
        val snap = snapshot(library(listOf(e)), items(1, listOf("x", "y"), gone = setOf("x")), news = listOf(NewsRow(1, "y", 1)))
        assertTrue(entryUi(snap, e).isNew)
        val hidden = snapshot(library(listOf(e)), items(1, listOf("x", "y"), gone = setOf("x")), news = listOf(NewsRow(1, "x", 1)))
        assertFalse(entryUi(hidden, e).isNew)
        assertEquals("the bell and the notification count only what can play", 0, NewNotifier.count(hidden))
        assertEquals(1, NewNotifier.count(snap))
    }

    @Test
    fun `a Spotify tile - the content's kind and hint over its visible list, the web player's or the stored line in its place (26c §4_3, §5_8, step 8)`() {
        val playlist = entry(6, Source.SPOTIFY, target = "spotify:playlist:FAKEid6", cache = 0).copy(spotifyMode = "list")
        val show = entry(7, Source.SPOTIFY, target = "spotify:show:FAKEid7", cache = 0).copy(spotifyMode = "list")
        val rows = listOf("t1", "t2", "t3").mapIndexed { i, k -> me.vibb.music.data.ItemRow(6, k, i, "Song $k — A", null, false, 180_000, null) } +
            listOf("p1", "p2").mapIndexed { i, k -> me.vibb.music.data.ItemRow(7, k, i, "Episode $k", null, false, 900_000, null) }
        val snap = snapshot(library(listOf(playlist, show)), rows, bookmarks = mapOf(playlist.key to ("t3" to 5_000L), show.key to ("p1" to 9_000L)))
        assertEquals(Hint.Started(Kind.MUSIC, 3, 3), entryUi(snap, playlist).hint)
        assertEquals("a show newest first, a podcast's episodes", Hint.Started(Kind.PODCAST, 2, 2), entryUi(snap, show).hint)
        assertEquals("no source on the tile: music", Kind.MUSIC, entryUi(snap, playlist).kind)
        assertEquals("a show is a podcast, like NRK's and RSS's", Kind.PODCAST, entryUi(snap, show).kind)
        assertEquals(me.vibb.music.playback.RendererState.SPOTIFY_NEEDS_WIFI, entryUi(snap, playlist, 6, me.vibb.music.playback.RendererState.SPOTIFY_NEEDS_WIFI).state)
        assertEquals(me.vibb.music.playback.RendererState.SPOTIFY_ASK_ADULT, entryUi(snap, show, 6, null, "reauthorize").state)
        assertNull(entryUi(snap, show, 6, null, "ok").state)
        // no offline marks in its list: no row is "on the phone", whatever the download rows say
        val withRows = snapshot(library(listOf(playlist, show)), rows, downloads = listOf(done(6, "t1")))
        val shown = withRows.order(playlist)
        assertEquals(3, shown.size)
        assertEquals("never a copy, even with a stray download row (the plan prunes it)", emptySet<String>(), withRows.onPhone[6].orEmpty())
        assertTrue(listRows(shown, "t2", emptySet(), withRows.onPhone[6].orEmpty()).none { it.offline })
    }

    @Test
    fun `a Spotify context's tile - the order is picked, no N av M, its one row (26c §4_3, §5_9, step 6)`() {
        val artist = entry(7, Source.SPOTIFY, target = "spotify:artist:FAKEid7", cache = 0).copy(spotifyMode = "context")
        val unstarted = snapshot(library(listOf(artist)))
        assertEquals(Hint.Picks(Kind.MUSIC), entryUi(unstarted, artist).hint)
        val started = snapshot(library(listOf(artist)), bookmarks = mapOf(artist.key to (SPOTIFY_CONTEXT_KEY to 70_000L)))
        assertEquals("started or not: no number", Hint.Picks(Kind.MUSIC), entryUi(started, artist).hint)
        assertEquals("resume off too", Hint.Picks(Kind.MUSIC), hint(artist.copy(resume = false), listOf(SPOTIFY_CONTEXT_KEY), null))
        assertNull("no progress line", entryUi(started, artist).progress)
        val rows = listRows(started.order(artist), SPOTIFY_CONTEXT_KEY, emptySet(), started.onPhone[7].orEmpty())
        assertEquals("one row, the one playing", 1, rows.size)
        assertTrue(rows.single().playing)
        assertFalse(rows.single().offline)
        assertEquals("no duration to show", "", formatDuration(rows.single().item.durationMs))
    }

    @Test
    fun `search - entries and items, at most 12, nothing found`() {
        val a = entry(1).copy(name = "Abels tårn")
        val b = entry(2).copy(name = "Bilturen")
        val itemsOf = { e: me.vibb.music.model.Entry ->
            (1..20).map { PlayItem(e.id, "k$it", "${e.name} del $it", "https://x/$it", false, null) }
        }
        val hits = search("abel", listOf(a, b), itemsOf)
        assertEquals(SEARCH_MAX, hits.size)
        assertTrue(hits.first() is SearchHit.EntryHit)
        assertTrue(hits.drop(1).all { it is SearchHit.ItemHit && it.entry == a })
        assertEquals(listOf(a, b), search("", listOf(a, b), itemsOf).map { it.entry })
        assertTrue(search("zzz", listOf(a, b), itemsOf).isEmpty())
        assertEquals(1, search("  BILTUREN ", listOf(a, b)) { emptyList() }.size)
    }

    @Test
    fun `list rows - numbers, the playing one, heard ones and offline marks`() {
        val shown = listOf("a", "b", "c").map { PlayItem(1, it, it, "u", false, 60_000) }
        val rows = listRows(shown, "b", setOf("a", "b"), setOf("c"))
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertTrue(rows[0].heard)
        assertTrue(rows[1].playing)
        assertFalse("the playing one shows its state, not ✓", rows[1].heard)
        assertTrue(rows[2].offline)
    }

    @Test
    fun `durations`() {
        assertEquals("14:10", formatDuration(850_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("0:00", formatDuration(0))
        assertEquals("", formatDuration(null))
    }

    @Test
    fun `an entry's category and kind`() {
        val e = entry(1, category = 4)
        val lib = library(listOf(e), categories = listOf(me.vibb.music.model.Category(4, "Podkast", "mic", "teal", 40)))
        val ui = entryUi(snapshot(lib, items(1, listOf("x"))), e)
        assertEquals("Podkast", ui.category?.name)
        assertEquals(Kind.PODCAST, ui.kind)
        assertNull("not started: no progress line", ui.progress)
    }

    private val books = listOf("b1", "b2", "b3", "b4", "b5")
    private fun storytel() = entry(7, Source.STORYTEL, target = "storytel:series:26175", cache = -1)

    /** Books 1 and 3 of five on the phone. */
    private fun partial(bookmark: Pair<String, Long>?) = storytel().let { e ->
        snapshot(
            library(listOf(e)),
            items(e.id, books, gone = books.toSet()),
            downloads = listOf(done(e.id, "b1", url = "storytel:book:b1"), done(e.id, "b3", url = "storytel:book:b3")),
            bookmarks = bookmark?.let { mapOf(e.key to it) }.orEmpty(),
        )
    }

    @Test
    fun `a Storytel entry is an audiobook`() {
        assertEquals(Kind.AUDIOBOOK, kindOf(storytel()))
        assertEquals(Kind.PODCAST, kindOf(entry(1, Source.NRK)))
        assertEquals(Kind.OWN, kindOf(entry(2, Source.OWN)))
        assertEquals(Hint.Started(Kind.AUDIOBOOK, 3, 5), hint(storytel(), books, Bookmark("b3", 1)))
        assertEquals(Hint.NotStarted(Kind.AUDIOBOOK), hint(storytel(), books, null))
    }

    @Test
    fun `del N av M counts the whole series, not the books on the phone (M5)`() {
        val e = storytel()
        val snap = partial("b3" to 60_000L)
        assertEquals(listOf("b1", "b3"), snap.order(e).map { it.key })
        val ui = entryUi(snap, e)
        assertEquals("book 3 of 5 with books 1 and 3 downloaded reads del 3 av 5", Hint.Started(Kind.AUDIOBOOK, 3, 5), ui.hint)
        assertEquals((2 + 60_000f / 600_000) / 5, ui.progress!!, 0.001f)

        val heardOnARoom = entryUi(partial("b4" to 1_000L), e)
        assertEquals("a bookmark on a book not on the phone still counts as started", Hint.Started(Kind.AUDIOBOOK, 4, 5), heardOnARoom.hint)
        assertTrue(heardOnARoom.progress != null)

        val podcast = entry(1)
        val p = snapshot(library(listOf(podcast)), items(1, listOf("x", "y", "z"), gone = setOf("x")), bookmarks = mapOf(podcast.key to ("y" to 10L)))
        assertEquals("a podcast still counts what is visible", Hint.Started(Kind.PODCAST, 2, 2), entryUi(p, podcast).hint)
    }

    @Test
    fun `del N av M is the book's place in the series, also when it plays newest first (L5)`() {
        val e = storytel().copy(order = "newest_first")
        val snap = snapshot(
            library(listOf(e)),
            items(e.id, books, gone = books.toSet()),
            downloads = books.map { done(e.id, it, url = "storytel:book:$it") },
            bookmarks = mapOf(e.key to ("b5" to 0L)),
        )
        assertEquals("b5", snap.order(e).first().key)
        assertEquals(Hint.Started(Kind.AUDIOBOOK, 5, 5), entryUi(snap, e).hint)
        val queue = snap.order(e)
        assertEquals(5 to 5, playerNumber(snap, NowView(e, null, queue, 0, live = false, playing = false, storedPosMs = 0, durationMs = null, shuffled = false)))
        assertEquals(1 to 5, playerNumber(snap, NowView(e, null, queue, 4, live = false, playing = false, storedPosMs = 0, durationMs = null, shuffled = false)))
    }

    @Test
    fun `the player's Del N av M counts the series too (M5)`() {
        val e = storytel()
        val snap = partial(null)
        val queue = snap.order(e)
        val now = NowView(e, null, queue, 1, live = false, playing = false, storedPosMs = 0, durationMs = null, shuffled = false)
        assertEquals("b3", now.item.key)
        assertEquals(3 to 5, playerNumber(snap, now))
        val podcast = entry(1)
        val p = snapshot(library(listOf(podcast)), items(1, listOf("x", "y", "z"), gone = setOf("x")))
        val pq = p.order(podcast)
        assertEquals(listOf("z", "y"), pq.map { it.key })
        assertEquals(2 to 2, playerNumber(p, NowView(podcast, null, pq, 1, live = false, playing = false, storedPosMs = 0, durationMs = null, shuffled = false)))
    }

    @Test
    fun `a new book lights NY and the bell once it is on the phone, and the tile still reads the series`() {
        val e = storytel()
        val news = listOf(NewsRow(e.id, "b5", 1))
        val waiting = snapshot(library(listOf(e)), items(e.id, books, gone = books.toSet()), downloads = listOf(done(e.id, "b1", url = "storytel:book:b1")), news = news)
        assertFalse("not downloaded yet: no NY", entryUi(waiting.forRenderer(false), e).isNew)
        assertEquals(0, NewNotifier.count(waiting.forRenderer(false)))
        val downloaded = snapshot(
            library(listOf(e)),
            items(e.id, books, gone = books.toSet()),
            downloads = listOf(done(e.id, "b1", url = "storytel:book:b1"), done(e.id, "b5", url = "storytel:book:b5")),
            news = news,
            bookmarks = mapOf(e.key to ("b5" to 1L)),
        ).forRenderer(false)
        val ui = entryUi(downloaded, e)
        assertTrue(ui.isNew)
        assertEquals(1, NewNotifier.count(downloaded))
        assertEquals("del 5 av 5 with two books on the phone", Hint.Started(Kind.AUDIOBOOK, 5, 5), ui.hint)
    }
}
