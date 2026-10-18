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

/**
 * [host] as an IP address when it is an IP literal (dotted-quad IPv4, or IPv6 with or without
 * brackets), else `null`. Parsed here into bytes, never by [InetAddress.getByName]: on Android a
 * string that isn't numeric would be a system look-up (into our own VPN).
 */
fun ipLiteral(host: String): InetAddress? {
    val bare = host.removePrefix("[").removeSuffix("]")
    val bytes = (if (':' in bare) ipv6Bytes(bare) else ipv4Bytes(bare)) ?: return null
    return InetAddress.getByAddress(bytes)
}

private fun ipv4Bytes(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}

private val HEX_GROUP = Regex("[0-9A-Fa-f]{1,4}")

private fun ipv6Bytes(text: String): ByteArray? {
    var groups = text
    // An embedded IPv4 tail (`::ffff:192.0.2.1`) becomes two hex groups.
    val tail = text.substringAfterLast(':')
    if ('.' in tail) {
        val v4 = ipv4Bytes(tail) ?: return null
        val hi = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
        val lo = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
        groups = text.substring(0, text.length - tail.length) + "%x:%x".format(hi, lo)
    }
    val halves = groups.split("::")
    if (halves.size > 2) return null
    fun list(part: String): List<String>? =
        if (part.isEmpty()) emptyList() else part.split(':').takeIf { gs -> gs.all { HEX_GROUP.matches(it) } }
    val head = list(halves[0]) ?: return null
    val rest = if (halves.size == 2) list(halves[1]) ?: return null else emptyList()
    val count = head.size + rest.size
    if (if (halves.size == 2) count > 7 else count != 8) return null
    val all = head + List(8 - count) { "0" } + rest
    val bytes = ByteArray(16)
    for ((i, group) in all.withIndex()) {
        val value = group.toInt(16)
        bytes[2 * i] = (value shr 8).toByte()
        bytes[2 * i + 1] = value.toByte()
    }
    return bytes
}

/** The last fallback for the bootstrap resolver (design 36 §5.3): Cloudflare over DoT, by its
 * fixed address - the only case a look-up leaves for anyone but the parent's servers. Asked through
 * [PublicResolvers.cloudflare], so on a network that blocks 853 it is Cloudflare's DoH on 443 (step 3). */
val CLOUDFLARE_BOOTSTRAP = CLOUDFLARE_DOT

/**
 * Where the servers' names are looked up (design 36 §5.3), in order: the backup when it is an IP
 * literal, the primary when it is, then [CLOUDFLARE_BOOTSTRAP]. A look-up (only a look-up, never a
 * query) moves to the next when one fails at the transport level, so a home-network primary by IP
 * away from home still lets the backup's name be found. Empty when both are IP literals (nothing to
 * look up).
 */
fun bootstrapResolvers(config: CustomUpstream): List<UpstreamTarget> {
    val backupIp = ipLiteral(config.backup.serverHost) != null
    val primaryIp = ipLiteral(config.primary.serverHost) != null
    if (backupIp && primaryIp) return emptyList()
    return listOfNotNull(config.backup.takeIf { backupIp }, config.primary.takeIf { primaryIp }, CLOUDFLARE_BOOTSTRAP)
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
 * fails. A name is looked up once at a time, and - stale or with nothing cached - not again within
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
            addresses.isNotEmpty() && now < entry.expiresAtMs -> false
            // Stale or missing: refresh, but not again within 30 s of a failed one.
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

    /** Every name with addresses and when they expire (elapsed ms), for the disk cache. */
    @Synchronized
    fun snapshot(): Map<String, CachedAddresses> = entries
        .filterValues { it.addresses.isNotEmpty() }
        .mapValues { (_, entry) -> CachedAddresses(entry.addresses, entry.expiresAtMs) }

    /** What the disk cache held, before any look-up: fresh until it expires, then stale (served
     * while one refresh runs, like any expired entry). */
    @Synchronized
    fun seed(host: String, cached: CachedAddresses) {
        entries[host] = Entry(ipv4First(cached.addresses), cached.expiresAtMs, lookingUp = false, failedAtMs = null)
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
        if (host == CLOUDFLARE_BOOTSTRAP.host) return listOf(CLOUDFLARE_ADDRESS)
        val found = cache.get(host, clock())
        if (found.lookUp) lookUp(host)
        return found.addresses
    }

    /** Never blocks: OkHttp asks only after [addresses] found some (the stale ones included). */
    override fun lookup(hostname: String): List<InetAddress> =
        addresses(hostname).ifEmpty { throw UnknownHostException("not looked up yet") }
}

/** A name's addresses and when they expire (elapsed realtime ms). */
data class CachedAddresses(val addresses: List<InetAddress>, val expiresAtMs: Long)

/**
 * The bootstrap's disk cache (design 36 §5.3's last sentence, step 3): the looked-up addresses of the
 * parent's servers' names with their expiry, so a reboot on a network where no look-up gets through
 * still finds them. It holds a host name the parent typed, so it lives in CE storage only
 * (`noBackupFilesDir`, 0600 by default - never device-protected storage) and is never logged. A
 * line per name: the host, the expiry as wall-clock ms (elapsed time restarts at boot), the
 * addresses. Pure; the file is written through a temp file and a rename ([writeAtomically]).
 */
object BootstrapDiskCache {
    const val FILE_NAME = "dns_bootstrap_cache.txt"
    private const val VERSION = "v1"

    fun encode(entries: Map<String, CachedAddresses>, elapsedNow: Long, wallNow: Long): String = buildString {
        append(VERSION).append('\n')
        for ((host, cached) in entries.toSortedMap()) {
            append(host).append('\t').append(wallNow + (cached.expiresAtMs - elapsedNow)).append('\t')
            append(cached.addresses.joinToString(",") { it.hostAddress.orEmpty() }).append('\n')
        }
    }

    /**
     * The entries for [hosts] (the current config's names), their expiry back in elapsed time - at
     * most [BootstrapCache.MAX_TTL_S] ahead, whatever the wall clock did. Anything malformed makes the
     * whole file nothing (an address is parsed into bytes, never looked up).
     */
    fun decode(text: String, hosts: Set<String>, elapsedNow: Long, wallNow: Long): Map<String, CachedAddresses> {
        val lines = text.lines().dropLastWhile { it.isEmpty() }
        if (lines.firstOrNull() != VERSION) return emptyMap()
        val found = HashMap<String, CachedAddresses>()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            if (parts.size != 3) return emptyMap()
            val expiresWall = parts[1].toLongOrNull() ?: return emptyMap()
            val addresses = parts[2].split(',').map { ipLiteral(it) ?: return emptyMap() }
            if (addresses.size > 2) return emptyMap()
            if (parts[0] !in hosts) continue
            val ahead = minOf(expiresWall - wallNow, BootstrapCache.MAX_TTL_S * 1000)
            found[parts[0]] = CachedAddresses(addresses, elapsedNow + ahead)
        }
        return found
    }

    /** The number of name lines in [text] (what [decode] would read if every one were this config's). */
    fun lineCount(text: String): Int = text.lines().drop(1).count { it.isNotEmpty() }

    /** Whether the file may exist at all: a custom server with a name to look up. */
    fun keptFor(config: CustomUpstream?): Boolean = config != null && namesOf(config).isNotEmpty()

    /** The names of [config] that are looked up (IP literals and Cloudflare's fixed name never are). */
    fun namesOf(config: CustomUpstream): Set<String> = listOf(config.primary.serverHost, config.backup.serverHost)
        .filter { ipLiteral(it) == null && it != CLOUDFLARE_BOOTSTRAP.host }
        .toSet()
}

/** [text] into [file] through a temp file next to it and a rename, so a reader finds the old file or
 * the new one, never half of one. */
fun writeAtomically(file: java.io.File, text: String) {
    val temp = java.io.File(file.parentFile, file.name + ".tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
        temp.delete()
        throw java.io.IOException("rename failed")
    }
}
