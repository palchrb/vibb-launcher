package me.vibb.music.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import me.vibb.music.BuildConfig
import me.vibb.music.data.ItemErrorRow
import me.vibb.music.data.ItemRow
import me.vibb.music.data.ListingRow
import me.vibb.music.data.NewsRow
import me.vibb.music.data.SeenRow
import me.vibb.music.data.Store
import me.vibb.music.logic.EntryError
import me.vibb.music.logic.ItemError
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.addItemError
import me.vibb.music.logic.addToTray
import me.vibb.music.logic.downloadWaiting
import me.vibb.music.logic.markNewSeen
import me.vibb.music.logic.pruneTray
import me.vibb.music.logic.stateReport
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.JSON
import me.vibb.music.model.Listing
import me.vibb.music.model.ListingParser
import me.vibb.music.model.Source
import java.util.concurrent.TimeUnit

/*
 * The sync (design 21 §4.5 as changed by 21b §6): bridge (step 3; step 2's debug feed has already
 * written the library and listings) -> listings into Room, NY -> prune -> downloads -> the Nytt
 * notification -> the report. Unique WorkManager work: on the nudge (15 s settle, as vibb's
 * `SYNC_SETTLE_S`), at start, and a 6 h backstop. It never fetches a listing from a source.
 */
object Sync {
    private const val WORK = "music-sync"
    private const val BACKSTOP = "music-backstop"
    private const val SETTLE_S = 15L

    /** Below this much free space the report says downloads wait for storage (WorkManager's own
     * storage-not-low constraint holds them back). */
    private const val LOW_STORAGE_BYTES = 500L * 1024 * 1024

    /** The bridge's nudge: sync after the settle (a burst of nudges is one sync). */
    fun nudge(context: Context, settleS: Long = SETTLE_S) = enqueue(context, settleS, ExistingWorkPolicy.REPLACE)

    /** At process start: once, if no sync is pending. */
    fun atStart(context: Context) {
        enqueue(context, 0, ExistingWorkPolicy.KEEP)
        val backstop = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BACKSTOP, ExistingPeriodicWorkPolicy.KEEP, backstop)
    }

    private fun enqueue(context: Context, delayS: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(delayS, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        val dao = store.db
        val lib = store.reloadLibrary()
        val entryErrors = readEntryErrors(store).toMutableList()

        // Listings received from the bridge (step 2: the debug feed).
        if (lib != null) {
            for (file in store.inboxDir.listFiles().orEmpty()) {
                val id = file.name.removeSuffix(".json").toLongOrNull()
                val entry = id?.let { lib.entry(it) }
                if (entry != null && entry.source != Source.OWN) {
                    val listing = runCatching { ListingParser.parse(file.readText()) }.getOrNull()
                    entryErrors.removeAll { it.entry == entry.id }
                    if (listing == null || listing.entryId != entry.id) {
                        entryErrors += EntryError(entry.id, "bad_listing")
                    } else {
                        applyListing(store, entry, listing)
                    }
                }
                file.delete()
            }
            // Own entries: new files are Nytt too ("Nytt spor").
            for (entry in lib.entries.filter { it.source == Source.OWN }) {
                markSeen(store, entry.id, lib.filesOf(entry.id).map { it.key })
            }
            // Entries the library no longer names at all take their listing, NY state and errors
            // with them; one it names but can't read keeps everything (qa-21-step2-code H1).
            // Positions and bookmarks are never deleted (vibb keeps its state files; the rows are tiny).
            val ids = lib.entryIds
            dao.listings().filter { it.entryId !in ids }.forEach { dao.deleteItems(it.entryId); dao.deleteListing(it.entryId) }
            dao.seenEntries().filter { it !in ids }.forEach { dao.deleteSeen(it) }
            val tray = dao.news().map { NewsItem(it.entryId, it.key, it.seq) }
            val pruned = pruneTray(tray, ids)
            if (pruned != tray) dao.replaceNews(pruned.map { NewsRow(it.entryId, it.key, it.seq) })
            dao.itemErrors().map { it.entryId }.distinct().filter { it !in ids }.forEach { dao.deleteItemErrors(it) }
            entryErrors.removeAll { it.entry !in ids }
            // Own files' copies of files the library no longer names (a `missing` one is still named, QA #3).
            store.ownCopies().filter { it !in lib.fileIds }.forEach { store.ownFile(it).delete() }
        }
        writeEntryErrors(store, entryErrors)

        // Keep/prune (only on parsed listings), then the downloads.
        val plan = downloadPlan(store.snapshot(), dao.downloads(), System.currentTimeMillis())
        for (row in plan.prune) {
            MediaCache.remove(context, row.cacheKey, row.hls)
            dao.deleteDownload(row.entryId, row.itemKey)
        }
        if (plan.wanted.isNotEmpty()) {
            Downloads.enqueue(context, store.mobileData)
        } else {
            plan.nextRetryAt?.let { Downloads.enqueue(context, store.mobileData, (it - System.currentTimeMillis()).coerceAtLeast(0)) }
        }
        NewNotifier.update(context, store.snapshot())
        writeReport(context)
    }

    /** Apply one listing: items replaced, NY marked, errors against another version dropped. A
     * failed item whose url changed is retried at once by the plan (`mayRetry`). */
    private suspend fun applyListing(store: Store, entry: Entry, listing: Listing) {
        val dao = store.db
        dao.replaceListing(
            ListingRow(entry.id, listing.version),
            listing.items.mapIndexed { i, it -> ItemRow(entry.id, it.key, i, it.title, it.url, it.hls, it.durationMs) },
        )
        // Only items that can play are news (a withdrawn one without a copy is hidden).
        markSeen(store, entry.id, listing.items.map { it.key }, listing.items.filter { it.url != null }.map { it.key }.toSet())
        dao.deleteStaleItemErrors(entry.id, listing.version)
    }

    /** `_mark_new_seen` for one entry; new keys in [eligible] (default all) join the Nytt tray. */
    private suspend fun markSeen(store: Store, entryId: Long, listed: List<String>, eligible: Set<String>? = null) {
        val dao = store.db
        val before = dao.seen(entryId).takeIf { it.isNotEmpty() }?.toSet()
        val (fresh, after) = markNewSeen(before, listed)
        if (after != null && after != before) dao.insertSeen((after - before.orEmpty()).map { SeenRow(entryId, it) })
        val news = fresh.filter { eligible == null || it in eligible }
        if (news.isNotEmpty()) {
            val tray = dao.news().map { NewsItem(it.entryId, it.key, it.seq) }
            dao.replaceNews(addToTray(tray, entryId, news).map { NewsRow(it.entryId, it.key, it.seq) })
        }
    }

    @Serializable
    private data class EntryErrorJson(val entry: Long, val error: String)

    private fun readEntryErrors(store: Store): List<EntryError> = runCatching {
        JSON.decodeFromString(ListSerializer(EntryErrorJson.serializer()), store.entryErrorsFile.readText()).map { EntryError(it.entry, it.error) }
    }.getOrDefault(emptyList())

    private fun writeEntryErrors(store: Store, errors: List<EntryError>) {
        val text = JSON.encodeToString(ListSerializer(EntryErrorJson.serializer()), errors.map { EntryErrorJson(it.entry, it.error) })
        Store.writeAtomic(store.entryErrorsFile, text.toByteArray())
    }

    /** The report for step 3's `reportState` (21 §1.3, 21b §4.3), kept in filesDir/music/report.json. */
    suspend fun writeReport(context: Context) = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        val snap = store.snapshot()
        val plan = downloadPlan(snap, store.db.downloads(), System.currentTimeMillis())
        val storageLow = store.ownDir.parentFile!!.usableSpace < LOW_STORAGE_BYTES
        val waiting = downloadWaiting(netCaps(context), store.mobileData, storageLow)
        val states = plan.states.map { if (it.have < it.want) it.copy(waiting = waiting) else it }
        val errors = store.db.itemErrors().map { ItemError(it.entryId, it.version, it.itemKey, it.error) }
        val ownBytes = store.ownDir.listFiles().orEmpty().sumOf { it.length() }
        val report = stateReport(
            BuildConfig.VERSION_CODE.toLong(),
            snap.library?.version,
            MediaCache.totalBytes(context) + ownBytes,
            readEntryErrors(store),
            errors,
            states,
        )
        reportLock.withLock { Store.writeAtomic(store.reportFile, report.toString().toByteArray()) }
    }

    /** The sync and the download job both write the report. */
    private val reportLock = Mutex()
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Sync.run(applicationContext)
        return Result.success()
    }
}

/** `item_errors` in Room: the latest failure of a download or stream of a listed url, per item. */
object ItemErrors {
    suspend fun record(context: Context, entryId: Long, itemKey: String, code: String) {
        val dao = Store.get(context).db
        val version = dao.listings().firstOrNull { it.entryId == entryId }?.version ?: return
        val log = dao.itemErrors().map { ItemError(it.entryId, it.version, it.itemKey, it.error) }
        val next = addItemError(log, ItemError(entryId, version, itemKey, code))
        val keep = next.map { it.entry to it.item }.toSet()
        log.filter { (it.entry to it.item) !in keep }.forEach { dao.deleteItemError(it.entry, it.item) }
        dao.upsertItemError(ItemErrorRow(entryId, itemKey, version, code, System.currentTimeMillis()))
    }
}
