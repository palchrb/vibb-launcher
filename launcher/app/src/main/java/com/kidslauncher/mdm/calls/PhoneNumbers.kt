package com.kidslauncher.mdm.calls

/*
 * Phone-number normalisation, shared with kid-phone-server's src/phone.rs (design 02-calls.md 3.4,
 * QA #16). Pure Kotlin, no Android imports: PhoneNumbersTest runs the same vector file as the
 * server (app/src/test/resources/phone_vectors.json, a byte-identical copy of the server's
 * testdata/phone_vectors.json).
 *
 * The server stores contacts normalised; the launcher normalises only the other side of a call
 * and compares strings exactly. No suffix ("last 8 digits") matching and no
 * PhoneNumberUtils.compare: their loose matching would let foreign numbers through.
 */

/** A normalised number: [E164] (`+` and 7-15 digits) or a [Short] number (3-6 digits, matched only
 * exactly - 112, 1881, a voicemail number). [value] is what the server stores. */
sealed interface Normalized {
    val value: String

    data class E164(override val value: String) : Normalized

    data class Short(override val value: String) : Normalized
}

object PhoneNumbers {

    private val SEPARATORS = setOf(' ', '-', '.', '(', ')', '/', ' ')

    /** ASCII only: Kotlin's `isDigit()` also accepts Arabic-Indic and fullwidth digits. */
    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun String.allAsciiDigits() = isNotEmpty() && all { it.isAsciiDigit() }

    /** 1-3 ASCII digits not starting with 0. */
    fun validCountryCode(cc: String): Boolean =
        cc.length in 1..3 && cc.allAsciiDigits() && cc[0] != '0'

    /** [raw] with the separators of rule 1 removed (nothing else). */
    fun stripSeparators(raw: String): String = raw.filterNot { it in SEPARATORS }

    /**
     * The rules of kid-phone-server's src/phone.rs, in the same order:
     * 1. strip ` -.()/` and NBSP; 2. a leading `00` becomes `+`; 3. `+` and 7-15 ASCII digits is
     * E.164; 4. 3-6 ASCII digits without `+` is a short number; 5. other ASCII digits are national:
     * drop one trunk `0`, prefix `+defaultCc`, 7-15 digits again; 6. anything else (`*`, `#`, `,`,
     * `;`, letters, fullwidth `＋`, non-ASCII digits) is `null`, so MMI/USSD codes never match.
     */
    fun normalize(raw: String, defaultCc: String): Normalized? {
        if (!validCountryCode(defaultCc)) return null
        val stripped = stripSeparators(raw)
        if (stripped.isEmpty()) return null
        val (plus, digits) = when {
            stripped.startsWith("+") -> true to stripped.substring(1)
            stripped.startsWith("00") -> true to stripped.substring(2)
            else -> false to stripped
        }
        if (!digits.allAsciiDigits()) return null
        if (plus) return e164(digits)
        if (digits.length in 3..6) return Normalized.Short(digits)
        return e164(defaultCc + digits.removePrefix("0"))
    }

    private fun e164(digits: String): Normalized? =
        if (digits.length in 7..15) Normalized.E164("+$digits") else null

    /**
     * The number in a call handle as Telecom gives it (`Uri.toString()`, still percent-encoded):
     * `tel:<number>` and `sip:<user>@<host>` (VoLTE / Wi-Fi calling handles such as
     * `sip:+4791234567@ims...;user=phone`) give the number or user part, without `;` parameters
     * and percent-decoded. `voicemail:` and anything else give `null` - the voicemail number is
     * never matched against a contact.
     */
    fun numberFromHandle(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val scheme = uri.substring(0, colon).lowercase()
        var rest = uri.substring(colon + 1)
        when (scheme) {
            "tel" -> {}
            "sip", "sips" -> rest = rest.substringBefore('@')
            else -> return null
        }
        val number = percentDecode(rest.substringBefore(';')) ?: return null
        return number.ifEmpty { null }
    }

    /** RFC 3986 percent-decoding as UTF-8; `+` stays `+` (unlike URLDecoder). `null` if malformed. */
    private fun percentDecode(value: String): String? {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 3 > value.length) return null
                val hex = value.substring(i + 1, i + 3)
                if (!hex.all { it in "0123456789abcdefABCDEF" }) return null
                bytes.write(hex.toInt(16))
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** The digits of [raw] after stripping separators, if that's all it is (no `+`, `*`, `#`). */
    fun dialableDigits(raw: String?): String? {
        if (raw == null) return null
        val stripped = stripSeparators(raw)
        return if (stripped.allAsciiDigits()) stripped else null
    }
}
