package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem

/*
 * Favourites (design 50 §1.5; ours, not vibb's: the user's pick of 2026-10-20): a heart per track or episode, kept on
 * the phone only (Room 6 `favourites`, keyed by the entry KEY and the item key like every per-entry state; never
 * synced or reported), and the "Favoritter" category tile. Pure; FavouritesTest.
 */

/** The "Favoritter" tile's category id: a sentinel no server id can be (the server's ids are positive). */
const val FAVOURITES_CATEGORY = -1L

/** Does item [itemKey] of [entry] carry a heart? Never a Spotify context's synthetic item (its key is no track). */
fun canFavourite(entry: Entry, itemKey: String): Boolean = !isContextItem(entry, itemKey)

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
 * [order] kept to a queue's [scope] (design r8 §1.2): since design r12 a favourites run's keys (`Active.favourites`, the
 * one guard in the service's `activeFor`), or an r8 row's stored scope; null: the whole entry. Option C itself - a cover
 * tap under "Favoritter" playing one entry's hearts - is gone: the chip holds one tile now (logic/Mix.kt).
 */
fun scoped(order: List<PlayItem>, scope: Set<String>?): List<PlayItem> = if (scope == null) order else order.filter { it.key in scope }
