package com.kidslauncher.mdm.ui.search

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/*
 * Search in the kid's lists (design 79 §4.2, `SearchFoldTest`): the phone book and Meldinger match a typed query against
 * the names the kid sees. Pure.
 *
 * The fold is for finding only - it never decides anything. D17's sender names (`SmsSenders.fold`) stay exact on
 * purpose: a looser fold would let a "Vípps" through on the parent's "Vipps". `SearchFoldTest` pins that nothing under
 * `sms/` or in `CallRules.kt` calls it.
 */

/** A query is cut to this many characters (code points) before it is folded. */
const val SEARCH_QUERY_MAX = 40

/**
 * [text] folded, and for each of its chars the source range it came from: [start] the index in the source string of the
 * code point it came from, [end] the index after that code point and any code point after it that folded to nothing (a
 * combining mark belongs to the letter before it).
 */
class Folded(val text: String, private val start: IntArray, private val end: IntArray) {
    /** The source range of the folded range [from, to), clamped to [0, [sourceLength]]. */
    fun sourceSpan(from: Int, to: Int, sourceLength: Int): Pair<Int, Int> {
        if (text.isEmpty() || from >= to) return 0 to 0
        val a = start[from.coerceIn(0, text.length - 1)].coerceIn(0, sourceLength)
        val b = end[(to - 1).coerceIn(0, text.length - 1)].coerceIn(a, sourceLength)
        return a to b
    }
}

/**
 * The search fold (QA L11): built from the shown string itself, code point by code point - `lowercase(Locale.ROOT)`,
 * NFD, the combining marks dropped, then the letters NFD can't split (ø o, æ ae, œ oe, ß ss, đ d, ł l, þ th, ı i). So
 * "o" finds "Ørjan", "a" finds "Åse", "e" finds "Hélène", and a decomposed "Björn" folds like a composed one.
 */
fun searchFold(source: String): Folded {
    val out = StringBuilder(source.length)
    val starts = ArrayList<Int>(source.length)
    val ends = ArrayList<Int>(source.length)
    var i = 0
    while (i < source.length) {
        val cp = source.codePointAt(i)
        val next = i + Character.charCount(cp)
        val piece = foldCodePoint(cp)
        if (piece.isEmpty()) {
            // Folded to nothing (a combining mark): it belongs to the char before it.
            if (ends.isNotEmpty()) ends[ends.size - 1] = next
        } else {
            for (c in piece) {
                out.append(c)
                starts += i
                ends += next
            }
        }
        i = next
    }
    return Folded(out.toString(), starts.toIntArray(), ends.toIntArray())
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
fun searching(query: String): Boolean = searchFold(searchQuery(query)).text.isNotEmpty()

/** [name] against [query]; `null` = no match (an empty query matches nothing). A word starts after a space, "-" or "'". */
fun searchMatch(name: String, query: String): SearchMatch? {
    val q = searchFold(searchQuery(query)).text
    if (q.isEmpty()) return null
    val folded = searchFold(name)
    val text = folded.text
    val at: Int
    val rank: MatchRank
    if (text.startsWith(q)) {
        at = 0
        rank = MatchRank.PREFIX
    } else {
        val word = (1 until text.length).firstOrNull { i -> text[i - 1] in WORD_BREAKS && text.startsWith(q, i) }
        if (word != null) {
            at = word
            rank = MatchRank.WORD
        } else {
            at = text.indexOf(q)
            if (at < 0) return null
            rank = MatchRank.INSIDE
        }
    }
    val (start, end) = folded.sourceSpan(at, at + q.length, name.length)
    return SearchMatch(rank, start, end)
}

private val WORD_BREAKS = setOf(' ', '-', '\'')

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
