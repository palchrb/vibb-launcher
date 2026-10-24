package me.vibb.music.playback

import androidx.media3.common.Player

/**
 * The session's view of what `KidPlayer` offers (r12 emulator round E-1). Media3's `ForwardingPlayer` hands a listener
 * the *wrapped* player's commands in `onAvailableCommandsChanged`, and the session sends exactly those to every
 * controller (the app's own, the launcher's lock tile, the notification). ExoPlayer drops `COMMAND_SEEK_TO_NEXT` on a
 * queue's last item - a favourites run of one item, or an entry's last item that wraps (vibb's rule) - so a controller's
 * next was dropped before it ever reached `KidPlayer.seekToNext`, while a headset's media button (the session's own
 * path) still worked. Each listener gets [commands] - `KidPlayer.getAvailableCommands`, with its own additions and
 * refusals - instead.
 */
internal class KidCommandsListener(
    val inner: Player.Listener,
    private val commands: () -> Player.Commands,
) : Player.Listener by inner {
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(commands())
}

/** The listeners a forwarding player gave out, so a removal finds the wrapper it added. Main thread (Media3's player). */
internal class KidListeners(private val commands: () -> Player.Commands) {
    private val wrappers = HashMap<Player.Listener, KidCommandsListener>()

    fun wrap(listener: Player.Listener): Player.Listener = wrappers.getOrPut(listener) { KidCommandsListener(listener, commands) }

    fun unwrap(listener: Player.Listener): Player.Listener = wrappers.remove(listener) ?: listener
}
