package com.kidslauncher.mdm.sms.engine

import com.kidslauncher.mdm.calls.Normalized
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.day.TitleRules
import java.text.Normalizer
import java.util.Locale

/*
 * Design 77 D17 (the parent's sender names) and D16 (the sender cleaned by its class before it leaves the phone): one
 * rule with the server's `sms.rs`, pinned by the shared `sms_contract.json` (`names`, `name_matches`, `clean`; the
 * server runs `intake` against what [SenderClean.of] gives). Pure, tested in SmsContractTest.
 */

object SmsNames {
    /** An alphanumeric TP-OA holds at most 11 GSM-7 characters. */
    const val MAX = 11

    /** At most this many names a phone (the server's limit; the phone reads what it is sent). */
    const val LIST_MAX = 50

    /** The shared cleaner (the server's `calendar::clean`), never cut: a longer name is no name. */
    fun clean(raw: String?): String = TitleRules.clean(raw, Int.MAX_VALUE)

    /**
     * A valid name: 1-11 code points after [clean] and at least one letter (Unicode's Alphabetic, the server's
     * `char::is_alphabetic`) - so "+4791234567", "112" and "91 23 45 67" are never names (QA R2-H1).
     */
    fun valid(raw: String?): Boolean {
        val name = clean(raw)
        val count = name.codePointCount(0, name.length)
        return count in 1..MAX && name.codePoints().anyMatch { Character.isAlphabetic(it) }
    }

    /** The one fold of both sides (QA R2-L1): NFC, then lowercase by Unicode's default mapping (Rust's
     * `str::to_lowercase`), no full case folding. */
    fun fold(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** Whether [sender] (an alphanumeric sender's raw text) is [listed], by the fold of both cleaned texts. */
    fun same(listed: String, sender: String): Boolean = fold(clean(listed)) == fold(clean(sender))

    /** Whether the parent's list ([names], as sent) lets [sender] through: a valid listed name equal by the fold. */
    fun listed(names: List<String>, sender: String): Boolean {
        val wanted = fold(clean(sender))
        return names.asSequence().take(LIST_MAX).any { valid(it) && fold(clean(it)) == wanted }
    }
}

/** The sender classes a held or blocked row carries (D16, §6.3, the server's intake). */
enum class SenderClass(val wire: String) {
    NUMBER("number"),
    SHORT("short"),
    ALPHANUMERIC("alphanumeric"),
    MALFORMED("malformed"),
    EMPTY("empty"),
    GROUP("group"),
    BAD_LOCATION("bad_location"),
}

/** A sender as it may leave the phone: its class and the cleaned text (empty for the classes without one). */
data class CleanSender(val senderClass: SenderClass, val sender: String)

object SenderClean {
    private val POSTABLE_NUMBER = Regex("\\+?[0-9]{2,15}")

    /**
     * The sender of a message of [kind] whose raw address is [raw], cleaned by its class (QA R2-M2):
     * - a number: normalised ([PhoneNumbers.normalize], the shared vectors) - E.164 is [SenderClass.NUMBER], 3-6
     *   digits [SenderClass.SHORT]; one that doesn't normalise is [SenderClass.MALFORMED], with no text;
     * - a name: [SmsNames]' cleaner cut to 11 code points; nothing left is [SenderClass.EMPTY];
     * - malformed: no text. An empty address is [SenderClass.EMPTY].
     */
    fun of(kind: SenderKind, raw: String?, defaultCc: String): CleanSender {
        if (raw.isNullOrEmpty()) return CleanSender(SenderClass.EMPTY, "")
        return when (kind) {
            SenderKind.NUMBER -> when (val number = PhoneNumbers.normalize(raw, defaultCc)) {
                is Normalized.E164 -> postable(SenderClass.NUMBER, number.value)
                is Normalized.Short -> postable(SenderClass.SHORT, number.value)
                null -> CleanSender(SenderClass.MALFORMED, "")
            }
            SenderKind.ALPHANUMERIC -> TitleRules.clean(raw, SmsNames.MAX).let {
                if (it.isEmpty()) CleanSender(SenderClass.EMPTY, "") else CleanSender(SenderClass.ALPHANUMERIC, it)
            }
            SenderKind.MALFORMED -> CleanSender(SenderClass.MALFORMED, "")
        }
    }

    /** `+` and digits, 3-16 characters - what the server's intake takes for a number. */
    private fun postable(senderClass: SenderClass, value: String): CleanSender =
        if (value.length in 3..16 && POSTABLE_NUMBER.matches(value)) CleanSender(senderClass, value) else CleanSender(SenderClass.MALFORMED, "")
}
