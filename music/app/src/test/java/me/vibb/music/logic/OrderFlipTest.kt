package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.flippedNowPlaying
import me.vibb.music.model.Source
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sync.artWant
import me.vibb.music.sync.downloadPlan
import me.vibb.music.ui.listOrderUi
import me.vibb.music.ui.nowView
import me.vibb.music.ui.playerNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kid's order flip (design 39 §3): the shown direction and its reset, the one seam (`Snapshot.order`) and what it
 * leaves alone (the keep set, the downloads, the art's keep sets), the words per kind, the stored queue a flip
 * rewrites (qa-39-design M5), the sync's prune of stale rows, and the numbers.
 */
class OrderFlipTest {
    private val keys = (1..5).map { "k$it" } // oldest first; an RSS feed plays newest first

    @Test
    fun `the shown direction - no row the parent's, a fresh flip the opposite, a stale one the parent's`() {
        assertTrue("no row: the parent's (newest first)", shownNewestFirst(true, null))
        assertFalse("no row: the parent's (oldest first)", shownNewestFirst(false, null))
        assertFalse("flipped at newest first: oldest first", shownNewestFirst(true, true))
        assertTrue("flipped at oldest first: newest first", shownNewestFirst(false, false))
        // the parent turned the entry the other way since the tap: the row is stale, the parent's direction shows -
        // the kid had flipped to it, so the list doesn't move; only the button goes unfilled
        assertFalse("stale: the parent's new oldest first", shownNewestFirst(false, true))
        assertTrue("stale: the parent's new newest first", shownNewestFirst(true, false))
        assertTrue(flipInEffect(true, true))
        assertFalse(flipInEffect(false, true))
        assertFalse(flipInEffect(true, null))
    }

    @Test
    fun `a change that keeps the effective direction keeps the flip - auto to the same explicit order`() {
        val auto = entry(1) // RSS auto: newest first
        val explicit = auto.copy(order = "newest_first")
        val opposite = auto.copy(order = "oldest_first")
        val flips = mapOf(auto.key to true) // flipped while newest first
        assertTrue(snapshot(library(listOf(explicit)), items(1, keys), flips = flips).flipped(explicit))
        assertFalse(snapshot(library(listOf(opposite)), items(1, keys), flips = flips).flipped(opposite))
        assertEquals(setOf(auto.key), flipsToDelete(flips, listOf(opposite), allRead = true))
        assertEquals(emptySet<String>(), flipsToDelete(flips, listOf(explicit), allRead = true))
        // an auto NRK serie past 100 episodes turns newest first (`effective_order` on the server, a keep_end) - its
        // order setting stays `auto`, so the phone's rule reads it as the parent's: covered by the stale rows above
    }

    @Test
    fun `the sync prunes stale rows and a gone entry's - a gone one only when every entry was read`() {
        val e1 = entry(1)
        val e2 = entry(2, Source.OWN)
        val flips = mapOf(e1.key to true, e2.key to false, "gone" to true)
        assertEquals(setOf("gone"), flipsToDelete(flips, listOf(e1, e2), allRead = true))
        assertEquals("an unreadable entry might be it: kept", emptySet<String>(), flipsToDelete(flips, listOf(e1, e2), allRead = false))
        assertEquals(setOf(e1.key, "gone"), flipsToDelete(flips, listOf(e1.copy(order = "oldest_first"), e2), allRead = true))
    }

    @Test
    fun `Snapshot order flips, the keep set and the downloads do not`() {
        val e = entry(1, cache = 2)
        val lib = library(listOf(e))
        val plain = snapshot(lib, items(1, keys), bookmarks = mapOf(e.key to ("k3" to 1000L)))
        val flipped = snapshot(lib, items(1, keys), bookmarks = mapOf(e.key to ("k3" to 1000L)), flips = mapOf(e.key to true))
        assertEquals(keys.reversed(), plain.order(e).map { it.key })
        assertEquals(keys, flipped.order(e).map { it.key })
        assertTrue(flipped.flipped(e))
        assertFalse(plain.flipped(e))
        assertEquals("the keep set: the parent's (the bookmark and its next newest-first)", keepSet(e, keys, "k3"), keepSet(e, flipped.listed[1]!!.map { it.key }, "k3"))
        assertEquals(downloadPlan(plain, emptyList(), 0).wanted, downloadPlan(flipped, emptyList(), 0).wanted)
        val keepPlain = artWant(plain, emptyList(), -1, emptySet(), emptyList()).keep
        assertEquals(keepPlain, artWant(flipped, emptyList(), -1, emptySet(), emptyList()).keep)
    }

    @Test
    fun `the words per kind - the dated ones name the direction, the others compare with the listing`() {
        assertEquals(OrderWords.NEWEST_FIRST, orderWords(entry(1, Source.RSS), true))
        assertEquals(OrderWords.OLDEST_FIRST, orderWords(entry(1, Source.NRK, target = "https://radio.nrk.no/podkast/x"), false))
        assertEquals(OrderWords.OLDEST_FIRST, orderWords(entry(1, Source.NRK, target = "https://radio.nrk.no/serie/x"), false))
        val show = entry(7, Source.SPOTIFY, target = "spotify:show:FAKEid7", cache = 0).copy(spotifyMode = "list")
        assertEquals(OrderWords.NEWEST_FIRST, orderWords(show, true))
        assertEquals(OrderWords.FROM_BOOK_1, orderWords(entry(3, Source.STORYTEL, target = "storytel:series:1"), false))
        assertEquals(OrderWords.FROM_LAST_BOOK, orderWords(entry(3, Source.STORYTEL, target = "storytel:series:1"), true))
        assertEquals(OrderWords.IN_ORDER, orderWords(entry(4, Source.OWN), false))
        assertEquals(OrderWords.REVERSED, orderWords(entry(4, Source.OWN), true))
        val playlist = entry(8, Source.SPOTIFY, target = "spotify:playlist:FAKEid8", cache = 0).copy(spotifyMode = "list")
        assertEquals(OrderWords.REVERSED, orderWords(playlist, true))
        assertEquals(OrderWords.IN_ORDER, orderWords(playlist.copy(order = "newest_first"), false))
    }

    @Test
    fun `M5 - the stored queue flips with the row, so a restore with nothing live goes the shown way`() {
        val stored = listOf("k3", "k2", "k1", "k5", "k4") // newest first from k3
        val row = NowPlayingRow(0, 1, stored.joinToString("\n"), 0, 5_000, 600_000, shuffled = false, updatedAt = 1)
        val flipped = flippedNowPlaying(row, 1, keys, nowMs = 2)!!
        assertEquals("k3\nk4\nk5\nk1\nk2", flipped.keys)
        assertEquals(0, flipped.index)
        assertEquals("the place kept", 5_000L, flipped.posMs)
        val plan = storedPlan(flipped.keys.split('\n'), flipped.index, flipped.posMs, keys.toSet()) { 0 }!!
        assertEquals("restore: k3 at its place, then the shown direction", listOf("k3", "k4", "k5", "k1", "k2"), plan.keys)
        assertEquals(5_000L, plan.startPosMs)
        assertNull("a shuffled queue is left alone", flippedNowPlaying(row.copy(shuffled = true), 1, keys, 2))
        assertNull("another entry's is left alone", flippedNowPlaying(row, 2, keys, 2))
        assertNull("its current item left the listing: left alone (N2)", flippedNowPlaying(row, 1, keys - "k3", 2))
        val mid = row.copy(index = 2) // k1 current
        assertEquals("k1\nk2\nk3\nk4\nk5", flippedNowPlaying(mid, 1, keys, 2)!!.keys)
    }

    @Test
    fun `the numbers follow the shown order - flipped to oldest first, the oldest episode is 1`() {
        val e = entry(1)
        val flipped = snapshot(library(listOf(e)), items(1, keys), flips = mapOf(e.key to true))
        val now = nowView(flipped, PlayerHub.State(live = true, entryId = 1, keys = keys, index = 0), null)!!
        assertEquals(1 to 5, playerNumber(flipped, now))
        val order = listOrderUi(flipped, now, 5)!!
        assertTrue(order.flipped)
        assertEquals(OrderWords.OLDEST_FIRST, order.words)
        assertNull("below two rows: no button", listOrderUi(flipped, now, 1))
        assertNull("shuffled: no button", listOrderUi(flipped, now.copy(shuffled = true), 5))
    }

    @Test
    fun `qa-39-code L1 - the service flips the current queue's keys, and setFlipTx rewrites the stored queue`() {
        val service = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        val flip = service.substringAfter("private fun flipOrder(").substringBefore("\n    }\n")
        // a room's queue (the HLS item left out) stays that: the queue's own keys, never the order's
        assertTrue(flip.contains("flippedQueue(a.order.map { it.key }, a.items.map { it.key }, current.key)"))
        assertTrue(flip.contains("renderer.reorder(index, items.drop(1).map { mediaItem(a, it) })"))
        val db = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        val tx = db.substringAfter("suspend fun setFlipTx(").substringBefore("\n    }\n")
        assertTrue(tx.contains("val row = nowPlaying() ?: return"))
        assertTrue(tx.contains("flippedNowPlaying(row, entryId, newOrder, nowMs)?.let { upsertNowPlaying(it) }"))
        assertTrue(db.contains("@Transaction\n    suspend fun setFlipTx("))
    }

    @Test
    fun `Room 5 - order_flips is a new table by an auto migration, never backed up`() {
        val v5 = me.vibb.music.Fixtures.moduleFile("schemas/me.vibb.music.data.MusicDb/5.json").readText()
        assertTrue(v5.contains("\"version\": 5"))
        assertTrue(v5.contains("CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`entryKey` TEXT NOT NULL, `parentNewestFirst` INTEGER NOT NULL, PRIMARY KEY(`entryKey`))"))
        val db = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 4, to = 5),"))
        val manifest = me.vibb.music.Fixtures.moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `a flipped Storytel series keeps its reading order's Del N av M`() {
        val series = entry(3, Source.STORYTEL, target = "storytel:series:1")
        val books = items(3, listOf("b1", "b2", "b3"))
        val flipped = snapshot(library(listOf(series)), books, downloads = listOf("b1", "b2", "b3").map { me.vibb.music.Fixtures.done(3, it) }, flips = mapOf(series.key to false))
        assertEquals(listOf("b3", "b2", "b1"), flipped.order(series).map { it.key })
        val now = nowView(flipped, PlayerHub.State(live = true, entryId = 3, keys = listOf("b3", "b2", "b1"), index = 0), null)!!
        assertEquals("book 3 is Del 3 av 3", 3 to 3, playerNumber(flipped, now))
        assertEquals(OrderWords.FROM_LAST_BOOK, listOrderUi(flipped, now, 3)!!.words)
    }
}
