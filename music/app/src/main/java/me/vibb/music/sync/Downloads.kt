package me.vibb.music.sync

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
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
import me.vibb.music.model.Source
import me.vibb.music.storytel.Mint
import me.vibb.music.storytel.StorytelAccount
import me.vibb.music.storytel.StorytelEndpoints
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

    /**
     * The free space where the cache lives (noBackupFilesDir), for Storytel's 1.5 GB floor (design 26b
     * §3.3): vibb's `_free_bytes` (`disk_usage(...).free`), not what the system could clear for us - the
     * floor keeps the card from filling, it doesn't make room.
     */
    @SuppressLint("UsableSpace")
    fun freeBytes(context: Context): Long = context.noBackupFilesDir.usableSpace

    /**
     * Fetch one item from [url] into the cache: (bytes cached, the HLS variant fetched). Blocking,
     * interruptible. A Storytel book ([url] minted for this attempt, design 26b §4.3) goes under the
     * item's cache key like any progressive item, so a partial resumes with Range whatever the token;
     * its CDN request never follows a redirect and never carries a bearer (MediaCache's clients add no
     * `Authorization`), and only the debug switch's fake lets it reach a private address.
     */
    @OptIn(UnstableApi::class)
    fun fetch(context: Context, w: WantedDownload, url: String): Pair<Long, Int?> {
        val cacheKey = itemCacheKey(w.entry.key, w.item.key)
        val storytel = w.entry.source == Source.STORYTEL
        val publicOnly = if (storytel) StorytelEndpoints.publicDownloads() else publicOnly(w.entry)
        val factory = MediaCache.downloadFactory(context, publicOnly, MediaCache.keyFactory(cacheKey, url, w.item.hls), followRedirects = !storytel)
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
 * The downloads: plan, fetch item by item ([DownloadRun] decides), plan again until nothing new is
 * wanted (a sync during the run adds items). A failure waits per item (logic/Offline.kt `mayRetry`)
 * and never holds the others back; no network means the job ends and a new one waits on its
 * constraints, not on a backoff (qa-21-step2-code M1, M4). Storytel books (design 26b §4.3) go one
 * at a time like everything else; a login failure or the 1.5 GB floor stops them for the run, and a
 * refused login brings a job back when its hour is over (the cooldown sends nothing before).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store.get(applicationContext)
        val dao = store.db
        val run = DownloadRun(WorkerIo(applicationContext, store))
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
                    NewNotifier.update(applicationContext, store.snapshot())
                    Sync.writeReport(applicationContext)
                    return@withContext Result.success()
                }
                run.fetchOne(w)
                // A Nytt item counts once its copy is here, not when the whole job ends (design 29
                // §1.2, QA M2: a series of large books, or a job stopped at 10 minutes, would wait).
                if (dao.news().any { it.entryId == w.entry.id && it.key == w.item.key }) {
                    NewNotifier.update(applicationContext, store.snapshot())
                }
            }
        }
        // A refused Storytel login: back when its cooldown is over (one login an hour at most).
        val back = listOfNotNull(nextRetryAt, run.storytelRefusedUntil).minOrNull()
        back?.let { Downloads.enqueue(applicationContext, store.mobileData, (it - System.currentTimeMillis()).coerceAtLeast(0)) }
        // A new book counts in the Nytt notification once its copy is on the phone (design 26b §3.4):
        // after the download, not at the next sync (qa-26b-step3-code M1).
        NewNotifier.update(applicationContext, store.snapshot())
        Sync.writeReport(applicationContext)
        Result.success()
    }

    /** [DownloadIo] over Room, the cache, Media3's downloaders and the Storytel account. */
    private class WorkerIo(private val context: Context, private val store: Store) : DownloadIo {
        private val dao = store.db

        override suspend fun row(entryId: Long, itemKey: String): DownloadRow? =
            dao.downloads().firstOrNull { it.entryId == entryId && it.itemKey == itemKey }

        override suspend fun saveRow(row: DownloadRow) = dao.upsertDownload(row)

        override fun removeCopy(cacheKey: String, hls: Boolean) = MediaCache.remove(context, cacheKey, hls)

        override suspend fun fetch(w: WantedDownload, url: String): Pair<Long, Int?> = runInterruptible { Downloads.fetch(context, w, url) }

        override fun errorCode(e: Exception): String = MediaCache.errorCode(e)

        override suspend fun recordError(entryId: Long, itemKey: String, code: String) = ItemErrors.record(context, entryId, itemKey, code)

        override suspend fun clearError(entryId: Long, itemKey: String) = dao.deleteItemError(entryId, itemKey)

        override fun nowMs(): Long = System.currentTimeMillis()

        override fun freeBytes(): Long = Downloads.freeBytes(context)

        override fun cachedBytes(cacheKey: String, hls: Boolean): Long = MediaCache.cachedBytes(context, cacheKey, hls)

        /** A download's mint: the asset call 15 s, the whole mint (the login and its lock included) within 60 s. */
        override suspend fun mint(cid: String): Mint =
            StorytelAccount.get(context).mint(cid, MINT_TIMEOUT_MS, SystemClock.elapsedRealtime() + MINT_BUDGET_MS)
    }

    private companion object {
        const val MINT_TIMEOUT_MS = 15_000L
        const val MINT_BUDGET_MS = 60_000L
    }
}
