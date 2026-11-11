package com.kidslauncher.mdm.sms.engine

import com.kidslauncher.mdm.calls.MmscRule
import com.kidslauncher.mdm.calls.OwnNumberRule
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.mms.MmsAddress
import java.net.URI
import java.net.URISyntaxException

/*
 * Design 77 D10 (the MMS download is gated before any fetch: the sender and the host) and D12 (the group rule). Pure,
 * no Android imports, tested in MmsGateTest. The engine runs these on what the isolated parse handed back (D8): the
 * main process never parses a PDU, it judges the strings.
 *
 * The promise, as D10 states it since design 83: an MMS is never fetched for a blocked sender, nor for a held one before
 * the parent's allow, nor from a host that is neither unasked for the SIM nor allowed by the parent for it - the
 * policy's row for the SIM's MCC/MNC holds exactly those (`docs/design/83-mmsc-hosts.md`).
 */
object MmsGate {
    /** D10 check 1: `From` is `<+ and ASCII digits>/TYPE=PLMN`. */
    sealed interface Sender {
        /** The number to normalise and judge by D4 ([SmsRules.decideIncoming]). */
        data class Number(val number: String) : Sender

        /** No allow path: a hidden or missing sender ([SenderClass.EMPTY]), an `@` address, another `TYPE=`, letters
         * ([SenderClass.MALFORMED]). */
        data class Blocked(val senderClass: SenderClass) : Sender
    }

    fun sender(from: MmsAddress?): Sender {
        if (from == null) return Sender.Blocked(SenderClass.EMPTY)
        return from.plmnNumber?.let { Sender.Number(it) } ?: Sender.Blocked(SenderClass.MALFORMED)
    }

    /** D10 check 2's answer. */
    sealed interface Location {
        data class Ok(val host: String) : Location

        /** A plain http(s) URL whose host and port make [entry], which isn't on this SIM's list - an unknown MCC/MNC
         * is this with an empty list. Fail closed: nothing is fetched, the bubble says the picture can't be fetched,
         * and a contact's is reported for the parent's allow (design 83 §4: the entry, never the URL). */
        data class Unlisted(val entry: String) : Location

        /** A location that isn't a plain http(s) URL to a host of the entry grammar: blocked as `bad_location`, with
         * no allow path. */
        data object Bad : Location
    }

    /** The ports a bare `host` entry covers; any other port is part of the entry (design 83 §1). */
    private val PLAIN_PORTS = setOf(80, 443, 8080)

    /**
     * D10 check 2 on `X-Mms-Content-Location`: printable ASCII with no whitespace (the parser keeps CR, LF and TAB, and
     * latin-1 octets - the codec's note for step 3b), a URI whose scheme is `http` or `https`, with no userinfo, whose
     * host - lower-cased, a trailing dot off a name of several labels (a single label with one is refused) - and port
     * make a valid entry ([validEntry]: `host` with no port or 80, 443 or 8080, else `host:port`). That entry is then
     * looked up, exactly, on [table]'s row for [operator] (the SIM's MCC/MNC). A location that fails the shape is
     * [Location.Bad] before the operator is asked.
     */
    fun location(contentLocation: String, operator: String?, table: List<MmscRule>): Location {
        if (contentLocation.isEmpty() || contentLocation.any { it.code !in 0x21..0x7E }) return Location.Bad
        val uri = try {
            URI(contentLocation)
        } catch (e: URISyntaxException) {
            return Location.Bad
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Location.Bad
        if (uri.isOpaque || uri.rawUserInfo != null) return Location.Bad
        // A host Java can't read as a server name (an underscore, a registry-based authority) has no `host`.
        val named = uri.host?.lowercase() ?: return Location.Bad
        val host = named.trimEnd('.')
        // `mmsc.` is an absolute name: another lookup than the entry `mmsc`, which the resolver's search list completes.
        if (host != named && '.' !in host) return Location.Bad
        val entry = if (uri.port == -1 || uri.port in PLAIN_PORTS) host else "$host:${uri.port}"
        if (!validEntry(entry)) return Location.Bad
        val hosts = table.firstOrNull { it.mccmnc == operator }?.hosts.orEmpty()
        return if (hosts.any { it.equals(entry, ignoreCase = true) }) Location.Ok(host) else Location.Unlisted(entry)
    }

    /**
     * Design 83 §1's entry, one grammar on both sides (`sms_contract.json`'s `mmsc_entries`; the server's
     * `mmsc_hosts::valid_entry`): `host` or `host:port`. The host is 1-253 bytes of `[a-z0-9.-]` in non-empty labels,
     * and either its last label starts with a letter or it is exactly four decimal octets - so nothing a resolver could
     * read as another address (`010.0.0.1`, `2130706433`) - whose first is neither 0 nor 127: "this host" is no MMS
     * server, and no question to put to a parent. The port is 1-65535 without a leading zero and never 80, 443 or 8080:
     * on those the bare host is the entry.
     */
    fun validEntry(entry: String): Boolean {
        if (':' in entry) {
            val port = entry.substringAfter(':')
            if (port.length !in 1..5 || port[0] == '0' || port.any { it !in '0'..'9' }) return false
            if (port.toInt() > 65535 || port.toInt() in PLAIN_PORTS) return false
        }
        val host = entry.substringBefore(':')
        if (host.length !in 1..253 || host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) return false
        return labels.last()[0] in 'a'..'z' || (labels.size == 4 && labels.all(::octet) && labels[0] != "0" && labels[0] != "127")
    }

    private fun octet(label: String): Boolean =
        label.length <= 3 && label.all { it in '0'..'9' } && (label == "0" || label[0] != '0') && label.toInt() <= 255

    /** Design 83 §7: [extra]'s hosts (the debug build's fake MMSC) joined into [table]'s row for their MCC/MNC - a
     * second row for it would never be read ([location] takes the first). */
    fun merged(table: List<MmscRule>, extra: List<MmscRule>): List<MmscRule> =
        (table + extra).groupBy { it.mccmnc }.map { (mccmnc, rows) -> MmscRule(mccmnc, rows.flatMap { it.hosts }.distinct()) }

    /** Whether this SIM's MCC/MNC has hosts on the list (`sms_state.mmsc_known`). */
    fun known(operator: String?, table: List<MmscRule>): Boolean =
        operator != null && table.any { it.mccmnc == operator && it.hosts.any { h -> h.isNotBlank() } }

    /** D10 check 3: the notification's `X-Mms-Message-Size` within the carrier's maximum ([cap], from `capOf`). */
    fun sizeOk(size: Long, cap: Int): Boolean = size in 0..cap.toLong()

    /** After the download (D10): the retrieve-conf's `From` must be the notification's - both `/TYPE=PLMN`, equal once
     * normalised. [notified] is the notification's sender as stored (normalised). */
    fun sameSender(notified: String, retrieved: MmsAddress?, defaultCc: String): Boolean {
        val number = retrieved?.plmnNumber ?: return false
        return PhoneNumbers.normalize(number, defaultCc)?.value == notified
    }

    /** D12's answer for a fetched MMS. */
    sealed interface Group {
        /** Only the sender and us: the sender's own conversation. */
        data object OneToOne : Group

        /** A group: the other addresses (normalised, sorted), each a contact with a text switch in effect. */
        data class Many(val others: List<String>) : Group

        /** A group with any other address - or any group while no own number is known: blocked, no allow path. */
        data object Blocked : Group
    }

    /**
     * D12. A group is more than one other address in from, to and cc. [sender] is the (normalised, allowed) sender;
     * [own] the phone's own numbers ([ownNumbers]). With no own number at all, a message addressed to more than one
     * address is blocked: with `To = [kid, stranger]`, "the one unknown address must be us" would let the stranger in.
     * One recipient alone is us (or we were Bcc'd), so it is the sender's own conversation either way. Every other
     * address must be a contact with `sms_in` or `sms_out` ([contacts]); a recipient that isn't a PLMN number is no
     * contact. The caller has checked that the sender ALLOWs by D4 rule 5. Design 80 D13: with [groups] off
     * ("Gruppemeldinger") every group is blocked, whatever its members; a one-to-one message is untouched.
     */
    fun group(
        sender: String,
        to: List<MmsAddress>,
        cc: List<MmsAddress>,
        own: Set<String>,
        contacts: List<RuleContact>,
        defaultCc: String,
        groups: Boolean = true,
    ): Group {
        val judged = members(sender, to, cc, own, contacts, defaultCc)
        return if (!groups && judged is Group.Many) Group.Blocked else judged
    }

    private fun members(
        sender: String,
        to: List<MmsAddress>,
        cc: List<MmsAddress>,
        own: Set<String>,
        contacts: List<RuleContact>,
        defaultCc: String,
    ): Group {
        val recipients = to + cc
        if (own.isEmpty()) return if (recipients.size <= 1) Group.OneToOne else Group.Blocked
        val others = mutableSetOf<String>()
        for (address in recipients) {
            val number = address.plmnNumber?.let { PhoneNumbers.normalize(it, defaultCc)?.value } ?: return Group.Blocked
            if (number in own || number == sender) continue
            others += number
        }
        if (others.isEmpty()) return Group.OneToOne
        val texting = contacts.filter { it.smsIn || it.smsOut }.mapTo(mutableSetOf()) { it.number }
        return if (others.all { it in texting }) Group.Many(others.sorted()) else Group.Blocked
    }

    /**
     * D12's own numbers: the parent's, per SIM slot; a slot the parent left empty takes the number its SIM knows
     * ([sim]: slot to normalised number, `null` when the SIM doesn't know). At most two. Design 80 §4.4: the number
     * [learned] from a message ([OwnLearning]) is "us" only while the parent's and the SIMs' are all missing.
     */
    fun ownNumbers(parent: List<OwnNumberRule>, sim: List<Pair<Int, String?>>, learned: String? = null): Set<String> {
        val bySlot = parent.filter { it.number.isNotBlank() }.associate { it.slot to it.number }
        val filled = sim.filter { (slot, number) -> number != null && slot !in bySlot }.associate { it.first to it.second!! }
        val known = (bySlot + filled).entries.sortedBy { it.key }.map { it.value }.take(2).toSet()
        return known.ifEmpty { setOfNotNull(learned) }
    }

    /** A group conversation's key (§6.6): its sorted participant set. */
    fun groupKey(participants: Collection<String>): String = participants.sorted().joinToString(",")

    /** The transaction id an answer (NotifyResp, Acknowledge) can carry: the codec's rule (README, "For step 3b"); a
     * notification whose id fails it can't be answered, so it is treated as malformed. */
    private val TRANSACTION_ID = Regex("""[\x21-\x7E]{1,64}""")

    fun answerableId(transactionId: String): Boolean = TRANSACTION_ID.matches(transactionId)
}
