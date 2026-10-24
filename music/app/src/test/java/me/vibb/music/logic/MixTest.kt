package me.vibb.music.logic

import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.ItemRow
import me.vibb.music.model.Source
import me.vibb.music.playback.RendererState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Design r12 §3: the favourites queue - every heart of every entry, newest first, played as runs of one entry. */
class MixTest {
    private val pod = entry(1)
    private val series = entry(2, Source.STORYTEL, target = "storytel:series:26175", cache = -1)
    private val own = entry(3, Source.OWN)
    private val list = entry(4, Source.SPOTIFY, target = "spotify:playlist:FAKEplaylist0000000001", cache = 0).copy(spotifyMode = "list")

    private fun ref(e: me.vibb.music.model.Entry, k: String) = MixRef(e.key, k)

    @Test
    fun `mixOrder - newest heart first, the pre-Room-8 zeros after them in library then reading order, unlisted and gone out`() {
        val listed = mapOf(pod.key to listOf("p1", "p2", "p3"), series.key to listOf("b1", "b2"))
        val hearts = mapOf(
            pod.key to mapOf("p1" to 0L, "p3" to 300L, "dropped" to 900L),
            series.key to mapOf("b2" to 0L, "b1" to 500L),
            "gone-entry" to mapOf("x" to 1_000L),
        )
        val order = mixOrder(listOf(pod, series), { listed[it.key].orEmpty() }, hearts)
        assertEquals(listOf(ref(series, "b1"), ref(pod, "p3"), ref(pod, "p1"), ref(series, "b2")), order)
        // the snapshot's own: the library's order (sort, id) and the listing's reading order
        val s = snapshot(library(listOf(series, pod)), items(1, listOf("p1", "p2", "p3")) + items(2, listOf("b1", "b2"), gone = setOf("b1", "b2")))
            .copy(favourites = mapOf(pod.key to setOf("p1", "p3"), series.key to setOf("b2")), heartedAt = mapOf(pod.key to mapOf("p3" to 7L)))
        assertEquals(listOf(ref(pod, "p3"), ref(pod, "p1"), ref(series, "b2")), s.favouritesOrder())
    }

    @Test
    fun `mixView - the phone holds a book and an own file only with a copy, Spotify in - a room every book, no own file, no Spotify, no withdrawn item`() {
        val s = snapshot(
            library(listOf(pod, series, own, list), files = listOf(file(30, 3), file(31, 3))),
            items(1, listOf("p1", "p2"), gone = setOf("p2")) + items(2, listOf("b1", "b2"), gone = setOf("b1", "b2")) +
                listOf(ItemRow(4, "t1", 0, "Song", null, false, 180_000, null)),
            downloads = listOf(done(2, "b1", url = "storytel:book:b1")),
            ownCopies = setOf(30L),
        )
        val ownKeys = s.listed[3]!!.map { it.key }
        val order = listOf(ref(pod, "p1"), ref(pod, "p2"), ref(series, "b1"), ref(series, "b2"), ref(own, ownKeys[0]), ref(own, ownKeys[1]), ref(list, "t1"))
        assertEquals(
            "the phone: no withdrawn item without a copy, the downloaded book, the copied own file, the Spotify track",
            listOf(ref(pod, "p1"), ref(series, "b1"), ref(own, ownKeys[0]), ref(list, "t1")),
            mixView(order) { s.holds(it) },
        )
        val room = s.forRenderer(true)
        assertEquals("a room: every book, no own file, no Spotify, no withdrawn item", listOf(ref(pod, "p1"), ref(series, "b1"), ref(series, "b2")), mixView(order) { room.holds(it) })
    }

    @Test
    fun `mixTapQueue - as the view, or a seeded permutation, null when empty`() {
        val view = (1..6).map { MixRef("e$it", "k$it") }
        assertEquals(view, mixTapQueue(view, shuffle = false))
        val shuffled = mixTapQueue(view, shuffle = true, random = Random(7))!!
        assertEquals(view.toSet(), shuffled.toSet())
        assertTrue("a permutation", shuffled != view)
        assertNull(mixTapQueue(emptyList(), shuffle = true))
    }

    private val queue = listOf(MixRef("a", "1"), MixRef("b", "1"), MixRef("b", "2"), MixRef("b", "3"), MixRef("c", "9"))

    @Test
    fun `segmentAt and afterRun - a run is its entry's consecutive refs, then the next, the wrap with repeat all, the end`() {
        assertEquals("a run in the middle starts at its first ref", Run("b", 1, listOf("1", "2", "3")), segmentAt(queue, 2))
        assertEquals("a lone ref is a run of one", Run("a", 0, listOf("1")), segmentAt(queue, 0))
        assertEquals(Run("c", 4, listOf("9")), segmentAt(queue, 4))
        assertEquals(4, afterRun(queue, segmentAt(queue, 3), Repeat.OFF))
        assertNull("the queue's end", afterRun(queue, segmentAt(queue, 4), Repeat.OFF))
        assertNull("repeat one is the run's own", afterRun(queue, segmentAt(queue, 4), Repeat.ONE))
        assertEquals(0, afterRun(queue, segmentAt(queue, 4), Repeat.ALL))
    }

    @Test
    fun `mixPlayableFrom - skips what can't play, one lap at most, never back to the origin`() {
        val ok = setOf(0, 3)
        assertEquals(3, mixPlayableFrom(5, 1, forward = true, wrap = false, origin = null) { it in ok })
        assertNull("no wrap past the end", mixPlayableFrom(5, 4, forward = true, wrap = false, origin = null) { it in ok })
        assertEquals("wraps", 0, mixPlayableFrom(5, 4, forward = true, wrap = true, origin = null) { it in ok })
        assertEquals("previous goes back", 0, mixPlayableFrom(5, 2, forward = false, wrap = false, origin = null) { it in ok })
        assertNull("the run of failures began at 0: never back there", mixPlayableFrom(5, 4, forward = true, wrap = true, origin = 0) { it == 0 })
        assertNull("nothing plays: one lap, then none", mixPlayableFrom(5, 2, forward = true, wrap = true, origin = null) { false })
        assertEquals("itself", 2, mixPlayableFrom(5, 2, forward = true, wrap = true, origin = null) { true })
    }

    @Test
    fun `toggledMix - on, the current first and the rest permuted - off, the tap's order from the current, dropped refs out`() {
        val on = toggledMix(queue, queue, queue[2], on = true, random = Random(3))
        assertEquals(queue[2], on.first())
        assertEquals(queue.toSet(), on.toSet())
        assertEquals("off: the order rotated to the current", listOf(queue[2], queue[3], queue[4], queue[0], queue[1]), toggledMix(queue, on, queue[2], on = false))
        // a ref no longer in the order (unhearted since a restore rebuilt it) drops - but the current stays
        val order = queue.filter { it != queue[4] }
        assertEquals(listOf(queue[3], queue[0], queue[1], queue[2]), toggledMix(order, on, queue[3], on = false))
        assertEquals(listOf(queue[4]) + order, toggledMix(order, on, queue[4], on = false))
    }

    @Test
    fun `encodeMix and decodeMix - a round trip, a malformed line dropped`() {
        assertEquals(queue, decodeMix(encodeMix(queue)))
        assertEquals(listOf(MixRef("a", "1")), decodeMix("a\u001F1\nnosep\n\u001Fx\nb\u001F"))
        assertNull(decodeMix(null))
        assertNull("nothing left", decodeMix("junk"))
        assertTrue("never an entry key", MIX_KEY.contains(':'))
    }

    @Test
    fun `restoredMix - the current ref survived, else the next surviving one from its own place, else nothing`() {
        val held = { r: MixRef -> r.entryKey != "b" }
        val same = restoredMix(queue, queue[0], held)!!
        assertEquals(listOf(queue[0], queue[4]), same.queue)
        assertEquals(0 to true, same.index to same.same)
        val next = restoredMix(queue, queue[2], held)!!
        assertEquals("b2 isn't held: the next that is, c9", 1 to false, next.index to next.same)
        val wrap = restoredMix(queue, queue[3]) { it == queue[0] }!!
        assertEquals("wrapping", 0 to false, wrap.index to wrap.same)
        assertEquals("unknown current: the first", 0 to false, restoredMix(queue, null, held)!!.let { it.index to it.same })
        assertNull(restoredMix(queue, queue[0]) { false })
    }

    @Test
    fun `runEnd - the next run playing, held at its start by Til slutten, or the end`() {
        assertEquals(RunEnd.Next(3, play = true), runEnd(3, heldBySleep = false))
        assertEquals(RunEnd.Next(3, play = false), runEnd(3, heldBySleep = true))
        assertEquals(RunEnd.End, runEnd(null, heldBySleep = true))
    }

    @Test
    fun `mixDropsSpotify - an adult's matter or no Widevine, never the network's lines, elsewhere or stopped`() {
        assertTrue(mixDropsSpotify(RendererState.SPOTIFY_ASK_ADULT))
        assertTrue(mixDropsSpotify(RendererState.SPOTIFY_NOT_ON_PHONE))
        assertTrue(mixDropsSpotify(RendererState.SPOTIFY_UNAVAILABLE))
        for (s in listOf(RendererState.SPOTIFY_NEEDS_WIFI, RendererState.SPOTIFY_NEEDS_INTERNET, RendererState.SPOTIFY_ELSEWHERE, RendererState.SPOTIFY_STOPPED, null)) {
            assertFalse("$s", mixDropsSpotify(s))
        }
    }
}
