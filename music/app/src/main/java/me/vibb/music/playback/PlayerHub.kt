package me.vibb.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.vibb.music.sonos.Room

/** The app's own session commands (only our own controller may send them). */
object Commands {
    const val PLAY_ENTRY = "me.vibb.music.PLAY_ENTRY"
    const val PLAY_ITEM = "me.vibb.music.PLAY_ITEM"
    const val RESUME = "me.vibb.music.RESUME"
    const val RESTORE = "me.vibb.music.RESTORE"
    const val SHUFFLE = "me.vibb.music.SHUFFLE"
    const val SLEEP = "me.vibb.music.SLEEP"
    const val OUTPUT = "me.vibb.music.OUTPUT"

    const val ARG_ENTRY = "entry"
    const val ARG_KEY = "key"
    const val ARG_MINUTES = "minutes"
    /** An [Output.key] (a String since design 26a step 2). */
    const val ARG_OUTPUT = "output"

    /** [RESTORE]: the stored queue loaded paused, then this (one of the THEN_ values) - so the
     * player's controls work before play after a restart (qa-21-step2-code M6). */
    const val ARG_THEN = "then"
    const val ARG_POSITION = "position"
    const val THEN_NEXT = "next"
    const val THEN_PREVIOUS = "previous"
    const val THEN_SEEK = "seek"
    const val THEN_SHUFFLE = "shuffle"

    val ALL: List<SessionCommand> = listOf(PLAY_ENTRY, PLAY_ITEM, RESUME, RESTORE, SHUFFLE, SLEEP, OUTPUT).map { SessionCommand(it, Bundle.EMPTY) }

    /** The commands that start playing - refused while the app is suspended (`playAllowed`). */
    val PLAYS: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, RESUME)
}

/**
 * What the player service tells the screens (same process): the live queue, play state, the
 * sleep timer and outputs ([Output], [RendererState]: PlayerModel.kt). While no service holds a
 * queue the screens show the stored now-playing row, paused (21 §4.4).
 */
object PlayerHub {
    data class State(
        /** The service holds a queue. */
        val live: Boolean = false,
        val entryId: Long? = null,
        val keys: List<String> = emptyList(),
        val index: Int = 0,
        val shuffled: Boolean = false,
        /** The intent: "playing" shows at once on a tap. */
        val playing: Boolean = false,
        val durationMs: Long? = null,
        /** `SystemClock.elapsedRealtime()` when the sleep timer ends; null = off. */
        val sleepEndsAt: Long? = null,
        val sleepMinutes: Int? = null,
        val outputs: List<Output> = listOf(Output.PHONE),
        /** The output in use (the chosen one, else where the system routes). */
        val output: Output = Output.PHONE,
        /** Design 26a step 3 (§3.3, §4.8); null on the phone. */
        val rendererState: RendererState? = null,
        /** Design 26a step 3: the Sonos rows of "Spill av på" (`RoomDirectory.state`). */
        val rooms: List<Room> = emptyList(),
        val roomsStale: Boolean = false,
        val scanning: Boolean = false,
    )

    /** NOT_DOWNLOADED: not on the phone and no network to stream it; CANT_PLAY: it failed anyway. */
    enum class Message { NOT_DOWNLOADED, CANT_PLAY }

    internal val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState

    internal val mutableMessages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val messages: SharedFlow<Message> = mutableMessages

    /** The live position (ms), read on the main thread; null without a live queue. */
    @Volatile internal var position: (() -> Long)? = null

    /** Pauses the player (main thread); null without a player service. */
    @Volatile internal var pause: (() -> Unit)? = null

    fun positionMs(): Long? = position?.invoke()
}
