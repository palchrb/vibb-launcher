package com.kidslauncher.mdm.server

import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode

private const val LOG_TAG = "DnsUpstream"

/** One attempt at a DNS server (design 36 §5.4). */
sealed interface Attempt {
    /** A DNS answer other than REFUSED/SERVFAIL - relayed. */
    class Answer(val bytes: ByteArray) : Attempt

    /** Failed below DNS: no address, connect, TLS, HTTP, a stream closed or silent before the
     * request was written. */
    data class Transport(val reason: BackupReason) : Attempt

    /** REFUSED or SERVFAIL ([bytes] the server's answer), or silence after the request was written. */
    class Odd(val kind: OddKind, val bytes: ByteArray?) : Attempt
}

/** Connect timeout and the whole attempt's budget (connect + handshake + answer), in ms. */
data class Budget(val connectMs: Int, val totalMs: Int)

/** A query to the parent's servers (§5.3): an answer inside the stub resolver's first ~5 s try. */
val UPSTREAM_BUDGET = Budget(1500, 2500)
/** The canary (§5.4). */
val CANARY_BUDGET = Budget(1500, 2000)
/** A bootstrap look-up (§5.3: 1 s connect, 1 s read). */
val BOOTSTRAP_BUDGET = Budget(1000, 2000)
/** Today's Cloudflare/Quad9 forwarding, as before design 36 (5 s connect, 5 s reads). */
val PROVIDER_BUDGET = Budget(5000, 10_000)

/** [bytes] as a DNS answer: parsable, then by its rcode. Garbage is a transport failure for [garbage]. */
fun classifyAnswer(bytes: ByteArray, garbage: BackupReason): Attempt {
    val rcode = try {
        Message(bytes).rcode
    } catch (e: Exception) {
        return Attempt.Transport(garbage)
    }
    return when (rcode) {
        Rcode.REFUSED -> Attempt.Odd(OddKind.REFUSED, bytes)
        Rcode.SERVFAIL -> Attempt.Odd(OddKind.SERVFAIL, bytes)
        else -> Attempt.Answer(bytes)
    }
}

/** A failure before the request was written: a TLS failure, a connect that never completed, or
 * (the TCP connection up) a timeout or a closed stream. */
fun transportReason(e: Throwable, connected: Boolean): BackupReason = when {
    generateSequence(e) { it.cause }.any { it is SSLException } -> BackupReason.TLS
    !connected -> BackupReason.CONNECT
    e is InterruptedIOException -> BackupReason.TIMEOUT
    else -> BackupReason.CONNECT
}

/** An exception after the request was written: silence is odd (the canary decides), anything else
 * (the stream closed, a reset) a transport failure. */
fun afterWriteOutcome(e: Throwable): Attempt =
    if (e is InterruptedIOException) Attempt.Odd(OddKind.TIMEOUT, null) else Attempt.Transport(BackupReason.CONNECT)

/**
 * DNS-over-TLS (RFC 7858: a 2-byte length, then the message) to [addresses] (at most two, IPv4
 * first) on [port], within one [budget]. A [host] name gets SNI and endpoint identification on it;
 * an IP literal no SNI (RFC 6066) and the default hostname verifier on the IP after the handshake
 * (the certificate must name it). The next address is tried only while nothing was written. A fresh
 * connection per query; every socket goes through [protect] before it connects (the VPN's own
 * sockets would otherwise loop into it). Logs name the exception class only, never the server.
 */
fun resolveDoT(
    query: ByteArray,
    addresses: List<InetAddress>,
    host: String,
    port: Int,
    budget: Budget,
    protect: (Socket) -> Boolean,
): Attempt {
    if (addresses.isEmpty()) return Attempt.Transport(BackupReason.BOOTSTRAP)
    val deadline = System.nanoTime() + budget.totalMs * 1_000_000L
    fun remainingMs(): Int = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
    val ip = ipLiteral(host)
    var outcome: Attempt = Attempt.Transport(BackupReason.CONNECT)
    for (address in addresses.take(2)) {
        if (remainingMs() <= 0) break
        var connected = false
        var written = false
        try {
            Socket().use { plain ->
                if (!protect(plain)) throw IOException("not protected")
                plain.connect(InetSocketAddress(address, port), minOf(budget.connectMs, remainingMs()).coerceAtLeast(1))
                connected = true
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(plain, ip?.hostAddress ?: host, port, true) as SSLSocket).use { tls ->
                    val params = tls.sslParameters
                    if (ip == null) {
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        params.serverNames = listOf(SNIHostName(host))
                    } else {
                        params.serverNames = emptyList()
                    }
                    tls.sslParameters = params
                    tls.soTimeout = remainingMs().coerceAtLeast(1)
                    tls.startHandshake()
                    if (ip != null && !HttpsURLConnection.getDefaultHostnameVerifier().verify(ip.hostAddress, tls.session)) {
                        throw SSLException("certificate doesn't name the address")
                    }
                    val out = tls.outputStream
                    out.write(byteArrayOf((query.size shr 8).toByte(), (query.size and 0xFF).toByte()) + query)
                    out.flush()
                    written = true
                    tls.soTimeout = remainingMs().coerceAtLeast(1)
                    val input = tls.inputStream
                    val len = ByteArray(2).also { readFully(input, it) }
                    val resp = ByteArray(((len[0].toInt() and 0xFF) shl 8) or (len[1].toInt() and 0xFF))
                    readFully(input, resp)
                    return classifyAnswer(resp, BackupReason.CONNECT)
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "DoT attempt failed: ${e.javaClass.simpleName}")
            if (written) return afterWriteOutcome(e)
            outcome = Attempt.Transport(transportReason(e, connected))
        }
    }
    return outcome
}

private fun readFully(input: InputStream, buf: ByteArray) {
    var offset = 0
    while (offset < buf.size) {
        val n = input.read(buf, offset, buf.size - offset)
        if (n < 0) throw EOFException()
        offset += n
    }
}

/** `application/dns-message` (RFC 8484). */
val DNS_MESSAGE = "application/dns-message".toMediaType()
const val DOH_MAX_RESPONSE = 65535

/** The DoH request body: the query with ID 0 (RFC 8484 §4.1, cache-friendly). */
fun dohQuery(query: ByteArray): ByteArray = query.copyOf().also { if (it.size >= 2) { it[0] = 0; it[1] = 0 } }

/**
 * A DoH response, checked (§5.2): 200, media type `application/dns-message` (parameters ignored),
 * at most 65535 bytes, a parsable DNS message; the query's own ID put back. Anything else is
 * `http` (a 3xx included: redirects are never followed).
 */
fun dohResponse(code: Int, contentType: String?, body: ByteArray?, query: ByteArray): Attempt {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (code != 200 || type != "application/dns-message" || body == null || body.size > DOH_MAX_RESPONSE || body.size < 2) {
        return Attempt.Transport(BackupReason.HTTP)
    }
    val restored = body.copyOf().also { if (query.size >= 2) { it[0] = query[0]; it[1] = query[1] } }
    return classifyAnswer(restored, BackupReason.HTTP)
}

/** A plain socket factory whose sockets are [protect]ed before they connect: OkHttp 4.12 takes the
 * no-argument [createSocket] for a direct route and layers TLS on it, so the whole connection is
 * outside the VPN. The connecting overloads create, protect, then connect. */
class ProtectedSocketFactory(private val protect: (Socket) -> Boolean) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also { if (!protect(it)) { it.close(); throw IOException("not protected") } }
    override fun createSocket(host: String, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().also { it.bind(InetSocketAddress(localHost, localPort)); it.connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().also { it.bind(InetSocketAddress(localAddress, localPort)); it.connect(InetSocketAddress(address, port)) }
}

/** What one DoH call got to (set by [CallMarks.listener]): the TCP connection up, the request written. */
class CallMarks {
    @Volatile var connected = false
    @Volatile var written = false
}

/**
 * The DoH client (§5.2), one per [KidVpnService] instance and config. Built from a fresh
 * [OkHttpClient.Builder] - never `newBuilder()` of another client (the API and SSE clients carry
 * tsnet's SOCKS proxy) -, no proxy (a Wi-Fi's SOCKS proxy would connect outside the factory, an
 * HTTP proxy would carry the DoH), protected sockets, [dns] (never the system resolver), no cache,
 * no redirects, no cookies (the default), the system CAs and OkHttp's verifier.
 */
fun buildDohClient(dns: okhttp3.Dns, protect: (Socket) -> Boolean): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .socketFactory(ProtectedSocketFactory(protect))
    .dns(dns)
    .connectTimeout(UPSTREAM_BUDGET.connectMs.toLong(), TimeUnit.MILLISECONDS)
    .callTimeout(UPSTREAM_BUDGET.totalMs.toLong(), TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    // Each call's own connect timeout (a bootstrap look-up's 1 s, §5.3) from its Budget tag.
    .addInterceptor { chain ->
        val budget = chain.request().tag(Budget::class.java)
        if (budget == null) chain.proceed(chain.request())
        else chain.withConnectTimeout(budget.connectMs, TimeUnit.MILLISECONDS).proceed(chain.request())
    }
    .eventListenerFactory { call ->
        val marks = call.request().tag(CallMarks::class.java) ?: return@eventListenerFactory EventListener.NONE
        object : EventListener() {
            override fun secureConnectStart(call: Call) { marks.connected = true }
            override fun connectionAcquired(call: Call, connection: okhttp3.Connection) { marks.connected = true }
            override fun requestBodyEnd(call: Call, byteCount: Long) { marks.written = true }
        }
    }
    .build()

/** One DoH query: POST [url] with the query (ID 0), both media types `application/dns-message`, within [budget]. */
fun resolveDoh(client: OkHttpClient, url: String, query: ByteArray, budget: Budget): Attempt {
    val marks = CallMarks()
    val request = Request.Builder()
        .url(url)
        .header("Accept", DNS_MESSAGE.toString())
        .post(dohQuery(query).toRequestBody(DNS_MESSAGE))
        .tag(CallMarks::class.java, marks)
        .tag(Budget::class.java, budget)
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(budget.totalMs.toLong(), TimeUnit.MILLISECONDS)
    return try {
        call.execute().use { response ->
            val body = response.body?.byteStream()?.readNBytes(DOH_MAX_RESPONSE + 1)
            dohResponse(response.code, response.header("Content-Type"), body, query)
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "DoH attempt failed: ${e.javaClass.simpleName}")
        when {
            e is java.net.UnknownHostException -> Attempt.Transport(BackupReason.BOOTSTRAP)
            marks.written -> afterWriteOutcome(e)
            else -> Attempt.Transport(transportReason(e, marks.connected))
        }
    }
}
