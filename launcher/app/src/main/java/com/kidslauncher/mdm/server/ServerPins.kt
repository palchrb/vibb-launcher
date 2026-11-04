package com.kidslauncher.mdm.server

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Handshake

/*
 * Design 76: the server's certificate issuer, pinned (docs/design/76-server-issuer-pin.md). Pure rules, no Android:
 * the built-in set B, the stored flag's rule, the ring of outside sightings, the anchor of OkHttp's pin failure.
 * The phone always checks and reports what it sees; it enforces only once the server switched it on (`nextFlag`), and
 * then over https only through a chain Android validated that holds a key of B ([toServer]'s pinner, after Android's
 * validation, never instead of it). Android glue: [ServerPinStore].
 */

/** A root of the built-in set: its name and `base64(SHA-256(DER SubjectPublicKeyInfo))` (OkHttp's pin without its
 * `sha256/`). */
data class BuiltinPin(val name: String, val pin: String)

/**
 * The built-in set B (design 76 D1): Let's Encrypt's roots and ZeroSSL's operator Sectigo's. A pinned operator's new
 * root goes in as soon as its certificate is published, never when a store adds it; B never gains another operator
 * without the user's decision. `ServerPinsTest` computes every pin from the roots' certificates (test resources).
 */
val BUILTIN_PINS: List<BuiltinPin> = listOf(
    BuiltinPin("ISRG Root X1", "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="),
    BuiltinPin("ISRG Root X2", "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="),
    BuiltinPin("Root YR", "fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="),
    BuiltinPin("Root YE", "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="),
    BuiltinPin("USERTrust RSA Certification Authority", "x4QzPSC810K5/cMjb05Qm4k3Bw5zBn4lTdO/nEW/Td4="),
    BuiltinPin("USERTrust ECC Certification Authority", "ICGRfpgmOUXIWcQ/HXPLQTkFPEFPoDyjvH7ohhQpjzs="),
    BuiltinPin("Sectigo Public Server Authentication Root R46", "Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q="),
    BuiltinPin("Sectigo Public Server Authentication Root E46", "sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE="),
)

/** The status report's capability: this launcher reads the policy's `server_tls` and reports its own. */
const val SERVER_PIN_CAPABILITY = "server_pin_v1"

/** At most this many outside sightings are kept and reported (design 76 D3). */
const val OUTSIDE_MAX = 8

/** A reported anchor name is cut to this many characters (qa L-5). */
const val ANCHOR_NAME_MAX = 64

/** `base64(SHA-256(the certificate's DER SubjectPublicKeyInfo))`. */
fun spkiPin(certificate: Certificate): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

/** Whether any of [pins] is in [set]. */
fun inSet(pins: Collection<String>, set: Collection<String> = BUILTIN_PINS.map { it.pin }): Boolean = pins.any { it in set }

/** How a server answer arrived, for [nextFlag] and [dropOutside] (the contract's `connection`). */
enum class PinConnection(val wire: String) {
    /** https, and Android's validated chain holds a key of B. */
    PASSED("passing"),

    /** https, and the validated chain holds none (the flag off, or no pinner). */
    FAILED("failing"),

    /** `http://<tailnet IP>` through tsnet ([ServerPath.Tailnet]): WireGuard authenticated the peer. */
    TAILNET("tailnet"),

    /** No handshake and not the tailnet: a debug build's `http://10.0.2.2` (qa R-5). */
    NONE("debug_http"),
}

/** How [handshake] (a response's, `null` without TLS) on [path] arrived. "Over the tailnet" is the request's path,
 * never "no handshake" (R-5). */
fun pinConnection(path: ServerPath, handshake: Handshake?, set: Collection<String> = BUILTIN_PINS.map { it.pin }): PinConnection = when {
    path is ServerPath.Tailnet -> PinConnection.TAILNET
    handshake == null -> PinConnection.NONE
    // A chain that can't be read (Android's cleaner refused it again) never passes.
    runCatching { inSet(handshake.peerCertificates.map(::spkiPin), set) }.getOrDefault(false) -> PinConnection.PASSED
    else -> PinConnection.FAILED
}

/** The policy's `server_tls.enforce`: true, false, or `null` - absent, `{}` (the server's `watching`, R-1), or
 * anything that isn't an object with a JSON Boolean `enforce`. */
fun enforceOf(serverTls: JsonElement?): Boolean? {
    val enforce = (serverTls as? JsonObject)?.get("enforce") as? JsonPrimitive ?: return null
    return if (enforce.isString) null else enforce.booleanOrNull
}

/**
 * The stored flag after a fresh policy (design 76 D2): on only from `enforce: true` over a connection that passed B;
 * off only from `enforce: false` over a connection that passed B or over the tailnet; anything else keeps it. One
 * caller: the sync's fresh policy fetch - never the cached policy, the start-up or `currentPolicyDecision` (R-5).
 */
fun nextFlag(flag: Boolean, enforce: Boolean?, connection: PinConnection): Boolean = when {
    enforce == true && connection == PinConnection.PASSED -> true
    enforce == false && (connection == PinConnection.PASSED || connection == PinConnection.TAILNET) -> false
    else -> flag
}

/** An anchor: its name (its CN, cut to [ANCHOR_NAME_MAX]) and its pin. */
data class Anchor(val name: String, val pin: String)

/** The name shown for a subject (`CN=..., O=...` in either Java's or RFC 2253's spacing): its CN, else the whole
 * subject; cut to [ANCHOR_NAME_MAX]. */
fun anchorName(subject: String): String {
    val cn = subject.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substring(3)?.trim()
    return (cn?.takeIf { it.isNotEmpty() } ?: subject.trim()).take(ANCHOR_NAME_MAX)
}

/** The anchor of a validated chain (its last certificate). */
fun chainAnchor(chain: List<Certificate>): Anchor? {
    val last = chain.lastOrNull() ?: return null
    val subject = (last as? X509Certificate)?.subjectX500Principal?.name ?: ""
    return Anchor(anchorName(subject), spkiPin(last))
}

/** OkHttp's pin failure starts with this (only this: a hostname failure is not a pin failure, qa L-5). */
const val PIN_FAILURE_PREFIX = "Certificate pinning failure!"

/** Whether [e] is OkHttp's pin failure (only that: a hostname failure is not one, qa L-5). */
fun isPinFailure(e: Throwable): Boolean =
    (e as? javax.net.ssl.SSLPeerUnverifiedException)?.message?.startsWith(PIN_FAILURE_PREFIX) == true

/** One certificate of OkHttp's "Peer certificate chain": four spaces, `sha256/` and a 32-byte pin, its subject. */
private val CHAIN_LINE = Regex("^ {4}sha256/([A-Za-z0-9+/]{43}=): (.*)$")

/**
 * The anchor of OkHttp's pin failure: the last `    sha256/<pin>: <subject>` line of its "Peer certificate chain"
 * (the chain Android validated, anchor last), up to the **last** "Pinned certificates for" - the hostname and the
 * pins after it are ours, while a leaf's subject is whoever got it issued and may carry either phrase or a newline
 * (code QA L-1: a leaf can't name the anchor). `null` for any other failure or a message of another shape. Pinned
 * against OkHttp 4.12's message by `ServerPinsTest`.
 */
fun pinFailureAnchor(e: Throwable): Anchor? {
    if (!isPinFailure(e)) return null
    val message = e.message!!
    val start = message.indexOf("\n  Peer certificate chain:")
    val end = message.lastIndexOf("\n  Pinned certificates for ")
    if (start < 0 || end <= start) return null
    val last = message.substring(start, end).lines().mapNotNull { CHAIN_LINE.matchEntire(it) }.lastOrNull() ?: return null
    return Anchor(anchorName(last.groupValues[2]), last.groupValues[1])
}

/** Thrown by a server client when the server's chain failed the pins (the flag on): nothing was sent. */
class ServerPinRefused(val anchor: Anchor?, cause: Throwable? = null) : java.io.IOException(
    "the server's certificate is from ${anchor?.name ?: "an issuer"} this phone doesn't accept",
    cause,
)

/** An outside sighting (design 76 D3): `refused` with the flag on, `seen` with it off. [network] is `wifi`,
 * `mobile` or `other`. The status report's `server_tls.outside` row, and the ring's. */
@Serializable
data class OutsideRow(
    val kind: String,
    val host: String,
    val name: String,
    val pin: String,
    val network: String,
    val atMs: Long,
) {
    /** The same sighting but for its time: the SSE's 30 s retries don't add rows. */
    fun sameAs(other: OutsideRow): Boolean =
        kind == other.kind && host == other.host && name == other.name && pin == other.pin && network == other.network

    companion object {
        const val SEEN = "seen"
        const val REFUSED = "refused"
    }
}

/** The policy's `server_tls.recovery_url` (code QA H-1): the plain-http tailnet address of this server, shown in
 * Settings for a phone that refuses its certificate - only an address the phone itself takes as tailnet http
 * ([serverUrlVerdict]), anything else is none. */
fun recoveryOf(serverTls: JsonElement?): String? {
    val url = (serverTls as? JsonObject)?.get("recovery_url") as? JsonPrimitive ?: return null
    return url.content.takeIf { url.isString && serverUrlVerdict(it) == ServerUrlVerdict.TAILNET_HTTP }
}

/** The stored recovery address after a fresh policy: the server's (or none) when the policy came over a connection
 * that passed the built-in set or over the tailnet and carries `server_tls`; otherwise the stored one, so a MITM
 * can't name an address (the contract's `recovery` rows). */
fun nextRecovery(stored: String?, serverTls: JsonElement?, connection: PinConnection): String? =
    if ((connection == PinConnection.PASSED || connection == PinConnection.TAILNET) && serverTls is JsonObject) {
        recoveryOf(serverTls)
    } else {
        stored
    }

/** The ring with [row] added: only when it differs from the last ([OutsideRow.sameAs]), the oldest dropped past
 * [OUTSIDE_MAX]. */
fun addOutside(ring: List<OutsideRow>, row: OutsideRow): List<OutsideRow> =
    if (ring.lastOrNull()?.sameAs(row) == true) ring else (ring + row).takeLast(OUTSIDE_MAX)

/** The ring after a 2xx that carried [sent]: the sent rows leave only over a connection that passed B or over the
 * tailnet - a MITM answering 2xx can't erase them (R-2). Rows added since stay. */
fun dropOutside(ring: List<OutsideRow>, sent: List<OutsideRow>, connection: PinConnection): List<OutsideRow> =
    if (connection == PinConnection.PASSED || connection == PinConnection.TAILNET) ring.filterNot { it in sent } else ring

private val RING = ListSerializer(OutsideRow.serializer())

fun encodeRing(ring: List<OutsideRow>): String = ServerJson.encodeToString(RING, ring)

/** The stored ring; anything unreadable reads as empty. */
fun decodeRing(stored: String?): List<OutsideRow> = try {
    if (stored.isNullOrBlank()) emptyList() else ServerJson.decodeFromString(RING, stored).takeLast(OUTSIDE_MAX)
} catch (e: Exception) {
    emptyList()
}

/** The status report's `server_tls` (`testdata/server_tls_report.json`'s `report`): the stored flag, this sync's
 * policy connection as seen (absent over the tailnet or plain http), and the ring. */
@Serializable
data class ServerTlsReport(val enforcing: Boolean, val seen: ServerTlsSeen? = null, val outside: List<OutsideRow>)

/** A validated chain as seen: in B or not, its anchor, the network, when. */
@Serializable
data class ServerTlsSeen(val inSet: Boolean, val name: String, val pin: String, val network: String, val atMs: Long)

/** [handshake]'s chain as seen now, `null` without one. */
fun seenOf(handshake: Handshake?, network: String, atMs: Long, set: Collection<String> = BUILTIN_PINS.map { it.pin }): ServerTlsSeen? {
    val chain = runCatching { handshake?.peerCertificates }.getOrNull() ?: return null
    val anchor = chainAnchor(chain) ?: return null
    return ServerTlsSeen(inSet(chain.map(::spkiPin), set), anchor.name, anchor.pin, network, atMs)
}
