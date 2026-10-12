package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.Source

/*
 * The play order, ported from palchrb/vibb `pi/vibb/library.py` (`_natural_order`,
 * `_expand_target_uncached`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under
 * the GPL-3.0 (or later) - see NOTICE.md. Design 21 §4.4: NRK podkast and RSS play newest first,
 * an NRK serie, own files and a Storytel series (design 26b §3.2) oldest first; an explicit order
 * other than the natural one reverses.
 */

/** Does [entry] naturally play newest first? */
fun naturallyNewestFirst(entry: Entry): Boolean = when (entry.source) {
    Source.OWN -> false
    Source.NRK -> !entry.nrkSerie
    Source.RSS -> true
    Source.STORYTEL -> false // book 1 first (vibb `_natural_order`: storytel = oldest_first)
}

/** Does [entry] play newest first, with its order setting applied? */
fun newestFirst(entry: Entry): Boolean = when (entry.order) {
    "newest_first" -> true
    "oldest_first" -> false
    else -> naturallyNewestFirst(entry)
}

/** [oldestFirst] (a listing, or own files in their order) in [entry]'s play order. */
fun <T> playOrder(entry: Entry, oldestFirst: List<T>): List<T> = if (newestFirst(entry)) oldestFirst.asReversed().toList() else oldestFirst
