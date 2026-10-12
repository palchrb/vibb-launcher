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
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.playOrder
import me.vibb.music.logic.visibleKeys
import me.vibb.music.model.Entry
import me.vibb.music.model.Library
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.model.toPlayItem

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
): Snapshot {
    val byEntry = items.groupBy { it.entryId }
    val done = downloads.filter { it.done }.groupBy { it.entryId }.mapValues { (_, ds) -> ds.map { it.itemKey }.toSet() }
    val listed = mutableMapOf<Long, List<PlayItem>>()
    val onPhone = mutableMapOf<Long, Set<String>>()
    for (e in lib?.entries.orEmpty()) {
        if (e.source == Source.OWN) {
            val files = lib!!.filesOf(e.id)
            listed[e.id] = files.map { it.toPlayItem() }
            onPhone[e.id] = files.filter { it.id in ownCopies }.map { it.key }.toSet()
        } else {
            listed[e.id] = byEntry[e.id].orEmpty().sortedBy { it.seq }.map { PlayItem(e.id, it.key, it.title, it.url, it.hls, it.durationMs) }
            onPhone[e.id] = done[e.id].orEmpty()
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
) {
    /**
     * This snapshot for a renderer: [remote] true for a Sonos room (the speaker fetches a Storytel book
     * from the CDN itself, so every book shows), false for the phone (a Storytel book only with its
     * copy: download-only). Every other source shows the same on both.
     */
    fun forRenderer(remote: Boolean): Snapshot = if (remote == this.remote) this else copy(remote = remote)

    /**
     * [entry]'s items that show and play, oldest first (21b §6: a withdrawn item without a copy is
     * hidden; design 26b §3.2: a Storytel book, listed without a url, only with its copy - or on a room).
     */
    fun visible(entry: Entry): List<PlayItem> {
        val all = listed[entry.id].orEmpty()
        if (remote && entry.source == Source.STORYTEL) return all
        val copies = onPhone[entry.id].orEmpty()
        // An own file has no url: it plays only from its copy (the phone never reaches the server).
        val keys = visibleKeys(all.map { KeepItem(it.key, it.url != null) }, copies).toSet()
        return all.filter { it.key in keys }
    }

    /** [entry]'s visible items in play order. */
    fun order(entry: Entry): List<PlayItem> = playOrder(entry, visible(entry))

    /** The carousel's entries: those with something to play, in the parent's order. */
    val entries: List<Entry> by lazy {
        library?.entries.orEmpty().filter { visible(it).isNotEmpty() }.sortedWith(compareBy({ it.sort }, { it.id }))
    }

    fun item(entryId: Long, key: String): PlayItem? = listed[entryId]?.firstOrNull { it.key == key }

    fun hasCopy(entryId: Long, key: String): Boolean = key in onPhone[entryId].orEmpty()
}
