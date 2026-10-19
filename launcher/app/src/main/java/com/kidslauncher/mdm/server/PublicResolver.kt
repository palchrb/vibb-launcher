package com.kidslauncher.mdm.server

import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
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

/** [this], with neither part above [cap]'s. */
fun Budget.atMost(cap: Budget): Budget = Budget(minOf(connectMs, cap.connectMs), minOf(totalMs, cap.totalMs))

/**
 * A public resolver (Cloudflare, Quad9): DoT on 853 to its fixed [address], and on a network that
 * blocks 853 the same provider's DoH on 443 by its IP-literal URL ([doh]: no SNI, the certificate must
 * name the IP - theirs do) - never the other provider (design 36 step 3). The parent's own servers
 * never come here: a parent's DoT server never gets a guessed DoH URL. Logs name the [label] and
 * counts only.
 */
class PublicResolver(
    val label: String,
    val dot: UpstreamTarget.Dot,
    val address: InetAddress,
    val doh: UpstreamTarget.Doh,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
    private val state = AtomicReference(DotBlockedRule.IDLE)

    internal fun state(): DotBlocked = state.get()

    /** A network reset: DoT first again. */
    fun reset() = update { DotBlockedRule.IDLE }

    /**
     * One query: DoT within [dotBudget] (a probe within at most [UPSTREAM_BUDGET], so its query still
     * gets DoH in time), or DoH within [dohCap] in a DoH period. A DoT transport failure counts towards
     * the period, and the query itself goes on to DoH while [QUERY_DEADLINE_MS] leaves at least
     * [BACKUP_FLOOR_MS]. In a period a DoH transport failure goes on to DoT the same way (QA L1: a
     * network that blocks the provider's 443 but not 853), and a DoT that works ends the period.
     * [transport] is one attempt at a target (DoT or DoH); one that throws counts as a connect failure.
     */
    fun resolve(
        query: ByteArray,
        dotBudget: Budget,
        dohCap: Budget,
        transport: (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt,
    ): Attempt {
        val deadline = elapsed() + QUERY_DEADLINE_MS
        val addresses = listOf(address)
        fun attempt(target: UpstreamTarget, budget: Budget): Attempt = try {
            transport(target, addresses, query, budget)
        } catch (e: Exception) {
            if (unprotected(e)) Attempt.Unprotected else Attempt.Transport(BackupReason.CONNECT)
        }
        fun left(cap: Budget): Budget? {
            val left = deadline - elapsed()
            return if (left < BACKUP_FLOOR_MS) null else cap.atMost(Budget(left.toInt(), left.toInt()))
        }
        var route = DotRoute.DOT
        update { s -> DotBlockedRule.route(s, elapsed()).let { (r, next) -> route = r; next } }
        if (route == DotRoute.DOH) {
            val outcome = attempt(doh, dohCap)
            if (outcome !is Attempt.Transport) return outcome
            val budget = left(dotBudget.atMost(UPSTREAM_BUDGET)) ?: return outcome
            val fallback = attempt(dot, budget)
            if (fallback is Attempt.Answer || fallback is Attempt.Odd) update(DotBlockedRule::afterDotWorked)
            return fallback
        }
        val outcome = attempt(dot, if (route == DotRoute.PROBE) dotBudget.atMost(UPSTREAM_BUDGET) else dotBudget)
        if (outcome is Attempt.Unprotected) {
            update { DotBlockedRule.afterUnprotected(it, route == DotRoute.PROBE) }
            return outcome
        }
        if (outcome !is Attempt.Transport) {
            update(DotBlockedRule::afterDotWorked)
            return outcome
        }
        update { DotBlockedRule.afterDotFailure(it, elapsed(), route == DotRoute.PROBE) }
        return attempt(doh, left(dohCap) ?: return outcome)
    }

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

/** The public resolvers (design 36 §0 and step 3), one state each for the process; Cloudflare's is
 * also the bootstrap's last resolver ([CLOUDFLARE_BOOTSTRAP]). [KidVpnService] resets them on a
 * network reset. */
object PublicResolvers {
    val cloudflare = cloudflareResolver()
    val quad9 = quad9Resolver()

    /** The policy's `dns_upstream_provider`: Quad9 by name, anything else Cloudflare (as before). */
    fun of(provider: String?): PublicResolver = if (provider == "quad9") quad9 else cloudflare

    fun reset() {
        cloudflare.reset()
        quad9.reset()
    }
}

val CLOUDFLARE_DOT = UpstreamTarget.Dot("cloudflare-dns.com", 853)
val CLOUDFLARE_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

fun cloudflareResolver(
    elapsed: () -> Long = SystemClock::elapsedRealtime,
    log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) = PublicResolver("cloudflare", CLOUDFLARE_DOT, CLOUDFLARE_ADDRESS, UpstreamTarget.Doh("https://1.1.1.1/dns-query"), elapsed, log)

fun quad9Resolver(
    elapsed: () -> Long = SystemClock::elapsedRealtime,
    log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) = PublicResolver(
    "quad9",
    UpstreamTarget.Dot("dns.quad9.net", 853),
    InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9)),
    UpstreamTarget.Doh("https://9.9.9.9/dns-query"),
    elapsed,
    log,
)
