package me.vibb.music.spotify

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.vibb.music.bridge.TokenResult
import me.vibb.music.logic.SPOTIFY_STATE_GAP_MS
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
        var focusGranted = true
        var focusHeld = false
        var focus: ((FocusChange) -> Unit)? = null
        var noisy: (() -> Unit)? = null
        var locks = false
        var renewals = 0
        var mayPlay = true
        var network: SpotifyLine? = null
        val saves = mutableListOf<Triple<Int, Long, Boolean>>()
        val outcomes = mutableListOf<String>()

        override fun now(): Long = this@SpotifyRendererTest.now
        override fun after(delayMs: Long, block: () -> Unit): () -> Unit = this@SpotifyRendererTest.after(delayMs, block)
        override fun requestFocus(speech: Boolean, onChange: (FocusChange) -> Unit): Boolean {
            focus = onChange
            focusHeld = focusGranted
            return focusGranted
        }

        override fun abandonFocus() {
            focusHeld = false
        }

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
        }

        override fun outcome(word: String) {
            outcomes += word
        }

        override fun log(message: String) {
            log += message
        }
    }

    /** The scripted page: records its calls; `ready` comes at once unless [autoReady] is null. */
    private inner class Page : SpotifyHost {
        override var events: ((HostEvent) -> Unit)? = null
        override var alive = false
        var opens = 0
        val destroys = mutableListOf<Boolean>()
        val calls = mutableListOf<String>()
        var autoReady: String? = DEVICE

        override fun open() {
            alive = true
            opens++
            autoReady?.let { id -> after(0) { emit(HostEvent.Ready(id)) } }
        }

        override fun call(name: String, vararg args: Any?) {
            calls += SpotifyPage.call(name, *args)
        }

        override fun destroy(clear: Boolean) {
            alive = false
            destroys += clear
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
    private var active: Active? = null

    /** Play the service's part on an unplayable track (`PlaybackService.onError`: record, then on to the next). */
    private var serviceMovesOn = true

    private fun key(n: Int) = "FAKEtrack%013d".format(n)
    private fun uri(n: Int) = "spotify:track:${key(n)}"

    private fun state(
        n: Int,
        pos: Long,
        paused: Boolean = false,
        prev: List<Int> = emptyList(),
        duration: Long = DURATION,
        shuffle: Boolean = false,
    ): SdkSnapshot = SdkState(
        paused, pos, duration, uri(n), null, listOf(n + 1, n + 2).map { uri(it) }, prev.map { uri(it) },
        "track", "Fake track $n", listOf("Fake Artist"), null, shuffle = shuffle,
    )

    private suspend fun post(s: SdkSnapshot) {
        page.emit(HostEvent.State(s))
        settle()
    }

    private fun CoroutineScope.renderer(count: Int = 5, duration: Long = DURATION): SpotifyRenderer {
        val entry = Entry(6, "e6", "Kveldsliste", null, Source.SPOTIFY, "spotify:playlist:FAKEplaylist0000000001", "auto", 0, true, null, "11ee", 6, spotifyMode = "list")
        val items = (0 until count).map { PlayItem(6, key(it), "Fake track $it — Fake Artist", null, false, duration) }
        active = Active(entry, items, items, mutableMapOf(), mutableSetOf(), mutableMapOf(), false, mutableMapOf<String, Uri>())
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val ask: suspend () -> TokenResult = {
            asks++
            TokenResult.Ok(if (sameToken) "FAKE-ACCESS-1" else "FAKE-ACCESS-$asks", now + tokenLife, account)
        }
        val r = SpotifyRenderer(page, api, "https://api.spotify.com".toHttpUrl(), ask, ports, dispatcher, { active }, null)
        r.onEvent = { e ->
            events += e
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
        assertTrue(ports.focusHeld)
        assertTrue(events.contains(RendererEvent.IsPlaying(true)))
        assertEquals(1, page.opens)
        advance(15_000)
        r.play() // the second tap during the cold start
        settle()
        assertEquals("no call before the page is ready", 0, api.all().size)
        assertTrue(r.showsPlaying)
        assertEquals(Player.STATE_BUFFERING, r.spec().playbackState)
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
        assertFalse(ports.focusHeld)
        r.seekTo(30_000)
        assertEquals("vibb.seek(30000)", page.calls.last())
        r.play()
        assertEquals("vibb.resume()", page.calls.last())
        assertTrue(r.showsPlaying && ports.locks && ports.focusHeld)
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

    // --- §5.6: focus, noisy, the fade ---

    @Test
    fun `focus - a loss pauses, a transient loss interrupts and the gain plays again, a duck lowers the volume`() = runBlocking {
        val r = playing()
        ports.focus!!(FocusChange.LOSS_TRANSIENT)
        settle()
        assertEquals("vibb.pause()", page.calls.last())
        assertTrue("play-when-ready holds through a transient loss", r.spec().playWhenReady)
        assertTrue(r.spec().suppressed)
        assertTrue(events.contains(RendererEvent.Interrupted))
        post(state(0, 3_000, paused = true))
        assertTrue("our own pause doesn't end the intent", r.spec().playWhenReady)
        ports.focus!!(FocusChange.GAIN)
        settle()
        assertEquals("vibb.resume()", page.calls.last())
        assertFalse(r.spec().suppressed)
        assertTrue(events.contains(RendererEvent.InterruptionEnded))
        ports.focus!!(FocusChange.DUCK)
        assertEquals("vibb.volume(0.3)", page.calls.last())
        ports.focus!!(FocusChange.GAIN)
        assertEquals("vibb.volume(1.0)", page.calls.last())
        events.clear()
        ports.focus!!(FocusChange.LOSS)
        settle()
        assertEquals("vibb.pause()", page.calls.last())
        assertFalse(r.showsPlaying)
        assertTrue(events.contains(RendererEvent.Paused(PauseCause.FAULT)))
        assertFalse(ports.locks)
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
        assertEquals(listOf(false), page.destroys)
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
        assertEquals("idle: the page went", listOf(false), page.destroys)
        account = "3:1"
        r.play()
        advance(0)
        assertEquals("the old account's cookies and storage cleared before the new page plays", listOf(false, true), page.destroys)
        assertTrue(page.alive)
        assertEquals(2, api.plays().size)
        // and the SDK's own ask that brings another account clears the live page at once
        account = "4:1"
        tokenLife = 60 * 60_000L
        r.tokens.invalidate()
        page.emit(HostEvent.NeedToken(3))
        settle()
        assertEquals(listOf(false, true, true), page.destroys)
        assertFalse("no token of another account reaches the old page", page.calls.any { it.startsWith("vibb.token(3,") })
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
        assertFalse(ports.focusHeld)
        assertNull(ports.noisy)
    }

    private companion object {
        const val DEVICE = "FAKEdevice1"
        const val DURATION = 200_000L
    }
}
