package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.WindowStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/*
 * Call allow/deny decisions - pure Kotlin, no Android imports, unit-tested in CallRulesTest.
 * KidCallScreeningService, KidCallRedirectionService, KidInCallService and the phone book feed them
 * a number and the in-memory [CallPolicyState] (CallPolicyStore) and act on the [Verdict]. Design:
 * 02-calls.md 2.1, with the "Decisions after QA review" section on top:
 * - the offline-override PIN and "pause all restrictions" never open calls;
 * - the callback window after an emergency call opens only for a call the platform confirms as an
 *   emergency number and that connected, or that the launcher itself placed through Telecom (H1) -
 *   never from the static list; an incoming SIM call Telecom marks as its own emergency callback
 *   always passes ([emergencyCallbackPass]); a placing alone opens only [PROVISIONAL_WINDOW_MS];
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
    /** SHA-256 of the contact's photo ([ContactPhotos]); never written to the DE boot copy. */
    val photo: String? = null,
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
    /** Design 73: the policy's emergency menu, kept with the rules so a cache that can't be read still has it;
     * `null` = none sent (112 alone). Never decides a call: [Emergency] and the sets below do. */
    val emergencyMenu: List<EmergencyEntry>? = null,
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
     * default country. Deliberately not 000/08/118/999 (QA blocker 2). The server shows the same list as "always
     * works" on the emergency menu (design 73): both are the shared `emergency_numbers.json` (EmergencyMenuTest). */
    internal val ALWAYS = setOf("112", "911")
    internal val BY_COUNTRY = mapOf(
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
 * outgoing call-log entry with a duration, or [recordedUntilMs] - the window our InCallService
 * recorded when it saw such a call become active, or the launcher recorded when it placed one
 * itself (H1: Telecom may show an emergency call in the system dialer, so our in-call service never
 * sees it, and leaves it out of the call log; a placing alone is [PROVISIONAL_WINDOW_MS], the hour
 * only on evidence that it connected) - already checked against
 * elapsed time and the boot count (CallSystem, `timedWindowActive`), so changing the clock or
 * rebooting can't reopen it. Call-log dates are wall-clock times; the clock is locked
 * (`DISALLOW_CONFIG_DATE_TIME`, auto time) whenever calls are managed. Dialling `08`/`000` never
 * opens it, nor does 112 that another app dialled without the call connecting (Telecom's own mark
 * on the callback aside, [emergencyCallbackPass]).
 */
fun callbackWindowUntil(
    nowMs: Long,
    outgoingCalls: List<LoggedCall>,
    recordedUntilMs: Long?,
    platform: (String) -> Boolean?,
): Long? {
    val fromLog = outgoingCalls
        .filter { it.durationSec > 0 && Emergency.platformConfirms(it.number, platform) }
        .map { it.startMs + it.durationSec * 1000 }
        .filter { end -> nowMs in end..end + CALLBACK_WINDOW_MS }
        .maxOfOrNull { it + CALLBACK_WINDOW_MS }
    return listOfNotNull(fromLog, recordedUntilMs?.takeIf { it > nowMs }).maxOrNull()
}

/**
 * How long the callback window lasts on a placing alone (H1 QA): the launcher placed an emergency call
 * (or Telecom marked a callback for one placed elsewhere) but nothing shows it connected - hung up
 * while dialling, no signal. Twice Telecom's own callback window (5 min by AOSP default, seen on the
 * emulator). Evidence that it connected - our in-call service seeing it active, the call log's
 * duration, the phone's call state off-hook for [PLACED_CONNECT_EVIDENCE_MS] ([PlacedCallEvidence]) -
 * gives the full [CALLBACK_WINDOW_MS].
 */
const val PROVISIONAL_WINDOW_MS = 10 * 60 * 1000L

/** Our stored emergency-call record (DE prefs `call_boot_state`): the window, its length, the call's
 * instant for the status report, and whether that instant is the launcher's own placing of it. */
data class EmergencyRecord(val window: WindowStart, val windowMs: Long, val atMs: Long, val placed: Boolean)

/**
 * Our record of a platform-confirmed emergency call (design 35 step 3; H1): the callback window runs
 * [windowMs] from **this** record ([nowWallMs], [nowElapsedMs], [bootCount]) - every connected state
 * our in-call service sees writes one, the hang-up's last, so a 50-minute call still leaves an hour
 * after the hang-up; a placing alone writes [PROVISIONAL_WINDOW_MS] -, while [atMs], the call's own
 * instant, is only the status report's ([reportedEmergencyAt]).
 */
fun emergencyRecord(
    nowWallMs: Long,
    nowElapsedMs: Long,
    bootCount: Int,
    atMs: Long,
    windowMs: Long = CALLBACK_WINDOW_MS,
    placed: Boolean = false,
): EmergencyRecord =
    EmergencyRecord(WindowStart(untilWallMs = nowWallMs + windowMs, elapsedStartMs = nowElapsedMs, bootCount = bootCount), windowMs, atMs, placed)

/** How long before Telecom creates a call the launcher's record of placing it may be ([placingOf]): the
 * placing is read just before `TelecomManager.placeCall`, which creates the call. */
const val PLACED_BEFORE_CREATED_MS = 5_000L

/**
 * Whether the stored record ([storedPlaced], [storedAtMs]) is the launcher's own placing of the call
 * Telecom created at [createdAtMs] - then our in-call service's records of that call keep the placing's
 * instant (one instant per call, so the server announces it once), and a disconnect without a connect
 * drops it back to [PROVISIONAL_WINDOW_MS]. Only a placing counts, never another call's record.
 */
fun placingOf(storedPlaced: Boolean, storedAtMs: Long, createdAtMs: Long): Boolean =
    storedPlaced && createdAtMs > 0 && storedAtMs in (createdAtMs - PLACED_BEFORE_CREATED_MS)..createdAtMs

/** Why an incoming call that the rules would block passes as the emergency services calling back. */
enum class CallbackPass { TELECOM_MARK, WINDOW }

/**
 * [CallbackPass] for an incoming call that the rules would block, `null` = none (E7's H1). First
 * Telecom's own mark, [ecbm] (`Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE`): Telecom keeps the last
 * outgoing emergency call itself - whichever app placed it, the system dialer included, and logged or
 * not - and marks an incoming call on that account within its own callback window (seen on the
 * emulator, E7; whether a Norwegian SIM's calls carry it is a device check). It counts only on a SIM
 * subscription's account ([simAccount]: no other app can register one, so no third-party connection
 * service can claim the mark). Else our window ([windowOpen], [callbackWindowUntil]). Both lazy: the
 * account lookup and the call log are only read when needed.
 */
fun emergencyCallbackPass(ecbm: Boolean, simAccount: () -> Boolean, windowOpen: () -> Boolean): CallbackPass? = when {
    ecbm && simAccount() -> CallbackPass.TELECOM_MARK
    windowOpen() -> CallbackPass.WINDOW
    else -> null
}

/** A call let through on Telecom's mark records the emergency call (placed outside the launcher:
 * Android's keyguard, Emergency SOS) so the parent hears of it - unless one is already known from the
 * last hour ([lastKnownEmergencyMs]: our record or the call log), which would be announced twice. */
fun telecomMarkRecords(lastKnownEmergencyMs: Long?, nowMs: Long): Boolean =
    lastKnownEmergencyMs == null || nowMs - lastKnownEmergencyMs >= CALLBACK_WINDOW_MS

/** The status report's instant of the recorded emergency call: the stored connect instant (one
 * value per call, so the server announces the call once), else - a record an older build wrote -
 * its window's start. */
fun reportedEmergencyAt(storedConnectedAtMs: Long, windowUntilWallMs: Long): Long =
    if (storedConnectedAtMs > 0) storedConnectedAtMs else windowUntilWallMs - CALLBACK_WINDOW_MS

/**
 * A call Telecom reports with an unknown direction (a handover, a conference parent, some
 * connection services): neither the incoming nor the outgoing rules fit, so while managed it is
 * kept only if it's an emergency call or the number is a contact allowed in either direction.
 */
fun decideUnknownDirection(raw: String?, state: CallPolicyState, isEmergency: Boolean): Verdict = when {
    isEmergency -> Verdict.ALLOW
    state is CallPolicyState.Unmanaged -> Verdict.ALLOW
    state !is CallPolicyState.Managed -> Verdict.BLOCK
    !state.rules.callsEnabled -> Verdict.BLOCK
    matches(raw, state.rules, state.rules.inbound + state.rules.outbound) -> Verdict.ALLOW
    else -> Verdict.BLOCK
}

/**
 * What to actually dial for an allowed outgoing [raw] number (QA step 2 #6): the number we checked,
 * not the string typed. Returns the stored contact number when the dialled string differs from it
 * after stripping separators (e.g. a national `91234567`, or a `0`-prefixed form the rules
 * accepted), `null` to dial [raw] unchanged (it already is that number, it's an emergency number,
 * or calls aren't managed).
 */
fun outgoingDialTarget(raw: String?, state: CallPolicyState, isEmergency: Boolean): String? {
    if (isEmergency || raw == null) return null
    val rules = (state as? CallPolicyState.Managed)?.rules ?: return null
    val number = rules.contactFor(raw)?.takeIf { it.outbound }?.number ?: return null
    return if (PhoneNumbers.stripSeparators(raw) == number) null else number
}

/**
 * What the phone book and Home show (QA step 2 #1): with calls on, every phone-book contact;
 * otherwise (calls off, rules unknown) only contacts whose number is an emergency number - those
 * calls always go through. [emergencyDialer]: rules can't be read at all, so there are no contacts
 * - show one "Emergency call" row (calls 112), so 112 is never out of reach (the
 * system dialer is hidden while calls are managed, and a phone without a screen lock has no
 * lock-screen Emergency button).
 */
data class PhoneBookView(val contacts: List<RuleContact>, val emergencyDialer: Boolean) {
    val isEmpty: Boolean get() = contacts.isEmpty() && !emergencyDialer
    val home: List<RuleContact> get() = contacts.filter { it.showOnHome }
}

fun phoneBookView(state: CallPolicyState, isEmergency: (String) -> Boolean): PhoneBookView = when (state) {
    CallPolicyState.Unmanaged -> PhoneBookView(emptyList(), false)
    CallPolicyState.UnknownFailClosed -> PhoneBookView(emptyList(), true)
    is CallPolicyState.Managed -> if (state.rules.callsEnabled) {
        PhoneBookView(state.rules.phoneBook, false)
    } else {
        PhoneBookView(state.rules.phoneBook.filter { isEmergency(it.number) }, false)
    }
}

/**
 * The call rules with a time rule on top (handy step 6): while a rule that allows no calls is
 * active ([callsBlocked]), managed rules act as "calls off" - only emergency numbers out, only the
 * emergency callback window in, the phone book shows emergency contacts only. Fail-closed and
 * unmanaged states are unchanged (unmanaged calls aren't screened by us at all; AppEnforcer still
 * restricts outgoing calls then).
 */
fun withTimeRule(state: CallPolicyState, callsBlocked: Boolean): CallPolicyState =
    if (callsBlocked && state is CallPolicyState.Managed && state.rules.callsEnabled) {
        CallPolicyState.Managed(state.rules.copy(callsEnabled = false))
    } else {
        state
    }

/**
 * Never a second call while one exists (user, fix round 2026-10-06): a new outgoing call is
 * refused while any call is ringing, dialling, active or on hold - except an emergency number,
 * which always goes through. [otherCallStates] are `android.telecom.Call` states of the calls we
 * know; a call still being set up (NEW/CONNECTING/SELECT_PHONE_ACCOUNT - the one being placed
 * now) or already ended doesn't count.
 */
fun secondCallAllowed(otherCallStates: List<Int>, emergency: Boolean): Boolean =
    emergency || otherCallStates.none { it in BUSY_CALL_STATES }

/** Ringing, dialling, active, on hold (`android.telecom.Call.STATE_*`, constants inlined). */
private val BUSY_CALL_STATES = setOf(
    2, // STATE_RINGING
    1, // STATE_DIALING
    4, // STATE_ACTIVE
    3, // STATE_HOLDING
    11, // STATE_PULLING_CALL
    12, // STATE_AUDIO_PROCESSING
    13, // STATE_SIMULATED_RINGING
)
