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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

    /** Run the downloads when the network allows; [now] replaces a waiting job (a url changed). */
    fun enqueue(context: Context, mobileData: Boolean, now: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (mobileData) NetworkType.NOT_ROAMING else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Fetch one item into the cache: (bytes cached, the HLS variant fetched). Blocking, interruptible. */
    @OptIn(UnstableApi::class)
    fun fetch(context: Context, w: WantedDownload): Pair<Long, Int?> {
        val url = requireNotNull(w.item.url)
        val cacheKey = itemCacheKey(w.entry.key, w.item.key)
        val factory = MediaCache.downloadFactory(context, publicOnly(w.entry))
        if (!w.item.hls) {
            ProgressiveDownloader(MediaItem.Builder().setUri(url).setCustomCacheKey(cacheKey).build(), factory).download(null)
            return MediaCache.cachedBytes(context, cacheKey, false) to null
        }
        MediaCache.registerHls(cacheKey, url)
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

/** An HTTP status that means the url itself is wrong: no retry until the server sends another. */
fun permanentFailure(code: String): Boolean {
    val status = code.removePrefix("http_").toIntOrNull() ?: return false
    return status in 400..499 && status != 408 && status != 429
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store.get(applicationContext)
        val dao = store.db
        val plan = downloadPlan(store.snapshot(), dao.downloads())
        var retry = false
        for (w in plan.wanted) {
            if (downloadWaiting(netCaps(applicationContext), store.mobileData, false) != null) return@withContext Result.retry()
            val url = w.item.url ?: continue
            val row = DownloadRow(w.entry.id, w.item.key, itemCacheKey(w.entry.key, w.item.key), url, w.item.hls, false, 0, null, null)
            dao.upsertDownload(row)
            try {
                val (bytes, variant) = runInterruptible { Downloads.fetch(applicationContext, w) }
                dao.upsertDownload(row.copy(done = true, bytes = bytes, variant = variant))
                dao.deleteItemError(w.entry.id, w.item.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = MediaCache.errorCode(e)
                ItemErrors.record(applicationContext, w.entry.id, w.item.key, code)
                val permanent = permanentFailure(code)
                dao.upsertDownload(row.copy(failedUrl = if (permanent) url else null))
                if (!permanent) retry = true
            }
        }
        Sync.writeReport(applicationContext)
        if (retry) Result.retry() else Result.success()
    }
}
