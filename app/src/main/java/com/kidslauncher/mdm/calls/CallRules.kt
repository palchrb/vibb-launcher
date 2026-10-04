package com.kidslauncher.mdm.calls

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/*
 * Call allow/deny decisions - pure Kotlin, no Android imports, unit-tested in CallRulesTest.
 * KidCallScreeningService, KidCallRedirectionService, KidInCallService and the phone book feed them
 * a number and the in-memory [CallPolicyState] (CallPolicyStore) and act on the [Verdict]. Design:
 * 02-calls.md 2.1, with the "Decisions after QA review" section on top:
 * - the offline-override PIN and "pause all restrictions" never open calls;
 * - the callback window after an emergency call opens only for a call the platform confirms as an
 *   emergency number and that connected - never from the static list;
 * - emergency numbers are always allowed outgoing, in or out of the phone book.
 */

/** One contact of the managed call rules - the launcher's copy of the server's `PolicyContact`.
 * [number] is normalised by the server (src/phone.rs). */
@Serializable
data class RuleContact(
    val id: Long = 0,
    val name: String = "",
    val number: String = "",
    val inbound: Boolean = false,
    val outbound: Boolean = false,
    val showOnHome: Boolean = false,
    /** "none", "sms", "element" or "signal", already resolved against the device default. */
    val messageApp: String = "none",
    /** The Matrix ID for "element". */
    val messageAddress: String? = null,
)

/**
 * The managed rules. Its own small serializable type (not the policy DTO), stored as
 * `last_call_rules` with every accepted managed policy, so a cache the launcher can't read any
 * more still leaves the last rules in force (QA blocker 3). Every default denies.
 */
@Serializable
data class CallRules(
    val callsEnabled: Boolean = false,
    val smsEnabled: Boolean = false,
    val defaultCc: String = "47",
    val contacts: List<RuleContact> = emptyList(),
) {
    @Transient
    val inbound: Set<String> = contacts.filter { it.inbound }.mapTo(mutableSetOf()) { it.number }

    @Transient
    val outbound: Set<String> = contacts.filter { it.outbound }.mapTo(mutableSetOf()) { it.number }

    /** What the kid may call, in the parent's order. */
    val phoneBook: List<RuleContact> get() = contacts.filter { it.outbound }

    /** Phone-book contacts that also get a button on Home. */
    val homeContacts: List<RuleContact> get() = contacts.filter { it.outbound && it.showOnHome }

    /** The contact a (raw, un-normalised) number belongs to, for showing a name. */
    fun contactFor(raw: String?): RuleContact? {
        val number = raw?.let { PhoneNumbers.normalize(it, defaultCc)?.value } ?: return null
        return contacts.firstOrNull { it.number == number }
    }
}

/** What the call path enforces right now. */
sealed interface CallPolicyState {
    /** The server says calls aren't managed (or the phone never had a policy): no effect. */
    data object Unmanaged : CallPolicyState

    data class Managed(val rules: CallRules) : CallPolicyState

    /** Calls were (or may have been) managed but no rules can be read: only emergency calls, and
     * the callback window. Also the state before anything has been loaded (QA #10). */
    data object UnknownFailClosed : CallPolicyState
}

/** Whether calls are under our control at all in [this] state. */
val CallPolicyState.managed: Boolean get() = this !is CallPolicyState.Unmanaged

enum class Verdict { ALLOW, BLOCK }

private fun matches(raw: String?, rules: CallRules, set: Set<String>): Boolean {
    val number = raw?.let { PhoneNumbers.normalize(it, rules.defaultCc) } ?: return false
    return number.value in set
}

/**
 * Outgoing calls, first rule that applies: emergency (ALLOW), unmanaged (ALLOW), fail closed
 * (BLOCK), calls off (BLOCK), then the outbound set. [raw] `null` (voicemail, an unparseable
 * handle) is blocked when managed; so are MMI/USSD codes (they never normalise).
 */
fun decideOutgoing(raw: String?, state: CallPolicyState, isEmergency: Boolean): Verdict = when {
    isEmergency -> Verdict.ALLOW
    state is CallPolicyState.Unmanaged -> Verdict.ALLOW
    state !is CallPolicyState.Managed -> Verdict.BLOCK
    !state.rules.callsEnabled -> Verdict.BLOCK
    matches(raw, state.rules, state.rules.outbound) -> Verdict.ALLOW
    else -> Verdict.BLOCK
}

/**
 * Incoming calls, first rule that applies: unmanaged (ALLOW), the callback window after an
 * emergency call (ALLOW - the emergency services may call back from any number, withheld too),
 * fail closed (BLOCK), calls off (BLOCK), withheld number (BLOCK), caller-ID verification failed
 * (BLOCK, QA #17), then the inbound set. [callbackWindowOpen] is only asked when it matters (it
 * may read the call log).
 */
fun decideIncoming(
    raw: String?,
    presentationAllowed: Boolean,
    verificationFailed: Boolean,
    state: CallPolicyState,
    callbackWindowOpen: () -> Boolean,
): Verdict = when {
    state is CallPolicyState.Unmanaged -> Verdict.ALLOW
    // Cheap and common: an allowed contact doesn't need the call log.
    state is CallPolicyState.Managed && state.rules.callsEnabled && presentationAllowed &&
        !verificationFailed && matches(raw, state.rules, state.rules.inbound) -> Verdict.ALLOW
    callbackWindowOpen() -> Verdict.ALLOW
    else -> Verdict.BLOCK
}

/** Emergency numbers. */
object Emergency {
    /** Used only to ALLOW an outgoing call (never to open the callback window), together with the
     * platform's answer: 112 and 911 work on every GSM phone, plus the national numbers of the
     * default country. Deliberately not 000/08/118/999 (QA blocker 2). */
    private val ALWAYS = setOf("112", "911")
    private val BY_COUNTRY = mapOf(
        "47" to setOf("110", "112", "113"),
        "46" to setOf("112"),
        "45" to setOf("112", "114"),
    )

    fun staticSet(defaultCc: String): Set<String> = ALWAYS + BY_COUNTRY[defaultCc].orEmpty()

    /**
     * Whether an outgoing [raw] number is an emergency number: an exact match on the
     * separator-stripped digits (`112#`, `1121234`, `*112` are not), the platform's answer
     * ([platform] returns `null` when TelephonyManager throws) ORed with [staticSet], so a
     * telephony failure can never block 112.
     */
    fun isEmergencyOutgoing(raw: String?, defaultCc: String, platform: (String) -> Boolean?): Boolean {
        val digits = PhoneNumbers.dialableDigits(raw) ?: return false
        return platform(digits) == true || digits in staticSet(defaultCc)
    }

    /** Only the platform counts here (callback window): `null` (it threw) is "no". */
    fun platformConfirms(raw: String?, platform: (String) -> Boolean?): Boolean {
        val digits = PhoneNumbers.dialableDigits(raw) ?: return false
        return platform(digits) == true
    }
}

/** How long anyone may call after an emergency call, so the emergency services can call back. */
const val CALLBACK_WINDOW_MS = 60 * 60 * 1000L

/** An outgoing call from the system call log (`CallLog.Calls`): number, start, duration. */
data class LoggedCall(val number: String?, val startMs: Long, val durationSec: Long)

/**
 * When the callback window closes, or `null` if it's closed. It opens only after an emergency call
 * that the platform confirms as an emergency number AND that connected (QA blocker 2): an
 * outgoing call-log entry with a duration, or [lastConnectedEmergencyEndMs] (recorded by our
 * InCallService when it saw such a call become active, already platform-confirmed). Dialling
 * `08`/`000`, or 112 without the call connecting, never opens it.
 */
fun callbackWindowUntil(
    nowMs: Long,
    outgoingCalls: List<LoggedCall>,
    lastConnectedEmergencyEndMs: Long?,
    platform: (String) -> Boolean?,
): Long? {
    val fromLog = outgoingCalls
        .filter { it.durationSec > 0 && Emergency.platformConfirms(it.number, platform) }
        .maxOfOrNull { it.startMs + it.durationSec * 1000 }
    val lastEnd = listOfNotNull(fromLog, lastConnectedEmergencyEndMs).maxOrNull() ?: return null
    val until = lastEnd + CALLBACK_WINDOW_MS
    return if (nowMs in lastEnd..until) until else null
}
