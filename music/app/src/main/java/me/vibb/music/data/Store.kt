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
import me.vibb.music.bridge.mobileSwitch
import me.vibb.music.bridge.storageLimit
import me.vibb.music.logic.ItemPosition
import me.vibb.music.logic.ListArt
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.listArtAfter
import me.vibb.music.logic.newestFirst
import me.vibb.music.logic.SpotifyContextTrack
import me.vibb.music.logic.SpotifyContexts
import me.vibb.music.logic.SpotifyOutcome
import me.vibb.music.logic.coversToDelete
import me.vibb.music.logic.isArtHash
import me.vibb.music.logic.recentArt
import me.vibb.music.model.Library
import me.vibb.music.model.LibraryParser
import java.io.File

/**
 * Where the app keeps what it received and what it plays: the library file, received listings
 * (applied into Room by the sync), covers, own files' copies (noBackupFilesDir, never backed up),
 * the report, Spotify's last outcome (design 26c §4.4) and its contexts' live tracks (§5.9), and the parent's settings from the launcher's
 * restrictions (design 30's two mobile-data switches and the storage limit; unmanaged, the debug feed
 * sets them).
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

    /**
     * The phone's last Spotify outcome (design 26c §4.4, QA L8): `{"outcome": "<word>", "at": <wall ms>}`,
     * written by the Spotify renderer through the service only when the word changes ([recordSpotifyOutcome],
     * tmp + rename), read by the report (`Sync.writeReport`, from the file) and every Spotify tile
     * ([spotifyOutcomes]). Never a token, an account or a device id.
     */
    val spotifyStateFile = File(app.noBackupFilesDir, "spotify_state.json")
    private val spotifyOutcomeState = MutableStateFlow<SpotifyOutcome?>(null)

    @Volatile private var spotifyOutcomeLoaded = false

    /**
     * A context-mode Spotify entry's live track (design 26c §5.9, step 6): `{"<entry key>": {"uri", "title"}}`, written
     * by the Spotify renderer through the service at every track change of a context it plays ([recordSpotifyContext],
     * tmp + rename), its row dropped at the context's end; read by the snapshot (the synthetic item's title) and the
     * renderer (where a playlist context goes on). Never a token, an account or a device id.
     */
    val spotifyContextFile = File(app.noBackupFilesDir, "spotify_context.json")
    private val spotifyContextState = MutableStateFlow<Map<String, SpotifyContextTrack>>(emptyMap())

    @Volatile private var spotifyContextLoaded = false

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
            return if (config.managed) config.storageLimitGb else storageLimit(prefs.all)
        }
        set(value) = prefs.edit().putInt(RestrictionKeys.STORAGE_LIMIT_GB, value ?: 0).apply()

    /** An unmanaged phone's switch: the debug feed's, else its old one `mobile_data` (a feed from before design
     * 30) - the managed config's rule, on the feed's prefs, which use the same key names (qa-30-s3a-code L2). */
    private fun unmanagedSwitch(key: String): Boolean = mobileSwitch(prefs.all, key)

    private fun loadLibrary(): Library? = runCatching { libraryFile.readText() }.getOrNull()?.let(LibraryParser::parse)

    /** Re-read the library file (after the bridge or the debug feed wrote it). */
    fun reloadLibrary(): Library? = loadLibrary().also {
        libraryState.value = it
        loaded = true
    }

    fun coverFile(hash: String): File = File(coversDir, hash)

    /** The stored Spotify outcome as the file holds it now (null: none stored, or junk) - call off the main thread. */
    fun readSpotifyOutcome(): SpotifyOutcome? = SpotifyOutcome.decode(runCatching { spotifyStateFile.readText() }.getOrNull())

    @Synchronized
    private fun spotifyOutcome(): SpotifyOutcome? {
        if (!spotifyOutcomeLoaded) {
            spotifyOutcomeState.value = readSpotifyOutcome()
            spotifyOutcomeLoaded = true
        }
        return spotifyOutcomeState.value
    }

    /**
     * The renderer's outcome [word] at [nowMs] (wall clock): written only when the word changes
     * (`SpotifyOutcome.next`; an unknown word never), atomically. Off the main thread (the service's writer). True when
     * the stored word changed: the report then has news for the parent (the service asks for a sync, §6l A F7).
     */
    @Synchronized
    fun recordSpotifyOutcome(word: String, nowMs: Long): Boolean {
        val next = SpotifyOutcome.next(spotifyOutcome(), word, nowMs) ?: return false
        runCatching { writeAtomic(spotifyStateFile, next.encode().toByteArray()) }.onFailure { return false }
        spotifyOutcomeState.value = next
        return true
    }

    /**
     * Every context's live track as stored: once loaded, a lock-free read of the in-memory copy (the renderer's `load` on
     * the main thread never waits for the file or the writer - qa-26c-step6-code L4; the service's snapshot, read on IO
     * before every start and restore, loads it first).
     */
    fun spotifyContexts(): Map<String, SpotifyContextTrack> =
        if (spotifyContextLoaded) spotifyContextState.value else loadSpotifyContexts()

    @Synchronized
    private fun loadSpotifyContexts(): Map<String, SpotifyContextTrack> {
        if (!spotifyContextLoaded) {
            spotifyContextState.value = SpotifyContexts.decode(runCatching { spotifyContextFile.readText() }.getOrNull())
            spotifyContextLoaded = true
        }
        return spotifyContextState.value
    }

    /** Only the rows of [contextKeys] (the parsed library's context-mode entries) stay (L4); off the main thread. */
    @Synchronized
    fun pruneSpotifyContexts(contextKeys: Set<String>) {
        val rows = spotifyContexts()
        val kept = rows.filterKeys { it in contextKeys }
        if (kept.size == rows.size) return
        runCatching { writeAtomic(spotifyContextFile, SpotifyContexts.encode(kept).toByteArray()) }.onFailure { return }
        spotifyContextState.value = kept
    }

    /**
     * [entryKey]'s live track is [track] now (null: the context played out, `clear_state`): written only when it
     * changes (`SpotifyContexts.next`), atomically. Off the main thread (the service's writer).
     */
    @Synchronized
    fun recordSpotifyContext(entryKey: String, track: SpotifyContextTrack?) {
        val next = SpotifyContexts.next(spotifyContexts(), entryKey, track) ?: return
        runCatching { writeAtomic(spotifyContextFile, SpotifyContexts.encode(next).toByteArray()) }.onFailure { return }
        spotifyContextState.value = next
    }

    /** The contexts' live tracks, live (read from the file on first collection - collect off the main thread). */
    fun spotifyContextFlow(): Flow<Map<String, SpotifyContextTrack>> = flow {
        spotifyContexts()
        emitAll(spotifyContextState)
    }

    /** The stored Spotify outcome, live (read from its file on first collection - collect off the main thread). */
    fun spotifyOutcomes(): Flow<SpotifyOutcome?> = flow {
        spotifyOutcome()
        emitAll(spotifyOutcomeState)
    }

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
        combine(
            db.positionsFlow(), db.bookmarksFlow(), db.newsFlow(),
            combine(db.shuffleFlow(), db.flipsFlow(), db.repeatFlow()) { s, f, r -> Triple(s, f, r) },
        ) { p, b, n, (s, f, r) -> Rows(p, b, n, s, f, r) },
        spotifyContextFlow(),
        db.favouritesFlow(),
    ) { lib, (items, downloads, listings), rows, contexts, favourites ->
        snapshot(lib, items, downloads, listings, rows, contexts, favourites)
    }.flowOn(Dispatchers.Default)

    /** The same, once. */
    suspend fun snapshot(): Snapshot = snapshot(
        library(), db.allItems(), db.downloads(), db.listings(), Rows(db.allPositions(), db.bookmarks(), db.news(), db.allShuffle(), db.allFlips(), db.allRepeat()),
        spotifyContexts(), db.allFavourites(),
    )

    private data class Rows(
        val positions: List<PositionRow>,
        val bookmarks: List<BookmarkRow>,
        val news: List<NewsRow>,
        val shuffle: List<ShuffleRow>,
        val flips: List<FlipRow>,
        val repeat: List<RepeatRow>,
    )

    /**
     * The kid's order flip of [entry] (design 39 §3.1), written by the UI side so the button always works: on
     * ([flipped]) the row with the parent's direction now, off no row; the stored queue goes the new way in the same
     * transaction ([MusicDao.setFlipTx]). [newOrder]: the entry's listed keys in the new shown direction. Off the main
     * thread; the screens send `FLIP_ORDER` to a live player after it returns (qa-39-design L9).
     */
    suspend fun setFlip(entry: me.vibb.music.model.Entry, flipped: Boolean, newOrder: List<String>) =
        db.setFlipTx(entry.key, if (flipped) newestFirst(entry) else null, entry.id, newOrder, System.currentTimeMillis())

    /** The episode list's held pictures (design 39 §2.2): read by the want set (`ArtWants.compute`) and the prune. */
    private val listArtFile = File(dir, "list_art.txt")

    @Synchronized
    fun listArt(): ListArt? = ListArt.decode(runCatching { listArtFile.readText() }.getOrNull())

    /** [entryId]'s list showed [window] (`listArtAfter`); written atomically only when it changed. */
    @Synchronized
    fun noteListArt(entryId: Long, window: List<String?>, libraryCovers: Set<String>) {
        val before = listArt()
        val after = listArtAfter(before, entryId, window, libraryCovers)
        if (after != before) runCatching { writeAtomic(listArtFile, after.encode().toByteArray()) }
    }

    private fun snapshot(
        lib: Library?,
        items: List<ItemRow>,
        downloads: List<DownloadRow>,
        listings: List<ListingRow>,
        rows: Rows,
        contexts: Map<String, SpotifyContextTrack>,
        favourites: List<FavouriteRow>,
    ): Snapshot {
        val positions = rows.positions.groupBy { it.entryKey }.mapValues { (_, ps) -> ps.associate { it.itemKey to ItemPosition(it.posMs, it.done) } }
        val shuffle = rows.shuffle.filter { it.on }.map { it.entryKey }.toSet()
        val flips = rows.flips.associate { it.entryKey to it.parentNewestFirst }
        val hearts = favourites.groupBy { it.entryKey }.mapValues { (_, fs) -> fs.map { it.itemKey }.toSet() }
        val repeat = rows.repeat.associate { it.entryKey to Repeat.of(it.mode) }.filterValues { it != Repeat.OFF }
        return buildSnapshot(lib, items, downloads, listings, ownCopies(), positions, rows.bookmarks, rows.news, shuffle, contexts, flips, hearts, repeat)
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
