package me.vibb.music.data

import android.content.Context
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
import kotlinx.coroutines.flow.Flow

/*
 * The app's own state (design 21 §4.3-§4.5): listings (filled from the bridge; step 2 from the
 * debug feed), positions and bookmarks, the Nytt tray and the keys already seen, downloads, item
 * errors, shuffle and the last now-playing queue. The library itself is a file (Store).
 * Keyed by entry id, except positions, bookmarks and shuffle, which follow vibb's `state_key`
 * (the library's entry `key`).
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
)

@Entity(tableName = "seen", primaryKeys = ["entryId", "key"])
data class SeenRow(val entryId: Long, val key: String)

@Entity(tableName = "news", primaryKeys = ["entryId", "key"])
data class NewsRow(val entryId: Long, val key: String, val seq: Long)

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
    val bytes: Long,
    /** The HLS variant fetched (one rendition, played with the same stream key); null = progressive or a media playlist. */
    val variant: Int?,
    /** The url that last failed, so a new url retries at once (21b §6). */
    val failedUrl: String?,
)

@Entity(tableName = "item_errors", primaryKeys = ["entryId", "itemKey"])
data class ItemErrorRow(val entryId: Long, val itemKey: String, val version: String, val error: String, val sync: Long, val at: Long)

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

    @Query("SELECT * FROM positions")
    suspend fun allPositions(): List<PositionRow>

    @Query("SELECT * FROM positions WHERE entryKey = :entryKey")
    suspend fun positions(entryKey: String): List<PositionRow>

    @Query("SELECT * FROM positions")
    fun positionsFlow(): Flow<List<PositionRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(row: PositionRow)

    @Query("DELETE FROM positions WHERE entryKey NOT IN (:keep)")
    suspend fun prunePositions(keep: List<String>)

    @Query("SELECT * FROM bookmarks")
    suspend fun bookmarks(): List<BookmarkRow>

    @Query("SELECT * FROM bookmarks")
    fun bookmarksFlow(): Flow<List<BookmarkRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(row: BookmarkRow)

    @Query("DELETE FROM bookmarks WHERE entryKey = :entryKey")
    suspend fun deleteBookmark(entryKey: String)

    @Query("DELETE FROM bookmarks WHERE entryKey NOT IN (:keep)")
    suspend fun pruneBookmarks(keep: List<String>)

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

@Database(
    entities = [
        ListingRow::class, ItemRow::class, SeenRow::class, NewsRow::class, PositionRow::class, BookmarkRow::class,
        DownloadRow::class, ItemErrorRow::class, ShuffleRow::class, NowPlayingRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MusicDb : RoomDatabase() {
    abstract fun dao(): MusicDao

    companion object {
        @Volatile private var instance: MusicDb? = null

        fun get(context: Context): MusicDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MusicDb::class.java, "music.db").build().also { instance = it }
        }
    }
}
