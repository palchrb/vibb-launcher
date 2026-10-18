package com.kidslauncher.mdm.server

import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.dto.DnsUpstreamReport
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Type

private const val LOG_TAG = "CustomDns"

/**
 * The parent's own DNS server and its backup at work (design 36 §5), for one [config] while
 * [KidVpnService] runs: the chooser's state (one [AtomicReference], changed by CAS through the pure
 * [UpstreamChooser]), the canary, [BootstrapDns] and the DoH client. A new config is a new instance
 * (fresh state, fresh client); the service [close]s the old one.
 *
 * Every query reaches this only after the phone's own lists let it through. The backup answers only
 * while the primary as a whole is unhealthy; a failing backup drops the query (no third tier).
 * Logs name modes, reasons, kinds and exception classes, never a server.
 */
class CustomDns(
    val config: CustomUpstream,
    private val protect: (Socket) -> Boolean,
    private val scope: CoroutineScope,
    /** Elapsed realtime; a test's clock otherwise. */
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
    /** One attempt at a target: DoH or DoT ([attempt]); a test's fake otherwise. */
    transport: ((UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt)? = null,
) {
    private val state = AtomicReference(ChooserState(sinceMs = elapsed()))
    private val canaryInFlight = AtomicReference<CompletableFuture<CanaryVerdict>?>(null)
    private val resolver = bootstrapResolver(config)
    private val bootstrap = BootstrapDns(elapsed) { host -> scope.launch { lookUp(host) } }
    private val client = buildDohClient(bootstrap, protect)
    private val transport = transport ?: ::attempt

    /** The chooser's state, for tests. */
    internal fun state(): ChooserState = state.get()

    /** Looks both names up now, so the backup's address is in hand before it is needed. */
    fun start() {
        bootstrap.addresses(config.primary.serverHost)
        bootstrap.addresses(config.backup.serverHost)
    }

    /** The answer to relay for an allowed query, or `null` to drop it. Never throws. */
    fun resolve(query: ByteArray, request: Message): ByteArray? = try {
        resolveChecked(query, request)
    } catch (e: Exception) {
        log("Query failed: ${e.javaClass.simpleName}")
        null
    }

    private fun resolveChecked(query: ByteArray, request: Message): ByteArray? {
        val (route, gen) = route(elapsed())
        if (route == UpstreamRoute.BACKUP) return backup(query)
        val addresses = bootstrap.addresses(config.primary.serverHost)
        if (addresses.isEmpty()) {
            // Not looked up yet (the look-up runs): this query goes to the backup, no failure.
            if (route == UpstreamRoute.PROBE) update { UpstreamChooser.releaseProbe(it, gen) }
            return backup(query)
        }
        var settled = false
        try {
            val outcome = transport(config.primary, addresses, query, UPSTREAM_BUDGET)
            when (outcome) {
                is Attempt.Answer -> {
                    update { UpstreamChooser.afterAnswer(it, gen, elapsed()) }
                    settled = true
                    return outcome.bytes
                }
                is Attempt.Transport -> {
                    update { UpstreamChooser.afterTransport(it, gen, elapsed(), outcome.reason) }
                    settled = true
                    return backup(query)
                }
                is Attempt.Odd -> {
                    val verdict = canary(addresses)
                    update { UpstreamChooser.afterOdd(it, gen, elapsed(), verdict) }
                    settled = true
                    if (!verdict.ok) return backup(query)
                    return when (oddAnswer(outcome.kind)) {
                        OddAnswer.BLOCKED -> blockedResponse(request)
                        OddAnswer.SERVFAIL -> servfailResponse(request)
                    }
                }
            }
        } finally {
            if (!settled && route == UpstreamRoute.PROBE) update { UpstreamChooser.releaseProbe(it, gen) }
        }
    }

    /** The backup's answer relayed as is (REFUSED/SERVFAIL included); a failure drops the query. */
    private fun backup(query: ByteArray): ByteArray? {
        val addresses = bootstrap.addresses(config.backup.serverHost)
        if (addresses.isEmpty()) return null
        return when (val outcome = transport(config.backup, addresses, query, UPSTREAM_BUDGET)) {
            is Attempt.Answer -> outcome.bytes
            is Attempt.Odd -> outcome.bytes
            is Attempt.Transport -> null
        }
    }

    private fun attempt(target: UpstreamTarget, addresses: List<InetAddress>, query: ByteArray, budget: Budget): Attempt =
        when (target) {
            is UpstreamTarget.Doh -> resolveDoh(client, target.url, query, budget)
            is UpstreamTarget.Dot -> resolveDoT(query, addresses, target.host, target.port, budget, protect)
        }

    /** The canary's verdict: a cached one younger than 60 s, else `. IN NS` to the primary, one in
     * flight at a time (the others wait for it). */
    private fun canary(addresses: List<InetAddress>): CanaryVerdict {
        state.get().let { s -> if (!UpstreamChooser.needsCanary(s, elapsed())) return s.canary!! }
        val mine = CompletableFuture<CanaryVerdict>()
        val running = canaryInFlight.compareAndExchange(null, mine)
        if (running != null) {
            return try {
                running.get(CANARY_BUDGET.totalMs + 1000L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                CanaryVerdict(elapsed(), ok = false, reason = BackupReason.TIMEOUT)
            }
        }
        var verdict = CanaryVerdict(elapsed(), ok = false, reason = BackupReason.TIMEOUT)
        try {
            val query = Message.newQuery(Record.newRecord(Name.root, Type.NS, DClass.IN)).toWire()
            verdict = canaryVerdict(transport(config.primary, addresses, query, CANARY_BUDGET), elapsed())
            update { UpstreamChooser.withCanary(it, verdict) }
            log("Canary: ok=${verdict.ok}, reason=${verdict.reason?.wire}")
        } finally {
            canaryInFlight.set(null)
            mine.complete(verdict)
        }
        return verdict
    }

    /** A look-up of one server's name (A, then AAAA) through the bootstrap resolver, in the
     * background. Nothing at all for the primary's name, with nothing cached, starts a period
     * (`bootstrap`); for the backup's, its queries stay dropped. */
    private fun lookUp(host: String) {
        val gen = state.get().gen
        val found = mutableListOf<InetAddress>()
        var ttl = Long.MAX_VALUE
        val via = resolver
        if (via != null) {
            for (type in intArrayOf(Type.A, Type.AAAA)) {
                val answer = try {
                    val qname = Name.fromString("$host.")
                    val query = Message.newQuery(Record.newRecord(qname, type, DClass.IN)).toWire()
                    val viaAddresses = bootstrap.addresses(via.serverHost)
                    (transport(via, viaAddresses, query, BOOTSTRAP_BUDGET) as? Attempt.Answer)
                        ?.let { addressesFromAnswer(Message(it.bytes), qname) }
                } catch (e: Exception) {
                    null
                }
                if (answer != null && answer.addresses.isNotEmpty()) {
                    found += answer.addresses
                    ttl = minOf(ttl, answer.ttlSeconds)
                }
            }
        }
        val now = elapsed()
        if (found.isNotEmpty()) {
            bootstrap.cache.put(host, found, ttl, now)
            return
        }
        bootstrap.cache.failed(host, now)
        val isPrimary = host == config.primary.serverHost
        log("Look-up gave nothing (primary=$isPrimary)")
        if (isPrimary && !bootstrap.cache.has(host)) {
            update { UpstreamChooser.afterTransport(it, gen, now, BackupReason.BOOTSTRAP) }
        }
    }

    /** A network reset (a new best network, or a captive portal logged in): the primary is tried
     * again at once, on fresh connections. */
    fun networkReset() {
        update { UpstreamChooser.reset(it, elapsed()) }
        bootstrap.cache.forgetFailures()
        thread(name = "dns-evict") { client.connectionPool.evictAll() }
    }

    /** The status report's `dns_upstream` (§4.2). */
    fun report(): DnsUpstreamReport = upstreamReport(state.get(), config.rev, elapsed(), System.currentTimeMillis())

    fun close() {
        thread(name = "dns-evict") { client.connectionPool.evictAll() }
    }

    private fun route(now: Long): Pair<UpstreamRoute, Long> {
        while (true) {
            val s = state.get()
            val (route, next) = UpstreamChooser.route(s, now)
            if (next == s || state.compareAndSet(s, next)) return route to next.gen
        }
    }

    private inline fun update(change: (ChooserState) -> ChooserState) {
        while (true) {
            val s = state.get()
            val next = change(s)
            if (next == s) return
            if (state.compareAndSet(s, next)) {
                if (next.backup != s.backup || next.failures != s.failures) {
                    val mode = if (next.backup) "backup" else "custom"
                    log("Upstream: $mode, reason=${next.reason?.wire}, failures=${next.failures}")
                }
                return
            }
        }
    }
}

/** The canary's answer as a verdict: ok = a NOERROR answer; REFUSED is `refused` (AdGuard Home's
 * answer to a client id it doesn't allow); a transport failure its reason; anything else `timeout`. */
fun canaryVerdict(outcome: Attempt, now: Long): CanaryVerdict = when (outcome) {
    is Attempt.Answer -> {
        val noError = runCatching { Message(outcome.bytes).rcode == Rcode.NOERROR }.getOrDefault(false)
        CanaryVerdict(now, noError, if (noError) null else BackupReason.TIMEOUT)
    }
    is Attempt.Transport -> CanaryVerdict(now, false, outcome.reason)
    is Attempt.Odd -> CanaryVerdict(now, false, if (outcome.kind == OddKind.REFUSED) BackupReason.REFUSED else BackupReason.TIMEOUT)
}
