package me.vibb.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

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

/** Reading what plays: the queue, the current item and their metadata (`COMMAND_GET_MEDIA_ITEMS_METADATA` is an old name of `COMMAND_GET_METADATA`). */
val READ_COMMANDS = intArrayOf(
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
)

/**
 * What a controller of another app may do with the player (security review 2026-10-21 S-L2): [base] without setting
 * or changing the media items - an app that could hand the session its own items would play any public URL in the
 * kid's player. A [trusted] controller (the launcher, signed as this app; the system UI and Bluetooth through the
 * platform session, or an app with notification access - `ControllerInfo.isTrusted`) also reads what plays; any other app gets the transport only - play,
 * pause, skip - without the queue, the current item or their metadata ([READ_COMMANDS]).
 */
@OptIn(UnstableApi::class)
fun foreignPlayerCommands(base: Player.Commands, trusted: Boolean): Player.Commands =
    base.buildUpon().removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
        .apply { if (!trusted) removeAll(*READ_COMMANDS) }.build()

/** Only this app's own controllers may hand the session media items (S-L2). */
fun mayChangeItems(controllerPackage: String, ownPackage: String): Boolean = controllerPackage == ownPackage
