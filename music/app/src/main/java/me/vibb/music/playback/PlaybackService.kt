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
import androidx.media3.session.SessionError
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
import me.vibb.music.bridge.LiveBridge
import me.vibb.music.bridge.NowPlayingKeys
import me.vibb.music.data.BookmarkRow
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.NewsRow
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.PositionRow
import me.vibb.music.data.ShuffleRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.Playability
import me.vibb.music.logic.QueuePlan
import me.vibb.music.logic.SLEEP_FADE_MS
import me.vibb.music.logic.TransportAction
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.coverTapPlan
import me.vibb.music.logic.finished
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.nextAction
import me.vibb.music.logic.nowWindow
import me.vibb.music.logic.overlapStart
import me.vibb.music.logic.pickPlan
import me.vibb.music.logic.playAllowed
import me.vibb.music.logic.playability
import me.vibb.music.logic.playedFromTray
import me.vibb.music.logic.previousAction
import me.vibb.music.logic.publicOnly
import me.vibb.music.logic.restoredQueue
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.resumeOverlapMs
import me.vibb.music.logic.save
import me.vibb.music.logic.seekGate
import me.vibb.music.logic.showsPlaying
import me.vibb.music.logic.sleepVolume
import me.vibb.music.logic.streamAllowed
import me.vibb.music.logic.toggledQueue
import me.vibb.music.media.ItemMediaSourceFactory
import me.vibb.music.media.ItemTag
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.sync.ArtWants
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
 *   its end drops the bookmark and the partial positions (`clear_state`, done marks stay); the last
 *   queue is stored and comes back paused - its controls load it paused first;
 * - a cover tap on the entry already loaded continues it, as vibb's tile press (never restarts);
 * - an item that can't play now ("Ikke lastet ned - trenger Wi-Fi") is skipped, the queue moves on;
 * - a failed stream of a listed url goes into `item_errors` (21b §6), a broken copy is dropped so
 *   the next sync fetches it again;
 * - the sleep timer fades out over ~10 s and pauses; a manual pause cancels it;
 * - "Spill av på": the phone or a connected Bluetooth/wired output (`setPreferredAudioDevice`)
 *   until that device goes - or, for the phone, until headphones arrive.
 * Nothing here ever starts playback by itself.
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
        val copies: MutableSet<String>,
        val downloads: MutableMap<String, DownloadRow>,
        var shuffled: Boolean,
        /** Cover hash -> its ≤ 300 px copy, made off the main thread. */
        val art: Map<String, Uri?>,
        /** The player's duration per item, once known (done marks don't depend on the listing's). */
        val durations: MutableMap<String, Long> = mutableMapOf(),
    )

    private var active: Active? = null
    private var lastIndex = -1
    private var sleepEndsAt: Long? = null
    private var sleepMinutes: Int? = null
    private var sleepPausing = false
    private var faultAt: Long? = null
    private var faultKey: String? = null
    private val faults = ArrayDeque<Long>()
    private var chosenOutput: Int? = null

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ItemMediaSourceFactory(this))
            .setAudioAttributes(attributes(speech = true), true)
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
        PlayerHub.position = { exo.currentPosition }
        PlayerHub.pause = { exo.pause() }
        LiveBridge.acquire(this)
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
        PlayerHub.pause = null
        PlayerHub.mutableState.value = PlayerHub.State()
        // Not null (that is a queue's end): Home keeps showing it paused (qa-21-step3-code L3).
        reportNowPlaying(forcePaused = true)
        LiveBridge.release(this)
        session?.release()
        session = null
        exo.release()
        scope.cancel()
        writer.close()
        super.onDestroy()
    }

    /** Speech (podcasts, stories: a notification pauses rather than ducks) or music. */
    private fun attributes(speech: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** This app is suspended now (a time rule, design 21 step 4): nothing may start playing. */
    private fun suspendedNow(): Boolean = try {
        packageManager.isPackageSuspended
    } catch (e: Exception) {
        false
    }

    /** Previous/next follow vibb (wrapping, the long-form rule) for every controller - the app, the
     * notification, headset and Bluetooth buttons. Play is refused while the app is suspended
     * (qa-21-step4-code M1: the launcher's lock view, a headset's play button). */
    private inner class KidPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        override fun isCommandAvailable(command: Int): Boolean = command in extra || super.isCommandAvailable(command)
        override fun hasNextMediaItem(): Boolean = mediaItemCount > 1
        override fun hasPreviousMediaItem(): Boolean = mediaItemCount > 1
        override fun seekToNext() = skipNext()
        override fun seekToNextMediaItem() = skipNext()
        override fun seekToPrevious() = skipPrevious()
        override fun seekToPreviousMediaItem() = skipPrevious()
        override fun play() {
            if (playAllowed(suspendedNow())) super.play()
        }
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (!playWhenReady || playAllowed(suspendedNow())) super.setPlayWhenReady(playWhenReady)
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
            if (customCommand.customAction in Commands.PLAYS && !playAllowed(suspendedNow())) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            when (customCommand.customAction) {
                Commands.PLAY_ENTRY -> scope.launch { playEntry(args.getLong(Commands.ARG_ENTRY)) }
                Commands.PLAY_ITEM -> scope.launch { playItem(args.getLong(Commands.ARG_ENTRY), args.getString(Commands.ARG_KEY).orEmpty()) }
                Commands.RESUME -> scope.launch { resumeLast() }
                Commands.RESTORE -> scope.launch { restoreThen(args.getString(Commands.ARG_THEN), args.getLong(Commands.ARG_POSITION)) }
                Commands.SHUFFLE -> toggleShuffle()
                Commands.SLEEP -> setSleep(args.getInt(Commands.ARG_MINUTES))
                Commands.OUTPUT -> setOutput(args.getInt(Commands.ARG_OUTPUT, Output.PHONE_ID))
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** A play button with nothing loaded (Bluetooth after a restart): the stored queue, at its place. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!playAllowed(suspendedNow())) return Futures.immediateFailedFuture(UnsupportedOperationException("suspended"))
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val restored = restore()
                if (restored == null) {
                    Resumable.set(this@PlaybackService, false)
                    future.setException(UnsupportedOperationException("nothing to resume"))
                } else {
                    val (a, index, pos) = restored
                    active = a
                    lastIndex = index
                    clearFault()
                    exo.setAudioAttributes(attributes(speech = a.entry.resume), true)
                    future.set(MediaSession.MediaItemsWithStartPosition(a.items.map { mediaItem(a, it) }, index, pos))
                }
            }
            return future
        }
    }

    // --- starting a queue ---

    private suspend fun load(): Pair<Snapshot, List<DownloadRow>> = withContext(Dispatchers.IO) { store.snapshot() to store.db.downloads() }

    private suspend fun activeFor(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, keys: List<String>, shuffled: Boolean): Active? {
        val order = snap.order(entry)
        val byKey = order.associateBy { it.key }
        val items = keys.mapNotNull { byKey[it] }
        if (items.isEmpty()) return null
        val hashes = (listOfNotNull(entry.cover) + order.mapNotNull { it.artHash }).toSet()
        val art = withContext(writer) { hashes.associateWith { artwork(it) } }
        return Active(
            entry, order, items,
            snap.positions[entry.key].orEmpty().toMutableMap(),
            snap.onPhone[entry.id].orEmpty().toMutableSet(),
            rows.filter { it.entryId == entry.id && it.done }.associateBy { it.itemKey }.toMutableMap(),
            shuffled,
            art,
        )
    }

    private suspend fun playEntry(entryId: Long) {
        val current = active
        if (current != null && current.entry.id == entryId && exo.mediaItemCount > 0) {
            exo.play() // a tile press on the loaded entry unpauses it and never restarts it (vibb)
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

    /** The player's controls before play after a restart: the stored queue loaded paused, then the action. */
    private suspend fun restoreThen(then: String?, positionMs: Long) {
        if (active == null || exo.mediaItemCount == 0) {
            val (a, index, pos) = restore() ?: return
            start(a, QueuePlan(a.items.map { it.key }, index, pos), play = false)
        }
        when (then) {
            Commands.THEN_NEXT -> skipNext()
            Commands.THEN_PREVIOUS -> skipPrevious()
            Commands.THEN_SEEK -> exo.seekTo(positionMs)
            Commands.THEN_SHUFFLE -> toggleShuffle()
        }
        publish()
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

    private fun start(a: Active, plan: QueuePlan, play: Boolean = true) {
        savePosition()
        clearFault()
        var index = plan.startIndex.coerceIn(a.items.indices)
        var pos = plan.startPosMs
        val first = playableFrom(a, index, forward = true, wrap = true)
        if (first == null) {
            if (play) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            return
        }
        if (first != index) {
            if (play) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            index = first
            pos = startPos(a, index)
        }
        active = a
        lastIndex = index
        exo.setAudioAttributes(attributes(speech = a.entry.resume), true)
        exo.setMediaItems(a.items.map { mediaItem(a, it) }, index, pos)
        exo.prepare()
        wakeModeFor(a, index)
        if (play) {
            exo.play()
            startedPlaying(a, index)
        }
        publish()
        wantArtSoon()
    }

    /** Where item [index] starts when the queue jumps to it: its own position when resume is on. */
    private fun startPos(a: Active, index: Int): Long =
        if (a.entry.resume) seekGate(resumeFrom(a.positions[a.items[index].key])) else 0

    private fun hasCopy(a: Active, item: PlayItem) = item.fileId != null || item.key in a.copies

    private fun playable(a: Active, index: Int, caps: NetCaps? = netCaps(this), mobileData: Boolean = store.mobileData): Boolean {
        val item = a.items[index]
        return playability(hasCopy(a, item), item.url != null, caps, mobileData).let { it == Playability.LOCAL || it == Playability.STREAM }
    }

    /** The first index from [index] on (or back) that can play now; null when none. */
    private fun playableFrom(a: Active, index: Int, forward: Boolean, wrap: Boolean): Int? {
        val n = a.items.size
        val caps = netCaps(this)
        val mobileData = store.mobileData
        for (step in 0 until n) {
            val i = if (forward) index + step else index - step
            val j = if (wrap) i.mod(n) else i
            if (j !in 0 until n) return null
            if (playable(a, j, caps, mobileData)) return j
        }
        return null
    }

    /** A copy plays without holding Wi-Fi awake; a stream needs it (qa-21-step2-code L18). */
    private fun wakeModeFor(a: Active, index: Int) {
        val item = a.items.getOrNull(index) ?: return
        exo.setWakeMode(if (hasCopy(a, item)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
    }

    private fun mediaItem(a: Active, item: PlayItem): MediaItem {
        val b = MediaItem.Builder().setMediaId("${a.entry.id}/${item.key}")
        val download = a.downloads[item.key]
        if (item.fileId != null) {
            b.setUri(Uri.fromFile(store.ownFile(item.fileId)))
            b.setTag(ItemTag(null, false, false))
        } else {
            // A copy plays from what was fetched (also when the source withdrew the episode).
            val url = download?.url ?: item.url.orEmpty()
            val hls = download?.hls ?: item.hls
            val cacheKey = itemCacheKey(a.entry.key, item.key)
            b.setUri(url).setCustomCacheKey(cacheKey)
            // A public entry's audio never comes from a private address (21b §2.7).
            b.setTag(ItemTag(cacheKey, hls, publicOnly(a.entry)))
            if (hls) {
                b.setMimeType(MimeTypes.APPLICATION_M3U8)
                download?.variant?.let { b.setStreamKeys(listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it))) }
            }
        }
        val meta = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(a.entry.name)
            .setAlbumTitle(a.entry.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        (item.artHash ?: a.entry.cover)?.let { a.art[it] }?.let { meta.setArtworkUri(it) }
        item.durationMs?.let { meta.setDurationMs(it) }
        return b.setMediaMetadata(meta.build()).build()
    }

    /** A ≤ 300 px copy of a cover, for the media notification and the launcher's media views (off the main thread). */
    private fun artwork(hash: String): Uri? {
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

    // --- episode pictures (design 27 §2.2) ---

    private val wantArtTick = Runnable { sendWantArt() }

    /** A queue start or an item change: the want set goes to the launcher 2 s later (debounced). */
    private fun wantArtSoon() {
        handler.removeCallbacks(wantArtTick)
        handler.postDelayed(wantArtTick, 2_000)
    }

    /** The pictures shown (the current item and its neighbours) are noted, and the want set sent when
     * its `now` list has something missing that it didn't ask for last time (LiveBridge.wantArt). */
    private fun sendWantArt() {
        val a = active ?: return
        val shown = nowWindow(a.items, exo.currentMediaItemIndex, reach = 1).map { it.artHash }
        scope.launch {
            val want = withContext(writer) {
                store.noteShown(shown)
                ArtWants.compute(this@PlaybackService)
            }
            LiveBridge.wantArt(want.now, want.later)
        }
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
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Headphones arriving while the phone's speaker is chosen take the sound, as the system
            // would route it (qa-21-step2-code M3): a story never stays loud by an old choice.
            if (chosenOutput == Output.PHONE_ID && addedDevices.any { outputKind(it.type) != null }) {
                chosenOutput = null
                exo.setPreferredAudioDevice(null)
            }
            publish()
        }

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
            // The launcher's snapshot keeps the place too, so Home is right after a crash or a
            // dead battery (qa-21-step4-code L4).
            reportNowPlaying(refresh = true)
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
            wakeModeFor(a, index)
            wantArtSoon()
            if (!playable(a, index)) {
                // The queue moves on (21 §4.2's one line beyond the mockup).
                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
                val nextIndex = playableFrom(a, index, forward = true, wrap = false)
                if (nextIndex != null) {
                    exo.seekTo(nextIndex, startPos(a, nextIndex))
                } else {
                    exo.pause()
                    // The bookmark moves on to the item that plays next, not back to the finished one.
                    saveBookmark(a, index)
                }
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // A natural advance resumes an item heard before (vibb: in-session jumps resume too).
                val pos = startPos(a, index)
                if (pos > 0) exo.seekTo(index, pos)
            }
            if (exo.playWhenReady) startedPlaying(a, index)
            savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> active?.let { a ->
                    val item = a.items.getOrNull(exo.currentMediaItemIndex)
                    if (item != null && exo.duration > 0) a.durations[item.key] = exo.duration
                }
                Player.STATE_ENDED -> queueEnded()
            }
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
                active?.let { a -> if (exo.currentMediaItemIndex in a.items.indices) startedPlaying(a, exo.currentMediaItemIndex) }
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
            if (!isPlaying) return
            handler.postDelayed(saveTick, 15_000)
            // It plays: an earlier failure of this item is history (qa-21-step2-code L9).
            val a = active ?: return
            val item = a.items.getOrNull(exo.currentMediaItemIndex) ?: return
            if (item.fileId == null) {
                val entryId = a.entry.id
                scope.launch(writer) { store.db.deleteItemError(entryId, item.key) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val a = active ?: return
            val index = exo.currentMediaItemIndex
            val item = a.items.getOrNull(index) ?: return
            val code = MediaCache.errorCode(error)
            val entryId = a.entry.id
            val copy = a.downloads[item.key]
            when {
                item.fileId != null -> Unit
                copy != null -> if (code == "bad_media") {
                    // A copy that isn't playable media: drop it, so the next sync fetches it again.
                    a.copies -= item.key
                    a.downloads -= item.key
                    scope.launch(writer) {
                        MediaCache.remove(this@PlaybackService, copy.cacheKey, copy.hls)
                        store.db.deleteDownload(entryId, item.key)
                    }
                }
                item.url != null -> scope.launch(writer) { ItemErrors.record(this@PlaybackService, entryId, item.key, code) }
            }
            val offline = copy == null && item.fileId == null && !streamAllowed(netCaps(this@PlaybackService), store.mobileData)
            PlayerHub.mutableMessages.tryEmit(if (offline) PlayerHub.Message.NOT_DOWNLOADED else PlayerHub.Message.CANT_PLAY)
            savePosition()
            val nextIndex = if (index + 1 < a.items.size) playableFrom(a, index + 1, forward = true, wrap = false) else null
            if (nextIndex != null) {
                exo.seekTo(nextIndex, startPos(a, nextIndex))
                exo.prepare()
                exo.play()
            } else {
                // Nothing after it can play now: stay here, paused (a tap on play tries again).
                exo.pause()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    /** An item started: it leaves Nytt (21 §4.5). */
    private fun startedPlaying(a: Active, index: Int) {
        val entryId = a.entry.id
        val key = a.items[index].key
        scope.launch(writer) {
            val tray = store.db.news().map { NewsItem(it.entryId, it.key, it.seq) }
            val next = playedFromTray(tray, entryId, key)
            if (next != tray) {
                store.db.replaceNews(next.map { NewsRow(it.entryId, it.key, it.seq) })
                NewNotifier.update(this@PlaybackService, store.snapshot())
            }
        }
    }

    private fun markDone(a: Active, item: PlayItem) {
        val rec = finished(a.durations[item.key], item.durationMs) ?: return
        a.positions[item.key] = rec
        val entryKey = a.entry.key
        scope.launch(writer) { store.db.upsertPosition(PositionRow(entryKey, item.key, rec.posMs, rec.done, System.currentTimeMillis())) }
    }

    /** The queue played to its end (vibb `clear_state`): the last item is done, the bookmark and the
     * partial positions go, the done marks stay. */
    private fun queueEnded() {
        val a = active ?: return
        a.items.getOrNull(exo.currentMediaItemIndex)?.let { markDone(a, it) }
        active = null
        lastIndex = -1
        Resumable.set(this, false)
        val entryKey = a.entry.key
        scope.launch(writer) {
            store.db.deleteBookmark(entryKey)
            store.db.clearPartialPositions(entryKey)
            store.db.clearNowPlaying()
        }
        exo.clearMediaItems()
        publish()
    }

    private fun markFault() {
        val now = SystemClock.elapsedRealtime()
        if (faultAt == null) {
            faultAt = now
            faultKey = active?.items?.getOrNull(exo.currentMediaItemIndex)?.key
        }
        faults.addLast(now)
        while (faults.size > 10) faults.removeFirst()
    }

    private fun clearFault() {
        faultAt = null
        faultKey = null
    }

    /** Back up a beat after an outage (vibb's resume overlap): only on the item that was
     * interrupted, never after a tap that started something else. */
    private fun applyOverlap() {
        val at = faultAt ?: return
        val key = faultKey
        clearFault()
        val a = active ?: return
        if (a.items.getOrNull(exo.currentMediaItemIndex)?.key != key) return
        val now = SystemClock.elapsedRealtime()
        val overlap = resumeOverlapMs(music = !a.entry.resume, goneMs = now - at, recentFaults = faults.count { now - it < 60_000 })
        if (overlap > 0) exo.seekTo(overlapStart(exo.currentPosition, overlap))
    }

    /** Store where the current item is (its position, the entry's bookmark, the now-playing queue). */
    private fun savePosition(blocking: Boolean = false) {
        val a = active ?: return
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
        val queue = NowPlayingRow(0, a.entry.id, a.items.joinToString("\n") { it.key }, index, pos, duration, a.shuffled, now)
        Resumable.set(this, true)
        val write: suspend () -> Unit = {
            store.db.upsertPosition(position)
            store.db.upsertBookmark(mark)
            store.db.upsertNowPlaying(queue)
        }
        if (blocking) runBlocking(writer) { write() } else scope.launch(writer) { write() }
    }

    /** The bookmark (and the stored queue) on item [index] at its start, its own position untouched. */
    private fun saveBookmark(a: Active, index: Int) {
        val item = a.items.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        val mark = BookmarkRow(a.entry.key, item.key, 0, now)
        val queue = NowPlayingRow(0, a.entry.id, a.items.joinToString("\n") { it.key }, index, 0, a.durations[item.key] ?: item.durationMs, a.shuffled, now)
        Resumable.set(this, true)
        scope.launch(writer) {
            store.db.upsertBookmark(mark)
            store.db.upsertNowPlaying(queue)
        }
    }

    private var sentNowPlaying: Triple<Long?, String?, Boolean>? = null

    /**
     * What plays, to the launcher (21 §4.6 `setNowPlaying`: Home and the lock, never the server):
     * on play/pause and item change, null at a queue's end; with [refresh] (the 15 s position save
     * while playing) the position again. Art at most 64 KB.
     */
    private fun reportNowPlaying(forcePaused: Boolean = false, refresh: Boolean = false) {
        val a = active
        val item = a?.items?.getOrNull(exo.currentMediaItemIndex)
        val playing = !forcePaused && exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
        val key = Triple(a?.entry?.id, item?.key, playing)
        if (key == sentNowPlaying && !refresh) return
        sentNowPlaying = key
        if (a == null || item == null) {
            LiveBridge.setNowPlaying(null)
            return
        }
        val art = (item.artHash ?: a.entry.cover)?.let { a.art[it] }?.path?.let { File(it) }
            ?.takeIf { it.isFile && it.length() <= 64 * 1024 }?.let { runCatching { it.readBytes() }.getOrNull() }
        LiveBridge.setNowPlaying(
            Bundle().apply {
                putString(NowPlayingKeys.ENTRY, a.entry.name)
                putString(NowPlayingKeys.ITEM, item.title)
                putLong(NowPlayingKeys.POSITION_MS, exo.currentPosition)
                putLong(NowPlayingKeys.DURATION_MS, exo.duration.takeIf { it > 0 } ?: a.durations[item.key] ?: item.durationMs ?: 0)
                putBoolean(NowPlayingKeys.PLAYING, playing)
                art?.let { putByteArray(NowPlayingKeys.ART, it) }
            },
        )
    }

    private fun publish() {
        reportNowPlaying()
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
