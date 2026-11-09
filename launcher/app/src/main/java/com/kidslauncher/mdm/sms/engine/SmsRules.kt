package com.kidslauncher.mdm.sms.engine

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.calls.smsMode

/*
 * Design 77 D4 (receive) and D5 (send): the gate inside the engine, at receive and at every send, so no front end can
 * go around it. Pure, no Android imports; the launcher-only vectors `sms_rules.json` run in SmsRulesTest (QA L8: the
 * phone judges every message, the server none).
 *
 * No sender is let in for being an emergency number (QA H1, R2-H1): anyone can send as "112" (§3). The callback window
 * after an emergency call covers the emergency services texting back.
 */

/** What the receive path does with a message. */
sealed interface IncomingVerdict {
    /** Shown. [unknownSender]: let in by the callback window alone (rule 2) - its thread line says so.
     * [contact]: the phone-book contact it came from, if any. */
    data class Allow(val unknownSender: Boolean, val contact: RuleContact?) : IncomingVerdict

    /** D15: the rules can't be judged now and the role is ours - kept, not shown, judged again later. */
    data object HoldRules : IncomingVerdict

    /** D16: a stranger - kept on the phone for the parent's decision, never shown. */
    data object HoldParent : IncomingVerdict

    /** Rule 4 or 8: no allow path - only who and when are kept. */
    data object Block : IncomingVerdict
}

/** The rules' inputs besides the message, each asked only when a rule needs it. */
class SmsJudge(
    /** The whole policy ([com.kidslauncher.mdm.calls.CallPolicyStore.state]), not the calls channel's. */
    val state: CallPolicyState,
    /** We hold the SMS role (D1). */
    val roleHeld: Boolean,
    /** The engine is up (its store open); `false` holds for the rules (D15). */
    val ready: Boolean = true,
    /** A time rule that allows no calls is in force (the override and the pause lift it, as for calls). */
    val timeBlocked: () -> Boolean = { false },
    /** The callback window after an emergency call is open ([com.kidslauncher.mdm.calls.callbackWindowUntil]). */
    val callbackWindowOpen: () -> Boolean = { false },
    /** The platform's emergency verdict (or `null` when it threw) for a dialable number, ORed with the static set. */
    val platformEmergency: (String) -> Boolean? = { null },
    /** Step 3b: a subscription's SIM operator and MMS maximum (`null` = the default SMS subscription). */
    val carrier: (Int?) -> Carrier = { Carrier(null, 0) },
    /** D12: each SIM slot's own number as the SIM knows it (normalised; `null` when it doesn't). */
    val simNumbers: () -> List<Pair<Int, String?>> = { emptyList() },
) {
    /** D12: the phone's own numbers - the parent's, else the SIM's ([MmsGate.ownNumbers]). */
    fun ownNumbers(): Set<String> = MmsGate.ownNumbers(rules?.ownNumbers.orEmpty(), simNumbers())

    val rules: CallRules? get() = (state as? CallPolicyState.Managed)?.rules

    val defaultCc: String get() = rules?.defaultCc ?: "47"

    /** An emergency number by the static set of the default country or the platform (R2-H1). */
    fun isEmergency(number: String): Boolean =
        com.kidslauncher.mdm.calls.Emergency.isEmergencyOutgoing(number, defaultCc, platformEmergency)
}

/** A SIM's MCC/MNC ([operator], `null` when unknown) and the carrier's MMS maximum in bytes (0 = none given: the codec's
 * default, `MmsCodec.capOf`). */
data class Carrier(val operator: String?, val maxMessageBytes: Int)

object SmsRules {
    /**
     * D4, the first rule that applies wins:
     * 1. the sender's [kind] comes from the raw PDU ([SmsSenderType]);
     * 2. the callback window is open: ALLOW any sender with an address (alphanumeric included);
     * 3. the rules can't be judged and the role is ours: HOLD for the rules (D15) - but SMS "Ikke styrt" with the role
     *    still ours (a hand-back pending or failed, QA L3) is ALLOW, unfiltered;
     * 4. SMS isn't "Styrt av Vibb" ("Av"): BLOCK (a guard - `DISALLOW_SMS` stops it first);
     * 5. a number that normalises, is a contact with `sms_in` and isn't an emergency number: ALLOW;
     * 6. a sender name with a letter on the parent's list: ALLOW;
     * 7. any other number that normalises, or a sender name: HOLD for the parent (D16);
     * 8. anything else - malformed, empty, a number that doesn't normalise: BLOCK.
     */
    fun decideIncoming(kind: SenderKind, raw: String?, judge: SmsJudge): IncomingVerdict {
        val sender = SenderClean.of(kind, raw, judge.defaultCc)
        val hasSender = sender.senderClass in setOf(SenderClass.NUMBER, SenderClass.SHORT, SenderClass.ALPHANUMERIC)
        val byRules = if (hasSender && judge.ready) allowedBy(kind, sender.sender, judge) else null
        if (hasSender && judge.callbackWindowOpen()) return IncomingVerdict.Allow(unknownSender = byRules == null, contact = byRules?.contact)
        // QA L3: SMS "Ikke styrt" while the role is still ours (the hand-back pending or failed) - unfiltered, as the
        // parent chose: kept and shown, never blocked and lost. Not a filter decision, so no "unknown sender" line.
        if (judge.roleHeld && judge.state.smsMode == ChannelMode.UNMANAGED) {
            return IncomingVerdict.Allow(unknownSender = false, contact = judge.rules?.contacts?.firstOrNull { it.number == sender.sender })
        }
        val rules = judge.rules
        if (!judge.ready || rules == null) {
            return if (judge.roleHeld && judge.state !is CallPolicyState.Unmanaged) IncomingVerdict.HoldRules else IncomingVerdict.Block
        }
        if (rules.smsMode != ChannelMode.MANAGED) return IncomingVerdict.Block
        if (byRules != null) return IncomingVerdict.Allow(unknownSender = false, contact = byRules.contact)
        return if (hasSender) IncomingVerdict.HoldParent else IncomingVerdict.Block
    }

    /** Rules 5 and 6: whom the parent let text the kid. [sender] is [SenderClean.of]'s text. `null` = not allowed. */
    private class Allowed(val contact: RuleContact?)

    private fun allowedBy(kind: SenderKind, sender: String, judge: SmsJudge): Allowed? {
        val rules = judge.rules ?: return null
        return when (kind) {
            SenderKind.NUMBER -> rules.contacts.firstOrNull { it.number == sender && it.smsIn }
                ?.takeUnless { judge.isEmergency(sender) }?.let { Allowed(it) }
            SenderKind.ALPHANUMERIC -> if (SmsNames.valid(sender) && SmsNames.listed(rules.smsNames, sender)) Allowed(null) else null
            SenderKind.MALFORMED -> null
        }
    }

    /**
     * D16's release: whether rule 5 or 6 now lets this exact held sender in - a number equal to a contact with
     * `sms_in` that isn't an emergency number, or a name on the list (with a letter). A name never releases a number,
     * nor the reverse. [sender] is the held row's (normalised number or cleaned name).
     */
    fun releases(kind: SenderKind, sender: String, judge: SmsJudge): Boolean =
        judge.rules?.smsMode == ChannelMode.MANAGED && allowedBy(kind, sender, judge) != null

    /** What a send is to: a normalised number, or a refusal before any rule (nothing to send to). */
    sealed interface SendVerdict {
        data class Allow(val number: String, val contact: RuleContact?) : SendVerdict

        data class Refused(val reason: Refusal) : SendVerdict
    }

    /**
     * D5, at the engine's `send`, every time, whoever calls it ([raw] is the number to send to):
     * 1. an emergency number: ALLOW (Norway's 110/112/113 don't take SMS; harmless, consistent with calls - QA I7);
     * 2. the rules unknown, SMS not "Styrt av Vibb", or a time rule without calls: BLOCK;
     * 3. a contact with `sms_out`: ALLOW;
     * 4. anything else: BLOCK.
     * The override PIN and the pause never open SMS; they lift a time rule's block, as for calls ([SmsJudge.timeBlocked]).
     */
    fun decideSend(raw: String?, judge: SmsJudge): SendVerdict {
        if (raw.isNullOrBlank()) return SendVerdict.Refused(Refusal.NOT_ALLOWED)
        val number = PhoneNumbers.normalize(raw, judge.defaultCc)?.value
        if (judge.isEmergency(raw)) return SendVerdict.Allow(number ?: PhoneNumbers.stripSeparators(raw), null)
        val rules = judge.rules
        return when {
            rules == null -> SendVerdict.Refused(Refusal.RULES_UNKNOWN)
            judge.state.smsMode != ChannelMode.MANAGED -> SendVerdict.Refused(Refusal.SMS_OFF)
            judge.timeBlocked() -> SendVerdict.Refused(Refusal.TIME_RULE)
            number == null -> SendVerdict.Refused(Refusal.NOT_ALLOWED)
            else -> rules.contacts.firstOrNull { it.number == number && it.smsOut }
                ?.let { SendVerdict.Allow(number, it) } ?: SendVerdict.Refused(Refusal.NOT_ALLOWED)
        }
    }
}
