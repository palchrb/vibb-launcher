package me.vibb.music

import me.vibb.music.data.BookmarkRow
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.ItemRow
import me.vibb.music.data.ListingRow
import me.vibb.music.data.NewsRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.buildSnapshot
import me.vibb.music.logic.ItemPosition
import me.vibb.music.model.Category
import me.vibb.music.model.Entry
import me.vibb.music.model.Library
import me.vibb.music.model.OwnFile
import me.vibb.music.model.Source
import java.io.File

/** Test helpers: entries, items and snapshots without Android. */
object Fixtures {
    fun resource(name: String): String = javaClass.classLoader!!.getResource(name)!!.readText()

    /** A file of the module (Gradle runs unit tests in music/app/). */
    fun moduleFile(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }

    fun entry(
        id: Long,
        source: Source = Source.RSS,
        key: String = "e$id",
        target: String? = if (source == Source.OWN) null else "https://example.org/$id.xml",
        order: String = "auto",
        cache: Int = 5,
        resume: Boolean = source != Source.OWN,
        category: Long? = null,
        items: String? = if (source == Source.OWN) null else "0123456789abcdef",
        sort: Int = id.toInt(),
    ) = Entry(id, key, "Entry $id", category, source, target, order, cache, resume, null, items, sort)

    fun file(id: Long, entry: Long, sort: Int = id.toInt(), missing: Boolean = false) =
        OwnFile(id, entry, "Track $id", null, sort, 180_000, 1000, "%064x".format(id), null, sort, missing)

    fun library(entries: List<Entry>, files: List<OwnFile> = emptyList(), categories: List<Category> = emptyList()) =
        Library("v1", categories, entries, files, emptySet())

    /** Listing rows (oldest first) for [entryId]: keys with a url unless listed in [gone]. */
    fun items(entryId: Long, keys: List<String>, gone: Set<String> = emptySet(), hls: Boolean = false, art: Map<String, String> = emptyMap()) =
        keys.mapIndexed { i, k -> ItemRow(entryId, k, i, "Title $k", if (k in gone) null else "https://cdn.example.org/$k.mp3", hls, 600_000, art[k]) }

    /** A fake 64-hex picture hash (not a real SHA-256 of anything). */
    fun art(name: String): String = name.toByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    fun done(entryId: Long, key: String, url: String = "https://cdn.example.org/$key.mp3", failedUrl: String? = null, finished: Boolean = true) =
        DownloadRow(entryId, key, "e$entryId:$key", url, false, finished, 1000, null, failedUrl)

    fun snapshot(
        lib: Library?,
        items: List<ItemRow> = emptyList(),
        downloads: List<DownloadRow> = emptyList(),
        listings: Map<Long, String> = items.map { it.entryId }.distinct().associateWith { "0123456789abcdef" },
        ownCopies: Set<Long> = emptySet(),
        positions: Map<String, Map<String, ItemPosition>> = emptyMap(),
        bookmarks: Map<String, Pair<String, Long>> = emptyMap(),
        news: List<NewsRow> = emptyList(),
        shuffle: Set<String> = emptySet(),
    ): Snapshot = buildSnapshot(
        lib, items, downloads, listings.map { (id, v) -> ListingRow(id, v) }, ownCopies, positions,
        bookmarks.map { (k, b) -> BookmarkRow(k, b.first, b.second, 0) }, news, shuffle,
    )
}
