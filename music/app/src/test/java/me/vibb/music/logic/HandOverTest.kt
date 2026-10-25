package me.vibb.music.logic

import me.vibb.music.playback.RendererState
import me.vibb.music.sonos.HandOverTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** PLAN "Connecting…": the player's line while a play is on its way, and the room hand-over's timing lines. */
class HandOverTest {
    private var now = 1_000L
    private val lines = mutableListOf<String>()

    @Before
    fun clock() {
        HandOverTiming.clock = { now }
        HandOverTiming.sink = { lines += it }
        HandOverTiming.enter(null) // no play job left from another test's controller
    }

    @After
    fun reset() {
        HandOverTiming.sink = {}
        HandOverTiming.clock = { System.nanoTime() / 1_000_000 }
    }

    @Test
    fun `a room taking a play shows the speaker's line until PLAYING, the room's own line wins`() {
        // the tap's command still waits in the service (a look, a switch), then the controller's play settles
        assertEquals(TimeRow.CONNECTING_SPEAKER, timeRow(onRoom = true, connecting = false, tapWaiting = true, spotify = false, rendererState = null))
        assertEquals(TimeRow.CONNECTING_SPEAKER, timeRow(onRoom = true, connecting = true, tapWaiting = false, spotify = true, rendererState = null))
        // PLAYING (or a play that ended without one): the usual times
        assertEquals(TimeRow.TIMES, timeRow(onRoom = true, connecting = false, tapWaiting = false, spotify = false, rendererState = null))
        // the existing failure line after the existing timeout: the times come back under it
        assertEquals(TimeRow.TIMES, timeRow(onRoom = true, connecting = true, tapWaiting = true, spotify = false, rendererState = RendererState.UNREACHABLE))
    }

    @Test
    fun `the web player's cold start shows the plain line until its first playing state, its own line wins`() {
        // qa-music-r13-code Q-3: the page, the SDK, the token, the Connect device - "Kobler til…", never the speaker's words
        assertEquals(TimeRow.CONNECTING, timeRow(onRoom = false, connecting = true, tapWaiting = false, spotify = true, rendererState = null))
        assertEquals("the tap's command still waits", TimeRow.CONNECTING, timeRow(onRoom = false, connecting = false, tapWaiting = true, spotify = true, rendererState = null))
        assertEquals("playing", TimeRow.TIMES, timeRow(onRoom = false, connecting = false, tapWaiting = false, spotify = true, rendererState = null))
        assertEquals("the failure path: Trenger internett", TimeRow.TIMES, timeRow(onRoom = false, connecting = true, tapWaiting = true, spotify = true, rendererState = RendererState.SPOTIFY_NEEDS_INTERNET))
        // the phone's own player has no start to wait for: a podcast's held tap shows the times
        assertEquals(TimeRow.TIMES, timeRow(onRoom = false, connecting = false, tapWaiting = true, spotify = false, rendererState = null))
    }

    @Test
    fun `the timing lines run from the tap, a play without one starts its own, and a first line prints once`() {
        val tap = HandOverTiming.tap("PLAY_ENTRY")
        HandOverTiming.running(tap)
        now += 40
        HandOverTiming.play("the play queued")
        HandOverTiming.finished(tap)
        now += 800
        HandOverTiming.first("ws", "the first PLAYING socket event")
        HandOverTiming.until("ws", "a socket event: BUFFERING")
        HandOverTiming.first("ws", "the first PLAYING socket event")
        now += 100
        val step = HandOverTiming.play("a step")
        now += HandOverTiming.WINDOW_MS + 1
        HandOverTiming.step("too late")
        val n = tap.number
        assertEquals(n + 1, step.number)
        assertEquals(
            listOf("#$n +0 ms tap: PLAY_ENTRY", "#$n +40 ms the play queued", "#$n +840 ms the first PLAYING socket event", "#${n + 1} +0 ms (no tap) a step"),
            lines,
        )
    }

    @Test
    fun `a second tap while the first play runs - each play's lines under its own number and time (Q-1)`() {
        val tap1 = HandOverTiming.tap("PLAY_ENTRY")
        HandOverTiming.running(tap1)
        val play1 = HandOverTiming.play("the play queued")
        HandOverTiming.finished(tap1)
        now += 100
        // the kid sees nothing happen and taps again; its command waits for the switch lock
        val tap2 = HandOverTiming.tap("PLAY_ENTRY")
        now += 200
        // the first play's job runs on the actor: its transport steps print under #1, timed from tap 1
        HandOverTiming.enter(play1)
        HandOverTiming.step("Play answered")
        HandOverTiming.done("the play job done, a poll woken")
        // the second command runs now: its play continues tap 2
        HandOverTiming.running(tap2)
        now += 50
        val play2 = HandOverTiming.play("the play queued")
        HandOverTiming.finished(tap2)
        HandOverTiming.enter(play2)
        HandOverTiming.step("Play answered")
        val (a, b) = tap1.number to tap2.number
        assertEquals(tap2, play2)
        assertEquals(
            listOf(
                "#$a +0 ms tap: PLAY_ENTRY", "#$a +0 ms the play queued", "#$b +0 ms tap: PLAY_ENTRY",
                "#$a +300 ms Play answered", "#$a +300 ms the play job done, a poll woken",
                "#$b +250 ms the play queued", "#$b +250 ms Play answered",
            ),
            lines,
        )
    }
}
