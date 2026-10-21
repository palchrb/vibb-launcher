package me.vibb.music.sonos

import android.annotation.SuppressLint
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.ConnectException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/*
 * The speaker's local event socket, the connection (design 53 §2.2): OkHttp's own WebSocket (no new
 * dependency) to `wss://<cached ip>:1443/websocket/api`, its lifetime ([socketWanted]), the host gate
 * and the limited trust, the backoff and the parks, the liveness rule and the health the session
 * reads. Ours (GPL-3.0 or later): vibb polls only. Android-free but for [SuppressLint] (the main
 * thread comes in as [SocketMain]); SonosSocketTest drives it through a fake [WebSocket.Factory].
 *
 * Events only: the socket never commands the speaker (UPnP does), never writes Room, and a URL
 * from a message (a `MOVED`'s or a player's `websocketUrl`) is never dialled - only the cached ip of
 * the uid we command, built with [HttpUrl.Builder] as every URL here.
 */

/**
 * The `X-Sonos-Api-Key` a speaker accepts on its local socket: the RFC 4122 example UUID (the one the
 * open-source `sonos-websocket` sends). Public, not a secret - it looks fake because it is the example.
 */
const val SONOS_WS_API_KEY = "123e4567-e89b-12d3-a456-426655440000"

/** The subprotocol, the port and the path the socket lives at (design 53 §0.1). */
const val SONOS_WS_PROTOCOL = "v1.api.smartspeaker.audio"
const val SONOS_WS_PORT = 1443
const val SONOS_WS_PATH = "/websocket/api"

/**
 * The socket's lifetime (design 53 §2.2): open while the session is [armed] (a play or an adopt armed
 * it - the polling rule, battery), Wi-Fi is up ([wifiNetwork]), the renderer isn't [closed] and the
 * [uid] isn't parked; closed at the disarm, a stop, the park, the hand-off and the release.
 */
fun socketWanted(armed: Boolean, wifiNetwork: Any?, closed: Boolean, parkedUntil: Long?, uid: String?, now: Long): Boolean =
    armed && wifiNetwork != null && !closed && !uid.isNullOrEmpty() && (parkedUntil == null || now >= parkedUntil)

/** `https://<ip>:1443/websocket/api` for an IP literal (OkHttp maps https to wss), or null. */
fun socketUrl(ip: String): HttpUrl? {
    if (!isIpLiteral(ip)) return null
    return try {
        HttpUrl.Builder().scheme("https").host(ip).port(SONOS_WS_PORT).encodedPath(SONOS_WS_PATH).build()
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * The host gate before any connect (design 53 §2.2): https to an IP literal in a private range
 * (`PlayBodies.privateLiteral`: RFC 1918, link-local, ULA ...) that is the cached ip of the uid we
 * command ([cachedIp]), port 1443, the socket's path. Anything else is never dialled.
 */
fun socketGate(url: HttpUrl, cachedIp: String?): Boolean {
    val cached = cachedIp?.let(::socketUrl) ?: return false
    return url.scheme == "https" && isIpLiteral(url.host) && PlayBodies.privateLiteral(url.host) &&
        url.host == cached.host && url.port == SONOS_WS_PORT && url.encodedPath == SONOS_WS_PATH
}

/**
 * The speaker's self-signed certificate, accepted - for the one gated host only: the socket's own
 * client verifies the host name as exactly that ip ([speakerSocketClient]), and no other client
 * ever uses this trust (OkHttp pools by `Address`, the socket factory and the verifier included).
 * Enough because the socket only reads, and what it says is checked: a spoofed event can at worst
 * re-base the bar until the next poll or wake a poll (rate limited); every decision is UPnP's, and
 * UPnP to the same address is already cleartext - pinning would add nothing against someone who can
 * answer port 1400 (design 53 §2.2, "Trust").
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private object SpeakerTrust : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The socket's own client for [ip]: the SOAP client's ([base]: unbound to any `Network`, design 26a
 * §6.5) with connect 2 s (the read timeout is cleared by OkHttp after the upgrade), the 30 s ping
 * (the probe's heartbeat: a missing pong closes it), [SpeakerTrust] and a host name verifier that
 * accepts exactly [ip]. Used for the socket and nothing else.
 */
fun speakerSocketClient(base: OkHttpClient, ip: String): OkHttpClient {
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(SpeakerTrust), SecureRandom()) }
    val host = socketUrl(ip)?.host
    return base.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .sslSocketFactory(tls.socketFactory, SpeakerTrust)
        .hostnameVerifier { name, _ -> host != null && name == host }
        .build()
}

/** Why a connection ended. */
enum class SocketFailure {
    /** A TCP refusal (an S1 speaker, a firmware without port 1443). */
    REFUSED,

    /** An HTTP answer instead of the upgrade (401/403/404: the key or the endpoint closed by a firmware update). */
    HTTP,

    /** A timeout, TLS, a reset, a close, a dead ping, a liveness miss. */
    OTHER,
}

/**
 * The reconnect delays (design 53 §2.2): 1, 2, 5, 15, 60 s, then every 300 s while wanted. They reset
 * only after a socket stayed healthy and passed a liveness check ([passed]), so a silent socket is
 * never rewarded. An HTTP answer, or [REFUSED_TO_PARK] refused connects in a row, park the uid for
 * [PARK_MS] (null from [failed]). Pure.
 */
class SocketBackoff {
    private var step = 0
    private var refused = 0

    /** The delay before the next connect, or null: park the uid. */
    fun failed(why: SocketFailure): Long? {
        if (why == SocketFailure.HTTP) return null
        if (why == SocketFailure.REFUSED) {
            if (++refused >= REFUSED_TO_PARK) return null
        } else {
            refused = 0
        }
        return DELAYS_MS[minOf(step++, DELAYS_MS.size - 1)]
    }

    /** The upgrade went through: the refused streak is over (the delays are not). */
    fun opened() {
        refused = 0
    }

    /** Healthy and live: the sequence starts over. */
    fun passed() {
        step = 0
        refused = 0
    }

    companion object {
        val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 60_000, 300_000)
        const val REFUSED_TO_PARK = 5
        const val PARK_MS = 6 * 3_600_000L
    }
}

/** Parked uids (until, elapsed), process memory only: a new renderer for the same room still waits. */
class SocketParks {
    private val until = ConcurrentHashMap<String, Long>()

    fun parkedUntil(uid: String, now: Long): Long? = until[uid]?.takeIf { it > now }

    fun park(uid: String, untilMs: Long) {
        until[uid] = untilMs
    }

    companion object {
        val PROCESS = SocketParks()
    }
}

/**
 * The liveness rule (design 53 §2.2, revision check N3): a verb of ours that must change the speaker's
 * state (a pause on a confirmed PLAYING, a resume on a confirmed PAUSED, a new item or a step) owes a
 * `playbackStatus` within [LIVENESS_MS] of its answer; none came, the socket is dead. Any event at or
 * after the verb's [owe] pays it. Pure.
 */
class Liveness {
    private var since: Long? = null
    private var deadline: Long? = null

    /** A verb went out at [since] and its answer came at [answeredAt]. */
    fun owe(since: Long, answeredAt: Long) {
        if (this.since == null) this.since = since
        deadline = answeredAt + LIVENESS_MS
    }

    /** An event at [at]: true when it paid an owed verb (the check passed). */
    fun seen(at: Long): Boolean {
        val s = since ?: return false
        if (at < s) return false
        since = null
        deadline = null
        return true
    }

    /** The owed event didn't come by its deadline. */
    fun missed(now: Long): Boolean = deadline?.let { now > it } ?: false

    fun clear() {
        since = null
        deadline = null
    }

    companion object {
        /** Measured 0.0-0.4 s after a command (design 53 §0.1). */
        const val LIVENESS_MS = 3_000L
    }
}

/** The main thread, as the socket needs it ([post] runs in order; [postDelayed] after [delayMs]). */
interface SocketMain {
    fun post(block: () -> Unit)

    fun postDelayed(delayMs: Long, block: () -> Unit)
}

/** The event socket as the controller sees it (design 53 §2.2-§2.3). */
interface SonosEventLink {
    /** Healthy now: open, our coordinator's playback subscribed, its status read, and live. Any thread. */
    val healthy: Boolean

    /** A verb of ours that must change the state went out at [since] and was answered at [answeredAt]: an event is owed. */
    fun owed(since: Long, answeredAt: Long) {}

    /** Two polls in a row saw another state than the socket's (design 53 §2.2, "Disagreement"). */
    fun disagreed() {}

    companion object {
        val NONE = object : SonosEventLink {
            override val healthy: Boolean get() = false
        }
    }
}

/**
 * One room's socket: main-thread confined but for [healthy] (volatile, read by the session on IO)
 * and the listener, which parses on OkHttp's reader thread, sends the handshake's commands from
 * there (`WebSocket.send` is thread-safe) and posts compact [SocketEvent]s to the main thread.
 * [want] is the only door: a target (the uid, its Wi-Fi network) opens or re-targets it, null
 * closes it. Every failure leaves the app on polling - speed lost, never a feature.
 */
class SonosSocket(
    /** The socket's own client for an ip ([speakerSocketClient]); an [OkHttpClient] is a [WebSocket.Factory]. */
    private val clientFor: (ip: String) -> WebSocket.Factory,
    /** The cached ip of a uid (`SpeakerCache.resolve`). */
    private val resolveIp: (uid: String) -> String?,
    private val clock: () -> Long,
    private val main: SocketMain,
    /** On the main thread. */
    private val onEvent: (SocketEvent) -> Unit,
    private val log: (String) -> Unit = {},
    private val parks: SocketParks = SocketParks.PROCESS,
) : SonosEventLink {
    /** What the socket is for: the uid we command on the Wi-Fi network it was asked on. */
    data class Target(val uid: String, val network: Any)

    private class Conn(val id: Int, val ws: WebSocket, val uid: String) {
        var opened = false
        var planHealthy = false
    }

    private var target: Target? = null
    private var conn: Conn? = null
    private var seq = 0
    private var retryToken = 0
    private var backoff = SocketBackoff()
    private val liveness = Liveness()

    /** The cached ip failed the host gate: nothing is dialled for this target. */
    private var gateRefused = false

    @Volatile
    private var healthyNow = false

    override val healthy: Boolean get() = healthyNow

    fun parkedUntil(uid: String): Long? = parks.parkedUntil(uid, clock())

    /** Open, re-target or close (null). A change of the uid or of the Wi-Fi network closes the old one first. */
    fun want(next: Target?) {
        if (next == target) return
        target = next
        close("re-target")
        backoff = SocketBackoff()
        gateRefused = false
        retryToken++
        if (next != null) connect()
    }

    private fun connect() {
        val t = target ?: return
        if (conn != null || gateRefused) return
        val now = clock()
        parks.parkedUntil(t.uid, now)?.let {
            log("sonos socket: ${t.uid} parked for ${(it - now) / 60_000} min - polling only")
            return
        }
        val ip = resolveIp(t.uid)
        val url = ip?.let(::socketUrl)
        if (url == null || !socketGate(url, ip)) {
            // a public or unknown address is never dialled (design 53 §2.2): polling only, until a new target
            gateRefused = true
            log("sonos socket: ${t.uid} has no private ip literal in the cache - not dialled")
            return
        }
        val id = ++seq
        val request = Request.Builder()
            .url(url)
            .header("X-Sonos-Api-Key", SONOS_WS_API_KEY)
            .header("Sec-WebSocket-Protocol", SONOS_WS_PROTOCOL)
            .build()
        val ws = try {
            clientFor(ip).newWebSocket(request, Listener(id, t.uid, ip))
        } catch (e: Exception) {
            log("sonos socket: no client (${e.javaClass.simpleName})")
            return retry(t, SocketFailure.OTHER)
        }
        conn = Conn(id, ws, t.uid)
    }

    /** Closes the connection we have (not a failure: nothing is counted). */
    private fun close(why: String) {
        val c = conn ?: return
        conn = null
        liveness.clear()
        c.ws.cancel()
        log("sonos socket: closed ($why)")
        health()
    }

    private fun retry(t: Target, why: SocketFailure) {
        val delay = backoff.failed(why)
        if (delay == null) {
            parks.park(t.uid, clock() + SocketBackoff.PARK_MS)
            log("sonos socket: ${t.uid} parked for 6 h ($why) - polling only")
            return
        }
        val token = ++retryToken
        main.postDelayed(delay) { if (token == retryToken && target == t) connect() }
    }

    private fun health() {
        val c = conn
        val h = c != null && c.opened && c.planHealthy
        if (h == healthyNow) return
        healthyNow = h
        log("sonos socket: ${if (h) "healthy" else "not healthy"}")
        onEvent(SocketEvent.Health(h))
    }

    // -- from the listener, on the main thread (a stale connection's word is dropped) --

    private fun opened(id: Int) {
        val c = conn?.takeIf { it.id == id } ?: return
        c.opened = true
        backoff.opened()
        log("sonos socket: open to ${c.uid}")
    }

    private fun planHealth(id: Int, healthy: Boolean) {
        val c = conn?.takeIf { it.id == id } ?: return
        c.planHealthy = healthy
        health()
    }

    private fun event(id: Int, e: SocketEvent) {
        if (conn?.id != id) return
        if (e is SocketEvent.Playback && liveness.seen(e.at) && healthyNow) backoff.passed()
        onEvent(e)
    }

    private fun ended(id: Int, why: SocketFailure, detail: String) {
        val c = conn?.takeIf { it.id == id } ?: return
        conn = null
        liveness.clear()
        log("sonos socket: ended ($why: $detail)")
        health()
        target?.takeIf { it.uid == c.uid }?.let { retry(it, why) }
    }

    /** The liveness miss, the disagreement: closed and counted as a failure. */
    private fun dead(why: String) {
        val c = conn ?: return
        c.ws.cancel()
        ended(c.id, SocketFailure.OTHER, why)
    }

    // -- the controller's hooks (main) --

    override fun owed(since: Long, answeredAt: Long) {
        if (!healthyNow) return
        liveness.owe(since, answeredAt)
        val id = conn?.id ?: return
        main.postDelayed(Liveness.LIVENESS_MS + 1) {
            if (conn?.id == id && liveness.missed(clock())) dead("no event for our verb within ${Liveness.LIVENESS_MS} ms")
        }
    }

    override fun disagreed() {
        if (healthyNow) dead("two polls disagree with its state")
    }

    /** OkHttp's callbacks run on its reader thread: parse there, post to main. */
    private inner class Listener(private val id: Int, uid: String, ip: String) : WebSocketListener() {
        private val plan = SonosEvents.Plan(uid, ip)
        private var lastHealthy = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post { opened(id) }
            for (c in plan.start()) webSocket.send(c)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val at = clock()
            val m = SonosEvents.parse(text) ?: return
            val step = plan.receive(m, at)
            for (c in step.commands) webSocket.send(c)
            for (e in step.events) main.post { event(id, e) }
            val h = plan.healthy
            if (h != lastHealthy) {
                lastHealthy = h
                main.post { planHealth(id, h) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { ended(id, SocketFailure.OTHER, "closed $code") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = when {
                response != null -> SocketFailure.HTTP
                t is ConnectException -> SocketFailure.REFUSED
                else -> SocketFailure.OTHER
            }
            val detail = response?.let { "HTTP ${it.code}" } ?: t.javaClass.simpleName
            main.post { ended(id, why, detail) }
        }
    }
}
