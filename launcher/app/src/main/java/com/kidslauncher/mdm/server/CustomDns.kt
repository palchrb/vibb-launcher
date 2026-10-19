package com.kidslauncher.mdm.server

import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.dto.DnsUpstreamReport
import java.io.File
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
    /** One attempt at a target: DoH or DoT ([targetTransport]); a test's fake otherwise. */
    transport: ((UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt)? = null,
    /** The bootstrap's last resolver, Cloudflare - DoT, or its DoH where 853 is blocked (step 3). */
    private val cloudflare: PublicResolver = PublicResolvers.cloudflare,
    /** The bootstrap's disk cache ([BootstrapDiskCache]); `null` = none (tests). */
    private val cacheFile: File? = null,
) {
    private val state = AtomicReference(ChooserState(sinceMs = elapsed()))
    private val canaryInFlight = AtomicReference<CompletableFuture<CanaryVerdict>?>(null)
    private val resolvers = bootstrapResolvers(config)
    private val bootstrap = BootstrapDns(elapsed) { host -> scope.launch { lookUp(host) } }
    private val client = buildDohClient(bootstrap, protect)
    private val transport = transport ?: targetTransport({ client }, protect)

    /** Set by [close]: a look-up still running for old servers writes no cache. */
    private var closed = false

    init {
        loadDiskCache()
    }

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
        val startedAt = elapsed()
        // One deadline for the whole query (design 36 §5.4): an answer inside the stub's first ~5 s try.
        val deadline = startedAt + QUERY_DEADLINE_MS
        val (route, gen) = route(startedAt)
        if (route == UpstreamRoute.BACKUP) return backup(query, deadline)
        val addresses = bootstrap.addresses(config.primary.serverHost)
        if (addresses.isEmpty()) {
            // Not looked up yet (the look-up runs): this query goes to the backup, no failure.
            if (route == UpstreamRoute.PROBE) update { UpstreamChooser.releaseProbe(it, gen) }
            return backup(query, deadline)
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
                    return backup(query, deadline)
                }
                // Our own VPN refused the socket: nothing counted, a probe given back (finally), dropped.
                Attempt.Unprotected -> return null
                is Attempt.Odd -> {
                    val verdict = canary(addresses, canaryBudget(deadline - elapsed()))
                    if (verdict != null) {
                        update { UpstreamChooser.afterOdd(it, gen, elapsed(), verdict) }
                        settled = true
                        if (!verdict.ok) return backup(query, deadline)
                    }
                    // A healthy canary - or one this query gave up waiting for, which changes nothing:
                    // the name's own outcome stands, never the backup.
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

    /** The backup's answer relayed as is (REFUSED/SERVFAIL included), within what is left of the
     * query's [deadline]; a failure drops the query. */
    private fun backup(query: ByteArray, deadline: Long): ByteArray? {
        val addresses = bootstrap.addresses(config.backup.serverHost)
        if (addresses.isEmpty()) return null
        return when (val outcome = transport(config.backup, addresses, query, backupBudget(deadline - elapsed()))) {
            is Attempt.Answer -> outcome.bytes
            is Attempt.Odd -> outcome.bytes
            is Attempt.Transport, Attempt.Unprotected -> null
        }
    }

    /**
     * The canary's verdict: a cached one younger than 60 s, else `. IN NS` to the primary within
     * [budget], one in flight at a time. A query that finds one running waits for it; `null` when it
     * gives up (then nothing changes for anyone, and its own name's outcome stands).
     */
    private fun canary(addresses: List<InetAddress>, budget: Budget): CanaryVerdict? {
        cachedCanary()?.let { return it }
        val mine = CompletableFuture<CanaryVerdict>()
        val running = canaryInFlight.compareAndExchange(null, mine)
        if (running != null) {
            return try {
                running.get(budget.totalMs + CANARY_WAIT_SLACK_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                null
            }
        }
        // Won the claim: a canary that finished just before it already decided.
        cachedCanary()?.let { cached ->
            canaryInFlight.set(null)
            mine.complete(cached)
            return cached
        }
        var verdict: CanaryVerdict? = CanaryVerdict(elapsed(), ok = false, reason = BackupReason.TIMEOUT)
        try {
            val query = Message.newQuery(Record.newRecord(Name.root, Type.NS, DClass.IN)).toWire()
            val outcome = transport(config.primary, addresses, query, budget)
            // Our own VPN refused the socket: no verdict, for us or a waiter (they give up: nothing changes).
            verdict = if (outcome == Attempt.Unprotected) null else canaryVerdict(outcome, elapsed())
            verdict?.let { v ->
                update { UpstreamChooser.withCanary(it, v) }
                log("Canary: ok=${v.ok}, reason=${v.reason?.wire}")
            }
        } finally {
            canaryInFlight.set(null)
            verdict?.let(mine::complete) ?: mine.completeExceptionally(NotProtectedException())
        }
        return verdict
    }

    private fun cachedCanary(): CanaryVerdict? =
        state.get().takeUnless { UpstreamChooser.needsCanary(it, elapsed()) }?.canary

    /**
     * A look-up of one server's name (A, then AAAA) in the background (§5.3). The bootstrap resolvers
     * are tried in order, the next one only when a step fails at the transport level (silence after
     * the request included) - an NXDOMAIN or an empty answer is an answer -, and a step that failed
     * for A is skipped for AAAA. Nothing at all for the primary's name, with nothing cached, starts
     * a period (`bootstrap`) unless one runs already; for the backup's, its queries stay dropped.
     */
    private fun lookUp(host: String) {
        val gen = state.get().gen
        val found = mutableListOf<InetAddress>()
        var ttl = Long.MAX_VALUE
        var step = 0
        for (type in intArrayOf(Type.A, Type.AAAA)) {
            val qname = try {
                Name.fromString("$host.")
            } catch (e: Exception) {
                break
            }
            val query = Message.newQuery(Record.newRecord(qname, type, DClass.IN)).toWire()
            while (step < resolvers.size) {
                val via = resolvers[step]
                val outcome = try {
                    if (via == cloudflare.dot) cloudflare.resolve(query, BOOTSTRAP_BUDGET, BOOTSTRAP_BUDGET, transport)
                    else transport(via, bootstrap.addresses(via.serverHost), query, BOOTSTRAP_BUDGET)
                } catch (e: Exception) {
                    Attempt.Transport(BackupReason.CONNECT)
                }
                if (outcome is Attempt.Transport || outcome == Attempt.Unprotected || (outcome is Attempt.Odd && outcome.kind == OddKind.TIMEOUT)) {
                    step++
                    continue
                }
                val answer = (outcome as? Attempt.Answer)
                    ?.let { runCatching { addressesFromAnswer(Message(it.bytes), qname) }.getOrNull() }
                if (answer != null && answer.addresses.isNotEmpty()) {
                    found += answer.addresses
                    ttl = minOf(ttl, answer.ttlSeconds)
                }
                break
            }
        }
        val now = elapsed()
        if (found.isNotEmpty()) {
            bootstrap.cache.put(host, found, ttl, now)
            saveDiskCache()
            return
        }
        bootstrap.cache.failed(host, now)
        val isPrimary = host == config.primary.serverHost
        log("Look-up gave nothing (primary=$isPrimary)")
        if (isPrimary && !bootstrap.cache.has(host)) {
            // A retry inside a period doesn't step the back-off again: the probe schedule does.
            update { if (it.backup) it else UpstreamChooser.afterTransport(it, gen, now, BackupReason.BOOTSTRAP) }
        }
    }

    /** The disk cache's entries for this config's names, before any look-up; a config with no names
     * keeps no file. Any failure is no cache (logged by class only). */
    private fun loadDiskCache() {
        val file = cacheFile ?: return
        val names = BootstrapDiskCache.namesOf(config)
        try {
            if (!BootstrapDiskCache.keptFor(config)) {
                file.delete()
                return
            }
            if (!file.exists()) return
            val text = file.readText()
            val entries = BootstrapDiskCache.decode(text, names, elapsed(), System.currentTimeMillis())
            for ((host, cached) in entries) bootstrap.cache.seed(host, cached)
            // Lines for other servers' names (an earlier config) or a malformed file: keep only these.
            if (entries.isEmpty()) {
                file.delete()
            } else if (BootstrapDiskCache.lineCount(text) != entries.size) {
                writeAtomically(file, BootstrapDiskCache.encode(entries, elapsed(), System.currentTimeMillis()))
            }
            log("Bootstrap cache: ${entries.size} names")
        } catch (e: Exception) {
            log("Bootstrap cache unreadable: ${e.javaClass.simpleName}")
        }
    }

    /** After a look-up found addresses (once per TTL per name at most): the cache to disk. */
    private fun saveDiskCache() {
        val file = cacheFile ?: return
        try {
            synchronized(this) {
                if (closed) return
                writeAtomically(file, BootstrapDiskCache.encode(bootstrap.cache.snapshot(), elapsed(), System.currentTimeMillis()))
            }
        } catch (e: Exception) {
            log("Bootstrap cache not written: ${e.javaClass.simpleName}")
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
        synchronized(this) { closed = true }
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

/** A query's deadline from its start, the canary's floor and its waiters' slack (design 36 §5.4,
 * QA N4): the primary takes at most 2.5 s, the canary what is left above [BACKUP_FLOOR_MS] (at least
 * [CANARY_MIN_MS], at most its own 2 s), the backup the rest (at least [BACKUP_FLOOR_MS], at most
 * 2.5 s) - so a failed canary's query is answered in under ~5 s. */
const val QUERY_DEADLINE_MS = 4_800L
const val CANARY_MIN_MS = 1_000L
const val BACKUP_FLOOR_MS = 1_300L
const val CANARY_WAIT_SLACK_MS = 500L

/** The canary's budget with [leftMs] of the query's deadline left. */
fun canaryBudget(leftMs: Long): Budget {
    val total = (leftMs - BACKUP_FLOOR_MS).coerceIn(CANARY_MIN_MS, CANARY_BUDGET.totalMs.toLong()).toInt()
    return Budget(minOf(CANARY_BUDGET.connectMs, total), total)
}

/** The backup's budget with [leftMs] of the query's deadline left. */
fun backupBudget(leftMs: Long): Budget {
    val total = leftMs.coerceIn(BACKUP_FLOOR_MS, UPSTREAM_BUDGET.totalMs.toLong()).toInt()
    return Budget(minOf(UPSTREAM_BUDGET.connectMs, total), total)
}

/** The canary's answer as a verdict: ok = a NOERROR answer; REFUSED is `refused` (AdGuard Home's
 * answer to a client id it doesn't allow); a transport failure its reason; anything else `timeout`. */
fun canaryVerdict(outcome: Attempt, now: Long): CanaryVerdict = when (outcome) {
    is Attempt.Answer -> {
        val noError = runCatching { Message(outcome.bytes).rcode == Rcode.NOERROR }.getOrDefault(false)
        CanaryVerdict(now, noError, if (noError) null else BackupReason.TIMEOUT)
    }
    is Attempt.Transport -> CanaryVerdict(now, false, outcome.reason)
    // Never asked: `canary` drops a refused socket before it gets here.
    Attempt.Unprotected -> CanaryVerdict(now, false, BackupReason.TIMEOUT)
    is Attempt.Odd -> CanaryVerdict(now, false, if (outcome.kind == OddKind.REFUSED) BackupReason.REFUSED else BackupReason.TIMEOUT)
}
