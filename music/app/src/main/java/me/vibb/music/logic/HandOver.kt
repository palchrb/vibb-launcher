package me.vibb.music.logic

import me.vibb.music.playback.RendererState

/*
 * The player's line while a play is on its way (PLAN "Connecting…", the user, 2026-10-25: "så barnet ikke tror den
 * bare henger"): from the tap until the renderer reports playing the seek line's times give way to a quiet line -
 * "Kobler til høyttaleren…" while a Sonos room takes it (every source; a Spotify one too once design 54-a2 lets it reach
 * a room), "Kobler til…" while the phone's Spotify web player starts (qa-music-r13-code Q-3, the user's decision: the
 * page, the SDK, the token, the Connect device). No string names a source (design 26c step 8, `SourceScanTest`). Then
 * the usual times; a play that fails ends it and the existing failure line or message shows (no timeout of its own).
 * Pure; HandOverTest.
 */

/** What the player shows under the seek bar. */
enum class TimeRow { TIMES, CONNECTING_SPEAKER, CONNECTING }

/**
 * [onRoom]: a Sonos room renders; [connecting]: `PlayerHub.State.connecting` (the room controller's play on its way, or
 * the web player's start); [tapWaiting]: a tap's instant switch is held while the service still has its command
 * waiting (`PlayerHub.State.waiting` - a room's look or switch comes before the play); [spotify]: the shown entry is a
 * Spotify one (on the phone only its web player has a start to wait for); [rendererState]: the renderer's own line
 * (unreachable, grouped away, "Trenger internett", ...) wins - the times come back under it.
 */
fun timeRow(onRoom: Boolean, connecting: Boolean, tapWaiting: Boolean, spotify: Boolean, rendererState: RendererState?): TimeRow = when {
    rendererState != null -> TimeRow.TIMES
    onRoom && (connecting || tapWaiting) -> TimeRow.CONNECTING_SPEAKER
    !onRoom && (connecting || (tapWaiting && spotify)) -> TimeRow.CONNECTING
    else -> TimeRow.TIMES
}
