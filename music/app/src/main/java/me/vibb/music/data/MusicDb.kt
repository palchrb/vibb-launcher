package me.vibb.music.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import me.vibb.music.logic.KnownEntry
import me.vibb.music.logic.Listed
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.newsAfter
import me.vibb.music.logic.newsBefore
import me.vibb.music.logic.pruneTray

/*
 * The app's own state (design 21 §4.3-§4.5): listings (filled from the bridge; step 2 from the
 * debug feed), positions and bookmarks, the Nytt tray and the keys already seen, downloads, item
 * errors, shuffle and the last now-playing queue. The library itself is a file (Store).
 * Keyed by entry id, except positions, bookmarks and shuffle, which follow vibb's `state_key`
 * (the library's entry `key`), and design 29's known entries (keys, with the id each last had).
 */

@Entity(tableName = "listings")
data class ListingRow(@PrimaryKey val entryId: Long, val version: String)

@Entity(tableName = "items", primaryKeys = ["entryId", "key"])
data class ItemRow(
    val entryId: Long,
    val key: String,
    /** 0 = the oldest: the listing's order. */
    val seq: Int,
    val title: String,
    val url: String?,
    val hls: Boolean,
    val durationMs: Long?,
    /** The item's own picture (design 27): the 64-hex hash of its 512 px JPEG, or null (the entry's cover). */
    val artHash: String? = null,
)

@Entity(tableName = "seen", primaryKeys = ["entryId", "key"])
data class SeenRow(val entryId: Long, val key: String)

@Entity(tableName = "news", primaryKeys = ["entryId", "key"])
data class NewsRow(val entryId: Long, val key: String, val seq: Long)

/**
 * An entry key the phone has known (design 29 §1.3; grow-only, never pruned: a few hundred short keys
 * at most). [entryId]: the id the library last gave it (null = none now). [pending]: added after the
 * baseline, its one item not given yet; [item] the item given, [newAt] when it joined the tray.
 */
@Entity(tableName = "known_entries")
data class KnownEntryRow(@PrimaryKey val entryKey: String, val entryId: Long?, val pending: Boolean, val item: String?, val newAt: Long?)

/** Design 29's baseline (one row): [closed] once a play has been stored, for good. No row = open. */
@Entity(tableName = "news_baseline")
data class NewsBaselineRow(@PrimaryKey val id: Int = 1, val closed: Boolean)

@Entity(tableName = "positions", primaryKeys = ["entryKey", "itemKey"])
data class PositionRow(val entryKey: String, val itemKey: String, val posMs: Long, val done: Boolean, val updatedAt: Long)

@Entity(tableName = "bookmarks")
data class BookmarkRow(@PrimaryKey val entryKey: String, val itemKey: String, val posMs: Long, val updatedAt: Long)

/** A download, finished or not. [url]/[hls] are what was fetched, so a withdrawn item's copy plays. */
@Entity(tableName = "downloads", primaryKeys = ["entryId", "itemKey"])
data class DownloadRow(
    val entryId: Long,
    val itemKey: String,
    val cacheKey: String,
    val url: String,
    val hls: Boolean,
    val done: Boolean,
    /** Done: the copy's size. Not done: the partial copy's size at the last failure (`DownloadRun.fail`), else 0. */
    val bytes: Long,
    /** The HLS variant fetched (one rendition, played with the same stream key); null = progressive or a media playlist. */
    val variant: Int?,
    /** The url of the last failed attempt: a new url retries at once (21b §6). */
    val failedUrl: String?,
    /** The last failure's `item_errors` code, when, and how many in a row (logic/Offline.kt `mayRetry`). */
    val failedCode: String? = null,
    val failedAt: Long? = null,
    val attempts: Int = 0,
)

/** The latest failure per item (21b §4.3), cleared when the item plays or downloads, its listing changes or its entry goes. */
@Entity(tableName = "item_errors", primaryKeys = ["entryId", "itemKey"])
data class ItemErrorRow(val entryId: Long, val itemKey: String, val version: String, val error: String, val at: Long)

@Entity(tableName = "shuffle")
data class ShuffleRow(@PrimaryKey val entryKey: String, val on: Boolean)

/** The last queue played (one row): back paused after a restart (21 §4.4). Keys joined by '\n'. */
@Entity(tableName = "now_playing")
data class NowPlayingRow(
    @PrimaryKey val id: Int = 0,
    val entryId: Long,
    val keys: String,
    val index: Int,
    val posMs: Long,
    val durationMs: Long?,
    val shuffled: Boolean,
    val updatedAt: Long,
)

@Dao
interface MusicDao {
    @Query("SELECT * FROM listings")
    suspend fun listings(): List<ListingRow>

    @Query("SELECT * FROM listings")
    fun listingsFlow(): Flow<List<ListingRow>>

    @Query("SELECT * FROM items WHERE entryId = :entryId ORDER BY seq")
    suspend fun items(entryId: Long): List<ItemRow>

    @Query("SELECT * FROM items ORDER BY entryId, seq")
    fun allItemsFlow(): Flow<List<ItemRow>>

    @Query("SELECT * FROM items ORDER BY entryId, seq")
    suspend fun allItems(): List<ItemRow>

    /** One listed item's own picture as applied now (design 27, QA L7). */
    @Query("SELECT artHash FROM items WHERE entryId = :entryId AND `key` = :key")
    suspend fun itemArt(entryId: Long, key: String): String?

    @Transaction
    suspend fun replaceListing(listing: ListingRow, items: List<ItemRow>) {
        deleteItems(listing.entryId)
        insertItems(items)
        upsertListing(listing)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListing(row: ListingRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(rows: List<ItemRow>)

    @Query("DELETE FROM items WHERE entryId = :entryId")
    suspend fun deleteItems(entryId: Long)

    @Query("DELETE FROM listings WHERE entryId = :entryId")
    suspend fun deleteListing(entryId: Long)

    @Query("SELECT key FROM seen WHERE entryId = :entryId")
    suspend fun seen(entryId: Long): List<String>

    @Query("SELECT DISTINCT entryId FROM seen")
    suspend fun seenEntries(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeen(rows: List<SeenRow>)

    @Query("DELETE FROM seen WHERE entryId = :entryId")
    suspend fun deleteSeen(entryId: Long)

    @Query("SELECT * FROM news ORDER BY seq DESC")
    suspend fun news(): List<NewsRow>

    @Query("SELECT * FROM news ORDER BY seq DESC")
    fun newsFlow(): Flow<List<NewsRow>>

    @Transaction
    suspend fun replaceNews(rows: List<NewsRow>) {
        clearNews()
        insertNews(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNews(rows: List<NewsRow>)

    @Query("DELETE FROM news")
    suspend fun clearNews()

    @Query("DELETE FROM news WHERE entryId = :entryId AND key = :key")
    suspend fun deleteNews(entryId: Long, key: String): Int

    @Query("DELETE FROM news WHERE entryId = :entryId")
    suspend fun deleteNewsOf(entryId: Long)

    /** Entries the library no longer names take their tray items with them, read and written in one transaction. */
    @Transaction
    suspend fun pruneNews(entryIds: Set<Long>) {
        val tray = news().map { NewsItem(it.entryId, it.key, it.seq) }
        val pruned = pruneTray(tray, entryIds)
        if (pruned != tray) replaceNews(pruned.map { NewsRow(it.entryId, it.key, it.seq) })
    }

    // --- Design 29: a new entry is news too ---

    @Query("SELECT * FROM known_entries")
    suspend fun knownEntries(): List<KnownEntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnownEntries(rows: List<KnownEntryRow>)

    @Query("SELECT closed FROM news_baseline WHERE id = 1")
    suspend fun newsBaselineClosed(): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNewsBaseline(row: NewsBaselineRow)

    /** Has a play ever been stored (a position or a bookmark; every renderer's save writes one)? */
    @Query("SELECT EXISTS(SELECT 1 FROM positions) OR EXISTS(SELECT 1 FROM bookmarks)")
    suspend fun anyPlayed(): Boolean

    @Query("SELECT * FROM seen WHERE entryId IN (:entryIds)")
    suspend fun seenOf(entryIds: List<Long>): List<SeenRow>

    /**
     * Sync phase A (design 29 §1.3-§1.4), before any listing is applied: the known keys and the
     * baseline after this library ([named]: `Library.keyById`), and every id that now carries another
     * key reset like a removed entry (its listing, items, seen rows, tray items and item errors; its
     * download rows stay for the plan). Returns the reset ids.
     */
    @Transaction
    suspend fun newsBeforeTx(named: Map<Long, String>): Set<Long> {
        val known = knownEntries().map { it.known() }
        val closed = newsBaselineClosed() == true
        val r = newsBefore(known, closed, named, anyPlayed())
        val changed = r.known - known.toSet()
        if (changed.isNotEmpty()) upsertKnownEntries(changed.map { it.row() })
        if (r.closed != closed) upsertNewsBaseline(NewsBaselineRow(closed = r.closed))
        for (id in r.reset) {
            deleteItems(id)
            deleteListing(id)
            deleteSeen(id)
            deleteNewsOf(id)
            deleteItemErrors(id)
        }
        return r.reset
    }

    /**
     * Sync phase B (design 29 §1.6), after the listings are applied: the seen rows, the tray, the
     * pending marks and the chosen items in one transaction, the tray read inside it (QA M3).
     * [applied]: this run's listings plus every own entry; [current]: every entry with a parsed listing.
     */
    @Transaction
    suspend fun newsAfterTx(applied: List<Listed>, current: Map<Long, Listed>, now: Long) {
        val known = knownEntries().map { it.known() }
        val tray = news().map { NewsItem(it.entryId, it.key, it.seq) }
        val seen = seenOf(applied.map { it.entry.id }.distinct()).groupBy({ it.entryId }, { it.key }).mapValues { it.value.toSet() }
        val r = newsAfter(known, tray, seen, applied, current, now)
        val changed = r.known - known.toSet()
        if (changed.isNotEmpty()) upsertKnownEntries(changed.map { it.row() })
        if (r.tray != tray) replaceNews(r.tray.map { NewsRow(it.entryId, it.key, it.seq) })
        val seenRows = r.seenAdded.flatMap { (id, keys) -> keys.map { SeenRow(id, it) } }
        if (seenRows.isNotEmpty()) insertSeen(seenRows)
    }

    @Query("SELECT * FROM positions")
    suspend fun allPositions(): List<PositionRow>

    @Query("SELECT * FROM positions WHERE entryKey = :entryKey")
    suspend fun positions(entryKey: String): List<PositionRow>

    @Query("SELECT * FROM positions")
    fun positionsFlow(): Flow<List<PositionRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(row: PositionRow)

    /** vibb's `clear_state` at a queue's natural end: the partial positions go, the done marks stay. */
    @Query("DELETE FROM positions WHERE entryKey = :entryKey AND done = 0")
    suspend fun clearPartialPositions(entryKey: String)

    @Query("SELECT * FROM bookmarks")
    suspend fun bookmarks(): List<BookmarkRow>

    @Query("SELECT * FROM bookmarks")
    fun bookmarksFlow(): Flow<List<BookmarkRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(row: BookmarkRow)

    @Query("DELETE FROM bookmarks WHERE entryKey = :entryKey")
    suspend fun deleteBookmark(entryKey: String)


    @Query("SELECT * FROM downloads")
    suspend fun downloads(): List<DownloadRow>

    @Query("SELECT * FROM downloads")
    fun downloadsFlow(): Flow<List<DownloadRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(row: DownloadRow)

    @Query("DELETE FROM downloads WHERE entryId = :entryId AND itemKey = :itemKey")
    suspend fun deleteDownload(entryId: Long, itemKey: String)

    @Query("SELECT * FROM item_errors ORDER BY at")
    suspend fun itemErrors(): List<ItemErrorRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItemError(row: ItemErrorRow)

    @Query("DELETE FROM item_errors WHERE entryId = :entryId AND itemKey = :itemKey")
    suspend fun deleteItemError(entryId: Long, itemKey: String)

    /** A new listing version: errors reported against another one are stale. */
    @Query("DELETE FROM item_errors WHERE entryId = :entryId AND version != :version")
    suspend fun deleteStaleItemErrors(entryId: Long, version: String)

    @Query("DELETE FROM item_errors WHERE entryId = :entryId")
    suspend fun deleteItemErrors(entryId: Long)

    @Query("SELECT * FROM shuffle")
    fun shuffleFlow(): Flow<List<ShuffleRow>>

    @Query("SELECT * FROM shuffle")
    suspend fun allShuffle(): List<ShuffleRow>

    @Query("SELECT * FROM shuffle WHERE entryKey = :entryKey")
    suspend fun shuffle(entryKey: String): ShuffleRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShuffle(row: ShuffleRow)

    @Query("SELECT * FROM now_playing WHERE id = 0")
    suspend fun nowPlaying(): NowPlayingRow?

    @Query("SELECT * FROM now_playing WHERE id = 0")
    fun nowPlayingFlow(): Flow<NowPlayingRow?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNowPlaying(row: NowPlayingRow)

    @Query("DELETE FROM now_playing")
    suspend fun clearNowPlaying()
}

private fun KnownEntryRow.known() = KnownEntry(entryKey, entryId, pending, item, newAt)

private fun KnownEntry.row() = KnownEntryRow(key, entryId, pending, item, newAt)

/** What [MusicDb.ReapplyListings] runs after adding the column. */
const val REAPPLY_LISTINGS_SQL = "UPDATE listings SET version = ''"

@Database(
    entities = [
        ListingRow::class, ItemRow::class, SeenRow::class, NewsRow::class, PositionRow::class, BookmarkRow::class,
        DownloadRow::class, ItemErrorRow::class, ShuffleRow::class, NowPlayingRow::class, KnownEntryRow::class,
        NewsBaselineRow::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = MusicDb.ReapplyListings::class),
        // Design 29: known_entries and news_baseline, new tables only. Empty = no baseline yet; an
        // upgraded phone has positions, so its first sync takes the baseline from its library.
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class MusicDb : RoomDatabase() {
    abstract fun dao(): MusicDao

    /**
     * Version 2 (design 27) adds `items.artHash`. The listings applied before it carried the art, but
     * the old app dropped it, and their versions still equal the launcher's, so nothing would fetch
     * them again (QA H1): an empty version makes the next sync pull and re-apply every listing from the
     * launcher's copy (no server traffic). The rows stay: prune runs only on parsed listings, and an
     * empty version is never served.
     */
    class ReapplyListings : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) = db.execSQL(REAPPLY_LISTINGS_SQL)
    }

    companion object {
        @Volatile private var instance: MusicDb? = null

        fun get(context: Context): MusicDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MusicDb::class.java, "music.db").build().also { instance = it }
        }
    }
}
