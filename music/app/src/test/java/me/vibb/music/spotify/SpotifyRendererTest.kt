package me.vibb.music.spotify

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.vibb.music.bridge.TokenResult
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.SPOTIFY_STATE_GAP_MS
import me.vibb.music.logic.SPOTIFY_STEP_COALESCE_MS
import me.vibb.music.logic.SpotifyContextTrack
import me.vibb.music.logic.SpotifyLine
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.playback.Active
import me.vibb.music.playback.PauseCause
import me.vibb.music.playback.RendererEvent
import me.vibb.music.playback.RendererKind
import me.vibb.music.playback.RendererState
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

/**
 * Spotify's renderer (design 26c §5.4-§5.8, §11 step 5a) against a scripted page ([SpotifyHost]), a
 * scripted Web API ([SpotifyHttp]) and a virtual clock (the ports' timers): §5.4's state table row by row,
 * one play call per start, a second tap and mashed steps sending nothing more, the window's end adding to
 * Spotify's queue, the end of the queue, focus, noisy, the fade, a suspension, "elsewhere", three
 * unplayable, and step 4's events (`NewWindow(resume)`, `ShuffleOrRepeat`, `TokenRefused`). The test's
 * own `onEvent` plays the service's part where the renderer relies on it (the move-on after an unplayable
 * track, `PlaybackService.onError`).
 */
class SpotifyRendererTest {
    private var now = 1_000_000L
    private var seq = 0L

    private class Task(val at: Long, val seq: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    private fun after(delayMs: Long, block: () -> Unit): () -> Unit {
        val t = Task(now + delayMs.coerceAtLeast(0), seq++, block)
        synchronized(tasks) { tasks += t }
        return { t.cancelled = true }
    }

    /** The virtual clock moves [ms]: every task due runs in order (the coroutines they wake run between them). */
    private suspend fun advance(ms: Long) {
        val target = now + ms
        settle() // what was just asked runs first: it may set timers due now
        while (true) {
            val next = synchronized(tasks) {
                tasks.removeAll { it.cancelled }
                tasks.filter { it.at <= target }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq }))?.also { tasks.remove(it) }
            } ?: break
            now = maxOf(now, next.at)
            next.block()
            settle()
        }
        now = target
        settle()
    }

    /** Let the renderer's coroutines run (the Web API calls hop to IO and back). */
    private suspend fun settle() {
        repeat(25) { delay(1) }
    }

    // --- the ports ---

    private inner class Ports : SpotifyPorts {
        val log = mutableListOf<String>()
        var noisy: (() -> Unit)? = null
        var locks = false
        var renewals = 0
        var mayPlay = true
        var network: SpotifyLine? = null
        val saves = mutableListOf<Triple<Int, Long, Boolean>>()

        /** Each save's duration ("heard to the end"): a context's item is always saved with none (§5.9). */
        val savedDurations = mutableListOf<Long?>()
        val outcomes = mutableListOf<String>()

        /** The page's account marker on disk (M1); the default account's, as after an earlier run. */
        var marker: String? = "2:1"

        override fun now(): Long = this@SpotifyRendererTest.now
        override fun after(delayMs: Long, block: () -> Unit): () -> Unit = this@SpotifyRendererTest.after(delayMs, block)
        override fun watchNoisy(on: Boolean, onNoisy: () -> Unit) {
            noisy = if (on) onNoisy else null
        }

        override fun holdLocks(on: Boolean) {
            locks = on
        }

        override fun renewLocks() {
            renewals++
        }

        override fun mayPlay(): Boolean = mayPlay
        override fun networkLine(): SpotifyLine? = network
        override fun save(a: Active, index: Int, posMs: Long, durationMs: Long?, force: Boolean) {
            saves += Triple(index, posMs, force)
            savedDurations += durationMs
        }

        override fun outcome(word: String) {
            outcomes += word
        }

        override fun pageAccount(): String? = marker

        override fun storePageAccount(account: String?) {
            marker = account
        }

        override fun log(message: String) {
            log += message
        }

        /** `spotify_context.json` (§5.9): the live track per entry key, as the store keeps it. */
        val contexts = mutableMapOf<String, SpotifyContextTrack>()

        /** The debug recorder's lines (§9.4): `state <json>` and the events by name. */
        val recorded = mutableListOf<String>()

        override fun contextTrack(entryKey: String): SpotifyContextTrack? = contexts[entryKey]

        override fun storeContextTrack(entryKey: String, track: SpotifyContextTrack?) {
            if (track == null) contexts -= entryKey else contexts[entryKey] = track
        }

        override fun recordState(json: String) {
            recorded += "state $json"
        }

        override fun record(event: String) {
            recorded += event
        }
    }

    /**
     * The scripted page, as `player.js` and the fake's stub behave (qa-26c-step5a-code L9): a new page's SDK asks
     * for a token at `connect()` (`needToken`), and `ready` follows a non-null `vibb.token` - unless [autoReady]
     * is null (the test drives both). Records its calls and its lifecycle ([log]: open, destroy, clear).
     */
    private inner class Page : SpotifyHost {
        override var events: ((HostEvent) -> Unit)? = null
        override var alive = false
        var opens = 0
        val log = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var autoReady: String? = DEVICE
        private var request = 0
        private var asking = -1

        val destroys get() = log.count { it == "destroy" }
        val clears get() = log.count { it == "clear" }

        override fun open() {
            alive = true
            opens++
            log += "open"
            if (autoReady != null) {
                val id = ++request
                asking = id
                after(0) { emit(HostEvent.NeedToken(id)) }
            }
        }

        override fun call(name: String, vararg args: Any?) {
            calls += SpotifyPage.call(name, *args)
            if (name == "token" && args[0] == asking && args[1] != null) {
                asking = -1
                autoReady?.let { id -> after(0) { emit(HostEvent.Ready(id)) } }
            }
        }

        override fun destroy() {
            alive = false
            log += "destroy"
        }

        override fun clearStorage(done: () -> Unit) {
            log += "clear"
            after(0, done)
        }

        fun emit(e: HostEvent) = events?.invoke(e)
    }

    /** The scripted Web API: 204 to everything unless [answer] says otherwise; [gate] holds the play calls. */
    private class Api : SpotifyHttp {
        val sent = mutableListOf<SpotifyRequest>()
        var answer: (SpotifyRequest) -> SpotifyResponse = { SpotifyResponse(204, null, ByteArray(0)) }
        var gate: CountDownLatch? = null

        override fun send(request: SpotifyRequest): SpotifyResponse {
            synchronized(this) { sent += request }
            if (request.url.encodedPath == "/v1/me/player/play") gate?.await(5, TimeUnit.SECONDS)
            return answer(request)
        }

        fun all(): List<SpotifyRequest> = synchronized(this) { sent.toList() }
        fun plays() = all().filter { it.url.encodedPath == "/v1/me/player/play" }
        fun paths() = all().map { it.method + " " + it.url.encodedPath + (it.url.encodedQuery?.let { q -> "?$q" } ?: "") }
    }

    private val ports = Ports()
    private val page = Page()
    private val api = Api()
    private val events = mutableListOf<RendererEvent>()
    private var asks = 0
    private var tokenLife = 60 * 60_000L
    private var account = "2:1"
    private var sameToken = false
    private var refused: TokenResult? = null
    private var active: Active? = null

    /** Play the service's part on an unplayable track (`PlaybackService.onError`: record, then on to the next). */
    private var serviceMovesOn = true

    /** The current item at each `Error("unplayable")`: the one the service records (`item_errors`). */
    private val errorsAt = mutableListOf<Int>()

    private fun key(n: Int) = "FAKEtrack%013d".format(n)
    private fun uri(n: Int) = "spotify:track:${key(n)}"

    private fun state(
        n: Int,
        pos: Long,
        paused: Boolean = false,
        prev: List<Int> = emptyList(),
        duration: Long = DURATION,
        shuffle: Boolean = false,
        repeat: Int = 0,
    ): SdkSnapshot = SdkState(
        paused, pos, duration, uri(n), null, listOf(n + 1, n + 2).map { uri(it) }, prev.map { uri(it) },
        "track", "Fake track $n", listOf("Fake Artist"), null, shuffle = shuffle, repeat = repeat,
    )

    private suspend fun post(s: SdkSnapshot) {
        page.emit(HostEvent.State(s))
        settle()
    }

    private fun CoroutineScope.renderer(count: Int = 5, duration: Long = DURATION, session: Boolean = false): SpotifyRenderer {
        val entry = Entry(6, "e6", "Kveldsliste", null, Source.SPOTIFY, "spotify:playlist:FAKEplaylist0000000001", "auto", 0, true, null, "11ee", 6, spotifyMode = "list")
        val items = (0 until count).map { PlayItem(6, key(it), "Fake track $it — Fake Artist", null, false, duration) }
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val ask: suspend () -> TokenResult = {
            asks++
            refused ?: TokenResult.Ok(if (sameToken) "FAKE-ACCESS-1" else "FAKE-ACCESS-$asks", now + tokenLife, account)
        }
        // session: a player Media3 can drive on this thread (the test's Looper and TestClock, §6l A F1); else none
        val r = if (session) {
            SpotifyRenderer(page, api, "https://api.spotify.com".toHttpUrl(), ask, ports, dispatcher, { active }, Looper.forCurrentThread(), TestClock)
        } else {
            SpotifyRenderer(page, api, "https://api.spotify.com".toHttpUrl(), ask, ports, dispatcher, { active }, null)
        }
        r.onEvent = { e ->
            events += e
            if (e is RendererEvent.Error) errorsAt += r.currentIndex() // the item the service records the error for
            if (e is RendererEvent.Error && serviceMovesOn) {
                val next = r.currentIndex() + 1
                if (next < r.itemCount()) {
                    r.seekTo(next, 0)
                    r.prepare()
                    r.play()
                } else {
                    r.pause()
                }
            }
        }
        return r
    }

    private fun mediaItems(count: Int) = (0 until count).map { MediaItem.Builder().setMediaId("6/${key(it)}").build() }

    /** A loaded queue, played: the page ready at once, the play call, our first state. */
    private suspend fun CoroutineScope.playing(count: Int = 5, at: Int = 0, duration: Long = DURATION): SpotifyRenderer {
        val r = renderer(count, duration)
        r.load(mediaItems(count), at, 0, playNow = true)
        advance(0)
        post(state(at, 0, duration = duration))
        return r
    }

    private fun body(r: SpotifyRequest) = Json.parseToJsonElement(r.body!!).jsonObject
    private fun windowOf(r: SpotifyRequest) = body(r)["uris"]!!.jsonArray.map { it.jsonPrimitive.content }

    // --- §5.4's table and the start ---

    @Test
    fun `a cold start held 15 s shows the playing intent with one PUT, and a second tap sends nothing`() = runBlocking {
        page.autoReady = null
        val r = renderer()
        assertEquals(RendererKind.SPOTIFY, r.kind)
        assertTrue(r.kind.onPhone)
        r.load(mediaItems(5), 0, 42_000, playNow = true)
        settle()
        var spec = r.spec()
        assertEquals("a start of ours runs", Player.STATE_BUFFERING, spec.playbackState)
        assertTrue(spec.playWhenReady)
        assertTrue("the intent: the button shows pause, design 28 sees playing", r.showsPlaying)
        assertTrue("the wake and Wi-Fi locks while a start runs", ports.locks)
        assertTrue(events.contains(RendererEvent.IsPlaying(true)))
        assertEquals(1, page.opens)
        advance(15_000)
        r.play() // the second tap during the cold start
        settle()
        assertEquals("no call before the page is ready", 0, api.all().size)
        assertTrue(r.showsPlaying)
        assertEquals(Player.STATE_BUFFERING, r.spec().playbackState)
        page.emit(HostEvent.NeedToken(1)) // the SDK's getOAuthToken at connect(): the start's token, from memory
        settle()
        assertEquals("vibb.token(1,\"FAKE-ACCESS-1\")", page.calls.last())
        assertEquals("one token ask for both", 1, asks)
        page.emit(HostEvent.Ready(DEVICE))
        settle()
        assertEquals(
            "a new device: shuffle and repeat off first, each awaited, then the play",
            listOf(
                "PUT /v1/me/player/shuffle?state=false&device_id=$DEVICE",
                "PUT /v1/me/player/repeat?state=off&device_id=$DEVICE",
                "PUT /v1/me/player/play?device_id=$DEVICE",
            ),
            api.paths(),
        )
        val play = api.plays().single()
        assertEquals((0 until 5).map { uri(it) }, windowOf(play))
        assertEquals("the resume position", 42_000L, body(play)["position_ms"]!!.jsonPrimitive.long)
        r.play()
        settle()
        assertEquals("still waiting for our first state: still buffering", Player.STATE_BUFFERING, r.spec().playbackState)
        post(state(0, 42_000))
        spec = r.spec()
        assertEquals(Player.STATE_READY, spec.playbackState)
        assertTrue(spec.playWhenReady && r.showsPlaying)
        assertNull(r.rendererState)
        assertEquals(listOf("ok"), ports.outcomes)
        assertTrue(events.contains(RendererEvent.Ready))
        assertEquals("one PUT in all", 1, api.plays().size)
        assertEquals(1, page.opens)
        r.release()
    }

    @Test
    fun `loaded, not started - no web player, no network, paused and ready`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 2, 30_000, playNow = false)
        settle()
        val spec = r.spec()
        assertEquals(Player.STATE_READY, spec.playbackState)
        assertFalse(spec.playWhenReady || r.showsPlaying)
        assertEquals(2, r.currentIndex())
        assertEquals(30_000L, r.positionMs())
        assertEquals("nothing starts by itself: no page, no call, no token", 0, page.opens + api.all().size + asks)
        r.saveNow()
        assertTrue("nothing measured, nothing saved", ports.saves.isEmpty())
        r.release()
    }

    @Test
    fun `steps within 300 ms coalesce into one play, and a paused step only moves`() = runBlocking {
        val r = playing()
        assertEquals(1, api.plays().size)
        r.seekTo(1, 0)
        advance(100)
        r.seekTo(2, 0)
        advance(100)
        r.seekTo(3, 5_000)
        assertEquals("the player shows the step at once", 3, r.currentIndex())
        assertEquals(Player.STATE_BUFFERING, r.spec().playbackState)
        advance(299)
        assertEquals("a mashed next sends nothing yet", 1, api.plays().size)
        advance(1)
        val plays = api.plays()
        assertEquals("one PUT for the three steps", 2, plays.size)
        assertEquals((3 until 5).map { uri(it) }, windowOf(plays[1]))
        assertEquals(5_000L, body(plays[1])["position_ms"]!!.jsonPrimitive.long)
        assertEquals("shuffle and repeat only before the device's first play", 1, api.paths().count { it.contains("/shuffle") })
        post(state(3, 5_000, prev = listOf(0)))
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        assertEquals(3, r.currentIndex())
        assertTrue("a step is not a natural advance", events.filterIsInstance<RendererEvent.ItemChanged>().none { it.auto })
        r.pause()
        assertTrue(page.calls.last() == "vibb.pause()")
        r.seekTo(4, 0)
        advance(1_000)
        assertEquals("paused: a step only moves where the next play starts", 2, api.plays().size)
        assertEquals(4, r.currentIndex())
        r.release()
    }

    // --- design 39 §3.3 (qa-39-design M2): the kid's order flip in list mode ---

    /** The service's reorder after a flip at the current [keep]: [order] is the queue's new order, the current first. */
    private fun SpotifyRenderer.flip(keep: Int, order: List<Int>) = reorder(keep, order.drop(1).map { mediaItems(5)[it] })

    @Test
    fun `d39 - a flip while paused makes no call, and the next Play is a fresh start in the new order`() = runBlocking {
        val r = playing(at = 2)
        post(state(2, 30_000))
        r.pause()
        post(state(2, 30_000, paused = true))
        val plays = api.plays().size
        r.flip(2, listOf(2, 1, 0, 4, 3))
        advance(1_000)
        assertEquals("no call at all", plays, api.plays().size)
        assertEquals(0, r.currentIndex())
        assertFalse(r.showsPlaying)
        r.play()
        advance(0)
        assertEquals(plays + 1, api.plays().size)
        assertEquals("the new order from the current track", listOf(2, 1, 0, 4, 3).map { uri(it) }, windowOf(api.plays().last()))
        assertEquals("at its place", 30_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        assertFalse("never an SDK resume inside Spotify's old window", page.calls.contains("vibb.resume()"))
        r.release()
    }

    @Test
    fun `d39 - two flips within 300 ms while playing are one play call of the last order, never from 0_00`() = runBlocking {
        val r = playing(at = 2)
        post(state(2, 40_000))
        val plays = api.plays().size
        r.flip(2, listOf(2, 1, 0, 4, 3))
        advance(100)
        r.flip(0, listOf(2, 3, 4, 0, 1)) // flipped back
        advance(299)
        assertEquals("coalesced: nothing yet", plays, api.plays().size)
        advance(1)
        assertEquals("one PUT for both flips", plays + 1, api.plays().size)
        assertEquals(listOf(2, 3, 4, 0, 1).map { uri(it) }, windowOf(api.plays().last()))
        assertEquals("the measured place, not 0:00", 40_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        assertTrue("the same item plays on: no item change for the service", events.filterIsInstance<RendererEvent.ItemChanged>().none { it.index == 0 })
        r.release()
    }

    @Test
    fun `qa-39-code M1 - a flip 10 s after the last state restarts where it plays, not at the last state`() = runBlocking {
        val r = playing(at = 2)
        post(state(2, 40_000))
        advance(10_000)
        r.flip(2, listOf(2, 1, 0, 4, 3))
        advance(300)
        val pos = body(api.plays().last())["position_ms"]!!.jsonPrimitive.long
        assertTrue("about 50 000, was $pos", pos in 49_000..51_000)
        r.release()
    }

    @Test
    fun `the natural advance sends nothing, and the window's end adds the next item to Spotify's queue one ahead`() = runBlocking {
        val r = playing(count = 60)
        assertEquals(50, windowOf(api.plays().single()).size)
        for (n in 1..49) {
            advance(4_000) // each played a while: Spotify's advance, not a skip
            post(state(n, 0, prev = listOf(n - 1)))
        }
        assertEquals(49, r.currentIndex())
        val auto = events.filterIsInstance<RendererEvent.ItemChanged>().filter { it.auto }.map { it.index }
        assertEquals("each one Spotify's own advance", (1..49).toList(), auto)
        assertEquals("no play call for the advance", 1, api.plays().size)
        val adds = api.all().filter { it.url.encodedPath == "/v1/me/player/queue" }
        assertEquals(listOf(uri(50)), adds.map { it.url.queryParameter("uri") })
        assertEquals("POST", adds.single().method)
        assertEquals(DEVICE, adds.single().url.queryParameter("device_id"))
        advance(4_000)
        post(state(50, 0, prev = listOf(47, 48, 49)))
        assertEquals(50, r.currentIndex())
        assertEquals("one ahead again", listOf(uri(50), uri(51)), api.all().filter { it.url.encodedPath == "/v1/me/player/queue" }.map { it.url.queryParameter("uri") })
        assertEquals(1, api.plays().size)
        r.release()
    }

    @Test
    fun `the queue's last item played out is Ended - nothing after it, the locks go`() = runBlocking {
        val r = playing(count = 2, duration = 20_000)
        advance(5_000)
        post(state(1, 0, prev = listOf(0), duration = 20_000))
        assertEquals(1, r.currentIndex())
        advance(15_000)
        post(state(1, 15_000, duration = 20_000))
        advance(5_000)
        post(state(1, 0, paused = true, prev = listOf(0), duration = 20_000))
        val spec = r.spec()
        assertEquals(Player.STATE_ENDED, spec.playbackState)
        assertFalse(r.showsPlaying)
        assertTrue(events.last { it != RendererEvent.StateChanged } == RendererEvent.Ended)
        assertFalse("an end is no pause", events.takeLastWhile { it != RendererEvent.IsPlaying(true) }.any { it is RendererEvent.Paused })
        assertFalse(ports.locks)
        // Autoplay after the queue: paused at once, nothing saved
        val saves = ports.saves.size
        post(SdkState(false, 0, 30_000, "spotify:track:FAKEforeign00000000001", null, emptyList(), listOf(uri(1)), "track", "Something else", listOf("Other"), null))
        assertEquals("vibb.pause()", page.calls.last())
        r.saveNow()
        assertEquals(saves + 1, ports.saves.size)
        assertEquals("the save is our item's measurement, never the foreign track's", 1, ports.saves.last().first)
        r.release()
    }

    @Test
    fun `held through a 30 s gap, paused with trenger internett after it`() = runBlocking {
        val r = playing()
        advance(SPOTIFY_STATE_GAP_MS - 1_000)
        assertTrue("held: a throttled timer, a track change", r.showsPlaying)
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        assertTrue(r.spec().playWhenReady)
        advance(1_001)
        assertFalse(r.showsPlaying)
        assertFalse(r.spec().playWhenReady)
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, r.rendererState)
        assertFalse(r.rendererState!!.remote)
        assertEquals("unreachable", ports.outcomes.last())
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(ports.locks)
        r.release()
    }

    @Test
    fun `no state of ours within 10 s of the play call - the start budget ran out`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals(1, api.plays().size)
        advance(9_999)
        assertTrue(r.showsPlaying)
        advance(1)
        assertFalse(r.showsPlaying)
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, r.rendererState)
        r.release()
    }

    @Test
    fun `a pause cancels a start - nothing sent before the call, a pause after one already sent`() = runBlocking {
        page.autoReady = null
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        settle()
        r.pause()
        page.emit(HostEvent.Ready(DEVICE))
        advance(30_000)
        assertEquals("cancelled before its calls: nothing sent", 0, api.all().size)
        assertFalse(r.showsPlaying)
        assertTrue(events.contains(RendererEvent.Paused(PauseCause.USER)))

        api.gate = CountDownLatch(1)
        r.play()
        settle()
        assertTrue(r.showsPlaying)
        r.pause() // the play call is on its way
        assertFalse(r.showsPlaying)
        api.gate!!.countDown()
        settle()
        assertEquals(1, api.plays().size)
        assertEquals("the sent play is followed by vibb.pause()", "vibb.pause()", page.calls.last())
        post(state(0, 0, paused = true))
        assertFalse(r.showsPlaying)
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        r.release()
    }

    @Test
    fun `a new queue while the old play call is on its way - the old result is dropped, the new one plays after it`() = runBlocking {
        val r = renderer()
        api.gate = CountDownLatch(1)
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals("the first play call is on its way", 1, api.plays().size)
        r.load(mediaItems(5), 2, 7_000, playNow = true) // the kid taps another place while the first call runs
        assertTrue(r.showsPlaying)
        api.gate!!.countDown()
        advance(0)
        val plays = api.plays()
        assertEquals(2, plays.size)
        assertEquals((2 until 5).map { uri(it) }, windowOf(plays[1]))
        assertEquals(7_000L, body(plays[1])["position_ms"]!!.jsonPrimitive.long)
        post(state(2, 7_000))
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        assertEquals(2, r.currentIndex())
        r.release()
    }

    @Test
    fun `a seek before a start's calls (the resume overlap) moves where it plays from`() = runBlocking {
        page.autoReady = null
        val r = renderer()
        r.load(mediaItems(5), 1, 40_000, playNow = true)
        settle()
        r.seekTo(37_000)
        page.emit(HostEvent.Ready(DEVICE))
        advance(0)
        assertEquals(37_000L, body(api.plays().single())["position_ms"]!!.jsonPrimitive.long)
        r.release()
    }

    @Test
    fun `pause and resume within the item are the SDK's - no Web API call`() = runBlocking {
        val r = playing()
        val calls = api.all().size
        r.pause()
        assertEquals("vibb.pause()", page.calls.last())
        post(state(0, 12_000, paused = true))
        assertFalse(r.showsPlaying)
        assertFalse("the locks go on every pause", ports.locks)
        r.seekTo(30_000)
        assertEquals("vibb.seek(30000)", page.calls.last())
        r.play()
        assertEquals("vibb.resume()", page.calls.last())
        assertTrue(r.showsPlaying && ports.locks)
        assertEquals(calls, api.all().size)
        r.release()
    }

    @Test
    fun `only measured positions are saved - never extrapolated, nothing before the first state`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 1, 10_000, playNow = true)
        advance(0)
        r.saveNow()
        assertTrue("the start position is the stored bookmark, no measurement", ports.saves.isEmpty())
        post(state(1, 10_000))
        advance(4_000)
        assertEquals("the display extrapolates", 14_000L, r.positionMs())
        r.saveNow()
        assertEquals(Triple(1, 10_000L, false), ports.saves.single())
        assertTrue("the 15 s save tick renews the locks", ports.renewals > 0)
        r.release()
    }

    // --- §5.6: focus (the WebView's own, §6l A F2), noisy, the fade ---

    @Test
    fun `focus is the WebView's - the renderer requests none, and the page's pause is a fault`() = runBlocking {
        val r = playing()
        val text = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt").readText()
        for (gone in listOf("requestAudioFocus", "AudioFocusRequest", "abandonAudioFocus", "FocusChange")) {
            assertFalse("no focus request of the renderer's own (F2): $gone", text.contains(gone))
        }
        events.clear()
        // Chromium's media session lost the focus to another app (or a call): the page's audio paused, not by us
        post(state(0, 3_000, paused = true))
        assertFalse(r.showsPlaying)
        assertFalse(r.spec().playWhenReady)
        assertTrue("a fault, as the phone's player's focus loss", events.contains(RendererEvent.Paused(PauseCause.FAULT)))
        assertFalse("the locks go with it", ports.locks)
        assertFalse("no pause of ours sent over it", page.calls.contains("vibb.pause()"))
        assertEquals(1, api.plays().size)
        r.release()
    }

    @Test
    fun `a transient focus loss - Chromium pauses the page and plays it again, the player follows both`() = runBlocking {
        val r = playing()
        post(state(0, 5_000))
        events.clear()
        post(state(0, 6_000, paused = true)) // a notification's transient loss: Chromium suspends the page's audio
        assertFalse(r.showsPlaying)
        assertTrue(events.contains(RendererEvent.Paused(PauseCause.FAULT)))
        events.clear()
        advance(3_000)
        post(state(0, 6_000)) // the gain: Chromium resumes it by itself
        assertTrue("shown playing again", r.showsPlaying)
        assertTrue("the service's overlap and listening window hear it", events.contains(RendererEvent.Resumed))
        assertTrue(ports.locks)
        assertEquals("nothing sent: the page plays by itself", 1, api.plays().size)
        assertFalse(page.calls.contains("vibb.resume()"))
        r.release()
    }

    @Test
    fun `a resume by itself is paused at once while the app is suspended or another renderer plays`() = runBlocking {
        val r = playing()
        post(state(0, 6_000, paused = true))
        ports.mayPlay = false
        post(state(0, 6_000))
        assertFalse("the launcher's time rule: nothing plays", r.showsPlaying)
        assertEquals("vibb.pause()", page.calls.last())
        r.release()
    }

    @Test
    fun `the focus decision - a pause not sent is a fault, a resume by itself shows only when allowed`() {
        assertEquals(PauseCause.USER, me.vibb.music.logic.spotifyPauseCause(ours = true))
        assertEquals(PauseCause.FAULT, me.vibb.music.logic.spotifyPauseCause(ours = false))
        assertTrue(me.vibb.music.logic.spotifySelfResumeShown(pausedStep = false, detached = false, mayPlay = true, askedPaused = false))
        assertFalse("a step while paused only moves", me.vibb.music.logic.spotifySelfResumeShown(pausedStep = true, detached = false, mayPlay = true, askedPaused = false))
        assertFalse("another renderer plays", me.vibb.music.logic.spotifySelfResumeShown(pausedStep = false, detached = true, mayPlay = true, askedPaused = false))
        assertFalse("the app is suspended", me.vibb.music.logic.spotifySelfResumeShown(pausedStep = false, detached = false, mayPlay = false, askedPaused = false))
        assertFalse(
            "a pause asked while the WebView held it (QA H1)",
            me.vibb.music.logic.spotifySelfResumeShown(pausedStep = false, detached = false, mayPlay = true, askedPaused = true),
        )
    }

    @Test
    fun `the volume is the fade's only - a duck is the WebView's`() = runBlocking {
        val r = playing()
        r.fadeOutAndPause {}
        advance(5_000)
        assertTrue(page.calls.last(), page.calls.last().startsWith("vibb.volume(") && page.calls.last() != "vibb.volume(1.0)")
        r.cancelFade()
        assertEquals("vibb.volume(1.0)", page.calls.last())
        assertFalse(page.calls.contains("vibb.volume(0.3)"))
        r.release()
    }

    @Test
    fun `becoming noisy pauses, as the phone's player does`() = runBlocking {
        val r = playing()
        events.clear()
        ports.noisy!!()
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        assertTrue(events.contains(RendererEvent.Paused(PauseCause.FAULT)))
        assertNull("the receiver goes with the playing", ports.noisy)
        r.release()
    }

    @Test
    fun `the sleep fade steps the SDK's volume to 0 over 10 s, pauses, then puts it back`() = runBlocking {
        val r = playing()
        assertEquals(10_000L, r.fadeMs)
        var done = false
        r.fadeOutAndPause { done = true }
        advance(5_000)
        assertEquals("vibb.volume(0.5)", page.calls.last())
        assertTrue(r.showsPlaying)
        advance(5_000)
        assertTrue(done)
        assertFalse(r.showsPlaying)
        val tail = page.calls.takeLast(3)
        assertEquals(listOf("vibb.volume(0.0)", "vibb.pause()", "vibb.volume(1.0)"), tail)
        r.play()
        r.fadeOutAndPause { }
        advance(2_000)
        r.cancelFade()
        assertEquals("cancelFade puts the volume back", "vibb.volume(1.0)", page.calls.last())
        advance(10_000)
        assertTrue(r.showsPlaying)
        r.release()
    }

    // --- §5.7: suspension; §5.8: errors ---

    @Test
    fun `a suspension destroys the web player whatever the state, and nothing starts while suspended`() = runBlocking {
        val r = playing()
        post(state(0, 8_000))
        r.suspended()
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(page.alive)
        assertEquals(listOf("open", "destroy"), page.log)
        assertEquals("the bookmark, written before it returns", Triple(0, 8_000L, true), ports.saves.last())
        assertFalse(r.showsPlaying)
        ports.mayPlay = false
        r.play()
        advance(1_000)
        assertEquals("no page, no call while suspended", 1, page.opens)
        assertEquals(1, api.plays().size)
        ports.mayPlay = true
        r.play()
        advance(0)
        assertEquals("a fresh start: a new page", 2, page.opens)
        assertEquals(8_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        r.release()
    }

    @Test
    fun `the page is destroyed 5 minutes after the last pause`() = runBlocking {
        val r = playing()
        r.pause()
        advance(4 * 60_000L)
        assertTrue(page.alive)
        advance(60_000L)
        assertFalse("the Connect device leaves the account's list", page.alive)
        r.play()
        advance(0)
        assertEquals("a resume after the device was dropped is a fresh start", 2, api.plays().size)
        r.release()
    }

    @Test
    fun `a confirmed null is ELSEWHERE with the bookmark kept, and a play takes it back`() = runBlocking {
        val r = playing()
        post(state(0, 30_000))
        post(Inactive)
        assertTrue("one null is looked at, not believed", r.showsPlaying)
        advance(2_000)
        assertEquals("vibb.state()", page.calls.last())
        post(Inactive)
        assertFalse(r.showsPlaying)
        assertEquals(RendererState.SPOTIFY_ELSEWHERE, r.rendererState)
        assertEquals("the bookmark of the last measured position", Triple(0, 30_000L, false), ports.saves.last())
        assertFalse("never a pause of the account's other player", page.calls.contains("vibb.pause()"))
        assertTrue("elsewhere stores no outcome", ports.outcomes.none { it != "ok" })
        r.play()
        advance(0)
        assertEquals(2, api.plays().size)
        assertEquals(30_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        assertNull("a start clears the line", r.rendererState)
        r.release()
    }

    @Test
    fun `three unplayable in a row stop - at most three play calls, the item errors on the way`() = runBlocking {
        val r = playing(count = 6)
        page.emit(HostEvent.Error("playback", "boom"))
        advance(0)
        assertEquals("the service hears the item error with that item current", 1, events.count { it == RendererEvent.Error("unplayable") })
        assertEquals("and moves on: one play from the next item", 2, api.plays().size)
        assertEquals(uri(1), windowOf(api.plays().last()).first())
        post(state(1, 0))
        page.emit(HostEvent.Error("playback", "boom"))
        advance(0)
        assertEquals(3, api.plays().size)
        post(state(2, 0))
        page.emit(HostEvent.Error("playback", "boom"))
        advance(1_000)
        assertEquals(3, events.count { it == RendererEvent.Error("unplayable") })
        assertEquals("the third stops: no fourth call", 3, api.plays().size)
        assertFalse(r.showsPlaying)
        assertEquals("vibb.pause()", page.calls.last())
        assertEquals("the move-on only moved where the next play starts", 3, r.currentIndex())
        r.release()
    }

    @Test
    fun `Spotify skipping a track of ours on evidence - the item error, and no call when Spotify already plays the next`() = runBlocking {
        val r = playing(count = 5)
        post(state(1, 0, prev = listOf(0))) // item 0 never played: Spotify skipped it, `prev` says so
        advance(5_000)
        post(state(2, 0, prev = listOf(0, 1))) // item 1 played 5 s: Spotify's advance
        assertEquals(2, r.currentIndex())
        assertEquals(1, events.count { it == RendererEvent.Error("unplayable") })
        assertEquals("the move-on found Spotify on it already", 1, api.plays().size)
        r.release()
    }

    @Test
    fun `the page's renderer gone - paused, stopped, the page destroyed`() = runBlocking {
        val r = playing()
        page.alive = false
        page.emit(HostEvent.Gone)
        settle()
        assertFalse(r.showsPlaying)
        assertEquals(RendererState.SPOTIFY_STOPPED, r.rendererState)
        assertEquals("an error that stops the renderer", Player.STATE_IDLE, r.spec().playbackState)
        r.play()
        advance(0)
        assertEquals("a new page", 2, page.opens)
        assertEquals(2, api.plays().size)
        r.release()
    }

    @Test
    fun `no Widevine and no Premium from the SDK`() = runBlocking {
        page.autoReady = null
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        settle()
        page.emit(HostEvent.Error("initialization", "no EME"))
        advance(0)
        assertEquals(RendererState.SPOTIFY_NOT_ON_PHONE, r.rendererState)
        assertEquals("no_widevine", ports.outcomes.last())
        assertFalse(page.alive)
        assertEquals(0, api.all().size)
        page.autoReady = DEVICE
        r.play()
        advance(0)
        post(state(0, 0))
        page.emit(HostEvent.Error("account", "no Premium"))
        settle()
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, r.rendererState)
        assertEquals("not_premium", ports.outcomes.last())
        assertFalse(r.showsPlaying)
        r.release()
    }

    @Test
    fun `the network gate refuses a start with its line`() = runBlocking {
        val r = renderer()
        ports.network = SpotifyLine.NEEDS_WIFI
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals(RendererState.SPOTIFY_NEEDS_WIFI, r.rendererState)
        assertFalse(r.showsPlaying)
        assertEquals("no page, no call", 0, page.opens + api.all().size)
        assertTrue("no Wi-Fi stores nothing", ports.outcomes.isEmpty())
        ports.network = SpotifyLine.NEEDS_INTERNET
        r.play()
        advance(0)
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, r.rendererState)
        assertEquals("unreachable", ports.outcomes.last())
        r.release()
    }

    // --- step 4's events ---

    @Test
    fun `a later uri of ours without evidence - paused, and our list goes on from the current item at its place (NewWindow resume)`() = runBlocking {
        val r = playing(count = 5)
        post(state(0, 10_000))
        post(state(3, 0)) // a later uri of ours, no `prev` evidence: not a skip
        assertTrue(page.calls.contains("vibb.pause()"))
        assertEquals("no item error", 0, events.count { it is RendererEvent.Error })
        advance(0)
        val plays = api.plays()
        assertEquals(2, plays.size)
        assertEquals((0 until 5).map { uri(it) }, windowOf(plays[1]))
        assertEquals("at its place (the measurement)", 10_000L, body(plays[1])["position_ms"]!!.jsonPrimitive.long)
        assertEquals(0, r.currentIndex())
        assertTrue(r.showsPlaying)
        r.release()
    }

    @Test
    fun `ShuffleOrRepeat - both turned off, then played again, and settings on a new device's first play`() = runBlocking {
        val r = playing()
        assertEquals(1, api.paths().count { it.contains("/shuffle") })
        post(state(0, 6_000))
        post(state(0, 7_000, shuffle = true))
        advance(0)
        val tail = api.paths().takeLast(3)
        assertEquals(
            listOf(
                "PUT /v1/me/player/shuffle?state=false&device_id=$DEVICE",
                "PUT /v1/me/player/repeat?state=off&device_id=$DEVICE",
                "PUT /v1/me/player/play?device_id=$DEVICE",
            ),
            tail,
        )
        assertEquals("from the current item at its measurement", 6_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        post(state(0, 6_000))
        // a new Connect device (the page made again): its first play turns them off again
        r.suspended()
        page.autoReady = "FAKEdevice2"
        r.play()
        advance(0)
        assertEquals(3, api.paths().count { it.contains("/shuffle") })
        r.release()
    }

    @Test
    fun `d49 - the start sends the wanted shuffle and repeat when they aren't known, a change or a differing state sets them again`() = runBlocking {
        val r = renderer()
        r.setModes(false, Repeat.ONE)
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals(
            "repeat one is Spotify's repeat track; list mode's shuffle is ours, Spotify's stays off",
            listOf(
                "PUT /v1/me/player/shuffle?state=false&device_id=$DEVICE",
                "PUT /v1/me/player/repeat?state=track&device_id=$DEVICE",
                "PUT /v1/me/player/play?device_id=$DEVICE",
            ),
            api.paths(),
        )
        post(state(0, 0, repeat = 2))
        r.pause()
        post(state(0, 1_000, paused = true, repeat = 2))
        r.play()
        advance(0)
        assertEquals("known and unchanged: the SDK's resume, nothing more", 1, api.paths().count { it.contains("/repeat") })
        post(state(0, 2_000, repeat = 2))
        // the kid's repeat off while it plays: set at once, a fresh start at the current place (never from 0:00)
        r.setModes(false, Repeat.OFF)
        advance(SPOTIFY_STEP_COALESCE_MS)
        assertEquals(
            listOf("PUT /v1/me/player/shuffle?state=false&device_id=$DEVICE", "PUT /v1/me/player/repeat?state=off&device_id=$DEVICE", "PUT /v1/me/player/play?device_id=$DEVICE"),
            api.paths().takeLast(3),
        )
        assertTrue(body(api.plays().last())["position_ms"]!!.jsonPrimitive.long >= 2_000L)
        post(state(0, 3_000))
        val calls = api.paths().size
        // off -> all in list mode is ours (the list again at its end): Spotify's side unchanged, nothing sent
        r.setModes(false, Repeat.ALL)
        advance(SPOTIFY_STEP_COALESCE_MS)
        assertEquals(calls, api.paths().size)
        // the account changed elsewhere (another app turned repeat on): corrected
        post(state(0, 4_000, repeat = 1))
        advance(0)
        assertEquals("PUT /v1/me/player/repeat?state=off&device_id=$DEVICE", api.paths().takeLast(2).first())
        r.release()
    }

    @Test
    fun `qa-music-a-code 2 - repeat all, the last track unplayable - the move-on's wrap to the first sends one play`() = runBlocking {
        val r = renderer(count = 3)
        r.setModes(false, Repeat.ALL)
        // the service's move-on with repeat all (moveOnIndex): past the last item to the first
        r.onEvent = { e ->
            events += e
            if (e is RendererEvent.Error) {
                r.seekTo((r.currentIndex() + 1) % r.itemCount(), 0)
                r.prepare()
                r.play()
            }
        }
        r.load(mediaItems(3), 2, 0, playNow = true)
        advance(0)
        post(state(2, 0))
        val plays = api.plays().size
        page.emit(HostEvent.Error("playback", "boom"))
        advance(0)
        assertEquals(0, r.currentIndex())
        assertEquals("a fresh start of the window from the first", plays + 1, api.plays().size)
        assertEquals(uri(0), windowOf(api.plays().last()).first())
        r.release()
    }

    @Test
    fun `qa-music-a-code 3 - after the account played elsewhere or another app's track, the next start sets the settings again`() = runBlocking {
        val r = playing()
        post(state(0, 1_000))
        val settings = api.paths().count { it.contains("/repeat") }
        post(Inactive)
        advance(SpotifyTracker.LOOK_MS)
        post(Inactive)
        advance(0)
        r.play()
        advance(0)
        assertEquals("elsewhere: shuffle and repeat before the play", settings + 1, api.paths().count { it.contains("/repeat") })
        assertEquals("PUT /v1/me/player/play?device_id=$DEVICE", api.paths().last())
        post(state(0, 2_000))
        post(SdkState(false, 0, 180_000, "spotify:track:FAKEforeign0000000001", null, emptyList(), emptyList(), "track", "Picked", listOf("Someone"), null))
        advance(0)
        r.play()
        advance(0)
        assertEquals("a foreign track: again", settings + 2, api.paths().count { it.contains("/repeat") })
        r.release()
    }

    @Test
    fun `d49 - a playlist context sets Spotify's own shuffle and repeat context`() = runBlocking {
        val r = contextRenderer()
        r.setModes(true, Repeat.ALL)
        r.load(contextItems(), 0, 0, playNow = true)
        advance(0)
        assertEquals(
            listOf("PUT /v1/me/player/shuffle?state=true&device_id=$DEVICE", "PUT /v1/me/player/repeat?state=context&device_id=$DEVICE"),
            api.paths().take(2),
        )
        r.release()
    }

    @Test
    fun `d49 - list mode's repeat all plays the list again from the first at its end - before Ended, as the natural advance`() = runBlocking {
        val r = renderer(count = 2)
        r.setModes(false, Repeat.ALL)
        r.load(mediaItems(2), 1, 0, playNow = true)
        advance(0)
        post(state(1, 0, duration = 20_000))
        post(state(1, 18_000, duration = 20_000))
        advance(3_000)
        events.clear()
        post(state(1, 0, paused = true, duration = 20_000))
        advance(0)
        assertFalse("no clear_state", events.contains(RendererEvent.Ended))
        assertTrue("the first item, as an automatic change", events.contains(RendererEvent.ItemChanged(0, auto = true)))
        assertEquals(0, r.currentIndex())
        assertEquals(listOf(uri(0), uri(1)), windowOf(api.plays().last()))
        assertEquals(0L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        r.release()
        // after the kid's pause nothing starts by itself: the same end, paused, sends no play
        val calls = api.plays().size
        val p = renderer(count = 2)
        p.setModes(false, Repeat.ALL)
        p.load(mediaItems(2), 1, 0, playNow = true)
        advance(0)
        post(state(1, 0, duration = 20_000))
        post(state(1, 18_000, duration = 20_000))
        p.pause()
        advance(3_000)
        post(state(1, 0, paused = true, duration = 20_000))
        advance(0)
        assertEquals("one play: the start", calls + 1, api.plays().size)
        p.release()
    }

    @Test
    fun `a 401 the renewal can't cure (the same token back) is the refused state, not trenger internett`() = runBlocking {
        sameToken = true
        api.answer = { SpotifyResponse(401, null, ByteArray(0)) }
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, r.rendererState)
        assertEquals("refused", ports.outcomes.last())
        assertFalse(r.showsPlaying)
        assertFalse(ports.locks)
        r.release()
    }

    @Test
    fun `another account's token clears the web player before it plays`() = runBlocking {
        tokenLife = 6 * 60_000L // a new ask at the next start
        val r = playing()
        r.pause()
        advance(5 * 60_000L)
        assertEquals("idle: the page went", listOf("open", "destroy"), page.log)
        account = "3:1"
        r.play()
        advance(0)
        assertEquals("the old account's cookies and storage cleared before the new page", listOf("open", "destroy", "clear", "open"), page.log)
        assertEquals("3:1", ports.marker)
        assertTrue(page.alive)
        assertEquals(2, api.plays().size)
        // and the SDK's own ask that brings another account clears the live page at once
        account = "4:1"
        tokenLife = 60 * 60_000L
        r.tokens.invalidate()
        page.emit(HostEvent.NeedToken(3))
        settle()
        assertEquals(listOf("open", "destroy", "clear", "open", "destroy", "clear"), page.log)
        assertFalse("no token of another account reaches the old page", page.calls.any { it.startsWith("vibb.token(3,") })
        assertNull("the marker goes with the page", ports.marker)
        assertFalse(r.showsPlaying)
        r.release()
    }

    @Test
    fun `the token is refreshed ahead of expiry while playing, under the wake lock`() = runBlocking {
        tokenLife = 15 * 60_000L
        val r = playing()
        assertEquals(1, asks)
        // keep the page posting, as it does every 5 s while playing
        repeat(70) {
            advance(5_000)
            post(state(0, (it + 1) * 5_000L))
        }
        assertTrue("a refresh with 10 minutes left", asks >= 2)
        assertTrue(ports.locks)
        page.emit(HostEvent.NeedToken(7))
        settle()
        assertEquals("the SDK's ask answered from memory", "vibb.token(7,\"FAKE-ACCESS-$asks\")", page.calls.last())
        r.release()
    }

    @Test
    fun `release destroys the page and lets everything go`() = runBlocking {
        val r = playing()
        r.release()
        assertFalse(page.alive)
        assertFalse(ports.locks)
        assertNull(ports.noisy)
    }

    // --- the code QA's fix round (qa-26c-step5a-code) ---

    @Test
    fun `H1 - shuffle switched on elsewhere while paused starts nothing, and the next play turns it off`() = runBlocking {
        val r = playing()
        post(state(0, 20_000))
        r.pause()
        post(state(0, 21_000, paused = true))
        advance(60_000)
        post(state(0, 21_000, paused = true, shuffle = true))
        advance(2_000)
        assertFalse("nothing starts by itself", r.showsPlaying)
        assertEquals("no call", 1, api.plays().size)
        assertFalse(ports.locks)
        r.play()
        advance(0)
        assertEquals("the kid's play turns them off first", 2, api.paths().count { it.contains("/shuffle") })
        assertEquals(21_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        r.release()
    }

    @Test
    fun `H1 - a pause behind a replaced step stays a pause - the play that went out is stopped`() = runBlocking {
        val r = playing()
        api.gate = CountDownLatch(1)
        r.seekTo(3, 0)
        advance(300) // the step's call is on its way
        r.seekTo(1, 0)
        advance(300) // a pick: queued behind it
        r.pause()
        val pauses = page.calls.count { it == "vibb.pause()" }
        events.clear()
        api.gate!!.countDown()
        settle()
        assertEquals("the replaced call's play is paused when its 204 comes", pauses + 1, page.calls.count { it == "vibb.pause()" })
        assertEquals("vibb.pause()", page.calls.last())
        post(state(3, 0, prev = listOf(0))) // Spotify plays what the replaced call sent
        advance(1_000)
        assertEquals(2, api.plays().size)
        assertFalse(r.showsPlaying)
        assertFalse("the kid's pause is never undone", events.any { it == RendererEvent.Resumed || it == RendererEvent.IsPlaying(true) })
        assertEquals("vibb.pause()", page.calls.last())
        r.release()
    }

    @Test
    fun `H1 - every play the renderer starts from a tracker event needs the kid's intent`() {
        val text = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt").readText()
        val apply = text.substringAfter("private fun apply(events: List<Event>) {").substringBefore("\n    }\n")
        assertTrue(apply.contains("val wanted = pwr\n"))
        val requests = apply.lines().filter { it.contains("request(") }
        assertEquals(2, requests.size)
        assertTrue(requests.joinToString("\n"), requests.all { it.contains("if (wanted && ports.mayPlay()) request(") })
    }

    @Test
    fun `M1 - a process's first page clears another account's storage before it opens`() = runBlocking {
        ports.marker = "2:1"
        account = "3:1"
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals("cleared before the first page", listOf("clear", "open"), page.log)
        assertEquals("3:1", ports.marker)
        assertEquals("vibb.token(1,\"FAKE-ACCESS-1\")", page.calls.first { it.startsWith("vibb.token(") })
        assertEquals(1, api.plays().size)
        r.release()
    }

    @Test
    fun `M1 - the same account's page keeps its storage, and no marker clears once`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals(listOf("open"), page.log)
        r.release()
        ports.marker = null
        page.log.clear()
        val again = renderer()
        again.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        assertEquals("an upgrade or a crash before the write: cleared", listOf("clear", "open"), page.log)
        assertEquals("2:1", ports.marker)
        again.release()
    }

    @Test
    fun `M3 - Spotify's own advance landing in a start is no skip - held through the call or not`() = runBlocking {
        for (held in listOf(true, false)) {
            events.clear()
            val r = playing(count = 6, duration = 20_000)
            for (n in 1..3) {
                advance(5_000)
                post(state(n, 0, prev = (0 until n).toList().takeLast(3), duration = 20_000))
            }
            post(state(3, 19_000, prev = listOf(0, 1, 2), duration = 20_000))
            val before = api.plays().size
            if (held) api.gate = CountDownLatch(1)
            r.seekTo(1, 0) // the kid picks track 1 in the last second of track 3
            advance(300)
            post(state(4, 0, prev = listOf(1, 2, 3), duration = 20_000)) // the old play's own advance
            api.gate?.countDown()
            api.gate = null
            settle()
            assertEquals("held=$held: no item error", 0, events.count { it is RendererEvent.Error })
            assertEquals("held=$held: one play for the pick", before + 1, api.plays().size)
            post(state(1, 0, prev = listOf(0), duration = 20_000))
            assertEquals(1, r.currentIndex())
            assertEquals(Player.STATE_READY, r.spec().playbackState)
            assertTrue(r.showsPlaying)
            r.release()
        }
    }

    @Test
    fun `L3 - reauthorize at the SDK's own token ask - ask a grown-up, and the word reauthorize`() = runBlocking {
        val r = playing()
        refused = TokenResult.Reauthorize
        r.tokens.invalidate()
        page.emit(HostEvent.NeedToken(9))
        settle()
        assertEquals("vibb.token(9,null)", page.calls.last { it.startsWith("vibb.token(") })
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, r.rendererState)
        assertEquals("reauthorize", ports.outcomes.last())
        assertFalse(r.showsPlaying)
        r.release()
    }

    @Test
    fun `L3 - the SDK's authentication error is read through the last token answer`() = runBlocking {
        val r = playing()
        refused = TokenResult.Unreachable
        r.tokens.invalidate()
        page.emit(HostEvent.NeedToken(9))
        settle()
        assertTrue("an unreachable answer alone isn't the line yet", r.showsPlaying)
        page.emit(HostEvent.Error("authentication", "expired"))
        settle()
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, r.rendererState)
        assertEquals("unreachable", ports.outcomes.last())
        r.release()
    }

    @Test
    fun `L4 - the page gone while the play call runs - stopped, not the call's trenger internett`() = runBlocking {
        api.gate = CountDownLatch(1)
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        api.answer = { SpotifyResponse(502, null, ByteArray(0)) }
        page.alive = false
        page.emit(HostEvent.Gone)
        api.gate!!.countDown()
        settle()
        assertEquals(RendererState.SPOTIFY_STOPPED, r.rendererState)
        assertEquals(Player.STATE_IDLE, r.spec().playbackState)
        r.release()
    }

    @Test
    fun `deviation 7 - our first state before the 204 is held and read after played - ours, not paused`() = runBlocking {
        api.gate = CountDownLatch(1)
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        post(state(0, 0)) // our new play's first state beats the call's answer
        api.gate!!.countDown()
        settle()
        assertFalse("never paused as foreign", page.calls.contains("vibb.pause()"))
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        assertTrue(events.contains(RendererEvent.Ready))
        r.release()
    }

    @Test
    fun `deviation 7 - the old play's news during a step is ignored`() = runBlocking {
        val r = playing()
        r.seekTo(3, 0)
        post(state(1, 0, prev = listOf(0))) // the old play moves on (and would read as a skip of 0)
        assertEquals(3, r.currentIndex())
        assertEquals(0, events.count { it is RendererEvent.Error })
        assertFalse(events.contains(RendererEvent.ItemChanged(1, auto = true)))
        advance(300)
        assertEquals(uri(3), windowOf(api.plays().last()).first())
        r.release()
    }

    // --- step 5b: what the service relies on ---

    @Test
    fun `5b - the service's network gate refused - loaded paused with the line, nothing sent, nothing moves`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 2, 30_000, playNow = false)
        settle()
        r.refused(SpotifyLine.NEEDS_INTERNET)
        settle()
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, r.rendererState)
        assertEquals("paused and ready, never idle: a tap tries again", Player.STATE_READY, r.spec().playbackState)
        assertFalse(r.showsPlaying)
        assertEquals("the item stays (never another index)", 2, r.currentIndex())
        assertEquals(listOf("unreachable"), ports.outcomes)
        assertEquals("no page, no call, no token", 0, page.opens + api.all().size + asks)
        r.refused(SpotifyLine.NEEDS_WIFI)
        settle()
        assertEquals(RendererState.SPOTIFY_NEEDS_WIFI, r.rendererState)
        assertEquals("no Wi-Fi leaves the word unchanged (§5.8)", listOf("unreachable"), ports.outcomes)
        // the network back: the kid's play starts at the place
        r.play()
        advance(0)
        post(state(2, 30_000))
        assertNull(r.rendererState)
        assertEquals(30_000L, body(api.plays().single())["position_ms"]!!.jsonPrimitive.long)
        r.release()
    }

    @Test
    fun `5b - the gate refusing Spotify's own advance pauses our track on the page`() = runBlocking {
        val r = playing()
        advance(4_000)
        post(state(1, 0, prev = listOf(0)))
        assertEquals(1, r.currentIndex())
        r.refused(SpotifyLine.NEEDS_WIFI) // the service's onItemChanged: roaming, or mobile data not allowed
        settle()
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        assertEquals(RendererState.SPOTIFY_NEEDS_WIFI, r.rendererState)
        assertEquals("never moved on", 1, r.currentIndex())
        assertEquals(1, api.plays().size)
        r.release()
    }

    @Test
    fun `5b - detached, its queue is null - the web player's late events never write another entry's place`() = runBlocking {
        val r = playing()
        advance(3_000)
        post(state(0, 3_000))
        r.saveNow()
        assertEquals(1, ports.saves.size)
        val ours = active
        active = null // the service's `active.takeIf { renderer === spotify }` once another renderer plays
        r.pause()
        r.saveNow(force = true)
        r.suspended()
        assertEquals("nothing written while it doesn't render", 1, ports.saves.size)
        active = ours
        r.release()
    }

    /** The service's `attach()` of another renderer over the web player (qa-26c-step5b-code M1), as PlaybackService does it. */
    private fun detach(r: SpotifyRenderer) {
        r.saveNow(force = true)
        r.pause()
        r.cancelFade()
        r.onEvent = null
        r.detached()
        active = null // `active.takeIf { renderer === spotify }`
    }

    @Test
    fun `M1 - a detached web player resumed by the account's other app is paused at once - no locks, no play`() = runBlocking {
        // the QA's counter-example (qa-26c-step5b-code M1), with the service's detach
        val r = playing()
        post(state(0, 20_000))
        detach(r)
        post(state(0, 20_000, paused = true))
        advance(60_000)
        post(state(0, 20_500)) // the other app's play on "Vibb"
        assertEquals(listOf(false, false, "vibb.pause()"), listOf(ports.locks, r.showsPlaying, page.calls.last()))
        r.release()
    }

    @Test
    fun `M1 - the detach sequence - a transfer-in and our own track paused, nothing written, then a load plays again`() = runBlocking {
        val r = playing()
        advance(4_000)
        post(state(0, 4_000))
        val saves = ports.saves.size
        val plays = api.plays().size
        detach(r)
        assertFalse(r.showsPlaying || ports.locks)
        // a foreign transfer, then our own track resumed from outside: each paused at once
        val pausesBefore = page.calls.count { it == "vibb.pause()" }
        post(SdkState(false, 1_000, 30_000, "spotify:track:FAKEforeign0000000001", null, emptyList(), emptyList(), "track", "Another", listOf("Someone"), null))
        post(state(0, 5_000))
        assertEquals(2, page.calls.count { it == "vibb.pause()" } - pausesBefore)
        assertFalse(r.showsPlaying || ports.locks)
        r.play() // nothing the service would do - a belt: no start while detached
        settle()
        assertEquals("no play call while detached", plays, api.plays().size)
        r.saveNow(force = true)
        assertEquals("no place written", saves + 1, ports.saves.size) // detach's own forced save only
        // re-attached for a new Spotify queue: load clears the detach, the play goes out
        renderer2(r)
        r.load(mediaItems(5), 2, 0, playNow = true)
        advance(0)
        post(state(2, 0))
        assertTrue(r.showsPlaying && ports.locks)
        assertEquals(plays + 1, api.plays().size)
        r.release()
    }

    /** The service's `active = a` for a re-attach. */
    private fun renderer2(r: SpotifyRenderer) {
        val entry = Entry(6, "e6", "Kveldsliste", null, Source.SPOTIFY, "spotify:playlist:FAKEplaylist0000000001", "auto", 0, true, null, "11ee", 6, spotifyMode = "list")
        val items = (0 until 5).map { PlayItem(6, key(it), "Fake track $it — Fake Artist", null, false, DURATION) }
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        r.onEvent = { events += it }
    }

    @Test
    fun `L4 - forget clears the page, the marker and the token, and stores none`() = runBlocking {
        val r = playing()
        r.forget()
        settle()
        assertEquals("none", ports.outcomes.last())
        assertNull(ports.marker)
        assertFalse(page.alive)
        assertTrue(page.clears >= 1)
        assertFalse(r.showsPlaying)
        r.release()
    }

    // --- context mode (design 26c §5.9, step 6) ---------------------------------------------------------------

    private val playlistContext = "spotify:playlist:FAKEplaylist0000000001"
    private val artistContext = "spotify:artist:FAKEartist000000000001"

    /** A context-mode entry (7) on the renderer: its one synthetic item, as the snapshot makes it. */
    private fun CoroutineScope.contextRenderer(target: String = playlistContext): SpotifyRenderer {
        val r = renderer()
        val entry = Entry(7, "e7", "Fake Artist", null, Source.SPOTIFY, target, "auto", 0, true, null, "22dd", 7, spotifyMode = "context")
        val items = listOf(me.vibb.music.logic.contextItem(entry, ports.contexts["e7"]))
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        return r
    }

    private fun contextItems() = listOf(
        MediaItem.Builder().setMediaId("7/context")
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(active!!.items[0].title).build()).build(),
    )

    private fun cstate(n: Int, pos: Long, context: String? = playlistContext, paused: Boolean = false, duration: Long = 20_000): SdkSnapshot = SdkState(
        paused, pos, duration, uri(n), null, listOf(uri(n + 1)), emptyList(), "track", "Fake track $n", listOf("Fake Artist"), context,
    )

    private fun title(r: SpotifyRenderer) = r.itemAt(0)!!.mediaMetadata.title.toString()

    @Test
    fun `context - a playlist starts with one PUT of the context from its live track at its place, and every track of it is ours`() = runBlocking {
        ports.contexts["e7"] = SpotifyContextTrack(uri(3), "Fake track 3 — Fake Artist")
        val r = contextRenderer()
        r.load(contextItems(), 0, 42_000, playNow = true)
        assertEquals("a restart's item shows the live track at once", "Fake track 3 — Fake Artist", title(r))
        assertEquals(1, r.itemCount())
        advance(0)
        val play = api.plays().single()
        assertEquals(
            """{"context_uri":"$playlistContext","offset":{"uri":"${uri(3)}"},"position_ms":42000}""",
            play.body,
        )
        post(cstate(3, 42_000))
        assertTrue(r.showsPlaying)
        assertEquals(Player.STATE_READY, r.spec().playbackState)
        assertEquals("ok", ports.outcomes.last())
        // Spotify's own advance in the context: ours - the title swapped in place, the live track stored, the place moved
        advance(5_000)
        post(cstate(4, 0))
        assertEquals("Fake track 4 — Fake Artist", title(r))
        assertEquals(SpotifyContextTrack(uri(4), "Fake track 4 — Fake Artist"), ports.contexts["e7"])
        assertEquals(Triple(0, 0L, false), ports.saves.last())
        assertTrue("a context's place is saved with no duration: never done", ports.savedDurations.all { it == null })
        assertEquals("one item: the index never moves", 0, r.currentIndex())
        assertEquals("nothing sent for Spotify's advance", 1, api.plays().size)
        assertEquals(20_000L, r.durationMs())
        // the service's art swap keeps the live title
        r.replaceItem(0, contextItems()[0])
        assertEquals("Fake track 4 — Fake Artist", title(r))
        // next and previous are the SDK's - no Web API call
        r.step(forward = true)
        assertEquals("vibb.next()", page.calls.last())
        r.step(forward = false)
        assertEquals("vibb.previous()", page.calls.last())
        assertEquals(1, api.plays().size)
        // a resume after a pause is the SDK's too, and a fresh play goes on from the live track
        r.pause()
        post(cstate(4, 3_000, paused = true))
        r.play()
        assertEquals("vibb.resume()", page.calls.last())
        r.release()
    }

    @Test
    fun `context - another context or a uri list on the player is not ours, paused at once, nothing stored`() = runBlocking {
        val r = contextRenderer()
        r.load(contextItems(), 0, 0, playNow = true)
        advance(0)
        assertEquals("no stored track: Spotify's start", """{"context_uri":"$playlistContext"}""", api.plays().single().body)
        post(cstate(1, 0))
        assertTrue(r.showsPlaying)
        val stored = ports.contexts["e7"]
        post(cstate(9, 0, context = "spotify:playlist:FAKEforeignList000001"))
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        assertEquals("nothing of it stored", stored, ports.contexts["e7"])
        assertEquals("Fake track 1 — Fake Artist", title(r))
        post(cstate(9, 500, context = null)) // still playing (a uri list from another app)
        assertEquals("vibb.pause()", page.calls.last())
        r.release()
    }

    @Test
    fun `context - Spotify moving on on a network the gate now refuses is paused with the line`() = runBlocking {
        val r = contextRenderer()
        r.load(contextItems(), 0, 0, playNow = true)
        advance(0)
        post(cstate(1, 0))
        assertTrue(r.showsPlaying)
        ports.network = SpotifyLine.NEEDS_WIFI // the streaming switch went off on mobile data
        post(cstate(2, 0))
        assertEquals("the live track is still stored", uri(2), ports.contexts["e7"]!!.uri)
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        assertEquals(RendererState.SPOTIFY_NEEDS_WIFI, r.rendererState)
        assertEquals(1, api.plays().size)
        r.release()
    }

    @Test
    fun `context - an artist plays from Spotify's start, its end is Ended, and nothing plays after it`() = runBlocking {
        val r = contextRenderer(artistContext)
        r.load(contextItems(), 0, 50_000, playNow = false)
        assertEquals("an artist has no resume (§12): its place shows 0", 0L, r.positionMs())
        r.play()
        advance(0)
        assertEquals("""{"context_uri":"$artistContext"}""", api.plays().single().body)
        post(cstate(1, 0, artistContext))
        advance(18_000)
        post(cstate(1, 18_000, artistContext))
        advance(2_500)
        post(cstate(1, 0, artistContext, paused = true))
        assertTrue(events.contains(RendererEvent.Ended))
        assertEquals(Player.STATE_ENDED, r.spec().playbackState)
        assertFalse(r.showsPlaying)
        post(cstate(2, 0, artistContext)) // anything after the end
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        r.release()
    }

    @Test
    fun `context - nothing starts by itself - a restore opens nothing, a step without our context sends nothing, a detached player pauses`() = runBlocking {
        ports.contexts["e7"] = SpotifyContextTrack(uri(2), "Fake track 2 — Fake Artist")
        val r = contextRenderer()
        r.load(contextItems(), 0, 42_000, playNow = false)
        settle()
        assertEquals("no page", 0, page.opens)
        assertEquals("no request", 0, api.all().size)
        assertEquals("Fake track 2 — Fake Artist", title(r))
        r.step(forward = true)
        settle()
        assertTrue("no context of ours on the player: nothing sent", page.calls.isEmpty())
        assertFalse(r.showsPlaying)
        // played, then paused: a step through the SDK that Spotify plays is paused at once (qa-26c-step6-code L1), a time
        // rule or not
        r.play()
        advance(0)
        post(cstate(2, 42_000))
        r.pause()
        post(cstate(2, 43_000, paused = true))
        ports.mayPlay = false // a time rule
        r.step(forward = true)
        assertEquals("vibb.next()", page.calls.last())
        post(cstate(3, 0))
        assertEquals("Spotify played it: paused at once", "vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        ports.mayPlay = true
        // the service attaches another renderer: from outside, our context is paused at once
        detach(r)
        post(cstate(3, 1_000))
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying || ports.locks)
        r.step(forward = true)
        assertEquals("a detached player takes no step", "vibb.pause()", page.calls.last())
        r.release()
    }

    @Test
    fun `the debug recorder gets every posted state as received and our calls by name - never a log line`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 0, 0, playNow = true)
        advance(0)
        val json = """{"paused":false,"position":0,"duration":200000,"uri":"${uri(0)}","linked":null,"type":"track","name":"Fake track 0",""" +
            """"artists":["Fake Artist"],"next":[],"prev":[],"context":null,"shuffle":false,"repeat":0}"""
        page.emit(HostEvent.State(SdkState.parse(json)!!, json))
        settle()
        r.pause()
        page.emit(HostEvent.Error("playback", "the SDK's own text"))
        settle()
        assertEquals(
            "a playback error on a paused track only moves the index: nothing starts by itself",
            listOf("ready", "play from 0 to 4 at 0 ms", "state $json", "pause", "error playback"),
            ports.recorded,
        )
        assertEquals(1, api.plays().size)
        assertFalse(r.showsPlaying)
        assertTrue("no state, no uri in the log", ports.log.none { it.contains("{") || it.contains("spotify:track") })
        assertFalse("never the SDK's text in the recorder", ports.recorded.any { it.contains("own text") })
        assertEquals("the event's text leaves out the JSON", "State(${SdkState.parse(json)})", HostEvent.State(SdkState.parse(json)!!, json).toString())
        r.release()
    }

    // --- qa-26c-step6-code's counter-examples (M3, L1), red on 74c4942, and the fix round's renderer cases ----------

    @Test
    fun `QA M3 - a stored track that left the playlist - the dead offset dropped, the context from the top with one more play`() = runBlocking {
        ports.contexts["e7"] = SpotifyContextTrack(uri(3), "Fake track 3 — Fake Artist")
        val r = contextRenderer()
        api.answer = { req ->
            if (req.url.encodedPath == "/v1/me/player/play" && req.body!!.contains("offset")) SpotifyResponse(404, null, ByteArray(0)) else SpotifyResponse(204, null, ByteArray(0))
        }
        r.load(contextItems(), 0, 42_000, playNow = true); advance(0); advance(5_000)
        r.play(); advance(0); advance(5_000)
        assertTrue(api.plays().any { !it.body!!.contains("offset") })
        assertEquals(
            "the offset twice (a 404, the transfer, the 404 again), then once from the top - never a loop",
            listOf(true, true, false), api.plays().map { it.body!!.contains("offset") },
        )
        assertEquals("""{"context_uri":"$playlistContext"}""", api.plays().last().body)
        assertNull("the dead offset is dropped on disk: no later tap repeats it", ports.contexts["e7"])
        assertTrue("it plays (the start's intent), never trenger internett", r.showsPlaying)
        assertNull(r.rendererState)
        post(cstate(1, 0))
        assertEquals(SpotifyContextTrack(uri(1), "Fake track 1 — Fake Artist"), ports.contexts["e7"])
        r.pause()
        r.play() // a later tap: our track, the SDK's resume
        advance(0)
        assertEquals(3, api.plays().size)
        r.release()
    }

    @Test
    fun `M3 - a context Spotify won't play for the account (a 404 after the transfer, no offset) asks a grown-up on its tile, no stored word`() = runBlocking {
        val r = contextRenderer()
        api.answer = { req -> if (req.url.encodedPath == "/v1/me/player/play") SpotifyResponse(404, null, ByteArray(0)) else SpotifyResponse(204, null, ByteArray(0)) }
        r.load(contextItems(), 0, 0, playNow = true); advance(0); advance(5_000)
        assertEquals("the play, the transfer, the play again - nothing more", 2, api.plays().size)
        assertEquals(RendererState.SPOTIFY_ASK_ADULT, r.rendererState)
        assertFalse(r.showsPlaying)
        assertFalse("no account word: one playlist, not the account", ports.outcomes.any { it == "refused" || it == "unreachable" })
        r.release()
    }

    @Test
    fun `QA L1 - a step while a context is paused only moves - Spotify playing it is paused at once`() = runBlocking {
        val r = contextRenderer()
        r.load(contextItems(), 0, 0, playNow = true); advance(0)
        post(cstate(1, 0))
        r.pause()
        post(cstate(1, 2_000, paused = true))
        r.step(forward = true)
        assertEquals("vibb.next()", page.calls.last())
        post(cstate(2, 0))
        assertFalse(r.showsPlaying)
        assertEquals("vibb.pause()", page.calls.last())
        assertEquals("the title still moves", "Fake track 2 — Fake Artist", title(r))
        assertEquals(uri(2), ports.contexts["e7"]!!.uri)
        // a step while playing goes on playing (Spotify's next), and the kid's play after a paused step plays
        r.play(); advance(0)
        post(cstate(2, 0))
        assertTrue(r.showsPlaying)
        r.step(forward = true)
        post(cstate(3, 0))
        assertTrue(r.showsPlaying)
        r.release()
    }

    @Test
    fun `QA M1 - our list's leftover add in a context is skipped once while the kid wants it playing, else paused - never the end`() = runBlocking {
        // a 60-track list played past its window: Spotify's queue holds item 50 (our add one ahead)
        val l = playing(count = 60)
        for (n in 1..49) {
            advance(4_000)
            post(state(n, 0, prev = listOf(n - 1)))
        }
        assertEquals(listOf(uri(50)), api.all().filter { it.url.encodedPath == "/v1/me/player/queue" }.map { it.url.queryParameter("uri") })
        val window = api.plays().size
        // switch the same renderer to a context (the service's load)
        val entry = Entry(7, "e7", "Fake Artist", null, Source.SPOTIFY, playlistContext, "auto", 0, true, null, "22dd", 7, spotifyMode = "context")
        val items = listOf(me.vibb.music.logic.contextItem(entry))
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        l.load(contextItems(), 0, 0, playNow = true); advance(0)
        assertEquals(window + 1, api.plays().size)
        post(cstate(7, 0)); advance(15_000); post(cstate(7, 15_000)); advance(5_000)
        post(cstate(50, 0)) // Spotify plays its queue first: our add, right after our track ran out
        assertEquals("skipped: the context goes on", "vibb.next()", page.calls.last())
        assertTrue("no end, still playing", l.showsPlaying && !events.contains(RendererEvent.Ended))
        val calls = page.calls.size
        post(cstate(50, 500))
        assertEquals("one skip only", calls, page.calls.size)
        post(cstate(8, 0))
        assertEquals("Fake track 8 — Fake Artist", title(l))
        assertTrue(l.showsPlaying)
        l.release()
    }

    @Test
    fun `M1 - our leftover add playing while the kid has the context paused is paused at once - no skip, no end`() = runBlocking {
        val l = playing(count = 60)
        for (n in 1..49) {
            advance(4_000)
            post(state(n, 0, prev = listOf(n - 1)))
        }
        val entry = Entry(7, "e7", "Fake Artist", null, Source.SPOTIFY, playlistContext, "auto", 0, true, null, "22dd", 7, spotifyMode = "context")
        val items = listOf(me.vibb.music.logic.contextItem(entry))
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        l.load(contextItems(), 0, 0, playNow = true); advance(0)
        post(cstate(7, 0))
        l.pause()
        post(cstate(7, 3_000, paused = true))
        post(cstate(50, 0)) // the account's other controls play on "Vibb": Spotify's queue first, our add
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(page.calls.contains("vibb.next()"))
        assertFalse(l.showsPlaying || events.contains(RendererEvent.Ended))
        l.release()
    }

    @Test
    fun `step 6 deviation 13 - a playback error on a paused list track only moves the index - no PUT`() = runBlocking {
        val r = playing()
        r.pause()
        post(state(0, 1_000, paused = true))
        val plays = api.plays().size
        page.emit(HostEvent.Error("playback", "x"))
        settle()
        advance(1_000)
        assertEquals("the service's move-on moved the index", 1, r.currentIndex())
        assertEquals("no play call", plays, api.plays().size)
        assertFalse(r.showsPlaying)
        r.release()
    }

    // --- the emulator check §6l A's fixes (2026-10-14) ---

    @Test
    fun `F1 - the session's player through Media3's Player API - play, pause, seek, a pick and stop reach the renderer`() = runBlocking {
        val r = renderer(session = true)
        r.load(mediaItems(5), 0, 0, playNow = false)
        val player = r.player
        assertFalse(player.playWhenReady)
        player.play() // the play/pause button, a headset's MEDIA_PLAY through the session (once a StackOverflowError)
        advance(0)
        post(state(0, 0))
        assertEquals(1, api.plays().size)
        assertTrue(player.playWhenReady)
        assertTrue(r.showsPlaying)
        player.seekTo(30_000) // the seek bar: the SDK's seek, no Web API call
        settle()
        assertEquals("vibb.seek(30000)", page.calls.last())
        player.pause()
        settle()
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(player.playWhenReady)
        post(state(0, 30_000, paused = true))
        player.play()
        settle()
        assertEquals("vibb.resume()", page.calls.last())
        player.seekTo(2, 0) // a pick: the renderer's step
        advance(300)
        assertEquals(2, r.currentIndex())
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(2, api.plays().size)
        player.stop() // the session's stop is a pause
        settle()
        assertFalse(r.showsPlaying)
        assertEquals("vibb.pause()", page.calls.last())
        assertEquals(2, api.plays().size)
        r.release()
    }

    @Test
    fun `F1 - a headset's play after a restart - a stored queue loaded paused starts through the session's player`() = runBlocking {
        val r = renderer(session = true)
        r.load(mediaItems(5), 3, 42_000, playNow = false) // restoreThen: no page, no network
        assertEquals(0, page.opens)
        r.player.play() // MediaSession's MEDIA_PLAY -> KidPlayer -> this player
        advance(0)
        assertEquals(1, page.opens)
        assertEquals(42_000L, body(api.plays().single())["position_ms"]!!.jsonPrimitive.long)
        assertEquals(uri(3), windowOf(api.plays().single()).first())
        r.release()
    }

    @Test
    fun `F1 - the session's player calls the renderer, never its own SimpleBasePlayer members`() {
        val text = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt").readText()
        val inner = text.substringAfter("private inner class SpotifyPlayer(").substringBefore("\n    }\n")
        // every call by name is qualified: the renderer's (`this@SpotifyRenderer.`, or `r.`, which is it)
        for (name in listOf("play", "pause", "seekTo", "stop", "prepare", "release", "durationMs", "positionMs", "spec")) {
            val bare = Regex("""(?<![.\w@])$name\(""").findAll(inner).map { it.value }.toList()
            assertTrue("unqualified $name( in SpotifyPlayer: $bare", bare.isEmpty())
        }
        assertTrue(inner.contains("this@SpotifyRenderer.play() else this@SpotifyRenderer.pause()"))
        assertTrue(inner.contains("handling { this@SpotifyRenderer.pause() }"))
    }

    @Test
    fun `F3 - offline, a step says the line and stays - paused or playing, the gate before the move`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 1, 0, playNow = false)
        ports.network = SpotifyLine.NEEDS_INTERNET
        r.seekTo(2, 0)
        advance(1_000)
        assertEquals("the item stays", 1, r.currentIndex())
        assertEquals(RendererState.SPOTIFY_NEEDS_INTERNET, r.rendererState)
        assertTrue("the fake hears nothing", api.all().isEmpty())
        r.seekTo(0, 0)
        advance(1_000)
        assertEquals("previous neither", 1, r.currentIndex())
        // playing, then the network goes: the step pauses with the line, the item stays
        ports.network = null
        r.play()
        advance(0)
        post(state(1, 0))
        assertTrue(r.showsPlaying)
        ports.network = SpotifyLine.NEEDS_WIFI
        r.seekTo(2, 0)
        advance(1_000)
        assertEquals(1, r.currentIndex())
        assertFalse(r.showsPlaying)
        assertEquals(RendererState.SPOTIFY_NEEDS_WIFI, r.rendererState)
        assertEquals(1, api.plays().size)
        // the network back: a step moves again
        ports.network = null
        r.seekTo(2, 0)
        advance(0)
        assertEquals(2, r.currentIndex())
        r.release()
    }

    // --- the code QA's fix round (qa-26c-6l-fixes-code) ---

    /** The service's call rule as `PlaybackService.callStep` runs it, against this renderer (QA M1). */
    private var call = me.vibb.music.logic.CallPause()

    private fun callStep(r: SpotifyRenderer, inCall: Boolean) {
        r.inCall = inCall
        val playing = r.showsPlaying || r.heldRecently()
        val paused = me.vibb.music.playback.pausedForCall(playing, r.itemCount(), r.rendererState)
        val (next, action) = me.vibb.music.logic.callPauseStep(call, inCall, playing, paused, mayPlay = true)
        call = next
        when (action) {
            me.vibb.music.logic.CallAction.PAUSE -> r.pause()
            me.vibb.music.logic.CallAction.PLAY -> r.play()
            me.vibb.music.logic.CallAction.NONE -> Unit
        }
    }

    @Test
    fun `H1 - the kid's pause while an alarm holds the page paused sticks - Chromium's resume is paused at once`() = runBlocking {
        val r = playing()
        post(state(0, 5_000))
        post(state(0, 6_000, paused = true)) // the alarm: Chromium suspends the page's audio
        assertTrue(r.heldByPage)
        r.pause() // the kid's pause during the ring
        settle()
        assertFalse(r.heldByPage)
        val pauses = page.calls.count { it == "vibb.pause()" }
        events.clear()
        post(state(0, 6_000)) // Dismiss: Chromium resumes what it suspended
        assertEquals("paused again at once", pauses + 1, page.calls.count { it == "vibb.pause()" })
        assertFalse("never shown playing", r.showsPlaying)
        assertFalse(events.contains(RendererEvent.Resumed))
        assertFalse(ports.locks)
        post(state(0, 6_000, paused = true))
        r.play() // the kid's own play goes on as ever
        settle()
        assertEquals("vibb.resume()", page.calls.last())
        assertTrue(r.showsPlaying)
        r.release()
    }

    @Test
    fun `H1 - the sleep timer ending during the ring stays paused after it`() = runBlocking {
        val r = playing()
        post(state(0, 5_000))
        r.fadeOutAndPause {}
        advance(8_000)
        post(state(0, 13_000, paused = true)) // the ring takes the focus in the fade's last seconds
        advance(3_000) // the fade ends: pause(USER)
        assertEquals("vibb.volume(1.0)", page.calls.last { it.startsWith("vibb.volume(") })
        post(state(0, 13_000)) // the call is over: Chromium resumes
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        r.release()
    }

    @Test
    fun `M1 - held by the WebView the page outlives the idle close, a pause of ours closes it 5 minutes later`() = runBlocking {
        val r = playing()
        post(state(0, 5_000))
        post(state(0, 6_000, paused = true)) // a long call's ring
        advance(6 * 60_000L)
        assertTrue("held: the page stays for Chromium's resume", page.alive)
        post(state(0, 6_000)) // Chromium plays on after the call
        assertTrue(r.showsPlaying)
        assertFalse(r.heldByPage)
        r.pause()
        post(state(0, 7_000, paused = true))
        advance(5 * 60_000L + 1_000)
        assertFalse("a pause of ours: closed after 5 idle minutes", page.alive)
        r.release()
    }

    @Test
    fun `M1 - a call - the ring's pause before CALL_HOLD, CALL_RELEASE plays again, short or long`() = runBlocking {
        for (long in listOf(false, true)) {
            call = me.vibb.music.logic.CallPause()
            val r = playing()
            post(state(0, 5_000))
            post(state(0, 6_000, paused = true)) // the ring's focus loss reaches the page first
            callStep(r, inCall = true) // then CALL_HOLD: the held web player counts as playing
            assertTrue(call.pausedByCall)
            post(state(0, 6_000)) // Chromium resumes within the call (a ring's transient): paused again, not shown
            assertEquals("vibb.pause()", page.calls.last())
            assertFalse(r.showsPlaying)
            post(state(0, 6_000, paused = true))
            val plays = api.plays().size
            advance(if (long) 10 * 60_000L else 30_000L)
            callStep(r, inCall = false) // CALL_RELEASE
            advance(0)
            if (long) {
                assertFalse("the page went after 5 paused minutes", page.calls.last() == "vibb.resume()")
                assertEquals("a fresh start at the place", plays + 1, api.plays().size)
                assertEquals(6_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
            } else {
                assertEquals("vibb.resume()", page.calls.last())
                assertEquals(plays, api.plays().size)
            }
            assertTrue(r.showsPlaying)
            r.release()
        }
    }

    @Test
    fun `M1 - a long call - CALL_HOLD before the ring's pause, CALL_RELEASE plays again from a new page`() = runBlocking {
        val r = playing()
        post(state(0, 5_000))
        callStep(r, inCall = true) // CALL_HOLD first: our pause
        post(state(0, 5_000, paused = true))
        assertTrue(call.pausedByCall)
        assertFalse(r.heldByPage)
        advance(10 * 60_000L) // a pause of ours: the page goes after 5 minutes
        assertFalse(page.alive)
        callStep(r, inCall = false)
        advance(0)
        assertEquals("a fresh start at the place", 2, api.plays().size)
        assertEquals(5_000L, body(api.plays().last())["position_ms"]!!.jsonPrimitive.long)
        r.release()
    }

    @Test
    fun `L2 - a play tapped during a call starts nothing and shows paused, no line - CALL_RELEASE plays what the call paused`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 1, 0, playNow = false)
        r.inCall = true
        r.play()
        advance(1_000)
        assertFalse(r.showsPlaying)
        assertNull("no line", r.rendererState)
        assertTrue("nothing sent", api.all().isEmpty())
        assertEquals(0, page.opens)
        r.inCall = false
        r.play()
        advance(0)
        assertEquals(1, api.plays().size)
        // playing, then a call: the kid's tap during it resumes nothing
        post(state(1, 0))
        callStep(r, inCall = true)
        post(state(1, 1_000, paused = true))
        r.play()
        settle()
        assertFalse(r.showsPlaying)
        assertFalse(page.calls.contains("vibb.resume()"))
        callStep(r, inCall = false)
        settle()
        assertEquals("vibb.resume()", page.calls.last())
        r.release()
    }

    @Test
    fun `N2 - the renderer's own refusal of a step tells the screens it stayed`() = runBlocking {
        val r = renderer()
        r.load(mediaItems(5), 1, 0, playNow = false)
        ports.network = SpotifyLine.NEEDS_INTERNET
        val seen = kotlinx.coroutines.CompletableDeferred<me.vibb.music.playback.PlayerHub.Message>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            seen.complete(me.vibb.music.playback.PlayerHub.messages.first())
        }
        r.seekTo(2, 0)
        settle()
        assertEquals(me.vibb.music.playback.PlayerHub.Message.STAYED, seen.await())
        job.cancel()
        r.release()
    }

    // --- the emulator round of 2026-10-14 (N1, N2, N3, N5) ---

    @Test
    fun `N1 - list mode - a pause posted at a track's end keeps playing and the locks while the next track plays`() = runBlocking {
        val r = playing(count = 3, duration = 20_000)
        advance(15_000)
        post(state(0, 15_000, duration = 20_000))
        events.clear()
        advance(5_000)
        post(state(0, 20_000, paused = true, duration = 20_000)) // the element's pause before its `ended`
        assertTrue("still playing", r.showsPlaying)
        assertTrue("the locks kept", ports.locks)
        assertTrue(r.spec().playWhenReady)
        assertFalse("no pause heard", events.any { it is RendererEvent.Paused })
        assertFalse(r.heldByPage)
        advance(100)
        post(state(1, 0, prev = listOf(0), duration = 20_000))
        assertEquals(1, r.currentIndex())
        assertTrue(events.contains(RendererEvent.ItemChanged(1, true)))
        assertTrue(r.showsPlaying && ports.locks)
        assertEquals("nothing sent: Spotify's own advance", 1, api.plays().size)
        // and if a pause before the end did show paused, Spotify's advance shows playing again with its locks
        advance(15_000)
        post(state(1, 15_000, prev = listOf(0), duration = 20_000))
        advance(1_000)
        post(state(1, 16_000, paused = true, prev = listOf(0), duration = 20_000))
        assertFalse(r.showsPlaying || ports.locks)
        advance(4_000)
        post(state(2, 0, prev = listOf(0, 1), duration = 20_000))
        assertEquals(2, r.currentIndex())
        assertTrue("Resumed: playing again", r.showsPlaying && ports.locks)
        r.release()
    }

    @Test
    fun `L2 - a pause at a track's end Spotify never follows - looked at again, then the next window, never trenger internett`() = runBlocking {
        val r = playing(count = 3, duration = 20_000)
        advance(15_000)
        post(state(0, 15_000, duration = 20_000))
        advance(5_000)
        post(state(0, 20_000, paused = true, duration = 20_000))
        assertTrue(r.showsPlaying)
        advance(SpotifyTracker.LOOK_MS)
        assertEquals("the page asked again", "vibb.state()", page.calls.last())
        post(state(0, 20_000, paused = true, duration = 20_000)) // its answer: still paused at the end
        advance(0)
        assertEquals("a new window from the next item", 2, api.plays().size)
        assertEquals(uri(1), windowOf(api.plays().last()).first())
        assertNull("no line", r.rendererState)
        assertFalse(ports.outcomes.contains("unreachable"))
        post(state(1, 0, prev = listOf(0), duration = 20_000))
        assertTrue(r.showsPlaying)
        // and at the queue's last item the same pause is the end at once
        advance(15_000)
        post(state(1, 15_000, prev = listOf(0), duration = 20_000))
        advance(5_000)
        post(state(2, 0, prev = listOf(0, 1), duration = 20_000))
        advance(15_000)
        post(state(2, 15_000, prev = listOf(0, 1), duration = 20_000))
        advance(5_000)
        post(state(2, 20_000, paused = true, prev = listOf(0, 1), duration = 20_000))
        assertTrue(events.contains(RendererEvent.Ended))
        advance(SPOTIFY_STATE_GAP_MS)
        assertNull(r.rendererState)
        r.release()
    }

    @Test
    fun `N2 - an unplayable track at a boundary is the next item's - recorded once, the list goes on`() = runBlocking {
        val r = playing(count = 4, duration = 20_000)
        advance(15_000)
        post(state(0, 15_000, duration = 20_000))
        advance(5_000)
        post(state(0, 20_000, paused = true, duration = 20_000))
        page.emit(HostEvent.Error("playback", "x")) // Spotify reached item 1 and it failed
        settle()
        assertEquals("the error is item 1's, never the one that played out", listOf(1), errorsAt)
        // L1: the advance comes first (auto), so the service marks the played-out item 0 done
        val advanced = events.indexOf(RendererEvent.ItemChanged(1, true))
        assertTrue("the natural advance to item 1", advanced >= 0)
        assertTrue("before the error", advanced < events.indexOfFirst { it is RendererEvent.Error })
        advance(300)
        post(state(2, 0, prev = listOf(0, 1), duration = 20_000)) // Spotify went on past it
        advance(0)
        post(state(2, 500, prev = listOf(0, 1), duration = 20_000))
        assertEquals(2, r.currentIndex())
        assertTrue(r.showsPlaying)
        assertEquals("recorded once", listOf(1), errorsAt)
        assertFalse(ports.log.any { it.contains("three unplayable") })
        assertFalse("item 2 is ours, never paused as not ours", ports.log.any { it.contains("isn't ours") })
        r.release()
    }

    @Test
    fun `N3 - the session never says playing while the page holds it paused`() = runBlocking {
        val r = playing()
        post(state(0, 5_000))
        post(state(0, 6_000, paused = true)) // the ring's focus loss reaches the page first
        assertTrue(r.heldByPage)
        assertFalse(r.showsPlaying)
        assertFalse("the session follows the player: paused", r.spec().playWhenReady)
        r.release()
    }

    @Test
    fun `M1 - a hold counts for a call only while fresh - another app's takeover is never played again by a call's end`() = runBlocking {
        val held = me.vibb.music.logic.SPOTIFY_HELD_RECENT_MS
        assertTrue(me.vibb.music.logic.spotifyHeldRecently(held = true, heldForMs = 0))
        assertTrue(me.vibb.music.logic.spotifyHeldRecently(held = true, heldForMs = held - 1))
        assertFalse(me.vibb.music.logic.spotifyHeldRecently(held = true, heldForMs = held))
        assertFalse("a clock gone back counts as no hold", me.vibb.music.logic.spotifyHeldRecently(true, -1))
        assertFalse(me.vibb.music.logic.spotifyHeldRecently(held = false, heldForMs = 0))
        assertTrue("long enough for the call's word after the ring (60-220 ms)", held in 1_000..5_000)
        // a video app takes the audio for good: the page held, then a call 10 s later
        val r = playing()
        post(state(0, 5_000))
        post(state(0, 6_000, paused = true))
        advance(10_000)
        assertTrue(r.heldByPage)
        assertFalse(r.heldRecently())
        val pages = page.calls.size
        val plays = api.plays().size
        callStep(r, inCall = true)
        assertFalse("not the call's to pause", call.pausedByCall)
        advance(30_000)
        callStep(r, inCall = false)
        advance(1_000)
        assertEquals("nothing sent to the page", pages, page.calls.size)
        assertEquals("no play call", plays, api.plays().size)
        assertFalse(r.showsPlaying)
        r.release()
    }

    @Test
    fun `N3 - a VoIP ring while paused - CALL_HOLD now comes, a play tapped during it starts nothing, nor after`() = runBlocking {
        // the launcher sends CALL_HOLD at every call's begin (a call began), the music app's own pause is no call's
        val r = playing()
        post(state(0, 5_000))
        r.pause()
        post(state(0, 5_000, paused = true))
        callStep(r, inCall = true) // CALL_HOLD of a VoIP ring: no audio mode, the launcher's word only
        assertFalse(call.pausedByCall)
        assertTrue(r.inCall)
        val pages = page.calls.size
        r.play()
        advance(1_000)
        assertFalse(r.showsPlaying)
        assertEquals(pages, page.calls.size)
        callStep(r, inCall = false) // CALL_RELEASE
        advance(1_000)
        assertFalse("nothing plays that the call didn't pause", r.showsPlaying)
        assertEquals(pages, page.calls.size)
        r.release()
    }

    @Test
    fun `N3 - a call the mode reports - a play tapped during it starts nothing, before or after the page's own pause`() = runBlocking {
        // paused before the call, then a call the launcher never sent (it saw nothing playing): the mode's call
        val r = playing()
        post(state(0, 5_000))
        r.pause()
        post(state(0, 5_000, paused = true))
        callStep(r, inCall = true)
        assertFalse("nothing of ours to pause", call.pausedByCall)
        val pages = page.calls.size
        val plays = api.plays().size
        r.play() // the kid's tap in the music app during the call
        advance(1_000)
        assertFalse(r.showsPlaying)
        assertEquals("nothing sent to the page", pages, page.calls.size)
        assertEquals(plays, api.plays().size)
        assertTrue(ports.log.contains("spotify: a start refused (a call)"))
        callStep(r, inCall = false)
        advance(1_000)
        assertFalse("the call ends: nothing plays that it didn't pause", r.showsPlaying)
        assertEquals(pages, page.calls.size)
        // playing at the ring, the page's pause first, then the mode's call: paused by the call, played again after
        call = me.vibb.music.logic.CallPause()
        r.play()
        settle()
        post(state(0, 5_000))
        assertTrue(r.showsPlaying)
        post(state(0, 5_500, paused = true))
        callStep(r, inCall = true)
        assertTrue(call.pausedByCall)
        r.play() // a tap during the call: refused, the call rule's play still comes
        settle()
        assertFalse(r.showsPlaying)
        callStep(r, inCall = false)
        settle()
        assertEquals("vibb.resume()", page.calls.last())
        assertTrue(r.showsPlaying)
        r.release()
    }

    @Test
    fun `N5 - a list flipped to a context while it plays - the new queue's context play, no more queue adds`() = runBlocking {
        val l = playing(count = 60)
        for (n in 1..50) {
            advance(4_000)
            post(state(n, 0, prev = listOf(n - 1)))
        }
        val adds = api.all().count { it.url.encodedPath == "/v1/me/player/queue" }
        assertTrue("the list adds one ahead", adds >= 1)
        val plays = api.plays().size
        // the service's new queue for the flipped entry (its cover tap, PlaybackService.playEntry)
        val entry = Entry(6, "e6", "Kveldsliste", null, Source.SPOTIFY, playlistContext, "auto", 0, true, null, "11ee", 6, spotifyMode = "context")
        assertTrue(me.vibb.music.playback.loadedPlayStale(active!!.entry, entry))
        val items = listOf(me.vibb.music.logic.contextItem(entry))
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        l.load(contextItems(), 0, 0, playNow = true)
        advance(0)
        assertEquals("one context play", plays + 1, api.plays().size)
        assertTrue(api.plays().last().body!!.contains("\"context_uri\":\"$playlistContext\""))
        post(state(51, 0, prev = listOf(50))) // the old list's next track, before the context took over: not ours
        post(cstate(3, 0))
        assertTrue(l.showsPlaying)
        for (n in 4..8) {
            advance(4_000)
            post(cstate(n, 0))
        }
        assertEquals("no queue add after the flip", adds, api.all().count { it.url.encodedPath == "/v1/me/player/queue" })
        l.release()
    }

    private companion object {
        const val DEVICE = "FAKEdevice1"
        const val DURATION = 200_000L
    }
}

/** Media3's clock for the session's player in a JVM test (§6l A, F1): handlers on the test's [Looper], a post runs at once. */
@OptIn(UnstableApi::class)
private object TestClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    override fun uptimeMillis(): Long = elapsedRealtime()
    override fun nanoTime(): Long = System.nanoTime()
    override fun createHandler(looper: Looper, callback: Handler.Callback?): HandlerWrapper = TestHandler(looper)
    override fun onThreadBlocked() = Unit
}

/** Answers Media3's `ListenerSet` and `SimpleBasePlayer` (their thread checks read [getLooper]); messages go nowhere. */
@OptIn(UnstableApi::class)
private class TestHandler(private val looper: Looper) : HandlerWrapper {
    private val message = object : HandlerWrapper.Message {
        override fun sendToTarget() = Unit
        override fun getTarget(): HandlerWrapper = this@TestHandler
    }

    override fun getLooper(): Looper = looper
    override fun hasMessages(what: Int): Boolean = false
    override fun obtainMessage(what: Int): HandlerWrapper.Message = message
    override fun obtainMessage(what: Int, obj: Any?): HandlerWrapper.Message = message
    override fun obtainMessage(what: Int, arg1: Int, arg2: Int): HandlerWrapper.Message = message
    override fun obtainMessage(what: Int, arg1: Int, arg2: Int, obj: Any?): HandlerWrapper.Message = message
    override fun sendMessageAtFrontOfQueue(message: HandlerWrapper.Message): Boolean = true
    override fun sendEmptyMessage(what: Int): Boolean = true
    override fun sendEmptyMessageDelayed(what: Int, delayMs: Int): Boolean = true
    override fun sendEmptyMessageAtTime(what: Int, uptimeMs: Long): Boolean = true
    override fun removeMessages(what: Int) = Unit
    override fun removeCallbacksAndMessages(token: Any?) = Unit
    override fun post(runnable: Runnable): Boolean {
        runnable.run()
        return true
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long): Boolean = true
    override fun postAtFrontOfQueue(runnable: Runnable): Boolean = post(runnable)
}
