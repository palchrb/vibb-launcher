package com.kidslauncher.mdm.sms.engine

import com.kidslauncher.mdm.calls.Normalized
import com.kidslauncher.mdm.calls.OwnNumberRule
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.sms.mms.MmsAddress
import kotlinx.serialization.Serializable

/*
 * Design 80 §4.4: the phone's own number learned from the first MMS from an approved contact addressed to the kid
 * alone - D12's "us" while neither the parent nor a SIM gives one. Pure, no Android imports; tested in OwnLearningTest.
 *
 * What it gives: "us" for the group rule, nothing else - no SMS rule, call rule or allow path. A hostile allowed
 * contact can teach a wrong number (a one-to-one MMS with `To = [stranger]`, the kid Bcc'd, first): the parent sees
 * the number and its teacher in the PWA, corrects it, or turns "Gruppemeldinger" off. A second message that meets
 * every condition with another `To` drops the number (no own number: groups dropped) and reports both - never
 * resolved by the phone itself.
 */

/** The learned state, kept in the engine's store (CE storage). */
@Serializable
data class OwnLearned(
    /** The learned number in use (normalised, a full `+` number); `null` during a conflict. */
    val number: String?,
    /** The teaching sender (normalised). */
    val from: String,
    /** Epoch milliseconds. */
    val at: Long,
    /** `[]`, or the two numbers seen. */
    val conflict: List<String> = emptyList(),
    /** The active SIMs (their subscription ids) when it was learned: another set clears it - a new SIM has another
     * number. */
    val sims: String = "",
)

object OwnLearning {
    fun encode(state: OwnLearned): String = ServerJson.encodeToString(OwnLearned.serializer(), state)

    /** `null` if unreadable: nothing learned (fail closed - no own number, groups dropped). */
    fun decode(json: String?): OwnLearned? = json?.let { runCatching { ServerJson.decodeFromString(OwnLearned.serializer(), it) }.getOrNull() }

    /**
     * The state in force: cleared by a change of the active SIMs ([sims]), by a number the parent stored ([parent]) -
     * which then wins - and by a contact with the learned number (a number that is a contact can't be "us"). An empty
     * [sims] is "can't be read now" (a SIM loading, a modem restart): the caller keeps the stored state and uses none
     * of it ([MmsFlow.ownLearned], code QA L2).
     */
    fun current(stored: OwnLearned?, sims: String, parent: List<OwnNumberRule>, contacts: List<RuleContact>): OwnLearned? {
        val state = stored ?: return null
        if (state.sims != sims) return null
        if (parent.any { it.number.isNotBlank() }) return null
        if (state.number != null && contacts.any { it.number == state.number }) return null
        if (state.conflict.any { seen -> contacts.any { it.number == seen } }) return null
        return state
    }

    /**
     * After a fetched MMS passed D10's checks and its `From` matched the notification's: the state to store, or `null`
     * when nothing changes. Learning only fills a gap and only when every condition holds:
     * - no own number from the parent or a SIM ([known] empty), and no conflict open ([current]);
     * - [senderAllowed]: D4 rule 5 - the sender is a contact with `sms_in` that isn't an emergency number;
     * - exactly one `To` and no `Cc`;
     * - that address a `/TYPE=PLMN` number that normalises to a full `+` number: not a short code, not an emergency
     *   number ([isEmergency]), not the [sender], not a contact on this phone ([contacts]).
     *
     * The first such message teaches; a later one naming the same number changes nothing; one naming another number
     * is the conflict: no number in use, both reported, nothing learned until the parent stores one or the SIMs change.
     */
    fun learn(
        current: OwnLearned?,
        known: Set<String>,
        sender: String,
        senderAllowed: Boolean,
        to: List<MmsAddress>,
        cc: List<MmsAddress>,
        contacts: List<RuleContact>,
        isEmergency: (String) -> Boolean,
        defaultCc: String,
        now: Long,
        sims: String,
    ): OwnLearned? {
        // Code QA L2: while the active SIMs can't be read ("") nothing is learned - the state is kept as it is.
        if (known.isNotEmpty() || !senderAllowed || sims.isEmpty()) return null
        if (current != null && current.conflict.isNotEmpty()) return null
        if (to.size != 1 || cc.isNotEmpty()) return null
        val number = to.single().plmnNumber
            ?.let { PhoneNumbers.normalize(it, defaultCc) as? Normalized.E164 }
            ?.value ?: return null
        if (!number.startsWith("+") || isEmergency(number) || number == sender || contacts.any { it.number == number }) return null
        return when {
            current == null -> OwnLearned(number = number, from = sender, at = now, sims = sims)
            current.number == number -> null
            else -> current.copy(number = null, conflict = listOfNotNull(current.number, number).take(2), at = now)
        }
    }
}
