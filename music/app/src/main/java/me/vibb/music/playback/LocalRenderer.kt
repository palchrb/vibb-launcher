package me.vibb.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.vibb.music.data.BookmarkRow
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.PositionRow
import me.vibb.music.data.Store
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.HLS_MAX_BITRATE
import me.vibb.music.logic.SLEEP_FADE_MS
import me.vibb.music.logic.save
import me.vibb.music.logic.showsPlaying
import me.vibb.music.logic.sleepVolume
import me.vibb.music.media.ItemMediaSourceFactory
import me.vibb.music.media.MediaCache

/** Speech (podcasts, stories: a notification pauses rather than ducks) or music. */
internal fun audioAttributes(speech: Boolean): AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
    .build()

/**
 * The phone plays (design 26a §3.1): the Media3 ExoPlayer, its audio focus, becoming-noisy pause,
 * wake mode and output device, and the one place a position is persisted ([saveNow]). The queue
 * itself is the service's ([queue]); its writes go through [scope] on [writer].
 */
@OptIn(UnstableApi::class)
internal class LocalRenderer(
    private val context: Context,
    private val store: Store,
    private val scope: CoroutineScope,
    private val writer: CoroutineDispatcher,
    private val queue: () -> Active?,
) : Renderer {
    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(ItemMediaSourceFactory(context))
        .setAudioAttributes(audioAttributes(speech = true), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .also { exo ->
            // Design 30 §1.7: a stream plays - and so writes to the cache - the HLS rendition a download
            // keeps (`pickVariant`: the highest up to HLS_MAX_BITRATE, else the lowest), never adaptive.
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setMaxAudioBitrate(HLS_MAX_BITRATE)
                .setForceHighestSupportedBitrate(true)
                .build()
        }

    override val player: Player get() = exo
    override val kind: RendererKind get() = RendererKind.LOCAL
    override var onEvent: ((RendererEvent) -> Unit)? = null

    private fun emit(event: RendererEvent) {
        onEvent?.invoke(event)
    }

    /** ExoPlayer's callbacks, re-emitted as they come (in its order, inside the call that caused them). */
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            emit(RendererEvent.ItemChanged(exo.currentMediaItemIndex, auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> emit(RendererEvent.Ready)
                Player.STATE_ENDED -> emit(RendererEvent.Ended)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                val cause = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PauseCause.FAULT
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> PauseCause.USER
                    else -> PauseCause.OTHER
                }
                emit(RendererEvent.Paused(cause))
            } else {
                emit(RendererEvent.Resumed)
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                emit(RendererEvent.Interrupted)
            } else if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                emit(RendererEvent.InterruptionEnded)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = emit(RendererEvent.IsPlaying(isPlaying))

        override fun onPlayerError(error: PlaybackException) = emit(RendererEvent.Error(MediaCache.errorCode(error), MediaCache.gated(error)))

        override fun onEvents(player: Player, events: Player.Events) = emit(RendererEvent.StateChanged)
    }

    init {
        exo.addListener(listener)
    }

    override fun load(items: List<MediaItem>, startIndex: Int, startMs: Long, playNow: Boolean) {
        exo.setMediaItems(items, startIndex, startMs)
        exo.prepare()
        if (playNow) exo.play()
    }

    /** Keep the current item playing: drop the others around it, then add the new order after it. */
    override fun reorder(keep: Int, after: List<MediaItem>) {
        exo.removeMediaItems(keep + 1, exo.mediaItemCount)
        exo.removeMediaItems(0, keep)
        exo.addMediaItems(1, after)
    }

    override fun positionMs(): Long = exo.currentPosition
    override fun durationMs(): Long? = exo.duration.takeIf { it > 0 }
    override fun currentIndex(): Int = exo.currentMediaItemIndex
    override fun itemCount(): Int = exo.mediaItemCount
    override fun itemAt(index: Int): MediaItem? = if (index in 0 until exo.mediaItemCount) exo.getMediaItemAt(index) else null
    override fun replaceItem(index: Int, item: MediaItem) = exo.replaceMediaItem(index, item)
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun prepare() = exo.prepare()
    override fun seekTo(index: Int, ms: Long) = exo.seekTo(index, ms)
    override fun seekTo(ms: Long) = exo.seekTo(ms)
    override fun clear() = exo.clearMediaItems()

    /**
     * A Sonos room renders now (design 26a §6.7): the player back to IDLE with no queue - a paused but
     * prepared ExoPlayer keeps the audio focus it was granted (Media3 1.7.1 `AudioFocusManager`
     * abandons it only in IDLE), and the phone must hold none while the speaker plays. The next
     * [load] prepares it again.
     */
    fun standDown() {
        exo.stop()
        exo.clearMediaItems()
    }

    /** Store where the current item is (its position, the entry's bookmark, the now-playing queue). */
    override fun saveNow(force: Boolean) {
        val a = queue() ?: return
        val index = exo.currentMediaItemIndex
        val item = a.items.getOrNull(index) ?: return
        if (exo.mediaItemCount == 0) return
        val pos = exo.currentPosition
        val duration = exo.duration.takeIf { it > 0 } ?: a.durations[item.key] ?: item.durationMs
        persistPlace(context, store, scope, writer, a, index, pos, duration, duration, force)
    }

    override val showsPlaying: Boolean get() = showsPlaying(exo.playWhenReady, exo.playbackState == Player.STATE_ENDED)
    override val rendererState: RendererState? get() = null
    override val room: Output? get() = null

    private val handler = Handler(Looper.getMainLooper())
    private var fade: Runnable? = null

    override val fadeMs: Long get() = SLEEP_FADE_MS

    /** Today's fade (design 21 §4.6): the gain down over [fadeMs] in 250 ms steps, then pause, gain back to 1. */
    override fun fadeOutAndPause(done: () -> Unit) {
        cancelFade()
        val start = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val left = fadeMs - (SystemClock.elapsedRealtime() - start)
                exo.volume = sleepVolume(left)
                if (left > 0) {
                    handler.postDelayed(this, FADE_STEP_MS)
                    return
                }
                fade = null
                exo.pause()
                exo.volume = 1f
                done()
            }
        }
        fade = tick
        handler.post(tick)
    }

    override fun cancelFade() {
        fade?.let { handler.removeCallbacks(it) }
        fade = null
        exo.volume = 1f
    }

    override fun setAudioAttributes(attributes: AudioAttributes) = exo.setAudioAttributes(attributes, true)
    override fun setWakeMode(wakeMode: Int) = exo.setWakeMode(wakeMode)
    override fun setPreferredAudioDevice(device: AudioDeviceInfo?) = exo.setPreferredAudioDevice(device)
    override fun release() {
        cancelFade()
        exo.release()
    }

    private companion object {
        const val FADE_STEP_MS = 250L
    }
}

/**
 * Persist where item [index] of [a] is: its position (`Bookmarks.save`, "heard to the end" decided
 * by [durationMs]), the entry's bookmark and the stored now-playing queue ([shownDurationMs]). The one
 * write behind `Renderer.saveNow` for both renderers (design 26a §3.1, QA H3); with [force] it lands
 * before this returns.
 */
internal fun persistPlace(
    context: Context,
    store: Store,
    scope: CoroutineScope,
    writer: CoroutineDispatcher,
    a: Active,
    index: Int,
    posMs: Long,
    durationMs: Long?,
    shownDurationMs: Long?,
    force: Boolean,
) {
    val item = a.items.getOrNull(index) ?: return
    val now = System.currentTimeMillis()
    val stored = NowPlayingRow(0, a.entry.id, a.items.joinToString("\n") { it.key }, index, posMs, shownDurationMs, a.shuffled, now)
    val place = place(a.entry.key, item.key, posMs, durationMs, now, stored)
    a.positions[item.key] = place.record
    val write: suspend () -> Unit = { writePlace(context, store, place) }
    if (force) runBlocking(writer) { write() } else scope.launch(writer) { write() }
}

/** One place as its rows: the item's position ([record], `Bookmarks.save`), the entry's bookmark, the stored queue's row. */
internal class Place(val record: ItemPosition, val position: PositionRow, val bookmark: BookmarkRow, val queue: NowPlayingRow?)

/** [posMs] of [itemKey] in entry [entryKey] as a [Place]; [durationMs] decides "heard to the end". */
internal fun place(entryKey: String, itemKey: String, posMs: Long, durationMs: Long?, now: Long, queue: NowPlayingRow?): Place {
    val (rec, bookmark) = save(itemKey, posMs, durationMs)
    return Place(rec, PositionRow(entryKey, itemKey, rec.posMs, rec.done, now), BookmarkRow(entryKey, bookmark.itemKey, bookmark.posMs, now), queue)
}

/**
 * The ONE write of a place (qa-26a-step3b-code L6): [persistPlace] (both renderers' `saveNow`) and
 * the suspension's standalone bookmark (`SonosSuspension`) - the position, the bookmark, the stored
 * queue's row, and "something to resume" for the media buttons.
 */
internal suspend fun writePlace(context: Context, store: Store, place: Place) {
    Resumable.set(context, true)
    store.db.upsertPosition(place.position)
    store.db.upsertBookmark(place.bookmark)
    place.queue?.let { store.db.upsertNowPlaying(it) }
}
