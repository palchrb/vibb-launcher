package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 53 §2.4 (QA 53-2): the display anchor's hysteresis - smooth within a second, a jump at one or more. */
class SonosAnchorTest {
    private fun poll(posMs: Long, at: Long, moving: Boolean = true, key: String = "k") = SonosMeasure(posMs, at, moving, key, poll = true)

    private fun event(posMs: Long, at: Long, moving: Boolean = true, key: String = "k") = SonosMeasure(posMs, at, moving, key, poll = false)

    @Test
    fun `d53 a sawtooth of whole-second polls 1_2 s apart is monotone - no step above one tick, no stall over 100 ms`() {
        // the speaker plays from 100.3 s; each poll reads RelTime truncated, the display adds half a second
        var a: SonosAnchor? = null
        val tick = 100L
        var shown = Long.MIN_VALUE
        var t = 0L
        var stalled = 0L
        while (t <= 120_000) {
            if (t % 1_200 == 0L) {
                val truth = 100_300 + t
                a = sonosAnchor(a, poll(truth / 1000 * 1000 + 500, t), socketHealthy = false)
            }
            val now = a!!.positionAt(t)
            if (shown != Long.MIN_VALUE) {
                assertTrue("monotone at $t", now >= shown)
                assertTrue("no step above one tick at $t", now - shown <= tick)
                stalled = if (now == shown) stalled + tick else 0
                assertTrue("no stall over 100 ms at $t", stalled <= 100)
            }
            shown = now
            t += tick
        }
        assertTrue("never more than a second from the truth", kotlin.math.abs(a!!.positionAt(120_000) - (100_300 + 120_000)) < 1_000)
    }

    @Test
    fun `d53 a correction of a second or more jumps at once, a pause holds`() {
        val a = sonosAnchor(null, poll(10_500, 0), false)
        val late = sonosAnchor(a, event(13_000, 1_000), false)
        assertEquals("1.5 s off: the measurement at once", 13_000L, late.positionAt(1_000))
        val paused = sonosAnchor(late, event(13_200, 1_200, moving = false), false)
        assertEquals("a change of motion: exact", 13_200L, paused.positionAt(60_000))
        val again = sonosAnchor(paused, poll(13_500, 5_000, moving = false), false)
        assertEquals("a paused poll within the second holds the exact place", 13_200L, again.positionAt(9_000))
    }

    @Test
    fun `d53 a seek back, a step and a new item jump - the key changed`() {
        val a = sonosAnchor(null, poll(600_500, 0), false)
        val back = sonosAnchor(a, poll(585_500, 1_000, key = "k|seek"), false)
        assertEquals("15 s tilbake: at once", 585_500L, back.positionAt(1_000))
        val next = sonosAnchor(back, event(300, 2_000, key = "next item"), false)
        assertEquals(300L, next.positionAt(2_000))
    }

    @Test
    fun `d53 the re-base survives more than 60 s of re-anchors - the extrapolation's cap is never reached`() {
        var a = sonosAnchor(null, event(0, 0), true)
        for (t in 5_000L..300_000L step 5_000L) a = sonosAnchor(a, event(t, t), true)
        assertEquals(300_000L, a.at)
        assertEquals(301_000L, a.positionAt(301_000))
    }

    @Test
    fun `d53 under a healthy socket a poll within the second changes nothing - one of a second or more still jumps`() {
        val a = sonosAnchor(null, event(431_700, 0), true)
        assertSame(a, sonosAnchor(a, poll(432_500, 1_000), socketHealthy = true))
        val off = sonosAnchor(a, poll(434_500, 1_000), socketHealthy = true)
        assertEquals(434_500L, off.positionAt(1_000))
        assertEquals("without the socket the poll re-bases (smoothly)", 1_000L, sonosAnchor(a, poll(432_500, 1_000), socketHealthy = false).at)
    }
}
