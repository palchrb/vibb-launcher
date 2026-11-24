package com.kidslauncher.mdm.server

import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "DnsUpstream"

/** Where one query to a public resolver goes (design 36 step 3): DoT on 853, DoT as this period's
 * one probe, or the same provider's DoH on 443. */
enum class DotRoute { DOT, PROBE, DOH }

/**
 * Whether 853 is blocked on this network for one public resolver (design 36 step 3), one value in an
 * `AtomicReference` changed by CAS. While DoT works: [failures] DoT transport failures since
 * [windowStartMs]. Once blocked ([doh]): DoH until [dohUntilMs], then one query probes DoT
 * ([probing]); [periods] DoH periods in a row (the back-off's exponent). Times are elapsed realtime.
 */
data class DotBlocked(
    val failures: Int = 0,
    val windowStartMs: Long = 0,
    val doh: Boolean = false,
    val periods: Int = 0,
    val dohUntilMs: Long = 0,
    val probing: Boolean = false,
)

/** The pure rule for [DotBlocked]: [THRESHOLD] DoT transport failures within [WINDOW_MS] start a DoH
 * period of [BACKOFF_BASE_MS], doubling with every failed probe to [BACKOFF_CAP_MS]; DoT that works
 * (a probe or any attempt) or a network reset ends it. */
object DotBlockedRule {
    const val THRESHOLD = 3
    const val WINDOW_MS = 60_000L
    const val BACKOFF_BASE_MS = 5 * 60_000L
    const val BACKOFF_CAP_MS = 60 * 60_000L

    fun route(s: DotBlocked, now: Long): Pair<DotRoute, DotBlocked> = when {
        !s.doh -> DotRoute.DOT to s
        now < s.dohUntilMs || s.probing -> DotRoute.DOH to s
        else -> DotRoute.PROBE to s.copy(probing = true)
    }

    /** DoT failed below DNS (connect refused or timed out, reset, TLS). In a DoH period only the
     * probe's failure counts: the next period, twice as long. */
    fun afterDotFailure(s: DotBlocked, now: Long, probe: Boolean): DotBlocked {
        if (s.doh) {
            if (!probe || !s.probing) return s
            val periods = s.periods + 1
            return s.copy(periods = periods, dohUntilMs = now + backoff(periods), probing = false)
        }
        val inWindow = s.failures > 0 && now - s.windowStartMs < WINDOW_MS
        val failures = if (inWindow) s.failures + 1 else 1
        if (failures >= THRESHOLD) return DotBlocked(doh = true, periods = 1, dohUntilMs = now + backoff(1))
        return s.copy(failures = failures, windowStartMs = if (inWindow) s.windowStartMs else now)
    }

    /** DoT reached the server (an answer, or anything above TLS): 853 isn't blocked here. */
    fun afterDotWorked(s: DotBlocked): DotBlocked = if (s == IDLE) s else IDLE

    /** Our own VPN refused the socket ([Attempt.Unprotected]): the server wasn't tried, so nothing counts -
     * only a probe that never ran is given back, so the next query probes (qa-vpn-protect-code L3). */
    fun afterUnprotected(s: DotBlocked, probe: Boolean): DotBlocked = if (probe && s.probing) s.copy(probing = false) else s

    /** DoT works, nothing counted (shared: no allocation per answered query). */
    val IDLE = DotBlocked()

    fun backoff(periods: Int): Long = minOf(BACKOFF_BASE_MS shl (periods - 1).coerceIn(0, 10), BACKOFF_CAP_MS)
}

/** [this], with no part above [cap]'s (and the setup part never above the whole). */
fun Budget.atMost(cap: Budget): Budget {
    val total = minOf(totalMs, cap.totalMs)
    return Budget(minOf(connectMs, cap.connectMs), total, minOf(setupMs, cap.setupMs, total))
}

/** What a DoT address's connect and handshake leave of the query's deadline above [BACKUP_FLOOR_MS] for the DoH after
 * it, so a few ms of overhead can't push the DoH under its floor (code QA of `dns-bootstrap`, L1). */
const val DOH_MARGIN_MS = 100L

/**
 * A public resolver (Quad9, Cloudflare): DoT on 853 to its fixed [addresses], and on a network
 * that blocks 853 the same provider's DoH on 443 ([doh]: its name at the same fixed addresses,
 * [PINNED_DNS]) - design 36 step 3. Which provider a query goes to is [resolveChain]'s. The parent's
 * own servers never come here: a parent's DoT server never gets a guessed DoH URL. Logs name the
 * [label] and counts only.
 *
 * Several addresses (2026-11-24): DoT starts at the one last reached ([preferred]) and takes one at a
 * time; an address that fails below DNS (or stays silent after the write) is moved past, for this query
 * and the next. An address failing
 * is not the provider failing: only a query that got no answer from any address it had time for, nor
 * from the DoH (every address), counts against the provider in the chain.
 */
class PublicResolver(
    val label: String,
    val dot: UpstreamTarget.Dot,
    val addresses: List<InetAddress>,
    val doh: UpstreamTarget.Doh,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
    private val state = AtomicReference(DotBlockedRule.IDLE)

    /** The index in [addresses] DoT starts at: the address last reached, or the next after one that failed. */
    private val preferred = AtomicInteger(0)

    /** The address DoT asks first now, for tests. */
    internal fun preferredAddress(): InetAddress = addresses[preferred.get()]

    /** Whether the chain skips this provider for now ([ProviderChainRule]); unproven until it answers. */
    internal val down = AtomicReference(ProviderChainRule.UNPROVEN)

    internal fun state(): DotBlocked = state.get()

    /** A network reset: DoT first again from the first address, and unproven in the chain until it answers on this network. */
    fun reset() {
        update { DotBlockedRule.IDLE }
        preferred.set(0)
        down.set(ProviderChainRule.UNPROVEN)
    }

    /**
     * One query: DoT within [dotBudget] (a probe within at most [UPSTREAM_BUDGET], so its query still
     * gets DoH in time), or DoH within [dohCap] in a DoH period. DoT asks one address at a time from
     * [preferred]; each address's connect and handshake end while [BACKUP_FLOOR_MS] + [DOH_MARGIN_MS] of
     * the deadline is still left for DoH (silence after the write keeps the rest), and the next address
     * is tried only while a whole connect still fits that way - a refused address, or one of a family the
     * network lacks, costs no time; a dropped or hanging one leaves DoH its share. An address that fails
     * below DNS or stays silent after the write is moved past. A query whose DoT failed below DNS counts
     * once towards the period (queries in flight at the same blocked address each count, as with one
     * address); one that met silence counts nothing, unless it was the probe (then the next period). The
     * query itself goes on to DoH (every address, [PINNED_DNS]) while [QUERY_DEADLINE_MS] leaves at least
     * [BACKUP_FLOOR_MS]. In a period a DoH transport failure goes on to DoT the same way (QA L1: a
     * network that blocks the provider's 443 but not 853), and a DoT that works ends the period.
     * [transport] is one attempt at a target (DoT or DoH); one that throws counts as a connect failure.
     */
    fun resolve(
        query: ByteArray,
        dotBudget: Budget,
        dohCap: Budget,
        transport: (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt,
    ): Attempt = resolveBy(elapsed() + QUERY_DEADLINE_MS, query, dotBudget, dohCap, transport)

    /** [resolve] within a [deadline] that is already running (the chain's one per query). */
    fun resolveBy(
        deadline: Long,
        query: ByteArray,
        dotBudget: Budget,
        dohCap: Budget,
        transport: (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt,
    ): Attempt {
        fun attempt(target: UpstreamTarget, to: List<InetAddress>, budget: Budget): Attempt = try {
            transport(target, to, query, budget)
        } catch (e: Exception) {
            if (unprotected(e)) Attempt.Unprotected else Attempt.Transport(BackupReason.CONNECT)
        }
        fun left(cap: Budget): Budget? {
            val left = deadline - elapsed()
            return if (left < BACKUP_FLOOR_MS) null else cap.atMost(Budget(left.toInt(), left.toInt()))
        }
        // DoT from the address last reached, one at a time. Each address's connect and handshake end while [reserve] of
        // the deadline is still left (M1); silence after the write keeps the rest. The next address only while a whole
        // connect within [cap] still fits that way; the first, with no room for that, gets what is left (nothing could
        // follow it anyway). An address that failed below DNS or stayed silent is moved past for later queries too.
        fun dotOver(cap: Budget, reserve: Long): Attempt {
            val start = preferred.get()
            var outcome: Attempt = Attempt.Transport(BackupReason.CONNECT)
            for (i in addresses.indices) {
                val at = (start + i) % addresses.size
                val left = deadline - elapsed()
                val room = left - reserve
                val budget = when {
                    room >= cap.connectMs -> cap.atMost(Budget(left.toInt(), left.toInt(), room.toInt()))
                    i == 0 -> cap.atMost(Budget(left.toInt(), left.toInt()))
                    else -> break
                }
                outcome = attempt(dot, listOf(addresses[at]), budget)
                if (outcome is Attempt.Transport || silent(outcome)) preferred.compareAndSet(at, (at + 1) % addresses.size)
                if (outcome !is Attempt.Transport) return outcome
            }
            return outcome
        }
        var route = DotRoute.DOT
        update { s -> DotBlockedRule.route(s, elapsed()).let { (r, next) -> route = r; next } }
        if (route == DotRoute.DOH) {
            val outcome = attempt(doh, addresses, dohCap)
            if (outcome !is Attempt.Transport) return outcome
            val cap = dotBudget.atMost(UPSTREAM_BUDGET)
            if (left(cap) == null) return outcome
            val fallback = dotOver(cap, reserve = 0)
            if (fallback is Attempt.Answer || (fallback is Attempt.Odd && !silent(fallback))) update(DotBlockedRule::afterDotWorked)
            return fallback
        }
        val probe = route == DotRoute.PROBE
        // Never past the query's deadline (a later provider in the chain keeps its share).
        val cap = if (probe) dotBudget.atMost(UPSTREAM_BUDGET) else dotBudget
        if (left(cap) == null) {
            update { DotBlockedRule.afterUnprotected(it, probe) }
            return Attempt.Transport(BackupReason.TIMEOUT)
        }
        val outcome = dotOver(cap, reserve = BACKUP_FLOOR_MS + DOH_MARGIN_MS)
        when {
            outcome is Attempt.Unprotected -> {
                update { DotBlockedRule.afterUnprotected(it, probe) }
                return outcome
            }
            // Silence after the write (L2): no proof DoT works, and no time left for DoH; a probe's counts as failed.
            silent(outcome) -> {
                if (probe) update { DotBlockedRule.afterDotFailure(it, elapsed(), probe = true) }
                return outcome
            }
            outcome !is Attempt.Transport -> {
                update(DotBlockedRule::afterDotWorked)
                return outcome
            }
        }
        // One count per query whose DoT failed below DNS (L3: a burst on a blocked network counts as with one address).
        update { DotBlockedRule.afterDotFailure(it, elapsed(), probe) }
        return attempt(doh, addresses, left(dohCap) ?: return outcome)
    }

    /** Silence after the write: the address took the query and never answered. */
    private fun silent(a: Attempt): Boolean = a is Attempt.Odd && a.kind == OddKind.TIMEOUT

    private inline fun update(change: (DotBlocked) -> DotBlocked) {
        while (true) {
            val s = state.get()
            val next = change(s)
            if (next == s) return
            if (state.compareAndSet(s, next)) {
                if (next.doh != s.doh || next.periods != s.periods) {
                    log(if (next.doh) "DoT blocked: $label over DoH, periods=${next.periods}" else "DoT works again: $label")
                }
                return
            }
        }
    }
}

/**
 * Whether the chain skips one provider for now (the web filter's resolver: the parent's choice first,
 * the others as fallbacks, all encrypted). Up: asked by every query. [unproven] (at start and after a
 * network reset, until it answers once): asked by one query at a time ([probing]). Down after a query it
 * didn't answer: skipped until [untilMs], then one query asks it; [periods] failed periods in a row.
 * Elapsed time.
 */
data class ProviderDown(val untilMs: Long = 0, val periods: Int = 0, val probing: Boolean = false, val unproven: Boolean = false)

/** The pure rule for [ProviderDown]: a query a provider doesn't answer skips it for [BACKOFF_BASE_MS],
 * doubling with every failed probe to [BACKOFF_CAP_MS]; an answer ends it. A network reset makes it
 * unproven, so a dead one costs one query, not every query in flight. */
object ProviderChainRule {
    const val BACKOFF_BASE_MS = 60_000L
    const val BACKOFF_CAP_MS = 15 * 60_000L

    val UP = ProviderDown()
    val UNPROVEN = ProviderDown(unproven = true)

    /** Whether a query asks the provider now, and the state after; an unproven or due one is asked by one query. */
    fun ask(s: ProviderDown, now: Long): Pair<Boolean, ProviderDown> = when {
        s.periods == 0 && !s.unproven -> true to s
        (s.unproven || now >= s.untilMs) && !s.probing -> true to s.copy(probing = true)
        else -> false to s
    }

    /** Another query is finding out whether this unproven provider answers: worth a short wait. */
    fun proving(s: ProviderDown): Boolean = s.unproven && s.probing

    /** It didn't answer. While unproven or down only the probe's failure counts (queries in flight count once). */
    fun afterFailure(s: ProviderDown, now: Long, probe: Boolean): ProviderDown {
        if ((s.periods > 0 || s.unproven) && !(probe && s.probing)) return s
        val periods = s.periods + 1
        return ProviderDown(untilMs = now + backoff(periods), periods = periods)
    }

    /** Our own VPN refused the socket: nothing counts, a probe that never ran is given back. */
    fun afterUnprotected(s: ProviderDown, probe: Boolean): ProviderDown = if (probe && s.probing) s.copy(probing = false) else s

    fun backoff(periods: Int): Long = minOf(BACKOFF_BASE_MS shl (periods - 1).coerceIn(0, 10), BACKOFF_CAP_MS)
}

/** How long a query waits at a time for another query to find out whether an unproven provider answers. */
const val PROVING_WAIT_MS = 25L

/** No answer from a provider: below DNS, or silence after the query was written. */
internal fun unanswered(a: Attempt): Boolean = a is Attempt.Transport || (a is Attempt.Odd && a.kind == OddKind.TIMEOUT)

/**
 * One query through [order] (the parent's choice first, then the fallbacks): each provider that is up,
 * or unproven or due for its probe and not being probed, in turn, within one [QUERY_DEADLINE_MS]. Only no
 * answer at all ([unanswered]: a transport failure or silence after the write) moves the query on - an
 * answer, SERVFAIL and REFUSED included, is relayed. One that doesn't answer is skipped for a while
 * ([ProviderChainRule]) and the query goes on to the next while at least [BACKUP_FLOOR_MS] is left. With
 * every provider skipped while another query finds out whether an unproven one answers, the query waits
 * for that ([PROVING_WAIT_MS] at a time), then looks again; with none to wait for, the first is asked
 * anyway (the filter never stops answering for a back-off). [answered] gets the label of the one that
 * answered. Every hop is DoT or DoH, never plain DNS.
 */
fun resolveChain(
    order: List<PublicResolver>,
    query: ByteArray,
    dotBudget: Budget,
    dohCap: Budget,
    transport: (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt,
    elapsed: () -> Long = SystemClock::elapsedRealtime,
    pause: (Long) -> Unit = Thread::sleep,
    answered: (String) -> Unit = {},
): Attempt {
    val deadline = elapsed() + QUERY_DEADLINE_MS
    var last: Attempt = Attempt.Transport(BackupReason.CONNECT)
    var asked = false
    val tried = HashSet<PublicResolver>()
    while (true) {
        for (provider in order) {
            if (provider in tried) continue
            if (asked && deadline - elapsed() < BACKUP_FLOOR_MS) return last
            var ask = false
            var probe = false
            provider.down.updateAndGet { s ->
                val (a, next) = ProviderChainRule.ask(s, elapsed())
                ask = a
                probe = next.probing && !s.probing
                next
            }
            if (!ask) continue
            asked = true
            tried += provider
            val outcome = provider.resolveBy(deadline, query, dotBudget, dohCap, transport)
            when {
                outcome is Attempt.Unprotected -> {
                    provider.down.updateAndGet { ProviderChainRule.afterUnprotected(it, probe) }
                    return outcome
                }
                unanswered(outcome) -> {
                    provider.down.updateAndGet { ProviderChainRule.afterFailure(it, elapsed(), probe) }
                    last = outcome
                }
                else -> {
                    provider.down.set(ProviderChainRule.UP)
                    answered(provider.label)
                    return outcome
                }
            }
        }
        val waitFor = order.any { it !in tried && ProviderChainRule.proving(it.down.get()) }
        if (!waitFor || deadline - elapsed() < BACKUP_FLOOR_MS + PROVING_WAIT_MS) break
        pause(PROVING_WAIT_MS)
    }
    if (asked) return last
    val first = order.first()
    val outcome = first.resolveBy(deadline, query, dotBudget, dohCap, transport)
    if (outcome !is Attempt.Unprotected && !unanswered(outcome)) {
        first.down.set(ProviderChainRule.UP)
        answered(first.label)
    }
    return outcome
}

/** The public resolvers, one state each for the process; [KidVpnService] resets them on a network
 * reset. The web filter's resolver: Quad9 by default, Cloudflare the fallback, all
 * encrypted; a parent's server's name is looked up through Quad9 alone ([bootstrap]). */
object PublicResolvers {
    val quad9 = quad9Resolver()
    val cloudflare = cloudflareResolver()

    /** The fallback order behind the parent's choice; Cloudflare last. */
    private val chain = listOf(quad9, cloudflare)

    /** The provider of a policy without a known one (the server's `dns_server::DEFAULT_PROVIDER`). */
    const val DEFAULT = "quad9"

    /** Quad9's unfiltered service, for the bootstrap alone: its own addresses, preferred address and DoT-blocked state. */
    val quad9Unfiltered = quad9UnfilteredResolver()

    /** Where a parent's server's name is looked up: Quad9's unfiltered service by its fixed addresses (DoT, then its
     * DoH), so its malware list never hides the parent's own server - never Cloudflare, and never the parent's backup
     * or primary, not even one given as an IP literal (2026-11-24). Not in the web filter's chain. */
    val bootstrap = listOf(quad9Unfiltered)

    /** The provider that answered last (the status report's `provider`); `null` before any. */
    @Volatile
    var answering: String? = null
        private set

    /** The policy's `dns_upstream_provider`, an unknown one [DEFAULT]. */
    fun of(provider: String?): PublicResolver = chain.firstOrNull { it.label == provider } ?: chain.first { it.label == DEFAULT }

    /** The parent's choice first, then the rest in [chain]'s order. */
    fun order(provider: String?): List<PublicResolver> = of(provider).let { first -> listOf(first) + chain.filter { it !== first } }

    fun resolve(
        provider: String?,
        query: ByteArray,
        dotBudget: Budget,
        dohCap: Budget,
        transport: (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt,
    ): Attempt {
        // Another choice: what answered before says nothing about it.
        if (provider != chosen) {
            chosen = provider
            answering = null
        }
        return resolveChain(order(provider), query, dotBudget, dohCap, transport, answered = { answering = it })
    }

    /** The choice the last query went out under. */
    @Volatile
    private var chosen: String? = null

    fun reset() {
        (chain + bootstrap).forEach(PublicResolver::reset)
        answering = null
    }
}

/** IP literals parsed into bytes ([ipLiteral]), never looked up. */
private fun pinned(vararg literals: String): List<InetAddress> = literals.map { requireNotNull(ipLiteral(it)) }

/** Cloudflare's malware-blocking resolver (1.1.1.1 for Families' "security"): both IPv4 addresses, then both IPv6. */
val CLOUDFLARE_DOT = UpstreamTarget.Dot("security.cloudflare-dns.com", 853)
val CLOUDFLARE_ADDRESSES: List<InetAddress> = pinned("1.1.1.2", "1.0.0.2", "2606:4700:4700::1112", "2606:4700:4700::1002")

/** Quad9's "Recommended" service (malware blocking, DNSSEC, no ECS): both IPv4 addresses, then both IPv6. */
val QUAD9_DOT = UpstreamTarget.Dot("dns.quad9.net", 853)
val QUAD9_ADDRESSES: List<InetAddress> = pinned("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")

/** Quad9's unfiltered service (no blocklist, DNSSEC, no ECS): the bootstrap's alone. */
val QUAD9_UNFILTERED_DOT = UpstreamTarget.Dot("dns10.quad9.net", 853)
val QUAD9_UNFILTERED_ADDRESSES: List<InetAddress> = pinned("9.9.9.10", "149.112.112.10", "2620:fe::10", "2620:fe::fe:10")

/** The providers' names and their fixed addresses: never looked up. */
val PINNED_PROVIDERS: Map<String, List<InetAddress>> = mapOf(
    CLOUDFLARE_DOT.host to CLOUDFLARE_ADDRESSES,
    QUAD9_DOT.host to QUAD9_ADDRESSES,
    QUAD9_UNFILTERED_DOT.host to QUAD9_UNFILTERED_ADDRESSES,
)

/** The bootstrap's resolver: Quad9's unfiltered service (no blocklist, DNSSEC, no ECS) - code QA of `dns-bootstrap`, L4. */
fun quad9UnfilteredResolver(
    elapsed: () -> Long = SystemClock::elapsedRealtime,
    log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) = PublicResolver(
    "quad9_unfiltered", QUAD9_UNFILTERED_DOT, QUAD9_UNFILTERED_ADDRESSES, UpstreamTarget.Doh("https://dns10.quad9.net/dns-query"), elapsed, log,
)

fun cloudflareResolver(
    elapsed: () -> Long = SystemClock::elapsedRealtime,
    log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) = PublicResolver(
    "cloudflare", CLOUDFLARE_DOT, CLOUDFLARE_ADDRESSES, UpstreamTarget.Doh("https://security.cloudflare-dns.com/dns-query"), elapsed, log,
)

fun quad9Resolver(
    elapsed: () -> Long = SystemClock::elapsedRealtime,
    log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) = PublicResolver("quad9", QUAD9_DOT, QUAD9_ADDRESSES, UpstreamTarget.Doh("https://dns.quad9.net/dns-query"), elapsed, log)
