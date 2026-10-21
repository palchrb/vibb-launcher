package me.vibb.music.sonos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/**
 * The event socket's connection (design 53 §2.2, §3): the host gate before any connect, the lifetime
 * rule, the backoff that resets only after a healthy, live socket, the parks, and the liveness rule -
 * through a fake [WebSocket.Factory] whose listener the test drives (no network, no sleeping: the
 * main thread's delayed blocks run when the test says).
 */
class SonosSocketTest {
    private val clock = FakeClock()
    private val uid = "RINCON_00000000000001400"
    private val ip = "192.168.1.16"

    private class FakeWs(private val request: Request) : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false

        override fun request(): Request = request

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = sent.add(text)

        override fun send(bytes: ByteString): Boolean = false

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() {
            cancelled = true
        }
    }

    /** The main thread: posts run at once; delayed blocks wait in [delayed] until [runDue]. */
    private inner class Main : SocketMain {
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()

        override fun post(block: () -> Unit) = block()

        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            delayed += (clock.now + delayMs) to block
        }

        /** The blocks due by now, in order. */
        fun runDue() {
            while (true) {
                val due = delayed.filter { it.first <= clock.now }.minByOrNull { it.first } ?: return
                delayed.remove(due)
                due.second()
            }
        }
    }

    private val main = Main()
    private val opened = mutableListOf<Pair<FakeWs, WebSocketListener>>()
    private val events = mutableListOf<SocketEvent>()
    private var cachedIp: String? = ip
    private val parks = SocketParks()
    private val network = Any()

    /** The (ip, uid) each client was asked for: the certificate is checked against that uid. */
    private val clients = mutableListOf<Pair<String, String>>()
    private val logs = mutableListOf<String>()
    private val socket = SonosSocket(
        clientFor = { ip, uid ->
            clients += ip to uid
            WebSocket.Factory { request, listener -> FakeWs(request).also { opened += it to listener } }
        },
        resolveIp = { cachedIp },
        clock = { clock.now },
        main = main,
        onEvent = { events += it },
        log = { logs += it },
        parks = parks,
    )

    private fun ws() = opened.last().first

    private fun listener() = opened.last().second

    private fun response(code: Int) = Response.Builder().request(ws().request()).protocol(Protocol.HTTP_1_1).code(code).message("x").build()

    /** The speaker's side of the handshake: opened, then each reply of `ws/handshake.json`. */
    private fun handshake() {
        listener().onOpen(ws(), response(101))
        for (m in Json.parseToJsonElement(SonosFakes.fixture("ws/handshake.json")) as JsonArray) listener().onMessage(ws(), m.toString())
    }

    private fun event(state: String) = listener().onMessage(
        ws(),
        "[{\"namespace\":\"playback:1\",\"groupId\":\"$uid:100\",\"type\":\"playbackStatus\"},{\"playbackState\":\"$state\",\"positionMillis\":1000}]",
    )

    private fun healthyNow(): SonosSocket {
        socket.want(SonosSocket.Target(uid, network))
        handshake()
        assertTrue(socket.healthy)
        return socket
    }

    @Test
    fun `the host gate - a private ip literal that is the cached ip of our uid, port 1443, the socket's path`() {
        val url = socketUrl(ip)!!
        assertEquals("https://192.168.1.16:1443/websocket/api", url.toString())
        assertTrue(socketGate(url, ip))
        assertFalse("a public address", socketGate(socketUrl("93.184.215.14")!!, "93.184.215.14"))
        assertNull("a name is never a socket url", socketUrl("stua.local"))
        assertFalse("another port", socketGate(url.newBuilder().port(1400).build(), ip))
        assertFalse("another path", socketGate(url.newBuilder().encodedPath("/other").build(), ip))
        assertFalse("not the cached ip of our uid", socketGate(url, "192.168.1.17"))
        assertFalse("nothing cached", socketGate(url, null))
        assertFalse("cleartext", socketGate(HttpUrl.Builder().scheme("http").host(ip).port(1443).encodedPath("/websocket/api").build(), ip))
        assertTrue("an IPv6 ULA", socketGate(socketUrl("fd00::5")!!, "fd00::5"))
        // design 53 §2.2's ranges only, not the public-address guard's wider "private" (qa-53a-code 6)
        for (ip in listOf("127.0.0.1", "0.0.0.0", "100.64.0.1", "100.100.1.1", "192.0.0.8", "::1", "2001:db8::1")) {
            assertFalse(ip, socketGate(socketUrl(ip)!!, ip))
        }
        for (ip in listOf("10.0.2.2", "172.20.0.3", "169.254.10.1", "fe80::1")) assertTrue(ip, socketGate(socketUrl(ip)!!, ip))
    }

    @Test
    fun `refused before any connect - a public cached ip, or none, is never dialled`() {
        cachedIp = "93.184.215.14"
        socket.want(SonosSocket.Target(uid, network))
        assertTrue(opened.isEmpty())
        cachedIp = null
        socket.want(SonosSocket.Target(uid, Any()))
        assertTrue(opened.isEmpty())
        assertTrue("and no retry is scheduled", main.delayed.isEmpty())
    }

    @Test
    fun `the request - the cached ip's url, the public example key, the subprotocol, the empty command first`() {
        socket.want(SonosSocket.Target(uid, network))
        val request = ws().request()
        assertEquals("https://192.168.1.16:1443/websocket/api", request.url.toString())
        assertEquals("123e4567-e89b-12d3-a456-426655440000", request.header("X-Sonos-Api-Key"))
        assertEquals(SONOS_WS_PROTOCOL, request.header("Sec-WebSocket-Protocol"))
        listener().onOpen(ws(), response(101))
        assertEquals("[{\"cmdId\":\"vibb-1\"},{}]", ws().sent.single())
        assertFalse("not healthy before the subscription", socket.healthy)
    }

    @Test
    fun `healthy after the handshake - events reach the controller, a Health event on each change`() {
        healthyNow()
        assertTrue(events.contains(SocketEvent.Health(true)))
        assertTrue(events.any { it is SocketEvent.Playback })
        socket.want(null)
        assertTrue(ws().cancelled)
        assertFalse(socket.healthy)
        assertEquals(SocketEvent.Health(false), events.last())
        assertTrue("a close we asked for is no failure: no retry", main.delayed.isEmpty())
    }

    @Test
    fun `the liveness rule - a verb owed an event that never came is a dead socket, and the session's reader says so`() {
        healthyNow()
        clock.advance(1) // the verb goes out after the handshake's status (an event at or after it would pay it)
        socket.owed(since = clock.now, answeredAt = clock.now)
        clock.advance(Liveness.LIVENESS_MS + 1)
        main.runDue()
        assertTrue(ws().cancelled)
        assertFalse("the session's health reader", socket.healthy)
        assertEquals("a reconnect after 1 s", 1, main.delayed.size)
        clock.advance(1_000)
        main.runDue()
        assertEquals(2, opened.size)
    }

    @Test
    fun `the liveness rule - an event that came before the verb's answer reached main pays it (qa-53a-code 2)`() {
        healthyNow()
        val sent = clock.now
        clock.advance(200)
        event("PLAYBACK_STATE_PAUSED") // the speaker pushes 0.0-0.4 s after the command
        clock.advance(100)
        socket.owed(since = sent, answeredAt = clock.now) // the SOAP answer, through the actor, later
        clock.advance(Liveness.LIVENESS_MS + 1)
        main.runDue()
        assertTrue(socket.healthy)
        assertFalse(ws().cancelled)
        // ... and an event from before the verb pays nothing
        val later = clock.now
        socket.owed(since = later, answeredAt = later)
        clock.advance(Liveness.LIVENESS_MS + 1)
        main.runDue()
        assertTrue("owed and never paid", ws().cancelled)
    }

    @Test
    fun `the liveness rule - an event at or after the verb pays it, and a verb that owes none keeps the socket (N3)`() {
        healthyNow()
        socket.owed(since = clock.now, answeredAt = clock.now)
        clock.advance(300)
        event("PLAYBACK_STATE_PAUSED")
        clock.advance(Liveness.LIVENESS_MS + 1)
        main.runDue()
        assertTrue(socket.healthy)
        // a seek while paused owes nothing (the controller never calls owed for it): no event, still healthy
        clock.advance(10_000)
        main.runDue()
        assertTrue(socket.healthy)
        assertFalse(ws().cancelled)
    }

    @Test
    fun `the backoff - 1, 2, 5, 15, 60 s, then 300 s, reset only after a healthy socket passed a liveness check`() {
        val b = SocketBackoff()
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 15_000L, 60_000L, 300_000L, 300_000L), (1..7).map { b.failed(SocketFailure.OTHER) })
        b.opened()
        assertEquals("opening alone resets nothing (a silent socket is never rewarded)", 300_000L, b.failed(SocketFailure.OTHER))
        b.passed()
        assertEquals(1_000L, b.failed(SocketFailure.OTHER))
        // through the socket: healthy, live, then a failure starts over at 1 s
        healthyNow()
        listener().onFailure(ws(), java.io.IOException("reset"), null)
        assertEquals(clock.now + 1_000, main.delayed.minOf { it.first })
        clock.advance(1_000)
        main.runDue()
        handshake()
        listener().onFailure(ws(), java.io.IOException("reset"), null)
        assertEquals("healthy is not enough: no liveness check passed", clock.now + 2_000, main.delayed.minOf { it.first })
        clock.advance(2_000)
        main.runDue()
        handshake()
        socket.owed(clock.now, clock.now)
        event("PLAYBACK_STATE_PAUSED")
        listener().onFailure(ws(), java.io.IOException("reset"), null)
        assertEquals("live: starts over", clock.now + 1_000, main.delayed.minOf { it.first })
    }

    @Test
    fun `an HTTP answer instead of the upgrade parks the uid for 6 h - so do five refused connects in a row`() {
        socket.want(SonosSocket.Target(uid, network))
        listener().onFailure(ws(), java.net.ProtocolException("Expected HTTP 101"), response(403))
        val until = parks.parkedUntil(uid, clock.now)
        assertNotNull(until)
        assertEquals(clock.now + SocketBackoff.PARK_MS, until)
        assertTrue("no retry while parked", main.delayed.isEmpty())
        assertFalse(socketWanted(true, network, false, socket.parkedUntil(uid), uid, clock.now))
        assertTrue("after the park", socketWanted(true, network, false, socket.parkedUntil(uid), uid, clock.now + SocketBackoff.PARK_MS))

        val other = "RINCON_00000000000001401"
        socket.want(SonosSocket.Target(other, network))
        repeat(4) {
            listener().onFailure(ws(), ConnectException("refused"), null)
            clock.advance(60_000)
            main.runDue()
        }
        assertNull(parks.parkedUntil(other, clock.now))
        listener().onFailure(ws(), ConnectException("refused"), null)
        assertNotNull("the fifth refused connect (an S1 speaker, no port 1443)", parks.parkedUntil(other, clock.now))
        assertEquals(5, opened.size - 1)
    }

    /** 53-c: a refused certificate is a refused connect (it won't change): five in a row park; its client is our uid's. */
    @Test
    fun `d53c - a refused certificate counts as a refused connect, its message never logged - other TLS failures don't park`() {
        socket.want(SonosSocket.Target(uid, network))
        assertEquals(listOf(ip to uid), clients)
        val refused = javax.net.ssl.SSLHandshakeException("TLS failed: CN=SECRET").apply {
            initCause(java.security.cert.CertificateException(SpeakerCertificateException("udn mismatch")))
        }
        repeat(4) {
            listener().onFailure(ws(), refused, null)
            clock.advance(60_000)
            main.runDue()
        }
        assertNull(parks.parkedUntil(uid, clock.now))
        listener().onFailure(ws(), refused, null)
        assertNotNull("the fifth refused certificate", parks.parkedUntil(uid, clock.now))
        assertTrue(logs.toString(), logs.any { "REFUSED: certificate refused" in it })
        assertTrue("the platform's message is never logged", logs.none { "SECRET" in it })

        val other = "RINCON_00000000000001401"
        socket.want(SonosSocket.Target(other, network))
        assertEquals(ip to other, clients.last())
        repeat(6) {
            listener().onFailure(ws(), javax.net.ssl.SSLHandshakeException("reset"), null)
            clock.advance(400_000)
            main.runDue()
        }
        assertNull("another TLS failure backs off without a park", parks.parkedUntil(other, clock.now))
    }

    @Test
    fun `socketWanted - opens when armed, closes at a disarm, a stop, the release, without Wi-Fi or parked`() {
        val now = clock.now
        assertTrue(socketWanted(armed = true, wifiNetwork = network, closed = false, parkedUntil = null, uid = uid, now = now))
        assertFalse("the disarm, a stop, the park (the session disarms)", socketWanted(false, network, false, null, uid, now))
        assertFalse("the hand-off and the release (closed)", socketWanted(true, network, true, null, uid, now))
        assertFalse("no Wi-Fi", socketWanted(true, null, false, null, uid, now))
        assertFalse("parked", socketWanted(true, network, false, now + 1, uid, now))
        assertFalse("no uid", socketWanted(true, network, false, null, "", now))
    }

    @Test
    fun `a change of the Wi-Fi network or of the uid closes the socket and opens it on the new one`() {
        healthyNow()
        val first = ws()
        socket.want(SonosSocket.Target(uid, Any())) // another Network (B's onAvailable before A's onLost)
        assertTrue(first.cancelled)
        assertEquals(2, opened.size)
        val second = ws()
        cachedIp = "192.168.1.17"
        socket.want(SonosSocket.Target("RINCON_00000000000001401", network)) // a follow re-targets
        assertTrue(second.cancelled)
        assertEquals("https://192.168.1.17:1443/websocket/api", ws().request().url.toString())
        socket.want(SonosSocket.Target("RINCON_00000000000001401", network))
        assertEquals("the same target: nothing new", 3, opened.size)
    }

    @Test
    fun `a stale connection's word is dropped`() {
        healthyNow()
        val (oldWs, oldListener) = opened.last()
        socket.want(SonosSocket.Target(uid, Any()))
        events.clear()
        oldListener.onMessage(oldWs, "[{\"namespace\":\"playback:1\",\"groupId\":\"$uid:100\",\"type\":\"playbackStatus\"},{\"playbackState\":\"PLAYING\"}]")
        oldListener.onFailure(oldWs, java.io.IOException("late"), null)
        assertTrue(events.isEmpty())
        assertTrue("no retry for the old one", main.delayed.isEmpty())
    }
}
