package com.kidslauncher.mdm.server

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory
import java.util.Collections
import java.util.WeakHashMap
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * How every server client reaches the server (security review S-M1): the API (`createMdmApi`), the command stream
 * (`CommandListenerService`) and the music client (`createMusicServer`) all build their OkHttp client through
 * [serverPath] and [toServer] - since design 95 as children of the one base client [ServerClients] keeps per path.
 * There is no fallback between the paths:
 * - `https://` goes direct, or through tsnet's SOCKS proxy while it runs (as before);
 * - `http://<tailnet IP>` goes only through tsnet's proxy; with tsnet down no request is made ([ServerPath.Closed]);
 * - anything else is never asked ([ServerUrlVerdict.REFUSED]): a phone set up before the rule with a plain-http
 *   LAN address stops talking to the server until the parent types the https address in Settings (behind the parent
 *   code) or scans a new setup QR.
 *
 * Cleartext (the manifest's network security config): off for the whole app, with one exception - the name
 * [TAILNET_SERVER_HOST]. A network security config names hosts, never address ranges, so the tailnet path gives OkHttp
 * that name instead of the IP: OkHttp checks cleartext against the URL's host, and its [Dns] for this client alone
 * answers the name with the tailnet address, and its sockets are SOCKS sockets to tsnet's proxy on loopback (the
 * proxy hop is a plain socket, never an HTTP request, so no loopback exception is needed). The name is under
 * `.invalid` (RFC 6761) and no client but the tailnet one asks for it (the others use the system resolver, which
 * would forward the query to the network's DNS - a hostile resolver could answer it, but nothing of ours asks).
 *
 * The proxy itself (`mobile/tsembed`, dial.go) dials a Tailscale address only through netstack and only while it is a
 * current peer - never on the phone's own network (the security QA of S-M1, H1).
 *
 * Design 76 narrows https's trust on the [ServerPath.Direct] path ([ServerPinning], [ServerPins.kt]): a request to
 * any other origin is refused before a socket and no redirect is followed; once the server has switched the check on,
 * OkHttp's pinner wants a key of the built-in set in the chain Android validated (after Android's validation, never
 * instead of it); either way every new connection's chain is looked at and a chain outside the set is kept as a
 * sighting for the server. The tailnet path is never pinned (WireGuard authenticates the peer), nor a debug build's
 * plain http to the emulator's host (no TLS).
 */

/** The name OkHttp sees for a server at a tailnet IP; see the file's comment. */
const val TAILNET_SERVER_HOST = "tailnet-server.vibb.invalid"

/** The Android emulator's address for its host - taken over plain http by a debug build only (the emulator loop,
 * `docs/testing/emulator.md`); the debug network security config allows cleartext to it. */
const val EMULATOR_HOST = "10.0.2.2"

/** Why a server client sends nothing. */
enum class ClosedWhy {
    /** The stored address is neither https nor http to a tailnet IP. */
    NOT_HTTPS,

    /** http to a tailnet IP while tsnet's proxy isn't running. */
    TAILNET_DOWN,
}

/** The way to the server now; [baseUrl] (ending in `/`) is what a client of it uses. */
sealed interface ServerPath {
    val baseUrl: String

    /** `https://` (or a debug build's `http://10.0.2.2`); [proxy] is tsnet's while it runs. */
    data class Direct(override val baseUrl: String, val proxy: Proxy?) : ServerPath

    /** `http://<tailnet IP>`: [baseUrl] names [TAILNET_SERVER_HOST]; requests carry [hostHeader] and go to
     * [address] through tsnet's [proxy] only. */
    data class Tailnet(override val baseUrl: String, val hostHeader: String, val address: InetAddress, val proxy: Proxy) : ServerPath

    /** No request is made. [baseUrl] only because Retrofit needs one: the client refuses before it is used. */
    data class Closed(val why: ClosedWhy) : ServerPath {
        override val baseUrl: String get() = "http://closed.vibb.invalid/"
    }
}

/** Thrown by a [ServerPath.Closed] client before anything leaves the phone. */
class ServerPathClosed(val why: ClosedWhy) : IOException(
    when (why) {
        ClosedWhy.NOT_HTTPS -> "the server address isn't https (plain http only to a Tailscale IP)"
        ClosedWhy.TAILNET_DOWN -> "the server is at a tailnet address and the tailnet isn't connected"
    },
)

/** Thrown by a [ServerPath.Direct] client for a request to another origin than the server's (design 76, qa H-2):
 * before a socket, so an `@Url` the server named can't reach another host unpinned. */
class ServerOriginRefused(host: String) : IOException("not the server's origin: $host")

/**
 * What a [ServerPath.Direct] client pins (design 76 §3): [enforce] is the stored flag ([ServerPinStore]), [pins] the
 * built-in set's keys (a test's own set in `ServerTransportTest`), [onOutside] where a sighting outside them goes -
 * `refused` (the pinner refused it) or `seen` (the flag off) with the request's host and the chain's anchor.
 */
class ServerPinning(
    val enforce: Boolean,
    val pins: List<String> = BUILTIN_PINS.map { it.pin },
    val onOutside: (kind: String, host: String, anchor: Anchor) -> Unit = { _, _, _ -> },
)

/** The pinner for [host] (exactly `HttpUrl.host`, the string OkHttp compares - qa I-1) and [pins]; `null` when it
 * can't be built or pins nothing for [host] - the caller then fails closed (qa L-4). */
fun serverPinner(host: String, pins: List<String>): CertificatePinner? = try {
    CertificatePinner.Builder().add(host, *pins.map { "sha256/$it" }.toTypedArray()).build()
        .takeIf { it.findMatchingPins(host).isNotEmpty() }
} catch (e: IllegalArgumentException) {
    null
}

/** Whether [url] may be stored as the server address: the rule ([serverUrlVerdict]), or in a [debug] build the
 * emulator's host over http. */
fun serverUrlUsable(url: String?, debug: Boolean): Boolean =
    serverUrlVerdict(url) != ServerUrlVerdict.REFUSED || (debug && emulatorUrl(parseServerUrl(url)))

private fun emulatorUrl(parts: ServerUrlParts?): Boolean = parts != null && !parts.https && parts.host == EMULATOR_HOST

/** The way to [serverUrl] with tsnet's [proxy] (null = not running); pure. */
fun serverPath(serverUrl: String, proxy: Proxy?, debug: Boolean): ServerPath {
    val parts = parseServerUrl(serverUrl)
    val base = serverUrl.trim(' ', '\t', '\n', '\u000c', '\r').let { if (it.endsWith("/")) it else "$it/" }
    return when (serverUrlVerdict(serverUrl)) {
        ServerUrlVerdict.HTTPS -> ServerPath.Direct(base, proxy)
        ServerUrlVerdict.TAILNET_HTTP -> {
            checkNotNull(parts)
            if (proxy == null) return ServerPath.Closed(ClosedWhy.TAILNET_DOWN)
            val port = parts.port?.let { ":$it" }.orEmpty()
            val path = parts.path.ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
            val host = if (parts.ip!!.size == 16) "[${parts.host}]" else parts.host
            ServerPath.Tailnet(
                baseUrl = "http://$TAILNET_SERVER_HOST$port$path",
                hostHeader = "$host$port",
                address = InetAddress.getByAddress(parts.ip),
                proxy = proxy,
            )
        }
        ServerUrlVerdict.REFUSED ->
            if (debug && emulatorUrl(parts)) ServerPath.Direct(base, null) else ServerPath.Closed(ClosedWhy.NOT_HTTPS)
    }
}

/** The last closed path's reason logged (null: open), so the stream's 30 s retries log a change only. */
@Volatile
private var lastClosedLogged: ClosedWhy? = null

/** [serverPath] for the stored address now: tsnet's proxy as it is, this build's debug flag. */
fun serverPathNow(serverUrl: String): ServerPath {
    val path = serverPath(serverUrl, TsnetClient.proxy(), com.kidslauncher.mdm.BuildConfig.DEBUG)
    val closed = (path as? ServerPath.Closed)?.why
    if (closed != lastClosedLogged) {
        lastClosedLogged = closed
        if (closed != null) Log.w("ServerTransport", "No request to the server: ${ServerPathClosed(closed).message}")
    }
    return path
}

/**
 * Sets this builder up for [path] - call it before adding any other interceptor, so a closed path's refusal runs
 * first and nothing else (the bearer included) sees the request. [pinning] is the certificate check's now (design 76:
 * the flag is read where the path is read). [currentProxy] is tsnet's proxy at connect time: a
 * tailnet socket refuses when it is no longer the one the path was made with (a client kept across a tsnet restart,
 * whose old loopback port any app could bind - the security QA of S-M1, L1).
 */
fun OkHttpClient.Builder.toServer(
    path: ServerPath,
    pinning: ServerPinning = ServerPinStore.pinningNow(),
    currentProxy: () -> Proxy? = { TsnetClient.proxy() },
): OkHttpClient.Builder = when (path) {
    is ServerPath.Direct -> direct(path, pinning)
    is ServerPath.Tailnet -> proxy(Proxy.NO_PROXY)
        .dns(TailnetServerDns(path.address))
        .socketFactory(SocksSocketFactory(path.proxy, currentProxy))
        // The API never redirects; a redirect off the tailnet name has nowhere to go anyway.
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.url.host == TAILNET_SERVER_HOST) {
                chain.proceed(request.newBuilder().header("Host", path.hostHeader).build())
            } else {
                chain.proceed(request)
            }
        })
    is ServerPath.Closed -> proxy(Proxy.NO_PROXY)
        .dns(NoDns)
        .addInterceptor(Interceptor { throw ServerPathClosed(path.why) })
}

/**
 * One base client per server path (design 95 §3.5, step 95-T1): the sync's API client (`createMdmApi`), the command
 * stream's ([CommandListenerService]), the music client (`createMusicServer`) and the shutdown's prepared client
 * ([PowerEvents]) are `newBuilder()` children of [base] with their own timeouts and interceptors, so they share one
 * connection pool and one TLS context: behind an HTTP/2 proxy (Caddy) a sync rides the stream's connection, and on
 * HTTP/1.1 a new connection resumes TLS instead of a full handshake. Before, every `createMdmApi` built its own client.
 *
 * The base is [toServer] for the path and the certificate check's flag and pins now, built again (and the old pool's
 * idle connections closed) when any of them changes - a tsnet restart's new proxy, Leave Tailnet, a new address, the
 * flag switched. Each child adds its own interceptors after the base's, so a closed path's refusal, the origin fence
 * and the pinner still run first. The DNS filter's DoH/DoT clients are never children of this (`buildDohClient`: they
 * must not carry tsnet's proxy).
 */
object ServerClients {
    private data class Key(val path: ServerPath, val enforce: Boolean, val pins: List<String>)

    private class Slot(val key: Key, val client: OkHttpClient)

    private var slot: Slot? = null

    /** The base client for [path] under [pinning] (default: the stored flag now); the same instance while neither
     * changes. */
    fun base(path: ServerPath, pinning: ServerPinning = ServerPinStore.pinningNow()): OkHttpClient {
        val key = Key(path, pinning.enforce, pinning.pins)
        val replaced: OkHttpClient?
        val client: OkHttpClient
        synchronized(this) {
            slot?.takeIf { it.key == key }?.let { return it.client }
            replaced = slot?.client
            client = OkHttpClient.Builder().toServer(path, pinning).build()
            slot = Slot(key, client)
        }
        // Idle connections only: a call still in flight on the old client finishes on its connection.
        replaced?.connectionPool?.evictAll()
        return client
    }

    /**
     * The command stream dropped: its connection may be dead (a network change, a NAT that forgot it), and a sync
     * that picked it from the pool would wait out its read timeout - so the pool's idle connections go, and the next
     * request opens a fresh one. Costs nothing on the line (no keepalive pings).
     */
    fun streamDropped() {
        val client = synchronized(this) { slot?.client }
        client?.connectionPool?.evictAll()
    }
}

/** OkHttp 4.12's wait for the pong of the ping a timed-out HTTP/2 stream sends (`DEGRADED_PONG_TIMEOUT_NS`, 1 s), and a
 * margin: past it an unanswered ping marks the connection unhealthy and the pool never hands it out again. */
const val STALE_RETRY_PAUSE_MS = 1_200L

/** The requests [retryOnStaleConnection] may send twice: every GET, and the status report (a second copy of a report
 * is one more `device_status` row, as a report sent twice in a minute today). */
fun staleRetryable(request: Request): Boolean =
    request.method == "GET" || (request.method == "POST" && request.url.encodedPath.endsWith("/api/devices/status"))

/**
 * Design 95 §3.5 (code QA M1): a request that timed out reading on a **reused** connection is sent once more on a
 * fresh one. The shared pool's connection can be dead without anyone knowing - behind HTTP/2 it is the command
 * stream's, busy and never evicted, which a NAT or a Wi-Fi without a way out can drop silently; the stream only
 * notices after its read timeout (5 min). So: the pool's idle connections are evicted, the retry waits out
 * [STALE_RETRY_PAUSE_MS] (OkHttp's ping on the timed-out HTTP/2 connection goes unanswered and the connection is
 * dropped from the pool), and the request goes again. Only [retryable] requests, only once, and never one that
 * opened its own connection (a server that is just slow isn't asked twice). Costs nothing on the line, unlike a
 * `pingInterval`. Added last, after the bearer: the retry repeats only what comes after it (OkHttp's own chain).
 */
fun OkHttpClient.Builder.retryOnStaleConnection(
    pool: () -> okhttp3.ConnectionPool,
    retryable: (Request) -> Boolean = ::staleRetryable,
    pause: () -> Unit = { Thread.sleep(STALE_RETRY_PAUSE_MS) },
    log: (String) -> Unit = { Log.w("ServerTransport", it) },
): OkHttpClient.Builder {
    val connected: MutableMap<okhttp3.Call, Boolean> = Collections.synchronizedMap(WeakHashMap())
    eventListenerFactory { call ->
        object : okhttp3.EventListener() {
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: Proxy) {
                connected[call] = true
            }
        }
    }
    addInterceptor(Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: java.net.SocketTimeoutException) {
            if (!retryable(request) || connected[chain.call()] == true || chain.call().isCanceled()) throw e
            log("A reused connection timed out - once more on a fresh one")
            pool().evictAll()
            pause()
            chain.proceed(request)
        }
    })
    return this
}

/** The [ServerPath.Direct] client (design 76 §3), in this order: the origin fence, the refusal catch, the pinner once
 * the flag is on, the chain observer. */
private fun OkHttpClient.Builder.direct(path: ServerPath.Direct, pinning: ServerPinning): OkHttpClient.Builder {
    val origin = path.baseUrl.toHttpUrlOrNull()
    path.proxy?.let { proxy(it) }
    // No device route redirects (qa I-2); a redirect would leave the fence's check of the first hop.
    followRedirects(false)
    followSslRedirects(false)
    // 1. The origin fence: the server's scheme, host and port only (a base that doesn't parse: nothing).
    addInterceptor(Interceptor { chain ->
        val url = chain.request().url
        if (origin == null || !sameOrigin(origin, url)) throw ServerOriginRefused(url.host)
        chain.proceed(chain.request())
    })
    // 2. The refusal catch: OkHttp's pin failure (that prefix only, qa L-5) is kept as `refused` and told as ours.
    addInterceptor(Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            if (!isPinFailure(e)) throw e
            // A message of another shape still refuses; only a readable anchor is kept as a sighting.
            val anchor = pinFailureAnchor(e)
            anchor?.let { pinning.onOutside(OutsideRow.REFUSED, chain.request().url.host, it) }
            throw ServerPinRefused(anchor, e)
        }
    })
    // 3. The pinner, with the flag on: it runs in connectTls after Android's validation, before the connection is
    // pooled or a request byte is written. One that can't be built fails closed, never open.
    if (pinning.enforce && origin != null) {
        val pinner = serverPinner(origin.host, pinning.pins)
        if (pinner != null) certificatePinner(pinner) else addInterceptor(Interceptor { throw ServerPinRefused(null) })
    }
    // 4. The observer, always: each new connection's validated chain once; one outside the set is kept as `seen`
    // (with the flag on no such connection gets this far).
    val observed: MutableMap<Connection, Boolean> = Collections.synchronizedMap(WeakHashMap())
    addNetworkInterceptor(Interceptor { chain ->
        val connection = chain.connection()
        val handshake = connection?.handshake()
        if (connection != null && handshake != null && observed.put(connection, true) == null) {
            try {
                val certificates = handshake.peerCertificates
                if (!inSet(certificates.map(::spkiPin), pinning.pins)) {
                    chainAnchor(certificates)?.let { pinning.onOutside(OutsideRow.SEEN, chain.request().url.host, it) }
                }
            } catch (e: Exception) {
                Log.w("ServerTransport", "Couldn't read the server's chain: ${e.javaClass.simpleName}")
            }
        }
        chain.proceed(chain.request())
    })
    return this
}

/** Answers nothing (a closed path's client, which never gets this far). */
private object NoDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("server path closed")
}

/** Answers [TAILNET_SERVER_HOST] with the tailnet address and nothing else. */
private class TailnetServerDns(private val address: InetAddress) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname == TAILNET_SERVER_HOST) listOf(address) else throw UnknownHostException("not the tailnet server")
}

/** Sockets through tsnet's SOCKS5 proxy (its credential comes from the process's Authenticator, [TsnetClient]), only
 * while [proxy] is still [currentProxy]. */
private class SocksSocketFactory(private val proxy: Proxy, private val currentProxy: () -> Proxy?) : SocketFactory() {
    override fun createSocket(): Socket {
        if (currentProxy() != proxy) throw IOException("tsnet's proxy changed since this client was made")
        return Socket(proxy)
    }
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("OkHttp connects the socket itself")
}
