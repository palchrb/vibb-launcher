package me.vibb.music.sonos

import android.annotation.SuppressLint
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.ConnectException
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/*
 * The speaker's local event socket, the connection (design 53 §2.2): OkHttp's own WebSocket (no new
 * dependency) to `wss://<cached ip>:1443/websocket/api`, its lifetime ([socketWanted]), the host gate
 * and the pinned trust (the Sonos root, the udn: round 53-c), the backoff and the parks, the liveness rule and the health the session
 * reads. Ours (GPL-3.0 or later): vibb polls only. Android-free but for [SuppressLint] (the main
 * thread comes in as [SocketMain]); SonosSocketTest drives it through a fake [WebSocket.Factory].
 *
 * Events only: the socket never commands the speaker (UPnP does), never writes Room, and a URL
 * from a message (a `MOVED`'s or a player's `websocketUrl`) is never dialled - only the cached ip of
 * the uid we command, built with [HttpUrl.Builder] as every URL here.
 */

/**
 * The `X-Sonos-Api-Key` a speaker accepts on its local socket: the RFC 4122 example UUID (the one the
 * open-source `sonos-websocket` sends). Public, not a secret - it looks fake because it is the example.
 */
const val SONOS_WS_API_KEY = "123e4567-e89b-12d3-a456-426655440000"

/** The subprotocol, the port and the path the socket lives at (design 53 §0.1). */
const val SONOS_WS_PROTOCOL = "v1.api.smartspeaker.audio"
const val SONOS_WS_PORT = 1443
const val SONOS_WS_PATH = "/websocket/api"

/**
 * The socket's lifetime (design 53 §2.2): open while the session is [armed] (a play or an adopt armed
 * it - the polling rule, battery), Wi-Fi is up ([wifiNetwork]), the renderer isn't [closed] and the
 * [uid] isn't parked; closed at the disarm, a stop, the park, the hand-off and the release.
 */
fun socketWanted(armed: Boolean, wifiNetwork: Any?, closed: Boolean, parkedUntil: Long?, uid: String?, now: Long): Boolean =
    armed && wifiNetwork != null && !closed && !uid.isNullOrEmpty() && (parkedUntil == null || now >= parkedUntil)

/** `https://<ip>:1443/websocket/api` for an IP literal (OkHttp maps https to wss), or null. */
fun socketUrl(ip: String): HttpUrl? {
    if (!isIpLiteral(ip)) return null
    return try {
        HttpUrl.Builder().scheme("https").host(ip).port(SONOS_WS_PORT).encodedPath(SONOS_WS_PATH).build()
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * A speaker's address as design 53 §2.2 names it (qa-53a-code 6): RFC 1918 (10/8, 172.16/12, 192.168/16),
 * link-local 169.254/16, IPv6 ULA fc00::/7 and link-local fe80::/10 - never loopback, `0.0.0.0/8`,
 * CGNAT/the tailnet or anything the public-address guard's wider set also calls private. A literal is
 * parsed, never looked up.
 */
fun speakerLiteral(host: String): Boolean {
    if (!isIpLiteral(host)) return false
    val b = runCatching { InetAddress.getByName(host) }.getOrNull()?.address?.map { it.toInt() and 0xFF } ?: return false
    return when (b.size) {
        4 -> b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
        16 -> (b[0] and 0xFE) == 0xFC || (b[0] == 0xFE && (b[1] and 0xC0) == 0x80)
        else -> false
    }
}

/**
 * The host gate before any connect (design 53 §2.2): https to an IP literal in a speaker's range
 * ([speakerLiteral]) that is the cached ip of the uid we command ([cachedIp]), port 1443, the socket's
 * path. Anything else is never dialled.
 */
fun socketGate(url: HttpUrl, cachedIp: String?): Boolean {
    val cached = cachedIp?.let(::socketUrl) ?: return false
    return url.scheme == "https" && speakerLiteral(url.host) &&
        url.host == cached.host && url.port == SONOS_WS_PORT && url.encodedPath == SONOS_WS_PATH
}

/**
 * The Sonos Device Authentication Root CA (`O=Sonos, Inc`, `OU=Sonos Devices`, valid 2015-10-05..2055-09-25, SHA-256
 * [SONOS_ROOT_SHA256]): the root a speaker's own certificate chains to (design 53, "53-c status"). A public
 * certificate and nothing else - the copy in github.com/nickblt/sonos-websocket, `src/certs/sonos-ca.pem` (no code
 * taken from there), kept because its fingerprint matched the root of the user's speaker's own chain (`openssl
 * s_client` to the speaker at 1443, 2026-10-21: leaf -> this root, `Verify return code: 0 (ok)`). SonosTrustTest
 * checks the fingerprint.
 */
const val SONOS_ROOT_PEM = """
-----BEGIN CERTIFICATE-----
MIIGUzCCBDugAwIBAgIJAN526bolv8HvMA0GCSqGSIb3DQEBCwUAMIGVMQswCQYD
VQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNU2FudGEgQmFy
YmFyYTETMBEGA1UECgwKU29ub3MsIEluYzEWMBQGA1UECwwNU29ub3MgRGV2aWNl
czEsMCoGA1UEAwwjU29ub3MgRGV2aWNlIEF1dGhlbnRpY2F0aW9uIFJvb3QgQ0Ew
IBcNMTUxMDA1MDE1MDM4WhgPMjA1NTA5MjUwMTUwMzhaMIGVMQswCQYDVQQGEwJV
UzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNU2FudGEgQmFyYmFyYTET
MBEGA1UECgwKU29ub3MsIEluYzEWMBQGA1UECwwNU29ub3MgRGV2aWNlczEsMCoG
A1UEAwwjU29ub3MgRGV2aWNlIEF1dGhlbnRpY2F0aW9uIFJvb3QgQ0EwggIiMA0G
CSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCYP0pOHXxJNjApEW1sl1pXZmty0HAI
nDAO75u1wyazBmgtReQVK+OSrW3nm1obhZIaZy7tsz7oEgVI79pPwFSeEa9wsoaG
5UzfiasSu5rsf+s5yGL7nnN658nNEIVDZAJdh6FAXzIt2TsvBqYp7HdO66ZBEBb9
UK5848WlbQnLFyab4ZihuCHklxVzeOsalLDiQWdXhmM9GHar/gy9rufKvmSwlXPi
hhU5KgwoK9rW9pIb8bAbTT3uiRfQh8SmH041CT28YXSDKLvPnja8xbigIVG0r8Fo
w9L7F7az4lW1OaIuszNbejUjd+9yw4fBHeg9cEwcrkqkklIrtpjyDIpwN8dBf7Ff
kUBQhHlbQyX37PsCl8pbpKsMrEvjDbTJW893slmIjnf0XLYqAr+JOsKZDs9VQRED
mJJAUWKKz9H1J/Ed+rId7OGmAD1DeWDnHsgszU+38eS6Tcscdiuor+R94Wb+mSGg
VL6rWWsLTW4lbmcYcKYCt5KMycr8bVw4kDxHKJmZVqcug7GfyMpcy8alE9NgswaM
SNMpKPL/XABmvcLmd3O1xr15m5USrkdva1U/O90PLu73H1Uw/qGjx6KBKjygRDPl
grLRGBv+yHd3+5u0ztCD5Ag9R9xTxq5Pep/UwA4XTAZbsuOddH2lg+3iMYsDubEq
SmKdWDDK+W0zGwIDAQABo4GhMIGeMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYE
FPKs3CiyYmhuy6zGRmirXT9ti4IRMB8GA1UdIwQYMBaAFPKs3CiyYmhuy6zGRmir
XT9ti4IRMA4GA1UdDwEB/wQEAwIBBjA7BgNVHR8ENDAyMDCgLqAshipodHRwOi8v
Y2VydHMud3Muc29ub3MuY29tL2NlcnRzL2RldmljZS9jcmwwDQYJKoZIhvcNAQEL
BQADggIBAA/ISAUd1IKddANWy5MgqRCwJYWtGOriFqp9GcvPbrLUz6SfJ86lZCFz
4GSrTSKQICXVaGuLOnC7fQMiBTbq9R7eKih5hrQZz8O/LqvobgLUNMzS1ahvgqx8
Yz0avj6T4//MKQSd4mt5wS6b3zCl7AZwtwA9drCcoAyUegl2/Pp4DoIRm9w91POL
GurgSmHJ8D+8GZwyi5V9LWuvaglI97MzzmisgxRerkOEXFlT4fM6nT70bk39bFhK
CQZmrRigrYA0A1rHemt0xr1YtdUkK3Uyu1yvSScXguIq/H/SGnOtcCX/q6uOBHRO
hKAxLscUgEQefLbGo2awMJGdheNIAyTMA3jjd1rLpAFPbDaMPfDM8Wkmu0/SrJob
uBaHP/xDd5A47J0dv3enkFnuaYkt0w9qiiUzXQzk8h6Si5VQ65EyxLyU/xxRt7Bk
vU5XbaL5lL8AvAJBxO4Az1a+aLY0gXJzSCvuOH+9pdhDBU2DBr6ji9eCAp1fjESd
JG5kQlpYoDrr+BT4divD5Onnq6OY3l2iVQW8V5JzELtn+YXjekjxrRTUKB+AJe3o
PCBKkzssZY5+sXEQVIVXoRMk4xivZXyidTPqNlNFGuz57N7Qi/okHYOzeBuTZZiQ
lpUhu6Y8JTH5zRGs/zdwXTIDbB0hQyoDHlIRcHcP6Ywg4Y6C+OL7
-----END CERTIFICATE-----
"""

/** [SONOS_ROOT_PEM]'s SHA-256 over its DER, as `openssl x509 -fingerprint -sha256` prints it. */
const val SONOS_ROOT_SHA256 = "42:DC:D4:A7:D4:B2:3B:D2:86:83:B4:03:37:7B:A8:8A:16:9B:35:78:EC:94:F4:15:BB:E6:FD:48:3D:D5:A0:8F"

/** What a speaker's certificate names its player by: `urn:sonos:udn:RINCON_<...>`, a URI in its SAN. */
const val SONOS_UDN_PREFIX = "urn:sonos:udn:"

private val SONOS_ROOT: X509Certificate by lazy { pemCertificate(SONOS_ROOT_PEM) }

/** The pinned root ([SONOS_ROOT_PEM]), parsed once. */
fun sonosRoot(): X509Certificate = SONOS_ROOT

fun pemCertificate(pem: String): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(pem.trim().byteInputStream()) as X509Certificate

/**
 * A trust manager whose one anchor is [root]: the platform's own chain validation (the path built over the
 * intermediates the server sends, validity, signatures, the CA flags, the leaf's key usages) over a `KeyStore`
 * holding [root] alone - never the system's CAs, never trust-all.
 */
fun rootOnlyTrust(root: X509Certificate): X509TrustManager {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("pinned-root", root)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

/** The players a certificate names: its SAN's `urn:sonos:udn:` URIs, the prefix taken off (`RINCON_<...>`). */
fun certificateUdns(leaf: X509Certificate): List<String> =
    leaf.subjectAlternativeNames.orEmpty()
        .filter { it.size >= 2 && it[0] == SAN_URI }
        .mapNotNull { (it[1] as? String)?.takeIf { uri -> uri.startsWith(SONOS_UDN_PREFIX) }?.removePrefix(SONOS_UDN_PREFIX) }

/** `GeneralName`'s uniformResourceIdentifier, as `getSubjectAlternativeNames` numbers it. */
private const val SAN_URI = 6

/**
 * Why the speaker's [chain] is refused for [uid] - a reason class only, never anything of the certificate (its SAN
 * also holds the household, a user and the serial) - or null: accepted. Accepted = the chain verifies against
 * [roots] (the pinned root's [rootOnlyTrust]; a chain with intermediates under it too, as long as the speaker sends
 * them) and the leaf's SAN names our player: a `urn:sonos:udn:` equal to [uid], the uid whose cached ip was dialled.
 */
fun speakerCertRefusal(chain: Array<out X509Certificate>?, authType: String?, uid: String, roots: X509TrustManager): String? {
    if (chain.isNullOrEmpty()) return "no chain"
    try {
        roots.checkServerTrusted(chain, authType?.takeIf { it.isNotEmpty() } ?: "UNKNOWN")
    } catch (_: Exception) {
        return "chain"
    }
    val udns = try {
        certificateUdns(chain[0])
    } catch (_: Exception) {
        return "san unreadable"
    }
    return when {
        udns.isEmpty() -> "no udn"
        udns.none { it.equals(uid, ignoreCase = true) } -> "udn mismatch"
        else -> null
    }
}

/** A certificate the socket refused ([speakerCertRefusal]); its message is the reason class only. */
class SpeakerCertificateException(reason: String) : CertificateException("speaker certificate refused: $reason")

private val CN = Regex("""(?:^|[,+])CN=((?:\\.|[^,+\\])*)""")

/** The `CN` of a principal (RFC 2253, its escapes kept), or null. */
fun commonName(principal: X500Principal): String? = CN.find(principal.getName(X500Principal.RFC2253))?.groupValues?.get(1)

/**
 * The speaker's certificate, checked (design 53 §2.2 "Trust", round 53-c): its chain against the pinned Sonos root
 * only and its SAN's udn against the [uid] we dial ([speakerCertRefusal]), in the handshake - before any message.
 * A refusal logs its reason class ([log], every build) and fails the handshake with a [SpeakerCertificateException]:
 * a failed connect, counted as a refused one (the backoff, five in a row park the uid). A debug build ([certLog])
 * notes the leaf's subject CN and issuer CN with the verdict once per uid and process (design 53 §5 check 10) -
 * never the SAN.
 */
@SuppressLint("CustomX509TrustManager")
private class SpeakerTrust(
    private val uid: String,
    private val roots: X509TrustManager,
    private val log: (String) -> Unit,
    private val certLog: ((String) -> Unit)?,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("the speaker socket takes no client certificate")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val refusal = speakerCertRefusal(chain, authType, uid, roots)
        val leaf = chain?.firstOrNull()
        if (certLog != null && leaf != null && CERT_LOGGED.add(uid)) {
            val verdict = refusal?.let { "refused ($it)" } ?: "verified (the pinned root, the udn)"
            certLog.invoke(
                "sonos socket: certificate CN \"${commonName(leaf.subjectX500Principal)}\", issuer CN \"${commonName(leaf.issuerX500Principal)}\" - $verdict",
            )
        }
        if (refusal != null) {
            log("sonos socket: certificate refused ($refusal)")
            throw SpeakerCertificateException(refusal)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = roots.acceptedIssuers

    companion object {
        val CERT_LOGGED: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/**
 * The socket's own client for [ip], the cached ip of [uid]: the SOAP client's ([base]: unbound to any `Network`,
 * design 26a §6.5) with connect 2 s (the read timeout is cleared by OkHttp after the upgrade), the 30 s ping (the
 * probe's heartbeat: a missing pong closes it), [SpeakerTrust] over [root] alone (the pinned Sonos root; a test
 * passes its own) and a host name verifier that accepts exactly [ip]. Used for the socket and nothing else.
 */
fun speakerSocketClient(
    base: OkHttpClient,
    ip: String,
    uid: String,
    log: (String) -> Unit = {},
    certLog: ((String) -> Unit)? = null,
    root: X509Certificate = sonosRoot(),
): OkHttpClient {
    val trust = SpeakerTrust(uid, rootOnlyTrust(root), log, certLog)
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    val host = socketUrl(ip)?.host
    return base.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .sslSocketFactory(tls.socketFactory, trust)
        .hostnameVerifier { name, _ -> host != null && name == host }
        .build()
}

/** Why a connection ended. */
enum class SocketFailure {
    /**
     * A TCP refusal (an S1 speaker, a firmware without port 1443), or the speaker's certificate refused
     * ([SpeakerCertificateException]: another try meets the same certificate).
     */
    REFUSED,

    /** An HTTP answer instead of the upgrade (401/403/404: the key or the endpoint closed by a firmware update). */
    HTTP,

    /** A timeout, TLS (but a refused certificate), a reset, a close, a dead ping, a liveness miss. */
    OTHER,
}

/**
 * The reconnect delays (design 53 §2.2): 1, 2, 5, 15, 60 s, then every 300 s while wanted. They reset
 * only after a socket stayed healthy and passed a liveness check ([passed]), so a silent socket is
 * never rewarded. An HTTP answer, or [REFUSED_TO_PARK] refused connects in a row, park the uid for
 * [PARK_MS] (null from [failed]). Pure.
 */
class SocketBackoff {
    private var step = 0
    private var refused = 0

    /** The delay before the next connect, or null: park the uid. */
    fun failed(why: SocketFailure): Long? {
        if (why == SocketFailure.HTTP) return null
        if (why == SocketFailure.REFUSED) {
            if (++refused >= REFUSED_TO_PARK) return null
        } else {
            refused = 0
        }
        return DELAYS_MS[minOf(step++, DELAYS_MS.size - 1)]
    }

    /** The upgrade went through: the refused streak is over (the delays are not). */
    fun opened() {
        refused = 0
    }

    /** Healthy and live: the sequence starts over. */
    fun passed() {
        step = 0
        refused = 0
    }

    companion object {
        val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 60_000, 300_000)
        const val REFUSED_TO_PARK = 5
        const val PARK_MS = 6 * 3_600_000L
    }
}

/** Parked uids (until, elapsed), process memory only: a new renderer for the same room still waits. */
class SocketParks {
    private val until = ConcurrentHashMap<String, Long>()

    fun parkedUntil(uid: String, now: Long): Long? = until[uid]?.takeIf { it > now }

    fun park(uid: String, untilMs: Long) {
        until[uid] = untilMs
    }

    companion object {
        val PROCESS = SocketParks()
    }
}

/**
 * The liveness rule (design 53 §2.2, revision check N3): a verb of ours that must change the speaker's
 * state (a pause on a confirmed PLAYING, a resume on a confirmed PAUSED, a new item or a step) owes a
 * `playbackStatus` within [LIVENESS_MS] of its answer; none came, the socket is dead. Any event at or
 * after the verb's [owe] pays it. Pure.
 */
class Liveness {
    private var since: Long? = null
    private var deadline: Long? = null

    /**
     * The connection's last event (its receipt): the speaker pushes 0.0-0.4 s after a command, often before
     * the verb's SOAP answer reaches main, so a debt can be paid before it is owed (qa-53a-code 2).
     */
    private var lastEventAt: Long? = null

    /**
     * A verb went out at [since] and its answer came at [answeredAt]: true when an event at or after [since]
     * already came (paid at once - the check passed), else the event is owed within [LIVENESS_MS].
     */
    fun owe(since: Long, answeredAt: Long): Boolean {
        if (lastEventAt?.let { it >= since } == true) return true
        if (this.since == null) this.since = since
        deadline = answeredAt + LIVENESS_MS
        return false
    }

    /** An event at [at]: true when it paid an owed verb (the check passed). */
    fun seen(at: Long): Boolean {
        lastEventAt = at
        val s = since ?: return false
        if (at < s) return false
        since = null
        deadline = null
        return true
    }

    /** The owed event didn't come by its deadline. */
    fun missed(now: Long): Boolean = deadline?.let { now > it } ?: false

    /** A new connection owes and remembers nothing. */
    fun clear() {
        since = null
        deadline = null
        lastEventAt = null
    }

    companion object {
        /** Measured 0.0-0.4 s after a command (design 53 §0.1). */
        const val LIVENESS_MS = 3_000L
    }
}

/** The main thread, as the socket needs it ([post] runs in order; [postDelayed] after [delayMs]). */
interface SocketMain {
    fun post(block: () -> Unit)

    fun postDelayed(delayMs: Long, block: () -> Unit)
}

/** The event socket as the controller sees it (design 53 §2.2-§2.3). */
interface SonosEventLink {
    /** Healthy now: open, our coordinator's playback subscribed, its status read, and live. Any thread. */
    val healthy: Boolean

    /** A verb of ours that must change the state went out at [since] and was answered at [answeredAt]: an event is owed. */
    fun owed(since: Long, answeredAt: Long) {}

    /** Two polls in a row saw another state than the socket's (design 53 §2.2, "Disagreement"). */
    fun disagreed() {}

    companion object {
        val NONE = object : SonosEventLink {
            override val healthy: Boolean get() = false
        }
    }
}

/**
 * One room's socket: main-thread confined but for [healthy] (volatile, read by the session on IO)
 * and the listener, which parses on OkHttp's reader thread, sends the handshake's commands from
 * there (`WebSocket.send` is thread-safe) and posts compact [SocketEvent]s to the main thread.
 * [want] is the only door: a target (the uid, its Wi-Fi network) opens or re-targets it, null
 * closes it. Every failure leaves the app on polling - speed lost, never a feature.
 */
class SonosSocket(
    /** The socket's own client for a uid's cached ip ([speakerSocketClient]); an [OkHttpClient] is a [WebSocket.Factory]. */
    private val clientFor: (ip: String, uid: String) -> WebSocket.Factory,
    /** The cached ip of a uid (`SpeakerCache.resolve`). */
    private val resolveIp: (uid: String) -> String?,
    private val clock: () -> Long,
    private val main: SocketMain,
    /** On the main thread. */
    private val onEvent: (SocketEvent) -> Unit,
    private val log: (String) -> Unit = {},
    private val parks: SocketParks = SocketParks.PROCESS,
) : SonosEventLink {
    /** What the socket is for: the uid we command on the Wi-Fi network it was asked on. */
    data class Target(val uid: String, val network: Any)

    private class Conn(val id: Int, val ws: WebSocket, val uid: String) {
        var opened = false
        var planHealthy = false
    }

    private var target: Target? = null
    private var conn: Conn? = null
    private var seq = 0
    private var retryToken = 0
    private var backoff = SocketBackoff()
    private val liveness = Liveness()

    /** The cached ip failed the host gate: nothing is dialled for this target. */
    private var gateRefused = false

    @Volatile
    private var healthyNow = false

    override val healthy: Boolean get() = healthyNow

    fun parkedUntil(uid: String): Long? = parks.parkedUntil(uid, clock())

    /** Open, re-target or close (null). A change of the uid or of the Wi-Fi network closes the old one first. */
    fun want(next: Target?) {
        if (next == target) return
        target = next
        close("re-target")
        backoff = SocketBackoff()
        gateRefused = false
        retryToken++
        if (next != null) connect()
    }

    private fun connect() {
        val t = target ?: return
        if (conn != null || gateRefused) return
        val now = clock()
        parks.parkedUntil(t.uid, now)?.let {
            log("sonos socket: ${t.uid} parked for ${(it - now) / 60_000} min - polling only")
            return
        }
        val ip = resolveIp(t.uid)
        val url = ip?.let(::socketUrl)
        if (url == null || !socketGate(url, ip)) {
            // a public or unknown address is never dialled (design 53 §2.2): polling only, until a new target
            gateRefused = true
            log("sonos socket: ${t.uid} has no private ip literal in the cache - not dialled")
            return
        }
        val id = ++seq
        val request = Request.Builder()
            .url(url)
            .header("X-Sonos-Api-Key", SONOS_WS_API_KEY)
            .header("Sec-WebSocket-Protocol", SONOS_WS_PROTOCOL)
            .build()
        val ws = try {
            clientFor(ip, t.uid).newWebSocket(request, Listener(id, t.uid, ip))
        } catch (e: Exception) {
            log("sonos socket: no client (${e.javaClass.simpleName})")
            return retry(t, SocketFailure.OTHER)
        }
        liveness.clear() // the last event and any debt are this connection's
        conn = Conn(id, ws, t.uid)
    }

    /** Closes the connection we have (not a failure: nothing is counted). */
    private fun close(why: String) {
        val c = conn ?: return
        conn = null
        liveness.clear()
        c.ws.cancel()
        log("sonos socket: closed ($why)")
        health()
    }

    private fun retry(t: Target, why: SocketFailure) {
        val delay = backoff.failed(why)
        if (delay == null) {
            parks.park(t.uid, clock() + SocketBackoff.PARK_MS)
            log("sonos socket: ${t.uid} parked for 6 h ($why) - polling only")
            return
        }
        val token = ++retryToken
        main.postDelayed(delay) { if (token == retryToken && target == t) connect() }
    }

    private fun health() {
        val c = conn
        val h = c != null && c.opened && c.planHealthy
        if (h == healthyNow) return
        healthyNow = h
        log("sonos socket: ${if (h) "healthy" else "not healthy"}")
        onEvent(SocketEvent.Health(h))
    }

    // -- from the listener, on the main thread (a stale connection's word is dropped) --

    private fun opened(id: Int) {
        val c = conn?.takeIf { it.id == id } ?: return
        c.opened = true
        backoff.opened()
        log("sonos socket: open to ${c.uid}")
    }

    private fun planHealth(id: Int, healthy: Boolean) {
        val c = conn?.takeIf { it.id == id } ?: return
        c.planHealthy = healthy
        health()
    }

    private fun event(id: Int, e: SocketEvent) {
        if (conn?.id != id) return
        if (e is SocketEvent.Playback && liveness.seen(e.at) && healthyNow) backoff.passed()
        onEvent(e)
    }

    private fun ended(id: Int, why: SocketFailure, detail: String) {
        val c = conn?.takeIf { it.id == id } ?: return
        conn = null
        liveness.clear()
        log("sonos socket: ended ($why: $detail)")
        health()
        target?.takeIf { it.uid == c.uid }?.let { retry(it, why) }
    }

    /** The liveness miss, the disagreement: closed and counted as a failure. */
    private fun dead(why: String) {
        val c = conn ?: return
        c.ws.cancel()
        ended(c.id, SocketFailure.OTHER, why)
    }

    // -- the controller's hooks (main) --

    override fun owed(since: Long, answeredAt: Long) {
        if (!healthyNow) return
        if (liveness.owe(since, answeredAt)) {
            // its event came before the answer reached main: live (the backoff starts over, as in event())
            backoff.passed()
            return
        }
        val id = conn?.id ?: return
        main.postDelayed(Liveness.LIVENESS_MS + 1) {
            if (conn?.id == id && liveness.missed(clock())) dead("no event for our verb within ${Liveness.LIVENESS_MS} ms")
        }
    }

    override fun disagreed() {
        if (healthyNow) dead("two polls disagree with its state")
    }

    /** OkHttp's callbacks run on its reader thread: parse there, post to main. */
    private inner class Listener(private val id: Int, uid: String, ip: String) : WebSocketListener() {
        private val plan = SonosEvents.Plan(uid, ip)
        private var lastHealthy = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post { opened(id) }
            for (c in plan.start()) webSocket.send(c)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val at = clock()
            val m = SonosEvents.parse(text) ?: return
            val step = plan.receive(m, at)
            for (c in step.commands) webSocket.send(c)
            for (e in step.events) main.post { event(id, e) }
            val h = plan.healthy
            if (h != lastHealthy) {
                lastHealthy = h
                main.post { planHealth(id, h) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { ended(id, SocketFailure.OTHER, "closed $code") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // the platform wraps the trust manager's refusal (an SSLHandshakeException caused by it)
            val certificate = generateSequence(t) { it.cause }.take(8).any { it is SpeakerCertificateException }
            val why = when {
                response != null -> SocketFailure.HTTP
                t is ConnectException || certificate -> SocketFailure.REFUSED
                else -> SocketFailure.OTHER
            }
            // never the message: a platform's TLS error may quote the certificate
            val detail = response?.let { "HTTP ${it.code}" } ?: if (certificate) "certificate refused" else t.javaClass.simpleName
            main.post { ended(id, why, detail) }
        }
    }
}
