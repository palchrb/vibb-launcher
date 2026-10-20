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
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.BuildConfig
import me.vibb.music.R
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.LAUNCHER_PACKAGES
import me.vibb.music.bridge.LiveBridge
import me.vibb.music.bridge.NowPlayingKeys
import me.vibb.music.data.BookmarkRow
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.PositionRow
import me.vibb.music.data.RepeatRow
import me.vibb.music.data.ShuffleRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.logic.CallAction
import me.vibb.music.logic.CallPause
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.QueuePlan
import me.vibb.music.logic.TransportAction
import me.vibb.music.logic.callMode
import me.vibb.music.logic.callPauseStep
import me.vibb.music.logic.callTimerEnded
import me.vibb.music.logic.artOrCover
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.canRepeat
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.moveOnIndex
import me.vibb.music.logic.coverTapAwaitsBook
import me.vibb.music.logic.coverTapPlan
import me.vibb.music.logic.finished
import me.vibb.music.logic.isSpotify
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.nextAction
import me.vibb.music.logic.nowWindow
import me.vibb.music.logic.wantArtDelay
import me.vibb.music.logic.overlapStart
import me.vibb.music.logic.pickPlan
import me.vibb.music.logic.playAllowed
import me.vibb.music.logic.previousAction
import me.vibb.music.logic.publicOnly
import me.vibb.music.logic.heldAhead
import me.vibb.music.logic.heldItems
import me.vibb.music.logic.homePlan
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.resumeOverlapMs
import me.vibb.music.logic.storedPlan
import me.vibb.music.logic.storedRowAwaitsBook
import me.vibb.music.logic.seekGate
import me.vibb.music.logic.smallArtMissing
import me.vibb.music.logic.spotifyInCall
import me.vibb.music.logic.spotifyNetworkLine
import me.vibb.music.logic.sourceId
import me.vibb.music.logic.streamAllowed
import me.vibb.music.logic.swipeTarget
import me.vibb.music.logic.toggledQueue
import me.vibb.music.logic.flippedQueue
import me.vibb.music.logic.withItemArt
import me.vibb.music.media.ItemTag
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.sonos.PlayBodies
import me.vibb.music.sonos.RendererChoice
import me.vibb.music.sonos.SonosHome
import me.vibb.music.sonos.SonosLocks
import me.vibb.music.sonos.SonosRenderer
import me.vibb.music.sonos.RoomVerdict
import me.vibb.music.sonos.SonosSuspension
import me.vibb.music.sonos.SonosTail
import me.vibb.music.sonos.adoptIndex
import me.vibb.music.sonos.adoptPlan
import me.vibb.music.sonos.Room
import me.vibb.music.sonos.SpeakerCache
import me.vibb.music.sonos.allowedRooms
import me.vibb.music.sonos.groupAllowed
import me.vibb.music.sonos.mustLeaveRoom
import me.vibb.music.sonos.ourLiveSession
import me.vibb.music.sonos.roomVerdict
import me.vibb.music.spotify.SpotifyRenderer
import me.vibb.music.sync.ArtWants
import me.vibb.music.sync.CacheThrough
import me.vibb.music.sync.ItemErrors
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.Sync
import me.vibb.music.sync.netCaps
import me.vibb.music.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The queue the player holds, in play order (or shuffled); the service's, read by [LocalRenderer.saveNow]. */
internal class Active(
    val entry: Entry,
    /** The entry's playable items in the shown order (for shuffle off and the list); a flip reverses it (design 39 §3.3). */
    var order: List<PlayItem>,
    var items: List<PlayItem>,
    val positions: MutableMap<String, ItemPosition>,
    val copies: MutableSet<String>,
    val downloads: MutableMap<String, DownloadRow>,
    var shuffled: Boolean,
    /** Cover hash -> its ≤ 300 px copy: only copies that exist, made off the main thread (design
     * 27, QA H2); read and filled on the main thread. */
    val art: MutableMap<String, Uri>,
    /** The player's duration per item, once known (done marks don't depend on the listing's). */
    val durations: MutableMap<String, Long> = mutableMapOf(),
    /** Items whose cached bytes are stale (design 30 §1.6): they play plain http, no cache (`ItemTag.uncached`). */
    val uncached: MutableSet<String> = mutableSetOf(),
    /** [order]'s direction (`Snapshot.shownNewestFirst`, design 39): a `FLIP_ORDER` to it already is nothing to do. */
    var shownNewestFirst: Boolean = false,
    /** The entry's repeat (design 49 §1.2, `Snapshot.repeatOf`): the renderer's through `setModes`; off ends the queue. */
    var repeat: Repeat = Repeat.OFF,
    /**
     * Where the current run of play-time failures began (qa-music-a-code 1): `onError`'s move-on stops once its lap is
     * back there (`moveOnIndex`'s origin); cleared when an item really plays (the 15 s save tick, or a natural end). A
     * new queue is a new [Active]: none.
     */
    var failRunFrom: Int? = null,
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
 * Who plays is the [Renderer] (design 26a §0): the phone's [LocalRenderer] (the ExoPlayer), Spotify's
 * web player on the phone ([SpotifyRenderer], design 26c §5.10: a Spotify entry's) or a Sonos room's
 * [SonosRenderer], changed only by [attach]; the session wraps `KidPlayer(renderer.player)`.
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

    /**
     * Spotify's web player on the phone (design 26c §5.10): made at the first Spotify start (never at a restore
     * of nothing Spotify: no page, no Connect device by itself), kept for the service's life, released in
     * [onDestroy]. A renderer like the others: only [attach] makes it the current one.
     */
    private var spotify: SpotifyRenderer? = null
    private var session: MediaSession? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /**
     * Who plays is settled (design 26a §4.7, QA H5): every custom command and playback resumption
     * awaits it first, so a RESUME after a restart can never start the phone under a playing speaker
     * (vibb's double-playback trap, `sonos_renderer.py` check 2). Complete at once in onCreate when
     * renderer.json says the phone; else after [adopt]'s one classify of the stored room.
     */
    private val rendererReady = RendererGate()

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

    /**
     * The parent's room scope as the library last said (design 26a §5.5; null = every room): the
     * sheet's rows and the renderer's belt read it. The decisions (the switch, the leave) read the
     * library itself.
     */
    @Volatile private var roomScope: List<String>? = null

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
        PlayerHub.capChanged = { (renderer as? SonosRenderer)?.controller?.capChanged() }
        modeInCall = callMode(audio.mode)
        audio.addOnModeChangedListener(mainExecutor, modeListener)
        // Design 26a §4.7: the phone settles at once; a room only after one classify of it (adopt).
        val choice = home.choice()
        scope.launch {
            try {
                // the scope before anything shows a room or adopts one (qa-26a-step3c-code L7)
                roomScope = allowedRoomsNow()
                publish()
                if (startupLook(choice)) adopt(choice)
            } finally {
                rendererReady.settle()
            }
        }
        // Design 26a §5.5, qa-26a-step3c-code M1: the household's groups changed (the sheet's
        // refresh, a scan, the scope's watch below) - the sheet again, and a session on a room whose
        // group now holds a forbidden room leaves it.
        scope.launch {
            home.rooms.state.collect {
                publish()
                if (renderer is SonosRenderer) leaveIfNotAllowed()
            }
        }
        // Design 26a §5.5: a new room scope (the sync applied a library) narrows the sheet, and a
        // session on a room it dropped is stopped - bookmark first - and comes home paused.
        scope.launch {
            store.libraries().map { it?.sonosRooms }.distinctUntilChanged().flowOn(Dispatchers.IO).collect { rooms ->
                roomScope = rooms
                publish()
                leaveIfNotAllowed()
            }
        }
        // M1: while a session plays on a room under a limited scope, the household's groups are read
        // every [SCOPE_WATCH_MS] (one GetZoneGroupState, never a discovery round), so a forbidden room
        // an adult groups into ours is noticed. Every room allowed (the default): nothing is read.
        scope.launch {
            while (true) {
                delay(SCOPE_WATCH_MS)
                val sonos = renderer as? SonosRenderer ?: continue
                if (roomScope != null && sonos.showsPlaying) withContext(Dispatchers.IO) { home.rooms.refreshQuietly() }
            }
        }
        // A picture landing (the sync copied it) refreshes the current item's artwork (QA M5/H2).
        scope.launch { store.coversVersion.collect { refreshArt() } }
        // Design 26c §5.2: a parsed library with no Spotify entry (the phone unbound, the parent removed them): the
        // web player's page, its cookies and storage, the account marker and the token go - also for a page an
        // earlier run left on disk (its marker). An unreadable library keeps everything (qa-21-step2-code H1).
        scope.launch {
            store.libraries().map { lib -> lib != null && lib.entries.none { isSpotify(it) } }.distinctUntilChanged().flowOn(Dispatchers.IO).collect { none ->
                if (none && (spotify != null || withContext(Dispatchers.IO) { SpotifyRenderer.pageAccountFile(this@PlaybackService).exists() })) {
                    spotifyRenderer().forget()
                }
            }
        }
        // qa-26c-emu-music-code N1: the Spotify entries as listed now, for the session's play of a flipped entry
        scope.launch {
            store.libraries().map { lib -> lib?.entries?.filter { isSpotify(it) }?.associateBy { it.id }.orEmpty() }.distinctUntilChanged()
                .flowOn(Dispatchers.IO).collect { spotifyEntriesNow = it }
        }
        // Design 26c §5.9 (qa-26c-step6-code L4): a parsed library drops the live-track rows of entries no longer in
        // context mode (gone, or listed again) - a flip back to context months later starts from Spotify's top
        scope.launch {
            store.libraries().map { lib -> lib?.entries?.filter { isSpotifyContext(it) }?.map { it.key }?.toSet() }.distinctUntilChanged()
                .flowOn(Dispatchers.IO).collect { keys -> if (keys != null) withContext(writer) { store.pruneSpotifyContexts(keys) } }
        }
        publish()
    }

    /** [leaveDisallowedRoom] once the renderer is settled, under the switch lock. */
    private suspend fun leaveIfNotAllowed() {
        rendererReady.await()
        switching.withLock { leaveDisallowedRoom() }
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
        PlayerHub.capChanged = null
        PlayerHub.mutableState.value = PlayerHub.State()
        // Not null (that is a queue's end): Home keeps showing it paused (qa-21-step3-code L3).
        reportNowPlaying(forcePaused = true)
        LiveBridge.release(this)
        session?.release()
        session = null
        if (renderer !== local) local.release()
        // Spotify's web player (design 26c §5.10): its page destroyed, its locks and focus let go (the current one's below)
        spotify?.let { if (it !== renderer) it.release() }
        if (sonos != null) {
            // the dead service hears nothing more (qa-26a-step3b-code L1); the place now, from the last
            // poll (its age term capped at 5 s), and the live second's again in the hand-off
            sonos.onEvent = null
            sonos.saveNow(force = true)
            // Design 26a §6.2 (vibb `_sonos_on_term`: box off = music off): the full stop - the live
            // classify, the bookmark at that second, Stop if ours - goes on after the service, and the
            // speaker, the household and the writer are let go after it. A crash skips this: the speaker
            // plays on until the next start adopts it or a suspension edge stops it.
            // Process-wide (SonosTail, L2): a suspension's standalone path and the next service's adopt wait for it.
            val tail = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            SonosTail.started(
                tail.launch {
                    try {
                        handOff(sonos)
                    } finally {
                        sonos.release()
                        home.release()
                        writer.close()
                        tail.cancel()
                    }
                },
            )
        } else {
            renderer.release()
            home.release()
            writer.close()
            locks.releaseAll()
        }
        // The player is gone, its periods with it (design 30 §1.6). Nothing is settled here - the scope
        // ends now - the next sync's catch-up does it.
        PlayerHub.hold(emptySet(), loaded = true)
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
            // design 26c §5.10, qa-26c-step5b-code M1: a detached web player owns nothing - a play from the account's
            // other app is paused at once, never focus or locks (the session no longer shows it)
            if (old === spotify) spotify?.detached()
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

        /** Only our own commands change shuffle and repeat (design 49 §1.3): Room stays the truth. */
        private val refused = listOf(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)

        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .removeAll(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)
            .build()
        override fun isCommandAvailable(command: Int): Boolean = command !in refused && (command in extra || super.isCommandAvailable(command))

        // the launcher, Bluetooth, the notification: refused (Commands.SHUFFLE / REPEAT are the app's own)
        override fun setRepeatMode(repeatMode: Int) = Unit
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit
        override fun hasNextMediaItem(): Boolean = mediaItemCount > 1
        override fun hasPreviousMediaItem(): Boolean = mediaItemCount > 1
        override fun seekToNext() = skipNext()
        override fun seekToNextMediaItem() = skipNext()
        override fun seekToPrevious() = skipPrevious()
        override fun seekToPreviousMediaItem() = skipPrevious()
        override fun play() {
            if (playAllowed(suspendedNow()) && !restartIfStale()) super.play()
        }
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady && playAllowed(suspendedNow()) && restartIfStale()) return
            if (!playWhenReady || playAllowed(suspendedNow())) super.setPlayWhenReady(playWhenReady)
        }
    }

    /** The library's Spotify entries now, by id (main thread; N5's rule for the plays that don't pass `playEntry`). */
    private var spotifyEntriesNow: Map<Long, Entry> = emptyMap()

    /**
     * A play from the bar, the notification, a headset or the lock (the session's play, qa-26c-emu-music-code N1) of a loaded
     * Spotify entry the library now lists another way (`loadedPlayStale`: list <-> context): its new queue starts, as a
     * cover tap's does - `playEntry` under the switch lock - and the old queue isn't resumed. True when it went that way.
     */
    private fun restartIfStale(): Boolean {
        val a = active ?: return false
        if (renderer.itemCount() == 0 || !loadedPlayStale(a.entry, spotifyEntriesNow[a.entry.id])) return false
        scope.launch {
            rendererReady.await()
            switching.withLock { playEntry(a.entry.id) }
        }
        return true
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
            // A tap's switch shows on the screens until this has run (PlayerHub.State.waiting, logic/Switch.kt).
            val moves = customCommand.customAction in Commands.MOVES
            if (moves) waitingMoves(+1)
            // Main.immediate: with the renderer settled this runs at once, as before step 2.
            scope.launch {
                rendererReady.await()
                switching.withLock {
                    // a room still recorded after an unanswered look: one more look before the phone plays (M1)
                    if (customCommand.customAction in Commands.PLAYS) lookAgainBeforePlaying()
                    when (customCommand.customAction) {
                        Commands.PLAY_ENTRY -> playEntry(args.getLong(Commands.ARG_ENTRY))
                        Commands.PLAY_ITEM -> playItem(args.getLong(Commands.ARG_ENTRY), args.getString(Commands.ARG_KEY).orEmpty())
                        Commands.RESUME -> resumeLast()
                        Commands.RESTORE -> restoreThen(args.getString(Commands.ARG_THEN), args.getLong(Commands.ARG_POSITION), args)
                        Commands.SKIP_TO -> skipTo(args)
                        Commands.SHUFFLE -> toggleShuffle()
                        Commands.REPEAT -> toggleRepeat()
                        Commands.FLIP_ORDER -> flipOrder(args.getString(Commands.ARG_ENTRY_KEY), args.getBoolean(Commands.ARG_NEWEST_FIRST))
                        Commands.SLEEP -> setSleep(args.getInt(Commands.ARG_MINUTES))
                        Commands.OUTPUT -> output(args.getString(Commands.ARG_OUTPUT) ?: Output.PHONE_KEY)
                        Commands.ROOMS_REFRESH -> launch(Dispatchers.IO) { home.rooms.refresh() }
                        Commands.ROOMS_RESCAN -> home.rooms.rescanInBackground()
                        Commands.CALL_HOLD -> launcherCall(true)
                        Commands.CALL_RELEASE -> launcherCall(false)
                    }
                }
            }.invokeOnCompletion { if (moves) waitingMoves(-1) }
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
                    lookAgainBeforePlaying()
                    if (!renderer.kind.onPhone) {
                        // Design 26a §4.7: on Sonos a Bluetooth play button starts the speaker - the queue it
                        // holds, else the stored one, gated (playAllowed above; the controller's mayPlay before
                        // anything is sent) - and hands the session no local items.
                        resumeLast()
                        future.setException(UnsupportedOperationException("playing on the Sonos room"))
                        return@launch
                    }
                    val restored = restore()
                    if (restored == Restored.NotOnPhone) {
                        // the kid's book isn't on the phone (design 26b §5.6, qa-26b-step4b-code M1): "Ikke lastet
                        // ned", nothing starts, the stored queue stays resumable
                        future.setException(UnsupportedOperationException("not on the phone"))
                    } else if (restored !is Restored.Queue) {
                        Resumable.set(this@PlaybackService, false)
                        future.setException(UnsupportedOperationException("nothing to resume"))
                    } else if (isSpotify(restored.active.entry)) {
                        // Design 26c §5.10 (QA H2): a stored Spotify queue takes the room's shape - the web player
                        // attached and the stored queue started through start (gated: playAllowed above, the
                        // renderer's own belt and the network gate), the session handed no local items
                        val (a, index, pos) = restored
                        start(a, QueuePlan(a.items.map { it.key }, index, pos))
                        future.setException(UnsupportedOperationException("playing on Spotify"))
                    } else {
                        val (a, index, pos) = restored
                        // the phone's own player takes Media3's items (never Spotify's web player)
                        usePhoneRenderer(a.entry)
                        active = a
                        applyModes(a)
                        lastIndex = index
                        clearFault()
                        renderer.setAudioAttributes(audioAttributes(speech = a.entry.resume))
                        future.set(MediaSession.MediaItemsWithStartPosition(a.items.map { mediaItem(a, it) }, index, pos))
                        // the session sets the items on the main thread: the queue's claims end after that (design 30 §1.6)
                        handler.post { updateHeld(loaded = true) }
                    }
                }
            }
            return future
        }
    }

    // --- starting a queue ---

    /**
     * The snapshot for a renderer (design 26b §3.2, §5.6; vibb `PREFER_REMOTE`, passed explicitly - 26a
     * §9): [remote] true for a Sonos room (every Storytel book: the speaker fetches it from the CDN),
     * false for the phone (a Storytel book with its copy only). Each call site names its renderer.
     */
    private suspend fun load(remote: Boolean): Pair<Snapshot, List<DownloadRow>> = withContext(Dispatchers.IO) { store.snapshot().forRenderer(remote) to store.db.downloads() }

    /** The queue for whoever plays now (design 26b §5.6's first row). */
    private suspend fun loadForRenderer(): Pair<Snapshot, List<DownloadRow>> = load(remote = !renderer.kind.onPhone)

    /**
     * The queue of [keys] as renderer [kind] plays it. A Sonos room gets only what a speaker can play
     * (design 26a §4.2, `PlayBodies.speakerPlayable`: own files, withdrawn items, HLS outside an NRK
     * series and a public entry's private addresses drop out) - the renderer passed explicitly, never
     * read from a global (§9). The renderer never filters (`Renderer.currentIndex`).
     *
     * On the phone (design 30 §1.6, L4) the item at [start] of [keys] and its next are claimed as held
     * before the rows are read again - a sync can no longer delete them under the new queue - and an
     * NRK/RSS item whose cached bytes are stale (`staleThrough`) plays uncached, its row gone.
     */
    private suspend fun activeFor(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, keys: List<String>, shuffled: Boolean, kind: RendererKind, start: Int = 0): Active? {
        val order = snap.order(entry)
        val byKey = order.associateBy { it.key }
        val items = keys.mapNotNull { byKey[it] }.filter { kind.onPhone || PlayBodies.speakerPlayable(entry, it) }
        if (items.isEmpty()) return null
        var entryRows = rows.filter { it.entryId == entry.id }
        var copies = snap.onPhone[entry.id].orEmpty().toMutableSet()
        var stale = emptySet<String>()
        var claimed = false
        try {
            if (claimsQueue(kind, entry)) {
                val before = PlayerHub.heldLocal
                PlayerHub.claim(heldItems(entry.id, keys, start, start + 1))
                claimed = true
                val through = entry.source == Source.NRK || entry.source == Source.RSS
                val fresh = CacheThrough.queueRows(this, entry, if (through) items else emptyList(), before)
                entryRows = fresh.rows
                stale = fresh.stale
                copies = entryRows.filter { it.done }.map { it.itemKey }.toMutableSet()
            }
            // The entry's cover is made now; an item's own picture only where its small copy exists - the
            // rest when it becomes current (refreshArt), never a decode per queue item up front (QA H2).
            val art = withContext(writer) {
                val map = HashMap<String, Uri>()
                entry.cover?.let { c -> artwork(c)?.let { map[c] = it } }
                for (hash in order.mapNotNull { it.artHash }.toSet()) smallArt(hash).takeIf { it.isFile }?.let { map[hash] = Uri.fromFile(it) }
                map
            }
            return Active(
                entry, order, items,
                snap.positions[entry.key].orEmpty().toMutableMap(),
                copies,
                entryRows.filter { it.done }.associateBy { it.itemKey }.toMutableMap(),
                shuffled,
                art,
                uncached = stale.toMutableSet(),
                shownNewestFirst = snap.shownNewestFirst(entry),
                repeat = snap.repeatOf(entry),
            )
        } catch (e: Throwable) {
            // A queue that never loads ends its claims (qa-30-s3b-code L6): a claimed progressive item would
            // otherwise stay busy - never fetched - until the next queue loads.
            if (claimed) updateHeld(loaded = true)
            throw e
        }
    }

    /**
     * [plan] of [entry] on the renderer [kind]: on a Sonos room only the speaker-playable items, the
     * plan's start moved with them (§4.2); nothing left is "Kan ikke spilles på Sonos" and nothing
     * changes (vibb's `nothing-remote-playable`: the renderer stays).
     */
    private suspend fun queueFor(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, plan: QueuePlan, shuffled: Boolean, kind: RendererKind): Pair<Active, QueuePlan>? {
        val p = if (!kind.onPhone) {
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
        val a = activeFor(snap, rows, entry, p.keys, shuffled, kind, p.startIndex) ?: return null
        return a to p
    }

    private suspend fun playEntry(entryId: Long) {
        val current = active
        if (current != null && current.entry.id == entryId && renderer.itemCount() > 0) {
            // a Spotify entry the library now lists another way (list <-> context, N5) starts its new queue instead
            val now = if (isSpotify(current.entry)) withContext(Dispatchers.IO) { store.library()?.entries?.firstOrNull { it.id == entryId } } else null
            if (!loadedPlayStale(current.entry, now)) {
                renderer.play() // a tile press on the loaded entry unpauses it and never restarts it (vibb)
                return
            }
            spotifyLog("web player: the entry's mode changed under its play - its new queue starts")
            renderer.saveNow(force = true)
        }
        val (snap, rows) = loadForRenderer()
        if (awaitsBook(snap, entryId) { entry, shown, listed -> coverTapAwaitsBook(entry, snap.bookmarks[entry.key], shown, listed) }) return
        val entry = snap.entries.firstOrNull { it.id == entryId } ?: return
        val shuffle = canShuffle(entry) && entry.key in snap.shuffle
        val plan = coverTapPlan(snap.order(entry).map { it.key }, entry.resume, snap.bookmarks[entry.key], shuffle) ?: return
        val (a, p) = queueFor(snap, rows, entry, plan, shuffle, queueKind(renderer.kind, entry)) ?: return
        start(a, p)
    }

    private suspend fun playItem(entryId: Long, key: String) {
        renderer.saveNow()
        val (snap, rows) = loadForRenderer()
        val entry = snap.entries.firstOrNull { it.id == entryId } ?: return
        val shuffle = canShuffle(entry) && entry.key in snap.shuffle
        val current = active?.takeIf { it.entry.id == entryId && it.shuffled }?.items?.map { it.key }
        val plan = pickPlan(snap.order(entry).map { it.key }, key, entry.resume, snap.positions[entry.key]?.get(key), if (shuffle) current else null) ?: return
        val (a, p) = queueFor(snap, rows, entry, plan, shuffle, queueKind(renderer.kind, entry)) ?: return
        start(a, p)
    }

    private suspend fun resumeLast() {
        val current = active
        if (current != null && renderer.itemCount() > 0) {
            // RESUME (Home's card) of a Spotify entry the library now lists another way: its new queue (N1 of the QA)
            if (isSpotify(current.entry)) return playEntry(current.entry.id)
            renderer.play()
            return
        }
        val (a, index, pos) = restore() as? Restored.Queue ?: return
        start(a, QueuePlan(a.items.map { it.key }, index, pos))
    }

    /**
     * The player's controls before play after a restart: the stored queue loaded paused, then the
     * action. One at a time (design 27 QA L10: two quick swipes on a restored queue load it once, then
     * skip in order): every custom command runs under [switching], which main's own `restoring` lock
     * did before the merge with design 26a.
     */
    private suspend fun restoreThen(then: String?, positionMs: Long, args: Bundle) {
        if (active == null || renderer.itemCount() == 0) {
            val (a, index, pos) = restore() as? Restored.Queue ?: return
            start(a, QueuePlan(a.items.map { it.key }, index, pos), play = false)
        }
        when (then) {
            Commands.THEN_NEXT -> skipNext()
            Commands.THEN_PREVIOUS -> skipPrevious()
            Commands.THEN_SEEK -> renderer.seekTo(positionMs)
            Commands.THEN_SHUFFLE -> toggleShuffle()
            Commands.THEN_REPEAT -> toggleRepeat()
            Commands.THEN_SKIP_TO -> skipTo(args)
        }
        publish()
    }

    /** What [restore] found: the stored queue, nothing, or a stored book that isn't on the phone. */
    private sealed interface Restored {
        data class Queue(val active: Active, val index: Int, val posMs: Long) : Restored

        data object Empty : Restored

        data object NotOnPhone : Restored
    }

    /**
     * The stored queue (21 §4.4: back after a restart, paused until a tap). Its current item a Storytel
     * book that isn't on the phone (heard on a room): nothing starts and "Ikke lastet ned" - never the
     * next book there, never a moved bookmark (design 26b §5.6, qa-26b-step4b-code M1) - for RESUME,
     * `restoreThen` and a headset's play alike.
     */
    private suspend fun restore(): Restored {
        val row = withContext(Dispatchers.IO) { store.db.nowPlaying() } ?: return Restored.Empty
        val (snap, rows) = loadForRenderer()
        val stored = row.keys.split('\n').filter { it.isNotEmpty() }
        if (awaitsBook(snap, row.entryId) { entry, shown, listed -> storedRowAwaitsBook(entry, stored, row.index, shown, listed) }) return Restored.NotOnPhone
        val entry = snap.entries.firstOrNull { it.id == row.entryId } ?: return Restored.Empty
        val plan = storedPlan(stored, row.index, row.posMs, snap.order(entry).map { it.key }.toSet()) { startPos(entry, snap.positions[entry.key]?.get(it)) } ?: return Restored.Empty
        val (a, p) = queueFor(snap, rows, entry, plan, row.shuffled, queueKind(renderer.kind, entry)) ?: return Restored.Empty
        return Restored.Queue(a, p.startIndex, p.startPosMs)
    }

    /**
     * The kid's place in [entryId] is a Storytel book the phone hasn't got ([rule] over the entry, the
     * keys [snap] shows - the renderer's view, so a room never waits - and every listed key): says "Ikke
     * lastet ned" and is true; nothing is started or saved (design 26b §5.6, qa-26b-step4b-code M1).
     */
    private fun awaitsBook(snap: Snapshot, entryId: Long, rule: (Entry, List<String>, List<String>) -> Boolean): Boolean {
        val entry = snap.library?.entries?.firstOrNull { it.id == entryId } ?: return false
        if (!rule(entry, snap.order(entry).map { it.key }, snap.listed[entry.id].orEmpty().map { it.key })) return false
        PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
        publish()
        return true
    }

    /**
     * [a] from [plan] on whoever plays: the phone's renderer for the entry ([usePhoneRenderer], design 26c
     * §5.10), or the room. An item that can't play now moves the start on to the next that can ("Ikke lastet
     * ned"); a Spotify entry the network gate refuses (§5.10, QA H1) is loaded paused at its place - the
     * player and its tile carry the renderer's line, "trenger Wi-Fi" or "trenger internett" - and never moves
     * to another item or says NOT_DOWNLOADED. Loading never touches the network or the web player (§5.4).
     */
    private fun start(a: Active, plan: QueuePlan, play: Boolean = true) {
        renderer.saveNow()
        clearFault()
        var index = plan.startIndex.coerceIn(a.items.indices)
        var pos = plan.startPosMs
        val first = playableFrom(a, index, forward = true, wrap = true)
        if (first == null && !isSpotify(a.entry)) {
            if (play) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            updateHeld(loaded = true) // the queue's claims end: it never loads
            return
        }
        if (first != null && first != index) {
            if (play) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            index = first
            pos = startPos(a, index)
        }
        // the old renderer saves its own place and pauses before the queue changes (attach)
        usePhoneRenderer(a.entry)
        active = a
        applyModes(a)
        lastIndex = index
        renderer.setAudioAttributes(audioAttributes(speech = a.entry.resume))
        // Loaded paused: the wake mode goes between prepare and play, as before step 2.
        renderer.load(a.items.map { mediaItem(a, it) }, index, pos, playNow = false)
        updateHeld(loaded = true)
        wakeModeFor(a, index)
        if (play && (first != null || !refuseSpotify())) {
            renderer.play()
            startedPlaying(a, index)
        }
        publish()
        wantArtSoon()
    }

    /**
     * The phone's renderer for [entry] (design 26c §5.10, "Which renderer"): Spotify's web player for a Spotify
     * entry, the phone's own player for every other ([phoneRendererFor]); a room keeps its SonosRenderer (its
     * queue never holds a Spotify item, §4.2). The change goes through [attach] (the old one saved with force
     * and paused first), and the phone's ExoPlayer stands down for the web player as it does for a room (§5.6,
     * QA L1: a paused Exo keeps its audio focus).
     */
    private fun usePhoneRenderer(entry: Entry) {
        if (!renderer.kind.onPhone) return
        val next = if (phoneRendererFor(entry) == RendererKind.SPOTIFY) spotifyRenderer() else local
        if (next === renderer) return
        attach(next)
        if (next !== local) local.standDown()
    }

    /** Spotify's web player, made once and kept (design 26c §5.10; qa-26c-step5a-code: the log is debug-only). */
    private fun spotifyRenderer(): SpotifyRenderer = spotify ?: SpotifyRenderer.create(
        this, store, scope, writer,
        // its queue only while it renders: a detached web player's late events never write another entry's place
        queue = { active.takeIf { renderer === spotify } },
        // a changed word is news for the parent (`reauthorize`: "log in again"): the report is written again and handed to
        // the launcher - not a whole sync (qa-26c-6l-fixes-code L1) - so its next sync, "Sync now" too, carries it (§6l A, F7)
        outcome = { word ->
            scope.launch(writer) { if (store.recordSpotifyOutcome(word, System.currentTimeMillis())) reportSpotifyWord() }
        },
        log = ::spotifyLog,
    ).apply { inCall = spotifyInCall(launcherInCall, modeInCall) }.also { spotify = it }

    /** The report with the web player's new word, to the launcher (§6l A F7; a failure waits for the next sync's). */
    private suspend fun reportSpotifyWord() {
        try {
            Sync.writeReport(this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the next sync's report carries the word
        }
    }

    /**
     * The web player's debug log (design 26c §7.1, qa-26c-step5a-code L8): fixed words from the renderer, the host
     * and the locks - never a token, a uri list or a state - and nothing at all in a release build.
     */
    private fun spotifyLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(SPOTIFY_LOG_TAG, message)
    }

    /**
     * A Spotify entry the network gate refuses now (design 26c §5.10): the web player paused with the gate's line
     * and its word (`SpotifyRenderer.refused`); false when the gate lets it stream after all.
     */
    private fun refuseSpotify(): Boolean {
        val line = spotifyNetworkLine(netCaps(this), store.streamMobileData) ?: return false
        (renderer as? SpotifyRenderer)?.refused(line)
        publish()
        return true
    }

    /** Where item [index] starts when the queue jumps to it: its own position when resume is on. */
    private fun startPos(a: Active, index: Int): Long = startPos(a.entry, a.positions[a.items[index].key])

    private fun startPos(entry: Entry, position: ItemPosition?): Long = if (entry.resume) seekGate(resumeFrom(position)) else 0

    private fun hasCopy(a: Active, item: PlayItem) = item.fileId != null || item.key in a.copies

    private fun playable(a: Active, index: Int, caps: NetCaps? = netCaps(this), streamMobileData: Boolean = store.streamMobileData): Boolean {
        // A speaker fetches by itself and its queue holds only what it can play (§4.2): the phone's gate is the phone's;
        // a Spotify item's is the network alone (design 26c §5.10, QA H1: never GONE). Both obey design 30's streaming
        // switch, never the downloads one.
        val item = a.items[index]
        return itemPlayable(renderer.kind, a.entry, hasCopy(a, item), item.url != null, caps, streamMobileData)
    }

    /** The first index from [index] on (or back) that can play now; null when none. A Spotify entry: [index] or null (§5.10). */
    private fun playableFrom(a: Active, index: Int, forward: Boolean, wrap: Boolean): Int? {
        val caps = netCaps(this)
        val streamMobileData = store.streamMobileData
        return playableIndex(a.entry, a.items.size, index, forward, wrap) { playable(a, it, caps, streamMobileData) }
    }

    /**
     * The move-on past item [from] that can't play (design 49 §1.4, [moveOnIndex]): the next playable item; with repeat
     * all it wraps, one lap at most and never back to [from] - nothing playable in a lap is null (the caller pauses).
     */
    private fun moveOnFrom(a: Active, from: Int): Int? {
        val caps = netCaps(this)
        val streamMobileData = store.streamMobileData
        return moveOnIndex(a.items.size, from, a.repeat, a.failRunFrom) { playable(a, it, caps, streamMobileData) }
    }

    /** A copy plays without holding Wi-Fi awake; a stream needs it (qa-21-step2-code L18). A no-op on Spotify's web
     * player (its own locks, design 26c §5.7) and on a room. */
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
        } else if (isSpotify(a.entry)) {
            // Design 26c §5.10: the web player plays it by its Spotify uri - no ItemTag, no cache key, no url.
            // Only SpotifyRenderer ever loads it (usePhoneRenderer; a room's queue drops it, §4.2; playback
            // resumption never hands it to the session), so it never reaches ItemMediaSourceFactory.
            spotifyItemUri(a.entry, item.key)?.let { b.setUri(it) }
        } else if (a.entry.source == Source.STORYTEL) {
            // Download-only (design 26b §4.4): the book plays from its copy and nothing else. Its uri is
            // the source id (no network scheme, never a minted URL); the data source has no upstream.
            val cacheKey = itemCacheKey(a.entry.key, item.key)
            b.setUri(sourceId(a.entry, item)).setCustomCacheKey(cacheKey)
            b.setTag(ItemTag(cacheKey, hls = false, publicOnly = true, copyOnly = true))
        } else {
            // A copy plays from what was fetched (also when the source withdrew the episode).
            val url = download?.url ?: item.url.orEmpty()
            val hls = download?.hls ?: item.hls
            val cacheKey = itemCacheKey(a.entry.key, item.key)
            val uncached = item.key in a.uncached
            b.setUri(url)
            // An uncached item (design 30 §1.6) has no cache key: no request of it names the stale copy's key,
            // so the length guard has no stored length to trip on again.
            if (!uncached) b.setCustomCacheKey(cacheKey)
            // A public entry's audio never comes from a private address (21b §2.7). What streams is written
            // as it plays (design 30 §1.4: the sink decides at each open), unless its cached bytes are stale (§1.6).
            val through = a.entry.source == Source.NRK || a.entry.source == Source.RSS
            b.setTag(ItemTag(cacheKey, hls, publicOnly(a.entry), through = through, uncached = uncached, ref = ItemRef(a.entry.id, item.key)))
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
        artUri(a, item)?.let { meta.setArtworkUri(it) }
        item.durationMs?.let { meta.setDurationMs(it) }
        return b.setMediaMetadata(meta.build()).build()
    }

    /**
     * The item's own picture when its small copy exists, else the entry's cover ([artOrCover]); main
     * thread. A copy the prune deleted counts as missing (QA L8): the series cover shows, never nothing.
     */
    private fun artUri(a: Active, item: PlayItem): Uri? = artOrCover(item, a.entry) { hasCopy(a, it) }?.let { a.art[it] }

    private fun hasCopy(a: Active, hash: String): Boolean = a.art[hash]?.path?.let { File(it).isFile } == true

    private fun smallArt(hash: String) = File(store.smallArtDir, "$hash.jpg")

    /**
     * The current item's picture landed after its queue was built (design 27, QA H2): its small copy
     * is made off the main thread, then the item's metadata is swapped in place
     * (`Renderer.replaceItem`: on the phone `replaceMediaItem`, the same source so playback goes on;
     * on a Sonos room only the session player's item, no SOAP) - the notification, the lock's tile
     * and Home's card show it - and the launcher's snapshot gets it too.
     */
    private fun refreshArt() {
        val a = active ?: return
        val queued = a.items.getOrNull(renderer.currentIndex()) ?: return
        scope.launch {
            // The item's art as the store has it now: a listing applied during the queue may have given
            // it a picture its queued copy doesn't know (QA L7). Own files' art comes with the library.
            val fresh = if (queued.fileId != null) queued.artHash else withContext(writer) { store.db.itemArt(a.entry.id, queued.key) }
            if (active !== a) return@launch
            val item = if (fresh == queued.artHash) queued else queued.copy(artHash = fresh)
            if (item !== queued) a.items = withItemArt(a.items, item.key, fresh)
            val missing = smallArtMissing(listOf(item.artHash, a.entry.cover)) { hasCopy(a, it) }
            val made = withContext(writer) { missing.filter { store.coverFile(it).isFile }.mapNotNull { h -> artwork(h)?.let { h to it } } }
            if (active !== a) return@launch
            a.art.putAll(made)
            missing.filter { h -> made.none { it.first == h } }.forEach { a.art.remove(it) }
            val uri = artUri(a, item)
            val mediaId = "${a.entry.id}/${item.key}"
            // through the renderer (design 26a): on a Sonos room the session's player shows the same art
            val index = (0 until renderer.itemCount()).firstOrNull { renderer.itemAt(it)?.mediaId == mediaId } ?: return@launch
            val old = renderer.itemAt(index) ?: return@launch
            if (old.mediaMetadata.artworkUri != uri) {
                // The series cover when the item's copy went and nothing replaces it (QA L8).
                renderer.replaceItem(index, old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon().setArtworkUri(uri).build()).build())
            }
            reportNowPlaying(refresh = true)
        }
    }

    /** A ≤ 300 px copy of a cover, for the media notification and the launcher's media views (off the main thread). */
    private fun artwork(hash: String): Uri? {
        val small = smallArt(hash)
        if (!small.isFile) {
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

    /** When [sendWantArt] last ran (`SystemClock.elapsedRealtime`), null before the first. */
    private var wantArtAt: Long? = null

    /**
     * A queue start or an item change: the want set goes to the launcher at once (the user, 2026-10-19: the playing
     * episode's picture comes now), a run of skips coalesced to one more send at the end of `WANT_ART_GAP_MS`
     * ([wantArtDelay]).
     */
    private fun wantArtSoon() {
        handler.removeCallbacks(wantArtTick)
        handler.postDelayed(wantArtTick, wantArtDelay(wantArtAt, android.os.SystemClock.elapsedRealtime()))
    }

    /** The pictures shown (the current item and its neighbours) are noted, and the want set sent when
     * its `now` list has something missing that it didn't ask for last time (LiveBridge.wantArt). */
    private fun sendWantArt() {
        val a = active ?: return
        wantArtAt = android.os.SystemClock.elapsedRealtime()
        val shown = nowWindow(a.items, renderer.currentIndex(), reach = 1).map { it.artHash }
        scope.launch {
            val want = withContext(writer) {
                store.noteShown(shown)
                ArtWants.compute(this@PlaybackService)
            }
            LiveBridge.wantArt(want.now, want.later)
        }
    }

    // --- transport ---

    /**
     * `SKIP_TO` (a swipe, design 27 §3.2): as next or previous, but to the item picked - never a
     * restart; an unplayable one skips on in the swipe's direction (goTo), paused stays paused. Only
     * for the entry loaded. The target is an index of `Active.items`, the renderer's queue - on a
     * Sonos room the speaker-playable items - and goes through [goTo] -> `renderer.seekTo` (a room's
     * `stepTo`: from 0 while playing, design 26a §4.3), never ExoPlayer directly.
     */
    private fun skipTo(args: Bundle) {
        val a = active ?: return
        if (renderer.itemCount() == 0) return
        val loaded = a.entry.id == args.getLong(Commands.ARG_ENTRY, -1)
        val target = swipeTarget(loaded, a.items.map { it.key }, renderer.currentIndex(), args.getString(Commands.ARG_KEY).orEmpty()) ?: return
        goTo(a, target, args.getBoolean(Commands.ARG_FORWARD, true))
    }

    private fun skipNext() {
        val a = active ?: return
        // a Spotify context (design 26c §5.9): the web player's next - Spotify's order - never the one item's rules
        if (stepsOnWebPlayer(a.entry)) return stepOnWebPlayer(forward = true)
        when (val action = nextAction(renderer.currentIndex(), renderer.itemCount())) {
            is TransportAction.GoTo -> goTo(a, action.index, forward = true)
            else -> Unit
        }
    }

    private fun skipPrevious() {
        val a = active ?: return
        if (stepsOnWebPlayer(a.entry)) return stepOnWebPlayer(forward = false)
        val duration = renderer.durationMs() ?: a.items.getOrNull(renderer.currentIndex())?.durationMs
        when (val action = previousAction(renderer.currentIndex(), renderer.itemCount(), renderer.positionMs(), duration)) {
            TransportAction.Restart -> renderer.seekTo(0L)
            is TransportAction.GoTo -> goTo(a, action.index, forward = false)
            TransportAction.Stay -> Unit
        }
    }

    /** Next or previous in a Spotify context (design 26c §5.9): the SDK's, gated as every step (the network line). */
    private fun stepOnWebPlayer(forward: Boolean) {
        val web = renderer as? SpotifyRenderer ?: return
        if (!web.stepsInContext) return
        if (refuseSpotify()) {
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.STAYED)
            return
        }
        web.step(forward)
    }

    private fun goTo(a: Active, index: Int, forward: Boolean) {
        renderer.saveNow()
        val target = playableFrom(a, index, forward, wrap = true) ?: run {
            // design 26c §5.10: Spotify's gate is the network, not the item - its line, no jump, never NOT_DOWNLOADED;
            // the screens drop the step they showed at once (§6l A, F3)
            if (isSpotify(a.entry)) {
                refuseSpotify()
                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.STAYED)
            } else {
                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            }
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
        if (isSpotifyContext(a.entry)) {
            // a playlist context (design 49 §1.7): Spotify's own shuffle - one synthetic item, no order of ours
            a.shuffled = on
        } else {
            val index = renderer.currentIndex().coerceIn(a.items.indices)
            val current = a.items[index]
            val byKey = a.order.associateBy { it.key }
            val items = toggledQueue(a.order.map { it.key }, current.key, on).mapNotNull { byKey[it] }
            // Keep the current item playing, the new order after it (every renderer: Spotify's list mode re-feeds its
            // tracker and marks its window stale, design 49 §1.6).
            renderer.reorder(index, items.drop(1).map { mediaItem(a, it) })
            a.items = items
            a.shuffled = on
            lastIndex = 0
        }
        applyModes(a)
        val entryKey = a.entry.key
        scope.launch(writer) { store.db.upsertShuffle(ShuffleRow(entryKey, on)) }
        renderer.saveNow()
        publish()
    }

    /**
     * The repeat button (design 49 §1.2-§1.3): off -> all -> one for the loaded entry, remembered in Room's `repeat`
     * (never synced or reported) and handed to the renderer, which repeats by its own means. Not a play: nothing starts.
     */
    private fun toggleRepeat() {
        val a = active ?: return
        if (!canRepeat(a.entry)) return
        val next = a.repeat.next()
        a.repeat = next
        applyModes(a)
        val entryKey = a.entry.key
        scope.launch(writer) { store.db.upsertRepeat(RepeatRow(entryKey, next.word)) }
        publish()
    }

    /** The loaded queue's shuffle and repeat to the renderer (before every load, after a toggle, at an adopt). */
    private fun applyModes(a: Active) = renderer.setModes(a.shuffled, a.repeat)

    /**
     * The kid's order flip (design 39 §3.3), after the list wrote its row (`Store.setFlip`, which also flipped the
     * stored queue): for the loaded entry only, never while shuffled, the order goes the [newestFirst] way and the
     * queue becomes its own keys in it from the current item ([flippedQueue]: a room's speaker-playable queue stays
     * that, qa-39-design M1), which plays on. The renderer reorders (the phone, a Sonos room, Spotify's list mode).
     */
    private fun flipOrder(entryKey: String?, newestFirst: Boolean) {
        val a = active ?: return
        if (a.entry.key != entryKey || a.shuffled || a.shownNewestFirst == newestFirst || renderer.itemCount() == 0) return
        val index = renderer.currentIndex().coerceIn(a.items.indices)
        val current = a.items[index]
        a.order = a.order.asReversed().toList()
        a.shownNewestFirst = newestFirst
        val byKey = a.order.associateBy { it.key }
        val items = flippedQueue(a.order.map { it.key }, a.items.map { it.key }, current.key).mapNotNull { byKey[it] }
        renderer.reorder(index, items.drop(1).map { mediaItem(a, it) })
        a.items = items
        lastIndex = 0
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
            // 15 s of real play (the tick starts when it plays): a run of failures ends here (qa-music-a-code 1) - not at
            // the first isPlaying, which an item that fails on its first frames may give too
            active?.failRunFrom = null
            renderer.saveNow()
            // The launcher's snapshot keeps the place too, so Home is right after a crash or a
            // dead battery (qa-21-step4-code L4).
            reportNowPlaying(refresh = true)
            // The held window moves with the position (design 30 §1.6, qa-30-s3b-code L5: HELD_AHEAD_MS).
            updateHeld()
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
                // never a Spotify context's (design 26c §5.9): the web player's is the live track's, the item is never done
                if (item != null && duration != null && keepsDuration(a.entry, item.key)) a.durations[item.key] = duration
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
            is RendererEvent.Error -> onError(event.code, event.gated)
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
            a.failRunFrom = null // an item played to its end: no run of failures (qa-music-a-code 1)
        }
        lastIndex = index
        wakeModeFor(a, index)
        wantArtSoon()
        refreshArt()
        if (isSpotify(a.entry)) {
            // Design 26c §5.10: a load or a step was gated before it; Spotify's own advance on a network the gate
            // refuses now (mobile data not allowed, roaming) pauses with the line - never moved on.
            if (auto && !playable(a, index)) refuseSpotify()
        } else if (!playable(a, index)) {
            // The queue moves on (21 §4.2's one line beyond the mockup).
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            val nextIndex = moveOnFrom(a, index)
            if (nextIndex != null) {
                renderer.seekTo(nextIndex, startPos(a, nextIndex))
            } else {
                renderer.pause()
                // The bookmark moves on to the item that plays next, not back to the finished one.
                saveBookmark(a, index)
            }
            return
        }
        if (resumesOnAdvance(auto, renderer.kind)) {
            // A natural advance resumes an item heard before (vibb: in-session jumps resume too); on a
            // speaker the next item starts at 0 (vibb `_sonos_poller`, design 26a §4.3), and on Spotify
            // where Spotify starts it - nothing is sent (design 26c §5.10, QA M4).
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
        // the web player hears a system call by the mode too (emulator round 2026-10-14 N3)
        spotify?.inCall = spotifyInCall(launcherInCall, modeInCall)
        callStep()
    }

    private fun launcherCall(inCall: Boolean) {
        launcherInCall = inCall
        // the web player starts nothing during a call (qa-26c-6l-fixes-code L2), as the phone's own player's refused focus
        spotify?.inCall = spotifyInCall(launcherInCall, modeInCall)
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
        val inCall = callOn(launcherInCall, modeInCall, r.kind)
        if (callRenderer != null && callRenderer !== r) {
            // the renderer changed under the call: nothing of ours to play again
            callPause = CallPause(inCall = callPause.inCall)
            callRenderer = null
        }
        // a web player the WebView paused (the ring's focus loss can beat CALL_HOLD) still wants to play: the call rule's
        // to pause and play again (qa-26c-6l-fixes-code M1) - only a fresh hold: an old one is another app that took the audio
        // for good, never started again by a call's end (qa-26c-emu-music-code M1)
        val playing = r.showsPlaying || (r as? SpotifyRenderer)?.heldRecently() == true
        // a Spotify line is a paused web player; a room's four mean the speaker isn't ours to count (design 26c §5.8)
        val paused = pausedForCall(playing, r.itemCount(), r.rendererState)
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
        markPlayed(a, item)
    }

    /**
     * The current item failed. Design 30 §1.6: [gated] - the streaming gate refused the network (a copy's
     * holes, an item that turned not playable) - is "Ikke lastet ned - trenger Wi-Fi" with no item error;
     * an `http_416` on the phone for an item without a done copy - the length guard's changed source, or
     * the server's own - drops the row and plays the item on uncached from where it was ([playOnUncached]).
     */
    private fun onError(code: String, gated: Boolean = false) {
        val a = active ?: return
        val index = renderer.currentIndex()
        val item = a.items.getOrNull(index) ?: return
        val entryId = a.entry.id
        val copy = a.downloads[item.key]
        if (code == "http_416" && !gated && copy == null && item.url != null && item.key !in a.uncached &&
            renderer.kind == RendererKind.LOCAL && a.entry.source != Source.STORYTEL
        ) {
            playOnUncached(a, index, item)
            return
        }
        when {
            item.fileId != null || gated -> Unit
            copy != null -> if (code == "bad_media") {
                // A copy that isn't playable media: drop it, so the next sync fetches it again.
                a.copies -= item.key
                a.downloads -= item.key
                scope.launch(writer) {
                    MediaCache.remove(this@PlaybackService, copy.cacheKey, copy.hls)
                    store.db.deleteDownload(entryId, item.key)
                }
            }
            // a listed url's stream, or a Spotify track the account can't play (`unplayable`, design 26c §5.8); only
            // the record goes to the writer - the move-on below stays synchronous on the main thread (§5.10: the
            // web player reads the seekTo/prepare/play made inside its Error("unplayable") as its own move-on)
            recordsItemError(a.entry, hasUrl = item.url != null) ->
                scope.launch(writer) { ItemErrors.record(this@PlaybackService, entryId, item.key, code) }
        }
        // Spotify's offline isn't the item's (its gate is the network, §5.10): always "Kan ikke spilles" - its web
        // player never reports `gated` (that is the phone's own player's streaming gate, design 30 §1.6)
        val offline = gated ||
            !isSpotify(a.entry) && copy == null && item.fileId == null && !streamAllowed(netCaps(this@PlaybackService), store.streamMobileData)
        PlayerHub.mutableMessages.tryEmit(if (offline) PlayerHub.Message.NOT_DOWNLOADED else PlayerHub.Message.CANT_PLAY)
        renderer.saveNow()
        if (a.failRunFrom == null) a.failRunFrom = index // the run's first failure: its lap ends back here
        val nextIndex = moveOnFrom(a, index)
        if (nextIndex != null) {
            renderer.seekTo(nextIndex, startPos(a, nextIndex))
            renderer.prepare()
            renderer.play()
        } else {
            // Nothing after it can play now: stay here, paused (a tap on play tries again).
            renderer.pause()
        }
    }

    /**
     * The source of [item] changed under its partial (design 30 §1.6): its row goes (the bytes become
     * orphans, swept once the player lets go), and it is replaced in place by a plain http source
     * (`ItemTag.uncached`) at the position it failed at, the play state as it was; no item error.
     */
    private fun playOnUncached(a: Active, index: Int, item: PlayItem) {
        val pos = renderer.positionMs()
        val entryId = a.entry.id
        a.uncached += item.key
        scope.launch(writer) { store.db.deleteDownload(entryId, item.key) }
        // The queue loaded again, never `replaceItem` (qa-30-s3b-code H1): Media3's replaceMediaItem keeps the
        // media source whenever `canUpdateMediaItem` says yes - it compares the uri (and for progressive the
        // cache key, for HLS the stream keys), never the tag - so the item would stay on the cache-backed source
        // and trip the guard again. A load builds every source anew; the play state stays as it was.
        renderer.load(a.items.map { mediaItem(a, it) }, index, pos, playNow = false)
    }

    /** An item started: it leaves Nytt (21 §4.5), and on the phone it is played (design 30 §1.5). */
    private fun startedPlaying(a: Active, index: Int) {
        val entryId = a.entry.id
        val key = a.items[index].key
        // `playedFromTray` as one statement: a read and a replace here would race the sync's tray
        // write and bring the row back (design 29 §1.6, QA M3).
        scope.launch(writer) {
            if (store.db.deleteNews(entryId, key) > 0) NewNotifier.update(this@PlaybackService, store.snapshot())
        }
        markPlayed(a, a.items[index])
    }

    /**
     * The phone's own player started [item] (design 30 §1.5): its row's `playedAt`, so outside the keep
     * set it stays as a played extra. Only an existing row - the sink made it for a stream, the job for a
     * copy - and never by the current depth (an item played at depth 0 has its row pruned anyway); never a
     * Storytel book (never streamed, never an extra) or an own file, never on a Sonos room (nothing passes
     * through the phone's cache). Also when it really plays ([onIsPlayingChanged]): a stream's sink makes
     * its row only at its first open, after the start.
     */
    private fun markPlayed(a: Active, item: PlayItem) {
        if (renderer.kind != RendererKind.LOCAL || item.fileId != null || a.entry.source == Source.STORYTEL) return
        val entryId = a.entry.id
        val now = System.currentTimeMillis()
        scope.launch(writer) { store.db.markPlayed(entryId, item.key, now) }
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
        // a Spotify context's item is never done (design 26c §5.9): its end drops its place and its live track only
        a.items.getOrNull(renderer.currentIndex())?.takeIf { keepsDuration(a.entry, it.key) }?.let { markDone(a, it) }
        active = null
        lastIndex = -1
        Resumable.set(this, false)
        val entryKey = a.entry.key
        val context = isSpotifyContext(a.entry)
        scope.launch(writer) {
            store.db.deleteBookmark(entryKey)
            store.db.clearPartialPositions(entryKey)
            store.db.clearNowPlaying()
            if (context) store.recordSpotifyContext(entryKey, null)
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
        // a Spotify context's item carries its live track's title (design 26c §5.9: the web player swaps it in place)
        val live = if (a != null && isSpotifyContext(a.entry)) renderer.itemAt(renderer.currentIndex())?.mediaMetadata?.title?.toString() else null
        val title = live ?: item?.title
        val key = Triple(a?.entry?.id, item?.key?.let { "$it\n$title" }, playing)
        if (key == sentNowPlaying && !refresh) return
        sentNowPlaying = key
        if (a == null || item == null) {
            LiveBridge.setNowPlaying(null)
            return
        }
        // The item's own picture once its copy is on the phone, else the entry's cover (artOrCover):
        // every report looks again, so Home's card and the lock's tile get a late picture (design 27).
        val art = artUri(a, item)?.path?.let { File(it) }
            ?.takeIf { it.isFile && it.length() <= 64 * 1024 }?.let { runCatching { it.readBytes() }.getOrNull() }
        LiveBridge.setNowPlaying(
            Bundle().apply {
                putString(NowPlayingKeys.ENTRY, a.entry.name)
                putString(NowPlayingKeys.ITEM, title)
                putLong(NowPlayingKeys.POSITION_MS, renderer.positionMs())
                putLong(NowPlayingKeys.DURATION_MS, renderer.durationMs() ?: a.durations[item.key] ?: item.durationMs ?: 0)
                putBoolean(NowPlayingKeys.PLAYING, playing)
                art?.let { putByteArray(NowPlayingKeys.ART, it) }
            },
        )
    }

    private fun publish() {
        updateHeld()
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
            repeat = a?.repeat ?: Repeat.OFF,
            playing = renderer.showsPlaying,
            durationMs = renderer.durationMs() ?: a?.items?.getOrNull(renderer.currentIndex())?.durationMs,
            sleepEndsAt = sleepEndsAt,
            sleepMinutes = sleepMinutes,
            outputs = outputs,
            output = output,
            rendererState = renderer.rendererState,
            // only the rooms the parent allows (design 26a §5.5): a group by its coordinator
            rooms = allowedRooms(home.rooms.state.value, roomScope),
            waiting = waitingMoves.get() > 0,
        )
    }

    /**
     * The held set from the player's timeline (design 30 §1.6): on the phone the current item and the next
     * one ExoPlayer would play; nothing on a Sonos room or with nothing loaded. Main thread, at every
     * [publish] (every item and timeline change goes through it) and after a load ([loaded]: the new queue's
     * claims end). An item that leaves the set is settled on IO, never on [writer] (§1.5); a settled one
     * is on the phone at once, in this queue too.
     */
    private fun updateHeld(loaded: Boolean = false) {
        val a = active
        val held = if (a == null || renderer.kind != RendererKind.LOCAL || renderer.itemCount() == 0) {
            emptySet()
        } else {
            // The current item, the next, and with short items what ExoPlayer may have opened after it (L5).
            val current = renderer.currentIndex()
            val length = { i: Int -> a.items.getOrNull(i)?.let { a.durations[it.key] ?: it.durationMs } }
            val remaining = length(current)?.let { (it - renderer.positionMs()).coerceAtLeast(0) }
            heldItems(a.entry.id, a.items.map { it.key }, heldAhead(current, renderer.player.nextMediaItemIndex, a.items.size, remaining, length))
        }
        for (ref in PlayerHub.hold(held, loaded)) {
            scope.launch(Dispatchers.IO) {
                val done = CacheThrough.settle(this@PlaybackService, ref) ?: return@launch
                withContext(Dispatchers.Main) {
                    active?.takeIf { it.entry.id == ref.entryId }?.let { q ->
                        q.copies += ref.itemKey
                        q.downloads[ref.itemKey] = done
                    }
                }
            }
        }
    }

    /** [Commands.MOVES] accepted and not yet run (a command's end, a cancel too: invokeOnCompletion). */
    private val waitingMoves = AtomicInteger()

    private fun waitingMoves(delta: Int) {
        val waiting = waitingMoves.addAndGet(delta) > 0
        PlayerHub.mutableState.update { it.copy(waiting = waiting) }
    }

    // --- the renderer: the phone or a Sonos room (design 26a §4.6) ---

    /** A room's renderer; its holds on the service's locks are its own (qa-26a-step3b-code L4). */
    private fun sonosRenderer(uid: String, name: String?): SonosRenderer {
        val owner = Any()
        return SonosRenderer(
            this, home, store, scope, writer, { active }, uid, name,
            onArmedPlaying = { locks.hold(owner, it) },
            roomAllowed = { groupAllowed(roomScope, home.rooms.state.value.rooms, it) },
            // M1: the session followed its stream to another coordinator - is that room allowed?
            onFollowed = { scope.launch { leaveIfNotAllowed() } },
        )
    }

    /** When the stored room was last looked at (elapsed; [lookAtRoom]). */
    private var lastRoomLook = Long.MIN_VALUE / 2

    /** Adopt at start ([lookAtRoom] under the switch lock). */
    private suspend fun adopt(choice: RendererChoice) = switching.withLock { lookAtRoom(choice) }

    /**
     * The look at the room renderer.json names (design 26a §4.7, vibb `_sonos_poller`'s
     * reconciliation): a destroyed service's stop finished first ([SonosTail]), then one classify of
     * the room in its actor ([SonosController.LIVE_BUDGET_MS]) and [roomVerdict]:
     * - our stream playing or paused -> adopted with zero transport commands: the stored entry's
     *   queue rebuilt ([adoptPlan]: the stored order and shuffle, the speaker-playable filter, the
     *   index by uri), the room the session's player; with no built uri stored yet, the speaker's own
     *   when it plays an item of the stored entry. Ours but its item gone from the library: one Stop
     *   (box off = music off) and the phone;
     * - a definite other answer (foreign, empty, stopped) -> renderer.json back to the phone;
     * - no answer (no Wi-Fi, unreachable, late) -> the phone renders, but the room stays recorded:
     *   the next start, the next play ([lookAgainBeforePlaying]) and the suspension look again
     *   (qa-26a-step3b-code M1: a transient never orphans a speaker playing our stream).
     * Nothing ever starts here; the bar comes back paused from the stored row unless the room plays on.
     */
    private suspend fun lookAtRoom(choice: RendererChoice) {
        SonosTail.join()
        lastRoomLook = SystemClock.elapsedRealtime()
        val uid = choice.uid ?: return
        // a room is looked at before the phone plays - its own player or Spotify's web player (design 26c §5.10)
        if (!renderer.kind.onPhone) return
        // the room's view (design 26b §5.6, QA H2): a Storytel book that isn't on the phone plays there too -
        // the phone's view would lose it, and the look would stop the kid's book (stopUnadopted)
        val (snap, rows) = load(remote = true)
        val entry = snap.entries.firstOrNull { it.id == choice.entryId }
        val order = entry?.let { snap.order(it) }.orEmpty()
        val ofEntry: (String) -> Boolean = { u -> entry != null && adoptIndex(entry, order, u) != null }
        val sonos = sonosRenderer(uid, choice.name)
        // ours: the session goes on with the live TrackURI (vibb posts `snap.uri` to /adopt; design 26b §5.3) -
        // for a Storytel book the signed URL the speaker holds, which the stored marker only names
        val live = withTimeoutOrNull(ADOPT_MS) {
            sonos.probe(choice.uri.orEmpty()) { f -> if (ourLiveSession(choice, f, ofEntry)) f.uri?.takeIf { it.isNotEmpty() } else null }
        }
        val verdict = roomVerdict(choice, live, ofEntry)
        val uri = live?.uri?.takeIf { verdict == RoomVerdict.OURS_LIVE }.orEmpty()
        val queue = if (verdict == RoomVerdict.OURS_LIVE && entry != null) adoptedQueue(snap, rows, entry, uri) else null
        if (queue == null || live == null || !renderer.kind.onPhone) {
            if (verdict == RoomVerdict.OURS_LIVE) sonos.stopUnadopted()
            sonos.release()
            if (verdict == RoomVerdict.NO_ANSWER) {
                SonosHome.log("sonos: no answer from ${choice.name ?: uid} - the phone renders, the room stays recorded and is looked at again")
                return
            }
            SonosHome.log("sonos: no live session of ours on ${choice.name ?: uid} ($verdict) - the phone renders, nothing starts")
            withContext(writer) { home.write(RendererChoice.local(home.choice())) }
            return
        }
        val (a, index) = queue
        // renderer.json through storedUri on both branches (design 26b §5.3, QA M1): the stored built uri kept,
        // or with none stored yet the live one; a Storytel book's marker either way, never the signed URL
        val adopted = choice.adopted(a.entry, a.items[index].key, uri)
        if (adopted != choice) withContext(writer) { home.write(adopted) }
        attach(sonos)
        active = a
        applyModes(a)
        lastIndex = index
        sonos.adopt(a.items.map { mediaItem(a, it) }, index, uri, live)
        publish()
        // a room the parent's scope no longer allows: adopted only to be bookmarked and stopped (§5.5)
        leaveDisallowedRoom()
    }

    /** The parent's room scope now, from the library (design 26a §5.5; null = every room). */
    private suspend fun allowedRoomsNow(): List<String>? = withContext(Dispatchers.IO) { store.library() }?.sonosRooms

    /** The household's rows as the speaker cache has them now (the groups: who sounds with whom). */
    private suspend fun roomsNow(): List<Room> = withContext(Dispatchers.IO) { SpeakerCache.rooms(home.cache.load()) }

    /**
     * Design 26a §5.5: a session on a room the parent's scope doesn't allow as the household is
     * grouped now (the sync applied a library that dropped it, adopt found our stream there, a
     * forbidden room was grouped into ours, the stream migrated to a forbidden coordinator) leaves it -
     * the hand-off (the live classify, the bookmark at that second, the ours-gated Stop) - and the phone
     * takes the queue, paused, as when a start fails. Nothing starts. Called under the switch lock.
     */
    private suspend fun leaveDisallowedRoom() {
        val sonos = renderer as? SonosRenderer ?: return
        if (!mustLeaveRoom(sonos.uid, roomsNow(), allowedRoomsNow())) return
        SonosHome.log("sonos: ${sonos.uid} isn't allowed on this phone (now) - bookmark, stop, the phone (paused)")
        PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.ROOM_NOT_ALLOWED)
        handOff(sonos)
        comeHome(sonos, play = false, quiet = true)
    }

    /** The stored entry's queue on a room ([adoptPlan]) and the index of [uri] in it; null when it can't be rebuilt. */
    private suspend fun adoptedQueue(snap: Snapshot, rows: List<DownloadRow>, entry: Entry, uri: String): Pair<Active, Int>? {
        val row = withContext(Dispatchers.IO) { store.db.nowPlaying() }?.takeIf { it.entryId == entry.id }
        val stored = row?.keys?.split('\n')?.filter { it.isNotEmpty() }
        val plan = adoptPlan(entry, snap.order(entry), stored, row?.shuffled == true, uri) ?: return null
        val a = activeFor(snap, rows, entry, plan.keys, plan.shuffled, RendererKind.SONOS) ?: return null
        if (a.items.map { it.key } != plan.keys) return null
        return a to plan.index
    }

    /**
     * A room still recorded after an unanswered look (M1): before the phone plays, one more look -
     * at most every [ROOM_LOOK_MS] - so Home's RESUME never starts the phone under our stream still
     * playing there (§4.7's trap). Nothing recorded: nothing to do.
     */
    private suspend fun lookAgainBeforePlaying() {
        if (!renderer.kind.onPhone || SystemClock.elapsedRealtime() - lastRoomLook < ROOM_LOOK_MS) return
        val choice = withContext(writer) { home.choice() }
        if (choice.sonos) lookAtRoom(choice)
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
            // Design 26c §5.7 (QA L11): Spotify's page goes whatever the state - the bookmark with force, the pause,
            // the page destroyed - so no Connect device "Vibb" exists while the app is suspended
            spotify?.suspended()
            val sonos = renderer as? SonosRenderer
            if (sonos == null) {
                if (renderer !== spotify) renderer.pause()
                // a room still recorded after an unanswered look (M1): the standalone look, stop and park
                if (withContext(writer) { home.choice() }.sonos) SonosSuspension.standalone(this@PlaybackService)
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
        if (!renderer.kind.onPhone) toPhone(resume = null)
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
        // The parent's room scope (design 26a §5.5): a room outside it is refused before anything moves.
        if (!groupAllowed(allowedRoomsNow(), roomsNow(), uid)) {
            SonosHome.log("sonos: $uid (or a room grouped with it) isn't allowed on this phone - refused")
            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.ROOM_NOT_ALLOWED)
            publish()
            return
        }
        val old = renderer
        if (old is SonosRenderer && old.uid == uid) {
            old.play()
            publish()
            return
        }
        val name = withContext(Dispatchers.IO) { home.label(uid) }
        val current = active?.takeIf { old.itemCount() > 0 }?.let { a0 -> a0.items.getOrNull(old.currentIndex())?.let { a0 to it } }
        if (old is SonosRenderer) handOff(old) else old.saveNow(force = true)
        // the room's view (design 26b §5.6, QA H2): every Storytel book, downloaded or not
        val (snap, rows) = load(remote = true)
        if (renderer !== old) return // changed under us (the switch lock makes this a belt)
        val place = if (current != null) {
            val (a0, item) = current
            if (a0.entry.source == Source.STORYTEL) {
                // the phone's queue holds only the downloaded books: the room re-plans over the series' remote
                // order at the current book and place (design 26b §5.6), or it would skip the others and end early
                a0.entry to homePlan(snap.order(a0.entry).map { it.key }, item.key, resumeFrom(a0.positions[item.key]))
            } else {
                // by key: refreshArt may have replaced the items with copies meanwhile (a late picture,
                // `withItemArt`), and an object lookup would start item 0 (qa-26a-merge27-code L1)
                a0.entry to QueuePlan(a0.items.map { it.key }, a0.items.indexOfFirst { it.key == item.key }, resumeFrom(a0.positions[item.key]))
            }
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

    /**
     * The phone takes over from [sonos] (the switch home, a lost start, the suspension, the room scope):
     * the queue on the phone from the hand-off's bookmark, playing on only with [play]. A Storytel book
     * that isn't on the phone (heard on the room) starts nothing: the phone stays paused with "Ikke lastet
     * ned" - never another book from 0 (design 26b §5.6, §9; [quiet] when the caller already said why).
     */
    private suspend fun comeHome(sonos: SonosRenderer, play: Boolean, quiet: Boolean = false) {
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
        // the phone's view (design 26b §5.6): it is the phone
        val (snap, rows) = load(remote = false)
        if (renderer !== local) return
        val order = snap.order(a0.entry).map { it.key }
        if (a0.entry.source == Source.STORYTEL && item.key !in order) {
            if (!quiet) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)
            publish()
            return
        }
        val plan = homePlan(order, item.key, resumeFrom(a0.positions[item.key]))
        // the phone's renderer for the entry (design 26c §5.10): start attaches it - a Spotify entry never reaches a
        // room (toRoom keeps the phone playing), so this is the phone's own player in practice
        val a = activeFor(snap, rows, a0.entry, plan.keys, shuffled = false, phoneRendererFor(a0.entry), plan.startIndex)
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
        comeHome(sonos, play = false, quiet = true)
        withContext(Dispatchers.IO) { home.rooms.refresh() }
    }

    internal companion object {
        /**
         * The hand-off is a 1.5 s classify, a bookmark and a Stop (2 s connect + 5 s read at worst), but it
         * queues behind what the actor holds: a hushed play's settled seek (up to 8 s), the 3 s ramp, a
         * poll - and since design 26b a Storytel play's mint (up to 10 s, `SonosController.MINT_BUDGET_MS`)
         * before its hushed play: 15 s -> 25 s (§5.1). Past this the switch goes on and the Stop stays
         * queued (`abandon`). The suspension receiver's `goAsync` budget (`MusicApp.SUSPEND_BUDGET_MS`, 9 s)
         * is shorter (design 26b §13 M6): with the service up the receiver finishes at 9 s and the
         * service's own `suspendStop` runs on in its scope (26a's path) - and a play queued before the
         * suspension never sounds after it, because its job's token (`playAllowed`, the generation, the
         * renderer's `closed`) is checked again after the mint, before `SetAVTransportURI`.
         * `SonosTail.MAX_MS` and `logic/Switch.kt`'s `SWITCH_WAIT_MAX_MS` move with it.
         */
        const val HANDOFF_MS = 25_000L

        /** Adopt's look at start: the Wi-Fi wait, the 1.5 s classify and the actor's start, with room. */
        const val ADOPT_MS = 4_000L

        /** A recorded room is looked at again before a play at most this often (the cabin case: no speaker answers). */
        const val ROOM_LOOK_MS = 15_000L

        /** How often the household's groups are read while a session plays under a limited room scope (§5.5, M1). */
        const val SCOPE_WATCH_MS = 60_000L

        /** The web player's debug log (design 26c §7.1; a debug build only). */
        const val SPOTIFY_LOG_TAG = "VibbSpotify"
    }
}
