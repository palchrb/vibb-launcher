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

/**
 * What a controller of another app may do with the player (security review 2026-10-21 S-L2): [base] without setting
 * or changing the media items - the system UI, the lock screen, Bluetooth and the launcher's card use the transport
 * only, and an app that could hand the session its own items would play any public URL in the kid's player. Reading
 * what plays stays (the platform session publishes it anyway).
 */
fun foreignPlayerCommands(base: Player.Commands): Player.Commands =
    base.buildUpon().removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS).build()

/** Only this app's own controllers may hand the session media items (S-L2). */
fun mayChangeItems(controllerPackage: String, ownPackage: String): Boolean = controllerPackage == ownPackage
