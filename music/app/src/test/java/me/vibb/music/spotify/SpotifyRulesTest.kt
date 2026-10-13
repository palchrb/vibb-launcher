package me.vibb.music.spotify

import me.vibb.music.Fixtures
import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.ItemRow
import me.vibb.music.logic.Hint
import me.vibb.music.logic.Kind
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.SPOTIFY_PLAYABLE
import me.vibb.music.logic.fetchable
import me.vibb.music.logic.hint
import me.vibb.music.logic.keepSet
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.listedOf
import me.vibb.music.logic.naturallyNewestFirst
import me.vibb.music.logic.newsEligible
import me.vibb.music.logic.wantedDownloads
import me.vibb.music.model.Entry
import me.vibb.music.model.ListingItem
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.sonos.PlayBodies
import me.vibb.music.sync.downloadPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 26c §4 as step 3 brings it: the target grammar, the library's rules, and Spotify hidden until step 5b. */
class SpotifyRulesTest {
    private fun spotify(id: Long, kind: String, mode: String = SPOTIFY_MODE_LIST, order: String = "auto"): Entry =
        entry(id, Source.SPOTIFY, target = "spotify:$kind:FAKEid$id", order = order, cache = 0)
            .copy(spotifyMode = mode)

    private fun rows(entryId: Long, keys: List<String>) =
        keys.mapIndexed { i, k -> ItemRow(entryId, k, i, "Song $k — Artist", null, false, 180_000, null) }

    @Test
    fun `the target grammar - canonical targets only`() {
        assertEquals(SpotifyRef(SpotifyKind.PLAYLIST, "FAKEplaylist0000000001"), spotifyTarget("spotify:playlist:FAKEplaylist0000000001"))
        for (kind in SpotifyKind.entries) {
            assertEquals(kind, spotifyTarget("spotify:${kind.wire}:abc123")?.kind)
            assertEquals("spotify:${kind.wire}:abc123", spotifyTarget("spotify:${kind.wire}:abc123")!!.target)
        }
        assertEquals("64 characters", 64, spotifyTarget("spotify:track:" + "a".repeat(64))!!.id.length)
        for (bad in listOf(
            null, "", "spotify:track:" + "a".repeat(65), "spotify:audiobook:abc", "spotify:user:x:collection", "spotify:track:a-b",
            "spotify:track:", "https://open.spotify.com/track/abc", "spotify:track:abc ", "SPOTIFY:track:abc", "spotify:Track:abc",
        )) {
            assertNull("$bad", spotifyTarget(bad))
        }
    }

    @Test
    fun `an item's uri - episodes for a show and an episode entry, tracks for every other kind`() {
        assertEquals("spotify:episode:k1", uriOf(SpotifyKind.SHOW, "k1"))
        assertEquals("qa-26c-step2a-code: an episode entry's one item is an episode", "spotify:episode:k1", uriOf(SpotifyKind.EPISODE, "k1"))
        for (kind in listOf(SpotifyKind.PLAYLIST, SpotifyKind.ALBUM, SpotifyKind.TRACK, SpotifyKind.ARTIST)) {
            assertEquals("spotify:track:k1", uriOf(kind, "k1"))
        }
        assertTrue(spotifyEpisodes(spotify(1, "show")))
        assertTrue(spotifyEpisodes(spotify(1, "episode")))
        assertFalse(spotifyEpisodes(spotify(1, "playlist")))
        assertFalse("not Spotify", spotifyEpisodes(entry(1, Source.RSS)))
    }

    @Test
    fun `until step 5b a listed Spotify entry shows nothing, on the phone or a room`() {
        assertFalse("flipped in step 5b", SPOTIFY_PLAYABLE)
        val playlist = spotify(6, "playlist")
        val snap = snapshot(library(listOf(playlist, entry(2))), items = rows(6, listOf("t1", "t2")) + Fixtures.items(2, listOf("e1")))
        assertTrue(snap.visible(playlist).isEmpty())
        assertTrue(snap.forRenderer(true).visible(playlist).isEmpty())
        assertEquals("only the feed's tile", listOf(2L), snap.entries.map { it.id })
        assertEquals("listed all the same", listOf("t1", "t2"), snap.listed[6]!!.map { it.key })
    }

    @Test
    fun `once playable a listed Spotify item shows in both views - it streams - and a room drops it from its queue (26c §4_2)`() {
        val playlist = spotify(6, "playlist")
        val snap = snapshot(library(listOf(playlist)), items = rows(6, listOf("t1", "t2"))).copy(spotifyPlayable = true)
        assertEquals(listOf("t1", "t2"), snap.visible(playlist).map { it.key })
        assertEquals(listOf("t1", "t2"), snap.forRenderer(true).visible(playlist).map { it.key })
        assertEquals(listOf(6L), snap.entries.map { it.id })
        assertEquals("spotify", PlayBodies.speakerDropReason(playlist, snap.visible(playlist)[0]))
        assertNull(PlayBodies.build(playlist, snap.visible(playlist)[0], "RINCON_000E58A0B1C201400", 0.0))
    }

    @Test
    fun `natural order - a show newest first, every other kind in the listing's order, the setting as for every entry`() {
        assertTrue(naturallyNewestFirst(spotify(1, "show")))
        for (kind in listOf("playlist", "album", "track", "artist", "episode")) assertFalse(kind, naturallyNewestFirst(spotify(1, kind)))
        val playlist = spotify(6, "playlist", order = "newest_first")
        val snap = snapshot(library(listOf(playlist)), items = rows(6, listOf("t1", "t2", "t3"))).copy(spotifyPlayable = true)
        assertEquals("the last-added song first", listOf("t3", "t2", "t1"), snap.order(playlist).map { it.key })
        assertEquals(listOf("t1", "t2", "t3"), snap.order(spotify(6, "playlist")).map { it.key })
    }

    @Test
    fun `never fetchable, never kept - nothing downloads, nothing is pruned for it`() {
        val playlist = spotify(6, "playlist")
        val item = PlayItem(6, "t1", "Song", null, false, 180_000)
        assertFalse(fetchable(playlist, item))
        assertFalse("even with a url", fetchable(playlist, item.copy(url = "https://cdn.example.org/x.mp3")))
        assertEquals(emptySet<String>(), keepSet(playlist, listOf("t1", "t2"), "t1"))
        assertEquals(emptyList<String>(), wantedDownloads(setOf("t1"), listOf(KeepItem("t1", false, fetchable(playlist, item))), emptySet(), "t1"))
        val snap = snapshot(library(listOf(playlist)), items = rows(6, listOf("t1")), downloads = listOf(done(6, "t1")))
        assertTrue("its items have no copy to count", snap.listed[6]!!.all { it.url == null })
        // qa-26c-step3-code L4: the plan skips it - no state row for the report's `downloads` (§4.4), nothing
        // wanted, and a copy that somehow exists goes.
        val feed = entry(2)
        val both = snapshot(library(listOf(playlist, feed)), items = rows(6, listOf("t1", "t2")) + Fixtures.items(2, listOf("e1")))
        val plan = downloadPlan(both, listOf(done(6, "t1")), 0)
        assertEquals("only the feed reports", listOf(2L), plan.states.map { it.entry })
        assertTrue(plan.wanted.none { it.entry.id == 6L })
        assertEquals(listOf(6L), plan.prune.map { it.entryId })
    }

    @Test
    fun `NY - a listed Spotify item is news once Spotify plays, only acknowledged before (26c §4_3)`() {
        val playlist = spotify(6, "playlist")
        val listing = listOf(ListingItem("t1", "Song — Artist", null, false, 180_000, null), ListingItem("t2", "Song 2 — Artist", null, false, 180_000, null))
        assertEquals(emptySet<String>(), newsEligible(playlist, listing))
        assertEquals(setOf("t1", "t2"), newsEligible(playlist, listing, spotifyPlayable = true))
        val items = listing.map { PlayItem(6, it.key, it.title, null, false, it.durationMs) }
        assertEquals(emptySet<String>(), listedOf(playlist, items, emptySet(), emptySet(), emptyMap()).eligible)
        assertEquals(setOf("t1", "t2"), listedOf(playlist, items, emptySet(), emptySet(), emptyMap(), spotifyPlayable = true).eligible)
        assertEquals("a withdrawn feed episode still never is", emptySet<String>(), newsEligible(entry(2), listOf(ListingItem("x", "X", null, false, null, null)), spotifyPlayable = true))
    }

    @Test
    fun `the words - Spotify is the kind, tracks or episodes by what the entry lists`() {
        assertEquals(Kind.SPOTIFY, kindOf(spotify(6, "playlist")))
        assertEquals(Hint.Started(Kind.SPOTIFY, 2, 3, episodes = false), hint(spotify(6, "playlist"), listOf("a", "b", "c"), me.vibb.music.logic.Bookmark("b", 1_000)))
        assertEquals(Hint.Started(Kind.SPOTIFY, 2, 3, episodes = true), hint(spotify(6, "show"), listOf("a", "b", "c"), me.vibb.music.logic.Bookmark("b", 1_000)))
        assertEquals(Hint.NotStarted(Kind.SPOTIFY), hint(spotify(6, "playlist"), listOf("a"), null))
        val strings = Fixtures.moduleFile("src/main/res/values/strings.xml").readText()
        val nb = Fixtures.moduleFile("src/main/res/values-nb/strings.xml").readText()
        assertTrue("the attribution, both locales", strings.contains("<string name=\"kind_spotify\">Spotify</string>") && nb.contains("<string name=\"kind_spotify\">Spotify</string>"))
    }
}
