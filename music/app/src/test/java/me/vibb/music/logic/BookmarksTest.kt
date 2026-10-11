package me.vibb.music.logic

import me.vibb.music.Fixtures
import me.vibb.music.model.LibraryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of palchrb/vibb `tests/episode_resume.py` (MIT, Copyright (c) 2026 palchrb): every item
 * remembers its own position, a finished one is done (a re-tap starts it fresh), and the
 * bookmarked item always plays first. Case 5 (the Pi's legacy single-bookmark file) has no
 * counterpart on the phone.
 */
class BookmarksTest {
    private val duration = 1_800_000L

    @Test
    fun `1 each item keeps its own position`() {
        val positions = mapOf("ida" to recordPosition(300_000, duration), "idb" to recordPosition(120_000, duration))
        assertEquals(300_000L, resumeFrom(positions["ida"]))
        assertEquals(120_000L, resumeFrom(positions["idb"]))
    }

    @Test
    fun `2 a stream and its downloaded copy share one position (keyed by the item, never the url)`() {
        // What the player stores names the item key only: the copy of "ida" finds the stream's place.
        val streamed = save("ida", 300_000, duration)
        assertEquals(Bookmark("ida", 300_000), streamed.bookmark)
        assertEquals(300_000L, resumeFrom(mapOf("ida" to streamed.position)["ida"]))
    }

    @Test
    fun `3 the entry's bookmark follows the item saved last`() {
        val saves = listOf(save("ida", 300_000, duration), save("idb", 120_000, duration))
        assertEquals(Bookmark("idb", 120_000), saves.last().bookmark)
        assertEquals("each keeps its own position", listOf(300_000L, 120_000L), saves.map { resumeFrom(it.position) })
    }

    @Test
    fun `4 a finished item reads as 0 and keeps its done mark, others are untouched`() {
        val done = recordPosition(1_795_000, duration) // within RESUME_MIN_MS of the end
        assertTrue(done.done)
        assertEquals("the duration is stored, not the position", duration, done.posMs)
        assertEquals(0L, resumeFrom(done))
        assertEquals(120_000L, resumeFrom(recordPosition(120_000, duration)))
        assertFalse(recordPosition(1_779_000, duration).done)
        assertFalse("an unknown duration is never done", recordPosition(1_795_000, null).done)
    }

    @Test
    fun `6 the resume flag defaults on and a non-boolean drops the entry`() {
        val base = Fixtures.resource("music_library.json")
        val lib = LibraryParser.parse(base.replace("      \"resume\": true,\n", ""))!!
        assertTrue("resume defaults on", lib.entry(1)!!.resume)
        assertFalse(lib.entry(3)!!.resume)
        val bad = LibraryParser.parse(base.replace("\"resume\": false", "\"resume\": \"nope\""))!!
        assertNull(bad.entry(3))
    }

    @Test
    fun `7 the bookmarked item always plays first, an early position only skips the seek`() {
        val order = listOf("e1", "e2", "e3")
        assertEquals(listOf("e3", "e1", "e2") to 0L, rotateToBookmark(order, Bookmark("e3", 5_000)))
        assertEquals(listOf("e3", "e1", "e2") to 300_000L, rotateToBookmark(order, Bookmark("e3", 300_000)))
        assertEquals("an unknown bookmark doesn't rotate", order to 0L, rotateToBookmark(order, Bookmark("zz", 99_000)))
        assertEquals(order to 0L, rotateToBookmark(order, null))
    }

    @Test
    fun `the seek gate starts the first 20 s at 0`() {
        assertEquals(0L, seekGate(RESUME_MIN_MS))
        assertEquals(RESUME_MIN_MS + 1, seekGate(RESUME_MIN_MS + 1))
    }

    @Test
    fun `an item that ends by itself is done, from the player's duration when the listing has none`() {
        assertEquals(ItemPosition(600_000, true), finished(600_000, null))
        assertEquals(ItemPosition(600_000, true), finished(null, 600_000))
        assertEquals("the player's wins", ItemPosition(601_000, true), finished(601_000, 600_000))
        assertNull("a live stream has no end", finished(null, null))
        assertNull(finished(0, null))
    }

    @Test
    fun `clear_state at a queue's end keeps only the done marks`() {
        val positions = mapOf("a" to ItemPosition(600_000, true), "b" to ItemPosition(300_000, false), "c" to ItemPosition(0, false))
        assertEquals(mapOf("a" to ItemPosition(600_000, true)), clearState(positions))
    }
}
