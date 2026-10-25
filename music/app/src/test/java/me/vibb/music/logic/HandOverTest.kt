package me.vibb.music.logic

import me.vibb.music.playback.RendererState
import me.vibb.music.sonos.HandOverTiming
import org.junit.Assert.assertEquals
import org.junit.Test

/** PLAN "Connecting…": the player's line while a room takes a play, and the hand-over's timing lines. */
class HandOverTest {
    @Test
    fun `a room taking a play shows the connecting line until PLAYING, the room's own line wins`() {
        // the tap's command still waits in the service (a look, a switch), then the controller's play settles
        assertEquals(TimeRow.CONNECTING, timeRow(onRoom = true, connecting = false, tapWaiting = true, rendererState = null))
        assertEquals(TimeRow.CONNECTING, timeRow(onRoom = true, connecting = true, tapWaiting = false, rendererState = null))
        // PLAYING (or a play that ended without one): the usual times
        assertEquals(TimeRow.TIMES, timeRow(onRoom = true, connecting = false, tapWaiting = false, rendererState = null))
        // the existing failure line after the existing timeout: the times come back under it
        assertEquals(TimeRow.TIMES, timeRow(onRoom = true, connecting = true, tapWaiting = true, rendererState = RendererState.UNREACHABLE))
        // the phone (and Spotify's web player) never: their own lines are theirs
        assertEquals(TimeRow.TIMES, timeRow(onRoom = false, connecting = true, tapWaiting = true, rendererState = null))
    }

    @Test
    fun `the timing lines run from the tap, a play without one starts its own, and a first line prints once`() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        HandOverTiming.clock = { now }
        HandOverTiming.sink = { lines += it }
        try {
            HandOverTiming.tap("PLAY_ENTRY")
            now += 40
            HandOverTiming.play("the play queued")
            now += 800
            HandOverTiming.first("ws", "the first PLAYING socket event")
            HandOverTiming.until("ws", "a socket event: BUFFERING")
            HandOverTiming.first("ws", "the first PLAYING socket event")
            now += 100
            HandOverTiming.play("a step")
            now += HandOverTiming.WINDOW_MS + 1
            HandOverTiming.step("too late")
            assertEquals(
                listOf("+0 ms tap: PLAY_ENTRY", "+40 ms the play queued", "+840 ms the first PLAYING socket event", "+0 ms (no tap) a step"),
                lines,
            )
        } finally {
            HandOverTiming.sink = {}
            HandOverTiming.clock = { System.nanoTime() / 1_000_000 }
        }
    }
}
