package me.vibb.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.vibb.music.R
import me.vibb.music.data.BookmarkRow
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.PositionRow
import me.vibb.music.data.ShuffleRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.Playability
import me.vibb.music.logic.QueuePlan
import me.vibb.music.logic.SLEEP_FADE_MS
import me.vibb.music.logic.TransportAction
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.coverTapPlan
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.nextAction
import me.vibb.music.logic.overlapStart
import me.vibb.music.logic.pickPlan
import me.vibb.music.logic.playability
import me.vibb.music.logic.previousAction
import me.vibb.music.logic.publicOnly
import me.vibb.music.logic.recordPosition
import me.vibb.music.logic.restoredQueue
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.resumeOverlapMs
import me.vibb.music.logic.seekGate
import me.vibb.music.logic.settledPosition
import me.vibb.music.logic.showsPlaying
import me.vibb.music.logic.sleepVolume
import me.vibb.music.logic.toggledQueue
import me.vibb.music.media.GuardedMediaSourceFactory
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.sync.ItemErrors
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.netCaps
import me.vibb.music.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * The one player (design 21 §4.6): Media3 ExoPlayer behind a [MediaSession] in a
 * [MediaSessionService] (FGS `mediaPlayback`, started only by a tap or a media command - QA #1).
 * Audio focus pauses for a call and resumes after it, becoming-noisy pauses, headset and AVRCP
 * buttons come through the session. The queue rules (logic/Queue.kt, Transport.kt, Bookmarks.kt)
 * are vibb's; this class wires them to the player:
 * - positions per item on pause, item change, every 15 s and when the task goes; a queue played to
 *   its end drops the bookmark (done marks stay); the last queue is stored and comes back paused;
 * - an item that can't play now ("Ikke lastet ned - trenger Wi-Fi") is skipped, the queue moves on;
 * - a failed stream of a listed url goes into `item_errors` (21b §6);
 * - the sleep timer fades out over ~10 s and pauses; a manual pause cancels it;
 * - "Spill av på": the phone or a connected Bluetooth/wired output (`setPreferredAudioDevice`)
 *   until that device goes. Nothing here ever starts playback by itself.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** The queue the player holds, in play order (or shuffled). */
    private class Active(
        val entry: Entry,
        /** The entry's playable items in play order (for shuffle off and the list). */
        val order: List<PlayItem>,
        var items: List<PlayItem>,
        val positions: MutableMap<String, ItemPosition>,
        val copies: Set<String>,
        val downloads: Map<String, DownloadRow>,
        var shuffled: Boolean,
    )

    private var active: Active? = null
    private var lastIndex = -1
    private var sleepEndsAt: Long? = null
    private var sleepMinutes: Int? = null
    private var sleepPausing = false
    private var faultAt: Long? = null
    private val faults = ArrayDeque<Long>()
    private var chosenOutput: Int? = null

    /** The position display holds at a resume target until the seek lands (vibb `_settle_position`). */
    private var resumeTarget = 0L
    private var resumeAt = 0L
    private var userSeeked = false

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(GuardedMediaSourceFactory(this))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, KidPlayer(exo)).setCallback(callback).setSessionActivity(open).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.music_glyph_music_note) })
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        PlayerHub.position = { settledPosition(exo.currentPosition, resumeTarget, SystemClock.elapsedRealtime() - resumeAt, userSeeked) }
        publish()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition(blocking = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePosition(blocking = true)
        handler.removeCallbacksAndMessages(null)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        PlayerHub.position = null
        PlayerHub.mutableState.value = PlayerHub.State()
        session?.release()
        session = null
        exo.release()
        scope.cancel()
        writer.close()
        super.onDestroy()
    }

    /** Previous/next follow vibb (wrapping, the long-form rule) for every controller - the app, the
     * notification, headset and Bluetooth buttons. */
    private inner class KidPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()
        override fun isCommandAvailable(command: Int): Boolean = command in extra || super.isCommandAvailable(command)
        override fun hasNextMediaItem(): Boolean = mediaItemCount > 1
        override fun hasPreviousMediaItem(): Boolean = mediaItemCount > 1
        override fun seekToNext() = skipNext()
        override fun seekToNextMediaItem() = skipNext()
        override fun seekToPrevious() = skipPrevious()
        override fun seekToPreviousMediaItem() = skipPrevious()

        override fun seekTo(positionMs: Long) {
            userSeeked = true
            super.seekTo(positionMs)
        }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply { Commands.ALL.forEach { add(it) } }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                Commands.PLAY_ENTRY -> scope.launch { playEntry(args.getLong(Commands.ARG_ENTRY)) }
                Commands.PLAY_ITEM -> scope.launch { playItem(args.getLong(Commands.ARG_ENTRY), args.getString(Commands.ARG_KEY).orEmpty()) }
                Commands.RESUME -> scope.launch { resumeLast() }
                Commands.SHUFFLE -> toggleShuffle()
                Commands.SLEEP -> setSleep(args.getInt(Commands.ARG_MINUTES))
                Commands.OUTPUT -> setOutput(args.getInt(Commands.ARG_OUTPUT, Output.PHONE_ID))
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** A play button with nothing loaded (Bluetooth after a restart): the stored queue, at its place. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val restored = restore()
                if (restored == null) {
                    future.setException(UnsupportedOperationException("nothing to resume"))
                } else {
                    val (a, index, pos) = restored
                    active = a
                    lastIndex = index
                    future.set(MediaSession.MediaItemsWithStartPosition(a.items.map { mediaItem(a, it) }, index, pos))
                }
            }
            return future
        }
    }

    // --- starting a queue ---

    private suspend fun load(): Pair<Snapshot, List<DownloadRow>> = withContext(Dispatchers.IO) { store.snapshot() to store.db.downloads() }

    private fun activeFor(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, keys: List<String>, shuffled: Boolean): Active? {
        val order = snap.order(entry)
        val byKey = order.associateBy { it.key }
        val items = keys.mapNotNull { byKey[it] }
        if (items.isEmpty()) return null
        return Active(
            entry, order, items,
            snap.positions[entry.key].orEmpty().toMutableMap(),
            snap.onPhone[entry.id].orEmpty(),
            rows.filter { it.entryId == entry.id && it.done }.associateBy { it.itemKey },
            shuffled,
        )
    }

    private suspend fun playEntry(entryId: Long) {
        val current = active
        if (current != null && current.entry.id == entryId && exo.mediaItemCount > 0) {
            exo.play() // the cover of what is loaded continues it
            return
        }
        val (snap, rows) = load()
        val entry = snap.entries.firstOrNull { it.id == entryId } ?: return
        val shuffle = canShuffle(entry) && entry.key in snap.shuffle
        val plan = coverTapPlan(snap.order(entry).map { it.key }, entry.resume, snap.bookmarks[entry.key], shuffle) ?: return
        start(activeFor(snap, rows, entry, plan.keys, shuffle) ?: return, plan)
    }

    private suspend fun playItem(entryId: Long, key: String) {
        savePosition()
        val (snap, rows) = load()
        val entry = snap.entries.firstOrNull { it.id == entryId } ?: return
        val shuffle = canShuffle(entry) && entry.key in snap.shuffle
        val current = active?.takeIf { it.entry.id == entryId && it.shuffled }?.items?.map { it.key }
        val plan = pickPlan(snap.order(entry).map { it.key }, key, entry.resume, snap.positions[entry.key]?.get(key), if (shuffle) current else null) ?: return
        start(activeFor(snap, rows, entry, plan.keys, shuffle) ?: return, plan)
    }

    private suspend fun resumeLast() {
        if (active != null && exo.mediaItemCount > 0) {
            exo.play()
            return
        }
        val (a, index, pos) = restore() ?: return
        start(a, QueuePlan(a.items.map { it.key }, index, pos))
    }

    /** The stored queue (21 §4.4: back after a restart, paused until a tap). */
    private suspend fun restore(): Triple<Active, Int, Long>? {
        val row = withContext(Dispatchers.IO) { store.db.nowPlaying() } ?: return null
        val (snap, rows) = load()
        val entry = snap.entries.firstOrNull { it.id == row.entryId } ?: return null
        val stored = row.keys.split('\n').filter { it.isNotEmpty() }
        val (keys, index) = restoredQueue(stored, row.index, snap.order(entry).map { it.key }.toSet()) ?: return null
        val a = activeFor(snap, rows, entry, keys, row.shuffled) ?: return null
        val pos = if (keys[index] == stored.getOrNull(row.index)) row.posMs else startPos(a, index)
        return Triple(a, index, pos)
    }

    private fun start(a: Active, plan: QueuePlan) {
        savePosition()
        var index = plan.startIndex.coerceIn(a.items.indices)
        var pos = plan.startPosMs
        val first = playableFrom(a, index, forward = true, wrap = true)
        if (first == null) {
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            return
        }
        if (first != index) {
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            index = first
            pos = startPos(a, index)
        }
        active = a
        lastIndex = index
        holdAt(pos)
        exo.setMediaItems(a.items.map { mediaItem(a, it) }, index, pos)
        exo.prepare()
        exo.play()
        startedPlaying(a, index)
        publish()
    }

    /** Where item [index] starts when the queue jumps to it: its own position when resume is on. */
    private fun startPos(a: Active, index: Int): Long =
        if (a.entry.resume) seekGate(resumeFrom(a.positions[a.items[index].key])) else 0

    private fun playable(a: Active, index: Int): Boolean {
        val item = a.items[index]
        val hasCopy = item.fileId != null || item.key in a.copies
        return playability(hasCopy, item.url != null, netCaps(this), store.mobileData).let { it == Playability.LOCAL || it == Playability.STREAM }
    }

    /** The first index from [index] on (or back) that can play now; null when none. */
    private fun playableFrom(a: Active, index: Int, forward: Boolean, wrap: Boolean): Int? {
        val n = a.items.size
        for (step in 0 until n) {
            val i = if (forward) index + step else index - step
            val j = if (wrap) i.mod(n) else i
            if (j !in 0 until n) return null
            if (playable(a, j)) return j
        }
        return null
    }

    private fun mediaItem(a: Active, item: PlayItem): MediaItem {
        val b = MediaItem.Builder().setMediaId("${a.entry.id}/${item.key}")
        val download = a.downloads[item.key]
        if (item.fileId != null) {
            b.setUri(Uri.fromFile(store.ownFile(item.fileId)))
        } else {
            // A copy plays from what was fetched (also when the source withdrew the episode).
            val url = download?.url ?: item.url.orEmpty()
            val hls = download?.hls ?: item.hls
            val cacheKey = itemCacheKey(a.entry.key, item.key)
            b.setUri(url)
            // A public entry's audio never comes from a private address (21b §2.7, QA Low 14).
            if (!publicOnly(a.entry)) b.setTag(GuardedMediaSourceFactory.ALLOW_PRIVATE)
            if (hls) {
                b.setMimeType(MimeTypes.APPLICATION_M3U8)
                MediaCache.registerHls(cacheKey, url)
                download?.variant?.let { b.setStreamKeys(listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it))) }
            } else {
                b.setCustomCacheKey(cacheKey)
            }
        }
        val meta = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(a.entry.name)
            .setAlbumTitle(a.entry.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        artwork(item.artHash ?: a.entry.cover)?.let { meta.setArtworkUri(it) }
        item.durationMs?.let { meta.setDurationMs(it) }
        return b.setMediaMetadata(meta.build()).build()
    }

    /** A ≤ 300 px copy of a cover, for the media notification and the launcher's media views. */
    private fun artwork(hash: String?): Uri? {
        if (hash == null) return null
        val small = File(cacheDir, "art/$hash.jpg")
        if (!small.exists()) {
            val src = store.coverFile(hash).takeIf { it.exists() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
            val bitmap = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > 300) Bitmap.createScaledBitmap(bitmap, 300, 300 * bitmap.height / bitmap.width, true) else bitmap
            small.parentFile?.mkdirs()
            runCatching { small.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } }.onFailure { small.delete(); return null }
        }
        return Uri.fromFile(small)
    }

    private fun holdAt(posMs: Long) {
        resumeTarget = posMs
        resumeAt = SystemClock.elapsedRealtime()
        userSeeked = false
    }

    // --- transport ---

    private fun skipNext() {
        val a = active ?: return
        when (val action = nextAction(exo.currentMediaItemIndex, exo.mediaItemCount)) {
            is TransportAction.GoTo -> goTo(a, action.index, forward = true)
            else -> Unit
        }
    }

    private fun skipPrevious() {
        val a = active ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: a.items.getOrNull(exo.currentMediaItemIndex)?.durationMs
        when (val action = previousAction(exo.currentMediaItemIndex, exo.mediaItemCount, exo.currentPosition, duration)) {
            TransportAction.Restart -> exo.seekTo(0)
            is TransportAction.GoTo -> goTo(a, action.index, forward = false)
            TransportAction.Stay -> Unit
        }
    }

    private fun goTo(a: Active, index: Int, forward: Boolean) {
        savePosition()
        val target = playableFrom(a, index, forward, wrap = true) ?: run {
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            return
        }
        if (target != index) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
        holdAt(startPos(a, target))
        exo.seekTo(target, startPos(a, target))
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
    }

    // --- shuffle, sleep, outputs ---

    private fun toggleShuffle() {
        val a = active ?: return
        if (!canShuffle(a.entry)) return
        val on = !a.shuffled
        val index = exo.currentMediaItemIndex.coerceIn(a.items.indices)
        val current = a.items[index]
        val byKey = a.order.associateBy { it.key }
        val items = toggledQueue(a.order.map { it.key }, current.key, on).mapNotNull { byKey[it] }
        // Keep the current item playing: drop the others around it, then add the new order after it.
        exo.removeMediaItems(index + 1, exo.mediaItemCount)
        exo.removeMediaItems(0, index)
        exo.addMediaItems(1, items.drop(1).map { mediaItem(a, it) })
        a.items = items
        a.shuffled = on
        lastIndex = 0
        val entryKey = a.entry.key
        scope.launch(writer) { store.db.upsertShuffle(ShuffleRow(entryKey, on)) }
        savePosition()
        publish()
    }

    private fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepTick)
        exo.volume = 1f
        if (minutes <= 0) {
            sleepEndsAt = null
            sleepMinutes = null
        } else {
            sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            sleepMinutes = minutes
            handler.post(sleepTick)
        }
        publish()
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            val end = sleepEndsAt ?: return
            val left = end - SystemClock.elapsedRealtime()
            exo.volume = sleepVolume(left)
            if (left <= 0) {
                sleepPausing = true
                exo.pause()
                sleepPausing = false
                exo.volume = 1f
                sleepEndsAt = null
                sleepMinutes = null
                savePosition()
                publish()
            } else {
                handler.postDelayed(this, if (left > SLEEP_FADE_MS + 1_000) minOf(left - SLEEP_FADE_MS, 30_000) else 250)
            }
        }
    }

    private fun outputKind(type: Int): OutputKind? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.WIRED
        else -> null
    }

    private fun outputs(): List<Output> = listOf(Output.PHONE) + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { d -> outputKind(d.type)?.let { Output(d.id, it, d.productName?.toString()?.takeIf { n -> n.isNotBlank() }) } }
        .distinctBy { it.kind to it.name }

    private fun setOutput(id: Int) {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = if (id == Output.PHONE_ID) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else devices.firstOrNull { it.id == id }
        chosenOutput = if (device == null) null else id
        exo.setPreferredAudioDevice(device)
        publish()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publish()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // The chosen device went: back to system routing.
            if (removedDevices.any { it.id == chosenOutput }) {
                chosenOutput = null
                exo.setPreferredAudioDevice(null)
            }
            publish()
        }
    }

    // --- events ---

    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 15_000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val a = active ?: return
            val index = exo.currentMediaItemIndex
            if (index !in a.items.indices) return
            val previous = a.items.getOrNull(lastIndex)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null && lastIndex != index) {
                markDone(a, previous)
            }
            lastIndex = index
            if (!playable(a, index)) {
                // The queue moves on (21 §4.2's one line beyond the mockup).
                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
                val nextIndex = playableFrom(a, index, forward = true, wrap = false)
                if (nextIndex != null) exo.seekTo(nextIndex, startPos(a, nextIndex)) else exo.pause()
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // A natural advance resumes an item heard before (vibb: in-session jumps resume too).
                val pos = startPos(a, index)
                holdAt(pos)
                if (pos > 0) exo.seekTo(index, pos)
            }
            startedPlaying(a, index)
            savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) queueEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> markFault()
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> if (!sleepPausing && sleepEndsAt != null) setSleep(0)
                }
                savePosition()
            } else {
                applyOverlap()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                markFault()
                savePosition()
            } else if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && exo.playWhenReady) {
                applyOverlap()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(saveTick)
            if (isPlaying) handler.postDelayed(saveTick, 15_000)
        }

        override fun onPlayerError(error: PlaybackException) {
            val a = active ?: return
            val index = exo.currentMediaItemIndex
            val item = a.items.getOrNull(index) ?: return
            if (item.fileId == null && item.key !in a.copies && item.url != null) {
                val code = MediaCache.errorCode(error)
                val entryId = a.entry.id
                scope.launch(writer) { ItemErrors.record(this@PlaybackService, entryId, item.key, code) }
            }
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            savePosition()
            val nextIndex = if (index + 1 < a.items.size) playableFrom(a, index + 1, forward = true, wrap = false) else null
            if (nextIndex != null) {
                exo.seekTo(nextIndex, startPos(a, nextIndex))
                exo.prepare()
                exo.play()
            } else {
                exo.pause()
                exo.prepare()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    /** An item started: it leaves Nytt (21 §4.5). */
    private fun startedPlaying(a: Active, index: Int) {
        val entryId = a.entry.id
        val key = a.items[index].key
        scope.launch(writer) {
            if (store.db.deleteNews(entryId, key) > 0) NewNotifier.update(this@PlaybackService, store.snapshot())
        }
    }

    private fun markDone(a: Active, item: PlayItem) {
        val duration = item.durationMs ?: return
        val rec = recordPosition(duration, duration)
        a.positions[item.key] = rec
        val entryKey = a.entry.key
        scope.launch(writer) { store.db.upsertPosition(PositionRow(entryKey, item.key, rec.posMs, rec.done, System.currentTimeMillis())) }
    }

    /** The queue played to its end: the last item is done, the bookmark goes (done marks stay). */
    private fun queueEnded() {
        val a = active ?: return
        a.items.getOrNull(exo.currentMediaItemIndex)?.let { markDone(a, it) }
        active = null
        lastIndex = -1
        val entryKey = a.entry.key
        scope.launch(writer) {
            store.db.deleteBookmark(entryKey)
            store.db.clearNowPlaying()
        }
        exo.clearMediaItems()
        publish()
    }

    private fun markFault() {
        val now = SystemClock.elapsedRealtime()
        if (faultAt == null) faultAt = now
        faults.addLast(now)
        while (faults.size > 10) faults.removeFirst()
    }

    /** Back up a beat after an outage (vibb's resume overlap), never after a plain tap. */
    private fun applyOverlap() {
        val at = faultAt ?: return
        faultAt = null
        val a = active ?: return
        val now = SystemClock.elapsedRealtime()
        val overlap = resumeOverlapMs(a.entry.source == Source.OWN, now - at, faults.count { now - it < 60_000 })
        if (overlap > 0) exo.seekTo(overlapStart(exo.currentPosition, overlap))
    }

    /** Store where the current item is (its position, the entry's bookmark, the now-playing queue). */
    private fun savePosition(blocking: Boolean = false) {
        val a = active ?: return
        val index = exo.currentMediaItemIndex
        val item = a.items.getOrNull(index) ?: return
        if (exo.mediaItemCount == 0) return
        val pos = exo.currentPosition
        val duration = exo.duration.takeIf { it > 0 } ?: item.durationMs
        val rec = recordPosition(pos, duration)
        a.positions[item.key] = rec
        val now = System.currentTimeMillis()
        val position = PositionRow(a.entry.key, item.key, rec.posMs, rec.done, now)
        val bookmark = BookmarkRow(a.entry.key, item.key, pos, now)
        val queue = NowPlayingRow(0, a.entry.id, a.items.joinToString("\n") { it.key }, index, pos, duration, a.shuffled, now)
        val write: suspend () -> Unit = {
            store.db.upsertPosition(position)
            store.db.upsertBookmark(bookmark)
            store.db.upsertNowPlaying(queue)
        }
        if (blocking) runBlocking(writer) { write() } else scope.launch(writer) { write() }
    }

    private fun publish() {
        val a = active
        val outputs = outputs()
        val output = outputs.firstOrNull { it.id == chosenOutput } ?: outputs.firstOrNull { it.kind != OutputKind.PHONE } ?: Output.PHONE
        PlayerHub.mutableState.value = PlayerHub.State(
            live = a != null && exo.mediaItemCount > 0,
            entryId = a?.entry?.id,
            keys = a?.items?.map { it.key }.orEmpty(),
            index = exo.currentMediaItemIndex,
            shuffled = a?.shuffled ?: false,
            playing = showsPlaying(exo.playWhenReady, exo.playbackState == Player.STATE_ENDED),
            durationMs = exo.duration.takeIf { it > 0 } ?: a?.items?.getOrNull(exo.currentMediaItemIndex)?.durationMs,
            sleepEndsAt = sleepEndsAt,
            sleepMinutes = sleepMinutes,
            outputs = outputs,
            output = output,
        )
    }
}
