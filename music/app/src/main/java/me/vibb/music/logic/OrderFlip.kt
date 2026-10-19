package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.Source
import me.vibb.music.spotify.SpotifyKind
import me.vibb.music.spotify.spotifyTarget

/*
 * The kid's order flip (design 39 §3; the user's decision, not vibb's - vibb's list follows the parent's order only):
 * a per-entry, per-phone row in Room (`order_flips`) that reverses the shown and played order. The row stores the
 * parent's effective direction at the tap; when that changes the row is stale - ignored at once, pruned at the next
 * sync. Pure; OrderFlipTest.
 */

/**
 * The direction the list shows and plays: [parentNewestFirst] (`newestFirst(entry)`) reversed when the row [flip]
 * (the parent's direction at the tap) still matches it, else - no row, or stale after the parent's change - the
 * parent's.
 */
fun shownNewestFirst(parentNewestFirst: Boolean, flip: Boolean?): Boolean =
    if (flip == parentNewestFirst) !parentNewestFirst else parentNewestFirst

/** Is a row [flip] in effect for an entry whose parent's direction is [parentNewestFirst]? */
fun flipInEffect(parentNewestFirst: Boolean, flip: Boolean?): Boolean = flip == parentNewestFirst

/**
 * The rows the sync deletes: a stale one (the parent changed the effective direction since the tap), and - when
 * every entry the library names was read ([allRead]) - one whose entry is gone.
 */
fun flipsToDelete(flips: Map<String, Boolean>, entries: List<Entry>, allRead: Boolean): Set<String> {
    val byKey = entries.associateBy { it.key }
    return flips.filterTo(mutableMapOf()) { (key, flip) ->
        val entry = byKey[key]
        if (entry == null) allRead else !flipInEffect(newestFirst(entry), flip)
    }.keys
}

/** The words for the shown order under the list's header (§4), by kind. */
enum class OrderWords { OLDEST_FIRST, NEWEST_FIRST, FROM_BOOK_1, FROM_LAST_BOOK, IN_ORDER, REVERSED }

/**
 * The words for [entry] shown [shownNewestFirst]: the dated kinds (NRK, RSS, a Spotify show) name the direction; a
 * Storytel series, own files and a Spotify album or playlist compare it with the listing's order (oldest first: book
 * 1, track 1, the playlist's top).
 */
fun orderWords(entry: Entry, shownNewestFirst: Boolean): OrderWords {
    val dated = when (entry.source) {
        Source.NRK, Source.RSS -> true
        Source.SPOTIFY -> spotifyTarget(entry.target)?.kind == SpotifyKind.SHOW
        Source.STORYTEL, Source.OWN -> false
    }
    return when {
        dated -> if (shownNewestFirst) OrderWords.NEWEST_FIRST else OrderWords.OLDEST_FIRST
        entry.source == Source.STORYTEL -> if (shownNewestFirst) OrderWords.FROM_LAST_BOOK else OrderWords.FROM_BOOK_1
        else -> if (shownNewestFirst) OrderWords.REVERSED else OrderWords.IN_ORDER
    }
}
