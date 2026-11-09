package com.kidslauncher.mdm.ui.search

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/*
 * Search in the kid's lists (design 79 §4.2, `SearchFoldTest`): the phone book and Meldinger match a typed query against
 * the names the kid sees. Pure.
 *
 * **One way** (code QA M3, the coordinator's decision): the name is folded, the query is only lowercased and normalised
 * - "o" finds "Ola", "Ørjan" and "Østby", but "ø" finds only Ø names and "å" never finds "Anna" ([searchMatch]).
 *
 * The fold is for finding only - it never decides anything. D17's sender names (`SmsSenders.fold`) stay exact on
 * purpose: a looser fold would let a "Vípps" through on the parent's "Vipps". `SearchFoldTest` pins that nothing under
 * `sms/` or in `CallRules.kt` calls it.
 */

/** A query is cut to this many characters (code points) before it is folded. */
const val SEARCH_QUERY_MAX = 40

/**
 * The search fold (QA L11): the shown string folded code point by code point - `lowercase(Locale.ROOT)`,
 * NFD, the combining marks dropped, then the letters NFD can't split (ø o, æ ae, œ oe, ß ss, đ d, ł l, þ th, ı i). So
 * "o" finds "Ørjan", "a" finds "Åse", "e" finds "Hélène", and a decomposed "Björn" folds like a composed one.
 */
fun searchFold(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val cp = source.codePointAt(i)
        out.append(foldCodePoint(cp))
        i += Character.charCount(cp)
    }
    return out.toString()
}

private fun foldCodePoint(cp: Int): String {
    val lower = String(Character.toChars(cp)).lowercase(Locale.ROOT)
    val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
    val out = StringBuilder(decomposed.length)
    var i = 0
    while (i < decomposed.length) {
        val c = decomposed.codePointAt(i)
        i += Character.charCount(c)
        when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> {}
            else -> out.append(LETTERS[c] ?: String(Character.toChars(c)))
        }
    }
    return out.toString()
}

/** The letters NFD leaves whole. */
private val LETTERS = mapOf(
    'ø'.code to "o",
    'æ'.code to "ae",
    'œ'.code to "oe",
    'ß'.code to "ss",
    'đ'.code to "d",
    'ł'.code to "l",
    'þ'.code to "th",
    'ı'.code to "i",
)

/** How well a name matches: it starts with the query, a word in it does, or it holds it. */
enum class MatchRank { PREFIX, WORD, INSIDE }

/** A hit: its [rank] and the matched part of the shown name, [start] to [end] (source indices, for the highlight). */
data class SearchMatch(val rank: MatchRank, val start: Int, val end: Int)

/** The query as it is searched: trimmed at both ends (inner spaces kept), at most [SEARCH_QUERY_MAX] characters. */
fun searchQuery(raw: String): String {
    val trimmed = raw.trim()
    val count = trimmed.codePointCount(0, trimmed.length)
    return if (count <= SEARCH_QUERY_MAX) trimmed else trimmed.substring(0, trimmed.offsetByCodePoints(0, SEARCH_QUERY_MAX))
}

/** Whether [query] (as the kid typed it) searches anything: it holds more than spaces. */
fun searching(query: String): Boolean = searchFold(searchQuery(query)).isNotEmpty()

/**
 * One letter of a name or a query: a code point with the combining marks after it, [lower] (lowercase ROOT, NFC) and
 * [folded] ([searchFold]'s form); [start]/[end] its range in the shown string.
 */
private class Letter(val lower: String, val folded: String, val start: Int, val end: Int) {
    /** Typed as a plain letter: folding changes nothing (no mark, not Æ Ø Å and the like). */
    val plain: Boolean get() = lower == folded
}

private fun letters(source: String): List<Letter> {
    val out = ArrayList<Letter>(source.length)
    var i = 0
    while (i < source.length) {
        var end = i + Character.charCount(source.codePointAt(i))
        while (end < source.length && isMark(source.codePointAt(end))) end += Character.charCount(source.codePointAt(end))
        val unit = source.substring(i, end)
        out += Letter(Normalizer.normalize(unit.lowercase(Locale.ROOT), Normalizer.Form.NFC), searchFold(unit), i, end)
        i = end
    }
    return out
}

private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

/**
 * The query's letters from the name's letter [at] on (code QA M3, the coordinator's decision: one way). A query letter
 * matches a name letter that is the same after lowercase - so "ø" finds only Ø, "å" never finds "Anna" - and a plain
 * query letter also matches what the name's letter folds to - so "o" still finds "Ola", "Ørjan" and "Østby", "a" finds
 * "Åse", "ae" finds "Æ". The query may end inside a letter that folds to two ("a" finds "Ærlig"). Returns the name's
 * letter index after the match, or -1.
 */
private fun matchAt(name: List<Letter>, query: List<Letter>, at: Int): Int {
    var k = at
    var j = 0
    while (j < query.size) {
        val n = name.getOrNull(k) ?: return -1
        val q = query[j]
        if (q.lower == n.lower) {
            j++
            k++
            continue
        }
        if (!q.plain || n.folded.isEmpty()) return -1
        // Plain query letters against the name letter's folded form ("ae" for Æ, "o" for Ø).
        var pos = 0
        while (pos < n.folded.length && j < query.size && query[j].plain && n.folded.startsWith(query[j].lower, pos)) {
            pos += query[j].lower.length
            j++
        }
        val whole = pos == n.folded.length
        if (pos == 0 || (!whole && j < query.size)) return -1
        k++
    }
    return k
}

/** [name] against [query]; `null` = no match (an empty query matches nothing). A word starts after a space, "-" or "'". */
fun searchMatch(name: String, query: String): SearchMatch? {
    val q = letters(searchQuery(query))
    if (q.isEmpty()) return null
    val n = letters(name)
    fun hit(at: Int, rank: MatchRank): SearchMatch? {
        val end = matchAt(n, q, at)
        if (end < 0) return null
        return SearchMatch(rank, n[at].start, n[end - 1].end)
    }
    hit(0, MatchRank.PREFIX)?.let { return it }
    for (i in 1 until n.size) if (n[i - 1].lower in WORD_BREAKS) hit(i, MatchRank.WORD)?.let { return it }
    for (i in 1 until n.size) hit(i, MatchRank.INSIDE)?.let { return it }
    return null
}

private val WORD_BREAKS = setOf(" ", "-", "'")

/**
 * The hits of [items] for [query] (QA N2): the name starts with the query, then a word in it does, then it holds it -
 * each rank A-Å by [collator], ties in the given order. Empty for an empty query.
 */
fun <T> searchHits(items: List<T>, name: (T) -> String, query: String, collator: Collator): List<Pair<T, SearchMatch>> =
    items.mapNotNull { item -> searchMatch(name(item), query)?.let { item to it } }
        .sortedWith(compareBy<Pair<T, SearchMatch>> { it.second.rank }.thenComparator { a, b -> collator.compare(name(a.first), name(b.first)) })

/** The lists' A-Å (design 79 §3.1): the language's own order (nb puts Æ, Ø and Å last), case and accents only after
 * the letters (strength SECONDARY). One per locale, kept. */
fun listCollator(locale: Locale): Collator = collators.getOrPut(locale) {
    Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
}

private val collators = java.util.concurrent.ConcurrentHashMap<Locale, Collator>()
