package me.vibb.music.logic

import me.vibb.music.media.MediaCache
import me.vibb.music.playback.PlayerHub
import java.net.UnknownHostException

/*
 * A failed item's words and its log line (PLAN "Round M1", the incident's diagnostics (1) and (2)). Media3 logs a fixed
 * "UnknownHostException (no network)" for any name failure (and android.util.Log prints no stack for one), so the
 * player logs the real cause itself; and a name that can't be looked up says "Trenger internett" instead of "Kan ikke
 * spilles nå". Pure; PlayFailureTest.
 */

/** [error] and its causes, at most eight (a cause cycle never loops; qa-music-splash-b Q-10). */
private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }.take(8)

/**
 * Whether [error] failed for the network rather than the item: a name that couldn't be looked up anywhere in the cause
 * chain (no network, or the phone's DNS - the launcher's filter's upstream - not answering). Only that: a refused
 * connect (`ConnectException`) or a timeout (`SocketTimeoutException`) also come from a server that is down or slow
 * while the network works, so they keep today's words; a phone with no network at all never gets here (the streaming
 * gate says "Ikke lastet ned" first). Not the guard's [MediaCache.PrivateAddressException] (an `UnknownHostException`
 * by type, but a decision about the item: a public entry on a private address, 21b §2.7; qa-music-splash-b Q-2). The
 * accepted residual (Q-4): a host the parent's Web Filter blocks fails the same way (NODATA), so it reads "Trenger
 * internett" too - the parent blocked it, and the kid can do nothing either way.
 */
fun noNetwork(error: Throwable): Boolean =
    causes(error).any { it is UnknownHostException && it !is MediaCache.PrivateAddressException }

/** The player's line for a failed item: the streaming gate's [offline] first, then [noNetwork], else today's words. */
fun failureMessage(offline: Boolean, noNetwork: Boolean): PlayerHub.Message = when {
    offline -> PlayerHub.Message.NOT_DOWNLOADED
    noNetwork -> PlayerHub.Message.NO_NETWORK
    else -> PlayerHub.Message.CANT_PLAY
}

/**
 * The log line of a failed item: Media3's error code by name and number, then every cause's class and message (at most
 * eight). A URL's query and user info are cut from the messages (a minted Storytel URL's `token=` is a bearer).
 */
fun failureLine(codeName: String, code: Int, error: Throwable): String =
    "Playback failed: $codeName ($code): " + causes(error).joinToString(" <- ") { e ->
        e.javaClass.name + (e.message?.let { ": " + redactUrls(it) } ?: "")
    }

private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")

private fun redactUrls(text: String): String = URL.replace(text) { redactUri(it.value) }
