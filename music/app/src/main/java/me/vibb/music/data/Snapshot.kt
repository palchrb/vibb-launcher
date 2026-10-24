package me.vibb.music.data

/*
 * What the screens and the player read. The renderer's view (`remote`, `forRenderer`, the Storytel
 * branch of `visible`) is ported from palchrb/vibb `pi/vibb/content.py` (`expand_entries`,
 * `PREFER_REMOTE`) and `pi/vibb/storytel.py` (`entries_for(remote)`; MIT License, Copyright (c) 2026
 * palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26b §3.2.
 */

import me.vibb.music.logic.Bookmark
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.MixRef
import me.vibb.music.logic.mixOrder
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.canRepeat
import me.vibb.music.logic.SPOTIFY_PLAYABLE
import me.vibb.music.logic.SpotifyContextTrack
import me.vibb.music.logic.contextItem
import me.vibb.music.logic.flipInEffect
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.newestFirst
import me.vibb.music.logic.visibleKeys
import me.vibb.music.model.Entry
import me.vibb.music.model.Library
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.model.toPlayItem
import me.vibb.music.spotify.SPOTIFY_MODES

/** Builds a [Snapshot] from rows (pure, so the rules are testable). */
fun buildSnapshot(
    lib: Library?,
    items: List<ItemRow>,
    downloads: List<DownloadRow>,
    listings: List<ListingRow>,
    ownCopies: Set<Long>,
    positions: Map<String, Map<String, ItemPosition>>,
    bookmarks: List<BookmarkRow>,
    news: List<NewsRow>,
    shuffle: Set<String>,
    /** `spotify_context.json` (design 26c §5.9): a context-mode entry's live track, by entry key. */
    spotifyContexts: Map<String, SpotifyContextTrack> = emptyMap(),
    /** `order_flips` (design 39 §3.1): entry key -> the parent's direction (newest first) at the kid's flip. */
    flips: Map<String, Boolean> = emptyMap(),
    /** `favourites` (design 50 §1.5): entry key -> the item keys with a heart. */
    favourites: Map<String, Set<String>> = emptyMap(),
    /** `repeat` (design 49 §1.2, Room 6): entry key -> the kid's repeat, only those not off. */
    repeat: Map<String, Repeat> = emptyMap(),
    /** `favourites.heartedAt` (design r12 §3.6): entry key -> item key -> when the heart was set (0: before Room 8). */
    heartedAt: Map<String, Map<String, Long>> = emptyMap(),
): Snapshot {
    val byEntry = items.groupBy { it.entryId }
    val done = downloads.filter { it.done }.groupBy { it.entryId }.mapValues { (_, ds) -> ds.map { it.itemKey }.toSet() }
    val listed = mutableMapOf<Long, List<PlayItem>>()
    val onPhone = mutableMapOf<Long, Set<String>>()
    for (e in lib?.entries.orEmpty()) {
        if (isSpotifyContext(e)) {
            // design 26c §5.9 (QA H3): Spotify picks the tracks, so the entry has one synthetic item - stable key
            // `context`, titled by the live track once one has played - whatever rows its last good list left
            listed[e.id] = listOf(contextItem(e, spotifyContexts[e.key]))
            onPhone[e.id] = emptySet()
        } else if (e.source == Source.OWN) {
            val files = lib!!.filesOf(e.id)
            listed[e.id] = files.map { it.toPlayItem() }
            onPhone[e.id] = files.filter { it.id in ownCopies }.map { it.key }.toSet()
        } else {
            listed[e.id] = byEntry[e.id].orEmpty().sortedBy { it.seq }.map { PlayItem(e.id, it.key, it.title, it.url, it.hls, it.durationMs, artHash = it.artHash) }
            // a Spotify item is never on the phone (design 26c §4.2): a stray download row (the plan prunes it) marks nothing
            onPhone[e.id] = if (e.source == Source.SPOTIFY) emptySet() else done[e.id].orEmpty()
        }
    }
    return Snapshot(
        library = lib,
        listed = listed,
        onPhone = onPhone,
        listingVersions = listings.associate { it.entryId to it.version },
        positions = positions,
        bookmarks = bookmarks.associate { it.entryKey to Bookmark(it.itemKey, it.posMs) },
        news = news.map { NewsItem(it.entryId, it.key, it.seq) },
        shuffle = shuffle,
        flips = flips,
        favourites = favourites,
        repeat = repeat,
        heartedAt = heartedAt,
    )
}

/** Everything the screens and the player read, at one moment. */
data class Snapshot(
    /** Null = no library ("Ingen bibliotek"). */
    val library: Library?,
    /** Per entry, every listed item oldest first (withdrawn ones included). */
    val listed: Map<Long, List<PlayItem>>,
    /** Per entry, the keys with a copy on the phone. */
    val onPhone: Map<Long, Set<String>>,
    val listingVersions: Map<Long, String>,
    /** Entry key -> item key -> position. */
    val positions: Map<String, Map<String, ItemPosition>>,
    /** Entry key -> bookmark. */
    val bookmarks: Map<String, Bookmark>,
    val news: List<NewsItem>,
    /** Entry keys with shuffle on. */
    val shuffle: Set<String>,
    /**
     * Who the items are shown and queued for (design 26b §3.2, vibb `entries_for(remote)`): false =
     * the phone, true = a Sonos room. Set only through [forRenderer], by each caller explicitly (26a §9).
     */
    val remote: Boolean = false,
    /** Spotify items show (design 26c §4.2), since step 5b's player ([SPOTIFY_PLAYABLE]; the tests may turn it off). */
    val spotifyPlayable: Boolean = SPOTIFY_PLAYABLE,
    /** The kid's order flips (design 39 §3.1): entry key -> the parent's direction at the tap (stale once it changed). */
    val flips: Map<String, Boolean> = emptyMap(),
    /** The kid's hearts (design 50 §1.5): entry key -> item keys; phone-only, never synced or reported. */
    val favourites: Map<String, Set<String>> = emptyMap(),
    /** The kid's repeat (design 49 §1.2): entry key -> its mode, only those not off. */
    val repeat: Map<String, Repeat> = emptyMap(),
    /** When each heart was set (design r12 §3.6): entry key -> item key -> wall clock ms, 0 for a heart from before Room 8. */
    val heartedAt: Map<String, Map<String, Long>> = emptyMap(),
) {
    /** Does item [itemKey] of [entry] carry the kid's heart? */
    fun favourite(entry: Entry, itemKey: String): Boolean = itemKey in favourites[entry.key].orEmpty()

    /**
     * The favourites queue's order (design r12 §3.2, [mixOrder]): every heart on an item its entry's listing names, in
     * an entry the library holds - newest heart first, the hearts from before Room 8 after them in library order (`sort`,
     * `id`, as [entries]), then reading order. The "Favoritter" tile's count; both renderers' views start from it.
     */
    fun favouritesOrder(): List<MixRef> {
        val all = library?.entries.orEmpty().sortedWith(compareBy({ it.sort }, { it.id }))
        val hearts = favourites.mapValues { (key, items) -> items.associateWith { heartedAt[key]?.get(it) ?: 0L } }
        return mixOrder(all, { e -> listed[e.id].orEmpty().map { it.key } }, hearts)
    }

    /**
     * Can this view hold [ref] (design r12 §3.2, r8's rule per item)? Its entry in the library, its item visible here
     * ([visible]: on the phone a Storytel book or an own file only with its copy, a listed Spotify item; on a room every
     * Storytel book) - on a room also only what a speaker can play (`PlayBodies.speakerPlayable`: no own file, no Spotify).
     */
    fun holds(ref: MixRef): Boolean {
        val entry = entryByKey(ref.entryKey) ?: return false
        val item = visible(entry).firstOrNull { it.key == ref.itemKey } ?: return false
        return !remote || me.vibb.music.sonos.PlayBodies.speakerPlayable(entry, item)
    }

    /** The library's entry of [key], if it holds one. */
    fun entryByKey(key: String): Entry? = library?.entries?.firstOrNull { it.key == key }

    /** [entry]'s repeat: off where it has none to offer ([canRepeat]). */
    fun repeatOf(entry: Entry): Repeat = if (canRepeat(entry)) repeat[entry.key] ?: Repeat.OFF else Repeat.OFF

    /**
     * This snapshot for a renderer: [remote] true for a Sonos room (the speaker fetches a Storytel book
     * from the CDN itself, so every book shows), false for the phone (a Storytel book only with its
     * copy: download-only). Every other source shows the same on both.
     */
    fun forRenderer(remote: Boolean): Snapshot = if (remote == this.remote) this else copy(remote = remote)

    /**
     * [entry]'s items that show and play, oldest first (21b §6: a withdrawn item without a copy is
     * hidden; design 26b §3.2: a Storytel book, listed without a url, only with its copy - or on a room;
     * design 26c §4.2: a listed Spotify item in both views - it streams, a room's queue drops it - once
     * [spotifyPlayable], and nowhere before; a context-mode entry's one synthetic item, §5.9, in both
     * views too - a room's queue drops it as it drops every Spotify item).
     */
    fun visible(entry: Entry): List<PlayItem> {
        val all = listed[entry.id].orEmpty()
        if (entry.source == Source.SPOTIFY) return if (spotifyPlayable && entry.spotifyMode in SPOTIFY_MODES) all else emptyList()
        if (remote && entry.source == Source.STORYTEL) return all
        val copies = onPhone[entry.id].orEmpty()
        // An own file has no url: it plays only from its copy (the phone never reaches the server).
        val keys = visibleKeys(all.map { KeepItem(it.key, it.url != null) }, copies).toSet()
        return all.filter { it.key in keys }
    }

    /** [entry]'s visible items in play order. */
    fun order(entry: Entry): List<PlayItem> = visible(entry).let { if (shownNewestFirst(entry)) it.asReversed().toList() else it }

    /**
     * Does [entry] show and play newest first - the parent's direction, or its opposite while the kid's flip is in
     * effect (design 39 §3.2: the one seam; `playOrder` - the keep set, the downloads, the art's keep sets - stays the
     * parent's).
     */
    fun shownNewestFirst(entry: Entry): Boolean = me.vibb.music.logic.shownNewestFirst(newestFirst(entry), flips[entry.key])

    /** Is the kid's flip of [entry] in effect (the button peach)? */
    fun flipped(entry: Entry): Boolean = flipInEffect(newestFirst(entry), flips[entry.key])

    /** The carousel's entries: those with something to play, in the parent's order. */
    val entries: List<Entry> by lazy {
        library?.entries.orEmpty().filter { visible(it).isNotEmpty() }.sortedWith(compareBy({ it.sort }, { it.id }))
    }

    fun item(entryId: Long, key: String): PlayItem? = listed[entryId]?.firstOrNull { it.key == key }

    fun hasCopy(entryId: Long, key: String): Boolean = key in onPhone[entryId].orEmpty()
}
