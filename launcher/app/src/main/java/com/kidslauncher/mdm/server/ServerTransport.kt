package com.kidslauncher.mdm.server

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/*
 * How every server client reaches the server (security review S-M1): the API (`createMdmApi`), the command stream
 * (`CommandListenerService`) and the music client (`createMusicServer`) all build their OkHttp client through
 * [serverPath] and [toServer]. There is no fallback between the paths:
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
 * `.invalid` (RFC 6761), which no resolver answers, so no other client can reach anything through the exception.
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
 * first and nothing else (the bearer included) sees the request.
 */
fun OkHttpClient.Builder.toServer(path: ServerPath): OkHttpClient.Builder = when (path) {
    is ServerPath.Direct -> apply { path.proxy?.let { proxy(it) } }
    is ServerPath.Tailnet -> proxy(Proxy.NO_PROXY)
        .dns(TailnetServerDns(path.address))
        .socketFactory(SocksSocketFactory(path.proxy))
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

/** Answers nothing (a closed path's client, which never gets this far). */
private object NoDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("server path closed")
}

/** Answers [TAILNET_SERVER_HOST] with the tailnet address and nothing else. */
private class TailnetServerDns(private val address: InetAddress) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname == TAILNET_SERVER_HOST) listOf(address) else throw UnknownHostException("not the tailnet server")
}

/** Sockets through tsnet's SOCKS5 proxy (its credential comes from the process's Authenticator, [TsnetClient]). */
private class SocksSocketFactory(private val proxy: Proxy) : SocketFactory() {
    override fun createSocket(): Socket = Socket(proxy)
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("OkHttp connects the socket itself")
}
