package com.kidslauncher.mdm.server

import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.dto.DnsUpstreamReport
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "UpstreamReach"

/**
 * One query's answer as the VPN got it back (design 67 §3.1): [bytes] to relay (`null` = dropped),
 * and whether a server proved itself alive with it ([answered]: its bytes relayed, or our own
 * NODATA/SERVFAIL after a healthy canary).
 */
class Resolved(val bytes: ByteArray?, val answered: Boolean)

/** What one handled query says about the upstream servers. */
enum class ReachKind { FAILED, ANSWERED, NEITHER }

/** FAILED: dropped with no server bytes, whatever the cause (every attempt a transport failure,
 * silence after the request, our own VPN refusing the socket, an exception in our code). NEITHER: our
 * own answer that proves nothing (a SERVFAIL after a canary this query gave up waiting for). */
fun reachKind(resolved: Resolved): ReachKind = when {
    resolved.bytes == null -> ReachKind.FAILED
    resolved.answered -> ReachKind.ANSWERED
    else -> ReachKind.NEITHER
}

/** A relayed attempt (the provider, the parent's backup): its bytes, or dropped. */
fun relayed(attempt: Attempt): Resolved = when (attempt) {
    is Attempt.Answer -> Resolved(attempt.bytes, answered = true)
    is Attempt.Odd -> Resolved(attempt.bytes, answered = attempt.bytes != null)
    is Attempt.Transport, Attempt.Unprotected -> Resolved(null, answered = false)
}

/** An ended outage, elapsed realtime. */
data class Outage(val sinceMs: Long, val untilMs: Long)

/**
 * "DNS can't be reached" (design 67 §3.1), elapsed realtime: [key] the servers the queries went to
 * (the custom `rev`, or the provider's name); the failures since the last answer, the first and the
 * last; [downSinceMs] an ongoing outage (dated from its first failure); [lastOutage] the last ended one.
 */
data class ReachState(
    val key: String? = null,
    val failures: Int = 0,
    val firstFailMs: Long = 0,
    val lastFailMs: Long = 0,
    val downSinceMs: Long? = null,
    val lastOutage: Outage? = null,
)

/** The pure rule; the glue ([ReachTracker]) applies it by CAS. */
object UpstreamReach {
    /** At least this many failed queries, spread over at least [MIN_SPAN_MS], with no answer between. */
    const val MIN_FAILURES = 3
    const val MIN_SPAN_MS = 30_000L
    /** A failure this long after the last one starts the count again (a stray failure dates nothing). */
    const val GAP_MS = 60_000L

    /** A failed query, counted only while the network is validated (offline is no outage - the glue). */
    fun afterFailure(s: ReachState, key: String, now: Long): ReachState {
        val k = rekey(s, key, now)
        if (k.downSinceMs != null) return k
        val fresh = k.failures == 0 || now - k.lastFailMs > GAP_MS
        val failures = if (fresh) 1 else k.failures + 1
        val first = if (fresh) now else k.firstFailMs
        val down = failures >= MIN_FAILURES && now - first >= MIN_SPAN_MS
        return k.copy(failures = failures, firstFailMs = first, lastFailMs = now, downSinceMs = if (down) first else null)
    }

    /** A server answered: an ongoing outage ends now, the count clears. */
    fun afterAnswer(s: ReachState, key: String, now: Long): ReachState = rekey(s, key, now).ended(now)

    /** Other servers (a new `rev`, another provider): an ongoing outage ends now, the count clears;
     * [ReachState.lastOutage] stays - the parent who fixed the servers wants to see it. */
    fun rekey(s: ReachState, key: String, now: Long): ReachState = if (s.key == key) s else s.ended(now).copy(key = key)

    /** The validated network is gone: an ongoing outage ends (nobody's server), the count clears. */
    fun networkLost(s: ReachState, now: Long): ReachState = s.ended(now)

    private fun ReachState.ended(now: Long): ReachState =
        ReachState(key = key, lastOutage = downSinceMs?.let { Outage(it, now) } ?: lastOutage)
}

/** The servers a query goes to, as [ReachState.key]: the parent's `rev` ([custom]), or the provider's
 * name ([provider] as the policy has it; anything but Quad9 is Cloudflare). */
fun reachKey(custom: CustomUpstream?, provider: String?): String =
    custom?.let { "custom:${it.rev}" } ?: PublicResolvers.of(provider).label

/** The phone had a validated network and has none now (another unvalidated one, or nothing). */
fun validatedLost(old: NetworkSnapshot?, new: NetworkSnapshot?): Boolean = old?.validated == true && new?.validated != true

/** The outage fields of the status report, wall clock (§3.2): ongoing `(since, null)`, the last
 * ended `(since, until)`, none `(null, null)`. */
fun outageTimes(s: ReachState, elapsedNow: Long, wallNow: Long): Pair<Long?, Long?> {
    fun wall(t: Long) = wallNow - (elapsedNow - t)
    s.downSinceMs?.let { return wall(it) to null }
    val ended = s.lastOutage ?: return null to null
    return wall(ended.sinceMs) to wall(ended.untilMs)
}

/** Provider mode's `dns_upstream` (§3.2): no rev, no since, no reason - only the outage. */
fun providerReport(reach: ReachState, elapsedNow: Long, wallNow: Long): DnsUpstreamReport {
    val (since, until) = outageTimes(reach, elapsedNow, wallNow)
    return DnsUpstreamReport(rev = 0, inUse = "provider", sinceMs = null, reason = null, unreachableSinceMs = since, unreachableUntilMs = until)
}

/** [UpstreamReach] for one VPN service instance: one [AtomicReference] changed by CAS. */
class ReachTracker(
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
    private val state = AtomicReference(ReachState())

    fun get(): ReachState = state.get()

    /** A query to [key]'s servers came back as [resolved]; [validated]: the phone's network was. */
    fun after(resolved: Resolved, key: String, validated: Boolean) = when (reachKind(resolved)) {
        ReachKind.FAILED -> if (validated) update { UpstreamReach.afterFailure(it, key, elapsed()) } else Unit
        ReachKind.ANSWERED -> update { UpstreamReach.afterAnswer(it, key, elapsed()) }
        ReachKind.NEITHER -> Unit
    }

    fun rekey(key: String) = update { UpstreamReach.rekey(it, key, elapsed()) }

    fun networkLost() = update { UpstreamReach.networkLost(it, elapsed()) }

    private inline fun update(change: (ReachState) -> ReachState) {
        while (true) {
            val s = state.get()
            val next = change(s)
            if (next == s) return
            if (state.compareAndSet(s, next)) {
                if (s.downSinceMs == null && next.downSinceMs != null) log("Upstream unreachable, failures=${next.failures}")
                if (s.downSinceMs != null && next.downSinceMs == null) log("Upstream outage ended")
                return
            }
        }
    }
}
