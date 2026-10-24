package me.vibb.music.playback

import androidx.media3.common.Player

/**
 * The listeners `KidPlayer` gave out (r12 emulator round E-1): each wrapped in a [KidCommandsListener] - every event
 * forwarded unchanged, `onAvailableCommandsChanged` with [commands] (`KidPlayer.getAvailableCommands`) instead of the
 * wrapped ExoPlayer's - and a removal finds the wrapper it added. Main thread (Media3's player).
 */
internal class KidListeners(private val commands: () -> Player.Commands) {
    private val wrappers = HashMap<Player.Listener, KidCommandsListener>()

    fun wrap(listener: Player.Listener): Player.Listener = wrappers.getOrPut(listener) { KidCommandsListener(listener, commands) }

    fun unwrap(listener: Player.Listener): Player.Listener = wrappers.remove(listener) ?: listener
}
