package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val LOG_TAG = "DnsFilterEngine"
private const val BLOCKLIST_FILE = "dns_blocklist.tsv"
private const val VERSION_FILE = "dns_blocklist_version.txt"

/**
 * The launcher's on-device ad/content blocklist. [KidVpnService] is the actual DNS interception
 * point (see its doc comment for the full local-filtering architecture); this object just owns
 * the compiled blocklist data, the block/allow decision for a given domain, and forwarding
 * allowed queries upstream over DNS-over-TLS to the admin-configured public resolver.
 *
 * The compiled list (potentially 100k+ domains) is cached to a plain tab-separated file in
 * [Context.filesDir] rather than SharedPreferences, which isn't designed for blobs this large.
 * Only re-fetched from the server when [PolicyResponse.dnsFilterVersion][com.kidslauncher.mdm.server.dto.PolicyResponse]
 * changes - see [refreshIfNeeded], called from [MdmSyncWorker] after every policy fetch - so a
 * normal 2-minute sync cycle does not re-download the full list every time.
 */
object DnsFilterEngine {
    // domain -> category (e.g. "Adult content"). A HashMap lookup per label-suffix (see classify)
    // is fast enough for the packet-handling hot path even at 100k+ entries.
    @Volatile
    private var blockedDomains: Map<String, String> = emptyMap()

    @Volatile
    private var loadedVersion: String? = null

    private val upstreamServers = mapOf(
        "cloudflare" to Pair("1.1.1.1", "cloudflare-dns.com"),
        "quad9" to Pair("9.9.9.9", "dns.quad9.net"),
    )

    /** Loads whatever's cached on disk into memory - call once when [KidVpnService] starts, before
     * the packet loop begins (a device reboot restarts the service with an empty in-memory map,
     * independent of whether a sync has happened yet this boot). No-ops if nothing's cached yet -
     * the filter simply blocks nothing until the first successful sync populates it, matching this
     * project's established policy-cache-miss handling elsewhere (fails open on missing data, not
     * closed, since there is no safe "assume blocked" default without a real list to check against). */
    fun loadFromDisk(context: Context) {
        try {
            val listFile = File(context.filesDir, BLOCKLIST_FILE)
            if (!listFile.exists()) return
            val map = HashMap<String, String>()
            listFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            blockedDomains = map
            val versionFile = File(context.filesDir, VERSION_FILE)
            loadedVersion = if (versionFile.exists()) versionFile.readText().trim() else null
            Log.i(LOG_TAG, "Loaded ${map.size} blocked domains from disk (version=$loadedVersion)")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to load cached blocklist", e)
        }
    }

    /** Re-fetches the full blocklist from the server if [newVersion] differs from what's already
     * loaded. Safe to call even if [KidVpnService] isn't running yet - just updates the on-disk
     * cache; the service picks it up via [loadFromDisk] the next time it (re)starts, or immediately
     * if it's already running, since this also updates the in-memory map directly. */
    suspend fun refreshIfNeeded(context: Context, api: MdmApi, newVersion: String?) {
        if (newVersion.isNullOrBlank() || newVersion == loadedVersion) return
        try {
            val response = api.getDnsBlocklist()
            if (!response.isSuccessful) return
            val categories = response.body() ?: return

            val map = HashMap<String, String>()
            val listFile = File(context.filesDir, BLOCKLIST_FILE)
            listFile.bufferedWriter().use { writer ->
                for (cat in categories) {
                    for (domain in cat.domains) {
                        map[domain] = cat.category
                        writer.write(domain)
                        writer.write("\t")
                        writer.write(cat.category)
                        writer.write("\n")
                    }
                }
            }
            File(context.filesDir, VERSION_FILE).writeText(newVersion)
            blockedDomains = map
            loadedVersion = newVersion
            Log.i(LOG_TAG, "Refreshed blocklist: ${map.size} domains (version=$newVersion)")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to refresh blocklist", e)
        }
    }

    /** Walks label suffixes (same technique the server's own `is_blocked` uses in `dns_engine.rs`)
     * so a block entry for "example.com" also matches "ads.example.com" - returns the matched
     * category, or null if not blocked (always for the FCM hosts, [com.kidslauncher.mdm.push.isFcmHost]). */
    fun classify(domainRaw: String): String? {
        val domain = domainRaw.trimEnd('.').lowercase()
        // FCM's own hosts are never blocked (handy step 7, QA #10): a blocklist entry for them or
        // a parent (google.com, googleapis.com) would silently stop every sync nudge.
        if (com.kidslauncher.mdm.push.isFcmHost(domain)) return null
        var start = 0
        while (start < domain.length) {
            blockedDomains[domain.substring(start)]?.let { return it }
            val dot = domain.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        return null
    }

    /**
     * Forwards an allowed DNS query to the admin-configured public upstream over DNS-over-TLS
     * (RFC 7858: a 2-byte big-endian length prefix, then the raw DNS message, over TLS on port
     * 853) - a small, well-defined protocol, no library needed. Returns the raw response message
     * bytes, or null on any failure (caller should just drop the query - the client's own retry/
     * timeout handles a dropped response the same as any transient DNS failure).
     *
     * [protectSocket] must be [android.net.VpnService.protect], called on the plain TCP socket
     * before the TLS handshake - without it, this connection would itself get swept into
     * [KidVpnService]'s own tunnel and never reach the real network (Android routes any socket the
     * active VPN app itself opens through its own tunnel by default, regardless of whether the
     * destination matches an added route - the same reason DNS66's own upstream-forwarding socket
     * needs this).
     *
     * SNI/hostname verification uses the resolver's real hostname (e.g. "cloudflare-dns.com"),
     * even though the actual TCP connection targets a pinned IP - connecting by IP avoids yet
     * another DNS lookup just to resolve the resolver itself, but the certificate must still be
     * validated against the name the cert was actually issued for, not the bare IP (a bare
     * SSLSocket does not do this by default unless explicitly configured, unlike HttpsURLConnection).
     */
    fun resolveUpstream(query: ByteArray, upstream: String, protectSocket: (Socket) -> Boolean): ByteArray? {
        val (ip, tlsHostname) = upstreamServers[upstream] ?: upstreamServers.getValue("cloudflare")
        return try {
            Socket().use { plain ->
                protectSocket(plain)
                plain.connect(InetSocketAddress(ip, 853), 5000)
                // SSLSocketFactory.getDefault()'s *declared* return type is the base SocketFactory
                // class (a real JDK API quirk, not a typo) - createSocket(Socket, String, int,
                // boolean) is only declared on SSLSocketFactory itself, so an explicit cast is
                // required even though the runtime object always actually is one.
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(plain, tlsHostname, 853, true) as SSLSocket
                tls.use { socket ->
                    val params = socket.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    params.serverNames = listOf(SNIHostName(tlsHostname))
                    socket.sslParameters = params
                    socket.soTimeout = 5000
                    socket.startHandshake()

                    val out = socket.outputStream
                    out.write(byteArrayOf((query.size shr 8).toByte(), (query.size and 0xFF).toByte()))
                    out.write(query)
                    out.flush()

                    val input = socket.inputStream
                    val lenBytes = ByteArray(2)
                    readFully(input, lenBytes)
                    val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                    val resp = ByteArray(respLen)
                    readFully(input, resp)
                    resp
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Upstream DoT query to $upstream failed", e)
            null
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw EOFException("upstream closed connection mid-response")
            offset += n
        }
    }
}
