package me.vibb.music.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Who makes the sound (design 26a §0): the phone's own player, or a Sonos room (step 3). */
enum class RendererKind { LOCAL, SONOS }

/**
 * What [PlaybackService] talks to (design 26a §3.1), apart from the output axis (where the phone's
 * own player writes audio). The session wraps [player] in `KidPlayer`, so its gating and skip
 * routing hold whoever plays. Main thread only. [LocalRenderer] is the phone's ExoPlayer.
 */
interface Renderer {
    /** What the session wraps (`KidPlayer(renderer.player)`). */
    val player: Player
    val kind: RendererKind

    /**
     * Where [RendererEvent]s go, set only by the service and only on the current renderer (null once
     * detached). Called on the main thread, synchronously: the local one from inside the ExoPlayer call
     * that caused the event, a polled one from its main-thread post of a poll result. The service's
     * handler may re-enter the renderer (seek, pause, play, prepare, save) from inside the call, so a
     * renderer must tolerate that (step 3's `SimpleBasePlayer`: emit outside its own handler futures).
     */
    var onEvent: ((RendererEvent) -> Unit)?

    /** A new queue at [startIndex], [startMs]; it plays only with [playNow]. */
    fun load(items: List<MediaItem>, startIndex: Int, startMs: Long, playNow: Boolean)

    /** Shuffle on or off: item [keep] plays on as the queue's first, [after] follows it. */
    fun reorder(keep: Int, after: List<MediaItem>)

    /** For display and the controls only; persisting goes through [saveNow] (design 26a QA H3). */
    fun positionMs(): Long

    /** The current item's duration once the renderer knows it, else null. */
    fun durationMs(): Long?
    /**
     * Indexes the queue passed to [load] and [reorder], i.e. the service's `Active.items`: a renderer
     * never filters it (the speaker-playable filter belongs where the queue is built, the renderer
     * passed explicitly, design 26a §9).
     */
    fun currentIndex(): Int
    fun itemCount(): Int
    fun play()
    fun pause()

    /** Make a loaded queue ready again (after an error, or a seek while idle). */
    fun prepare()
    fun seekTo(index: Int, ms: Long)

    /** A seek within the current item. */
    fun seekTo(ms: Long)
    fun clear()

    /**
     * The ONLY persistence path for where the current item is (its position, the entry's bookmark,
     * the stored queue). With [force] the write lands before this returns (the task or the service
     * is going).
     */
    fun saveNow(force: Boolean = false)

    /** The sleep timer's fade (0..1): a local gain only; a remote renderer ignores it (its fade is
     * `fadeOutAndPause`, step 3a), so the speaker's volume never sees these steps. */
    fun setFadeGain(gain: Float)

    // The phone's own player only; a remote renderer ignores them (design 26a §3.2).
    fun setAudioAttributes(attributes: AudioAttributes)
    fun setWakeMode(wakeMode: Int)
    fun setPreferredAudioDevice(device: AudioDeviceInfo?)
    fun release()
}

/** What a renderer reports back (the local one: its ExoPlayer's `Player.Listener` callbacks). */
sealed interface RendererEvent {
    /** Another item is current; [auto] = the previous one played to its end. */
    data class ItemChanged(val index: Int, val auto: Boolean) : RendererEvent

    /** Ready to play (the current item's duration is known, if it ever will be). */
    data object Ready : RendererEvent

    /** The queue played to its end. */
    data object Ended : RendererEvent

    /** It stopped wanting to play, and why. */
    data class Paused(val cause: PauseCause) : RendererEvent

    /** It wants to play again. */
    data object Resumed : RendererEvent

    /** A transient loss of audio focus (a notification sound) holds it; [InterruptionEnded] when it lets go. */
    data object Interrupted : RendererEvent
    data object InterruptionEnded : RendererEvent
    data class IsPlaying(val playing: Boolean) : RendererEvent

    /** The current item failed, with the `item_errors` code (`MediaCache.errorCode`). */
    data class Error(val code: String) : RendererEvent

    /** After every batch of the events above (Media3's `onEvents`): time to publish. */
    data object StateChanged : RendererEvent
}

/** [FAULT]: becoming noisy or a loss of audio focus (vibb's resume overlap); [USER]: a pause asked for. */
enum class PauseCause { USER, FAULT, OTHER }
