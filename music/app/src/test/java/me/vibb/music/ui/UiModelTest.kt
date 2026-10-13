package me.vibb.music.ui

import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.ItemRow
import me.vibb.music.logic.Hint
import me.vibb.music.logic.Kind
import me.vibb.music.logic.SpotifyWords
import me.vibb.music.model.Entry
import me.vibb.music.model.Source
import me.vibb.music.playback.PlayerHub
import me.vibb.music.playback.RendererState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a Spotify entry shows (design 26c §4.3, §5.8, step 5b): the loaded tile's web player line, the stored
 * outcome's account line on every Spotify tile, the hint otherwise; "Spor N av M" over the visible list in
 * play order (`countedKeys`/`playerNumber` as for NRK/RSS); a cover tap's instant switch on the phone, none on a
 * room (a speaker can't play it).
 */
class UiModelTest {
    private fun spotify(id: Long, kind: String = "playlist", order: String = "auto"): Entry =
        entry(id, Source.SPOTIFY, target = "spotify:$kind:FAKEid$id", order = order, cache = 0).copy(spotifyMode = "list")

    private fun rows(entryId: Long, keys: List<String>) =
        keys.mapIndexed { i, k -> ItemRow(entryId, k, i, "Song $k — Artist", null, false, 180_000, null) }

    private val list = spotify(6)
    private val other = spotify(8)
    private val feed = entry(2)
    private val snap = snapshot(
        library(listOf(list, other, feed)),
        rows(6, listOf("t1", "t2", "t3")) + rows(8, listOf("u1")) + items(2, listOf("e1")),
        bookmarks = mapOf(list.key to ("t2" to 30_000L)),
    )

    @Test
    fun `the loaded tile shows its web player's line, every Spotify tile the stored account line, else the hint`() {
        // nothing wrong: the hint
        assertNull(entryUi(snap, list).state)
        assertEquals(Hint.Started(Kind.SPOTIFY, 2, 3), entryUi(snap, list).hint)
        // the loaded entry's line (PlayerHub.State.entryId + rendererState)
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, entryUi(snap, list, 6, RendererState.SPOTIFY_NEEDS_INTERNET, null).state)
        assertNull("not the other Spotify tile's", entryUi(snap, other, 6, RendererState.SPOTIFY_NEEDS_INTERNET, null).state)
        assertNull("never another source's", entryUi(snap, feed, 2, RendererState.SPOTIFY_NEEDS_INTERNET, null).state)
        assertNull("never a room's line", entryUi(snap, list, 6, RendererState.UNREACHABLE, null).state)
        // the stored outcome: every Spotify tile, until a play succeeds
        for (word in listOf(SpotifyWords.REAUTHORIZE, SpotifyWords.REFUSED, SpotifyWords.NOT_PREMIUM, SpotifyWords.NO_WIDEVINE)) {
            assertEquals(word, RendererState.SPOTIFY_ASK_ADULT, entryUi(snap, list, null, null, word).state)
            assertEquals(word, RendererState.SPOTIFY_ASK_ADULT, entryUi(snap, other, 6, null, word).state)
            assertNull(word, entryUi(snap, feed, null, null, word).state)
        }
        assertEquals(RendererState.SPOTIFY_UNAVAILABLE, entryUi(snap, other, null, null, SpotifyWords.QUOTA).state)
        for (word in listOf(null, SpotifyWords.NONE, SpotifyWords.OK, SpotifyWords.UNREACHABLE)) assertNull("$word", entryUi(snap, other, null, null, word).state)
        // the loaded one's own line wins over the stored one
        assertEquals(RendererState.SPOTIFY_ELSEWHERE, entryUi(snap, list, 6, RendererState.SPOTIFY_ELSEWHERE, SpotifyWords.REAUTHORIZE).state)
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, entryUi(snap, list, 6, null, SpotifyWords.REAUTHORIZE).state)
    }

    @Test
    fun `the tile's state is spotifyTileState - pure, by the entry`() {
        assertNull(spotifyTileState(feed, 2, RendererState.SPOTIFY_ASK_ADULT, SpotifyWords.REFUSED))
        assertEquals(RendererState.SPOTIFY_STOPPED, spotifyTileState(list, 6, RendererState.SPOTIFY_STOPPED, null))
        assertEquals(RendererState.SPOTIFY_NOT_ON_PHONE, spotifyTileState(list, 6, RendererState.SPOTIFY_NOT_ON_PHONE, SpotifyWords.NO_WIDEVINE))
    }

    @Test
    fun `Spor N av M counts the visible list in play order, as for NRK and RSS - the order setting too`() {
        val now = nowView(snap, PlayerHub.State(live = true, entryId = 6, keys = listOf("t1", "t2", "t3"), index = 1), null)!!
        assertEquals(2 to 3, playerNumber(snap, now))
        assertEquals(3, playerNumberOf(snap, now, now.queue[2]))
        val newest = spotify(6, order = "newest_first")
        val s2 = snapshot(library(listOf(newest)), rows(6, listOf("t1", "t2", "t3")))
        val n2 = nowView(s2, PlayerHub.State(live = true, entryId = 6, keys = listOf("t3", "t2", "t1"), index = 0), null)!!
        assertEquals("the last-added song first is number 1", 1 to 3, playerNumber(s2, n2))
        assertEquals(listOf("t3", "t2", "t1"), listOrder(s2, n2).map { it.key })
    }

    @Test
    fun `a cover tap switches at once on the phone - its bookmarked track - and never on a room`() {
        val sw = playSwitch(snap, 6, null, null, 1_000)!!
        assertEquals("t2", sw.view.item.key)
        assertEquals(30_000L, sw.view.storedPosMs)
        assertEquals("no shuffle for Spotify (resume on)", false, sw.view.canShuffle)
        assertNull("a speaker can't play it: the service says Kan ikke spilles på Sonos", playSwitch(snap, 6, null, null, 1_000, onRoom = true))
    }
}
