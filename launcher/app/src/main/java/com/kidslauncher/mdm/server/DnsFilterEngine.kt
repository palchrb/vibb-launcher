package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Socket
import okhttp3.OkHttpClient
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section

private const val LOG_TAG = "DnsFilterEngine"
private const val BLOCKLIST_FILE = "dns_blocklist.tsv"
private const val VERSION_FILE = "dns_blocklist_version.txt"

/** The category of the built-in secure-DNS hosts (design 67 §2.2, [DOH_HOSTS_ASSET]). */
const val DOH_BYPASS_CATEGORY = "Secure DNS bypass"

/** The built-in list's asset, under `assets/`. */
const val DOH_HOSTS_ASSET = "dns/doh_hosts.txt"

/** Firefox's canary domain (design 67 §2.3): the exact name, never a subdomain. */
const val FIREFOX_CANARY = "use-application-dns.net"

/** [domainRaw] is Firefox's canary, in any case, with or without its trailing dot. */
fun isFirefoxCanary(domainRaw: String): Boolean = domainRaw.trimEnd('.').lowercase() == FIREFOX_CANARY

/** The built-in list's names ([DOH_HOSTS_ASSET]): one per line, `#` starts a comment. */
fun parseDohHosts(text: String): Set<String> =
    text.lineSequence().map { it.substringBefore('#').trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

/** The first of [domain]'s label suffixes (itself, then each parent) [lookup] gives a value for -
 * the technique of the server's own `is_blocked` (`dns_engine.rs`): "example.com" matches "ads.example.com". */
private inline fun <T : Any> firstSuffix(domain: String, lookup: (String) -> T?): T? {
    var start = 0
    while (start < domain.length) {
        lookup(domain.substring(start))?.let { return it }
        val dot = domain.indexOf('.', start)
        if (dot < 0) break
        start = dot + 1
    }
    return null
}

/** The category [domainRaw] is blocked by (a label-suffix match), or null if not blocked: always for
 * the FCM hosts ([com.kidslauncher.mdm.push.isFcmHost]), and for Spotify's hosts
 * ([com.kidslauncher.mdm.music.isSpotifyHost]) while [spotify] - the cached policy's
 * `music.spotify` - is true (design 26c §6.2: per phone, not global like FCM). Both exemptions come
 * before the suffix walk, so a blocked parent (`google.com`, `akamaized.net`) never blocks them.
 * Then the built-in secure-DNS hosts [dohHosts] ([DOH_BYPASS_CATEGORY], design 67 §2.2), then the
 * server's lists [blocked]. Asked only for a query that came through the tunnel
 * ([KidVpnService.handlePacket]): the launcher's own upstream look-ups never arrive there (IP
 * literals, [BootstrapDns] on protected sockets), so a parent's backup by such a name still works.
 * Pure; DnsFilterEngineTest. */
internal fun classifyDomain(blocked: Map<String, String>, domainRaw: String, spotify: Boolean, dohHosts: Set<String> = emptySet()): String? {
    val domain = domainRaw.trimEnd('.').lowercase()
    // FCM's own hosts are never blocked (handy step 7, QA #10): a blocklist entry for them or
    // a parent (google.com, googleapis.com) would silently stop other apps' FCM (Element X).
    if (com.kidslauncher.mdm.push.isFcmHost(domain)) return null
    if (spotify && com.kidslauncher.mdm.music.isSpotifyHost(domain)) return null
    if (firstSuffix(domain) { it.takeIf(dohHosts::contains) } != null) return DOH_BYPASS_CATEGORY
    return firstSuffix(domain) { blocked[it] }
}

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

    /** The built-in secure-DNS hosts (design 67 §2.2), read from the APK at every VPN start. */
    @Volatile
    private var dohHosts: Set<String> = emptySet()

    /** The cached policy's `music.spotify` (design 26c §6.2): this phone plays Spotify, so its hosts
     * pass ([classifyDomain]). Set by every sync ([setSpotify]) and from the cached policy when the
     * VPN starts ([loadFromDisk]). */
    @Volatile
    private var spotifyOn: Boolean = false

    /** The policy's `music.spotify`, from the sync (a fresh policy or the cached one). */
    fun setSpotify(on: Boolean) {
        spotifyOn = on
    }

    /** The parent's own server and its backup (design 36 §5.1), parsed from the pref
     * `dns_upstream_custom`: set by every accepted sync ([setCustomUpstream]) and from the pref when
     * the VPN starts ([loadFromDisk]); `null` = forward to the provider. */
    @Volatile
    private var customUpstream: CustomUpstream? = null

    /** Told when [setCustomUpstream] runs ([KidVpnService] while its VPN is up). */
    @Volatile
    var onCustomChanged: (() -> Unit)? = null

    fun customUpstream(): CustomUpstream? = customUpstream

    /** The accepted policy's servers, after they were committed with it (`MdmSyncWorker`). */
    fun setCustomUpstream(upstream: CustomUpstream?) {
        customUpstream = upstream
        onCustomChanged?.invoke()
    }

    /** Loads whatever's cached on disk into memory - call once when [KidVpnService] starts, before
     * the packet loop begins (a device reboot restarts the service with an empty in-memory map,
     * independent of whether a sync has happened yet this boot). No-ops if nothing's cached yet -
     * the filter simply blocks nothing until the first successful sync populates it, matching this
     * project's established policy-cache-miss handling elsewhere (fails open on missing data, not
     * closed, since there is no safe "assume blocked" default without a real list to check against). */
    fun loadFromDisk(context: Context) {
        // First, before the list's early return: in force on a phone that never synced a list.
        try {
            dohHosts = parseDohHosts(context.assets.open(DOH_HOSTS_ASSET).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't load the built-in secure-DNS hosts: ${e.javaClass.simpleName}")
        }
        spotifyOn = runCatching {
            com.kidslauncher.mdm.music.musicSpotify((cachedPolicy() as? CachedPolicy.Ok)?.policy?.music)
        }.getOrDefault(false)
        customUpstream = customUpstreamFromPref(com.kidslauncher.mdm.preferences.LauncherPreferences.mdm().dnsUpstreamCustom())
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

    /** The category [domainRaw] is blocked by, or null ([classifyDomain] over the built-in secure-DNS
     * hosts and the loaded list, with Spotify's hosts exempt while this phone plays Spotify). */
    fun classify(domainRaw: String): String? = classifyDomain(blockedDomains, domainRaw, spotifyOn, dohHosts)

    /**
     * Forwards an allowed DNS query to the admin-configured public resolver (Cloudflare or Quad9,
     * [PublicResolvers.of]): DoT by its fixed address, SNI and certificate on its name, with
     * [PROVIDER_BUDGET] (2.5 s connect), and on a network that blocks 853 the same provider's DoH
     * on 443 through [dohClient], built at its first use (design 36 step 3). Returns the response to
     * relay, its bytes null on any failure (the query is dropped; the client's own retry handles it).
     * The parent's own server goes through [CustomDns] instead.
     */
    fun resolveProvider(query: ByteArray, upstream: String, protectSocket: (Socket) -> Boolean, dohClient: () -> OkHttpClient): Resolved =
        relayed(PublicResolvers.of(upstream).resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, targetTransport(dohClient, protectSocket)))
}

/** The phone's block answer: NODATA (NOERROR, empty answer, a negative-cache SOA in the authority
 * section) - well handled by every client, without the retry storms NXDOMAIN sometimes provokes.
 * Also what a healthy custom server's REFUSED becomes (design 36 §5.4). */
internal fun blockedResponse(request: Message): ByteArray = negativeResponse(request, Rcode.NOERROR)

/** NXDOMAIN for [request], with [blockedResponse]'s SOA: Firefox's canary (design 67 §2.3) - any
 * RCODE but NOERROR turns off the DoH that Firefox enabled by default. */
internal fun nxdomainResponse(request: Message): ByteArray = negativeResponse(request, Rcode.NXDOMAIN)

/** [rcode], the question echoed, and a negative-cache SOA (RFC 2308) in the authority section. */
private fun negativeResponse(request: Message, rcode: Int): ByteArray {
    val response = answerTo(request, rcode)
    response.addRecord(
        SOARecord(
            request.question.name, DClass.IN, 300L,
            Name.fromConstantString("localhost."),
            Name.fromConstantString("admin.localhost."),
            1L, 300L, 300L, 300L, 300L,
        ),
        Section.AUTHORITY,
    )
    return response.toWire()
}

/** SERVFAIL for [request]: a healthy custom server's SERVFAIL or silence (design 36 §5.4). */
internal fun servfailResponse(request: Message): ByteArray = answerTo(request, Rcode.SERVFAIL).toWire()

private fun answerTo(request: Message, rcode: Int): Message {
    val response = Message(request.header.id)
    response.header.setFlag(Flags.QR.toInt())
    if (request.header.getFlag(Flags.RD.toInt())) response.header.setFlag(Flags.RD.toInt())
    response.header.setFlag(Flags.RA.toInt())
    response.header.rcode = rcode
    response.addRecord(request.question, Section.QUESTION)
    return response
}
