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

    /** Our own VPN refused the socket ([NotProtectedException]: the VPN going down or replaced) - the
     * server was never tried, so nothing is counted against it: the query is dropped and no chooser or
     * [DotBlockedRule] state changes (qa-vpn-protect-code L3). */
    data object Unprotected : Attempt
}

/** [e] or one of its causes is a refused protect (OkHttp may wrap the factory's exception). */
fun unprotected(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is NotProtectedException }

/** Connect timeout and the whole attempt's budget (connect + handshake + answer), in ms. */
data class Budget(val connectMs: Int, val totalMs: Int)

/** A query to the parent's servers (§5.3): an answer inside the stub resolver's first ~5 s try. */
val UPSTREAM_BUDGET = Budget(1500, 2500)
/** The canary (§5.4). */
val CANARY_BUDGET = Budget(1500, 2000)
/** A bootstrap look-up (§5.3: 1 s connect, 1 s read). */
val BOOTSTRAP_BUDGET = Budget(1000, 2000)
/** Cloudflare/Quad9 over DoT: a 2.5 s connect (design 36 step 3, qa-36-s3-code M2 - before, 5 s), so a
 * connect a network drops still leaves [BACKUP_FLOOR_MS] of the query's [QUERY_DEADLINE_MS] for the
 * same provider's DoH; 10 s in all once connected, as before design 36. */
val PROVIDER_BUDGET = Budget(2500, 10_000)

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
 * (the certificate must name it). The next address is tried only while nothing was written. Every
 * socket goes through [protect] before it connects (the VPN's own sockets would otherwise loop into
 * it). With a [pool] (security review S-L9) a connection that answered is kept for the next query
 * ([DotPool]) instead of a fresh TCP + TLS connection per query. Logs name the exception class only,
 * never the server.
 */
fun resolveDoT(
    query: ByteArray,
    addresses: List<InetAddress>,
    host: String,
    port: Int,
    budget: Budget,
    protect: (Socket) -> Boolean,
    pool: DotPool? = null,
): Attempt {
    val ip = ipLiteral(host)
    return dotAttempt(query, addresses, budget, pool, { DotPool.Key(it, port, host) }) { address, deadline, marks ->
        openDot(address, host, ip, port, budget, deadline, marks, protect)
    }
}

/**
 * [resolveDoT]'s rules over any [open]: one deadline for the whole attempt ([DotDeadline], the TLS handshake
 * included), the next address only while nothing was written. Security review S-L9: a connection kept in [pool]
 * goes first; one the server closed while it was idle (EOF, a reset, a close_notify, an answer to another query)
 * is let go with every other kept for that server, and the query is asked again on a fresh connection - a DNS
 * query is safe to repeat, and nothing is counted against the server for it. A kept connection that stays
 * silent to the deadline is silence after the write, as on a fresh one. Only a connection that gave a whole,
 * parsable answer before the deadline is kept.
 */
internal fun dotAttempt(
    query: ByteArray,
    addresses: List<InetAddress>,
    budget: Budget,
    pool: DotPool?,
    keyOf: (InetAddress) -> DotPool.Key,
    log: (String) -> Unit = { Log.w(LOG_TAG, it) },
    open: (InetAddress, DotDeadline, CallMarks) -> DotLink,
): Attempt {
    if (addresses.isEmpty()) return Attempt.Transport(BackupReason.BOOTSTRAP)
    val deadline = System.nanoTime() + budget.totalMs * 1_000_000L
    fun remainingMs(): Int = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
    var outcome: Attempt = Attempt.Transport(BackupReason.CONNECT)
    for (address in addresses.take(2)) {
        if (remainingMs() <= 0) break
        val key = keyOf(address)
        val kept = pool?.take(key)
        if (pool != null && kept != null) {
            val watch = DotDeadline(remainingMs())
            watch.guard(kept)
            try {
                val attempt = classifyAnswer(kept.ask(query, ::remainingMs, CallMarks()), BackupReason.CONNECT)
                if (watch.end() && attempt !is Attempt.Transport) pool.give(key, kept) else kept.cut()
                return attempt
            } catch (e: Exception) {
                kept.cut()
                // Past the deadline - the watchdog's close, or the read's own timeout at it (the QA fix round's L1) - is
                // silence after the write, never a connection gone.
                if (!watch.end()) return afterWriteOutcome(java.net.SocketTimeoutException("DoT attempt past its deadline"))
                if (e is InterruptedIOException) return afterWriteOutcome(e)
                log("Kept DoT connection gone: ${e.javaClass.simpleName}")
                pool.drop(key)
            }
        }
        if (remainingMs() <= 0) break
        val marks = CallMarks()
        // Code QA L5: one deadline for the whole attempt, the TLS handshake included - at it the socket is closed, so a
        // server trickling its handshake can't hold the query past the budget either (a close then reads as a timeout).
        val watch = DotDeadline(remainingMs())
        var link: DotLink? = null
        try {
            link = open(address, watch, marks)
            val attempt = classifyAnswer(link.ask(query, ::remainingMs, marks), BackupReason.CONNECT)
            if (pool != null && watch.end() && attempt !is Attempt.Transport) pool.give(key, link) else link.cut()
            return attempt
        } catch (e: Exception) {
            link?.cut()
            log("DoT attempt failed: ${e.javaClass.simpleName}")
            if (unprotected(e)) return Attempt.Unprotected
            // The watchdog's close at the deadline is a timeout, whatever the socket threw for it.
            val failure = if (!watch.end()) java.net.SocketTimeoutException("DoT attempt past its deadline") else e
            if (marks.written) return afterWriteOutcome(failure)
            outcome = Attempt.Transport(transportReason(failure, marks.connected))
        } finally {
            watch.end()
        }
    }
    return outcome
}

/** One DoT connection: [ask] writes a query and reads its answer before the deadline, [cut] closes it at once (the
 * deadline's watchdog, a connection let go). */
internal interface DotLink : java.io.Closeable {
    /** The answer's bytes; marks [CallMarks.written] once the query is out. Throws on anything but a whole answer
     * to this query. */
    fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray

    fun cut()

    override fun close() = cut()
}

/** A TCP + TLS connection to [address] ([protectedSocket], connected and shaken hands within [deadline]). */
private fun openDot(
    address: InetAddress,
    host: String,
    ip: InetAddress?,
    port: Int,
    budget: Budget,
    deadline: DotDeadline,
    marks: CallMarks,
    protect: (Socket) -> Boolean,
): DotLink {
    val plain = protectedSocket(protect)
    deadline.guard(plain)
    try {
        plain.connect(InetSocketAddress(address, port), minOf(budget.connectMs, deadline.remainingMs()).coerceAtLeast(1))
        marks.connected = true
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(plain, ip?.hostAddress ?: host, port, true) as SSLSocket
        val params = tls.sslParameters
        if (ip == null) {
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName(host))
        } else {
            params.serverNames = emptyList()
        }
        tls.sslParameters = params
        tls.soTimeout = deadline.remainingMs().coerceAtLeast(1)
        tls.startHandshake()
        if (ip != null && !HttpsURLConnection.getDefaultHostnameVerifier().verify(ip.hostAddress, tls.session)) {
            throw SSLException("certificate doesn't name the address")
        }
        return TlsDotLink(plain, tls)
    } catch (e: Exception) {
        runCatching { plain.close() }
        throw e
    }
}

private class TlsDotLink(private val plain: Socket, private val tls: SSLSocket) : DotLink {
    override fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray {
        val out = tls.outputStream
        out.write(byteArrayOf((query.size shr 8).toByte(), (query.size and 0xFF).toByte()) + query)
        out.flush()
        marks.written = true
        val input = tls.inputStream
        // S-L9: one deadline for the whole answer - a server trickling a byte at a time can't hold the query past
        // the budget (the timeout no longer resets per read).
        val timeout = { ms: Int -> tls.soTimeout = ms }
        val len = ByteArray(2).also { readFully(input, it, remainingMs, timeout) }
        val resp = ByteArray(((len[0].toInt() and 0xFF) shl 8) or (len[1].toInt() and 0xFF))
        readFully(input, resp, remainingMs, timeout)
        if (!dotAnswers(query, resp)) throw IOException("not this query's answer")
        return resp
    }

    /** The plain socket first: closing it never waits on the peer, so the watchdog can't block. */
    override fun cut() {
        runCatching { plain.close() }
        runCatching { tls.close() }
    }
}

/** [answer] is a DNS message (a whole header) with [query]'s ID: a kept connection that gives anything else is out
 * of step and let go (S-L9). */
internal fun dotAnswers(query: ByteArray, answer: ByteArray): Boolean =
    query.size >= 2 && answer.size >= 12 && answer[0] == query[0] && answer[1] == query[1]

/**
 * One DoT attempt's deadline: at it whatever is [guard]ed is closed (a blocked connect, handshake or read then
 * throws) and [end] answers false. Scheduled on one daemon thread for every attempt.
 */
internal class DotDeadline(ms: Int) {
    private val endsAt = System.nanoTime() + ms.coerceAtLeast(1) * 1_000_000L
    private val expired = java.util.concurrent.atomic.AtomicBoolean(false)
    private val guarded = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>(null)
    private val future = DOT_WATCHDOG.schedule(
        { expired.set(true); guarded.get()?.let { runCatching { it.close() } } },
        ms.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS,
    )

    fun remainingMs(): Int = ((endsAt - System.nanoTime()) / 1_000_000L).toInt()

    /** Closed at the deadline - at once when it has passed. */
    fun guard(c: java.io.Closeable) {
        guarded.set(c)
        if (expired.get()) runCatching { c.close() }
    }

    /** Stops the watchdog; false when the deadline came first (what it guarded is closed). */
    fun end(): Boolean {
        future.cancel(false)
        return !expired.get()
    }
}

/**
 * The DoT connections kept for the next query (security review S-L9), one pool per [KidVpnService] instance: a
 * connection is taken by one query at a time (no pipelining), given back only after a whole answer, kept at most
 * [idleMs] idle (below the shortest server idle timeouts in use, e.g. Knot Resolver's 10 s - one past it costs a
 * retry, [dotAttempt]) and at most [maxIdle] per server; the newest goes first. [evictAll] on a network change,
 * [close] at the VPN's stop. A connection was protected by the service that made it, so a pool never outlives it.
 */
class DotPool(
    private val idleMs: Long = DOT_IDLE_MS,
    private val maxIdle: Int = DOT_MAX_IDLE,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** One server: an address, its port and the name its certificate is checked against. */
    data class Key(val address: InetAddress, val port: Int, val host: String)

    private class Kept(val link: DotLink, val sinceMs: Long)

    private val idle = HashMap<Key, ArrayDeque<Kept>>()
    private var closed = false

    /** The newest connection kept for [key] that hasn't idled past [idleMs], or null; older ones are closed. */
    internal fun take(key: Key): DotLink? {
        val stale = mutableListOf<DotLink>()
        val found = synchronized(this) {
            val queue = idle[key] ?: return@synchronized null
            var found: DotLink? = null
            while (found == null) {
                val kept = queue.removeLastOrNull() ?: break
                if (nowMs() - kept.sinceMs < idleMs) found = kept.link else stale += kept.link
            }
            if (queue.isEmpty()) idle.remove(key)
            found
        }
        stale.forEach(DotLink::cut)
        return found
    }

    /** [link] kept for [key]; closed instead once the pool is closed, and the oldest past [maxIdle]. Every server's
     * connections idle past [idleMs] are closed too (the QA fix round's L4: a server no query asks any more - another
     * provider, a rotated address - keeps none open). */
    internal fun give(key: Key, link: DotLink) {
        val over = synchronized(this) {
            if (closed) return@synchronized listOf(link)
            val now = nowMs()
            val gone = mutableListOf<DotLink>()
            val each = idle.entries.iterator()
            while (each.hasNext()) {
                val queue = each.next().value
                while (queue.isNotEmpty() && now - queue.first().sinceMs >= idleMs) gone += queue.removeFirst().link
                if (queue.isEmpty()) each.remove()
            }
            val queue = idle.getOrPut(key) { ArrayDeque() }
            queue.addLast(Kept(link, now))
            while (queue.size > maxIdle) gone += queue.removeFirst().link
            gone
        }
        over.forEach(DotLink::cut)
    }

    /** Every connection kept for [key] closed (one turned out gone: the server likely closed the rest too). */
    internal fun drop(key: Key) {
        val gone = synchronized(this) { idle.remove(key)?.map { it.link }.orEmpty() }
        gone.forEach(DotLink::cut)
    }

    /** Every kept connection closed (a network change: they ran over the old one). */
    fun evictAll() {
        val gone = synchronized(this) { idle.values.flatMap { q -> q.map { it.link } }.also { idle.clear() } }
        gone.forEach(DotLink::cut)
    }

    /** [evictAll], and nothing is kept from now on. */
    fun close() {
        synchronized(this) { closed = true }
        evictAll()
    }

    /** How many are kept, for tests. */
    internal fun size(): Int = synchronized(this) { idle.values.sumOf { it.size } }
}

/** How long a DoT connection is kept idle ([DotPool]). */
const val DOT_IDLE_MS = 10_000L
/** At most this many idle DoT connections per server ([DotPool]). */
const val DOT_MAX_IDLE = 4

/** Closes a DoT attempt's socket at its deadline ([DotDeadline]); one daemon thread for every attempt. */
private val DOT_WATCHDOG: java.util.concurrent.ScheduledThreadPoolExecutor =
    java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "dot-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

/**
 * [buf] filled from [input] before the deadline (security review S-L9): before every read the socket's timeout is set
 * to what is left ([remainingMs] -> [setTimeoutMs]); none left is a [java.net.SocketTimeoutException], however many
 * bytes the server keeps trickling.
 */
internal fun readFully(input: InputStream, buf: ByteArray, remainingMs: () -> Int, setTimeoutMs: (Int) -> Unit) {
    var offset = 0
    while (offset < buf.size) {
        val left = remainingMs()
        if (left <= 0) throw java.net.SocketTimeoutException("DoT answer past its deadline")
        setTimeoutMs(left)
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

/** [VpnService.protect][android.net.VpnService.protect] refused a socket (logged by its class name). */
class NotProtectedException : IOException("not protected")

/**
 * A new, unconnected socket that [protect] accepted, bound first to [local] (null: any address, an
 * ephemeral port). The bind is what gives it a file descriptor: Android's `Socket()` creates none
 * until a bind or connect, and `VpnService.protect(Socket)` reads the descriptor as it is (-1), so
 * it returns false for a fresh `Socket()` every time - launcher 0.29.0 refused every upstream
 * socket and the VPN answered no query (design 36's "protect needs a descriptor" rule,
 * `ProtectedSocketTest`). Closed when it can't be protected.
 */
fun protectedSocket(protect: (Socket) -> Boolean, local: InetSocketAddress? = null): Socket {
    val socket = Socket()
    try {
        socket.bind(local)
        if (!protect(socket)) throw NotProtectedException()
    } catch (e: Exception) {
        socket.close()
        throw e
    }
    return socket
}

/** A plain socket factory whose sockets are [protect]ed before they connect ([protectedSocket]):
 * OkHttp 4.12 takes the no-argument [createSocket] for a direct route and layers TLS on it, so the
 * whole connection is outside the VPN. The connecting overloads create, protect, then connect. */
class ProtectedSocketFactory(private val protect: (Socket) -> Boolean) : SocketFactory() {
    override fun createSocket(): Socket = protectedSocket(protect)
    override fun createSocket(host: String, port: Int): Socket =
        connected(protectedSocket(protect), InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(protectedSocket(protect, InetSocketAddress(localHost, localPort)), InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(protectedSocket(protect), InetSocketAddress(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connected(protectedSocket(protect, InetSocketAddress(localAddress, localPort)), InetSocketAddress(address, port))

    /** [socket] connected to [to], or closed when the connect throws (qa-vpn-protect-code L2). */
    private fun connected(socket: Socket, to: InetSocketAddress): Socket {
        try {
            socket.connect(to)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }
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

/** One attempt at [target] - DoH through [client] (asked for at the first DoH use), DoT through [protect]ed sockets to its [addresses],
 * kept in [dotPool] (S-L9; the transport [CustomDns] and [PublicResolver] are given). */
fun targetTransport(
    client: () -> OkHttpClient,
    protect: (Socket) -> Boolean,
    dotPool: DotPool? = null,
): (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt =
    { target, addresses, query, budget ->
        when (target) {
            is UpstreamTarget.Doh -> resolveDoh(client(), target.url, query, budget)
            is UpstreamTarget.Dot -> resolveDoT(query, addresses, target.host, target.port, budget, protect, dotPool)
        }
    }

/** The public resolvers' DoH client's [okhttp3.Dns] (design 36 step 3): their URLs are IP literals,
 * which OkHttp never looks up; anything else has no address (never the system resolver). */
val IP_LITERAL_DNS: okhttp3.Dns = object : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOfNotNull(ipLiteral(hostname)).ifEmpty { throw java.net.UnknownHostException("not an IP literal") }
}

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
            unprotected(e) -> Attempt.Unprotected
            e is java.net.UnknownHostException -> Attempt.Transport(BackupReason.BOOTSTRAP)
            marks.written -> afterWriteOutcome(e)
            else -> Attempt.Transport(transportReason(e, marks.connected))
        }
    }
}
