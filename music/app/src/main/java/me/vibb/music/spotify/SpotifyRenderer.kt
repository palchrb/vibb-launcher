package me.vibb.music.spotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.vibb.music.BuildConfig
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.SpotifyBridgeToken
import me.vibb.music.bridge.TokenResult
import me.vibb.music.data.Store
import me.vibb.music.logic.SLEEP_FADE_MS
import me.vibb.music.logic.SPOTIFY_CALL_MS
import me.vibb.music.logic.SPOTIFY_FIRST_STATE_MS
import me.vibb.music.logic.SPOTIFY_HOST_IDLE_MS
import me.vibb.music.logic.SPOTIFY_READY_MS
import me.vibb.music.logic.SPOTIFY_START_BUDGET_MS
import me.vibb.music.logic.SPOTIFY_STATE_GAP_MS
import me.vibb.music.logic.SPOTIFY_STEP_COALESCE_MS
import me.vibb.music.logic.SpotifyContextTrack
import me.vibb.music.logic.SpotifyLine
import me.vibb.music.logic.SpotifyProblem
import me.vibb.music.logic.SpotifyWords
import me.vibb.music.logic.playAllowed
import me.vibb.music.logic.playWindow
import me.vibb.music.logic.debugSwitchToken
import me.vibb.music.logic.sdkErrorProblem
import me.vibb.music.logic.sleepVolume
import me.vibb.music.logic.spotifyNetworkLine
import me.vibb.music.media.MediaCache
import me.vibb.music.playback.Active
import me.vibb.music.playback.Output
import me.vibb.music.playback.PauseCause
import me.vibb.music.playback.Renderer
import me.vibb.music.playback.RendererEvent
import me.vibb.music.playback.RendererKind
import me.vibb.music.playback.RendererState
import me.vibb.music.playback.audioAttributes
import me.vibb.music.playback.persistPlace
import me.vibb.music.spotify.SpotifyTracker.Event
import me.vibb.music.spotify.SpotifyTracker.Mode
import me.vibb.music.sync.netCaps
import okhttp3.HttpUrl
import java.io.File
import kotlin.coroutines.resume

/*
 * Spotify's web player as the session's player (design 26c §5.4-§5.8): a third renderer beside the phone's
 * ExoPlayer and a Sonos room. It plays our own list - a window of track uris from the current item
 * (`PUT /v1/me/player/play`), one track added ahead past the window - on the one Connect device the
 * [SpotifyHost] page registers, reads the page's states through [SpotifyTracker] (the ownership rule:
 * only our uris are ours), and shows §5.4's state table to Media3 through a SimpleBasePlayer, so the
 * session, the notification, Home's card, the lock's tile and design 28's listening window work
 * unchanged. Main thread only; Web API calls and the token wait run on IO and post back.
 *
 * - A start (a tap, a pick, a step, a resume after the device was dropped): the network gate, then in
 *   parallel the page's `ready` (at most 20 s) and a token with 5 minutes left; then shuffle and repeat off
 *   on a device's first play and after the tracker saw either on (qa-26c-step4-code M1), the play call
 *   with its retries, and our first uri playing - all within 25 s. Meanwhile the player shows the intent:
 *   play-when-ready, BUFFERING, `showsPlaying` (QA M1). A second tap during a start sends nothing; a pause
 *   cancels it (no call after it; one already sent is followed by `vibb.pause()`); steps within 300 ms
 *   coalesce into one play call.
 * - Playing: pause, resume and seek within the item are the SDK's (no Web API call); the natural advance
 *   sends nothing; the window's end adds the next item to Spotify's queue one ahead; a track that isn't
 *   ours is paused at once and never saved; a state gap up to 30 s is held as playing, past it the player
 *   pauses with "trenger internett"; a confirmed `null` is "elsewhere" (the bookmark kept); an unplayable
 *   track is the item error and the queue moves on, the third in a row stops.
 * - The phone's own audio rules (§5.6): audio focus (a loss pauses, a transient loss interrupts and plays
 *   again on the gain, a duck lowers the SDK's volume), becoming noisy pauses, the sleep fade steps the
 *   SDK's volume over 10 s; the wake and Wi-Fi locks (§5.7) while a start runs or it shows playing; the
 *   token refreshed ahead of expiry while playing (§5.5, QA M2) under them.
 * - Positions: [saveNow] writes only the tracker's measurement (as the SDK measured it, never
 *   extrapolated - 26a: no guess reaches Room); [positionMs] is for display.
 * - The page lives from the first play (never at a restore: no Connect device appears by itself) until a
 *   suspension ([suspended]), 5 minutes after the last pause, its renderer's death, an account change, an
 *   `off` token, or [release].
 * - **Context mode** (§5.9, step 6): an entry Spotify plays in its own order (an artist, a playlist no account may
 *   list) is one synthetic item. A start is `PUT .../play` with the context (a playlist from its live track at its
 *   place - `spotify_context.json` - an artist from Spotify's start), next and previous are the SDK's ([step]), every
 *   track Spotify picks in the context is ours (another context paused), each track change swaps the item's title in
 *   place and stores the live track, and the context's end is `Ended`. Its position row is the live track's place,
 *   saved with no duration (never done).
 * - The debug build's state recorder (§9.4): every posted state, the page's events and our calls by name go to
 *   [SpotifyPorts.recordState] / [SpotifyPorts.record] - a no-op unless `src/debug` switched it on, never a log.
 *
 * Android stays at the edges ([SpotifyPorts], [SpotifyHost], the SimpleBasePlayer): SpotifyRendererTest
 * drives the rest with a scripted page, a scripted Web API and a virtual clock. Design 26c step 5a; the
 * service's sites are step 5b's (`create`, `refused`, `suspended`, `forget`, the outcome's store).
 */

/** A change of the audio focus as the renderer reads it. */
enum class FocusChange { GAIN, LOSS, LOSS_TRANSIENT, DUCK }

/** What the renderer needs from Android ([AndroidSpotifyPorts]; SpotifyRendererTest scripts them). Main thread. */
internal interface SpotifyPorts {
    /** The elapsed clock (ms): the tracker's, the token cache's and every deadline's. */
    fun now(): Long

    /** [block] on the main thread [delayMs] from now; the answer cancels it. */
    fun after(delayMs: Long, block: () -> Unit): () -> Unit

    /** AUDIOFOCUS_GAIN for media (speech for a show); false when refused (a call). [onChange] on the main thread. */
    fun requestFocus(speech: Boolean, onChange: (FocusChange) -> Unit): Boolean

    fun abandonFocus()

    /** Watch `ACTION_AUDIO_BECOMING_NOISY` ([on]) or stop; [onNoisy] on the main thread. */
    fun watchNoisy(on: Boolean, onNoisy: () -> Unit)

    /** The partial wake lock and the Wi-Fi lock ([SpotifyLocks]); [renewLocks] starts their timeouts again. */
    fun holdLocks(on: Boolean)

    fun renewLocks()

    /** `playAllowed`: the launcher's time rule (nothing starts while the app is suspended). */
    fun mayPlay(): Boolean

    /** The network gate (`spotifyNetworkLine`): null when Spotify may stream now, else the line. */
    fun networkLine(): SpotifyLine?

    /** `persistPlace`: item [index] of [a] at [posMs] (measured), [durationMs] for "heard to the end". */
    fun save(a: Active, index: Int, posMs: Long, durationMs: Long?, force: Boolean)

    /** The phone's last Spotify outcome (§4.4: `spotify_state.json`, written only when the word changes). */
    fun outcome(word: String)

    /**
     * The account the web player's cookies and storage on disk belong to (the token's `"<id>:<generation>"`,
     * not a secret; qa-26c-step5a-code M1), or null when none is known; [storePageAccount] writes it (null
     * deletes it).
     */
    fun pageAccount(): String?

    fun storePageAccount(account: String?)

    /** The debug log: fixed words, never a token, a uri list, a state or the SDK's message in a release. */
    fun log(message: String)

    /** Context mode (§5.9): the live track stored for the entry [entryKey] (`spotify_context.json`), or null. */
    fun contextTrack(entryKey: String): SpotifyContextTrack?

    /** Context mode: [entryKey]'s live track is [track] now (null drops it) - written by the store, off the main thread. */
    fun storeContextTrack(entryKey: String, track: SpotifyContextTrack?)

    /**
     * The debug build's state recorder (§9.4; qa-26c-step5b-code, "the state-recording gap"): one posted state, the
     * compact JSON as the page posted it. A no-op unless `src/debug` switched the recorder on (`SpotifyRecording`):
     * never in a release, and never a log line (§7.1).
     */
    fun recordState(json: String)

    /** The recorder's other lines: a page event or one of our calls, by name only (`ready`, `play from 3 to 52 at 0 ms`). */
    fun record(event: String)
}

/** What the session's player shows (§5.4's table): Media3's state and play-when-ready, the position anchor. */
data class SpotifySpec(
    /** `Player.STATE_IDLE` / `STATE_BUFFERING` / `STATE_READY` / `STATE_ENDED`. */
    val playbackState: Int,
    val playWhenReady: Boolean,
    val showsPlaying: Boolean,
    val index: Int,
    /** A transient focus loss holds it (play-when-ready stays, nothing sounds). */
    val suppressed: Boolean,
    val line: RendererState?,
)

/** §5.8's line as the player's state. */
fun rendererStateOf(line: SpotifyLine): RendererState = when (line) {
    SpotifyLine.NEEDS_WIFI -> RendererState.SPOTIFY_NEEDS_WIFI
    SpotifyLine.NEEDS_INTERNET -> RendererState.SPOTIFY_NEEDS_INTERNET
    SpotifyLine.UNAVAILABLE -> RendererState.SPOTIFY_UNAVAILABLE
    SpotifyLine.ASK_ADULT -> RendererState.SPOTIFY_ASK_ADULT
    SpotifyLine.NOT_ON_PHONE -> RendererState.SPOTIFY_NOT_ON_PHONE
    SpotifyLine.ELSEWHERE -> RendererState.SPOTIFY_ELSEWHERE
    SpotifyLine.STOPPED -> RendererState.SPOTIFY_STOPPED
}

@OptIn(UnstableApi::class)
internal class SpotifyRenderer(
    private val host: SpotifyHost,
    http: SpotifyHttp,
    apiBase: HttpUrl,
    /** One token ask: the launcher's bridge, or the fake's route with the debug switch. */
    askToken: suspend () -> TokenResult,
    private val ports: SpotifyPorts,
    /** The main thread's (`Dispatchers.Main`); the tests' event loop. */
    dispatcher: CoroutineDispatcher,
    /** The service's queue (`Active`): the items given to [load] are its items. */
    private val queue: () -> Active?,
    /** The session player's looper (the main one); null in the JVM tests, which read [spec]. */
    private val looper: Looper?,
) : Renderer {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** When the last play call of a start went out (an IO thread writes it): what the page posted before it is the old play's. */
    @Volatile
    private var playSentAt = Long.MAX_VALUE
    private val api = SpotifyApi(
        { request ->
            if (request.url.encodedPath == PLAY_PATH) playSentAt = ports.now()
            http.send(request)
        },
        apiBase,
        ports::now,
    )

    /** The access token (§5.5), in memory only. */
    val tokens = TokenCache(scope, askToken, ports::now, sleep = { sleep(it) }, log = ports::log)
    private val tracker = SpotifyTracker()
    private var released = false

    /**
     * Another renderer plays (the service's `attach`, qa-26c-step5b-code M1): the web player owns nothing until its next
     * [load] - every playing state is paused at once, never focus, locks or a play.
     */
    private var detached = false

    // --- the queue and what the player shows ---

    private var mediaItems: List<MediaItem> = emptyList()

    /** List mode: the queue's tracks. Context mode: the one synthetic item (its uri the context's), for the bookkeeping. */
    private var tracks: List<SpotifyTrack> = emptyList()

    /** Context mode (§5.9): the entry's target - Spotify's order of it - else null (list mode). */
    private var contextUri: String? = null

    /** Context mode: a playlist goes on from its live track (an offset); an artist has none (Spotify's start, §12). */
    private var contextOffsets = false

    /** Context mode: the entry's key (its `spotify_context.json` row) and name (the title before a track is known). */
    private var contextKey: String? = null
    private var contextName: String? = null

    /** Context mode: the live track's title, shown as the item's (the player, the session, the notification). */
    private var liveTitle: String? = null
    private var speech = false
    private var index = 0

    /** Where the current item stands for display and a fresh start: a measurement, a seek or the load's position. */
    private var place = Place(0, 0, 0, moving = false)

    private data class Place(val index: Int, val posMs: Long, val atMs: Long, val moving: Boolean)

    private var pwr = false
    private var ended = false

    /** An error that stopped the renderer (the page destroyed): Media3's IDLE. */
    private var stopped = false
    private var line: SpotifyLine? = null
    private var suppressed = false
    private var ducked = false
    private var fadeLevel = 1.0
    private var pauseCause = PauseCause.OTHER

    /** The SDK's duration of the current item (its states'), else the listing's. */
    private var stateDuration: Long? = null
    private var lastOursAt = 0L

    // --- the web player ---

    private var deviceId: String? = null

    /** The account the page's cookies and storage belong to (the token's `"<id>:<generation>"`). */
    private var hostAccount: String? = null

    /** Shuffle and repeat go off before the next play: a new device's first play, or the tracker saw either on. */
    private var needSettings = true
    private var focusHeld = false
    private var noisyWatched = false
    private var locksHeld = false

    // --- a start (§5.4): at most one runs ---

    private data class Request(val index: Int, val posMs: Long, val fresh: Boolean, val settings: Boolean)

    private class Start(val request: Request, val deadline: Long) {
        /** Where the play starts: the request's, or a seek before its calls (the service's resume overlap). */
        var posMs = request.posMs

        /** The kid paused while its Web API calls ran: a sent play is followed by `vibb.pause()`. */
        var cancelled = false

        /** A newer play replaced it while its calls ran: their result is dropped. */
        var superseded = false

        /** Inside the Web API calls: never cancelled (a call already on its way isn't taken back). */
        var calling = false

        /** Shuffle and repeat went off first: asked for, or the device is new since the play was asked for. */
        var settings = false

        /** Context mode: the offset the play call carried (a playlist's live track), or null. */
        var offset: String? = null
        var job: Job? = null
    }

    private sealed interface StartEnd {
        data class Called(val result: StartResult, val window: Int) : StartEnd

        data class Failed(val problem: SpotifyProblem) : StartEnd

        data object Dropped : StartEnd
    }

    private var start: Start? = null

    /** A play asked for while a start's calls ran: it goes when they end. */
    private var queued: Request? = null

    /**
     * States posted while a start's Web API calls run, with their arrival: read after the call - those before
     * the play call went out as the old play's (they only tell the tracker what played before), the rest
     * after `played()` (our new play's first state can beat the 204).
     */
    private val held = ArrayDeque<Pair<SdkSnapshot, Long>>()

    /** A clear of the web player's cookies and storage in progress: the next page waits for it (qa-26c-step5a-code L6). */
    private var clearing: CompletableDeferred<Unit>? = null

    /** The last token answer that wasn't a token (the SDK's `authentication_error` is read through it; L3). */
    private var tokenFailure: TokenResult? = null
    private var readyWait: CompletableDeferred<String?>? = null
    private var deviceWait: CompletableDeferred<Boolean>? = null

    /** An SDK error while a start ran: it ends the start. */
    private var sdkProblem: SpotifyProblem? = null

    // --- timers ---

    private var stepTimer: (() -> Unit)? = null
    private var firstStateTimer: (() -> Unit)? = null
    private var gapTimer: (() -> Unit)? = null
    private var idleTimer: (() -> Unit)? = null
    private var prefetchTimer: (() -> Unit)? = null
    private var lookTimer: (() -> Unit)? = null
    private var fadeTimer: (() -> Unit)? = null

    /** The service's `onError` move-on, while it handles our `Error("unplayable")` (step 4's mapping). */
    private class MoveOn(val stop: Boolean)

    private var moveOn: MoveOn? = null

    init {
        host.events = { if (!released) onHost(it) }
    }

    // --- Renderer ---

    private var sbp: SpotifyPlayer? = null

    override val player: Player
        get() = sbp ?: SpotifyPlayer(looper ?: error("the session's player needs the main looper")).also { sbp = it }
    override val kind: RendererKind get() = RendererKind.SPOTIFY
    override var onEvent: ((RendererEvent) -> Unit)? = null

    /**
     * The queue (§5.4: list mode's tracks; §5.9: a context's one synthetic item); no web player and no network before
     * a play. A context's live track - where a playlist context goes on - is the stored one (`spotify_context.json`).
     */
    override fun load(items: List<MediaItem>, startIndex: Int, startMs: Long, playNow: Boolean) {
        detached = false // attached again: a re-attach always loads
        val a = queue()
        val ref = a?.entry?.target?.let { spotifyTarget(it) }
        mediaItems = items
        contextUri = ref?.takeIf { a?.entry?.spotifyMode == SPOTIFY_MODE_CONTEXT }?.target
        contextOffsets = ref?.kind == SpotifyKind.PLAYLIST
        contextKey = a?.entry?.key?.takeIf { contextUri != null }
        contextName = a?.entry?.name?.takeIf { contextUri != null }
        val stored = contextKey?.let { ports.contextTrack(it) }
        liveTitle = stored?.title
        val ctx = contextUri
        // a context's item shows its live track's title from the start (a restart: paused at the live track)
        liveTitle?.let { t -> mediaItems = items.mapIndexed { i, mi -> if (i == 0) titled(mi, t) else mi } }
        tracks = when {
            a == null || ref == null -> emptyList()
            ctx != null -> a.items.take(1).map { SpotifyTrack(ctx, it.title, null) }
            a.entry.spotifyMode != SPOTIFY_MODE_LIST -> emptyList()
            else -> a.items.map { SpotifyTrack(uriOf(ref.kind, it.key), it.title, it.durationMs) }
        }
        if (a != null && tracks.isEmpty()) ports.log("spotify: nothing to play (${a.entry.spotifyMode ?: "no mode"})")
        speech = ref?.kind?.episodes == true && ctx == null
        supersede()
        cancelStep()
        cancelFirstState()
        index = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        lastIndex = -1 // a new queue: the service hears its current item (as ExoPlayer's playlist change)
        if (ctx != null) tracker.loadContext(ctx, stored?.uri) else tracker.load(tracks, index)
        // an artist context has no resume (§12: no offset for an artist): it starts where Spotify starts it
        place = Place(index, if (ctx != null && !contextOffsets) 0 else startMs.coerceAtLeast(0), ports.now(), moving = false)
        stateDuration = null
        ended = false
        stopped = false
        line = null
        if (pwr) {
            pwr = false
            pauseCause = PauseCause.OTHER
        }
        if (playNow) play() else changed()
    }

    /** No shuffle for Spotify (§4.2: `canShuffle` is own files without resume), so the service never reorders it. */
    override fun reorder(keep: Int, after: List<MediaItem>) {
        ports.log("spotify: a reorder was asked for and ignored")
    }

    override fun positionMs(): Long {
        val p = place
        if (p.index != index) return 0
        val now = ports.now()
        val pos = if (p.moving && pwr && !suppressed && !ended) p.posMs + (now - p.atMs).coerceIn(0, SPOTIFY_STATE_GAP_MS) else p.posMs
        return durationMs()?.let { pos.coerceAtMost(it) } ?: pos
    }

    override fun durationMs(): Long? = stateDuration ?: tracks.getOrNull(index)?.durationMs
    override fun currentIndex(): Int = index
    override fun itemCount(): Int = tracks.size
    override fun itemAt(index: Int): MediaItem? = mediaItems.getOrNull(index)

    override fun replaceItem(index: Int, item: MediaItem) {
        if (index !in mediaItems.indices || mediaItems[index].mediaId != item.mediaId) return
        // a context's item keeps its live title (the service swaps the art from its own copy of the item)
        val live = liveTitle?.takeIf { contextUri != null }
        mediaItems = mediaItems.toMutableList().also { it[index] = if (live != null) titled(item, live) else item }
        changed()
    }

    /**
     * Play: nothing while a start of ours runs (a second tap sends nothing, §5.4) - but a start the kid
     * paused while its calls ran is wanted again; a paused track of ours on a live device resumes through
     * the SDK; anything else is a fresh start at [place].
     */
    override fun play() {
        if (tracks.isEmpty() || released) return
        val mo = moveOn
        if (mo != null) return // the service's move-on: its seekTo already asked (or, after the third, nothing plays)
        val s = start
        if (s != null && !s.superseded) {
            if (s.cancelled && ports.mayPlay()) {
                s.cancelled = false
                pwr = true
                changed()
            }
            return
        }
        if (s != null) {
            // a new queue while the old one's calls ran: this play goes right after them
            request(Request(index, startPosition(), fresh = true, settings = needSettings))
            return
        }
        if (stepTimer != null) {
            if (!pwr && ports.mayPlay()) {
                pwr = true
                changed()
            }
            return
        }
        if (pwr && !ended && (tracker.mode == Mode.STARTING || tracker.mode == Mode.OURS)) return
        if (resumable()) resume() else request(Request(index, startPosition(), fresh = true, settings = needSettings))
    }

    override fun pause() {
        pause(PauseCause.USER)
    }

    override fun prepare() = Unit

    /** A step (next, previous, a pick, a swipe): coalesced within 300 ms into one play from the last target; paused, it only moves. */
    override fun seekTo(index: Int, ms: Long) {
        if (index !in tracks.indices || released) return
        val mo = moveOn
        if (mo != null) {
            moveOnTo(index, ms.coerceAtLeast(0), mo)
            return
        }
        if (index == this.index && stepTimer == null) return seekTo(ms)
        moveIndex(index, auto = false)
        place = Place(index, ms.coerceAtLeast(0), ports.now(), moving = false)
        ended = false
        stepTimer?.invoke()
        stepTimer = ports.after(SPOTIFY_STEP_COALESCE_MS) {
            stepTimer = null
            if (pwr && !released) request(Request(this.index, startPosition(), fresh = true, settings = needSettings))
            changed()
        }
        changed()
    }

    /** A seek within the current item: the SDK's (no Web API call) when our track is on the player, else where the next start begins. */
    override fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        val live = host.alive && tracker.mode == Mode.OURS && tracker.index == index && stepTimer == null && start == null
        if (live) sdk("seek", target)
        start?.takeIf { !it.calling && it.request.index == index }?.posMs = target
        place = Place(index, target, ports.now(), moving = live && pwr)
        changed()
    }

    override fun clear() {
        pause(PauseCause.OTHER)
        supersede()
        cancelStep()
        mediaItems = emptyList()
        tracks = emptyList()
        contextUri = null
        contextKey = null
        contextName = null
        liveTitle = null
        index = 0
        tracker.load(emptyList(), 0)
        place = Place(0, 0, ports.now(), moving = false)
        changed()
    }

    /**
     * The bookmark: the tracker's last measured position of the current item - as the SDK measured it, at
     * most 5 s old while playing, never extrapolated - and nothing before the first state of ours. Also the
     * 15 s save tick's renewal of the locks (§5.7).
     */
    override fun saveNow(force: Boolean) {
        if (locksHeld) ports.renewLocks()
        val a = queue() ?: return
        val m = tracker.measured ?: return
        if (m.index != index || m.index !in a.items.indices || m.index !in tracks.indices) return
        val item = a.items[m.index]
        // a context's item is saved with no duration (§5.9): a track's end is not the context's, so it is never done
        val duration = if (contextUri != null) null else stateDuration ?: a.durations[item.key] ?: item.durationMs
        ports.save(a, m.index, m.positionMs, duration, force)
    }

    /** §5.4's table: the intent during a start, held through a 30 s gap; false paused, `null`, foreign or ended. */
    override val showsPlaying: Boolean get() = pwr && !ended
    override val rendererState: RendererState? get() = line?.let { rendererStateOf(it) }
    override val room: Output? get() = null

    override val fadeMs: Long get() = SLEEP_FADE_MS

    /** The sleep timer's end (§5.6): the SDK's volume from 1 to 0 over 10 s, pause, then 1 again. */
    override fun fadeOutAndPause(done: () -> Unit) {
        cancelFade()
        val begin = ports.now()
        lateinit var tick: () -> Unit
        tick = {
            val left = fadeMs - (ports.now() - begin)
            fadeLevel = sleepVolume(left).toDouble()
            applyVolume()
            if (left > 0) {
                fadeTimer = ports.after(FADE_STEP_MS, tick)
            } else {
                fadeTimer = null
                pause(PauseCause.USER)
                fadeLevel = 1.0
                applyVolume()
                done()
            }
        }
        fadeTimer = ports.after(0, tick)
    }

    override fun cancelFade() {
        fadeTimer?.invoke()
        fadeTimer = null
        if (fadeLevel != 1.0) {
            fadeLevel = 1.0
            applyVolume()
        }
    }

    // The phone's own player only; the web player's audio is Android's to route (§5.6).
    override fun setAudioAttributes(attributes: AudioAttributes) = Unit
    override fun setWakeMode(wakeMode: Int) = Unit
    override fun setPreferredAudioDevice(device: AudioDeviceInfo?) = Unit

    override fun release() {
        if (released) return
        saveNow(force = true)
        supersede()
        for (t in listOf(stepTimer, firstStateTimer, gapTimer, idleTimer, prefetchTimer, lookTimer, fadeTimer)) t?.invoke()
        stepTimer = null
        firstStateTimer = null
        gapTimer = null
        idleTimer = null
        prefetchTimer = null
        lookTimer = null
        fadeTimer = null
        if (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING) sdk("pause")
        destroyHost()
        pwr = false
        released = true
        if (focusHeld) ports.abandonFocus()
        focusHeld = false
        if (noisyWatched) ports.watchNoisy(false) {}
        noisyWatched = false
        if (locksHeld) ports.holdLocks(false)
        locksHeld = false
        scope.cancel()
        host.events = null
        onEvent = null
    }

    // --- for the service (step 5b) ---

    /**
     * Next ([forward]) or previous in a context (§5.9, step 6): the SDK's (`vibb.next()` / `vibb.previous()`) -
     * Spotify's order and its own previous rule - while a track of our context is on the player; the tracker reads
     * where Spotify went ([Event.TrackChanged]). Nothing else: with no context of ours on the player (a restored
     * queue, a page gone idle, a start on its way) only Spotify knows the next track, and a play goes on from the
     * live one. Not for list mode, whose steps are the service's (`seekTo(index, ms)`).
     */
    fun step(forward: Boolean) {
        if (contextUri == null || released || detached) return
        if (!host.alive || tracker.mode != Mode.OURS || start != null) {
            ports.log("spotify: a step with no context of ours on the player - nothing sent")
            return
        }
        sdk(if (forward) "next" else "previous")
    }

    /** Context mode: whether [step] is the way next and previous go (the service asks before its own skip rules). */
    val stepsInContext: Boolean get() = contextUri != null

    /**
     * Another renderer took over (§5.10's `attach`: the phone's own player, a room; qa-26c-step5b-code M1). The page
     * lives on up to 5 idle minutes, and the account's other Spotify app can still resume "Vibb" or transfer to it: from
     * now until the next [load] every playing state is not ours - paused at once (`vibb.pause()`), never the audio focus,
     * the locks or a play the session can't show or stop.
     */
    fun detached() {
        if (released) return
        pause(PauseCause.OTHER)
        supersede()
        cancelStep()
        detached = true
        tracker.lost()
        ports.log("spotify: detached - a play from outside is paused")
        changed()
    }

    /**
     * `MY_PACKAGE_SUSPENDED` with the service up (§5.7, QA L11): the bookmark, a pause, and the page
     * destroyed whatever the state - no Connect device "Vibb" exists while the app is suspended.
     */
    fun suspended() {
        saveNow(force = true)
        pause(PauseCause.OTHER)
        destroyHost()
        changed()
    }

    /**
     * The service's network gate said no (§5.10, QA H1: a start, a step or Spotify's own advance on a network the
     * gate refuses): paused with [line] - "trenger Wi-Fi" or "trenger internett" - and its word, nothing moves on.
     * The renderer's own gate before every start says the same (its belt).
     */
    fun refused(line: SpotifyLine) {
        if (released) return
        refuse(line)
    }

    /**
     * Spotify off on this phone, or no Spotify entry left (§5.2's clearing): the page and its storage go, and the token;
     * the stored word is `none` (§4.4: no Spotify to play; qa-26c-step5b-code L4 - a later binding never inherits the
     * last account's word), as the token's `off` writes it.
     */
    fun forget() {
        pause(PauseCause.OTHER)
        forgetAccount()
        tokens.clear()
        ports.outcome(SpotifyWords.NONE)
        changed()
    }

    /** The page, its cookies, storage and cache, and the account marker (M1) go. */
    private fun forgetAccount() {
        destroyHost()
        startClear()
        hostAccount = null
        ports.storePageAccount(null)
    }

    /** Clear the web player's cookies, storage and cache; the next page waits for it (L6). */
    private fun startClear() {
        val done = CompletableDeferred<Unit>()
        clearing = done
        val timeout = ports.after(CLEAR_WAIT_MS) {
            ports.log("spotify: the web player's clear took too long")
            done.complete(Unit)
        }
        host.clearStorage {
            timeout()
            done.complete(Unit)
        }
    }

    /** What the session's player shows (§5.4's table); SpotifyRendererTest reads it row by row. */
    fun spec(): SpotifySpec {
        val state = when {
            tracks.isEmpty() || mediaItems.isEmpty() -> if (ended) Player.STATE_ENDED else Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            stopped -> Player.STATE_IDLE
            pwr && (start != null || stepTimer != null || tracker.mode == Mode.STARTING) -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return SpotifySpec(state, pwr && !ended, showsPlaying, index, suppressed, rendererState)
    }

    // --- starts ---

    /**
     * Our paused track plays again through the SDK - unless shuffle or repeat must go off first (a state said
     * either is on while paused: the tracker reads nothing until the next play call, H1).
     */
    private fun resumable(): Boolean =
        host.alive && deviceId != null && tracker.mode == Mode.OURS && tracker.index == index && !ended && !stopped && !needSettings

    /** Where a fresh start of the current item begins: its last measurement, seek or load position. */
    private fun startPosition(): Long = place.takeIf { it.index == index }?.posMs ?: 0

    /** Our paused track plays again through the SDK (§5.4: no Web API call). */
    private fun resume() {
        if (detached || !ports.mayPlay()) return
        ports.networkLine()?.let { return refuse(it) }
        if (!acquireFocus()) return
        sdk("resume")
        tracker.resumeSent()
        pwr = true
        line = null
        lastOursAt = ports.now()
        changed()
        armGap()
    }

    /** A play from [r]: a start's calls running take it after them; one that hasn't called yet is dropped for it. */
    private fun request(r: Request) {
        val s = start
        if (s != null) {
            if (s.calling) {
                s.superseded = true
                queued = r
                if (!pwr) {
                    pwr = true
                    changed()
                }
                return
            }
            dropStart(s)
        }
        begin(r)
    }

    private fun begin(r: Request) {
        if (detached) {
            ports.log("spotify: a start refused (detached)")
            return
        }
        if (!ports.mayPlay()) {
            ports.log("spotify: a start refused (the app is suspended)")
            if (pwr) pause(PauseCause.OTHER)
            return
        }
        ports.networkLine()?.let { return refuse(it) }
        if (!acquireFocus()) {
            ports.log("spotify: no audio focus - not started")
            if (pwr) pause(PauseCause.OTHER)
            return
        }
        cancelFirstState()
        cancelGap()
        cancelIdle()
        val s = Start(r, ports.now() + SPOTIFY_START_BUDGET_MS)
        start = s
        sdkProblem = null
        pwr = true
        ended = false
        stopped = false
        line = null
        applyVolume()
        changed()
        s.job = scope.launch { run(s) }
    }

    /** A start that hasn't reached its Web API calls: cancelled outright (nothing was sent). */
    private fun dropStart(s: Start) {
        if (start !== s) return
        start = null
        s.job?.cancel()
        readyWait?.complete(null)
        replayHeld()
    }

    /** The current start and any queued play go (a new queue, a release). */
    private fun supersede() {
        queued = null
        val s = start ?: return
        if (s.calling) s.superseded = true else dropStart(s)
    }

    private suspend fun run(s: Start) {
        val end = try {
            prepareAndCall(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ports.log("spotify: a start failed (${e.javaClass.simpleName})")
            StartEnd.Failed(SpotifyProblem.UNREACHABLE)
        }
        startDone(s, end)
    }

    /** §5.4 steps 2-3: the page and its device with a token, then the Web API calls with their retries. */
    private suspend fun prepareAndCall(s: Start): StartEnd = coroutineScope {
        val r = s.request
        val readyBy = minOf(s.deadline, ports.now() + SPOTIFY_READY_MS)
        // a live page of a known account gets ready beside the token; a new page waits for it, so that it is
        // never made on another account's storage (M1)
        var ready: Deferred<String?>? = if (hostAccount != null && host.alive) async { awaitReady(readyBy) } else null
        // the token's deadline leaves the play call its room (qa-26c-step4-code's note)
        val token = tokens.get(s.deadline - SPOTIFY_CALL_MS, atStart = true)
        if (token !is TokenResult.Ok) {
            ready?.cancel()
            noteTokenFailure(token)
            return@coroutineScope StartEnd.Failed(token.problem() ?: SpotifyProblem.UNREACHABLE)
        }
        tokenFailure = null
        if (hostAccount != token.account) {
            // §5.2 (qa-26c-step5a-code M1): the cookies and storage on disk must be this account's - after another
            // account or a new login in this process, and at a process's first page against the marker on disk
            // (a differing or missing marker clears: an upgrade, a crash before the write, a rebind while the app
            // was down)
            ready?.cancel()
            ready = null
            val owner = hostAccount ?: ports.pageAccount()
            if (owner != token.account) {
                ports.log("spotify: the web player's storage isn't this account's - cleared")
                destroyHost()
                startClear()
            }
            hostAccount = token.account
            ports.storePageAccount(token.account)
        }
        val device = (ready ?: async { awaitReady(readyBy) }).await()
        sdkProblem?.let { return@coroutineScope StartEnd.Failed(it) }
        if (device == null) {
            ports.log("spotify: the web player wasn't ready in time")
            destroyHost() // the next play loads the page again
            return@coroutineScope StartEnd.Failed(SpotifyProblem.UNREACHABLE)
        }
        if (s.cancelled || s.superseded || start !== s) return@coroutineScope StartEnd.Dropped
        val ctx = contextUri
        val window = if (ctx != null) listOf(ctx) else playWindow(tracks.map { it.uri }, r.index)
        if (window.isEmpty()) return@coroutineScope StartEnd.Dropped
        // context mode (§5.9): a playlist from its live track at its place, an artist from Spotify's start
        s.offset = if (ctx != null && contextOffsets) tracker.liveUri else null
        val body = if (ctx != null) PlayBody.Context(ctx, s.offset, s.posMs) else PlayBody.Uris(window, s.posMs)
        // decided now, not when the play was asked for: a `ready` with a new device may have come since
        s.settings = r.settings || needSettings
        s.calling = true
        val result = api.start(
            device,
            body,
            s.deadline,
            tokens = { renew ->
                if (renew) tokens.invalidate()
                tokens.get(s.deadline - SPOTIFY_CALL_MS)
            },
            awaitDevice = { awaitDevice(it) },
            sleep = { sleep(it) },
            settings = s.settings,
        )
        s.calling = false
        StartEnd.Called(result, window.size)
    }

    private fun startDone(s: Start, end: StartEnd) {
        if (start !== s || released) return
        start = null
        val r = s.request
        val problem = sdkProblem
        sdkProblem = null
        val replay = held.toList()
        held.clear()
        val sentAt = playSentAt
        playSentAt = Long.MAX_VALUE
        val sent = end is StartEnd.Called && end.result == StartResult.Sent
        if (sent && !s.superseded) {
            // what the page posted before the play call went out is the old play's: it only tells the
            // tracker what played before, read for nothing (qa-26c-step5a-code M3)
            for ((snap, at) in replay) if (at < sentAt) tracker.onState(snap, at)
        }
        when {
            s.superseded && sent && queued == null -> {
                // its successor was dropped (a pause, a new queue): the play that went out is stopped,
                // never left sounding or read against the old play (qa-26c-step5a-code H1)
                ports.log("spotify: a replaced play went out - paused")
                if (host.alive) sdk("pause")
                tracker.lost()
            }
            s.superseded -> ports.log("spotify: a start replaced by a newer play")
            end is StartEnd.Called && end.result == StartResult.Sent -> {
                if (s.settings) needSettings = false
                if (contextUri != null) {
                    ports.record(if (s.offset != null) "play context from the live track at ${s.posMs} ms" else "play context")
                    apply(tracker.playedContext(s.offset, s.posMs, r.fresh))
                } else {
                    ports.record("play from ${r.index} to ${r.index + end.window - 1} at ${s.posMs} ms")
                    apply(tracker.played(r.index, r.index + end.window - 1, s.posMs, r.fresh))
                }
                when {
                    problem != null -> fail(problem)
                    s.cancelled || suppressed -> {
                        // the kid paused, or a transient focus loss holds it (M2): it never sounds over that
                        sdk("pause")
                        tracker.pauseSent()
                    }
                    else -> armFirstState(s.deadline)
                }
            }
            s.cancelled -> ports.log("spotify: a paused start ended")
            // the SDK's own word (the page gone, no Widevine, no Premium) wins over the call's (L4)
            problem != null -> fail(problem)
            end is StartEnd.Called -> fail(end.result.problem() ?: SpotifyProblem.UNREACHABLE)
            end is StartEnd.Failed -> fail(end.problem)
            else -> if (pwr && queued == null) {
                // dropped before its calls (the queue changed under it): nothing plays
                pwr = false
                pauseCause = PauseCause.OTHER
            }
        }
        if (!s.superseded) for ((snap, at) in replay) if (!sent || at >= sentAt) feed(snap)
        val next = queued
        queued = null
        if (next != null) begin(next) else changed()
    }

    private fun replayHeld() {
        val replay = held.toList()
        held.clear()
        for ((snap, _) in replay) feed(snap)
    }

    /** The page and its `ready` device id within [by] (the elapsed clock); null when it didn't come or the SDK refused. */
    private suspend fun awaitReady(by: Long): String? {
        if (!host.alive) {
            // a clear in progress first: the new page must not read what it is removing (L6)
            clearing?.await()
            deviceId = null
            host.open()
        }
        deviceId?.let { return it }
        val wait = CompletableDeferred<String?>()
        readyWait = wait
        val cancel = ports.after((by - ports.now()).coerceAtLeast(0)) { wait.complete(null) }
        try {
            return wait.await()
        } finally {
            cancel()
            if (readyWait === wait) readyWait = null
        }
    }

    /** After a transfer: a state or `ready` from the SDK within [timeoutMs] (the Player API doesn't order calls). */
    private suspend fun awaitDevice(timeoutMs: Long): Boolean {
        val wait = CompletableDeferred<Boolean>()
        deviceWait = wait
        val cancel = ports.after(timeoutMs.coerceAtLeast(0)) { wait.complete(false) }
        try {
            return wait.await()
        } finally {
            cancel()
            if (deviceWait === wait) deviceWait = null
        }
    }

    private suspend fun sleep(ms: Long) = suspendCancellableCoroutine { c ->
        val cancel = ports.after(ms.coerceAtLeast(0)) { if (c.isActive) c.resume(Unit) }
        c.invokeOnCancellation { cancel() }
    }

    /** The network gate refused a start (§5.8's first two rows). */
    private fun refuse(l: SpotifyLine) {
        ports.log("spotify: no start - ${l.name}")
        fail(if (l == SpotifyLine.NEEDS_WIFI) SpotifyProblem.NO_WIFI else SpotifyProblem.UNREACHABLE)
    }

    /** A start or the playback stopped on [p] (§5.8's table): paused, its line, its stored word. */
    private fun fail(p: SpotifyProblem) {
        ports.log("spotify: stopped - ${p.name}")
        val ours = tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING
        if (ours && host.alive) sdk("pause") // our own device only: never a pause of the account's other player
        if (ours) tracker.lost()
        cancelFirstState()
        cancelGap()
        line = p.line
        p.outcome?.let { ports.outcome(it) }
        when (p) {
            SpotifyProblem.NO_WIDEVINE, SpotifyProblem.STOPPED -> {
                stopped = true
                destroyHost()
            }
            SpotifyProblem.OFF -> forgetAccount()
            else -> Unit
        }
        if (pwr) {
            pwr = false
            pauseCause = PauseCause.OTHER
        }
        changed()
    }

    private fun pause(cause: PauseCause) {
        val s = start
        if (s != null) {
            if (s.calling) s.cancelled = true else dropStart(s)
        }
        queued = null
        cancelFirstState()
        if (host.alive && (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING)) {
            sdk("pause")
            tracker.pauseSent()
        }
        suppressed = false
        if (pwr) {
            pwr = false
            pauseCause = cause
        }
        changed()
    }

    // --- the page's events ---

    private fun onHost(e: HostEvent) {
        when (e) {
            is HostEvent.State -> e.json?.let { ports.recordState(it) }
            is HostEvent.Ready -> ports.record("ready")
            is HostEvent.NotReady -> ports.record("not_ready")
            is HostEvent.Error -> ports.record("error ${e.kind}") // the kind only, never the SDK's text
            HostEvent.Gone -> ports.record("gone")
            is HostEvent.NeedToken -> Unit
        }
        when (e) {
            is HostEvent.Ready -> {
                if (e.deviceId != deviceId) {
                    // a new Connect device: its Spotify queue is empty, its settings are the account's again
                    tracker.newDevice()
                    needSettings = true
                    deviceId = e.deviceId
                    ports.log("spotify: the web player is ready")
                }
                readyWait?.complete(e.deviceId)
                deviceWait?.complete(true)
            }
            is HostEvent.NotReady -> {
                if (e.deviceId == deviceId) deviceId = null
                apply(tracker.onNotReady())
                changed()
            }
            is HostEvent.NeedToken -> answerToken(e.requestId)
            is HostEvent.State -> {
                deviceWait?.complete(true)
                if (start?.calling == true) {
                    held.addLast(e.snapshot to ports.now())
                    while (held.size > HELD_MAX) held.removeFirst()
                } else {
                    feed(e.snapshot)
                }
            }
            is HostEvent.Error -> onSdkError(e.kind, e.message)
            HostEvent.Gone -> {
                deviceId = null
                val playing = start != null || pwr || tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING
                if (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING) tracker.lost()
                if (start != null) {
                    sdkProblem = SpotifyProblem.STOPPED
                    readyWait?.complete(null)
                } else if (playing) {
                    fail(SpotifyProblem.STOPPED)
                }
                changed()
            }
        }
    }

    private fun onSdkError(kind: String, message: String) {
        // the SDK's own text only in a debug build (§7.1; qa-26c-step5a-code L8): it is foreign text
        ports.log("spotify: the web player said $kind" + if (BuildConfig.DEBUG) ": $message" else "")
        if (kind == "playback") {
            apply(tracker.onPlaybackError())
            changed()
            return
        }
        if (kind == "authentication") {
            // read through the token answer the renderer already has (§5.5; L3): during a start its own
            // token decides; else the last failed answer says why, and a failure without one is the network
            if (start == null) fail(tokenFailure?.problem() ?: SpotifyProblem.UNREACHABLE)
            return
        }
        val p = sdkErrorProblem(kind) ?: return // autoplay, csp: the log only
        if (start != null) {
            sdkProblem = p
            readyWait?.complete(null)
        } else {
            fail(p)
        }
    }

    /** The SDK's `getOAuthToken` (at `connect()` and whenever its token expired): from memory, or one ask. */
    private fun answerToken(requestId: Int) {
        scope.launch {
            val t = tokens.get(ports.now() + SPOTIFY_CALL_MS)
            if (t is TokenResult.Ok && t.account != hostAccount) {
                // never another account's token to this page (M1): a start's own check clears it; else now
                if (start == null) accountChanged(t) else host.call("token", requestId, null)
                return@launch
            }
            host.call("token", requestId, (t as? TokenResult.Ok)?.token)
            if (t is TokenResult.Ok) {
                tokenFailure = null
                return@launch
            }
            noteTokenFailure(t)
            // the SDK asks when its token expired, so without one it stops: say why (§5.8; L3) - an adult's
            // matter for `reauthorize`, the entries' end for `off`; the rest is read at its authentication error
            if (start == null && (t == TokenResult.Off || t == TokenResult.Reauthorize)) {
                fail(t.problem() ?: SpotifyProblem.UNREACHABLE)
            }
        }
    }

    private fun noteTokenFailure(t: TokenResult) {
        if (t !is TokenResult.Ok) tokenFailure = t
    }

    /**
     * A token of another account than the page's (the phone bound to another account, or a new login):
     * the page and its storage go before the token reaches it (§5.2) - the next play makes a new one.
     */
    private fun accountChanged(t: TokenResult.Ok): Boolean {
        if (hostAccount == t.account || start != null) return false
        ports.log("spotify: another account - the web player is cleared")
        pause(PauseCause.OTHER)
        forgetAccount()
        changed()
        return true
    }

    /** One posted state through the tracker. */
    private fun feed(snap: SdkSnapshot) {
        val before = tracker.measured
        val events = tracker.onState(snap, ports.now())
        val m = tracker.measured
        if (m != null && m !== before && m.index == tracker.index) {
            if (snap is SdkState) snap.durationMs?.let { stateDuration = it }
            if (m.playing) lastOursAt = m.atMs
        }
        apply(events)
        syncPlace()
        if (pwr && tracker.mode == Mode.OURS) armGap()
        changed()
    }

    /** The tracker's measurement is the newest place of the current item. */
    private fun syncPlace() {
        val m = tracker.measured ?: return
        if (m.index != index || m.atMs < place.atMs) return
        place = Place(index, m.positionMs, m.atMs, moving = m.playing)
    }

    /** What the tracker's events do (step 4's mapping; §5.4). */
    private fun apply(events: List<Event>) {
        // a play the renderer starts by itself only goes on with a play the kid wants now: never while paused,
        // never under a transient focus loss (qa-26c-step5a-code H1, M2) - "nothing starts by itself"
        val wanted = pwr && !suppressed
        for (e in events) {
            // while a start or a step replaces the old play, only a foreign track still needs its pause
            if ((start != null || stepTimer != null) && e !is Event.NotOurs) continue
            when (e) {
                is Event.Started -> onStarted(e.index)
                is Event.ItemChanged -> moveIndex(e.index, e.auto)
                is Event.Unplayable -> unplayable(e.index, e.stop)
                is Event.Paused -> if (!suppressed && pwr) {
                    pwr = false
                    pauseCause = if (e.ours) PauseCause.USER else PauseCause.OTHER
                }
                Event.Resumed -> if (!pwr) {
                    // detached (M1): the session shows another renderer, so nothing of ours may sound
                    if (!detached && ports.mayPlay() && acquireFocus()) {
                        // our track plays again by the account's other controls: shown as it is
                        pwr = true
                        lastOursAt = ports.now()
                    } else {
                        sdk("pause")
                        tracker.pauseSent()
                    }
                }
                is Event.NotOurs -> {
                    if (e.playing && host.alive) sdk("pause")
                    ports.log("spotify: a track that isn't ours was paused")
                    if (pwr) {
                        pwr = false
                        pauseCause = PauseCause.OTHER
                    }
                }
                Event.Ended -> {
                    ended = true
                    pwr = false
                    explicit += Pending(RendererEvent.Ended, null)
                }
                is Event.NewWindow -> {
                    if (e.index != index) moveIndex(e.index, auto = !e.resume && e.index == index + 1)
                    val pos = if (e.resume) measuredAt(e.index) else 0
                    place = Place(e.index, pos, ports.now(), moving = false)
                    if (wanted && ports.mayPlay()) request(Request(e.index, pos, fresh = false, settings = needSettings))
                }
                is Event.QueueAdd -> queueAdd(e.index)
                is Event.ShuffleOrRepeat -> {
                    // paused, the next play of the kid's turns them off (`needSettings`)
                    ports.log("spotify: shuffle or repeat is on - turned off at the next play")
                    needSettings = true
                    val pos = measuredAt(e.index)
                    place = Place(e.index, pos, ports.now(), moving = false)
                    if (wanted && ports.mayPlay()) request(Request(e.index, pos, fresh = false, settings = true))
                }
                Event.Look -> {
                    lookTimer?.invoke()
                    lookTimer = ports.after(SpotifyTracker.LOOK_MS) {
                        lookTimer = null
                        host.call("state")
                    }
                }
                Event.Elsewhere -> {
                    ports.log("spotify: the account plays elsewhere")
                    saveNow()
                    fail(SpotifyProblem.ELSEWHERE)
                }
                is Event.TrackChanged -> contextTrackChanged(e.uri, e.title)
            }
        }
    }

    /**
     * Context mode (§5.9): Spotify moved on to [uri] in our context. The item's title is swapped in place (the player,
     * the session, the notification; the entry's cover stays its art - the phone loads no remote picture), the live
     * track is stored (`spotify_context.json`: the tile's item, a restart, where a playlist goes on) and the place
     * moves to it (the position row holds the live track's place).
     */
    private fun contextTrackChanged(uri: String, title: String?) {
        val key = contextKey ?: return
        val t = title ?: contextName ?: return
        liveTitle = t
        showTitle(t)
        ports.storeContextTrack(key, SpotifyContextTrack(uri, t))
        saveNow()
        // Spotify moving on on a network the gate refuses now (streaming on mobile data not allowed, roaming): paused
        // with the line - list mode's advance is gated by the service's onItemChanged (§5.10), a context's here
        if (pwr) ports.networkLine()?.let { refuse(it) }
    }

    /** The current item's title, in place (context mode). */
    private fun showTitle(title: String) {
        val mi = mediaItems.getOrNull(0) ?: return
        if (mi.mediaMetadata.title?.toString() == title) return
        mediaItems = mediaItems.toMutableList().also { it[0] = titled(mi, title) }
        changed()
    }

    private fun titled(item: MediaItem, title: String): MediaItem =
        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(title).build()).build()

    /** One of the SDK's transport calls (pause, resume, seek, next, previous): to the page, and its name to the recorder. */
    private fun sdk(name: String, vararg args: Any?) {
        host.call(name, *args)
        ports.record(name)
    }

    private fun measuredAt(i: Int): Long = tracker.measured?.takeIf { it.index == i }?.positionMs ?: place.takeIf { it.index == i }?.posMs ?: 0

    private fun onStarted(j: Int) {
        cancelFirstState()
        if (j != index) moveIndex(j, auto = false)
        line = null
        stopped = false
        ports.outcome(SpotifyWords.OK)
        lastOursAt = ports.now()
        explicit += Pending(RendererEvent.Ready, null)
        armGap()
        armPrefetch()
    }

    /** An unplayable item: the service hears `Error("unplayable")` with that item current, and moves on through [moveOnTo]. */
    private fun unplayable(k: Int, stop: Boolean) {
        if (k != index && k in tracks.indices) moveIndex(k, auto = false)
        flush()
        explicit += Pending(RendererEvent.Error(UNPLAYABLE), MoveOn(stop))
        if (stop) {
            ports.log("spotify: three unplayable in a row - stopped")
            if (host.alive) sdk("pause")
            if (pwr) {
                pwr = false
                pauseCause = PauseCause.OTHER
            }
        }
        flush()
    }

    /**
     * The service's `onError` moves on to [target] (§5.10): after the third nothing plays (the index
     * only moves); when Spotify already plays it or past it, nothing is sent; else one play from it -
     * only while the kid wants it playing (step 6: a `playback_error` on a paused track only moves the
     * index, as a step while paused does - nothing starts by itself, qa-26c-step5a-code H1's rule).
     */
    private fun moveOnTo(target: Int, ms: Long, mo: MoveOn) {
        if (mo.stop) {
            moveIndex(target, auto = false)
            place = Place(target, ms, ports.now(), moving = false)
        } else if (target <= tracker.index && (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING)) {
            if (target != index) moveIndex(target, auto = false)
        } else {
            moveIndex(target, auto = false)
            place = Place(target, ms, ports.now(), moving = false)
            if (pwr && ports.mayPlay()) request(Request(target, ms, fresh = false, settings = needSettings))
        }
        changed()
    }

    /** Add queue item [i] to Spotify's queue, one track ahead (§5.4, "Past the window"); a failed add is a gap. */
    private fun queueAdd(i: Int) {
        val device = deviceId ?: return
        val uri = tracks.getOrNull(i)?.uri ?: return
        scope.launch {
            val t = tokens.get(ports.now() + SPOTIFY_CALL_MS, minLeftMs = QUEUE_TOKEN_MIN_LEFT_MS) as? TokenResult.Ok ?: return@launch
            val outcome = runInterruptible(Dispatchers.IO) { api.queue(t.token, device, uri) }
            if (outcome != PlayOutcome.Ok) {
                ports.log("spotify: the queue add failed")
                return@launch
            }
            ports.record("queue $i")
            if (released || deviceId != device || tracks.getOrNull(i)?.uri != uri) return@launch
            apply(tracker.queued(i))
            changed()
        }
    }

    private fun moveIndex(i: Int, auto: Boolean) {
        if (i == index) return
        index = i
        autoPending = auto
        stateDuration = null
        if (place.index != i) place = Place(i, 0, ports.now(), moving = false)
    }

    // --- focus, noisy, volume ---

    private fun acquireFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = ports.requestFocus(speech) { onFocus(it) }
        return focusHeld
    }

    private fun onFocus(change: FocusChange) {
        if (released) return
        ports.log("spotify: audio focus ${change.name}")
        when (change) {
            FocusChange.LOSS -> {
                focusHeld = false
                if (pwr) pause(PauseCause.FAULT)
            }
            FocusChange.LOSS_TRANSIENT -> if (pwr && !suppressed) {
                suppressed = true
                cancelGap()
                if (host.alive && (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING)) {
                    sdk("pause")
                    tracker.pauseSent()
                }
                explicit += Pending(RendererEvent.Interrupted, null)
                changed()
            }
            FocusChange.DUCK -> {
                ducked = true
                applyVolume()
            }
            FocusChange.GAIN -> {
                if (ducked) {
                    ducked = false
                    applyVolume()
                }
                if (suppressed) {
                    suppressed = false
                    val live = host.alive && (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING)
                    when {
                        !pwr || start != null -> Unit // a start still on its way goes on as it is
                        live -> {
                            // our track, or the play sent under the loss and paused (M2): it plays again
                            sdk("resume")
                            tracker.resumeSent()
                            lastOursAt = ports.now()
                            if (tracker.mode == Mode.STARTING) armFirstState(ports.now() + SPOTIFY_FIRST_STATE_MS) else armGap()
                        }
                        ports.mayPlay() -> request(Request(index, startPosition(), fresh = false, settings = needSettings))
                    }
                    explicit += Pending(RendererEvent.InterruptionEnded, null)
                    changed()
                }
            }
        }
    }

    /** A headset unplugged or Bluetooth gone (§5.6): paused, as the phone's own player does. */
    private fun onNoisy() {
        if (released || !pwr) return
        ports.log("spotify: becoming noisy - paused")
        pause(PauseCause.FAULT)
    }

    /** The SDK's volume: 1, the fade's step, or 0.3 while ducked (the system volume and the cap stay Android's). */
    private fun applyVolume() {
        if (!host.alive) return
        val v = minOf(fadeLevel, if (ducked) DUCK_VOLUME else 1.0)
        host.call("volume", v)
    }

    // --- timers ---

    private fun cancelStep() {
        stepTimer?.invoke()
        stepTimer = null
    }

    /** After the play call's 204: our first uri playing within 10 s (and the start's budget). */
    private fun armFirstState(deadline: Long) {
        cancelFirstState()
        val wait = minOf(SPOTIFY_FIRST_STATE_MS, deadline - ports.now()).coerceAtLeast(0)
        firstStateTimer = ports.after(wait) {
            firstStateTimer = null
            if (pwr && !suppressed && tracker.mode == Mode.STARTING && start == null) {
                ports.log("spotify: no state of ours after the play call")
                fail(SpotifyProblem.UNREACHABLE)
            }
        }
    }

    private fun cancelFirstState() {
        firstStateTimer?.invoke()
        firstStateTimer = null
    }

    /** No state of ours for 30 s while we play (§5.4's table): paused, "trenger internett". */
    private fun armGap() {
        cancelGap()
        if (!pwr || suppressed || tracker.mode != Mode.OURS) return
        val at = lastOursAt + SPOTIFY_STATE_GAP_MS
        gapTimer = ports.after((at - ports.now()).coerceAtLeast(0)) {
            gapTimer = null
            if (pwr && !suppressed && tracker.mode == Mode.OURS && start == null) {
                if (ports.now() - lastOursAt >= SPOTIFY_STATE_GAP_MS) {
                    ports.log("spotify: no state for ${SPOTIFY_STATE_GAP_MS / 1000} s")
                    fail(SpotifyProblem.UNREACHABLE)
                } else {
                    armGap()
                }
            }
        }
    }

    private fun cancelGap() {
        gapTimer?.invoke()
        gapTimer = null
    }

    /** The page is destroyed 5 minutes after the last play (§5.2): the device leaves the account's Connect list. */
    private fun armIdle() {
        if (idleTimer != null || !host.alive) return
        idleTimer = ports.after(SPOTIFY_HOST_IDLE_MS) {
            idleTimer = null
            if (!pwr && start == null) {
                ports.log("spotify: idle for ${SPOTIFY_HOST_IDLE_MS / 60_000} min - the web player goes")
                destroyHost()
                changed()
            }
        }
    }

    private fun cancelIdle() {
        idleTimer?.invoke()
        idleTimer = null
    }

    /** The token refreshed ahead of expiry while playing (§5.5, QA M2), under the wake lock. */
    private fun armPrefetch() {
        prefetchTimer?.invoke()
        prefetchTimer = null
        if (!pwr || released) return
        val at = tokens.nextPrefetchAtMs() ?: return
        prefetchTimer = ports.after((at - ports.now()).coerceAtLeast(PREFETCH_MIN_WAIT_MS)) {
            prefetchTimer = null
            if (!pwr || released) return@after
            if (tokens.prefetchDue(playing = true)) {
                scope.launch {
                    val t = tokens.prefetch(ports.now() + SPOTIFY_CALL_MS)
                    if (t is TokenResult.Ok && accountChanged(t)) return@launch
                    noteTokenFailure(t)
                    armPrefetch()
                }
            } else {
                armPrefetch()
            }
        }
    }

    private fun destroyHost() {
        cancelIdle()
        lookTimer?.invoke()
        lookTimer = null
        if (tracker.mode == Mode.OURS || tracker.mode == Mode.STARTING) tracker.lost()
        if (host.alive) host.destroy()
        deviceId = null
        readyWait?.complete(null)
    }

    // --- events to the service, and the session's player ---

    private class Pending(val event: RendererEvent, val moveOn: MoveOn?)

    private val explicit = mutableListOf<Pending>()
    private var autoPending = false
    private var lastIndex = -1
    private var lastPwr = false
    private var lastShows = false
    private var inHandler = 0
    private var flushing = false
    private var again = false
    private var flushPosted = false

    private fun changed() {
        if (released) return
        syncHolds()
        sbp?.refresh()
        if (inHandler > 0) postFlush() else flush()
    }

    /** The locks, the noisy receiver, the focus and the timers follow what the player shows. */
    private fun syncHolds() {
        val playing = pwr && !ended
        if (playing != locksHeld) {
            locksHeld = playing
            ports.holdLocks(playing)
        }
        if (playing != noisyWatched) {
            noisyWatched = playing
            ports.watchNoisy(playing) { onNoisy() }
        }
        if (!playing && focusHeld) {
            focusHeld = false
            ports.abandonFocus()
        }
        if (playing || start != null) cancelIdle() else armIdle()
        if (!playing) {
            cancelGap()
            prefetchTimer?.invoke()
            prefetchTimer = null
        } else if (prefetchTimer == null && start == null) {
            armPrefetch()
        }
    }

    private fun postFlush() {
        if (flushPosted) return
        flushPosted = true
        ports.after(0) {
            flushPosted = false
            if (!released) flush()
        }
    }

    /** The service hears what changed (the local renderer's events, in kind); re-entrant calls flush again after. */
    private fun flush() {
        if (flushing) {
            again = true
            return
        }
        flushing = true
        try {
            do {
                again = false
                val events = mutableListOf<Pending>()
                if (tracks.isNotEmpty() && index != lastIndex) {
                    lastIndex = index
                    events += Pending(RendererEvent.ItemChanged(index, autoPending), null)
                    autoPending = false
                }
                val ending = explicit.any { it.event == RendererEvent.Ended }
                if (pwr != lastPwr) {
                    lastPwr = pwr
                    if (!ending) events += Pending(if (pwr) RendererEvent.Resumed else RendererEvent.Paused(pauseCause), null)
                }
                if (showsPlaying != lastShows) {
                    lastShows = showsPlaying
                    events += Pending(RendererEvent.IsPlaying(lastShows), null)
                }
                events += explicit
                explicit.clear()
                events += Pending(RendererEvent.StateChanged, null)
                for (p in events) {
                    moveOn = p.moveOn
                    try {
                        onEvent?.invoke(p.event)
                    } finally {
                        moveOn = null
                    }
                }
            } while (again)
        } finally {
            flushing = false
        }
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

    /** The session's player: its State is [spec]'s; every handler only sets intent (as SonosRenderer's). */
    private inner class SpotifyPlayer(looper: Looper) : SimpleBasePlayer(looper) {
        fun refresh() = invalidateState()

        override fun getState(): State {
            val sp = spec()
            val b = State.Builder()
                .setAvailableCommands(COMMANDS)
                .setDeviceInfo(DEVICE_INFO)
                .setAudioAttributes(audioAttributes(speech))
                .setPlayWhenReady(sp.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            val items = mediaItems
            if (items.isEmpty()) {
                return b.setPlaybackState(if (sp.playbackState == Player.STATE_ENDED) Player.STATE_ENDED else Player.STATE_IDLE).build()
            }
            val seen = mutableSetOf<String>()
            val playlist = items.mapIndexed { i, mi ->
                val base = mi.mediaId.ifEmpty { "item$i" }
                val uid = if (seen.add(base)) base else "$base#$i".also { seen.add(it) }
                val durationMs = if (i == index) durationMs() else mi.mediaMetadata.durationMs
                MediaItemData.Builder(uid)
                    .setMediaItem(mi)
                    .setMediaMetadata(mi.mediaMetadata)
                    .setDurationUs(durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                    .setIsSeekable(durationMs != null)
                    .build()
            }
            return b.setPlaylist(playlist)
                .setCurrentMediaItemIndex(sp.index.coerceIn(playlist.indices))
                .setPlaybackState(sp.playbackState)
                .setPlaybackSuppressionReason(
                    if (sp.suppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                )
                .setContentPositionMs { positionMs() }
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
            handling { if (playWhenReady) play() else pause() }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = handling {
            if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == index) seekTo(positionMs.coerceAtLeast(0)) else seekTo(mediaItemIndex, 0)
        }

        override fun handleStop(): ListenableFuture<*> = handling { pause() }

        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    companion object {
        /** The item error of a track the account can't play (§5.8). */
        const val UNPLAYABLE = "unplayable"

        /** The SDK's volume while another app ducks us (§5.6). */
        const val DUCK_VOLUME = 0.3

        /** The fade's step (the phone's 250 ms, LocalRenderer). */
        const val FADE_STEP_MS = 250L

        /** A queue add's token needs only this long left (it is one call). */
        const val QUEUE_TOKEN_MIN_LEFT_MS = 60_000L

        /** The prefetch timer never spins faster than this (an ask in flight isn't "due"). */
        const val PREFETCH_MIN_WAIT_MS = 1_000L

        /** The play call's path (its send time splits the states held through a start's calls). */
        const val PLAY_PATH = "/v1/me/player/play"

        /** A clear of the web player's stores is awaited at most this long before the next page. */
        const val CLEAR_WAIT_MS = 3_000L

        /** At most this many states held through a start's calls (the page posts every 5 s and on every change). */
        const val HELD_MAX = 32

        /** Play, pause, stop, the four skips (KidPlayer routes them), seeks, and the GETs the platform session needs (26a QA M1). */
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()

        /**
         * The page's account marker (qa-26c-step5a-code M1): `noBackupFilesDir/spotify_page_account`. The service
         * asks whether one exists when the library has no Spotify entry left (§5.2's clearing after a restart).
         */
        fun pageAccountFile(context: Context): File = File(context.noBackupFilesDir, "spotify_page_account")

        /** Local playback: the volume keys are the phone's STREAM_MUSIC (and the launcher's cap), §5.6. */
        private val DEVICE_INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()

        /**
         * The Android renderer (step 5b's service makes one, kept for the service's life): the page, the
         * Web API over the guarded public-only client (the LAN client with the debug switch, §9.3), the
         * token from the launcher's bridge (or the fake's route with the switch), the locks, focus and
         * noisy receiver, `persistPlace` for the bookmark, [outcome] for `spotify_state.json` and [log]
         * the debug log (fixed words only).
         */
        fun create(
            context: Context,
            store: Store,
            scope: CoroutineScope,
            writer: CoroutineDispatcher,
            queue: () -> Active?,
            outcome: (String) -> Unit,
            log: (String) -> Unit,
        ): SpotifyRenderer {
            val app = context.applicationContext
            val lan = SpotifyEndpoints.apiOverLan()
            val http = OkHttpSpotifyHttp(MediaCache.httpClient(publicOnly = !lan), "VibbMusic/${BuildConfig.VERSION_NAME}")
            val base = SpotifyEndpoints.apiBase()
            val fakeTokens = SpotifyEndpoints.debugTokens()
            val fakeApi = SpotifyApi(http, base, SystemClock::elapsedRealtime)
            // the fake's token route only for a run without a launcher (§9.3): a managed phone - the debug launcher
            // with the server on the fake's accounts base, §6l part A - takes its token through the bridge
            val ask: suspend () -> TokenResult = suspend {
                if (fakeTokens != null && withContext(Dispatchers.IO) { !Bridge.config(app).managed }) {
                    runInterruptible(Dispatchers.IO) { fakeApi.debugToken(fakeTokens) }
                } else {
                    val t = withContext(Dispatchers.IO) { SpotifyBridgeToken.get(app) }
                    // with the switch set only a fake token meets the debug base (§9.3, qa-26c-step5b-code L2)
                    if (fakeTokens == null) {
                        t
                    } else {
                        debugSwitchToken(t).also { if (it !== t) log("spotify: a real token with the debug switch - not sent") }
                    }
                }
            }
            val ports = AndroidSpotifyPorts(app, store, scope, writer, SpotifyLocks(app, log), outcome, log)
            return SpotifyRenderer(SpotifyWebHost(app, log), http, base, ask, ports, Dispatchers.Main, queue, Looper.getMainLooper())
        }
    }
}

/** The renderer's Android edges (§5.6, §5.7): the main thread's timers, focus, noisy, locks, the gate, the bookmark's write. */
private class AndroidSpotifyPorts(
    private val app: Context,
    private val store: Store,
    private val scope: CoroutineScope,
    private val writer: CoroutineDispatcher,
    private val locks: SpotifyLocks,
    private val outcomeSink: (String) -> Unit,
    private val logSink: (String) -> Unit,
) : SpotifyPorts {
    private val handler = Handler(Looper.getMainLooper())
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun after(delayMs: Long, block: () -> Unit): () -> Unit {
        val r = Runnable(block)
        handler.postDelayed(r, delayMs.coerceAtLeast(0))
        return { handler.removeCallbacks(r) }
    }

    override fun requestFocus(speech: Boolean, onChange: (FocusChange) -> Unit): Boolean {
        val am = audio ?: return true
        abandonFocus()
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(if (speech) android.media.AudioAttributes.CONTENT_TYPE_SPEECH else android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true) // the renderer ducks the SDK's volume itself (§5.6)
            .setOnAudioFocusChangeListener({ change ->
                val c = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
                    AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.DUCK
                    else -> null
                }
                if (c != null) onChange(c)
            }, handler)
            .build()
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = request
        return granted
    }

    override fun abandonFocus() {
        val request = focus ?: return
        focus = null
        audio?.abandonAudioFocusRequest(request)
    }

    override fun watchNoisy(on: Boolean, onNoisy: () -> Unit) {
        noisy?.let { runCatching { app.unregisterReceiver(it) } }
        noisy = null
        if (!on) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
            }
        }
        app.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        noisy = receiver
    }

    override fun holdLocks(on: Boolean) = locks.hold(on)

    override fun renewLocks() = locks.renew()

    override fun mayPlay(): Boolean = playAllowed(
        try {
            app.packageManager.isPackageSuspended
        } catch (e: Exception) {
            false
        },
    )

    override fun networkLine(): SpotifyLine? = spotifyNetworkLine(netCaps(app), store.streamMobileData)

    override fun save(a: Active, index: Int, posMs: Long, durationMs: Long?, force: Boolean) =
        persistPlace(app, store, scope, writer, a, index, posMs, durationMs, durationMs, force)

    override fun outcome(word: String) = outcomeSink(word)

    private val accountFile = SpotifyRenderer.pageAccountFile(app)

    override fun pageAccount(): String? = runCatching { accountFile.readText().trim() }.getOrNull()?.takeIf { ACCOUNT.matches(it) }

    override fun storePageAccount(account: String?) {
        try {
            if (account == null) {
                accountFile.delete()
                return
            }
            val tmp = File(accountFile.path + ".tmp")
            tmp.writeText(account)
            if (!tmp.renameTo(accountFile)) logSink("spotify: the page's account marker not written")
        } catch (e: Exception) {
            logSink("spotify: the page's account marker not written (${e.javaClass.simpleName})")
        }
    }

    override fun log(message: String) = logSink(message)

    override fun contextTrack(entryKey: String): SpotifyContextTrack? = store.spotifyContexts()[entryKey]

    override fun storeContextTrack(entryKey: String, track: SpotifyContextTrack?) {
        scope.launch(writer) { store.recordSpotifyContext(entryKey, track) }
    }

    // the debug build's recorder (§9.4): nothing unless src/debug set the hook - no file, no log here
    override fun recordState(json: String) {
        SpotifyRecording.recorder?.state(now(), json)
    }

    override fun record(event: String) {
        SpotifyRecording.recorder?.event(now(), event)
    }

    private companion object {
        val ACCOUNT = Regex("[0-9]{1,18}:[0-9]{1,18}")
    }
}
