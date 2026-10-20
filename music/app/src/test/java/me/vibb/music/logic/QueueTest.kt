package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Play order, cover taps, picks and shuffle (design 21 §4.2, §4.4). */
class QueueTest {
    private val oldestFirst = listOf("a", "b", "c", "d")

    @Test
    fun `natural order - NRK podkast and RSS newest first, NRK serie and own files oldest first`() {
        assertEquals(listOf("d", "c", "b", "a"), playOrder(entry(1, Source.RSS), oldestFirst))
        assertEquals(listOf("d", "c", "b", "a"), playOrder(entry(1, Source.NRK, target = "https://radio.nrk.no/podkast/x"), oldestFirst))
        assertEquals(oldestFirst, playOrder(entry(1, Source.NRK, target = "https://radio.nrk.no/serie/x"), oldestFirst))
        assertEquals(oldestFirst, playOrder(entry(1, Source.OWN), oldestFirst))
    }

    @Test
    fun `an explicit order other than the natural one reverses`() {
        assertEquals(oldestFirst, playOrder(entry(1, Source.RSS, order = "oldest_first"), oldestFirst))
        assertEquals(listOf("d", "c", "b", "a"), playOrder(entry(1, Source.OWN, order = "newest_first"), oldestFirst))
        assertEquals(listOf("d", "c", "b", "a"), playOrder(entry(1, Source.RSS, order = "newest_first"), oldestFirst))
    }

    @Test
    fun `a cover tap with resume on continues at the bookmark`() {
        assertEquals(QueuePlan(listOf("c", "d", "a", "b"), 0, 90_000), coverTapPlan(oldestFirst, true, Bookmark("c", 90_000), false))
        assertEquals(QueuePlan(oldestFirst, 0, 0), coverTapPlan(oldestFirst, true, null, false))
        assertNull(coverTapPlan(emptyList(), true, null, false))
    }

    @Test
    fun `a cover tap with resume off starts the first item at 0`() {
        assertEquals(QueuePlan(oldestFirst, 0, 0), coverTapPlan(oldestFirst, false, Bookmark("c", 90_000), false))
    }

    @Test
    fun `a pick starts that item at its own position when resume is on`() {
        assertEquals(QueuePlan(listOf("b", "c", "d", "a"), 0, 300_000), pickPlan(oldestFirst, "b", true, ItemPosition(300_000, false), null))
        assertEquals("a done item starts fresh", 0L, pickPlan(oldestFirst, "b", true, ItemPosition(600_000, true), null)!!.startPosMs)
        assertEquals("resume off: from 0", 0L, pickPlan(oldestFirst, "b", false, ItemPosition(300_000, false), null)!!.startPosMs)
        assertNull(pickPlan(oldestFirst, "zz", true, null, null))
    }

    private fun spotify(kind: String, mode: String) =
        entry(6, Source.SPOTIFY, target = "spotify:$kind:FAKEid6", cache = 0).copy(spotifyMode = mode)

    @Test
    fun `d49 - controlSlots - music gets shuffle and repeat, what resumes the skip pair (own music keeps shuffle)`() {
        assertEquals(ControlSlots.SHUFFLE_REPEAT, controlSlots(entry(1, Source.OWN, resume = false)))
        assertEquals(ControlSlots.SKIP, controlSlots(entry(1, Source.OWN, resume = true)))
        assertEquals(ControlSlots.SKIP, controlSlots(entry(1, Source.RSS, resume = false)))
        assertEquals(ControlSlots.SKIP, controlSlots(entry(1, Source.NRK)))
        assertEquals(ControlSlots.SKIP, controlSlots(entry(1, Source.STORYTEL, target = "storytel:series:1")))
        for (kind in listOf("playlist", "album", "track")) assertEquals(kind, ControlSlots.SHUFFLE_REPEAT, controlSlots(spotify(kind, "list")))
        assertEquals("a show is a podcast", ControlSlots.SKIP, controlSlots(spotify("show", "list")))
        assertEquals(ControlSlots.SHUFFLE_REPEAT, controlSlots(spotify("artist", "context")))
    }

    @Test
    fun `d49 - shuffle and repeat follow the slots - an artist context repeats only (Spotify picks)`() {
        assertTrue(canShuffle(entry(1, Source.OWN, resume = false)))
        assertFalse(canShuffle(entry(1, Source.OWN, resume = true)))
        assertFalse(canShuffle(entry(1, Source.RSS, resume = false)))
        assertFalse(canShuffle(entry(1, Source.NRK)))
        assertTrue("26c's No shuffle overridden (the user's call)", canShuffle(spotify("playlist", "list")))
        assertTrue("a playlist context: Spotify's own shuffle", canShuffle(spotify("playlist", "context")))
        assertFalse(canShuffle(spotify("artist", "context")))
        assertTrue(canRepeat(spotify("artist", "context")))
        assertTrue(canRepeat(entry(1, Source.OWN, resume = false)))
        assertFalse(canRepeat(entry(1, Source.RSS)))
        assertFalse(canRepeat(spotify("show", "list")))
    }

    @Test
    fun `d49 - repeat steps off, all, one - an unknown word is off`() {
        assertEquals(listOf(Repeat.ALL, Repeat.ONE, Repeat.OFF), Repeat.entries.map { it.next() })
        for (r in Repeat.entries) assertEquals(r, Repeat.of(r.word))
        assertEquals(Repeat.OFF, Repeat.of(null))
        assertEquals(Repeat.OFF, Repeat.of("sideways"))
    }

    @Test
    fun `d49 - the natural end - off ends, all wraps to the first, one plays the item again`() {
        assertEquals(2, advanceIndex(1, 3, Repeat.OFF))
        assertNull(advanceIndex(2, 3, Repeat.OFF))
        assertEquals(0, advanceIndex(2, 3, Repeat.ALL))
        assertEquals(2, advanceIndex(1, 3, Repeat.ALL))
        assertEquals(1, advanceIndex(1, 3, Repeat.ONE))
        assertEquals("one item, all: itself", 0, advanceIndex(0, 1, Repeat.ALL))
        assertNull(advanceIndex(0, 0, Repeat.ALL))
    }

    @Test
    fun `d49 - the move-on past an unplayable item wraps only with repeat all, one lap at most, never back to it`() {
        val playable = setOf(0, 3)
        assertEquals(3, moveOnIndex(5, 1, Repeat.OFF) { it in playable })
        assertNull("off: nothing after the last", moveOnIndex(5, 4, Repeat.OFF) { it in playable })
        assertNull("one: the queue's end too", moveOnIndex(5, 4, Repeat.ONE) { it in playable })
        assertEquals("all: around to the first", 0, moveOnIndex(5, 4, Repeat.ALL) { it in playable })
        var asked = 0
        assertNull("a lap with nothing playable pauses", moveOnIndex(5, 2, Repeat.ALL) { asked++; false })
        assertEquals("one lap: every other item once, never the failed one", 4, asked)
        assertNull("the failed item alone is no place to go", moveOnIndex(3, 1, Repeat.ALL) { it == 1 })
    }

    @Test
    fun `qa-music-a-code 1 - a chain of move-ons with every item failing at play time ends within one lap`() {
        // each item passes the gate but fails when played: onError moves on from the next one, the run's origin kept
        val n = 5
        val origin = 2
        var from = origin
        var steps = 0
        while (true) {
            val next = moveOnIndex(n, from, Repeat.ALL, origin) { true } ?: break
            from = next
            steps++
            assertTrue("never more than one lap", steps <= n - 1)
        }
        assertEquals("every other item tried once, then paused", n - 1, steps)
        assertEquals("without an origin the chain would go on", 3, moveOnIndex(n, 2, Repeat.ALL) { true })
        assertEquals("off: the queue's end ends it anyway", null, moveOnIndex(n, 4, Repeat.OFF, origin) { true })
    }

    @Test
    fun `d49 - shuffle with resume changes the order only - the bookmarked item first at its place`() {
        val keys = (1..20).map { "t$it" }
        val plan = coverTapPlan(keys, true, Bookmark("t7", 90_000), true, Random(4))!!
        assertEquals("t7", plan.keys.first())
        assertEquals(90_000L, plan.startPosMs)
        assertEquals(keys.toSet(), plan.keys.toSet())
        assertTrue("shuffled", plan.keys.drop(1) != keys.filter { it != "t7" })
        val none = coverTapPlan(keys, true, null, true, Random(4))!!
        assertEquals("no bookmark: a fresh permutation from 0", 0L, none.startPosMs)
        assertEquals(keys.toSet(), none.keys.toSet())
        assertEquals("the screens know the resumed item", "t7" to 90_000L, coverTapStart(keys, true, Bookmark("t7", 90_000), shuffle = true))
        assertEquals("but not a permutation's first", null to 0L, coverTapStart(keys, true, null, shuffle = true))
    }

    @Test
    fun `d49 - the flip - shuffle ignores it, unshuffling goes the flipped way, repeat applies after either`() {
        val flipped = oldestFirst.asReversed()
        val on = toggledQueue(flipped, "c", shuffleOn = true, random = Random(5))
        assertEquals("c", on.first())
        assertEquals(oldestFirst.toSet(), on.toSet())
        assertEquals(listOf("c", "b", "a", "d"), toggledQueue(flipped, "c", shuffleOn = false))
        assertEquals("all wraps the flipped queue to its first", 0, advanceIndex(3, 4, Repeat.ALL))
    }

    @Test
    fun `shuffle on keeps the current item first, a permutation of all, stable for a seed`() {
        val keys = (1..30).map { "t$it" }
        val q = toggledQueue(keys, "t7", shuffleOn = true, random = Random(1))
        assertEquals("t7", q.first())
        assertEquals(keys.toSet(), q.toSet())
        assertEquals(keys.size, q.size)
        assertEquals(q, toggledQueue(keys, "t7", shuffleOn = true, random = Random(1)))
    }

    @Test
    fun `shuffle off plays the play order from the current item`() {
        assertEquals(listOf("c", "d", "a", "b"), toggledQueue(oldestFirst, "c", shuffleOn = false))
    }

    @Test
    fun `with shuffle on, a pick continues in the shuffled order the list shows`() {
        val shuffled = listOf("c", "a", "d", "b")
        assertEquals(listOf("d", "b", "c", "a"), pickPlan(oldestFirst, "d", false, null, shuffled)!!.keys)
        val fresh = pickPlan(oldestFirst, "d", false, null, listOf("x"), Random(2))!!.keys
        assertEquals("d", fresh.first())
        assertEquals(oldestFirst.toSet(), fresh.toSet())
    }

    @Test
    fun `a cover tap on a shuffled music entry starts a fresh permutation`() {
        val plan = coverTapPlan(oldestFirst, false, null, true, Random(3))!!
        assertEquals(oldestFirst.toSet(), plan.keys.toSet())
        assertEquals(0L, plan.startPosMs)
    }

    @Test
    fun `the stored queue as a plan - its place when the item is still there, else where it starts`() {
        assertEquals(QueuePlan(listOf("a", "b", "c"), 1, 42_000), storedPlan(listOf("a", "b", "c"), 1, 42_000, setOf("a", "b", "c")) { 7 })
        assertEquals("b went: c, where it starts", QueuePlan(listOf("a", "c"), 1, 7), storedPlan(listOf("a", "b", "c"), 1, 42_000, setOf("a", "c")) { 7 })
        assertNull(storedPlan(listOf("a"), 0, 0, emptySet()) { 0 })
    }

    @Test
    fun `coming home from a speaker - the whole order rotated to the item that played, at its bookmark`() {
        assertEquals(QueuePlan(listOf("c", "a", "b"), 0, 99_000), homePlan(listOf("a", "b", "c"), "c", 99_000))
        assertEquals("an item gone from the order starts the order at 0", QueuePlan(listOf("a", "b"), 0, 0), homePlan(listOf("a", "b"), "x", 99_000))
    }

    @Test
    fun `d39 - a flip keeps the queue's own keys - what a room's queue left out stays out (QA M1)`() {
        // newest first e d c b a, the room's queue without c (an HLS item), playing d; flipped to oldest first
        val roomQueue = listOf("d", "b", "a", "e")
        assertEquals(listOf("d", "e", "a", "b"), flippedQueue(listOf("a", "b", "c", "d", "e"), roomQueue, "d"))
        assertEquals("the phone's whole queue", listOf("b", "c", "a"), flippedQueue(listOf("a", "b", "c"), listOf("b", "a", "c"), "b"))
        assertEquals("flipped back", listOf("b", "a", "c"), flippedQueue(listOf("c", "b", "a"), listOf("b", "c", "a"), "b"))
    }
}
