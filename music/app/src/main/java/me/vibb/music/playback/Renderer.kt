package me.vibb.music.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Who makes the sound (design 26a §0): the phone's own player, or a Sonos room. */
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

    /** The MediaItem the service built for queue index [index] (its metadata), or null. */
    fun itemAt(index: Int): MediaItem?

    /**
     * Item [index]'s metadata swapped in place (design 27, QA H2: its picture landed after the queue
     * was built): the same item, so playback goes on and nothing is sent to a speaker; the session's
     * player - the notification, the lock's tile, Home's card - shows the new art.
     */
    fun replaceItem(index: Int, item: MediaItem)
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

    /**
     * The play/pause button, the launcher's `setNowPlaying` and screen time (design 26a §3.3): the
     * phone shows the intent (play-when-ready, not ended); a Sonos room only a fresh, reachable,
     * PLAYING stream of ours - so a stale snapshot shows "not playing" while the player itself holds
     * play-when-ready through a transient (§3.2).
     */
    val showsPlaying: Boolean

    /** Why a remote renderer isn't playing what we sent it (the player's one quiet line); null on the phone. */
    val rendererState: RendererState?

    /** The Sonos room this renderer plays in, as the player's output (`sonos:<uid>`); null on the phone. */
    val room: Output?

    /** How long [fadeOutAndPause] takes: the sleep timer starts it this long before its end. */
    val fadeMs: Long

    /**
     * The sleep timer's end (design 26a §5.2): fade out over [fadeMs], pause, and call [done] on the
     * main thread. The phone fades its own gain over 10 s; a Sonos room steps `SetVolume` three times,
     * pauses and puts the volume back. [cancelFade] stops a fade in progress (a pause, a new timer).
     */
    fun fadeOutAndPause(done: () -> Unit)
    fun cancelFade()

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

    /**
     * The current item failed, with the `item_errors` code (`MediaCache.errorCode`); [gated]: the phone's
     * streaming gate refused it (design 30 §1.6, `MediaCache.StreamGate`) - not the item's fault.
     */
    data class Error(val code: String, val gated: Boolean = false) : RendererEvent

    /** The renderer can't play at all (a Sonos room's failed start, design 26a §4.2): back to the phone, paused. */
    data object Lost : RendererEvent

    /** After every batch of the events above (Media3's `onEvents`): time to publish. */
    data object StateChanged : RendererEvent
}

/** [FAULT]: becoming noisy or a loss of audio focus (vibb's resume overlap); [USER]: a pause asked for. */
enum class PauseCause { USER, FAULT, OTHER }
