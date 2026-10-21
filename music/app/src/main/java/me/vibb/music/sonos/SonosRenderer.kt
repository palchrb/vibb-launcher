package me.vibb.music.sonos

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.vibb.music.bridge.Bridge
import me.vibb.music.data.Store
import me.vibb.music.logic.AT_END_LOOK_MS
import me.vibb.music.logic.NO_AUDIO
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.atEndNextLookMs
import me.vibb.music.logic.itemRemaining
import me.vibb.music.logic.playAllowed
import me.vibb.music.logic.resumeFrom
import me.vibb.music.playback.Active
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.PlayerHub
import me.vibb.music.playback.Renderer
import me.vibb.music.playback.RendererEvent
import me.vibb.music.playback.RendererKind
import me.vibb.music.playback.RendererState
import me.vibb.music.playback.persistPlace
import me.vibb.music.storytel.StorytelAccount
import me.vibb.music.sync.ItemErrors
import kotlin.coroutines.resume

/*
 * A Sonos room as the session's player (design 26a §3.2): a Media3 SimpleBasePlayer built on the main
 * looper (MediaSession.setPlayer checks the application looper), driven by the poller's snapshots.
 * The rules are SonosController's (pure, tested); this class is the Android side: the actor and the
 * poller, the player's State from SonosController.spec (the §3.2 table), the RendererEvents the
 * service reads, and the bookmark's rows (persistPlace, the same write as the phone's).
 *
 * Handlers return completed futures at once (SimpleBasePlayer ignores invalidateState while one is
 * pending) and only set intent; the player re-reads its state as the handler returns, and the
 * RendererEvents of a handler are posted - emitted outside it (Renderer.onEvent's contract).
 */
@OptIn(UnstableApi::class)
internal class SonosRenderer(
    private val context: Context,
    private val home: SonosHome,
    private val store: Store,
    /** The service's main scope and its writer (the bookmark rows, renderer.json). */
    private val scope: CoroutineScope,
    private val writer: CoroutineDispatcher,
    /** The service's queue (`Active`): the items given to [load] are its items, speaker-playable. */
    private val queue: () -> Active?,
    uid: String,
    name: String?,
    /** Hold the wake lock and the Wi-Fi lock while true ([SonosController.armedPlaying], design 26a §6.3). */
    private val onArmedPlaying: (Boolean) -> Unit = {},
    /**
     * The parent's room scope (design 26a §5.5): may a play go to this room's uid (and its group)? With the rooms the
     * session accepted (grouped in by hand, design 53 §2.8: `sessionAllowed`).
     */
    private val roomAllowed: (uid: String, accepted: Set<String>) -> Boolean = { _, _ -> true },
    /** The session followed its stream to another coordinator (§4.8): the scope looks again (qa-26a-step3c-code M1). */
    private val onFollowed: () -> Unit = {},
) : Renderer {
    private val handler = Handler(Looper.getMainLooper())
    private val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock: () -> Long = SystemClock::elapsedRealtime
    // design 53 §2.4: the 1 s display poll reads the player screen's flag and the socket's health, never a call from main
    private val session = SonosSession(clock, SonosHome::log, playerShown = { PlayerHub.playerShown.value }, socketHealthy = { socket.healthy })
    private var poller: SonosPoller? = null
    private val transport = SonosTransport(session, home.speakers, home.nrk, SonosClock(clock), wake = { poller?.wake() }, log = SonosHome::log)
    private val actor = SonosActor(actorScope, Dispatchers.IO, post = { block -> handler.post { block() } }, postDelayed = { ms, block -> handler.postDelayed({ block() }, ms) })
    private var released = false

    private val sonosStore = object : SonosStore {
        override fun savedPosMs(itemKey: String): Long = resumeFrom(queue()?.positions?.get(itemKey))

        override fun save(index: Int, itemKey: String, posMs: Long, durationMs: Long?, shownDurationMs: Long?, force: Boolean) {
            val a = queue() ?: return
            if (a.items.getOrNull(index)?.key != itemKey) return
            persistPlace(context, store, scope, writer, a, index, posMs, durationMs, shownDurationMs, force)
        }

        // design 53-d (qa-53d-code 1): bound to the queue held now; once the service holds another, the old item's place
        // and its entry's bookmark only - never the stored queue's row, which is the new queue's
        override fun pin(): SonosStore {
            val a = queue() ?: return this
            return object : SonosStore {
                override fun savedPosMs(itemKey: String): Long = resumeFrom(a.positions[itemKey])

                override fun save(index: Int, itemKey: String, posMs: Long, durationMs: Long?, shownDurationMs: Long?, force: Boolean) {
                    if (a.items.getOrNull(index)?.key != itemKey) return
                    persistPlace(context, store, scope, writer, a, index, posMs, durationMs, shownDurationMs, force, withQueue = queue() === a)
                }
            }
        }
    }

    private val host = object : SonosHost {
        override fun changed() = this@SonosRenderer.changed()

        override fun played(itemKey: String, uri: String) {
            val entry = queue()?.entry ?: return
            // design 26b §5.3 (QA M1): a Storytel play stores its marker, never the signed URL
            writeChoice { it.played(entry, itemKey, uri) }
        }

        override fun followed(uid: String, name: String?, itemKey: String?, uri: String) {
            val entry = queue()?.entry
            // the uri the adopt went out with (the hint's), through storedUri as every write (design 26b §5.3)
            writeChoice { it.followed(uid, name, entry, itemKey, uri) }
            onFollowed()
        }

        override fun storytelUnavailable(entryId: Long, itemKey: String, code: String) {
            SonosHome.log("sonos: a Storytel book got no URL ($code)")
            // design 26b §5.1: locked, not in this country, gone - the book's item error on the parent's card,
            // under the entry the play was for (qa-26b-step4b-code L3); a login's failure is the account's state
            // (the report's `storytel`), the rest transient
            if (code != NO_AUDIO) return
            scope.launch(writer) { ItemErrors.record(context, entryId, itemKey, NO_AUDIO) }
        }

        override fun ramp(volume: Int?) = writeChoice { it.copy(volume = volume, volumeUid = volume?.let { _ -> controller.uid }) }

        override fun armedPlaying(playing: Boolean) = onArmedPlaying(playing)

        override fun groupsChanged() = this@SonosRenderer.groupsChanged()

        override fun log(message: String) = SonosHome.log(message)
    }

    /**
     * The speaker's event socket (design 53 §2.2): events only, to the cached ip of the uid we command;
     * open while [socketWanted] says so ([updateSocket] after every change). Its events reach the
     * controller on the main thread.
     */
    private val socket = SonosSocket(
        clientFor = { ip, uid -> home.socketClient(ip, uid) },
        resolveIp = { home.cache.resolve(it)?.ip },
        clock = clock,
        main = object : SocketMain {
            override fun post(block: () -> Unit) {
                handler.post { block() }
            }

            override fun postDelayed(delayMs: Long, block: () -> Unit) {
                handler.postDelayed({ block() }, delayMs)
            }
        },
        // posted once more: a close inside [updateSocket] reports its Health from within a flush
        onEvent = { e -> handler.post { if (!released) controller.onSocket(e) } },
        log = SonosHome::log,
    )

    val controller: SonosController = SonosController(
        transport, actor, sonosStore, clock, uid, name, host, sleep = { delay(it) },
        restoreVolume = home.choice().takeIf { it.volumeUid == uid }?.volume,
        // every play the controller sends asks first, as KidPlayer and the play commands do (H1);
        // and never to a room outside the parent's scope (design 26a §5.5, a belt: the service refuses
        // the switch and leaves a room the scope dropped)
        mayPlay = { playAllowed(suspendedNow()) && roomAllowed(this@SonosRenderer.uid, controller.accepted) },
        // the parent's cap in force, read live from the managed config like the mobile-data switches (§5.3)
        cap = { Bridge.config(context).volumeCapPct },
        // a start caps every room of the group it plays in (§5.3, qa-26a-corrections M2)
        members = { roomMembers(home.rooms.state.value.rooms, it) },
        // a Storytel book's URL, minted inside the play's actor job (design 26b §5.1): the process's one account
        minter = { cid, timeoutMs, deadlineMs -> StorytelAccount.get(context).mint(cid, timeoutMs, deadlineMs) },
        // design 53 §2.2-§2.3: the socket's health and liveness hooks, and the confirm poll an event asks for
        events = socket,
        wakePoll = { poller?.wake() },
        // design 53 §2.12: the group's other rooms' volumes, set and read off our actor
        memberVolumes = RoomVolumes(home.speakers, actorScope, post = { block -> handler.post { if (!released) block() } }, log = SonosHome::log),
    )

    private fun suspendedNow(): Boolean = try {
        context.packageManager.isPackageSuspended
    } catch (e: Exception) {
        false
    }

    /** The room this renderer commands (migration follow may move it). */
    val uid: String get() = controller.uid

    private val sbp = SonosPlayer(Looper.getMainLooper())

    /** The MediaItems the service built for the queue: the player's metadata (title, artist, art, duration). */
    private var mediaItems: List<MediaItem> = emptyList()

    /** The Wi-Fi network and the player screen's flag, watched while this renderer lives (design 53 §2.2, §2.4). */
    private val watches = mutableListOf<Job>()

    /**
     * [groupsChanged]'s debounced look: the topology - handed to the controller whether or not the rows changed (an
     * equal view isn't emitted again, and an adopt's baseline waits for this fresh read; qa-53b-code 2) - and the busy
     * look while the sheet is open.
     */
    private val regroup = Runnable {
        val ours = controller.uid
        scope.launch(Dispatchers.IO) {
            if (home.rooms.refreshQuietly()) handler.post { topology(home.rooms.state.value.rooms) }
            if (PlayerHub.sheetOpen.value) home.rooms.lookBusy(ours)
        }
    }

    init {
        poller = SonosPoller(actorScope, actor, { transport.poll(home.probe) to session.snapshot }) { tick, fields -> controller.onPoll(tick, fields) }
            .also { it.start() }
        // any change of the Wi-Fi network closes the socket and opens it on the new one
        watches += scope.launch { home.wifiNetwork.collect { updateSocket() } }
        // the player coming on screen polls at once (the 1 s display poll starts now, not at the next cadence)
        watches += scope.launch { PlayerHub.playerShown.collect { if (it) poller?.wake() } }
        // design 53 §2.8: the group as the rows say now (no baseline yet: that is the first start's, an adopt's)
        topology(home.rooms.state.value.rooms)
    }

    /**
     * The household's grouping changed (design 53 §2.8; the service's rooms.state collector, before its sheet and its
     * scope's look): our group's members to the controller - the joiners accepted and capped, the baseline, the advance
     * guard - and the label ("Stua + Kjøkken", or the room's own name).
     */
    fun topology(rooms: List<Room>) {
        if (released) return
        val uid = controller.uid
        controller.topology(roomMembers(rooms, uid))
        controller.relabel(rooms.firstOrNull { it.uid == uid }?.label ?: rooms.firstNotNullOfOrNull { r -> r.names.firstOrNull { it.first == uid }?.second })
    }

    /** The rooms the session accepted (grouped in by hand, design 53 §2.8): the scope's leave and sheet read them. */
    val accepted: Set<String> get() = controller.accepted

    /** The event socket is healthy (design 53 §2.8: without it the group watch looks every 60 s, whatever the scope). */
    val socketHealthy: Boolean get() = socket.healthy

    /**
     * A `groups` or `MOVED` event (design 53 §2.8): one topology refresh, debounced [GROUPS_DEBOUNCE_MS] (`groupInfo`,
     * `groups` and `groupCoordinatorChanged` come together), and while the sheet is open its busy look again.
     */
    private fun groupsChanged() {
        if (released) return
        handler.removeCallbacks(regroup)
        handler.postDelayed(regroup, GROUPS_DEBOUNCE_MS)
    }

    /** The watches end with the renderer (never the actor's scope: its queued Stop still goes out). */
    private fun unwatch() {
        for (w in watches) w.cancel()
        watches.clear()
    }

    /**
     * The socket's lifetime (design 53 §2.2, [socketWanted]): armed (the session's own flag), Wi-Fi
     * up, not closed or released - the target the uid we command on this network, so a follow or a
     * network change re-targets it; a park is the socket's own (53-c: it holds for an ip). Cheap; run on
     * every change.
     */
    private fun updateSocket() {
        val uid = controller.uid
        val network = home.wifiNetwork.value
        val wanted = !released && socketWanted(session.armed, network, controller.closed, uid)
        socket.want(if (wanted && network != null) SonosSocket.Target(uid, network) else null)
    }

    override val player: Player get() = sbp
    override val kind: RendererKind get() = RendererKind.SONOS
    override var onEvent: ((RendererEvent) -> Unit)? = null

    override fun load(items: List<MediaItem>, startIndex: Int, startMs: Long, playNow: Boolean) {
        val a = queue() ?: return
        mediaItems = items
        lastIndex = -1
        controller.load(a.entry, a.items, startIndex, startMs)
        if (playNow) controller.playTile()
    }

    override fun reorder(keep: Int, after: List<MediaItem>) {
        val a = queue() ?: return
        val byKey = a.order.associateBy { it.key }
        controller.reorder(keep, after.mapNotNull { byKey[it.mediaId.substringAfter('/')] })
        mediaItems = listOfNotNull(mediaItems.getOrNull(keep)) + after
    }

    /** Design 49 §1.9: shuffle is the service's order ([reorder]); repeat is the controller's advance (wrap, or the item again). */
    override fun setModes(shuffled: Boolean, repeat: Repeat) {
        controller.repeat = repeat
    }

    override fun positionMs(): Long {
        val now = clock()
        return controller.spec(now).positionAt(now)
    }

    override fun durationMs(): Long? = controller.spec(clock()).durationMs
    override fun currentIndex(): Int = controller.index
    override fun itemCount(): Int = controller.items.size
    override fun itemAt(index: Int): MediaItem? = mediaItems.getOrNull(index)

    /**
     * Only the player's metadata (design 27's late picture): the speaker's DIDL keeps what it got at
     * the item's start - a speaker can't fetch the phone's art file (design 26a §2.5).
     */
    override fun replaceItem(index: Int, item: MediaItem) {
        if (index !in mediaItems.indices || mediaItems[index].mediaId != item.mediaId) return
        mediaItems = mediaItems.toMutableList().also { it[index] = item }
        sbp.refresh()
    }

    /** A tap on the loaded entry, RESUME, or the first play after [load] (the same-tile rule). */
    override fun play() = controller.playTile()
    override fun pause() = controller.pause()
    override fun prepare() = Unit

    /** Another item is a step (coalesced, at 0); the current one a seek. */
    override fun seekTo(index: Int, ms: Long) = if (index == controller.index) controller.seek(ms) else controller.stepTo(index, ms)
    override fun seekTo(ms: Long) = controller.seek(ms)

    override fun clear() {
        controller.clear()
        mediaItems = emptyList()
    }

    /** The bookmark (`bookmarkNow`): the only write of a Sonos position. */
    override fun saveNow(force: Boolean) = controller.bookmarkNow(force)

    override val showsPlaying: Boolean get() = controller.showsPlaying(clock())
    override val rendererState: RendererState? get() = controller.rendererState(clock())
    override val room: Output get() = Output(Output.sonosKey(controller.uid), OutputKind.SONOS, controller.name)

    override val fadeMs: Long get() = 3 * SonosController.RAMP_STEP_MS
    override fun fadeOutAndPause(done: () -> Unit) = controller.fadeOutAndPause(done)
    override fun cancelFade() = controller.cancelFade()

    /** "Til slutten" (design 50 §1.2): its done, the look at the item's time left, the item whose ramp runs (-1 none). */
    private var atEnd: (() -> Unit)? = null
    private var atEndLook: Runnable? = null
    private var atEndRampIndex = -1

    /**
     * The room's way (design 50 §1.2): the controller holds at the next item at 0 when the episode runs off its end
     * ([SonosController.holdAtEnd], `advance()`), and the room's own 3 s ramp starts when the item's time left crosses
     * [fadeMs] (its Pause then lands at about the end; whichever comes first ends it, the other is cancelled). A seek
     * back or a step cancels a running ramp and the look starts it again for the new place.
     */
    override fun pauseAtItemEnd(done: (() -> Unit)?) {
        atEndLook?.let { handler.removeCallbacks(it) }
        atEndLook = null
        if (atEndRampIndex >= 0) controller.cancelFade()
        atEndRampIndex = -1
        atEnd = done
        if (done == null) {
            controller.holdAtEnd = null
            return
        }
        controller.holdAtEnd = { finishAtEnd() }
        val look = object : Runnable {
            override fun run() {
                val left = itemRemaining(positionMs(), durationMs())
                if (atEndRampIndex >= 0 && (atEndRampIndex != controller.index || left == null || left > fadeMs + AT_END_LOOK_MS)) {
                    controller.cancelFade()
                    atEndRampIndex = -1
                }
                if (left != null && left <= fadeMs && atEndRampIndex < 0 && showsPlaying) {
                    atEndRampIndex = controller.index
                    controller.fadeOutAndPause { finishAtEnd() }
                }
                handler.postDelayed(this, atEndNextLookMs(left, fadeMs))
            }
        }
        atEndLook = look
        handler.post(look)
    }

    private fun finishAtEnd() {
        val done = atEnd ?: return
        pauseAtItemEnd(null)
        done()
    }

    // The phone's own player only (§3.2).
    override fun setAudioAttributes(attributes: AudioAttributes) = Unit
    override fun setWakeMode(wakeMode: Int) = Unit
    override fun setPreferredAudioDevice(device: AudioDeviceInfo?) = Unit

    /**
     * The way off this speaker (§4.6): a live classify, the bookmark, Stop if ours; true when the
     * speaker was PLAYING. Afterwards this renderer sends and writes nothing.
     */
    suspend fun handOff(): Boolean = suspendCancellableCoroutine { cont -> controller.handOff { if (cont.isActive) cont.resume(it) } }

    /** A hand-off that timed out: the last bookmark and an ours-gated Stop queued behind the busy actor (M1). */
    fun abandon() = controller.abandon()

    /**
     * The suspension's stop with the service up (design 26a §6.1): [handOff] with the park - one live
     * classify (also of a stood-down session), the bookmark, then for our stream playing or paused
     * Stop and the speaker's own queue as its source.
     */
    suspend fun park(): Boolean = suspendCancellableCoroutine { cont -> controller.handOff(park = true) { if (cont.isActive) cont.resume(it) } }

    /**
     * Adopt at start (design 26a §4.7), the look: [SonosTransport.adoptLive] in the actor - zero
     * transport commands, one classify of this room within [SonosController.LIVE_BUDGET_MS] once
     * Wi-Fi is known (the network callback answers within a moment of the service's start). [keep]
     * decides and names the uri the session goes on with; kept, the poller runs on. Null: no Wi-Fi or
     * no answer.
     */
    suspend fun probe(uri: String, keep: (Fields) -> String?): Fields? {
        if (!home.awaitWifi(WIFI_WAIT_MS)) return null
        val target = uid
        return actor.call({ transport.adoptLive(target, kindOfUri(uri), uri, SonosController.LIVE_BUDGET_MS, keep = keep) }) { }.getOrNull()
    }

    /**
     * A kept probe that can't be adopted (our stream, its item gone from the library;
     * qa-26a-step3b-code M1): box off = music off - one Stop to the room, then let it go.
     */
    suspend fun stopUnadopted() {
        val target = uid
        actor.call({ transport.stop(target) }) { }
    }

    /** The probe was kept: the queue ([items], the service's MediaItems of `Active.items`) at [index], nothing sent. */
    fun adopt(items: List<MediaItem>, index: Int, uri: String, live: Fields) {
        val a = queue() ?: return
        mediaItems = items
        lastIndex = -1
        controller.adopt(a.entry, a.items, index, uri, live)
    }

    override fun release() {
        if (released) return
        released = true
        pauseAtItemEnd(null)
        // the locks go with it: close() reports false when this renderer held them (never for another's, qa L4)
        controller.close()
        poller?.stop()
        // no socket while the phone renders (design 53 §2.2): only a SonosRenderer owns one
        unwatch()
        socket.want(null)
        // the queued jobs (a hand-off's or an abandon's Stop) still go out; the scope ends after them
        actor.close()
        handler.removeCallbacksAndMessages(null)
        onEvent = null
    }

    private fun writeChoice(change: (RendererChoice) -> RendererChoice) {
        scope.launch(writer) {
            val current = home.choice()
            if (current.sonos) home.write(change(current))
        }
    }

    // -- events --

    private var inHandler = 0
    private var flushPosted = false
    private var lastIndex = -1
    private var lastPwr = false
    private var lastShows = false

    private fun changed() {
        if (released) return
        if (inHandler > 0) postFlush() else flush()
    }

    private fun postFlush() {
        if (flushPosted) return
        flushPosted = true
        handler.post {
            flushPosted = false
            if (!released) flush()
        }
    }

    /** The player re-reads its state, then the service hears what changed (the local renderer's events, in kind). */
    private fun flush() {
        updateSocket()
        val now = clock()
        val spec = controller.spec(now)
        controller.shown(spec)
        sbp.refresh()
        val events = mutableListOf<RendererEvent>()
        if (controller.items.isNotEmpty() && controller.index != lastIndex) {
            lastIndex = controller.index
            events += RendererEvent.ItemChanged(lastIndex, controller.takeAuto())
        }
        val ended = controller.takeEnded()
        if (spec.playWhenReady != lastPwr) {
            lastPwr = spec.playWhenReady
            if (!ended) events += if (lastPwr) RendererEvent.Resumed else RendererEvent.Paused(controller.pauseCause(now))
        }
        val shows = controller.showsPlaying(now)
        if (shows != lastShows) {
            lastShows = shows
            events += RendererEvent.IsPlaying(shows)
        }
        if (ended) events += RendererEvent.Ended
        if (controller.takeLost() != null) events += RendererEvent.Lost
        if (controller.takeRefused()) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.CANT_PLAY_ON_SONOS)
        events += RendererEvent.StateChanged
        for (e in events) onEvent?.invoke(e)
    }

    private fun handling(block: () -> Unit): ListenableFuture<*> {
        inHandler++
        try {
            block()
        } finally {
            inHandler--
        }
        postFlush()
        return Futures.immediateVoidFuture()
    }

    /** The session's player: its State is SonosController.spec's (§3.2's table); every handler only sets intent. */
    private inner class SonosPlayer(looper: Looper) : SimpleBasePlayer(looper) {
        fun refresh() = invalidateState()

        override fun getState(): State {
            val now = clock()
            val spec = controller.spec(now)
            val b = State.Builder()
                .setAvailableCommands(COMMANDS)
                .setDeviceInfo(DEVICE_INFO)
                .setDeviceVolume(controller.deviceVolume())
                .setPlayWhenReady(spec.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            val items = mediaItems
            val plan = playlistPlan(items.map { it.mediaId }, controller.items.size, spec)
            if (plan.uids.isEmpty()) {
                return b.setPlaybackState(if (plan.phase == PlayPhase.ENDED) Player.STATE_ENDED else Player.STATE_IDLE).build()
            }
            val index = plan.index
            val playlist = items.mapIndexed { i, mi ->
                val durationMs = if (i == index) spec.durationMs else mi.mediaMetadata.durationMs
                MediaItemData.Builder(plan.uids[i])
                    .setMediaItem(mi)
                    .setMediaMetadata(mi.mediaMetadata)
                    .setDurationUs(durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                    .setIsSeekable(durationMs != null)
                    .build()
            }
            return b.setPlaylist(playlist)
                .setCurrentMediaItemIndex(index)
                .setPlaybackState(
                    when (plan.phase) {
                        PlayPhase.IDLE -> Player.STATE_IDLE
                        PlayPhase.BUFFERING -> Player.STATE_BUFFERING
                        PlayPhase.READY -> Player.STATE_READY
                        PlayPhase.ENDED -> Player.STATE_ENDED
                    },
                )
                // anchored at the poll's measuredAt, so a controller's own extrapolation (the launcher's
                // positionNow) agrees with ours
                .setContentPositionMs { spec.positionAt(clock()) }
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
            handling { if (playWhenReady) controller.playPause() else controller.pause() }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = handling {
            if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == controller.index) {
                controller.seek(positionMs.coerceAtLeast(0))
            } else {
                controller.stepTo(mediaItemIndex, 0)
            }
        }

        override fun handleStop(): ListenableFuture<*> = handling { controller.stop() }

        // design 53 §2.12: on a group the system's slider is the group's mean and moves every room
        override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> = handling { controller.setDeviceVolume(deviceVolume) }

        override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handling { controller.adjustVolume(VOLUME_STEP) }

        override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handling { controller.adjustVolume(-VOLUME_STEP) }

        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    companion object {
        /** One volume key press (§5.3). */
        const val VOLUME_STEP = 3

        /** How long a probe waits for the Wi-Fi network at start. */
        const val WIFI_WAIT_MS = 1_000L

        /** Design 53 §2.8: a `groups` event's topology refresh waits this long for its companions. */
        const val GROUPS_DEBOUNCE_MS = 300L

        /** §3.2 (QA M1): without the three GET commands the platform metadata is empty. */
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(*SONOS_COMMANDS).build()

        private val DEVICE_INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()
    }
}
