package me.vibb.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.RestrictionKeys
import me.vibb.music.bridge.storageLimit
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.coversToDelete
import me.vibb.music.logic.isArtHash
import me.vibb.music.logic.recentArt
import me.vibb.music.model.Library
import me.vibb.music.model.LibraryParser
import java.io.File

/**
 * Where the app keeps what it received and what it plays: the library file, received listings
 * (applied into Room by the sync), covers, own files' copies (noBackupFilesDir, never backed up),
 * the report, and the parent's settings from the launcher's restrictions (design 30's two mobile-data
 * switches and the storage limit; unmanaged, the debug feed sets them).
 */
class Store private constructor(context: Context) {
    private val app = context.applicationContext
    val dir = File(app.filesDir, "music")
    val libraryFile = File(dir, "library.json")

    /** Received listings, `<entry id>.json`, until the sync applies them. */
    val inboxDir = File(dir, "inbox")
    val coversDir = File(dir, "covers")
    val ownDir = File(app.noBackupFilesDir, "own")
    val reportFile = File(dir, "report.json")

    /** The pictures the player showed last (design 27, QA L4): kept by the prune a while. */
    private val recentArtFile = File(dir, "recent_art.txt")

    /** The ≤ 300 px copies of covers for the media session (PlaybackService), `<hash>.jpg`. */
    val smallArtDir = File(app.cacheDir, "art")
    val entryErrorsFile = File(dir, "entry_errors.json")
    val db: MusicDao = MusicDb.get(app).dao()
    private val prefs = app.getSharedPreferences("music", Context.MODE_PRIVATE)

    /** The parsed library, read from its file on first use (never in the constructor, which may run
     * on the main thread); null = none (the app is unmanaged or never fed: "Ingen bibliotek"). */
    private val libraryState = MutableStateFlow<Library?>(null)

    @Volatile private var loaded = false

    /** The library now (parsed on first use - call off the main thread). */
    fun library(): Library? {
        if (!loaded) reloadLibrary()
        return libraryState.value
    }

    /** May a stream use mobile data (design 30 §1.1, the streaming switch)? Read live from the
     * launcher's managed config (qa-21-step3-code S2); unmanaged, the debug feed's setting. */
    var streamMobileData: Boolean
        get() = Bridge.config(app).takeIf { it.managed }?.streamMobileData ?: unmanagedSwitch(RestrictionKeys.STREAM_MOBILE_DATA)
        set(value) = prefs.edit().putBoolean(RestrictionKeys.STREAM_MOBILE_DATA, value).apply()

    /** May the download job use mobile data, Storytel books included (design 30 §1.1, the downloads
     * switch)? Read live like [streamMobileData]. */
    var downloadMobileData: Boolean
        get() = Bridge.config(app).takeIf { it.managed }?.downloadMobileData ?: unmanagedSwitch(RestrictionKeys.DOWNLOAD_MOBILE_DATA)
        set(value) = prefs.edit().putBoolean(RestrictionKeys.DOWNLOAD_MOBILE_DATA, value).apply()

    /** The parent's music storage limit in GB, null = none (design 30 §1.8). Read live like [streamMobileData];
     * unmanaged, the debug feed's (0 = none), else 2 GB. */
    var storageLimitGb: Int?
        get() {
            val config = Bridge.config(app)
            if (config.managed) return config.storageLimitGb
            val key = RestrictionKeys.STORAGE_LIMIT_GB
            return storageLimit(if (prefs.contains(key)) mapOf(key to prefs.getInt(key, 0)) else emptyMap())
        }
        set(value) = prefs.edit().putInt(RestrictionKeys.STORAGE_LIMIT_GB, value ?: 0).apply()

    /** An unmanaged phone's switch: the debug feed's, else its old one `mobile_data` (a feed from before design 30). */
    private fun unmanagedSwitch(key: String): Boolean =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else prefs.getBoolean(RestrictionKeys.MOBILE_DATA, false)

    private fun loadLibrary(): Library? = runCatching { libraryFile.readText() }.getOrNull()?.let(LibraryParser::parse)

    /** Re-read the library file (after the bridge or the debug feed wrote it). */
    fun reloadLibrary(): Library? = loadLibrary().also {
        libraryState.value = it
        loaded = true
    }

    fun coverFile(hash: String): File = File(coversDir, hash)

    /** The cover files on the phone (library covers and episode art, design 27 §2.3). */
    fun coverHashes(): Set<String> = coversDir.list().orEmpty().filter(::isArtHash).toSet()

    private val coversVersionState = MutableStateFlow(0L)

    /**
     * Moves whenever a cover file lands or goes (QA M5): a slot that found no file for its hash tries
     * again, and the player refreshes the current item's artwork.
     */
    val coversVersion: StateFlow<Long> = coversVersionState.asStateFlow()

    fun coversChanged() {
        coversVersionState.update { it + 1 } // from the sync, the player and the screens' threads
    }

    /**
     * The prune (`prune_cache`, design 27 §2.3), only on a parsed library: every cover file that is
     * neither in the library's covers nor in the want set's universe goes, with its ≤ 300 px copy
     * (QA L3). True when something went.
     */
    fun pruneCovers(libraryCovers: Set<String>, keep: Set<String>): Boolean {
        val gone = coversToDelete(coverHashes(), libraryCovers, keep)
        gone.forEach { File(coversDir, it).delete() }
        val smallGone = smallArtDir.list().orEmpty().filter { it.endsWith(".jpg") }.map { it.removeSuffix(".jpg") }
            .filter { it !in libraryCovers && it !in keep }
        smallGone.forEach { File(smallArtDir, "$it.jpg").delete() }
        // The player looks again at what it shows (a deleted small copy falls back to the series cover, QA L8).
        val changed = gone.isNotEmpty() || smallGone.isNotEmpty()
        if (changed) coversChanged()
        return changed
    }

    /** The pictures shown last, newest first. */
    @Synchronized
    fun recentArt(): List<String> = runCatching { recentArtFile.readLines() }.getOrDefault(emptyList()).filter(::isArtHash)

    /** The player showed [hashes] (its current item and neighbours). */
    @Synchronized
    fun noteShown(hashes: List<String?>) {
        val before = recentArt()
        val after = recentArt(before, hashes)
        if (after != before) runCatching { writeAtomic(recentArtFile, after.joinToString("\n").toByteArray()) }
    }

    fun ownFile(fileId: Long): File = File(ownDir, fileId.toString())

    /** The SHA-256 checked when [fileId]'s copy arrived (design 29 step 4), beside it; not a copy (its name isn't a number). */
    private fun ownShaFile(fileId: Long): File = File(ownDir, "$fileId.sha256")

    /** The recorded SHA-256 of [fileId]'s copy, or null (none: a copy from before design 29). */
    fun ownSha(fileId: Long): String? = runCatching { ownShaFile(fileId).readText().trim() }.getOrNull()

    fun recordOwnSha(fileId: Long, sha: String) {
        runCatching { writeAtomic(ownShaFile(fileId), sha.toByteArray()) } // without it the copy is hashed again
    }

    /** A copy goes with its record. */
    fun deleteOwnCopy(fileId: Long) {
        ownFile(fileId).delete()
        ownShaFile(fileId).delete()
    }

    /** Own files with a copy on the phone. */
    fun ownCopies(): Set<Long> = ownDir.listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }.toSet()

    /** The library, live (read from its file on first collection - collect off the main thread). */
    fun libraries(): Flow<Library?> = flow {
        library()
        emitAll(libraryState)
    }

    /** Everything the screens and the player read, live, built off the main thread. */
    fun snapshots(): Flow<Snapshot> = combine(
        libraries(),
        combine(db.allItemsFlow(), db.downloadsFlow(), db.listingsFlow()) { items, downloads, listings -> Triple(items, downloads, listings) },
        combine(db.positionsFlow(), db.bookmarksFlow(), db.newsFlow(), db.shuffleFlow()) { p, b, n, s -> Rows(p, b, n, s) },
    ) { lib, (items, downloads, listings), rows -> snapshot(lib, items, downloads, listings, rows) }.flowOn(Dispatchers.Default)

    /** The same, once. */
    suspend fun snapshot(): Snapshot =
        snapshot(library(), db.allItems(), db.downloads(), db.listings(), Rows(db.allPositions(), db.bookmarks(), db.news(), db.allShuffle()))

    private data class Rows(val positions: List<PositionRow>, val bookmarks: List<BookmarkRow>, val news: List<NewsRow>, val shuffle: List<ShuffleRow>)

    private fun snapshot(lib: Library?, items: List<ItemRow>, downloads: List<DownloadRow>, listings: List<ListingRow>, rows: Rows): Snapshot {
        val positions = rows.positions.groupBy { it.entryKey }.mapValues { (_, ps) -> ps.associate { it.itemKey to ItemPosition(it.posMs, it.done) } }
        val shuffle = rows.shuffle.filter { it.on }.map { it.entryKey }.toSet()
        return buildSnapshot(lib, items, downloads, listings, ownCopies(), positions, rows.bookmarks, rows.news, shuffle)
    }

    companion object {
        @Volatile private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context).also { instance = it }
        }

        /** Write [bytes] to [file] atomically (a temp file of its own, renamed over it). */
        fun writeAtomic(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val tmp = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("rename to $file failed")
            }
        }
    }
}
