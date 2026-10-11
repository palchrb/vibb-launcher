package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.NowPlayingSnapshot
import com.kidslauncher.mdm.music.SessionPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's "Spilles nå" card (design 21 §3, mockup HomeCard.dc.html v12). */
class MusicCardTest {
    private fun session(play: SessionPlay = SessionPlay.PLAYING, entry: String? = "Reven og månen", item: String? = "Kapittel 3", duration: Long = 1_110_000) =
        MusicSession("me.vibb.music", play, entry, item, 466_000, 1_000, 1f, duration)

    private val snapshot = NowPlayingSnapshot("Reven og månen", "Kapittel 3", 300_000, 1_110_000)

    private fun card(session: MusicSession? = session(), snap: NowPlayingSnapshot? = snapshot, locked: Boolean = false, access: Boolean = true, usable: Boolean = true, now: Long = 1_000) =
        musicCard(locked, access, usable, session, snap, now)

    @Test
    fun `a live session plays or is paused - transport controls, the session's times`() {
        val playing = card()!!
        assertEquals(MusicCardModel("Reven og månen", "Kapittel 3", 466_000, 1_110_000, playing = true, live = true), playing)
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
            assertEquals(MusicCardModel("Reven og månen", "Kapittel 3", 300_000, 1_110_000, playing = false, live = false), c)
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
}
