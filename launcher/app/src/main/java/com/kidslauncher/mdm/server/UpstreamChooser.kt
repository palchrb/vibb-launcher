package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.DnsUpstreamReport

/** The status report's capability for design 36's custom DNS server ([STATUS_CAPABILITIES]). */
const val DNS_CUSTOM_CAPABILITY = "dns_custom_v1"

/** Why the backup answers (design 36 §4.2), as the status report spells it. */
enum class BackupReason(val wire: String) {
    BOOTSTRAP("bootstrap"), CONNECT("connect"), TLS("tls"), HTTP("http"), REFUSED("refused"), TIMEOUT("timeout"),
}

/** An odd answer from the primary (§5.4): judged by the canary, never by itself. */
enum class OddKind { REFUSED, SERVFAIL, TIMEOUT }

/** Where one query goes: the primary, the primary as this back-off's one probe, or the backup. */
enum class UpstreamRoute { PRIMARY, PROBE, BACKUP }

/** The canary's verdict (`. IN NS` to the primary): [ok] = a NOERROR answer; otherwise why not. */
data class CanaryVerdict(val atMs: Long, val ok: Boolean, val reason: BackupReason?)

/**
 * The backup rule's state (design 36 §5.4), one value in an `AtomicReference` changed by CAS.
 * Times are elapsed-realtime milliseconds ([sinceMs] becomes wall clock only in [upstreamReport]).
 * [gen] goes up on every period start, recovery and reset, so the outcomes of attempts made under
 * an older [gen] (a burst of failures) count once.
 */
data class ChooserState(
    val gen: Long = 0,
    val backup: Boolean = false,
    val failures: Int = 0,
    val skipUntilMs: Long = 0,
    val probing: Boolean = false,
    val sinceMs: Long = 0,
    val reason: BackupReason? = null,
    val canary: CanaryVerdict? = null,
)

/**
 * The pure backup rule (design 36 §5.4). It judges only the primary: the backup's outcomes never
 * reach it. The glue ([CustomDns]) applies each function by CAS and acts on the result.
 */
object UpstreamChooser {
    const val BACKOFF_BASE_MS = 60_000L
    const val BACKOFF_CAP_MS = 15 * 60_000L
    const val CANARY_TTL_MS = 60_000L

    /** Healthy → the primary. In a period: the backup until the back-off ends, then one query
     * probes the primary (`probing`) while the rest keep going to the backup until it returns. */
    fun route(s: ChooserState, now: Long): Pair<UpstreamRoute, ChooserState> = when {
        !s.backup -> UpstreamRoute.PRIMARY to s
        now < s.skipUntilMs || s.probing -> UpstreamRoute.BACKUP to s
        else -> UpstreamRoute.PROBE to s.copy(probing = true)
    }

    /** A probe that couldn't try the primary (its address isn't looked up yet) gives the slot back. */
    fun releaseProbe(s: ChooserState, attemptGen: Long): ChooserState =
        if (attemptGen != s.gen || !s.probing) s else s.copy(probing = false)

    /** The primary failed below DNS: a period starts (or the back-off doubles, to 15 min). */
    fun afterTransport(s: ChooserState, attemptGen: Long, now: Long, reason: BackupReason): ChooserState {
        if (attemptGen != s.gen) return s
        val failures = s.failures + 1
        val backoff = minOf(BACKOFF_BASE_MS shl minOf(failures - 1, 5), BACKOFF_CAP_MS)
        return s.copy(
            gen = s.gen + 1,
            backup = true,
            failures = failures,
            skipUntilMs = now + backoff,
            probing = false,
            sinceMs = if (s.backup) s.sinceMs else now,
            reason = reason,
        )
    }

    /** The primary answered: a period ends (from the probe); nothing changes while healthy. */
    fun afterAnswer(s: ChooserState, attemptGen: Long, now: Long): ChooserState {
        if (attemptGen != s.gen || !s.backup) return s
        return s.copy(gen = s.gen + 1, backup = false, failures = 0, skipUntilMs = 0, probing = false, sinceMs = now, reason = null)
    }

    /** REFUSED, SERVFAIL or silence after the request was written: the canary decides. A failed
     * canary is a transport failure (`refused` when it was REFUSED - AdGuard Home's answer to a
     * client id it doesn't allow); a healthy one is an answer, and the name's own outcome stands
     * ([oddAnswer]) - never the backup for that name. */
    fun afterOdd(s: ChooserState, attemptGen: Long, now: Long, canary: CanaryVerdict): ChooserState =
        if (canary.ok) afterAnswer(s, attemptGen, now)
        else afterTransport(s, attemptGen, now, canary.reason ?: BackupReason.TIMEOUT)

    /** No canary verdict younger than 60 s. */
    fun needsCanary(s: ChooserState, now: Long): Boolean {
        val canary = s.canary ?: return true
        return now - canary.atMs >= CANARY_TTL_MS
    }

    /** The canary's verdict, kept 60 s. */
    fun withCanary(s: ChooserState, verdict: CanaryVerdict): ChooserState = s.copy(canary = verdict)

    /** A network reset or new servers: the primary is tried again at once. */
    fun reset(s: ChooserState, now: Long): ChooserState = s.copy(
        gen = s.gen + 1,
        backup = false,
        failures = 0,
        skipUntilMs = 0,
        probing = false,
        sinceMs = if (s.backup) now else s.sinceMs,
        reason = null,
        canary = null,
    )
}

/** What a healthy server's odd answer becomes (§5.4): its REFUSED is a block (the phone's NODATA),
 * a SERVFAIL or silence a SERVFAIL. */
enum class OddAnswer { BLOCKED, SERVFAIL }

fun oddAnswer(kind: OddKind): OddAnswer = if (kind == OddKind.REFUSED) OddAnswer.BLOCKED else OddAnswer.SERVFAIL

/** The status report's `dns_upstream` (§4.2): [ChooserState.sinceMs] (elapsed realtime) turned into
 * wall clock with the two clocks read now; the reason with `backup` only; the outage from [reach]
 * (design 67, [outageTimes]). Never an address. */
fun upstreamReport(s: ChooserState, rev: Long, reach: ReachState, elapsedNow: Long, wallNow: Long): DnsUpstreamReport {
    val (unreachableSince, unreachableUntil) = outageTimes(reach, elapsedNow, wallNow)
    return DnsUpstreamReport(
        rev = rev,
        inUse = if (s.backup) "backup" else "custom",
        sinceMs = wallNow - (elapsedNow - s.sinceMs),
        reason = if (s.backup) s.reason?.wire else null,
        unreachableSinceMs = unreachableSince,
        unreachableUntilMs = unreachableUntil,
    )
}

/** The phone's best non-VPN network, as the network callback sees it. */
data class NetworkSnapshot(val handle: Long, val validated: Boolean)

/** A network reset (§5.4): the best network changed, or it gained `VALIDATED` (a captive portal
 * logged in). Losing the network is not one - there is nothing to try then. */
fun networkChanged(old: NetworkSnapshot?, new: NetworkSnapshot?): Boolean = when {
    new == null -> false
    old == null || old.handle != new.handle -> true
    else -> !old.validated && new.validated
}
