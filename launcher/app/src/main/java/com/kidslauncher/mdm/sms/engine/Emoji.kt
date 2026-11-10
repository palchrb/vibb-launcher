package com.kidslauncher.mdm.sms.engine

/*
 * Design 81 D2 and D13: the one emoji list of the reactions - for parsing, badges and the picker. It is Unicode's
 * emoji-test.txt 16.0, stripped to its sequences, statuses and groups by `scripts/emoji-list.py` into the asset
 * `assets/sms/emoji-16.0.txt` (SmsReactionsTest pins its SHA-256). Pure: `SmsRuntime` reads the asset once and hands
 * the text to [EmojiList.parse].
 */

/** A picker group (D13): Unicode's group name and its fully-qualified emoji in Unicode's order, without the skin-tone
 * variants. */
data class EmojiGroup(val name: String, val emoji: List<String>)

class EmojiList private constructor(
    /** Every listed sequence (fully-qualified, minimally-qualified, unqualified), as it is listed. */
    private val listed: Set<String>,
    /** D2's key -> the fully-qualified form. */
    private val qualified: Map<String, String>,
    val groups: List<EmojiGroup>,
) {
    /**
     * D2's emoji rule: at most [MAX_UNITS] UTF-16 units, the text-style selector U+FE0E dropped, then a sequence the
     * list holds - returned in its fully-qualified form. Anything else (a ZWJ chain or a tag sequence that isn't listed,
     * a lone regional indicator, a bare `1` or `#`) is `null`.
     */
    fun canonical(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_UNITS) return null
        val plain = raw.replace(TEXT_STYLE, "")
        if (plain !in listed) return null
        return qualified[key(plain)]
    }

    companion object {
        /** D2: an emoji part longer than this is never one. */
        const val MAX_UNITS = 16

        private const val TEXT_STYLE = "︎"
        private const val EMOJI_STYLE = "️"

        /** The Fitzpatrick modifiers U+1F3FB..U+1F3FF: a sequence holding one is a skin-tone variant (D13 leaves them
         * out of the picker; a received one still shows as itself). */
        private val SKIN_TONES = 0x1F3FB..0x1F3FF

        val EMPTY = EmojiList(emptySet(), emptyMap(), emptyList())

        /** D2's key: the emoji without its presentation selectors - `❤`, `❤︎` and `❤️` are one emoji. */
        fun key(emoji: String): String = emoji.replace(EMOJI_STYLE, "").replace(TEXT_STYLE, "")

        /** The asset's text: `# group: <name>` lines and `<code points>;<f|m|u>` lines, anything else ignored. */
        fun parse(text: String): EmojiList {
            val listed = HashSet<String>()
            val qualified = HashMap<String, String>()
            val groups = mutableListOf<EmojiGroup>()
            var name: String? = null
            var members = mutableListOf<String>()
            fun close() {
                name?.let { groups += EmojiGroup(it, members) }
                members = mutableListOf()
            }
            for (line in text.lineSequence()) {
                if (line.startsWith("# group: ")) {
                    close()
                    name = line.removePrefix("# group: ").trim()
                    continue
                }
                if (line.startsWith("#") || ';' !in line) continue
                val (points, status) = line.split(';', limit = 2)
                val codes = points.trim().split(' ').map { it.toIntOrNull(16) }
                if (codes.isEmpty() || codes.any { it == null || !Character.isValidCodePoint(it) }) continue
                val emoji = codes.joinToString("") { String(Character.toChars(it!!)) }
                listed += emoji
                if (status.trim() == "f") {
                    qualified[key(emoji)] = emoji
                    if (codes.none { it in SKIN_TONES } && emoji.length <= MAX_UNITS) members += emoji
                }
            }
            close()
            return EmojiList(listed, qualified, groups.filter { it.emoji.isNotEmpty() })
        }
    }
}
