package com.kidslauncher.mdm.server

/*
 * The VPN's DNS intake under load (security review S-L9). Pure, no Android imports (DnsIntakeTest); KidVpnService and
 * BlockedEventLog are the glue.
 */

/** At most this many queries are resolved at once (each may wait seconds on an upstream). */
const val DNS_PARALLEL = 16

/** At most this many queries are in the phone at once - resolving or waiting for one of the [DNS_PARALLEL] slots; the
 * rest are dropped (the asking app's own retry handles it). Any app could flood the fake server, and every query held
 * memory and an IO thread before. */
const val DNS_IN_FLIGHT_MAX = 256

/** A packet read from the tunnel is taken while fewer than [DNS_IN_FLIGHT_MAX] are in flight. */
fun dnsAdmit(inFlight: Int): Boolean = inFlight < DNS_IN_FLIGHT_MAX

/** Only a query with exactly one question is answered or forwarded: the lists classify the first question only, so a
 * QDCOUNT > 1 query would carry unclassified names to the resolver. */
fun dnsQuestionsOk(questionCount: Int): Boolean = questionCount == 1

/**
 * The blocked-event log's per-domain dedup times, bounded: the least recently logged domain goes first once [max]
 * are held (it grew without bound before). Not thread-safe - its one user holds a lock.
 */
class DedupTimes(private val max: Int = 512) {
    private val times = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > max
    }

    /** True when [domain] was logged less than [windowMs] before [nowMs]; otherwise it is stamped [nowMs]. */
    fun seenWithin(domain: String, nowMs: Long, windowMs: Long): Boolean {
        val last = times[domain]
        if (last != null && nowMs - last in 0 until windowMs) return true
        times[domain] = nowMs
        return false
    }

    fun clear() = times.clear()

    val size: Int get() = times.size
}
