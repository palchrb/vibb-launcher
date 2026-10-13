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
import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.Playability
import me.vibb.music.logic.SPOTIFY_CONTEXT_KEY
import me.vibb.music.logic.SPOTIFY_START_BUDGET_MS
import me.vibb.music.logic.SPOTIFY_WINDOW_MAX
import me.vibb.music.logic.SpotifyLine
import me.vibb.music.logic.SpotifyOutcome
import me.vibb.music.logic.SpotifyProblem
import me.vibb.music.logic.SpotifyWords
import me.vibb.music.logic.contextItem
import me.vibb.music.logic.nextQueueAdd
import me.vibb.music.logic.playWindow
import me.vibb.music.logic.sdkErrorProblem
import me.vibb.music.logic.spotifyNetworkLine
import me.vibb.music.logic.spotifyPlayability
import me.vibb.music.logic.spotifyStartDeadline
import me.vibb.music.logic.spotifyTileLine
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

/**
 * Design 26c §4 as step 3 brings it - the target grammar, the library's rules, and Spotify hidden until
 * step 5b - and step 4's player rules (§5.4, §5.8-§5.10): the network gate, the context item, the play
 * window and the queue add, the start's budget, the lines and words, the stored outcome.
 */
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
    fun `step 5b flipped it - a listed Spotify entry shows by default, and with the switch off nothing shows`() {
        assertTrue("flipped in step 5b", SPOTIFY_PLAYABLE)
        val playlist = spotify(6, "playlist")
        val lib = library(listOf(playlist, entry(2)))
        val rows = rows(6, listOf("t1", "t2")) + Fixtures.items(2, listOf("e1"))
        val snap = snapshot(lib, items = rows)
        assertEquals("the default snapshot shows it", listOf("t1", "t2"), snap.visible(playlist).map { it.key })
        assertEquals(listOf(2L, 6L), snap.entries.map { it.id })
        // the switch the tests can still turn off (as before step 5b): nothing shows, listed all the same
        val off = snap.copy(spotifyPlayable = false)
        assertTrue(off.visible(playlist).isEmpty())
        assertTrue(off.forRenderer(true).visible(playlist).isEmpty())
        assertEquals("only the feed's tile", listOf(2L), off.entries.map { it.id })
        assertEquals("listed all the same", listOf("t1", "t2"), off.listed[6]!!.map { it.key })
    }

    @Test
    fun `a context-mode entry shows nothing in step 5b - its one synthetic item is step 6's (26c §5_9)`() {
        val artist = spotify(7, "artist", mode = SPOTIFY_MODE_CONTEXT)
        val playlist = spotify(6, "playlist", mode = SPOTIFY_MODE_CONTEXT)
        // a playlist that went to context mode may still carry its last good list: neither shows a tile until step 6
        val snap = snapshot(library(listOf(artist, playlist)), items = rows(6, listOf("t1", "t2")))
        assertTrue(snap.visible(playlist).isEmpty())
        assertTrue(snap.forRenderer(true).visible(playlist).isEmpty())
        assertTrue(snap.visible(artist).isEmpty())
        assertEquals(emptyList<Long>(), snap.entries.map { it.id })
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
        assertEquals("step 5b: the default", setOf("t1", "t2"), newsEligible(playlist, listing))
        assertEquals(emptySet<String>(), newsEligible(playlist, listing, spotifyPlayable = false))
        assertEquals(setOf("t1", "t2"), newsEligible(playlist, listing, spotifyPlayable = true))
        val items = listing.map { PlayItem(6, it.key, it.title, null, false, it.durationMs) }
        assertEquals(setOf("t1", "t2"), listedOf(playlist, items, emptySet(), emptySet(), emptyMap()).eligible)
        assertEquals(emptySet<String>(), listedOf(playlist, items, emptySet(), emptySet(), emptyMap(), spotifyPlayable = false).eligible)
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

    // --- step 4: the player's pure rules -------------------------------------------------------------

    private val wifi = NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
    private val mobile = NetCaps(internet = true, notMetered = false, notRoaming = true, physical = true)
    private val roaming = NetCaps(internet = true, notMetered = false, notRoaming = false, physical = true)
    private val noInternet = NetCaps(internet = false, notMetered = true, notRoaming = true, physical = true)

    @Test
    fun `spotifyPlayability is the stream gate - never GONE, never LOCAL (H1)`() {
        assertEquals(Playability.STREAM, spotifyPlayability(wifi, mobileData = false))
        assertEquals(Playability.STREAM, spotifyPlayability(mobile, mobileData = true))
        for ((caps, mobileData) in listOf(mobile to false, roaming to true, noInternet to true, null to true)) {
            assertEquals("$caps $mobileData", Playability.NEEDS_NETWORK, spotifyPlayability(caps, mobileData))
        }
        assertNull(spotifyNetworkLine(wifi, false))
        assertEquals("mobile data not allowed", SpotifyLine.NEEDS_WIFI, spotifyNetworkLine(mobile, false))
        assertEquals("roaming: Wi-Fi would do", SpotifyLine.NEEDS_WIFI, spotifyNetworkLine(roaming, true))
        assertEquals("offline", SpotifyLine.NEEDS_INTERNET, spotifyNetworkLine(noInternet, true))
        assertEquals(SpotifyLine.NEEDS_INTERNET, spotifyNetworkLine(null, true))
    }

    @Test
    fun `contextItem - one stable key, the entry's name, no url and no duration (H3)`() {
        val artist = spotify(7, "artist", mode = SPOTIFY_MODE_CONTEXT)
        val item = contextItem(artist)
        assertEquals(PlayItem(7, "context", "Entry 7", null, false, null), item)
        assertEquals(SPOTIFY_CONTEXT_KEY, contextItem(artist.copy(name = "Another name")).key)
        assertFalse("never a Spotify id's shape", Regex("[A-Za-z0-9]{22}").matches(SPOTIFY_CONTEXT_KEY))
    }

    @Test
    fun `the play window - at most 50 from the current index - and the queue add one track ahead`() {
        val uris = (1..60).map { "spotify:track:FAKEtrack%013d".format(it) }
        assertEquals(50, SPOTIFY_WINDOW_MAX)
        assertEquals(uris.subList(0, 50), playWindow(uris, 0))
        assertEquals(uris.subList(20, 60), playWindow(uris, 20))
        assertEquals(listOf(uris.last()), playWindow(uris, 59))
        assertEquals(emptyList<String>(), playWindow(uris, 60))
        assertEquals(emptyList<String>(), playWindow(uris, -1))
        assertEquals(uris.subList(3, 5), playWindow(uris, 3, max = 2))
        // window 0..49 of 60: nothing until the window's last item is current, then 50; then 51 once 50 plays
        assertNull(nextQueueAdd(current = 48, known = 49, size = 60))
        assertEquals(50, nextQueueAdd(current = 49, known = 49, size = 60))
        assertNull("50 added: Spotify knows it", nextQueueAdd(current = 49, known = 50, size = 60))
        assertEquals(51, nextQueueAdd(current = 50, known = 50, size = 60))
        assertNull("the queue's last item", nextQueueAdd(current = 59, known = 59, size = 60))
        assertNull("a list that fits one window", nextQueueAdd(current = 9, known = 9, size = 10))
    }

    @Test
    fun `the start's budget`() {
        assertEquals(25_000L, SPOTIFY_START_BUDGET_MS)
        assertEquals(1_025_000L, spotifyStartDeadline(1_000_000L))
    }

    @Test
    fun `every problem's line and word, as section 5_8's table says`() {
        val table = mapOf(
            SpotifyProblem.NO_WIFI to (SpotifyLine.NEEDS_WIFI to null),
            SpotifyProblem.UNREACHABLE to (SpotifyLine.NEEDS_INTERNET to "unreachable"),
            SpotifyProblem.REAUTHORIZE to (SpotifyLine.ASK_ADULT to "reauthorize"),
            SpotifyProblem.REFUSED to (SpotifyLine.ASK_ADULT to "refused"),
            SpotifyProblem.NOT_PREMIUM to (SpotifyLine.ASK_ADULT to "not_premium"),
            SpotifyProblem.QUOTA to (SpotifyLine.UNAVAILABLE to "quota"),
            SpotifyProblem.NO_WIDEVINE to (SpotifyLine.NOT_ON_PHONE to "no_widevine"),
            SpotifyProblem.ELSEWHERE to (SpotifyLine.ELSEWHERE to null),
            SpotifyProblem.STOPPED to (SpotifyLine.STOPPED to null),
            SpotifyProblem.OFF to (null to "none"),
        )
        assertEquals(SpotifyProblem.entries.toSet(), table.keys)
        for ((problem, want) in table) assertEquals(problem.name, want, problem.line to problem.outcome)
        for (p in SpotifyProblem.entries) p.outcome?.let { assertTrue(it, it in SpotifyWords.ALL) }
        assertEquals("the server's SPOTIFY_WORDS", listOf("none", "ok", "reauthorize", "refused", "not_premium", "no_widevine", "unreachable", "quota"), SpotifyWords.ALL)
        assertEquals(SpotifyProblem.NO_WIDEVINE, sdkErrorProblem("initialization"))
        assertEquals(SpotifyProblem.NOT_PREMIUM, sdkErrorProblem("account"))
        assertEquals(SpotifyProblem.UNREACHABLE, sdkErrorProblem("script"))
        for (k in listOf("authentication", "playback", "autoplay", "other")) assertNull(k, sdkErrorProblem(k))
    }

    @Test
    fun `the stored outcome shows on every tile until a play succeeds`() {
        for (w in listOf("reauthorize", "refused", "not_premium", "no_widevine")) assertEquals(w, SpotifyLine.ASK_ADULT, spotifyTileLine(w))
        assertEquals(SpotifyLine.UNAVAILABLE, spotifyTileLine("quota"))
        for (w in listOf("none", "ok", "unreachable", null, "junk")) assertNull("$w", spotifyTileLine(w))
    }

    @Test
    fun `SpotifyOutcome - the file round trip, written only when the word changes (L8)`() {
        val o = SpotifyOutcome("not_premium", 1_760_000_000_000)
        assertEquals("""{"outcome":"not_premium","at":1760000000000}""", o.encode())
        assertEquals(o, SpotifyOutcome.decode(o.encode()))
        for (junk in listOf(null, "", "{", "[]", """{"outcome":"ok"}""", """{"outcome":"ok","at":"1"}""", """{"outcome":"bearer","at":1}""",
            """{"outcome":7,"at":1}""")) {
            assertNull(junk, SpotifyOutcome.decode(junk))
        }
        assertEquals("unknown fields ignored", SpotifyOutcome("ok", 5), SpotifyOutcome.decode("""{"outcome":"ok","at":5,"x":1}"""))
        assertNull("nothing stored is none", SpotifyOutcome.next(null, "none", 10))
        assertEquals(SpotifyOutcome("ok", 10), SpotifyOutcome.next(null, "ok", 10))
        assertNull("unchanged: nothing to write", SpotifyOutcome.next(SpotifyOutcome("ok", 10), "ok", 20))
        assertEquals(SpotifyOutcome("quota", 20), SpotifyOutcome.next(SpotifyOutcome("ok", 10), "quota", 20))
        assertNull("an unknown word is never stored", SpotifyOutcome.next(SpotifyOutcome("ok", 10), "FAKE-ACCESS-1", 20))
        assertNull(SpotifyOutcome.next(SpotifyOutcome("ok", 10), null, 20))
        // the report's word from the file (step 5b): its word, else none
        assertEquals("not_premium", SpotifyOutcome.reported(o.encode()))
        for (none in listOf(null, "", "{", """{"outcome":"FAKE-ACCESS-1","at":1}""", """{"at":1}""")) assertEquals(none, "none", SpotifyOutcome.reported(none))
    }
}
