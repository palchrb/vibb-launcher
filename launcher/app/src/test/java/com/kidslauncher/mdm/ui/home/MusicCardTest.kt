package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.music.BootStamp
import com.kidslauncher.mdm.music.Listening
import com.kidslauncher.mdm.music.MUSIC_LINGER_MS
import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.NowPlayingSnapshot
import com.kidslauncher.mdm.music.SessionPlay
import com.kidslauncher.mdm.music.musicRecheckInMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's "Spilles nå" card (design 21 §3, mockup HomeCard.dc.html v12; the listening window, design 28). */
class MusicCardTest {
    private val boot = 7
    /** The music stopped at elapsed 0 in this boot: the window is open until 10 minutes. */
    private val open = Listening(BootStamp(0, boot), null)

    private fun session(play: SessionPlay = SessionPlay.PLAYING, entry: String? = "Reven og månen", item: String? = "Kapittel 3", duration: Long = 1_110_000) =
        MusicSession("me.vibb.music", play, entry, item, 466_000, 1_000, 1f, duration)

    private val snapshot = NowPlayingSnapshot("Reven og månen", "Kapittel 3", 300_000, 1_110_000)

    private fun card(
        session: MusicSession? = session(),
        snap: NowPlayingSnapshot? = snapshot,
        locked: Boolean = false,
        access: Boolean = true,
        usable: Boolean = true,
        now: Long = 1_000,
        listening: Listening = open,
    ) = musicCard(locked, access, usable, session, snap, now, listening, boot)

    @Test
    fun `a live session plays or is paused - transport controls, the session's times`() {
        val playing = card()!!
        assertEquals(MusicCardModel("Reven og månen", "Kapittel 3", 466_000, 1_110_000, playing = true, live = true, hideAtElapsedMs = null), playing)
        assertTrue(musicCardTicks(playing))
        assertEquals("the clock moves while playing", 476_000, card(now = 11_000)!!.positionMs)
        val paused = card(session(SessionPlay.PAUSED), now = 11_000)!!
        assertFalse(paused.playing)
        assertTrue(paused.live)
        assertEquals(466_000, paused.positionMs)
        assertFalse(musicCardTicks(paused))
    }

    @Test
    fun `no live session - the last snapshot, always paused (back at once after a restart)`() {
        for (gone in listOf(null, session(SessionPlay.STOPPED))) {
            val c = card(session = gone)!!
            assertEquals(MusicCardModel("Reven og månen", "Kapittel 3", 300_000, 1_110_000, playing = false, live = false, hideAtElapsedMs = MUSIC_LINGER_MS), c)
            assertFalse(musicCardTicks(c))
        }
        assertNull("a queue's end clears the snapshot: no card", card(session = null, snap = null))
        assertNull("nothing named", card(session = null, snap = NowPlayingSnapshot(" ", null, 0, 0)))
    }

    @Test
    fun `never while locked, without listener access or while the music app can't be used`() {
        assertNull(card(locked = true))
        assertNull("no notification access: no media surfaces", card(access = false))
        assertNull("no access: not even the snapshot", card(session = null, access = false))
        assertNull("suspended (a time rule) or not allowed", card(usable = false))
        assertNull(card(session = null, usable = false))
    }

    @Test
    fun `missing names and durations degrade, never fail`() {
        val noEntry = card(session(entry = null))!!
        assertEquals("the item becomes the title", "Kapittel 3", noEntry.title)
        assertNull(noEntry.item)
        val nothing = card(session(entry = null, item = null))!!
        assertEquals("the snapshot's entry", "Reven og månen", nothing.title)
        assertNull(card(session(entry = null, item = null), snap = null))
        assertEquals("the snapshot's duration for the same item", 1_110_000, card(session(duration = 0))!!.durationMs)
        assertEquals("not for another item", 0, card(session(item = "Kapittel 4", duration = 0))!!.durationMs)
        val clamped = card(session = null, snap = NowPlayingSnapshot("A", "B", 5_000_000, 1_000_000))!!
        assertEquals(1_000_000, clamped.positionMs)
    }

    @Test
    fun `the card shows only while playing or inside the window`() {
        val closed = Listening(null, null)
        assertTrue("playing needs no window", card(listening = closed)!!.playing)
        assertNull("paused, no window", card(session(SessionPlay.PAUSED), listening = closed))
        assertNull("the snapshot, no window", card(session = null, listening = closed))
        assertTrue("paused at 10 min - 1 ms", card(session(SessionPlay.PAUSED), now = MUSIC_LINGER_MS - 1)!!.live)
        assertNull("paused at exactly 10 min", card(session(SessionPlay.PAUSED), now = MUSIC_LINGER_MS))
        assertNull("the snapshot at exactly 10 min", card(session = null, now = MUSIC_LINGER_MS))
        val headset = Listening(BootStamp(0, boot), BootStamp(5 * 60_000, boot))
        assertFalse("a fresh headset after the stop: the later window", card(session = null, now = MUSIC_LINGER_MS, listening = headset)!!.live)
        assertNull("a stamp from another boot counts as nothing", card(session = null, listening = Listening(BootStamp(0, boot - 1), null)))
        // the window never opens what the old rules closed
        assertNull(card(locked = true))
        assertNull(card(session = null, access = false))
        assertNull(card(session(SessionPlay.PAUSED), usable = false))
    }

    @Test
    fun `a paused live session outside the window hides the card - never the snapshot instead`() {
        assertNull(card(session(SessionPlay.PAUSED), now = MUSIC_LINGER_MS + 1))
        assertNull(card(session(SessionPlay.PAUSED), listening = Listening(null, null)))
        // a stopped session isn't live: the snapshot inside the window, nothing outside it
        assertFalse(card(session(SessionPlay.STOPPED))!!.live)
        assertNull(card(session(SessionPlay.STOPPED), now = MUSIC_LINGER_MS + 1))
    }

    @Test
    fun `hideAt is the window's end while paused and null while playing`() {
        assertNull(card()!!.hideAtElapsedMs)
        assertNull("playing outside any window: still no end", card(listening = Listening(null, null))!!.hideAtElapsedMs)
        assertEquals(MUSIC_LINGER_MS, card(session(SessionPlay.PAUSED))!!.hideAtElapsedMs)
        assertEquals(MUSIC_LINGER_MS, card(session = null)!!.hideAtElapsedMs)
        val headset = Listening(BootStamp(0, boot), BootStamp(60_000, boot))
        assertEquals("the later window's end", 60_000 + MUSIC_LINGER_MS, card(session(SessionPlay.PAUSED), now = 60_000, listening = headset)!!.hideAtElapsedMs)
        assertEquals("the recheck: the time left", 9 * 60_000L, musicRecheckInMs(card(session = null, now = 60_000)!!.hideAtElapsedMs, 60_000))
        assertNull("no recheck while playing", musicRecheckInMs(card()!!.hideAtElapsedMs, 1_000))
    }
}
