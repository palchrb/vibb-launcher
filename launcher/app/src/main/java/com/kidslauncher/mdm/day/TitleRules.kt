package com.kidslauncher.mdm.day

import java.text.Normalizer

/*
 * Dagen's title rules (design 69 §3.4, docs/design/69-dagen.md at the monorepo root): the parent's rules, applied on
 * the phone wherever a title shows (the day, the event's page, the glance line, a reminder). One grammar with the
 * server's `calendar.rs` - cleaning, matching that ignores case per code point and whitespace, the three actions -
 * pinned by the shared `calendar_title_rules.json` (TitleRulesTest). Pure, no Android imports.
 */

const val TITLE_MAX = 200
const val DESCRIPTION_MAX = 2000
const val PATTERN_MAX = 120
const val TEXT_MAX = 40

enum class RuleField { TITLE, DESCRIPTION }
enum class RuleMatch { STARTS, CONTAINS }
enum class RuleAction { REPLACE, PREFIX, HIDE }

/** One usable rule ([calendarPolicy] drops the rest), its pattern and text already cleaned. [calendar] `null` = any. */
data class TitleRule(
    val id: Long,
    val calendar: String?,
    val field: RuleField,
    val match: RuleMatch,
    val pattern: String,
    val action: RuleAction,
    val text: String,
) {
    override fun toString() = "TitleRule($id)"
}

object TitleRules {
    /** Unicode's White_Space, spelled out as the server's `is_space`. */
    fun isSpace(cp: Int): Boolean = cp in 0x9..0xD || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
        cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** Removed outright: the other control characters, the bidi marks, overrides and isolates, the invisible ones. */
    private fun isDropped(cp: Int): Boolean = cp in 0x0..0x1F || cp in 0x7F..0x9F || cp == 0x61C || cp == 0x200B ||
        cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2064 || cp in 0x2066..0x2069 || cp == 0xFEFF

    /** Cleaning (§3.4): whitespace runs one space, the control and bidi characters out, trimmed, NFC, at most [max]
     * code points (trimmed again after the cut). */
    fun clean(raw: String?, max: Int): String {
        if (raw == null) return ""
        val spaced = StringBuilder(raw.length)
        var space = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSpace(cp) -> space = true
                isDropped(cp) -> Unit
                else -> {
                    if (space && spaced.isNotEmpty()) spaced.append(' ')
                    space = false
                    spaced.appendCodePoint(cp)
                }
            }
        }
        val normal = Normalizer.normalize(spaced, Normalizer.Form.NFC)
        val count = normal.codePointCount(0, normal.length)
        val cut = if (count <= max) normal else normal.substring(0, normal.offsetByCodePoints(0, max))
        return cut.trimEnd(' ')
    }

    /** One non-space code point: folded, and where it starts and ends in the text (UTF-16 indices). */
    private class Folded(val cp: Int, val start: Int, val end: Int)

    private fun folded(text: String): List<Folded> {
        val out = ArrayList<Folded>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (!isSpace(cp)) out += Folded(Character.toLowerCase(cp), i, next)
            i = next
        }
        return out
    }

    /** Where [pattern] is in [text], ignoring case and whitespace (§3.4): from the first matched code point to the end
     * of the last (UTF-16, end exclusive). Starts with: at the start only; Contains: the first occurrence. */
    fun find(text: String, pattern: String, match: RuleMatch): Pair<Int, Int>? {
        val hay = folded(text)
        val needle = folded(pattern).map { it.cp }
        if (needle.isEmpty() || needle.size > hay.size) return null
        fun at(k: Int) = needle.indices.all { hay[k + it].cp == needle[it] }
        val k = when (match) {
            RuleMatch.STARTS -> if (at(0)) 0 else return null
            RuleMatch.CONTAINS -> (0..hay.size - needle.size).firstOrNull(::at) ?: return null
        }
        return hay[k].start to hay[k + needle.size - 1].end
    }

    private const val SEPARATORS = " -–—:·|,;/"

    /** The rules on one event (§3.4): the first rule whose calendar is `null` or [calendarKey] and whose field's
     * cleaned text matches wins; `null` = hidden. No rule applies: the cleaned title. */
    fun apply(rules: List<TitleRule>, calendarKey: String?, title: String?, description: String?): String? =
        applied(rules, calendarKey, title, description).shown

    /** What the rules did: [rule] the index of the one that applied (`null` = none), [shown] `null` = hidden. */
    data class Applied(val rule: Int?, val shown: String?) {
        override fun toString() = "Applied($rule)"
    }

    fun applied(rules: List<TitleRule>, calendarKey: String?, title: String?, description: String?): Applied {
        val cleanTitle = clean(title, TITLE_MAX)
        val cleanDescription by lazy { clean(description, DESCRIPTION_MAX) }
        for ((index, rule) in rules.withIndex()) {
            if (rule.calendar != null && rule.calendar != calendarKey) continue
            val text = if (rule.field == RuleField.TITLE) cleanTitle else cleanDescription
            val (start, end) = find(text, rule.pattern, rule.match) ?: continue
            val shown = when (rule.action) {
                RuleAction.HIDE -> null
                RuleAction.REPLACE -> rule.text
                RuleAction.PREFIX -> {
                    val rest = clean(cleanTitle.substring(0, start) + " " + cleanTitle.substring(end), Int.MAX_VALUE)
                        .trim { it in SEPARATORS }
                    when {
                        rule.text.isEmpty() && rest.isEmpty() -> cleanTitle
                        rule.text.isEmpty() -> rest
                        rest.isEmpty() -> rule.text
                        else -> "${rule.text} $rest"
                    }
                }
            }
            return Applied(index, shown)
        }
        return Applied(null, cleanTitle)
    }
}
