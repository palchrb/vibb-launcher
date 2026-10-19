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

    @Test
    fun `shuffle is for music entries only - own files with resume off`() {
        assertTrue(canShuffle(entry(1, Source.OWN, resume = false)))
        assertFalse(canShuffle(entry(1, Source.OWN, resume = true)))
        assertFalse(canShuffle(entry(1, Source.RSS, resume = false)))
        assertFalse(canShuffle(entry(1, Source.NRK)))
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
