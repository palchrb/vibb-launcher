package com.kidslauncher.mdm.server

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xbill.DNS.ARecord
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section

/** The host of a target: a DoH URL's host (an IPv6 literal without brackets), a DoT host. */
val UpstreamTarget.serverHost: String
    get() = when (this) {
        is UpstreamTarget.Doh -> url.toHttpUrl().host
        is UpstreamTarget.Dot -> host
    }

private val DOTTED_QUAD = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * [host] as an IP address when it is an IP literal (dotted-quad IPv4, or IPv6 with or without
 * brackets), else `null`. Never a DNS look-up: a name is never handed to [InetAddress].
 */
fun ipLiteral(host: String): InetAddress? {
    val bare = host.removePrefix("[").removeSuffix("]")
    val literal = when {
        ':' in bare -> true
        DOTTED_QUAD.matches(bare) -> bare.split('.').all { it.toInt() <= 255 }
        else -> false
    }
    if (!literal) return null
    return runCatching { InetAddress.getByName(bare) }.getOrNull()
}

/** The last fallback for the bootstrap resolver (design 36 §5.3): Cloudflare over DoT, by its
 * fixed address - the only case a look-up leaves for anyone but the parent's servers. */
val CLOUDFLARE_BOOTSTRAP = UpstreamTarget.Dot("cloudflare-dns.com", 853)
private val CLOUDFLARE_BOOTSTRAP_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

/**
 * Where the servers' names are looked up (design 36 §5.3), fixed per config: the backup when it is
 * an IP literal, else the primary when it is, else [CLOUDFLARE_BOOTSTRAP]; `null` when both are
 * IP literals (nothing to look up).
 */
fun bootstrapResolver(config: CustomUpstream): UpstreamTarget? {
    val backupIp = ipLiteral(config.backup.serverHost) != null
    val primaryIp = ipLiteral(config.primary.serverHost) != null
    return when {
        backupIp && primaryIp -> null
        backupIp -> config.backup
        primaryIp -> config.primary
        else -> CLOUDFLARE_BOOTSTRAP
    }
}

/** At most two addresses, IPv4 first (one of each family when there are both). */
fun ipv4First(addresses: List<InetAddress>): List<InetAddress> {
    val v4 = addresses.filterIsInstance<Inet4Address>()
    val v6 = addresses.filterNot { it is Inet4Address }
    return (v4.take(1) + v6.take(1) + v4.drop(1)).take(2)
}

/** Addresses from one A or AAAA answer and the smallest TTL (seconds) on the way to them. */
data class LookupAnswer(val addresses: List<InetAddress>, val ttlSeconds: Long)

/**
 * The A/AAAA records of the final name of the CNAME chain from [qname] in [response]'s answer
 * section (a NOERROR answer only), with the smallest TTL of the chain and the records. Empty when
 * there are none.
 */
fun addressesFromAnswer(response: Message, qname: Name): LookupAnswer {
    if (response.rcode != Rcode.NOERROR) return LookupAnswer(emptyList(), 0)
    val records = response.getSection(Section.ANSWER)
    var name = qname
    var ttl = Long.MAX_VALUE
    repeat(records.size) {
        val cname = records.firstOrNull { it is CNAMERecord && it.name == name } as? CNAMERecord ?: return@repeat
        ttl = minOf(ttl, cname.ttl)
        name = cname.target
    }
    val addresses = records.filter { it.name == name }.mapNotNull { record ->
        when (record) {
            is ARecord -> record.address.also { ttl = minOf(ttl, record.ttl) }
            is AAAARecord -> record.address.also { ttl = minOf(ttl, record.ttl) }
            else -> null
        }
    }
    return LookupAnswer(addresses, if (addresses.isEmpty()) 0 else ttl)
}

/**
 * The bootstrap's memory cache (design 36 §5.3). Pure: the clock is passed in. A TTL is clamped to
 * 5 min - 24 h. An expired entry is still served while one refresh runs, and kept when the refresh
 * fails. A name with nothing cached is looked up once at a time, and not again within
 * [RETRY_AFTER_FAILURE_MS] of a failed look-up (a network reset clears that, [forgetFailures]).
 */
class BootstrapCache {
    private class Entry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
        val lookingUp: Boolean,
        val failedAtMs: Long?,
    )

    private val entries = HashMap<String, Entry>()

    /** What [get] found: the addresses to use now (empty = none yet), and whether the caller must
     * start a look-up (it then reports with [put] or [failed]). */
    data class Found(val addresses: List<InetAddress>, val lookUp: Boolean)

    @Synchronized
    fun get(host: String, now: Long): Found {
        val entry = entries[host]
        val addresses = entry?.addresses.orEmpty()
        val due = when {
            entry == null -> true
            entry.lookingUp -> false
            addresses.isNotEmpty() -> now >= entry.expiresAtMs
            else -> entry.failedAtMs == null || now - entry.failedAtMs >= RETRY_AFTER_FAILURE_MS
        }
        if (due) entries[host] = Entry(addresses, entry?.expiresAtMs ?: 0, lookingUp = true, failedAtMs = entry?.failedAtMs)
        return Found(addresses, due)
    }

    @Synchronized
    fun put(host: String, addresses: List<InetAddress>, ttlSeconds: Long, now: Long) {
        val ttlMs = ttlSeconds.coerceIn(MIN_TTL_S, MAX_TTL_S) * 1000
        entries[host] = Entry(ipv4First(addresses), now + ttlMs, lookingUp = false, failedAtMs = null)
    }

    /** Whether anything is cached for [host] (expired included). */
    @Synchronized
    fun has(host: String): Boolean = entries[host]?.addresses.orEmpty().isNotEmpty()

    /** A look-up gave nothing: what was cached stays. */
    @Synchronized
    fun failed(host: String, now: Long) {
        val entry = entries[host]
        entries[host] = Entry(entry?.addresses.orEmpty(), entry?.expiresAtMs ?: 0, lookingUp = false, failedAtMs = now)
    }

    @Synchronized
    fun forgetFailures() {
        for ((host, entry) in entries.entries.toList()) {
            if (entry.failedAtMs != null) entries[host] = Entry(entry.addresses, entry.expiresAtMs, entry.lookingUp, null)
        }
    }

    companion object {
        const val MIN_TTL_S = 5 * 60L
        const val MAX_TTL_S = 24 * 60 * 60L
        const val RETRY_AFTER_FAILURE_MS = 30_000L
    }
}

/**
 * The addresses of both servers (design 36 §5.3), and OkHttp's [Dns] for the DoH client - never the
 * system resolver (the launcher's own look-ups go to the VPN's fake DNS server, back into us).
 * An IP literal is itself, [CLOUDFLARE_BOOTSTRAP] its fixed address, a name what [cache] holds; a
 * name with nothing cached starts a look-up through [lookUp] (in the background, see [CustomDns])
 * and has no addresses for now. [lookUp] reports through [put]/[failed].
 */
class BootstrapDns(
    private val clock: () -> Long,
    private val lookUp: (host: String) -> Unit,
) : Dns {
    val cache = BootstrapCache()

    /** The addresses to connect to now, at most two, IPv4 first; empty = not looked up yet. */
    fun addresses(host: String): List<InetAddress> {
        ipLiteral(host)?.let { return listOf(it) }
        if (host == CLOUDFLARE_BOOTSTRAP.host) return listOf(CLOUDFLARE_BOOTSTRAP_ADDRESS)
        val found = cache.get(host, clock())
        if (found.lookUp) lookUp(host)
        return found.addresses
    }

    /** Never blocks: OkHttp asks only after [addresses] found some (the stale ones included). */
    override fun lookup(hostname: String): List<InetAddress> =
        addresses(hostname).ifEmpty { throw UnknownHostException("not looked up yet") }
}
