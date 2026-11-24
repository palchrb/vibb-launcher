package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/*
 * Design 129b (`docs/design/129b-enrolment-requires-a-key.md`): a new phone's enrolment requires a proven hardware
 * key. A refused key (or none) is the server's `409 {state: "waiting", reason, number, retry_after_s}`: the phone keeps
 * trying the same code with the same attempt and shows "Venter på en forelder." with the attempt's number, until the
 * parent's exception lets that attempt enrol. The pure parts here are the contract's (`device_key.json`: `enroll`,
 * `enrol_wait`); the runtime is [EnrolWait].
 */

/** D4: the attempt's six-digit number - `u32_be(SHA-256("vibb-attempt-v1" || 0x00 || SHA-256(attempt))[0..4]) mod
 * 1 000 000` - as six digits ("089851"); the server derives the same from the hash it keeps. */
fun attemptNumber(attempt: ByteArray): String {
    val attemptHash = MessageDigest.getInstance("SHA-256").digest(attempt)
    val digest = MessageDigest.getInstance("SHA-256").run {
        update("vibb-attempt-v1".toByteArray(Charsets.UTF_8))
        update(0.toByte())
        update(attemptHash)
        digest()
    }
    val n = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
        ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
    return "%06d".format(java.util.Locale.ROOT, n % 1_000_000)
}

/** The number as the phone shows it: "482 913". */
fun shownNumber(number: String): String = if (number.length == 6) number.substring(0, 3) + " " + number.substring(3) else number

/** The waiting answer's body (§4.1). */
@Serializable
data class EnrolWaitingAnswer(val state: String = "", val reason: String = "", val number: String = "", val retryAfterS: Long = 0)

/** A refusal's body: `{reason, retry_after_s?}` (`update_app`, `server_setup`, `list_stale`). */
@Serializable
data class EnrolReasonAnswer(val reason: String = "", val retryAfterS: Long? = null)

/** What the phone does with an enrolment's answer (the contract's `enroll.answers[*].phone`). */
enum class EnrolAnswerKind(val wire: String) {
    ENROLLED("enrolled"),
    ENROLLED_EXEMPT("enrolled_exempt"),
    WAITING("waiting"),
    TRANSIENT("transient"),
    CODE_REFUSED("code_refused"),
    UPDATE_APP("update_app"),
}

/** An answer's kind from its status and, for a 200, the enrolled key's state. */
fun enrolAnswerKind(status: Int, keyState: String?, waitingState: String?): EnrolAnswerKind = when {
    status in 200..299 -> if (keyState == "exempt") EnrolAnswerKind.ENROLLED_EXEMPT else EnrolAnswerKind.ENROLLED
    status == 409 && waitingState == "waiting" -> EnrolAnswerKind.WAITING
    status == 426 -> EnrolAnswerKind.UPDATE_APP
    status in CODE_REFUSED_STATUSES -> EnrolAnswerKind.CODE_REFUSED
    else -> EnrolAnswerKind.TRANSIENT
}

/** D11: the waiting stops this long after the first waiting answer. */
const val ENROL_WAIT_STOP_MS = 45 * 60_000L
const val ENROL_WAIT_MIN_S = 10L
const val ENROL_WAIT_MAX_S = 300L

/** A transient answer without a pace waits this long. */
const val ENROL_WAIT_DEFAULT_S = 30L

/** The step after a try (`enrol_wait` rows): retry after [delayMs] (making a key first when [makeKey]), stop, or
 * enrolled. */
data class EnrolWaitAction(val action: String, val delayMs: Long? = null, val makeKey: Boolean = false)

/**
 * D11, pure: a try's [answer] (`waiting`, `transient`, `code_refused`, `enrolled`) with its pace [retryAfterS] (the
 * waiting body's, a `Retry-After`; `null` none), [sinceFirstMs] since the first waiting answer and whether a key is
 * held for the code. Enrolled ends it, a code refusal stops it, so do 45 minutes; otherwise the next try waits the pace
 * clamped to 10-300 s, and a phone without a key makes one on that try.
 */
fun enrolWaitStep(answer: String, retryAfterS: Long?, sinceFirstMs: Long, hasKey: Boolean): EnrolWaitAction = when {
    answer == "enrolled" -> EnrolWaitAction("enrolled")
    answer == "code_refused" -> EnrolWaitAction("stop")
    sinceFirstMs >= ENROL_WAIT_STOP_MS -> EnrolWaitAction("stop")
    else -> EnrolWaitAction(
        "retry",
        (retryAfterS ?: ENROL_WAIT_DEFAULT_S).coerceIn(ENROL_WAIT_MIN_S, ENROL_WAIT_MAX_S) * 1000,
        makeKey = !hasKey,
    )
}

/** X (§8) and the stale list: what the registration does with the challenge's answer (`phone` rows'
 * `challenge_action`). */
data class ChallengeAction(val action: String, val waitMs: Long? = null, val makeKey: Boolean = false)

/** A challenge: make the key. A 503 with `retry_after_s` (`address_history`, `list_stale`): wait that long, no key.
 * Anything else: back off, no key. */
fun keyChallengeStep(status: Int, refusal: KeyRefusalAnswer?): ChallengeAction = when {
    status == 200 -> ChallengeAction("make_key", makeKey = true)
    status == 503 && refusal?.retryAfterS != null -> ChallengeAction("wait", waitMs = refusal.retryAfterS * 1000)
    else -> ChallengeAction("backoff")
}

/** D10: an exempt phone tries a key once a week. */
const val EXEMPT_TRY_EVERY_MS = 7 * 24 * 60 * 60_000L
