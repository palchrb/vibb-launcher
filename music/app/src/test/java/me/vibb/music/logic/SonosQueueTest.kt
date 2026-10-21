package me.vibb.music.logic

import me.vibb.music.sonos.Soap
import me.vibb.music.sonos.SonosFakes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 54's pure rules of a share link on a room (`logic/SonosQueue.kt`): vibb's decode and prefix rule, the map of
 * one read of the queue, its trust by version (N5), ours' contradiction (N2), natural or pressed (N8), the queue's end
 * by the anchor (N1b), the at-end arming (54-8), the probe's match (`sonos_migrate_probe.py` 3) and the speaker's sleep
 * timer (N1).
 */
class SonosQueueTest {
    private val a = "spotify:track:1FakeTrackA00000000001"
    private val c = "spotify:track:1FakeTrackC00000000003"
    private val d = "spotify:track:1FakeTrackD00000000004"
    private val e = "spotify:track:1FakeTrackE00000000005"
    private val ep9 = "spotify:episode:5FakeEpisode0000000009"

    private fun read(vararg uris: String?, updateId: String? = "39") =
        QueueRead(uris.size, uris.withIndex().associate { (i, u) -> i + 1 to u }, updateId)

    @Test
    fun `d54 trackSpotifyUri - both prefixes, the part before the query decoded as Python's unquote, junk null`() {
        assertEquals(a, trackSpotifyUri("x-sonos-spotify:spotify%3atrack%3a1FakeTrackA00000000001?sid=9&flags=8232&sn=6"))
        assertEquals(c, trackSpotifyUri("x-sonosprog-spotify:spotify%3atrack%3a1FakeTrackC00000000003?sid=9&flags=8224&sn=1"))
        assertEquals("a + stays a +", "spotify:track:a+b", trackSpotifyUri("x-sonos-spotify:spotify%3atrack%3aa+b"))
        assertNull(trackSpotifyUri("x-sonos-vli:RINCON_X:1,spotify:abc"))
        assertNull(trackSpotifyUri("https://podkast.example/ep12.mp3"))
        assertNull(trackSpotifyUri("x-rincon-queue:RINCON_X#0"))
        assertNull(trackSpotifyUri(""))
        assertNull(trackSpotifyUri(null))
        assertNull("nothing after the prefix", trackSpotifyUri("x-sonos-spotify:?sid=9"))
        // a Browse item's res decodes the same
        val page = Soap.parseQueuePage(SonosFakes.reply("browse_queue_page.xml", "Browse"))
        assertEquals(listOf(a, ep9, c, a, d), page.res.map(::trackSpotifyUri))
    }

    @Test
    fun `d54 the slots - a gap, an extra, a duplicate matched in order, a reversed capped show, a relinked id`() {
        // the fake playlist's queue (browse_queue_page.xml): A, an episode our listing drops, C, A again, D
        val queue = read(a, ep9, c, a, d)
        val map = QueueMap(listOf(a, c, a, d), queue)
        assertEquals("the extra episode shifts nothing; the k-th A to the k-th A", listOf(1, 3, 4, 5), map.slots)
        assertEquals(0, map.itemAt(1))
        assertNull("the extra has no item of ours", map.itemAt(2))
        assertEquals(2, map.itemAt(4))
        assertNull(map.itemAt(9))
        // a gap: one of ours the speaker didn't get (unplayable for the household)
        assertEquals(listOf(1, null, 2), QueueMap(listOf(a, e, c), read(a, c)).slots)
        // a show listed newest first over a queue in Spotify's order, capped at our end
        val show = (1..5).map { "spotify:episode:5FakeEpisode000000000$it" }
        assertEquals(listOf(5, 4, 3), QueueMap(show.reversed().take(3), read(*show.toTypedArray())).slots)
        // a relinked track: our listing names its relinked id, the queue the one the speaker plays - no slot, never a guess
        assertEquals(listOf(1, null), QueueMap(listOf(a, "spotify:track:1FakeRelinked000000001"), read(a, c)).slots)
    }

    @Test
    fun `d54 the confirm verifies, the nearest occurrence when untrusted, and follows`() {
        val map = QueueMap(listOf(a, c, a, d), read(a, ep9, c, a, d))
        assertTrue(map.verifies(3, c))
        assertFalse("position 3 holds C, not D: untrusted", map.verifies(3, d))
        assertFalse(map.verifies(2, ep9))
        assertEquals("the A nearest the expected index", 2, map.nearest(a, 3))
        assertEquals(0, map.nearest(a, 0))
        assertNull(map.nearest(e, 1))
        // follows: our next is at the next position
        assertTrue("C (pos 3) -> A (pos 4)", map.follows(1, 2))
        assertFalse("A (pos 1) -> the episode at 2, not our C", map.follows(0, 1))
        assertFalse("our last never follows: the at-end rules decide", map.follows(3, null))
        assertFalse("a shuffled next", map.follows(1, 0))
    }

    @Test
    fun `d54 N5 - the version compared with the same source only, a missing source re-read, never a mismatch`() {
        val r = read(a, updateId = "39").copy(socketVersion = "39")
        assertEquals(VersionCheck.SAME, versionCheck(r, VersionSource.UPNP, "39"))
        assertEquals(VersionCheck.MOVED, versionCheck(r, VersionSource.UPNP, "41"))
        assertEquals(VersionCheck.MOVED, versionCheck(r, VersionSource.SOCKET, "40"))
        assertEquals("no value is no evidence", VersionCheck.SAME, versionCheck(r, VersionSource.SOCKET, null))
        val noSocket = read(a, updateId = "39")
        assertEquals("the socket came back after the read: read again", VersionCheck.REREAD, versionCheck(noSocket, VersionSource.SOCKET, "39"))
        assertEquals(VersionCheck.REREAD, versionCheck(read(a, updateId = null), VersionSource.UPNP, "39"))
    }

    @Test
    fun `d54 a read grows by pages, a page past the length is cut`() {
        var r = QueueRead(3, emptyMap(), null)
        assertFalse(r.complete)
        r = r.withPage(1, listOf(a, c), "39")
        assertEquals(setOf(a, c), r.uris)
        r = r.withPage(3, listOf(d, e))
        assertTrue(r.complete)
        assertEquals(3, r.tracks.size)
        assertFalse("never an id in a line", r.toString().contains("Fake"))
    }

    @Test
    fun `d54 the search's pages - the guess's page first, then outward, at most ten`() {
        assertEquals(listOf(0), queuePagesAround(1, 30))
        assertEquals(listOf(200, 300, 100, 400, 0), queuePagesAround(250, 450))
        assertEquals(10, queuePagesAround(1, 5_000).size)
        assertEquals(emptyList<Int>(), queuePagesAround(1, 0))
        assertEquals("a guess past the end is the last page", listOf(100, 0), queuePagesAround(900, 150))
        assertEquals(4, positionNearest(read(a, ep9, c, a, d), a, 3))
        assertNull(positionNearest(read(a), d, 1))
    }

    @Test
    fun `d54 N2 - an absent, empty or unseen container is no evidence, another playlist is`() {
        val ours = "spotify:playlist:0FakePlaylistKveld0001"
        assertFalse(containerContradicts(null, ours))
        assertFalse(containerContradicts("", ours))
        assertFalse(containerContradicts("vibb-1", ours))
        assertFalse(containerContradicts(ours, ours))
        assertFalse("an album's form was never measured [D8+]", containerContradicts("spotify:album:2FakeAlbumX00000000001", ours))
        assertTrue(containerContradicts("spotify:playlist:FAKEforeignList000001", ours))
        assertTrue("a playlist over our album", containerContradicts("spotify:playlist:FAKEforeignList000001", "spotify:album:2FakeAlbumX00000000001"))
        // ours: the prefix unless contradicted
        assertTrue(shareOurs(a, byContainer = false, inEitherList = true, versionMoved = null))
        assertTrue("an unlisted track of our queue is ours (54-1)", shareOurs(e, false, inEitherList = false, versionMoved = false))
        assertTrue("a moved version alone keeps it ours", shareOurs(a, false, inEitherList = true, versionMoved = true))
        assertTrue("no baseline or no answer is no evidence", shareOurs(e, false, inEitherList = false, versionMoved = null))
        assertFalse("a track in neither list on a queue edited by someone else", shareOurs(e, false, inEitherList = false, versionMoved = true))
        assertFalse(shareOurs(a, byContainer = true, inEitherList = true, versionMoved = null))
        assertFalse("not a Spotify queue track", shareOurs(null, false, true, null))
    }

    @Test
    fun `d54 sonos_migrate_probe 3 - the exact track and position continuity, never the prefix alone`() {
        val live = "x-sonos-spotify:spotify%3atrack%3a1FakeTrackC00000000003?sid=9&flags=8232&sn=6"
        assertTrue(shareProbeMatches(c, 60.0, live, 62.0))
        assertTrue(shareProbeMatches(c, 60.0, live, 55.0))
        assertTrue(shareProbeMatches(c, 60.0, live, 105.0))
        assertFalse("too far behind", shareProbeMatches(c, 60.0, live, 54.9))
        assertFalse("too far ahead", shareProbeMatches(c, 60.0, live, 105.1))
        assertFalse("another track of the same prefix", shareProbeMatches(a, 60.0, live, 61.0))
        assertFalse("no recorded track, never", shareProbeMatches(null, null, live, 61.0))
        assertFalse(shareProbeMatches(c, 60.0, live, null))
    }

    @Test
    fun `d54 N8 natural or pressed on our anchor - the fallback only marks done, the correction needs the anchor`() {
        val playing = SonosAnchor(225_000, 1_000, moving = true, key = "k")
        assertEquals("at 229 s of 230 s when it changed", Boundary.NATURAL, boundary(playing, 230_000, 5_000, endsNear = false))
        assertTrue(Boundary.NATURAL.correctable)
        assertEquals("a press at 1:00", Boundary.PRESSED, boundary(SonosAnchor(60_000, 1_000, true, "k"), 230_000, 2_000, endsNear = true))
        assertEquals("paused near the end and pressed: the anchor doesn't move", Boundary.PRESSED, boundary(SonosAnchor(220_000, 0, false, "k"), 230_000, 9_000, false))
        assertEquals(Boundary.NATURAL_GUESS, boundary(null, 230_000, 5_000, endsNear = true))
        assertTrue(Boundary.NATURAL_GUESS.natural)
        assertFalse(Boundary.NATURAL_GUESS.correctable)
        assertEquals(Boundary.PRESSED, boundary(null, null, 5_000, endsNear = false))
        // N1b: the queue's end from the anchor, no tail poll needed
        assertTrue(anchorNearEnd(SonosAnchor(200_000, 0, true, "k"), 230_000, 15_000, 20.0))
        assertFalse(anchorNearEnd(SonosAnchor(200_000, 0, true, "k"), 230_000, 5_000, 20.0))
        assertFalse(anchorNearEnd(null, 230_000, 5_000, 20.0))
    }

    @Test
    fun `d54 54-8 the at-end arming - Til slutten, repeat one, our last only when the queue goes on`() {
        assertEquals(AtEnd.HOLD, atEndAction(holdAtEnd = true, Repeat.ALL, ourLast = true, slotAfter = true))
        assertEquals(AtEnd.REPEAT_ONE, atEndAction(false, Repeat.ONE, ourLast = false, slotAfter = true))
        assertEquals(AtEnd.END, atEndAction(false, Repeat.OFF, ourLast = true, slotAfter = true))
        assertEquals(AtEnd.WRAP, atEndAction(false, Repeat.ALL, ourLast = true, slotAfter = true))
        assertEquals("our last at the queue's last position: the speaker stops by itself", AtEnd.NONE, atEndAction(false, Repeat.OFF, ourLast = true, slotAfter = false))
        assertEquals(AtEnd.NONE, atEndAction(false, Repeat.ALL, ourLast = true, slotAfter = false))
        assertEquals(AtEnd.NONE, atEndAction(false, Repeat.OFF, ourLast = false, slotAfter = true))
        assertEquals("400 ms before the end", 9_600L, atEndDelayMs(230_000, 220_000))
        assertEquals(0L, atEndDelayMs(230_000, 229_900))
    }

    @Test
    fun `d54 Q6 the unmapped look - the server's title form`() {
        assertEquals("Månesang — Kari Li", unmappedTitle("Månesang", "Kari Li"))
        assertEquals("Uten artist", unmappedTitle("Uten artist", " "))
        assertNull(unmappedTitle(null, "Kari Li"))
    }

    @Test
    fun `d54 N1 the speaker's sleep timer - to the edge, capped, none past a day, re-set past 30 s, cleared only while ours`() {
        val now = 1_760_000_000_000L
        assertNull("no edge", sleepTimerFor(0, now))
        assertNull("an edge past", sleepTimerFor(now - 1, now))
        assertNull("more than a day away", sleepTimerFor(now + 86_400_001, now))
        assertEquals("1:00:00", sleepTimerFor(now + 3_600_000, now))
        assertEquals("rounded up", "0:00:02", sleepTimerFor(now + 1_200, now))
        assertEquals("capped at 23:59:59", "23:59:59", sleepTimerFor(now + 86_400_000, now))
        assertTrue(sleepTimerResetDue(null, 60_000))
        assertFalse(sleepTimerResetDue(3_600_000, 3_620_000))
        assertTrue(sleepTimerResetDue(3_600_000, 3_640_000))
        assertTrue(sleepTimerOurs(3_590.0, 3_600_000))
        assertFalse("one set in the Sonos app afterwards is theirs", sleepTimerOurs(1_800.0, 3_600_000))
        assertFalse(sleepTimerOurs(null, 3_600_000))
        assertFalse(sleepTimerOurs(3_590.0, null))
    }
}
