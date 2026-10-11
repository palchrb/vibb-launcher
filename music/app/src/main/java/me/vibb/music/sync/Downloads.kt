package me.vibb.music.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Store
import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.downloadWaiting
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.pickVariant
import me.vibb.music.logic.publicOnly
import me.vibb.music.media.MediaCache
import java.util.concurrent.TimeUnit

/*
 * Downloads (design 21 §4.5, QA #1): WorkManager jobs, never a dataSync foreground service from a
 * nudge or a job. One item at a time with Media3's downloaders into the shared cache; an
 * interrupted item continues with Range requests from what is cached. NOT_METERED unless the
 * parent's mobile-data switch is on, storage not low, never while roaming.
 */

/** The default network's capabilities, or null without one. */
fun netCaps(context: Context): NetCaps? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
    return NetCaps(
        internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        physical = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET)
            .any { caps.hasTransport(it) },
    )
}

object Downloads {
    private const val WORK = "music-downloads"

    /**
     * Run the downloads when the network allows, after [delayMs]. A running job gets this one
     * appended (it re-plans until nothing new is wanted, but a sync landing at its very end must not
     * be lost); a waiting one is replaced, so a sync never sits behind a retry delay
     * (qa-21-step2-code M4). Call off the main thread.
     */
    fun enqueue(context: Context, mobileData: Boolean, delayMs: Long = 0) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (mobileData) NetworkType.NOT_ROAMING else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        val wm = WorkManager.getInstance(context)
        val running = runCatching { wm.getWorkInfosForUniqueWork(WORK).get() }.getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.RUNNING }
        wm.enqueueUniqueWork(WORK, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    /** Fetch one item into the cache: (bytes cached, the HLS variant fetched). Blocking, interruptible. */
    @OptIn(UnstableApi::class)
    fun fetch(context: Context, w: WantedDownload): Pair<Long, Int?> {
        val url = requireNotNull(w.item.url)
        val cacheKey = itemCacheKey(w.entry.key, w.item.key)
        val factory = MediaCache.downloadFactory(context, publicOnly(w.entry), MediaCache.keyFactory(cacheKey, url, w.item.hls))
        if (!w.item.hls) {
            ProgressiveDownloader(MediaItem.Builder().setUri(url).setCustomCacheKey(cacheKey).build(), factory).download(null)
            return MediaCache.cachedBytes(context, cacheKey, false) to null
        }
        // One rendition only: a multivariant playlist would otherwise fetch every bitrate.
        val playlist = ParsingLoadable.load(factory.createDataSource(), HlsPlaylistParser(), Uri.parse(url), C.DATA_TYPE_MANIFEST)
        val variant = (playlist as? HlsMultivariantPlaylist)?.variants?.takeIf { it.isNotEmpty() }?.let { v ->
            pickVariant(v.map { it.format.bitrate })
        }
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
        if (variant != null) item.setStreamKeys(listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, variant)))
        HlsDownloader(item.build(), factory).download(null)
        return MediaCache.cachedBytes(context, cacheKey, true) to variant
    }
}

/**
 * The downloads: plan, fetch item by item, plan again until nothing new is wanted (a sync during
 * the run adds items). A failure waits per item (logic/Offline.kt `mayRetry`) and never holds the
 * others back; no network means the job ends and a new one waits on its constraints, not on a
 * backoff (qa-21-step2-code M1, M4).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store.get(applicationContext)
        val dao = store.db
        val tried = mutableSetOf<Pair<Long, String>>()
        var nextRetryAt: Long? = null
        while (true) {
            val plan = downloadPlan(store.snapshot(), dao.downloads(), System.currentTimeMillis())
            nextRetryAt = plan.nextRetryAt
            val todo = plan.wanted.filter { (it.entry.id to it.item.key) !in tried }
            if (todo.isEmpty()) break
            for (w in todo) {
                tried += w.entry.id to w.item.key
                if (downloadWaiting(netCaps(applicationContext), store.mobileData, false) != null) {
                    // The constraints, not a backoff, wait for the network.
                    Downloads.enqueue(applicationContext, store.mobileData)
                    Sync.writeReport(applicationContext)
                    return@withContext Result.success()
                }
                fetchOne(store, w)
            }
        }
        nextRetryAt?.let { Downloads.enqueue(applicationContext, store.mobileData, (it - System.currentTimeMillis()).coerceAtLeast(0)) }
        Sync.writeReport(applicationContext)
        Result.success()
    }

    private suspend fun fetchOne(store: Store, w: WantedDownload) {
        val dao = store.db
        val url = w.item.url ?: return
        val cacheKey = itemCacheKey(w.entry.key, w.item.key)
        val old = dao.downloads().firstOrNull { it.entryId == w.entry.id && it.itemKey == w.item.key }
        // A partial copy from another url may be another file: start over (qa-21-step2-code L7).
        if (old != null && !old.done && old.url != url) MediaCache.remove(applicationContext, old.cacheKey, old.hls)
        val row = DownloadRow(w.entry.id, w.item.key, cacheKey, url, w.item.hls, false, 0, null, old?.failedUrl, old?.failedCode, old?.failedAt, old?.attempts ?: 0)
        dao.upsertDownload(row)
        var code = attempt(w)
        if (code == "http_416") {
            // The file changed under a partial copy: drop it and fetch from the start, once.
            MediaCache.remove(applicationContext, cacheKey, w.item.hls)
            code = attempt(w)
        }
        if (code == null) {
            dao.deleteItemError(w.entry.id, w.item.key)
            return
        }
        ItemErrors.record(applicationContext, w.entry.id, w.item.key, code)
        val attempts = if (row.failedUrl == url && row.failedCode == code) row.attempts + 1 else 1
        dao.upsertDownload(row.copy(failedUrl = url, failedCode = code, failedAt = System.currentTimeMillis(), attempts = attempts))
    }

    /** One attempt: null when the item is on the phone now, else the failure's code. */
    private suspend fun attempt(w: WantedDownload): String? {
        val dao = Store.get(applicationContext).db
        return try {
            val (bytes, variant) = runInterruptible { Downloads.fetch(applicationContext, w) }
            val url = w.item.url!!
            dao.upsertDownload(DownloadRow(w.entry.id, w.item.key, itemCacheKey(w.entry.key, w.item.key), url, w.item.hls, true, bytes, variant, null))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MediaCache.errorCode(e)
        }
    }
}
