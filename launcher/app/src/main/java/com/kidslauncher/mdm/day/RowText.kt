package com.kidslauncher.mdm.day

/*
 * A Dagen row's second line (design 69 §2.3): the location when there is one, else the description's first sentence -
 * pure; FirstSentenceTest. The row shows two lines of each at most; the whole description is on the event's page.
 */

/** What a row shows of a text, and whether something was dropped ([cut]: the row shows "›"). */
data class Cut(val text: String, val cut: Boolean) {
    override fun toString() = "Cut(${text.length}, $cut)"
}

/** The second line's cap, in code points. */
const val SECOND_LINE_MAX = 120

/** Words whose "." doesn't end a sentence ("ca. 13.15", "kl. 8", "f.eks. gymtøy"). */
private val ABBREVIATIONS = setOf("ca", "kl", "nr", "osv", "bl.a", "f.eks")

/**
 * The cleaned description up to and including the first ". ", "! " or "? " - a "." after a digit (an ordinal, "5. trinn",
 * a time "09.11.") or after one of [ABBREVIATIONS] ends nothing - or the whole text; then at most [SECOND_LINE_MAX]
 * code points, cut at the last space before it (hard when there is none) and ended with "…".
 */
fun firstSentence(description: String): Cut {
    val text = description.trim()
    var end = text.length
    var i = 0
    while (i < text.length - 1) {
        val c = text[i]
        if ((c == '.' || c == '!' || c == '?') && text[i + 1] == ' ' && endsSentence(text, i)) {
            end = i + 1
            break
        }
        i++
    }
    val sentence = text.substring(0, end)
    val clamped = clamp(sentence)
    return Cut(clamped, end < text.length || clamped != sentence)
}

private fun endsSentence(text: String, at: Int): Boolean {
    if (text[at] != '.') return true
    if (at > 0 && text[at - 1].isDigit()) return false
    val start = text.lastIndexOf(' ', at - 1) + 1
    val word = text.substring(start, at).trimStart { !it.isLetterOrDigit() }.lowercase()
    return word !in ABBREVIATIONS
}

private fun clamp(text: String): String {
    if (text.codePointCount(0, text.length) <= SECOND_LINE_MAX) return text
    val hard = text.substring(0, text.offsetByCodePoints(0, SECOND_LINE_MAX))
    val space = hard.lastIndexOf(' ')
    return (if (space > 0) hard.substring(0, space) else hard).trimEnd() + "…"
}

/** The second line: the location when non-blank, else [firstSentence] of the description. */
fun secondLine(location: String, description: String): Cut =
    if (location.isNotBlank()) Cut(location, false) else firstSentence(description)
