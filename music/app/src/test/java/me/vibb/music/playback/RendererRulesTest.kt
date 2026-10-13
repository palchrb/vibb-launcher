package me.vibb.music.playback

import me.vibb.music.Fixtures.entry
import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.SPOTIFY_CONTEXT_KEY
import me.vibb.music.model.Entry
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's renderer rules for Spotify (design 26c §5.10, step 5b), the pure half of §5.10's service
 * tests: which renderer plays an entry, a Spotify item's playability (the network, never GONE), a start or a
 * step never moving to another Spotify item, the MediaItem's uri, the call rule's "paused", the natural
 * advance sending nothing, and `unplayable` recorded. GuardsTest pins the service's sites that ask them.
 */
class RendererRulesTest {
    private fun spotify(kind: String = "playlist", mode: String = "list"): Entry =
        entry(6, Source.SPOTIFY, target = "spotify:$kind:FAKEid6", cache = 0).copy(spotifyMode = mode)

    private val wifi = NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
    private val mobile = NetCaps(internet = true, notMetered = false, notRoaming = true, physical = true)
    private val roaming = NetCaps(internet = true, notMetered = false, notRoaming = false, physical = true)

    @Test
    fun `a Spotify entry plays on Spotify's web player, every other on the phone's own player`() {
        assertEquals(RendererKind.SPOTIFY, phoneRendererFor(spotify()))
        assertEquals(RendererKind.SPOTIFY, phoneRendererFor(spotify("show")))
        for (source in listOf(Source.NRK, Source.RSS, Source.OWN, Source.STORYTEL)) assertEquals(source.name, RendererKind.LOCAL, phoneRendererFor(entry(1, source)))
        assertTrue("both are the phone's", phoneRendererFor(spotify()).onPhone && phoneRendererFor(entry(1)).onPhone)
    }

    @Test
    fun `a Spotify item plays by the network gate alone - no copy, no url, never GONE (QA H1)`() {
        val s = spotify()
        for (kind in listOf(RendererKind.LOCAL, RendererKind.SPOTIFY)) {
            assertTrue(itemPlayable(kind, s, hasCopy = false, hasUrl = false, wifi, streamMobileData = false))
            assertFalse("streaming on mobile data not allowed", itemPlayable(kind, s, false, false, mobile, streamMobileData = false))
            assertTrue("the parent's streaming switch", itemPlayable(kind, s, false, false, mobile, streamMobileData = true))
            assertFalse("never roaming", itemPlayable(kind, s, false, false, roaming, streamMobileData = true))
            assertFalse("offline", itemPlayable(kind, s, false, false, null, streamMobileData = true))
        }
        // the phone's other items: unchanged
        assertFalse("a url-less feed item without a copy is gone", itemPlayable(RendererKind.LOCAL, entry(2), false, false, wifi, false))
        assertTrue("a copy plays offline", itemPlayable(RendererKind.LOCAL, entry(2), true, true, null, false))
        assertFalse(itemPlayable(RendererKind.LOCAL, entry(2), false, true, null, false))
        assertTrue("a room fetches by itself", itemPlayable(RendererKind.SONOS, entry(2), false, true, null, false))
    }

    @Test
    fun `a start or a step on a Spotify entry is all or none - never another item`() {
        val s = spotify()
        assertEquals(3, playableIndex(s, 5, 3, forward = true, wrap = true) { true })
        assertNull("the network refused: no index, never the next", playableIndex(s, 5, 3, forward = true, wrap = true) { false })
        assertNull("out of the queue", playableIndex(s, 5, 5, forward = true, wrap = false) { true })
        var asked = 0
        assertNull(playableIndex(s, 5, 0, forward = true, wrap = true) { asked++; it != 0 })
        assertEquals("only the index itself is asked", 1, asked)
        // every other entry: the first playable on, as before
        assertEquals(4, playableIndex(entry(2), 5, 3, forward = true, wrap = true) { it == 4 })
        assertEquals(0, playableIndex(entry(2), 5, 3, forward = true, wrap = true) { it == 0 })
    }

    @Test
    fun `a Spotify MediaItem's uri - a track, an episode, a context's target - and nothing for a bad target`() {
        assertEquals("spotify:track:k1", spotifyItemUri(spotify("playlist"), "k1"))
        assertEquals("spotify:track:k1", spotifyItemUri(spotify("album"), "k1"))
        assertEquals("spotify:episode:k1", spotifyItemUri(spotify("show"), "k1"))
        assertEquals("spotify:episode:k1", spotifyItemUri(spotify("episode"), "k1"))
        assertEquals("step 6's context item", "spotify:artist:FAKEid6", spotifyItemUri(spotify("artist", "context"), SPOTIFY_CONTEXT_KEY))
        assertEquals("the key is a track's in list mode", "spotify:track:context", spotifyItemUri(spotify("playlist"), SPOTIFY_CONTEXT_KEY))
        assertNull(spotifyItemUri(spotify().copy(target = "https://open.spotify.com/playlist/x"), "k1"))
    }

    @Test
    fun `the call rule counts a paused web player with its line as paused, a room's lines not (26c §5_8)`() {
        assertTrue(pausedForCall(showsPlaying = false, itemCount = 3, state = null))
        for (st in RendererState.entries.filter { !it.remote }) assertTrue(st.name, pausedForCall(false, 3, st))
        for (st in RendererState.entries.filter { it.remote }) assertFalse(st.name, pausedForCall(false, 3, st))
        assertFalse("playing", pausedForCall(true, 3, null))
        assertFalse("nothing loaded", pausedForCall(false, 0, RendererState.SPOTIFY_ELSEWHERE))
    }

    @Test
    fun `a natural advance resumes a heard item on the phone's own player only - Spotify sends nothing (QA M4)`() {
        assertTrue(resumesOnAdvance(auto = true, RendererKind.LOCAL))
        assertFalse(resumesOnAdvance(auto = true, RendererKind.SPOTIFY))
        assertFalse(resumesOnAdvance(auto = true, RendererKind.SONOS))
        assertFalse("a step is not an advance", resumesOnAdvance(auto = false, RendererKind.LOCAL))
    }

    @Test
    fun `an unplayable Spotify track goes into item_errors, like a listed url's stream`() {
        assertTrue("no url, still recorded", recordsItemError(spotify(), hasUrl = false))
        assertTrue(recordsItemError(entry(2), hasUrl = true))
        assertFalse("a withdrawn feed item without a copy", recordsItemError(entry(2), hasUrl = false))
        assertFalse("a Storytel book (no url; its failures are the download's)", recordsItemError(entry(5, Source.STORYTEL), hasUrl = false))
    }
}
