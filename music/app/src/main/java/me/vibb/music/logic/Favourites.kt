package me.vibb.music.logic

import me.vibb.music.model.Entry

/*
 * Favourites (design 50 §1.5; ours, not vibb's: the user's pick of 2026-10-20): a heart per track or episode, kept on
 * the phone only (Room 6 `favourites`, keyed by the entry KEY and the item key like every per-entry state; never
 * synced or reported), and the "Favoritter" category tile. Pure; FavouritesTest.
 */

/** The "Favoritter" tile's category id: a sentinel no server id can be (the server's ids are positive). */
const val FAVOURITES_CATEGORY = -1L

/** Does item [itemKey] of [entry] carry a heart? Never a Spotify context's synthetic item (its key is no track). */
fun canFavourite(entry: Entry, itemKey: String): Boolean = !isContextItem(entry, itemKey)

/** The keys of the entries that hold a favourite, from [favourites] (entry key -> item keys). */
fun favouriteEntryKeys(favourites: Map<String, Set<String>>): Set<String> = favourites.filterValues { it.isNotEmpty() }.keys

/**
 * The hearts of [entryKey] a parsed listing (or, for own files, the parsed library) no longer names ([listedKeys]):
 * the only prune (§1.5). A hidden or withdrawn item still listed - kept with its copy - keeps its heart.
 */
fun favouritesToPrune(favourites: Map<String, Set<String>>, entryKey: String, listedKeys: Set<String>): List<String> =
    favourites[entryKey].orEmpty().filter { it !in listedKeys }.sorted()

/**
 * The entries whose hearts all go: one the parsed library no longer names - only when every entry it names was read
 * ([allRead]), as design 39's flips (`flipsToDelete`).
 */
fun favouriteEntriesGone(favourites: Map<String, Set<String>>, entries: List<Entry>, allRead: Boolean): Set<String> {
    if (!allRead) return emptySet()
    val keys = entries.map { it.key }.toSet()
    return favourites.keys.filter { it !in keys }.toSet()
}

/**
 * A tap on the "Favoritter" tile (§1.5, the proposal "drop if unwanted", built): with exactly one entry holding a
 * favourite among [shown] that entry's id - the screen plays it as its cover would - else null (the tile filters).
 */
fun favouritesTapOpens(shown: List<Entry>, favouriteEntries: Set<String>): Long? =
    shown.filter { it.key in favouriteEntries }.singleOrNull()?.id
