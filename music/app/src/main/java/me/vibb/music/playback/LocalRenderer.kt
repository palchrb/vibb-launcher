package me.vibb.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
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
import me.vibb.music.logic.save
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

        override fun onPlayerError(error: PlaybackException) = emit(RendererEvent.Error(MediaCache.errorCode(error)))

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
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun prepare() = exo.prepare()
    override fun seekTo(index: Int, ms: Long) = exo.seekTo(index, ms)
    override fun seekTo(ms: Long) = exo.seekTo(ms)
    override fun clear() = exo.clearMediaItems()

    /** Store where the current item is (its position, the entry's bookmark, the now-playing queue). */
    override fun saveNow(force: Boolean) {
        val a = queue() ?: return
        val index = exo.currentMediaItemIndex
        val item = a.items.getOrNull(index) ?: return
        if (exo.mediaItemCount == 0) return
        val pos = exo.currentPosition
        val duration = exo.duration.takeIf { it > 0 } ?: a.durations[item.key] ?: item.durationMs
        val (rec, bookmark) = save(item.key, pos, duration)
        a.positions[item.key] = rec
        val now = System.currentTimeMillis()
        val position = PositionRow(a.entry.key, item.key, rec.posMs, rec.done, now)
        val mark = BookmarkRow(a.entry.key, bookmark.itemKey, bookmark.posMs, now)
        val stored = NowPlayingRow(0, a.entry.id, a.items.joinToString("\n") { it.key }, index, pos, duration, a.shuffled, now)
        Resumable.set(context, true)
        val write: suspend () -> Unit = {
            store.db.upsertPosition(position)
            store.db.upsertBookmark(mark)
            store.db.upsertNowPlaying(stored)
        }
        if (force) runBlocking(writer) { write() } else scope.launch(writer) { write() }
    }

    override fun setFadeGain(gain: Float) {
        exo.volume = gain
    }

    override fun setAudioAttributes(attributes: AudioAttributes) = exo.setAudioAttributes(attributes, true)
    override fun setWakeMode(wakeMode: Int) = exo.setWakeMode(wakeMode)
    override fun setPreferredAudioDevice(device: AudioDeviceInfo?) = exo.setPreferredAudioDevice(device)
    override fun release() = exo.release()
}
