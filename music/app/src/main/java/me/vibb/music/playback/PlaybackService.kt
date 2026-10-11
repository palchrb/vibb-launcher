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
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.R
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.LAUNCHER_PACKAGES
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
import me.vibb.music.logic.CallAction
import me.vibb.music.logic.CallPause
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.Playability
import me.vibb.music.logic.QueuePlan
import me.vibb.music.logic.TransportAction
import me.vibb.music.logic.callMode
import me.vibb.music.logic.callPauseStep
import me.vibb.music.logic.callTimerEnded
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.coverTapPlan
import me.vibb.music.logic.finished
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.nextAction
import me.vibb.music.logic.overlapStart
import me.vibb.music.logic.pickPlan
import me.vibb.music.logic.playAllowed
import me.vibb.music.logic.playability
import me.vibb.music.logic.playedFromTray
import me.vibb.music.logic.previousAction
import me.vibb.music.logic.publicOnly
import me.vibb.music.logic.homePlan
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.resumeOverlapMs
import me.vibb.music.logic.storedPlan
import me.vibb.music.logic.seekGate
import me.vibb.music.logic.streamAllowed
import me.vibb.music.logic.toggledQueue
import me.vibb.music.media.ItemTag
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.sonos.PlayBodies
import me.vibb.music.sonos.RendererChoice
import me.vibb.music.sonos.SonosHome
import me.vibb.music.sonos.SonosLocks
import me.vibb.music.sonos.SonosRenderer
import me.vibb.music.sonos.adoptIndex
import me.vibb.music.sonos.ourLiveSession
import me.vibb.music.sync.ItemErrors
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.netCaps
import me.vibb.music.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors

/** The queue the player holds, in play order (or shuffled); the service's, read by [LocalRenderer.saveNow]. */
internal class Active(
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
 *   until that device goes - or, for the phone, until headphones arrive; or a Sonos room.
 * Who plays is the [Renderer] (design 26a §0): the phone's [LocalRenderer] (the ExoPlayer) or a
 * Sonos room's [SonosRenderer], changed only by [attach]; the session wraps `KidPlayer(renderer.player)`.
 * Nothing here ever starts playback by itself.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var renderer: Renderer

    /** The phone's player: the renderer unless a Sonos room plays, and where "Spill av på"'s output axis applies. */
    private lateinit var local: LocalRenderer

    /** The household: Wi-Fi, the speaker cache, the rooms, renderer.json (design 26a §2.1, §4.1). */
    private lateinit var home: SonosHome

    /** The wake and Wi-Fi locks a Sonos session holds while it plays (design 26a §6.3). */
    private lateinit var locks: SonosLocks
    private var session: MediaSession? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /**
     * Who plays is settled (design 26a §4.7, QA H5): every custom command and playback resumption
     * awaits it first, so a RESUME after a restart can never start the phone under a playing speaker
     * (vibb's double-playback trap, `sonos_renderer.py` check 2). Complete at once in onCreate when
     * renderer.json says the phone; else after [adopt]'s one classify of the stored room.
     */
    private val rendererReady = CompletableDeferred<Unit>()

    /**
     * One command at a time where the renderer matters (qa-26a-step3a-code M2): a switch suspends for
     * a hand-off and IO, and a pick or a play arriving meanwhile must neither switch twice nor start a
     * queue built for the other renderer. Uncontended it never suspends (the commands stay synchronous).
     */
    private val switching = Mutex()

    private var active: Active? = null
    private var lastIndex = -1
    private var sleepEndsAt: Long? = null
    private var sleepMinutes: Int? = null
    /** A pause the service makes itself (the sleep timer's end, a renderer switch): not the kid's, so it
     * never cancels the sleep timer. */
    private var selfPausing = false
    private var faultAt: Long? = null
    private var faultKey: String? = null
    private val faults = ArrayDeque<Long>()
    /** An [Output.key]; null = the system's routing. */
    private var chosenOutput: String? = null

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        home = SonosHome(this)
        locks = SonosLocks(this)
        attach(LocalRenderer(this, store, scope, writer) { active }.also { local = it })
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, KidPlayer(renderer.player)).setCallback(callback).setSessionActivity(open).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.music_glyph_music_note) })
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        PlayerHub.position = { renderer.positionMs() }
        LiveBridge.acquire(this)
        PlayerHub.suspendStop = { scope.launch { suspendStop() } }
        audio.addOnModeChangedListener(mainExecutor, modeListener)
        // Design 26a §4.7: the phone settles at once; a room only after one classify of it (adopt).
        val choice = home.choice()
        scope.launch {
            try {
                if (choice.sonos) adopt(choice)
            } finally {
                rendererReady.complete(Unit)
            }
        }
        scope.launch { home.rooms.state.collect { publish() } }
        publish()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * The task swiped away: the place is saved; Media3 then pauses every player and stops the service
     * unless one plays (a playing Sonos room plays on, as the phone does), and [onDestroy] does the
     * speaker's full stop (design 26a §6.2).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        renderer.saveNow(force = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val sonos = renderer as? SonosRenderer
        // the phone's place now; a room's after its live classify, below
        if (sonos == null) renderer.saveNow(force = true)
        handler.removeCallbacksAndMessages(null)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        audio.removeOnModeChangedListener(modeListener)
        PlayerHub.position = null
        PlayerHub.suspendStop = null
        PlayerHub.mutableState.value = PlayerHub.State()
        // Not null (that is a queue's end): Home keeps showing it paused (qa-21-step3-code L3).
        reportNowPlaying(forcePaused = true)
        LiveBridge.release(this)
        session?.release()
        session = null
        if (renderer !== local) local.release()
        if (sonos != null) {
            // Design 26a §6.2 (vibb `_sonos_on_term`: box off = music off): the full stop - the live
            // classify, the bookmark at that second, Stop if ours - goes on after the service, and the
            // speaker, the household and the writer are let go after it. A crash skips this: the speaker
            // plays on until the next start adopts it or a suspension edge stops it.
            val tail = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            tail.launch {
                try {
                    handOff(sonos)
                } finally {
                    sonos.release()
                    home.release()
                    writer.close()
                    tail.cancel()
                }
            }
        } else {
            renderer.release()
            home.release()
            writer.close()
            locks.hold(false)
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The one place the renderer changes (design 26a §0, §4.6), from [onCreate] (no old one, no session
     * yet) and from step 3's switch. The old renderer is saved (force: the bookmark flush) and paused
     * BEFORE it is detached, so its own events (the pause: a save, the 15 s tick dropped) are still
     * handled while it is [renderer]; the pause is the service's own, so the sleep timer stays. Then
     * only [next] reports to the service, and the session wraps it in [KidPlayer]. `next.player` must
     * live on the main looper (`MediaSession.setPlayer` checks the application looper).
     */
    private fun attach(next: Renderer) {
        if (::renderer.isInitialized) {
            val old = renderer
            old.saveNow(force = true)
            selfPausing = true
            old.pause()
            selfPausing = false
            old.cancelFade()
            old.onEvent = null
        }
        next.onEvent = { onRendererEvent(it) }
        renderer = next
        session?.setPlayer(KidPlayer(next.player))
        // a sleep timer runs on: its fade is the new renderer's
        if (sleepEndsAt != null) {
            handler.removeCallbacks(sleepTick)
            handler.post(sleepTick)
        }
    }

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
            if (controller.packageName in LAUNCHER_PACKAGES && Bridge.sameSigner(this@PlaybackService, controller.packageName)) {
                // the launcher's call rule only (design 26a §6.7, qa-26a-step3b-code H1)
                val calls = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply { Commands.LAUNCHER.forEach { add(it) } }.build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(calls).build()
            }
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
            // OUTPUT to a Sonos room starts the speaker: a play too (design 26a §3.3), read from its argument here.
            val toRoom = customCommand.customAction == Commands.OUTPUT && Output.sonosUid(args.getString(Commands.ARG_OUTPUT)) != null
            if ((customCommand.customAction in Commands.PLAYS || toRoom) && !playAllowed(suspendedNow())) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            if ((Commands.ALL + Commands.LAUNCHER).none { it.customAction == customCommand.customAction }) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            // Main.immediate: with the renderer settled this runs at once, as before step 2.
            scope.launch {
                rendererReady.await()
                switching.withLock {
                    when (customCommand.customAction) {
                        Commands.PLAY_ENTRY -> playEntry(args.getLong(Commands.ARG_ENTRY))
                        Commands.PLAY_ITEM -> playItem(args.getLong(Commands.ARG_ENTRY), args.getString(Commands.ARG_KEY).orEmpty())
                        Commands.RESUME -> resumeLast()
                        Commands.RESTORE -> restoreThen(args.getString(Commands.ARG_THEN), args.getLong(Commands.ARG_POSITION))
                        Commands.SHUFFLE -> toggleShuffle()
                        Commands.SLEEP -> setSleep(args.getInt(Commands.ARG_MINUTES))
                        Commands.OUTPUT -> output(args.getString(Commands.ARG_OUTPUT) ?: Output.PHONE_KEY)
                        Commands.ROOMS_REFRESH -> launch(Dispatchers.IO) { home.rooms.refresh() }
                        Commands.ROOMS_RESCAN -> home.rooms.rescanInBackground()
                        Commands.CALL_HOLD -> launcherCall(true)
                        Commands.CALL_RELEASE -> launcherCall(false)
                    }
                }
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
                rendererReady.await()
                switching.withLock {
                    if (renderer.kind == RendererKind.SONOS) {
                        // Design 26a §4.7: on Sonos a Bluetooth play button starts the speaker - the queue it
                        // holds, else the stored one, gated (playAllowed above; the controller's mayPlay before
                        // anything is sent) - and hands the session no local items.
                        resumeLast()
                        future.setException(UnsupportedOperationException("playing on the Sonos room"))
                        return@launch
                    }
                    val restored = restore()
                    if (restored == null) {
                        Resumable.set(this@PlaybackService, false)
                        future.setException(UnsupportedOperationException("nothing to resume"))
                    } else {
                        val (a, index, pos) = restored
                        active = a
                        lastIndex = index
                        clearFault()
                        renderer.setAudioAttributes(audioAttributes(speech = a.entry.resume))
                        future.set(MediaSession.MediaItemsWithStartPosition(a.items.map { mediaItem(a, it) }, index, pos))
                    }
                }
            }
            return future
        }
    }

    // --- starting a queue ---

    private suspend fun load(): Pair<Snapshot, List<DownloadRow>> = withContext(Dispatchers.IO) { store.snapshot() to store.db.downloads() }

    /**
     * The queue of [keys] as renderer [kind] plays it. A Sonos room gets only what a speaker can play
     * (design 26a §4.2, `PlayBodies.speakerPlayable`: own files, withdrawn items, HLS outside an NRK
     * series and a public entry's private addresses drop out) - the renderer passed explicitly, never
     * read from a global (§9). The renderer never filters (`Renderer.currentIndex`).
     */
    private suspend fun activeFor(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, keys: List<String>, shuffled: Boolean, kind: RendererKind): Active? {
        val order = snap.order(entry)
        val byKey = order.associateBy { it.key }
        val items = keys.mapNotNull { byKey[it] }.filter { kind == RendererKind.LOCAL || PlayBodies.speakerPlayable(entry, it) }
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

    /**
     * [plan] of [entry] on the renderer [kind]: on a Sonos room only the speaker-playable items, the
     * plan's start moved with them (§4.2); nothing left is "Kan ikke spilles på Sonos" and nothing
     * changes (vibb's `nothing-remote-playable`: the renderer stays).
     */
    private suspend fun queueFor(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, plan: QueuePlan, shuffled: Boolean, kind: RendererKind): Pair<Active, QueuePlan>? {
        val p = if (kind == RendererKind.SONOS) {
            val byKey = snap.order(entry).associateBy { it.key }
            val dropped = plan.keys.mapNotNull { k -> byKey[k]?.let { PlayBodies.speakerDropReason(entry, it) } }
            if (dropped.isNotEmpty()) SonosHome.log("sonos: ${dropped.size} item(s) a speaker can't play left out (${dropped.distinct().joinToString()})")
            PlayBodies.speakerPlan(plan) { k -> byKey[k]?.let { PlayBodies.speakerPlayable(entry, it) } == true } ?: run {
                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.CANT_PLAY_ON_SONOS)
                return null
            }
        } else {
            plan
        }
        val a = activeFor(snap, rows, entry, p.keys, shuffled, kind) ?: return null
        return a to p
    }

    private suspend fun playEntry(entryId: Long) {
        val current = active
        if (current != null && current.entry.id == entryId && renderer.itemCount() > 0) {
            renderer.play() // a tile press on the loaded entry unpauses it and never restarts it (vibb)
            return
        }
        val (snap, rows) = load()
        val entry = snap.entries.firstOrNull { it.id == entryId } ?: return
        val shuffle = canShuffle(entry) && entry.key in snap.shuffle
        val plan = coverTapPlan(snap.order(entry).map { it.key }, entry.resume, snap.bookmarks[entry.key], shuffle) ?: return
        val (a, p) = queueFor(snap, rows, entry, plan, shuffle, renderer.kind) ?: return
        start(a, p)
    }

    private suspend fun playItem(entryId: Long, key: String) {
        renderer.saveNow()
        val (snap, rows) = load()
        val entry = snap.entries.firstOrNull { it.id == entryId } ?: return
        val shuffle = canShuffle(entry) && entry.key in snap.shuffle
        val current = active?.takeIf { it.entry.id == entryId && it.shuffled }?.items?.map { it.key }
        val plan = pickPlan(snap.order(entry).map { it.key }, key, entry.resume, snap.positions[entry.key]?.get(key), if (shuffle) current else null) ?: return
        val (a, p) = queueFor(snap, rows, entry, plan, shuffle, renderer.kind) ?: return
        start(a, p)
    }

    private suspend fun resumeLast() {
        if (active != null && renderer.itemCount() > 0) {
            renderer.play()
            return
        }
        val (a, index, pos) = restore() ?: return
        start(a, QueuePlan(a.items.map { it.key }, index, pos))
    }

    /** The player's controls before play after a restart: the stored queue loaded paused, then the action. */
    private suspend fun restoreThen(then: String?, positionMs: Long) {
        if (active == null || renderer.itemCount() == 0) {
            val (a, index, pos) = restore() ?: return
            start(a, QueuePlan(a.items.map { it.key }, index, pos), play = false)
        }
        when (then) {
            Commands.THEN_NEXT -> skipNext()
            Commands.THEN_PREVIOUS -> skipPrevious()
            Commands.THEN_SEEK -> renderer.seekTo(positionMs)
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
        val plan = storedPlan(stored, row.index, row.posMs, snap.order(entry).map { it.key }.toSet()) { startPos(entry, snap.positions[entry.key]?.get(it)) } ?: return null
        val (a, p) = queueFor(snap, rows, entry, plan, row.shuffled, renderer.kind) ?: return null
        return Triple(a, p.startIndex, p.startPosMs)
    }

    private fun start(a: Active, plan: QueuePlan, play: Boolean = true) {
        renderer.saveNow()
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
        renderer.setAudioAttributes(audioAttributes(speech = a.entry.resume))
        // Loaded paused: the wake mode goes between prepare and play, as before step 2.
        renderer.load(a.items.map { mediaItem(a, it) }, index, pos, playNow = false)
        wakeModeFor(a, index)
        if (play) {
            renderer.play()
            startedPlaying(a, index)
        }
        publish()
    }

    /** Where item [index] starts when the queue jumps to it: its own position when resume is on. */
    private fun startPos(a: Active, index: Int): Long = startPos(a.entry, a.positions[a.items[index].key])

    private fun startPos(entry: Entry, position: ItemPosition?): Long = if (entry.resume) seekGate(resumeFrom(position)) else 0

    private fun hasCopy(a: Active, item: PlayItem) = item.fileId != null || item.key in a.copies

    private fun playable(a: Active, index: Int, caps: NetCaps? = netCaps(this), mobileData: Boolean = store.mobileData): Boolean {
        // A speaker fetches by itself and its queue holds only what it can play (§4.2): the phone's gate is the phone's.
        if (renderer.kind == RendererKind.SONOS) return true
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
        renderer.setWakeMode(if (hasCopy(a, item)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
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

    // --- transport ---

    private fun skipNext() {
        val a = active ?: return
        when (val action = nextAction(renderer.currentIndex(), renderer.itemCount())) {
            is TransportAction.GoTo -> goTo(a, action.index, forward = true)
            else -> Unit
        }
    }

    private fun skipPrevious() {
        val a = active ?: return
        val duration = renderer.durationMs() ?: a.items.getOrNull(renderer.currentIndex())?.durationMs
        when (val action = previousAction(renderer.currentIndex(), renderer.itemCount(), renderer.positionMs(), duration)) {
            TransportAction.Restart -> renderer.seekTo(0L)
            is TransportAction.GoTo -> goTo(a, action.index, forward = false)
            TransportAction.Stay -> Unit
        }
    }

    private fun goTo(a: Active, index: Int, forward: Boolean) {
        renderer.saveNow()
        val target = playableFrom(a, index, forward, wrap = true) ?: run {
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            return
        }
        if (target != index) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
        renderer.seekTo(target, startPos(a, target))
        if (renderer.player.playbackState == Player.STATE_IDLE) renderer.prepare()
    }

    // --- shuffle, sleep, outputs ---

    private fun toggleShuffle() {
        val a = active ?: return
        if (!canShuffle(a.entry)) return
        val on = !a.shuffled
        val index = renderer.currentIndex().coerceIn(a.items.indices)
        val current = a.items[index]
        val byKey = a.order.associateBy { it.key }
        val items = toggledQueue(a.order.map { it.key }, current.key, on).mapNotNull { byKey[it] }
        // Keep the current item playing, the new order after it.
        renderer.reorder(index, items.drop(1).map { mediaItem(a, it) })
        a.items = items
        a.shuffled = on
        lastIndex = 0
        val entryKey = a.entry.key
        scope.launch(writer) { store.db.upsertShuffle(ShuffleRow(entryKey, on)) }
        renderer.saveNow()
        publish()
    }

    private fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepTick)
        renderer.cancelFade()
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

    /** The timer's end: the renderer's own fade and pause ([Renderer.fadeOutAndPause]: the phone 10 s, a Sonos room 3 s). */
    private val sleepTick = object : Runnable {
        override fun run() {
            val end = sleepEndsAt ?: return
            val left = end - SystemClock.elapsedRealtime()
            if (left > renderer.fadeMs) {
                handler.postDelayed(this, minOf(left - renderer.fadeMs, 30_000))
                return
            }
            renderer.fadeOutAndPause { sleepEnded() }
        }
    }

    private fun sleepEnded() {
        // a pause held for a call is not undone after the timer's end (qa-26a-step3b-code H1)
        callPause = callTimerEnded(callPause)
        sleepEndsAt = null
        sleepMinutes = null
        renderer.saveNow()
        publish()
    }

    private fun outputKind(type: Int): OutputKind? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.WIRED
        else -> null
    }

    private fun outputs(): List<Output> = listOf(Output.PHONE) + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { d -> outputKind(d.type)?.let { Output(Output.deviceKey(d.id), it, d.productName?.toString()?.takeIf { n -> n.isNotBlank() }) } }
        .distinctBy { it.kind to it.name }

    private fun setOutput(key: String) {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = if (key == Output.PHONE_KEY) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            Output.deviceId(key)?.let { id -> devices.firstOrNull { it.id == id } }
        }
        // Canonical keys only ("dev:07" names device 7 but would never match its key again).
        chosenOutput = when {
            device == null -> null
            key == Output.PHONE_KEY -> key
            else -> Output.deviceKey(device.id)
        }
        local.setPreferredAudioDevice(device)
        publish()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Headphones arriving while the phone's speaker is chosen take the sound, as the system
            // would route it (qa-21-step2-code M3): a story never stays loud by an old choice.
            if (chosenOutput == Output.PHONE_KEY && addedDevices.any { outputKind(it.type) != null }) {
                chosenOutput = null
                local.setPreferredAudioDevice(null)
            }
            publish()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // The chosen device went: back to system routing.
            if (removedDevices.any { Output.deviceKey(it.id) == chosenOutput }) {
                chosenOutput = null
                local.setPreferredAudioDevice(null)
            }
            publish()
        }
    }

    // --- events ---

    private val saveTick = object : Runnable {
        override fun run() {
            renderer.saveNow()
            // The launcher's snapshot keeps the place too, so Home is right after a crash or a
            // dead battery (qa-21-step4-code L4).
            reportNowPlaying(refresh = true)
            handler.postDelayed(this, 15_000)
        }
    }

    /** The renderer's events (the local one's are ExoPlayer's `Player.Listener` callbacks, in order). */
    private fun onRendererEvent(event: RendererEvent) {
        when (event) {
            is RendererEvent.ItemChanged -> onItemChanged(event.index, event.auto)
            RendererEvent.Ready -> active?.let { a ->
                val item = a.items.getOrNull(renderer.currentIndex())
                val duration = renderer.durationMs()
                if (item != null && duration != null) a.durations[item.key] = duration
            }
            RendererEvent.Ended -> queueEnded()
            is RendererEvent.Paused -> {
                when (event.cause) {
                    PauseCause.FAULT -> markFault()
                    PauseCause.USER -> if (!selfPausing && sleepEndsAt != null) setSleep(0)
                    PauseCause.OTHER -> Unit
                }
                renderer.saveNow()
            }
            RendererEvent.Resumed -> {
                applyOverlap()
                active?.let { a -> if (renderer.currentIndex() in a.items.indices) startedPlaying(a, renderer.currentIndex()) }
            }
            RendererEvent.Interrupted -> {
                markFault()
                renderer.saveNow()
            }
            RendererEvent.InterruptionEnded -> if (renderer.player.playWhenReady) applyOverlap()
            is RendererEvent.IsPlaying -> {
                onIsPlayingChanged(event.playing)
                if (callPause.inCall) callStep()
            }
            is RendererEvent.Error -> onError(event.code)
            RendererEvent.Lost -> scope.launch { lost() }
            RendererEvent.StateChanged -> publish()
        }
    }

    private fun onItemChanged(index: Int, auto: Boolean) {
        val a = active ?: return
        if (index !in a.items.indices) return
        val previous = a.items.getOrNull(lastIndex)
        if (auto && previous != null && lastIndex != index) {
            markDone(a, previous)
        }
        lastIndex = index
        wakeModeFor(a, index)
        if (!playable(a, index)) {
            // The queue moves on (21 §4.2's one line beyond the mockup).
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            val nextIndex = playableFrom(a, index, forward = true, wrap = false)
            if (nextIndex != null) {
                renderer.seekTo(nextIndex, startPos(a, nextIndex))
            } else {
                renderer.pause()
                // The bookmark moves on to the item that plays next, not back to the finished one.
                saveBookmark(a, index)
            }
            return
        }
        if (auto && renderer.kind == RendererKind.LOCAL) {
            // A natural advance resumes an item heard before (vibb: in-session jumps resume too); on a
            // speaker the next item starts at 0 (vibb `_sonos_poller`, design 26a §4.3).
            val pos = startPos(a, index)
            if (pos > 0) renderer.seekTo(index, pos)
        }
        if (renderer.player.playWhenReady) startedPlaying(a, index)
        renderer.saveNow()
    }

    // --- calls (design 26a §6.7) ---

    private var callPause = CallPause()

    /** The renderer we paused for the call: only it is played again. */
    private var callRenderer: Renderer? = null

    /** The launcher says a call rings or is up (CALL_HOLD) / is over (CALL_RELEASE). */
    private var launcherInCall = false

    /** The audio mode says a system call rings or is up: the backstop on a Sonos room. */
    private var modeInCall = false

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        modeInCall = callMode(mode)
        callStep()
    }

    private fun launcherCall(inCall: Boolean) {
        launcherInCall = inCall
        callStep()
    }

    /**
     * The call rule's step ([callPauseStep]): the launcher's hold for every renderer, the audio mode
     * only while a Sonos room renders (the phone's own player pauses for a system call through its
     * audio focus; a room holds none). The pause is the service's own ([selfPausing]: the sleep timer
     * runs on); the resume asks `playAllowed`, goes only to the renderer we paused, and through its
     * player's play - the same entry point as the session's play (qa-26a-step3b-code L7).
     */
    private fun callStep() {
        val r = renderer
        val inCall = launcherInCall || (modeInCall && r.kind == RendererKind.SONOS)
        if (callRenderer != null && callRenderer !== r) {
            // the renderer changed under the call: nothing of ours to play again
            callPause = CallPause(inCall = callPause.inCall)
            callRenderer = null
        }
        val playing = r.showsPlaying
        val paused = !playing && r.itemCount() > 0 && r.rendererState == null
        val (next, action) = callPauseStep(callPause, inCall, playing, paused, playAllowed(suspendedNow()))
        callPause = next
        when (action) {
            CallAction.PAUSE -> {
                callRenderer = r
                selfPausing = true
                r.pause()
                selfPausing = false
            }
            CallAction.PLAY -> if (callRenderer === r) r.player.play()
            CallAction.NONE -> Unit
        }
        if (!next.pausedByCall) callRenderer = null
    }

    private fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(saveTick)
        if (!isPlaying) return
        handler.postDelayed(saveTick, 15_000)
        // It plays: an earlier failure of this item is history (qa-21-step2-code L9).
        val a = active ?: return
        val item = a.items.getOrNull(renderer.currentIndex()) ?: return
        if (item.fileId == null) {
            val entryId = a.entry.id
            scope.launch(writer) { store.db.deleteItemError(entryId, item.key) }
        }
    }

    private fun onError(code: String) {
        val a = active ?: return
        val index = renderer.currentIndex()
        val item = a.items.getOrNull(index) ?: return
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
        renderer.saveNow()
        val nextIndex = if (index + 1 < a.items.size) playableFrom(a, index + 1, forward = true, wrap = false) else null
        if (nextIndex != null) {
            renderer.seekTo(nextIndex, startPos(a, nextIndex))
            renderer.prepare()
            renderer.play()
        } else {
            // Nothing after it can play now: stay here, paused (a tap on play tries again).
            renderer.pause()
        }
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
        a.items.getOrNull(renderer.currentIndex())?.let { markDone(a, it) }
        active = null
        lastIndex = -1
        Resumable.set(this, false)
        val entryKey = a.entry.key
        scope.launch(writer) {
            store.db.deleteBookmark(entryKey)
            store.db.clearPartialPositions(entryKey)
            store.db.clearNowPlaying()
        }
        renderer.clear()
        publish()
    }

    private fun markFault() {
        val now = SystemClock.elapsedRealtime()
        if (faultAt == null) {
            faultAt = now
            faultKey = active?.items?.getOrNull(renderer.currentIndex())?.key
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
        if (a.items.getOrNull(renderer.currentIndex())?.key != key) return
        val now = SystemClock.elapsedRealtime()
        val overlap = resumeOverlapMs(music = !a.entry.resume, goneMs = now - at, recentFaults = faults.count { now - it < 60_000 })
        if (overlap > 0) renderer.seekTo(overlapStart(renderer.positionMs(), overlap))
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
        val item = a?.items?.getOrNull(renderer.currentIndex())
        // the same predicate as the play button (§3.3): on Sonos grouped away, taken over or stale is not playing
        val playing = !forcePaused && renderer.showsPlaying
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
                putLong(NowPlayingKeys.POSITION_MS, renderer.positionMs())
                putLong(NowPlayingKeys.DURATION_MS, renderer.durationMs() ?: a.durations[item.key] ?: item.durationMs ?: 0)
                putBoolean(NowPlayingKeys.PLAYING, playing)
                art?.let { putByteArray(NowPlayingKeys.ART, it) }
            },
        )
    }

    private fun publish() {
        reportNowPlaying()
        val a = active
        val outputs = outputs()
        val output = renderer.room
            ?: outputs.firstOrNull { it.key == chosenOutput }
            ?: outputs.firstOrNull { it.kind == OutputKind.BLUETOOTH || it.kind == OutputKind.WIRED }
            ?: Output.PHONE
        PlayerHub.mutableState.value = PlayerHub.State(
            live = a != null && renderer.itemCount() > 0,
            entryId = a?.entry?.id,
            keys = a?.items?.map { it.key }.orEmpty(),
            index = renderer.currentIndex(),
            shuffled = a?.shuffled ?: false,
            playing = renderer.showsPlaying,
            durationMs = renderer.durationMs() ?: a?.items?.getOrNull(renderer.currentIndex())?.durationMs,
            sleepEndsAt = sleepEndsAt,
            sleepMinutes = sleepMinutes,
            outputs = outputs,
            output = output,
            rendererState = renderer.rendererState,
            rooms = home.rooms.state.value,
        )
    }

    // --- the renderer: the phone or a Sonos room (design 26a §4.6) ---

    private fun sonosRenderer(uid: String, name: String?) =
        SonosRenderer(this, home, store, scope, writer, { active }, uid, name, onArmedPlaying = { locks.hold(it) })

    /**
     * Adopt at start (design 26a §4.7, vibb `_sonos_poller`'s reconciliation): renderer.json names a
     * room. One classify of it (in its actor, [SonosController.LIVE_BUDGET_MS]); our stream there,
     * playing or paused ([ourLiveSession]) -> adopted with zero transport commands: the stored
     * entry's queue rebuilt with the speaker-playable filter (the stored queue's order when it is
     * that entry's), the index by uri equality ([adoptIndex]), the room the session's player.
     * Anything else - unreachable, late, foreign, stopped, the item gone - renderer.json back to the
     * phone, nothing starts, the bar comes back paused from the stored row (`NowPlayingRow`).
     */
    private suspend fun adopt(choice: RendererChoice) = switching.withLock {
        val uid = choice.uid ?: return@withLock
        val uri = choice.uri
        val sonos = sonosRenderer(uid, choice.name)
        val live = if (uri.isNullOrEmpty()) null else withTimeoutOrNull(ADOPT_MS) { sonos.probe(uri) { ourLiveSession(choice, it) } }
        val queue = if (uri != null && live != null && ourLiveSession(choice, live)) adoptedQueue(choice, uri) else null
        if (queue == null || renderer !== local) {
            SonosHome.log("sonos: no live session of ours on ${choice.name ?: uid} at start - the phone renders, nothing starts")
            sonos.release()
            withContext(writer) { home.write(RendererChoice.local(home.choice())) }
            return@withLock
        }
        val (a, index) = queue
        attach(sonos)
        active = a
        lastIndex = index
        sonos.adopt(a.items.map { mediaItem(a, it) }, index, uri!!, live!!)
        publish()
    }

    /** The stored entry's queue on a room, speaker-playable, and the index of [uri] in it; null when it can't be rebuilt. */
    private suspend fun adoptedQueue(choice: RendererChoice, uri: String): Pair<Active, Int>? {
        val (snap, rows) = load()
        val entry = snap.entries.firstOrNull { it.id == choice.entryId } ?: return null
        val row = withContext(Dispatchers.IO) { store.db.nowPlaying() }?.takeIf { it.entryId == entry.id }
        val keys = row?.keys?.split('\n')?.filter { it.isNotEmpty() } ?: snap.order(entry).map { it.key }
        val a = activeFor(snap, rows, entry, keys, row?.shuffled ?: false, RendererKind.SONOS) ?: return null
        val index = adoptIndex(entry, a.items, uri) ?: return null
        return a to index
    }

    /**
     * The launcher suspended the app (a time rule; design 26a §6.1), with the service up: on a room,
     * the hand-off with the park - one live classify (a stood-down session's too), the bookmark, and
     * for our stream playing or paused Stop and the speaker's own queue as its source - then the
     * phone, paused (renderer.json back to it). On the phone: the pause, as before.
     */
    private suspend fun suspendStop() {
        rendererReady.await()
        switching.withLock {
            val sonos = renderer as? SonosRenderer
            if (sonos == null) {
                renderer.pause()
                return@withLock
            }
            withTimeoutOrNull(HANDOFF_MS) { sonos.park() } ?: run {
                SonosHome.log("sonos: the suspension's stop took too long - Stop queued, the bookmark from the last poll")
                sonos.abandon()
            }
            comeHome(sonos, play = false)
        }
    }

    /** "Spill av på": a room is a switch to the speaker (gated as a play); the phone or a headset first comes home. */
    private suspend fun output(key: String) {
        val uid = Output.sonosUid(key)
        if (uid != null) return toRoom(uid)
        if (renderer.kind == RendererKind.SONOS) toPhone(resume = null)
        setOutput(key)
    }

    /**
     * Phone -> room, or room -> room (`_renderer_to_sonos`, 1625-1667): the place first (the phone's
     * position flushed; the old room's live bookmark and its Stop), the speaker-playable queue (nothing
     * left: "Kan ikke spilles på Sonos", nothing changes), renderer.json before the first SOAP, the
     * session on the speaker, then the start at the saved place. With no queue the stored one comes
     * back first, as RESUME does (QA ambiguity 4). The room we're on is the same-tile rule.
     */
    private suspend fun toRoom(uid: String) {
        val old = renderer
        if (old is SonosRenderer && old.uid == uid) {
            old.play()
            publish()
            return
        }
        val name = withContext(Dispatchers.IO) { home.label(uid) }
        val current = active?.takeIf { old.itemCount() > 0 }?.let { a0 -> a0.items.getOrNull(old.currentIndex())?.let { a0 to it } }
        if (old is SonosRenderer) handOff(old) else old.saveNow(force = true)
        val (snap, rows) = load()
        if (renderer !== old) return // changed under us (the switch lock makes this a belt)
        val place = if (current != null) {
            val (a0, item) = current
            a0.entry to QueuePlan(a0.items.map { it.key }, a0.items.indexOf(item), resumeFrom(a0.positions[item.key]))
        } else {
            withContext(Dispatchers.IO) { store.db.nowPlaying() }?.let { row ->
                val entry = snap.entries.firstOrNull { it.id == row.entryId } ?: return@let null
                val stored = row.keys.split('\n').filter { it.isNotEmpty() }
                storedPlan(stored, row.index, row.posMs, snap.order(entry).map { it.key }.toSet()) { 0 }?.let { entry to it }
            }
        }
        val queue = place?.let { (entry, plan) -> queueFor(snap, rows, entry, plan, shuffled = false, RendererKind.SONOS)?.let { entry to it } }
        if (place != null && queue == null) {
            // nothing a speaker can play: the phone stays (an old room is already stopped: back home, paused)
            if (old is SonosRenderer) comeHome(old, play = false)
            publish()
            return
        }
        withContext(writer) {
            val ramp = home.choice()
            home.write(
                RendererChoice(
                    RendererChoice.SONOS, uid, name, queue?.first?.id, queue?.second?.let { (a, p) -> a.items.getOrNull(p.startIndex)?.key },
                    volume = ramp.volume, volumeUid = ramp.volumeUid,
                ),
            )
        }
        if (renderer !== old) return
        attach(sonosRenderer(uid, name))
        // the phone's player stands down to IDLE: a paused Exo keeps its audio focus (§6.7)
        if (old is SonosRenderer) old.release() else local.standDown()
        if (queue == null) {
            active = null
            lastIndex = -1
        } else {
            val (a, p) = queue.second
            start(a, p)
        }
        publish()
    }

    /**
     * Room -> phone (`_renderer_to_box`, 1669-1708): the live classify, the bookmark and the Stop
     * (ours-gated), renderer.json back to the phone, the phone's player, and the queue on the phone
     * from that bookmark - playing on only if the speaker was PLAYING (a paused speaker comes home
     * paused, design 26a §9), or as [resume] says.
     */
    private suspend fun toPhone(resume: Boolean?) {
        val sonos = renderer as? SonosRenderer ?: return
        val wasPlaying = handOff(sonos)
        // the switch is the kid's own tap; still nothing starts while the app is suspended
        comeHome(sonos, (resume ?: wasPlaying) && playAllowed(suspendedNow()))
    }

    private suspend fun comeHome(sonos: SonosRenderer, play: Boolean) {
        if (renderer !== sonos) {
            // another switch already moved on: this room is only to be let go (M2)
            sonos.release()
            return
        }
        val a0 = active?.takeIf { sonos.itemCount() > 0 }
        val item = a0?.items?.getOrNull(sonos.currentIndex())
        withContext(writer) { home.write(RendererChoice.local(home.choice())) }
        if (renderer !== sonos) return
        attach(local)
        sonos.release()
        active = null
        lastIndex = -1
        if (a0 == null || item == null) {
            publish()
            return
        }
        val (snap, rows) = load()
        if (renderer !== local) return
        val plan = homePlan(snap.order(a0.entry).map { it.key }, item.key, resumeFrom(a0.positions[item.key]))
        val a = activeFor(snap, rows, a0.entry, plan.keys, shuffled = false, RendererKind.LOCAL)
        if (a == null) {
            publish()
            return
        }
        start(a, plan, play = play)
    }

    /**
     * The hand-off within [HANDOFF_MS]; past it (the actor busy with a hushed play or the ramp) the
     * last bookmark and an ours-gated Stop queued behind the busy actor, which the release drains (M1).
     */
    private suspend fun handOff(sonos: SonosRenderer): Boolean =
        withTimeoutOrNull(HANDOFF_MS) { sonos.handOff() } ?: run {
            SonosHome.log("sonos: the hand-off took too long - Stop queued, the bookmark from the last poll")
            sonos.abandon()
            false
        }

    /** A start on the speaker failed (§4.2): back to the phone, paused, and one topology refresh for the sheet. */
    private suspend fun lost() = switching.withLock {
        val sonos = renderer as? SonosRenderer ?: return@withLock
        PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.CANT_PLAY_ON_SONOS)
        // anything of ours still on the speaker (a superseded play that landed) is stopped, ours-gated
        handOff(sonos)
        comeHome(sonos, play = false)
        withContext(Dispatchers.IO) { home.rooms.refresh() }
    }

    private companion object {
        /**
         * The hand-off is a 1.5 s classify, a bookmark and a Stop (2 s connect + 5 s read at worst), but it
         * queues behind what the actor holds: a hushed play's settled seek (up to 8 s), the 3 s ramp, a
         * poll. Past this the switch goes on and the Stop stays queued (`abandon`).
         */
        const val HANDOFF_MS = 15_000L

        /** Adopt's look at start: the Wi-Fi wait, the 1.5 s classify and the actor's start, with room. */
        const val ADOPT_MS = 4_000L
    }
}
