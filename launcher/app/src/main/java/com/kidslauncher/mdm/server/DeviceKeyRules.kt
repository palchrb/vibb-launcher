package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/*
 * Design 129 (the hardware-bound phone key), the launcher's pure rules: the signed request's canonical form (§4.2),
 * the proofs and the enrolment's challenge (§5.1, §5.2), the signature clock's offset (§4.3), what each `Vibb-Auth`
 * answer makes the phone do (§4.4), and the registration flow's one decision (§5.2, `keyStep`). All of them are rows
 * of the shared contract `contracts/core/device_key.json` (`DeviceKeyContractTest`).
 */

/** The headers (§4.2, §4.4). */
const val SIGNATURE_HEADER = "Vibb-Signature"
const val BODY_SHA256_HEADER = "Vibb-Body-SHA256"
const val SIGNATURE_TIME_HEADER = "Vibb-Time"
const val AUTH_REFUSAL_HEADER = "Vibb-Auth"

/** The part of every device path the signature covers: from here on, so a proxy's path prefix signs the same (L3). */
const val SIGNED_PATH_ROOT = "/api/devices"

fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** The request target as signed: the raw path from [SIGNED_PATH_ROOT] on, and `?` + the raw query when there is one
 * (OkHttp's `encodedPath`/`encodedQuery`, never a decoded form). `null`: not a device path - nothing is signed. */
fun signedTarget(encodedPath: String, encodedQuery: String?): String? {
    val at = encodedPath.indexOf(SIGNED_PATH_ROOT)
    if (at < 0) return null
    return encodedPath.substring(at) + (encodedQuery?.let { "?$it" } ?: "")
}

/** §4.2's signed bytes, as UTF-8 text: eight lines, no trailing newline. */
fun canonicalRequest(
    keyId: String,
    t: Long,
    nonce: String,
    method: String,
    target: String,
    contentEncoding: String,
    bodySha256: String,
): String = listOf("vibb-req-v1", keyId, t.toString(), nonce, method, target, contentEncoding, bodySha256).joinToString("\n")

/** The `Vibb-Signature` value for [signature] (DER ECDSA). */
fun signatureHeader(keyId: String, t: Long, nonce: String, signature: ByteArray): String =
    "v1;k=$keyId;t=$t;n=$nonce;s=${b64u(signature)}"

/** The enrolment code as the server reads it: spaces and dashes stripped, upper-cased (`enrollment::normalize`). */
fun normalisedEnrolCode(code: String): String = code.filter { !it.isWhitespace() && it != '-' }.uppercase()

/** §5.1: the attestation challenge of an enrolment with [code] - the phone knows it before it calls. */
fun enrolChallenge(code: String): ByteArray =
    sha256("vibb-enroll-v1".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + normalisedEnrolCode(code).toByteArray(Charsets.UTF_8))

/** A proof's kind: the enrolment's or a registration's (§5.1, §5.2 step 4). */
enum class ProofKind(val wire: String) { ENROLL("enroll"), REGISTER("register") }

/** The bytes a proof signs: `vibb-key-v1\n<challenge Base64url>\n<kind>`. Signed afresh on every attempt (S1 refuses a
 * repeated ECDSA `r`). */
fun proofMessage(challenge: ByteArray, kind: ProofKind): String = "vibb-key-v1\n${b64u(challenge)}\n${kind.wire}"

/** §4.3: the server's signature clock minus elapsed realtime, from an answer's `Vibb-Time` and the round trip's
 * middle. */
fun signatureOffset(vibbTime: Long, sentElapsedMs: Long, receivedElapsedMs: Long): Long =
    vibbTime - (sentElapsedMs + receivedElapsedMs) / 2

/** The `t` a request signs now. */
fun signatureTime(offset: Long, nowElapsedMs: Long): Long = nowElapsedMs + offset

/** What a refused signature makes the phone do (§4.4; the contract's `answers`). */
enum class RefusalAction(val wire: String) {
    TAKE_OFFSET_AND_RETRY_ONCE("take_offset_and_retry_once"),
    RETRY_ONCE_NEW_NONCE("retry_once_new_nonce"),
    ASK_REGISTRATION("ask_registration"),
    SILENT("silent"),
}

/** An unknown reason is silent, as `bad_sig`. */
fun refusalAction(reason: String?): RefusalAction = when (reason) {
    "stale" -> RefusalAction.TAKE_OFFSET_AND_RETRY_ONCE
    "replay" -> RefusalAction.RETRY_ONCE_NEW_NONCE
    "unknown_key" -> RefusalAction.ASK_REGISTRATION
    else -> RefusalAction.SILENT
}

/** The policy's `device_key` (§5.5) - only a nudge to ask `GET /api/devices/key`. */
@Serializable
data class DeviceKeyPolicy(
    val state: String = "none",
    val keyId: String? = null,
    val spkiSha256: String? = null,
    val rotate: Boolean = false,
)

/** `GET /api/devices/key`'s answer: `{state, key_id?, spki_sha256?, rotate}`. */
@Serializable
data class DeviceKeyAnswer(
    val state: String = "none",
    val keyId: String? = null,
    val spkiSha256: String? = null,
    val rotate: Boolean = false,
)

/** Step 1 as the phone got it: [status] 0 for no answer; [answer] the 200's body. */
data class KeyStepOne(val status: Int, val vibbAuth: String? = null, val answer: DeviceKeyAnswer? = null)

/**
 * What the registration flow knows before it decides (§5.2): [local] the SPKI hashes of the keys the phone holds,
 * [adopted] the one it signs with (`null`: none yet), [hasToken] whether the bearer still goes along, [inFlight] a
 * registration's POST still running in this flow, [conflictReported] the key the phone last reported as not its own,
 * [backoff] a refusal or a failed attempt is still waiting out its time.
 */
data class KeyStepInput(
    val hasToken: Boolean,
    val local: List<String>,
    val adopted: String?,
    val step1: KeyStepOne,
    val inFlight: Boolean = false,
    val conflictReported: String? = null,
    val backoff: Boolean = false,
    /** Design 129b D10: the time since the key book's last try of an exempt phone (`null`: none kept). */
    val exemptSinceLastTryMs: Long? = null,
)

/** The flow's actions (the contract's `phone` rows). */
sealed interface KeyAction {
    /** Store the server's key id for [spki], delete every other key; [dropToken]: the key is `bound` - the sealed
     * bearer goes (§5.5). */
    data class Adopt(val spki: String, val dropToken: Boolean) : KeyAction

    /** Nothing changes: the named key is the adopted one, and the only one. */
    data object Keep : KeyAction

    /** Make a key with a new challenge and POST it (§5.2 step 4, a rotation §5.3). */
    data object Register : KeyAction

    /** The server names a key the phone doesn't hold (§5.2 step 3): `POST /api/devices/key/conflict`, once. */
    data object ReportConflict : KeyAction

    /** Ask again later; nothing is made or deleted. */
    data object Wait : KeyAction

    /** §5.4: a key the server no longer knows and no bearer - one more strike towards "lost" ([lostVerdict]); never a
     * fallback to anything, and never "lost" on one answer (a server whose key index is reloading answers it too). */
    data object SuspectLost : KeyAction
}

/** The states in which the server names a key. */
private val NAMING_STATES = setOf("observed", "bound", "held", "pending")

/**
 * §5.2's one decision: step 1's answer decides, nothing else does. A key is never deleted on another call's answer, and
 * only an adoption deletes (the keys the server doesn't name).
 */
fun keyStep(input: KeyStepInput): KeyAction {
    if (input.inFlight) return KeyAction.Wait
    val step1 = input.step1
    if (step1.status == 401 && step1.vibbAuth == "unknown_key") {
        return if (!input.hasToken && input.adopted != null) KeyAction.SuspectLost else KeyAction.Wait
    }
    val answer = step1.answer
    if (step1.status != 200 || answer == null) return KeyAction.Wait
    if (answer.state == "none") return if (input.backoff) KeyAction.Wait else KeyAction.Register
    // Design 129b D10: enrolled without a key by the parent's exception - a try once a week (the book keeps the last).
    if (answer.state == "exempt") {
        val due = input.exemptSinceLastTryMs == null || input.exemptSinceLastTryMs >= EXEMPT_TRY_EVERY_MS
        return if (!input.backoff && due) KeyAction.Register else KeyAction.Wait
    }
    val named = answer.spkiSha256
    if (answer.state !in NAMING_STATES || named.isNullOrBlank()) return KeyAction.Wait
    if (named !in input.local) {
        return if (input.conflictReported == named) KeyAction.Wait else KeyAction.ReportConflict
    }
    val dropToken = answer.state == "bound" && input.hasToken
    if (named == input.adopted && answer.rotate) return if (input.backoff) KeyAction.Wait else KeyAction.Register
    if (named == input.adopted && input.local.size == 1 && !dropToken) return KeyAction.Keep
    return KeyAction.Adopt(named, dropToken)
}

/**
 * Whether a policy's `device_key` asks for step 1 (§5.5: only a nudge). An older server's policy without it asks
 * nothing; a lost key asks nothing (only a new setup code helps, §5.4).
 */
fun keyNudge(
    policy: DeviceKeyPolicy?,
    adoptedKeyId: String?,
    hasToken: Boolean,
    lost: Boolean,
    backoff: Boolean,
    sinceLastAskMs: Long? = null,
): Boolean = when {
    policy == null || lost -> false
    // A state that names no key asks again only after [NONE_ASK_AGAIN_MS] (code QA L9: not on every sync).
    // Design 129b: an exempt phone asks as one without a key does (its weekly try is `keyStep`'s).
    policy.state == "none" || policy.state == "exempt" ->
        !backoff && (sinceLastAskMs == null || sinceLastAskMs >= NONE_ASK_AGAIN_MS)
    policy.rotate -> !backoff
    policy.keyId != adoptedKeyId -> true
    policy.state == "bound" && hasToken -> true
    else -> false
}

/** How often a policy whose `device_key` names no key asks step 1 again. */
const val NONE_ASK_AGAIN_MS = 60 * 60_000L

/** Code QA H1: "lost" needs [LOST_STRIKES] step-1 `unknown_key` answers spread over at least [LOST_SPAN_MS]. */
const val LOST_STRIKES = 3
const val LOST_SPAN_MS = 60 * 60_000L

/** The 129 L1 gate's finding 1: a step-1 `unknown_key` within this long of the last counted strike is no strike - only
 * spaced asks count (an SSE retry every few seconds once made 12 strikes in a minute). */
const val LOST_STRIKE_GAP_MS = 10 * 60_000L

/** The strikes so far: the first one's elapsed realtime and boot, how many, and the last counted one's elapsed realtime
 * (in the same boot as the first). */
@Serializable
data class LostSuspicion(val firstElapsedMs: Long, val firstBoot: Int, val strikes: Int, val lastElapsedMs: Long = firstElapsedMs)

/**
 * One more step-1 `unknown_key` with no bearer (code QA H1): the suspicion after it, and whether the key is now lost.
 * A reboot starts the span again (elapsed realtime restarts), keeping the strikes so far but never counting a span
 * across the boot. An answer within [LOST_STRIKE_GAP_MS] of the last counted strike changes nothing and never makes the
 * key lost (the gate's finding 1).
 */
fun lostVerdict(before: LostSuspicion?, nowElapsedMs: Long, boot: Int): Pair<LostSuspicion, Boolean> {
    val next = when {
        before == null -> LostSuspicion(nowElapsedMs, boot, 1)
        before.firstBoot != boot -> LostSuspicion(nowElapsedMs, boot, before.strikes + 1)
        nowElapsedMs - before.lastElapsedMs < LOST_STRIKE_GAP_MS -> return before to false
        else -> before.copy(strikes = before.strikes + 1, lastElapsedMs = nowElapsedMs)
    }
    return next to (next.strikes >= LOST_STRIKES && nowElapsedMs - next.firstElapsedMs >= LOST_SPAN_MS)
}

/**
 * The 129 L1 gate's finding 1: an answer that proves the server knows the adopted key - a 2xx or a 304 to a request
 * the key signed with no bearer beside it (with a bearer, S1 answers on the bearer whatever the signature). It ends any
 * suspicion of a lost key, which no step 1 would otherwise clear once signed calls work again.
 */
fun signatureKnown(status: Int, signed: Boolean, bearer: Boolean): Boolean =
    signed && !bearer && (status in 200..299 || status == 304)

/** The registration backoff after a refused key - a registration's 400 or the enrolment's `refused` (the gate's finding
 * 2: the enrolment's set none, so the first sync made a second key at once). A stale challenge may go again at once. */
fun refusalBackoffMs(reason: String): Long = if (reason == "challenge") 0L else REFUSED_BACKOFF_MS

/** A refused chain: the server's checks won't change by themselves soon. */
const val REFUSED_BACKOFF_MS = 24 * 60 * 60_000L

/**
 * Code QA M3: whether a key whose signs fail is unusable - at once when the failure is permanent (no key, a key
 * permanently invalidated), else only after [SIGN_FAILURES] failures in a row spread over [SIGN_FAILURE_SPAN_MS].
 */
fun signUnusable(permanent: Boolean, failuresInRow: Int, firstFailureElapsedMs: Long, nowElapsedMs: Long): Boolean =
    permanent || (failuresInRow >= SIGN_FAILURES && nowElapsedMs - firstFailureElapsedMs >= SIGN_FAILURE_SPAN_MS)

const val SIGN_FAILURES = 5
const val SIGN_FAILURE_SPAN_MS = 10 * 60_000L

/** The rkpd packages (design 129 Q9): AOSP's, and Google's - the Jelly Star (Android 16) has `com.google.android.rkpdapp`. */
val RKPD_PACKAGES = listOf("com.android.rkpdapp", "com.google.android.rkpdapp")

/** Code QA M4: an rkpd package is exempted from the sink only when it is a system app with an app uid of its own -
 * never a shared uid (another package would ride along). */
fun rkpdExemptable(uid: Int, system: Boolean, packagesForUid: Int): Boolean =
    uid >= android.os.Process.FIRST_APPLICATION_UID && system && packagesForUid == 1

/** A key the phone made: [createdMs] and until when its challenge holds ([challengeUntilMs]), whether the server ever
 * named it. */
@Serializable
data class KeyAlias(val alias: String, val createdMs: Long, val challengeUntilMs: Long, val named: Boolean = false)

/** `POST /api/devices/key`'s body (§5.2 step 4). */
@Serializable
data class KeyRegisterBody(val challenge: String, val chain: List<String>, val proof: String)

/** `GET /api/devices/key/challenge`'s answer. */
@Serializable
data class KeyChallengeAnswer(val challenge: String, val expiresInS: Long = 600)

/** A refusal's body: `{reason, retry_after_s?}`. */
@Serializable
data class KeyRefusalAnswer(val reason: String = "", val retryAfterS: Long? = null)

/** At most this many keys at once (§2). */
const val MAX_DEVICE_KEYS = 3

/**
 * §2's cap before a new key: with [MAX_DEVICE_KEYS] or more, the oldest key whose challenge has expired and that the
 * server never named goes first. Returns the aliases to delete, or `null` when none may go (the new key waits).
 */
fun keyCapPlan(keys: List<KeyAlias>, nowMs: Long): List<String>? {
    if (keys.size < MAX_DEVICE_KEYS) return emptyList()
    val deletable = keys.filter { !it.named && it.challengeUntilMs <= nowMs }.sortedBy { it.createdMs }
    val needed = keys.size - MAX_DEVICE_KEYS + 1
    if (deletable.size < needed) return null
    return deletable.take(needed).map { it.alias }
}
