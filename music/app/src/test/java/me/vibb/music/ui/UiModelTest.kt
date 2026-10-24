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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(Hint.Started(Kind.MUSIC, 2, 3), entryUi(snap, list).hint)
        // the loaded entry's line (PlayerHub.State.entryId + rendererState)
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, entryUi(snap, list, 6, RendererState.SPOTIFY_NEEDS_INTERNET, null).state)
        assertNull("not the other Spotify tile's", entryUi(snap, other, 6, RendererState.SPOTIFY_NEEDS_INTERNET, null).state)
        assertNull("never another source's", entryUi(snap, feed, 2, RendererState.SPOTIFY_NEEDS_INTERNET, null).state)
        assertNull("never a room's line", entryUi(snap, list, 6, RendererState.UNREACHABLE, null).state)
        // the stored outcome: every Spotify tile, until a play succeeds
        for (word in listOf(SpotifyWords.REAUTHORIZE, SpotifyWords.REAUTHORIZE, SpotifyWords.NOT_PREMIUM, SpotifyWords.NO_WIDEVINE)) {
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
        assertNull(spotifyTileState(feed, 2, RendererState.SPOTIFY_ASK_ADULT, SpotifyWords.REAUTHORIZE))
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
        assertEquals("design 49 §1.1: Spotify music shuffles (the user's override of 26c)", true, sw.view.canShuffle)
        assertEquals(true, sw.view.canRepeat)
        assertNull("a speaker can't play it: the service says Kan ikke spilles på Sonos", playSwitch(snap, 6, null, null, 1_000, onRoom = true))
    }

    @Test
    fun `a context entry - one item, no number, no progress, Spotify's steps (26c §5_9, step 6)`() {
        val artist = entry(7, Source.SPOTIFY, target = "spotify:artist:FAKEartist000000000001", cache = 0).copy(spotifyMode = "context")
        val live = me.vibb.music.logic.SpotifyContextTrack("spotify:track:FAKEtrack0000000000004", "Fake track 4 — Fake Artist")
        val s = snapshot(
            library(listOf(list, artist)),
            rows(6, listOf("t1", "t2", "t3")) + rows(7, listOf("stale1")), // a last good list in Room is never shown
            bookmarks = mapOf(artist.key to (me.vibb.music.logic.SPOTIFY_CONTEXT_KEY to 40_000L)),
            spotifyContexts = mapOf(artist.key to live),
        )
        val now = nowView(s, PlayerHub.State(live = true, entryId = 7, keys = listOf("context"), index = 0, playing = true), null)!!
        assertEquals("the live track's title", "Fake track 4 — Fake Artist", now.itemTitle)
        // countedKeys is the one synthetic item: playerNumber says 1 of 1, and the player shows no number at all
        assertEquals(1 to 1, playerNumber(s, now))
        assertEquals(1, playerNumberOf(s, now, now.item))
        assertEquals(listOf("context"), listOrder(s, now).map { it.key })
        assertEquals(false, showsNumber(now))
        assertEquals("a list entry's number shows", true, showsNumber(nowView(s, PlayerHub.State(live = true, entryId = 6, keys = listOf("t1", "t2", "t3"), index = 0), null)!!))
        // next and previous are the web player's: no instant switch (only Spotify knows where they go)
        assertNull(skipSwitch(s, now, forward = true, posMs = 10_000, atMs = 1_000))
        assertNull(skipSwitch(s, now, forward = false, posMs = 1_000, atMs = 1_000))
        // the tile: "Musikk · rekkefølgen velges for deg", no progress line, the stored account line still wins
        val tile = entryUi(s, artist)
        assertEquals(Hint.Picks(Kind.MUSIC), tile.hint)
        assertNull(tile.progress)
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, entryUi(s, artist, null, null, SpotifyWords.REAUTHORIZE).state)
        // a cover tap shows the context at once - an artist at 0: its play starts where Spotify starts it (§12, L2)
        val sw = playSwitch(s, 7, null, null, 1_000)!!
        assertEquals("Fake track 4 — Fake Artist", sw.view.itemTitle)
        assertEquals(0L, sw.view.storedPosMs)
        assertNull("a room can't play it", playSwitch(s, 7, null, null, 1_000, onRoom = true))
    }

    @Test
    fun `L2 - an artist context shows 0 everywhere (it starts where Spotify starts it), a playlist context its place`() {
        val artist = entry(7, Source.SPOTIFY, target = "spotify:artist:FAKEartist000000000001", cache = 0).copy(spotifyMode = "context")
        val playlist = entry(9, Source.SPOTIFY, target = "spotify:playlist:FAKEplaylist0000000001", cache = 0).copy(spotifyMode = "context")
        val key = me.vibb.music.logic.SPOTIFY_CONTEXT_KEY
        val s = snapshot(library(listOf(artist, playlist)), bookmarks = mapOf(artist.key to (key to 40_000L), playlist.key to (key to 40_000L)))
        assertEquals(false, me.vibb.music.logic.contextResumes(artist))
        assertEquals(true, me.vibb.music.logic.contextResumes(playlist))
        assertEquals("every other entry resumes as before", true, me.vibb.music.logic.contextResumes(list))
        assertEquals(0L, playSwitch(s, 7, null, null, 1_000)!!.view.storedPosMs)
        assertEquals(40_000L, playSwitch(s, 9, null, null, 1_000)!!.view.storedPosMs)
        // the restored bar (the stored queue's row)
        val row = { id: Long -> me.vibb.music.data.NowPlayingRow(0, id, key, 0, 40_000, null, false, 0) }
        assertEquals(0L, nowView(s, PlayerHub.State(), row(7))!!.storedPosMs)
        assertEquals(40_000L, nowView(s, PlayerHub.State(), row(9))!!.storedPosMs)
        assertEquals("a pick of its one row as well", 0L, playSwitch(s, 7, key, null, 1_000)!!.view.storedPosMs)
    }

    // design 50 §1.4 and design 46 §1.4: dimmed tiles, the play arrow on every tile

    private val wifi = me.vibb.music.logic.NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
    private val cell = wifi.copy(notMetered = false)
    private val offline: me.vibb.music.logic.NetCaps? = null
    private fun net(caps: me.vibb.music.logic.NetCaps?, stream: Boolean = false, room: Boolean = false) = NetNow(caps, stream, room)

    @Test
    fun `d50 - unplayableNow per source under offline, cell and Wi-Fi - nothing on a room`() {
        val nrk = entry(3, Source.NRK)
        val own = entry(4, Source.OWN)
        val s = snapshot(
            library(listOf(list, feed, nrk, own), files = listOf(me.vibb.music.Fixtures.file(40, 4))),
            rows(6, listOf("t1")) + items(2, listOf("e1", "e2")) + items(3, listOf("n1")),
            downloads = listOf(me.vibb.music.Fixtures.done(3, "n1")),
            ownCopies = setOf(40L),
        )
        for (e in listOf(list, feed)) {
            assertTrue("${e.source} offline", entryUi(s, e, net = net(offline)).unplayableNow)
            assertTrue("${e.source} on cell, streaming off", entryUi(s, e, net = net(cell)).unplayableNow)
            assertFalse("${e.source} on cell, streaming on", entryUi(s, e, net = net(cell, stream = true)).unplayableNow)
            assertFalse("${e.source} on Wi-Fi", entryUi(s, e, net = net(wifi)).unplayableNow)
            assertFalse("${e.source} on a room: the phone's gate isn't the rule", entryUi(s, e, net = net(offline, room = true)).unplayableNow)
        }
        assertFalse("a copy on the phone plays offline", entryUi(s, nrk, net = net(offline)).unplayableNow)
        assertFalse("own files show only with a copy", entryUi(s, own, net = net(offline)).unplayableNow)
        assertFalse("no network read: nothing dimmed", entryUi(s, feed).unplayableNow)
    }

    @Test
    fun `d50 - one line - Trenger Wi-Fi in the hint's place, the account line first, ASK_ADULT dims too`() {
        val offlineFeed = entryUi(snap, feed, net = net(offline))
        assertTrue(offlineFeed.needsWifi)
        val askOffline = entryUi(snap, list, null, null, SpotifyWords.REAUTHORIZE, net(offline))
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, askOffline.state)
        assertFalse("the account line wins", askOffline.needsWifi)
        assertTrue(askOffline.unplayableNow)
        val askOnWifi = entryUi(snap, list, null, null, SpotifyWords.REAUTHORIZE, net(wifi))
        assertTrue("Spør en voksen: the kid can't play it either", askOnWifi.unplayableNow)
        assertFalse(askOnWifi.needsWifi)
        assertFalse("never on a room", entryUi(snap, list, null, null, SpotifyWords.REAUTHORIZE, net(wifi, room = true)).unplayableNow)
        val library = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/ui/LibraryScreen.kt").readText()
        assertTrue("one line: the state, else Trenger Wi-Fi, else the hint", library.contains("?: if (e.needsWifi) stringResource(R.string.tile_needs_wifi) else hintText(e.hint)"))
        assertTrue("the cover at 60 % times the neighbour's alpha", library.contains("alpha = lerp(1f, 0.45f, f) * (if (e.unplayableNow) UNPLAYABLE_ALPHA else 1f)"))
        assertEquals(0.6f, UNPLAYABLE_ALPHA, 0f)
    }

    // design r12 §3: the favourites queue across entries

    private val pod = entry(3, order = "oldest_first")
    private val songs = spotify(9)

    /** A podcast (e2 half heard) and a playlist; hearts e2 (newest), t1, e4 (oldest), in three runs: pod, list, pod. */
    private val mixed = snapshot(
        library(listOf(pod, songs)),
        items(3, listOf("e1", "e2", "e3", "e4")) + rows(9, listOf("t1", "t2")),
        positions = mapOf(pod.key to mapOf("e2" to me.vibb.music.logic.ItemPosition(90_000, false), "e4" to me.vibb.music.logic.ItemPosition(40_000, false))),
        bookmarks = mapOf(pod.key to ("e3" to 30_000L)),
    ).copy(
        favourites = mapOf(pod.key to setOf("e2", "e4"), songs.key to setOf("t1")),
        heartedAt = mapOf(pod.key to mapOf("e2" to 30L, "e4" to 10L), songs.key to mapOf("t1" to 20L)),
    )
    private val refs = listOf(
        me.vibb.music.logic.MixRef(pod.key, "e2"), me.vibb.music.logic.MixRef(songs.key, "t1"), me.vibb.music.logic.MixRef(pod.key, "e4"),
    )

    @Test
    fun `r12 - a live and a stored favourites queue - each page its own entry, N av M over the queue, the playing item's slots`() {
        assertEquals(refs, mixed.favouritesOrder())
        val hub = PlayerHub.State(live = true, entryId = 9, keys = listOf("t1"), index = 0, favouritesQueue = refs, favouritesIndex = 1, favouritesShuffled = true)
        val live = nowView(mixed, hub, null)!!
        assertTrue(live.favourites)
        assertEquals(listOf("e2", "t1", "e4"), live.queue.map { it.key })
        assertEquals("the header follows the playing item", songs, live.entry)
        assertEquals(pod, live.entryOf(live.queue[0]))
        assertEquals("Favoritt 2 av 3", 2 to 3, playerNumber(mixed, live))
        assertEquals(3, playerNumberOf(mixed, live, live.queue[2]))
        assertEquals("a song: shuffle and repeat, acting on the queue", me.vibb.music.logic.ControlSlots.SHUFFLE_REPEAT, live.slots)
        assertTrue("the queue's shuffle", live.shuffled)
        assertEquals("the list shows the queue as it plays", live.queue, listOrder(mixed, live))
        assertNull("no order button", listOrderUi(mixed, live, 3))
        // stored (after a restart): the row is the run's - e4 of the podcast, its place - and the mix around it
        val row = me.vibb.music.data.NowPlayingRow(0, 3, "e4", 0, 41_000, null, false, 0, favourites = "e4", mix = me.vibb.music.logic.encodeMix(refs))
        val stored = nowView(mixed, PlayerHub.State(), row)!!
        // qa-r12-music-code Q-8: only a live favourites queue is the same tile; a stored one is a new tap's queue
        assertTrue(favouritesSwitch(mixed, stored, 1_000) != null)
        // Q-7: the row's item not in this view (here: dropped from the listing) - the bar shows the next favourite at its
        // own place, as RESUME plays it; a row whose entry left the library goes on after its own ref, never from the top
        val dropped = nowView(mixed, PlayerHub.State(), row.copy(keys = "gone", favourites = "gone", mix = me.vibb.music.logic.encodeMix(refs.take(2) + me.vibb.music.logic.MixRef(pod.key, "gone") + refs.drop(2))))!!
        assertEquals("e4" to 40_000L, dropped.item.key to dropped.storedPosMs)
        val left = mixed.copy(library = library(listOf(songs)))
        val goneRow = me.vibb.music.data.NowPlayingRow(0, 3, "e2", 0, 5_000, null, false, 0, mix = me.vibb.music.logic.encodeMix(refs))
        assertEquals("after the gone e2: t1", "t1", nowView(left, PlayerHub.State(), goneRow)!!.item.key)
        assertEquals(pod, stored.entry)
        assertEquals(3 to 3, playerNumber(mixed, stored))
        assertEquals(41_000L, stored.storedPosMs)
        assertEquals("an episode: the skip pair", me.vibb.music.logic.ControlSlots.SKIP, stored.slots)
        assertFalse("an entry's row without mix stays an entry's", nowView(mixed, PlayerHub.State(), row.copy(mix = null))!!.favourites)
        // the list's rows: each by its own entry - heard, hearted, the playing one
        val rowsOf = favouritesRows(mixed, live)
        assertEquals(listOf(false, true, false), rowsOf.map { it.playing })
        assertEquals(listOf(true, true, true), rowsOf.map { it.favourite })
    }

    @Test
    fun `r12 - the tile's tap shows the first ref at its own place - a step across a run shows the next entry's item at its own place`() {
        val tap = favouritesSwitch(mixed, null, 1_000)!!
        assertEquals("e2", tap.view.item.key)
        assertEquals("its own place, not the entry's bookmark", 90_000L, tap.view.storedPosMs)
        assertEquals(me.vibb.music.logic.PlayRequest(3, "e2", null, 1_000), tap.request)
        assertNull("shuffled: the item isn't known", favouritesSwitch(mixed.copy(shuffle = setOf(me.vibb.music.logic.MIX_KEY)), null, 1_000)!!.request.itemKey)
        val live = nowView(mixed, PlayerHub.State(live = true, entryId = 3, keys = listOf("e2"), index = 0, favouritesQueue = refs, favouritesIndex = 0), null)!!
        assertNull("the loaded favourites queue: the same tile unpauses", favouritesSwitch(mixed, live, 1_000))
        val next = skipSwitch(mixed, live, forward = true, posMs = 10_000, atMs = 2_000)!!
        assertEquals(songs, next.view.entry)
        assertEquals("t1", next.view.item.key)
        assertEquals(me.vibb.music.logic.PlayRequest(9, "t1", live.ref, 2_000), next.request)
        val after = skipSwitch(mixed, next.view, forward = true, posMs = 0, atMs = 3_000)!!
        assertEquals("e4 at its own place", pod to 40_000L, after.view.entry to after.view.storedPosMs)
        // a pick in the favourites list: inside the queue, at its own place
        val pick = favouritePickSwitch(mixed, live, live.queue[2], 4_000)!!
        assertEquals(2 to 40_000L, pick.view.index to pick.view.storedPosMs)
        // the tile: every listed heart counted; dimmed only when nothing the phone holds can play now, never on a room
        assertEquals(FavouritesUi(3, dimmed = false, needsWifi = false), favouritesUi(mixed, refs, net(wifi)))
        assertEquals(FavouritesUi(3, dimmed = true, needsWifi = true), favouritesUi(mixed, refs, net(offline)))
        assertEquals(FavouritesUi(3, dimmed = false, needsWifi = false), favouritesUi(mixed.forRenderer(true), refs, net(offline, room = true)))
        assertNull("no heart listed: no chip, no tile", favouritesUi(mixed, emptyList(), net(wifi)))
    }

    @Test
    fun `d46 - the play arrow on every tile, never on a dimmed one`() {
        val fresh = entryUi(snap, other)
        assertNull("not started: no progress line", fresh.progress)
        assertTrue("but the arrow: tap to play", showsPlayMark(fresh))
        assertTrue(showsPlayMark(entryUi(snap, list)))
        assertFalse(showsPlayMark(entryUi(snap, feed, net = net(offline))))
        val library = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/ui/LibraryScreen.kt").readText()
        assertTrue(library.contains("if (showsPlayMark(e)) {"))
        assertFalse("its own condition, not the progress", library.substringAfter("private fun CoverBadges(").contains("if (e.progress != null)"))
    }
}
