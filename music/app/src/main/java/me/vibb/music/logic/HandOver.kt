package me.vibb.music.logic

import me.vibb.music.playback.RendererState

/*
 * The player's line while a Sonos room takes a play (PLAN "Connecting…", the user, 2026-10-25: "så barnet ikke tror
 * den bare henger"): from the tap until the room reports PLAYING the seek line's times give way to a quiet
 * "Kobler til høyttaleren…" - for every source, a Spotify one too once design 54-a2 lets it reach a room (no string
 * names a source: design 26c step 8, `SourceScanTest`). Then the usual times; a play that fails ends the hand-over and
 * the existing failure line or message shows (no timeout of its own). Pure; HandOverTest.
 */

/** What the player shows under the seek bar. */
enum class TimeRow { TIMES, CONNECTING }

/**
 * [onRoom]: a Sonos room renders; [connecting]: `PlayerHub.State.connecting` (the controller's play on its way to the
 * room); [tapWaiting]: a tap's instant switch is held while the service still has its command waiting
 * (`PlayerHub.State.waiting` - a room's look or switch comes before the play); [rendererState]: the room's own line
 * (unreachable, grouped away, taken over, lost) wins - the times come back under it.
 */
fun timeRow(onRoom: Boolean, connecting: Boolean, tapWaiting: Boolean, rendererState: RendererState?): TimeRow =
    if (onRoom && rendererState == null && (connecting || tapWaiting)) TimeRow.CONNECTING else TimeRow.TIMES
