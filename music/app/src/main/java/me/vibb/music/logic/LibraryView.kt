package me.vibb.music.logic

import me.vibb.music.model.Category
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.spotify.spotifyEpisodes
import java.util.Locale

/*
 * The library screen's rules (design 21 §4.2, mockup v12 `Main.dc.html`): category tiles, the
 * carousel's pages, the hint under a cover, search and the list rows. The category rules follow
 * palchrb/vibb `pi/ui.py`'s category carousel (`carousel_cats`, scoped entry carousels; MIT
 * License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Pure; LibraryViewTest and CarouselTest (vibb's tests/cat_carousel.py and
 * ui_carousel_slide.py).
 */

/** What a cover says it is: NRK and RSS "Podkast", own files "Egne filer", Storytel "Lydbok" (design
 * 26b §3.5), and Spotify by what it holds, never by its source (design 26c §4.3, step 8: the kid's app
 * names no source - a Spotify entry is a tile like any other): a show or an episode "Podkast" (episodes,
 * `spotifyEpisodes`), a playlist, album, track or artist "Musikk" (tracks). */
enum class Kind { PODCAST, OWN, AUDIOBOOK, MUSIC }

fun kindOf(entry: Entry): Kind = when (entry.source) {
    Source.OWN -> Kind.OWN
    Source.STORYTEL -> Kind.AUDIOBOOK
    Source.NRK, Source.RSS -> Kind.PODCAST
    Source.SPOTIFY -> if (spotifyEpisodes(entry)) Kind.PODCAST else Kind.MUSIC
}

/**
 * The keys "del N av M", `started` and the progress line count (design 26b §3.5, QA M5): for Storytel
 * the series' reading order ([readingOrder], the listing's order: every book, downloaded or not,
 * whatever the play order - a book's number is its place in the series, qa-26b-step1-code L5), so
 * book 3 of 5 with books 1 and 3 on the phone reads "del 3 av 5" and a bookmark on a book heard on a
 * room still counts; for every other entry the visible items in play order ([visibleInPlayOrder]).
 */
fun countedOrder(entry: Entry, visibleInPlayOrder: List<String>, readingOrder: List<String>): List<String> =
    if (entry.source == Source.STORYTEL) readingOrder else visibleInPlayOrder

/** The hint under a cover. */
sealed interface Hint {
    val kind: Kind

    /** Resume on and started: "<kind> · episode|spor|del N av M" (by the kind: a podcast's episodes, music's tracks). */
    data class Started(override val kind: Kind, val n: Int, val of: Int) : Hint

    /** Resume on, not started: "<kind> · fortsetter der du slapp". */
    data class NotStarted(override val kind: Kind) : Hint

    /** Resume off: "<kind> · starter fra begynnelsen". */
    data class FromStart(override val kind: Kind) : Hint

    /**
     * A Spotify entry in context mode (design 26c §4.3, §5.9): "Musikk · rekkefølgen velges for deg" - no "N av M"
     * (nothing is listed: Spotify picks the tracks), whether started or not; no source named (step 8).
     */
    data class Picks(override val kind: Kind) : Hint
}

/** Has the kid started [entry] (its bookmark is in [order] past the first item's start)? */
fun started(order: List<String>, bookmark: Bookmark?): Boolean {
    val i = bookmark?.let { order.indexOf(it.itemKey) } ?: -1
    return i > 0 || (i == 0 && bookmark!!.posMs > 0)
}

fun hint(entry: Entry, order: List<String>, bookmark: Bookmark?): Hint = when {
    isSpotifyContext(entry) -> Hint.Picks(kindOf(entry))
    !entry.resume -> Hint.FromStart(kindOf(entry))
    started(order, bookmark) -> Hint.Started(kindOf(entry), order.indexOf(bookmark!!.itemKey) + 1, order.size)
    else -> Hint.NotStarted(kindOf(entry))
}

/** The thin overall-progress line of a started resume entry: (items before + fraction of this) / all. */
fun overallProgress(order: List<String>, bookmark: Bookmark?, durationMs: Long?): Float {
    val i = bookmark?.let { order.indexOf(it.itemKey) } ?: -1
    if (i < 0 || order.isEmpty()) return 0f
    val frac = if (durationMs != null && durationMs > 0) (bookmark!!.posMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    return ((i + frac) / order.size).coerceIn(0f, 1f)
}

/** The category tiles after "Alle": the categories with entries here, in their order. */
fun categoryTiles(categories: List<Category>, entries: List<Entry>): List<Category> =
    categories.filter { c -> entries.any { it.category == c.id } }.sortedWith(compareBy({ it.sort }, { it.id }))

/**
 * Single choice: a tile shows only its category; tapping it again, or Alle (null), shows all. "Favoritter" is the
 * sentinel [FAVOURITES_CATEGORY] (design 50 §1.5), a tile like the others.
 */
fun tapCategory(selected: Long?, tapped: Long?): Long? = if (tapped == null || tapped == selected) null else tapped

/** Is [entry] in [selected]: its category, or for "Favoritter" ([FAVOURITES_CATEGORY]) one of [favourites]' entry keys. */
private fun inCategory(entry: Entry, selected: Long, favourites: Set<String>): Boolean =
    if (selected == FAVOURITES_CATEGORY) entry.key in favourites else entry.category == selected

/**
 * The carousel's entries for [selected]; a category that emptied out under the kid falls back to all. [favourites]:
 * the entry keys holding a favourite (design 50 §1.5), for the "Favoritter" tile.
 */
fun shownEntries(entries: List<Entry>, selected: Long?, favourites: Set<String> = emptySet()): List<Entry> {
    val scoped = if (selected == null) emptyList() else entries.filter { inCategory(it, selected, favourites) }
    return scoped.ifEmpty { entries }
}

/** The selection still in force ([selected] unless its category - or the favourites - emptied out). */
fun effectiveCategory(entries: List<Entry>, selected: Long?, favourites: Set<String> = emptySet()): Long? =
    selected?.takeIf { s -> entries.any { inCategory(it, s, favourites) } }

/** Virtual pages: more than one entry wraps (laps of the list), so a swipe never hits an end. */
const val CAROUSEL_LAPS = 1000

/**
 * The carousel's first index (design 50 §1.3, ours: "husker hvilken tile som ble spilt"): the stored now-playing
 * entry's ([entryId]) place in the [shown] order, 0 without one or when it isn't shown (another category, gone).
 */
fun carouselStartIndex(shown: List<Entry>, entryId: Long?): Int = shown.indexOfFirst { it.id == entryId }.coerceAtLeast(0)

/**
 * The carousel opens on the last played tile once (design 50 §1.3, QA 50-5): [first] answers the start index the first
 * time it is asked, and never after the kid [moved] (turned the carousel, picked a category, came back from the player
 * - `backToLibrary` wins) or after it already answered.
 */
class CarouselStart {
    private var settled = false

    fun moved() {
        settled = true
    }

    fun first(shown: List<Entry>, entryId: Long?): Int? {
        if (settled) return null
        settled = true
        return carouselStartIndex(shown, entryId)
    }
}

fun carouselPageCount(n: Int): Int = if (n > 1) n * CAROUSEL_LAPS else n

/** The page in the middle laps that shows entry [index]. */
fun carouselStartPage(n: Int, index: Int): Int = if (n > 1) (CAROUSEL_LAPS / 2) * n + index.coerceIn(0, n - 1) else 0

fun carouselIndex(page: Int, n: Int): Int = if (n <= 0) 0 else page.mod(n)

/**
 * A tap on a neighbour turns from where the carousel is going ([targetPage]), not from what is on
 * screen mid-slide: a tap caught during a slide is never lost (vibb's mash rule).
 */
fun neighbourTapTarget(targetPage: Int, direction: Int, pageCount: Int): Int =
    (targetPage + direction).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/** A cover tap plays the entry the carousel lands on, never the departing one. */
fun coverTapIndex(targetPage: Int, n: Int): Int = carouselIndex(targetPage, n)

/** What a tap on carousel page [page] does. */
sealed interface CarouselTap {
    /** Play the entry at this index. */
    data class Play(val index: Int) : CarouselTap

    /** Turn to this page. */
    data class TurnTo(val page: Int) : CarouselTap
}

/**
 * A tap on [page]: the cover the carousel is landing on ([targetPage]) or the one on screen
 * ([currentPage], a cover still sliding in) plays the landed entry; a neighbour turns one page
 * from the target.
 */
fun carouselTap(page: Int, targetPage: Int, currentPage: Int, n: Int, pageCount: Int): CarouselTap =
    if (page == targetPage || page == currentPage) {
        CarouselTap.Play(coverTapIndex(targetPage, n))
    } else {
        CarouselTap.TurnTo(neighbourTapTarget(targetPage, (page - targetPage).coerceIn(-1, 1), pageCount))
    }

/** One search hit. */
sealed interface SearchHit {
    val entry: Entry

    data class EntryHit(override val entry: Entry) : SearchHit
    data class ItemHit(override val entry: Entry, val item: PlayItem) : SearchHit
}

const val SEARCH_MAX = 12

/**
 * Live hits over entry and item titles, at most [SEARCH_MAX]: each entry that matches, then its
 * matching items. An empty query lists the entries.
 */
fun search(query: String, entries: List<Entry>, itemsOf: (Entry) -> List<PlayItem>): List<SearchHit> {
    val q = query.trim().lowercase()
    val hits = mutableListOf<SearchHit>()
    for (e in entries) {
        if (q.isEmpty() || e.name.lowercase().contains(q)) hits += SearchHit.EntryHit(e)
        if (q.isNotEmpty()) itemsOf(e).filter { it.title.lowercase().contains(q) }.forEach { hits += SearchHit.ItemHit(e, it) }
        if (hits.size >= SEARCH_MAX) break
    }
    return hits.take(SEARCH_MAX)
}

/** A row in "Liste". */
data class ListRow(
    val item: PlayItem,
    val number: Int,
    val playing: Boolean,
    val heard: Boolean,
    val offline: Boolean,
    /** The row's heart (design 50 §1.5): set or not; null = no heart (a Spotify context's synthetic item). */
    val favourite: Boolean? = null,
)

/**
 * [shown] (the play order, or the shuffled queue) as rows; a heard item shows ✓, the playing one its state.
 * [favourites]: the entry's item keys with a heart, null when its rows carry none (design 50 §1.5).
 */
fun listRows(shown: List<PlayItem>, currentKey: String?, heard: Set<String>, onPhone: Set<String>, favourites: Set<String>? = null): List<ListRow> =
    shown.mapIndexed { i, item ->
        ListRow(item, i + 1, item.key == currentKey, item.key in heard && item.key != currentKey, item.key in onPhone, favourites?.let { item.key in it })
    }

/** A row's leading text (design 39 §4, r12 §1): none in a pictured list - a picture, its own or the series cover, is
 *  never written on; else ✓ for a heard row, else its number. */
fun rowBadge(row: ListRow, pictures: Boolean): String? = when {
    pictures -> null
    row.heard -> "✓"
    else -> row.number.toString()
}

/** 754_000 -> "12:34", 3_723_000 -> "1:02:03". */
fun formatDuration(ms: Long?): String {
    if (ms == null || ms < 0) return ""
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}
