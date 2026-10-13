package me.vibb.music.sync

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Store
import me.vibb.music.logic.Extra
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.extrasToEvict
import me.vibb.music.logic.hlsResourceKey
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.itemOfResource
import me.vibb.music.logic.orphanResources
import me.vibb.music.logic.pickVariant
import me.vibb.music.logic.staleThrough
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.PlayerHub

/*
 * What a stream wrote to the cache as it played (design 30 §1.4-§1.8; ours, not vibb's - vibb caches
 * no stream): settled into a copy once complete, kept as a played extra within the parent's storage
 * limit and the free floor, its stale or orphaned bytes dropped. The rules are pure (logic/Offline.kt,
 * OfflineTest); this is their glue. Nothing here waits: the settle is a span check (never the
 * downloader, never a cache lock - qa-30-design H1), and every removal skips what the player holds,
 * read again right before it removes (`PlayerHub.heldLocal`, L4). One small mutex of its own, held for
 * one row at a time, so a settle never meets a removal of the same row (a done row without its bytes);
 * nothing blocking runs under it, and the sync runs [catchUp] before its `runLock`.
 */
object CacheThrough {
    private val lock = Mutex()

    /**
     * [ref] left the player's held set (the service, on IO - never its writer): its row becomes a done
     * copy when every byte is cached. The done row, else null (incomplete, held, or no row). A played row
     * that stays incomplete asks for the download job (qa-30-s3b-code L4): while it was held the plan left it out
     * (busy), so a keep-set item let go half streamed is completed now, not at the next sync; the job's own
     * plan decides whether it is wanted at all.
     */
    suspend fun settle(context: Context, ref: ItemRef): DownloadRow? = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        var incomplete = false
        val done = lock.withLock {
            val row = store.db.download(ref.entryId, ref.itemKey) ?: return@withLock null
            settleLocked(context, store, row).also { incomplete = it == null && row.playedAt != null && ref !in PlayerHub.heldLocal }
        }
        if (incomplete) Downloads.enqueue(context, store.downloadMobileData)
        done
    }

    /**
     * At every sync, before `Sync.runLock` (§1.5): every played row that isn't done or held is settled
     * when complete. A partial extra is checked again at every sync - a span check, cheap. No download job
     * from here (qa-30-s3b-code L3): the sync's own plan, built right after, enqueues one when a keep-set
     * item is still wanted; a partial extra is never fetched, so a job for it would find nothing to do.
     */
    suspend fun catchUp(context: Context) = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        val rows = store.db.downloads().filter { !it.done && it.playedAt != null && ItemRef(it.entryId, it.itemKey) !in PlayerHub.heldLocal }
        // One row at a time under the mutex: a new queue's rows (queueRows) never wait for the whole pass.
        for (row in rows) {
            lock.withLock { store.db.download(row.entryId, row.itemKey)?.let { settleLocked(context, store, it) } }
        }
    }

    private suspend fun settleLocked(context: Context, store: Store, row: DownloadRow): DownloadRow? {
        if (row.done) return row
        if (ItemRef(row.entryId, row.itemKey) in PlayerHub.heldLocal) return null
        val (bytes, variant) = complete(context, row) ?: return null
        val done = row.copy(done = true, bytes = bytes, variant = variant, failedUrl = null, failedCode = null, failedAt = null, attempts = 0)
        store.db.upsertDownload(done)
        return done
    }

    /**
     * Is every byte of [row]'s item cached (§1.5)? Its bytes and HLS variant, else null. Non-blocking:
     * - progressive: the stored content length is known and `isCached(key, 0, length)`;
     * - HLS: the cached playlist read through a copy-only source (no upstream, no sink, no
     *   `FLAG_BLOCK_ON_CACHE`), the download's variant ([pickVariant]) and its media playlist, then every
     *   segment, initialization segment and key file cached, its length from the byte range or the key's
     *   stored length. An unknown length, a live playlist or separate audio renditions are not complete.
     */
    @OptIn(UnstableApi::class)
    private fun complete(context: Context, row: DownloadRow): Pair<Long, Int?>? {
        val cache = MediaCache.get(context)
        fun cached(key: String, offset: Long, length: Long): Boolean {
            val len = if (length != C.LENGTH_UNSET.toLong()) length else MediaCache.storedLength(cache, key)?.minus(offset) ?: return false
            return len > 0 && cache.isCached(key, offset, len)
        }
        if (!row.hls) {
            if (!cached(row.cacheKey, 0, C.LENGTH_UNSET.toLong())) return null
            return MediaCache.cachedBytes(context, row.cacheKey, false) to null
        }
        val copy = MediaCache.copyOnlyFactory(context, MediaCache.keyFactory(row.cacheKey, row.url, true))
        fun playlist(url: Uri): HlsPlaylist? =
            runCatching { ParsingLoadable.load(copy.createDataSource(), HlsPlaylistParser(), url, C.DATA_TYPE_MANIFEST) }.getOrNull()
        var variant: Int? = null
        val media = when (val first = playlist(Uri.parse(row.url))) {
            is HlsMediaPlaylist -> first
            is HlsMultivariantPlaylist -> {
                if (first.variants.isEmpty() || first.audios.any { it.url != null }) return null
                val v = pickVariant(first.variants.map { it.format.bitrate })
                variant = v
                playlist(first.variants[v].url) as? HlsMediaPlaylist
            }
            else -> null
        } ?: return null
        if (!media.hasEndTag || media.segments.isEmpty()) return null
        val parts = media.segments.flatMap { listOfNotNull(it.initializationSegment, it) }.distinct()
        for (s in parts) {
            val key = hlsResourceKey(row.cacheKey, row.url, UriUtil.resolve(media.baseUri, s.url))
            if (!cached(key, s.byteRangeOffset, s.byteRangeLength)) return null
            val keyFile = s.fullSegmentEncryptionKeyUri ?: continue
            if (!cached(hlsResourceKey(row.cacheKey, row.url, UriUtil.resolve(media.baseUri, keyFile)), 0, C.LENGTH_UNSET.toLong())) return null
        }
        return MediaCache.cachedBytes(context, row.cacheKey, true) to variant
    }

    /**
     * Delete [rows] and their bytes (the sync's prune, eviction): each unless the player holds it,
     * read again right before it goes (L4).
     */
    suspend fun remove(context: Context, rows: List<DownloadRow>) = withContext(Dispatchers.IO) {
        val dao = Store.get(context).db
        for (row in rows) {
            lock.withLock {
                if (ItemRef(row.entryId, row.itemKey) in PlayerHub.heldLocal) return@withLock
                MediaCache.remove(context, row.cacheKey, row.hls)
                dao.deleteDownload(row.entryId, row.itemKey)
            }
        }
    }

    /**
     * The played extras over the parent's limit ([limitBytes], null = none) or under the free floor
     * ([floorBytes]) go, least recently played first ([extrasToEvict]); the held ones never (§1.8). The
     * sync with its prune, the download job each round before it fetches. Returns the rows evicted.
     */
    suspend fun evict(context: Context, plan: DownloadPlan, limitBytes: Long?, floorBytes: Long): List<DownloadRow> = withContext(Dispatchers.IO) {
        if (plan.extras.isEmpty()) return@withContext emptyList()
        val bytes = MediaCache.bytesByItem(context, plan.extras.map { it.cacheKey }.toSet())
        val extras = plan.extras.map { Extra(it.entryId, it.itemKey, it.cacheKey, bytes[it.cacheKey] ?: 0L, it.playedAt ?: 0L) }
        val gone = extrasToEvict(extras, PlayerHub.heldLocal, MediaCache.totalBytes(context), limitBytes, MediaCache.freeBytes(context), floorBytes)
            .map { ItemRef(it.entryId, it.itemKey) }.toSet()
        val rows = plan.extras.filter { ItemRef(it.entryId, it.itemKey) in gone }
        remove(context, rows)
        rows
    }

    /** The played extras' bytes now (the report's `extra_bytes`, §1.10: after this run's eviction). */
    fun extraBytes(context: Context, plan: DownloadPlan): Long {
        if (plan.extras.isEmpty()) return 0
        val bytes = MediaCache.bytesByItem(context, plan.extras.map { it.cacheKey }.toSet())
        return plan.extras.sumOf { bytes[it.cacheKey] ?: 0L }
    }

    /**
     * The orphan sweep (§1.8, M1's backstop), at every sync: cache resources no row owns and the player
     * doesn't hold ([orphanResources]). The cache's keys are read before the rows: the sink makes its
     * row before it writes a byte, so a key with committed bytes seen first has its row by the time the rows
     * are read. A key can appear earlier - `SimpleCache` adds it at the hole lock, before the upstream open and
     * the sink's row (qa-30-s3b-code N4) - but such a key is an item the player holds, and has no committed
     * span: `removeResource` of a locked, empty content removes nothing. No row means nothing to settle, and
     * only a held item gets a new one: no mutex, the held set read before each removal.
     */
    suspend fun sweepOrphans(context: Context) = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        val keys = MediaCache.resourceKeys(context)
        val rows = store.db.downloads()
        val orphans = orphanResources(keys, rows.map { it.cacheKey }.toSet(), heldCacheKeys(store, rows))
        for (key in orphans) {
            if (itemOfResource(key, heldCacheKeys(store, rows)) != null) continue
            MediaCache.removeResource(context, key)
        }
    }

    /** The cache keys of the items the player holds now: their rows', and the library's for those without one. */
    private fun heldCacheKeys(store: Store, rows: List<DownloadRow>): Set<String> {
        val lib = store.library()
        val keys = mutableSetOf<String>()
        for (ref in PlayerHub.heldLocal) {
            rows.firstOrNull { it.entryId == ref.entryId && it.itemKey == ref.itemKey }?.let { keys += it.cacheKey }
            lib?.entry(ref.entryId)?.let { keys += itemCacheKey(it.key, ref.itemKey) }
        }
        return keys
    }

    /** A new queue's rows ([rows], read after its claim) and the keys of its [stale] items, which play uncached. */
    class QueueRows(val rows: List<DownloadRow>, val stale: Set<String>)

    /**
     * The rows of [entry] for a new queue of [items] on the phone, read after the service claimed its start
     * item and the next (L4), and its stale items (§1.6, [staleThrough]: cached bytes with no row, or a row
     * from another source): each one's row goes, and its bytes too unless the player held it before this
     * queue ([before]) - those go with the orphan sweep once let go.
     */
    suspend fun queueRows(context: Context, entry: Entry, items: List<PlayItem>, before: Set<ItemRef>): QueueRows = withContext(Dispatchers.IO) {
        val dao = Store.get(context).db
        lock.withLock {
            val rows = dao.downloads().filter { it.entryId == entry.id }
            val rowOf = rows.associateBy { it.itemKey }
            val open = items.filter { it.url != null && rowOf[it.key]?.done != true }
            if (open.isEmpty()) return@withLock QueueRows(rows, emptySet())
            val bytes = MediaCache.bytesByItem(context, open.map { itemCacheKey(entry.key, it.key) }.toSet())
            val stale = open.filter { staleThrough(false, rowOf[it.key]?.url, it.url, bytes[itemCacheKey(entry.key, it.key)] ?: 0L) }
            for (item in stale) {
                val row = rowOf[item.key]
                if (row != null) dao.deleteDownload(entry.id, item.key)
                if (ItemRef(entry.id, item.key) !in before) MediaCache.remove(context, itemCacheKey(entry.key, item.key), row?.hls ?: item.hls)
            }
            val keys = stale.map { it.key }.toSet()
            QueueRows(rows.filter { it.itemKey !in keys }, keys)
        }
    }

    /**
     * The download job drops a partial copy (another source, a changed one: `DownloadRun`): unless the
     * player holds that item now - an HLS stream's, never fetched while busy otherwise (§1.6).
     */
    suspend fun removeCopy(context: Context, cacheKey: String, hls: Boolean) = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        lock.withLock {
            if (cacheKey in heldCacheKeys(store, store.db.downloads())) return@withLock
            MediaCache.remove(context, cacheKey, hls)
        }
    }
}
