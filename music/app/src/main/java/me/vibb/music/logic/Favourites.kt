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

/*
 * Option C (design r8 §1, the user's pick of 2026-10-21): under "Favoritter" a cover tap plays only that entry's hearted
 * items, in the entry's own order. The queue's scope - the hearts at the tap that the listing still names
 * (`Snapshot.heartsListed`, the same in both renderers' views) - is fixed with the queue and kept on every rebuild
 * (`Active.favourites`, `NowPlayingRow.favourites`); null is the whole entry, as before. A pick of a row inside the
 * loaded queue's scope stays in it; any other pick plays the whole entry from that row.
 */

/** [order] kept to a queue's [scope]; null: the whole entry. */
fun scoped(order: List<PlayItem>, scope: Set<String>?): List<PlayItem> = if (scope == null) order else order.filter { it.key in scope }

/** What a "Favoritter" cover tap plans from: [keys] in play order, and where it starts ([bookmark]; null: the top). */
data class FavouritesScope(val keys: List<String>, val bookmark: Bookmark?)

/**
 * A "Favoritter" cover tap over [order] (this renderer's play order: the phone's view, or a room's), kept to the stored
 * [scope] - [FavouritesScope.keys] is only this plan's order, never what is stored. It starts at the entry's [bookmark]
 * when that item is in the keys; else, resume on, at the first key's own place ([position], `resumeFrom`); resume off,
 * at no bookmark (`coverTapPlan` starts at the top). The result goes to the unchanged `coverTapPlan`/`coverTapStart`.
 * A Storytel bookmark on a hearted book that isn't on the phone waits before this (`coverTapAwaitsBook`, given the
 * bookmark only when it is in [scope]), so it never moves to another book here.
 */
fun favouritesScope(order: List<String>, scope: Set<String>, bookmark: Bookmark?, resume: Boolean, position: (String) -> ItemPosition?): FavouritesScope {
    val keys = order.filter { it in scope }
    val start = when {
        bookmark != null && bookmark.itemKey in keys -> bookmark
        resume && keys.isNotEmpty() -> Bookmark(keys.first(), resumeFrom(position(keys.first())))
        else -> null
    }
    return FavouritesScope(keys, start)
}

/** A cover tap's plan input: the whole [order] at the entry's [bookmark], or with a [scope] [favouritesScope]'s. */
fun coverTapScope(order: List<String>, scope: Set<String>?, bookmark: Bookmark?, resume: Boolean, position: (String) -> ItemPosition?): FavouritesScope =
    if (scope == null) FavouritesScope(order, bookmark) else favouritesScope(order, scope, bookmark, resume, position)

/** The bookmark a cover tap's Storytel wait looks at (`coverTapAwaitsBook`): only one in the [scope] - an unhearted book never waits. */
fun bookmarkIn(bookmark: Bookmark?, scope: Set<String>?): Bookmark? = bookmark?.takeIf { scope == null || it.itemKey in scope }
