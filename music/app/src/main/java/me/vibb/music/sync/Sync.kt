package me.vibb.music.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import android.util.Log
import me.vibb.music.BuildConfig
import me.vibb.music.bridge.BridgeSync
import me.vibb.music.data.ItemErrorRow
import me.vibb.music.data.ItemRow
import me.vibb.music.data.ListingRow
import me.vibb.music.data.Store
import me.vibb.music.logic.EntryError
import me.vibb.music.logic.ItemError
import me.vibb.music.logic.Listed
import me.vibb.music.logic.NewsItem
import me.vibb.music.logic.addItemError
import me.vibb.music.logic.downloadWaiting
import me.vibb.music.logic.listedOf
import me.vibb.music.logic.newsApplied
import me.vibb.music.logic.repickWanted
import me.vibb.music.logic.reportWaiting
import me.vibb.music.logic.reportedRooms
import me.vibb.music.logic.stateReport
import me.vibb.music.media.MediaCache
import me.vibb.music.model.Entry
import me.vibb.music.model.JSON
import me.vibb.music.model.Library
import me.vibb.music.model.Listing
import me.vibb.music.model.ListingParser
import me.vibb.music.model.Source
import me.vibb.music.sonos.SonosFiles
import me.vibb.music.sonos.SpeakerCache
import me.vibb.music.storytel.StorytelAccount
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * The sync (design 21 §4.5 as changed by 21b §6): bridge (step 3; step 2's debug feed has already
 * written the library and listings) -> listings into Room, NY -> prune -> downloads -> the Nytt
 * notification -> the report. Unique WorkManager work: on the nudge (15 s settle, as vibb's
 * `SYNC_SETTLE_S`), at start, and a 6 h backstop. It never fetches a listing from a source.
 * Design 29: the known keys before the listings, the news after them, and one run at a time.
 */
object Sync {
    private const val WORK = "music-sync"
    private const val BACKSTOP = "music-backstop"
    private const val SETTLE_S = 15L

    /** Below this much free space the report says downloads wait for storage (WorkManager's own
     * storage-not-low constraint holds them back). */
    private const val LOW_STORAGE_BYTES = 500L * 1024 * 1024

    /**
     * The bridge's nudge: sync after the settle. A nudge never cancels a running sync - it gets
     * one more after it (qa-21-step3-code L6) - and one waiting already covers a burst. Off the main
     * thread (it asks WorkManager what runs).
     */
    fun nudge(context: Context, settleS: Long = SETTLE_S) {
        val app = context.applicationContext
        nudger.execute {
            val states = runCatching { WorkManager.getInstance(app).getWorkInfosForUniqueWork(WORK).get() }.getOrDefault(emptyList()).map { it.state }
            val policy = when {
                states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
                states.any { it == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> ExistingWorkPolicy.REPLACE
            }
            enqueue(app, settleS, policy)
        }
    }

    private val nudger = java.util.concurrent.Executors.newSingleThreadExecutor()

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

    /**
     * One sync at a time (design 29 §1.6, QA M3): `music-sync` (the nudge, the start) and
     * `music-backstop` are different unique works running the same [SyncWorker], so WorkManager can
     * run both at once in the app's one process.
     */
    private val runLock = Mutex()

    suspend fun run(context: Context) = withContext(Dispatchers.IO) { runLock.withLock { runLocked(context) } }

    private suspend fun runLocked(context: Context) {
        // The bridge first (21 §4.5 as 21b §6 has it): what the launcher holds lands in the inbox.
        try {
            BridgeSync.pull(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Sync", "The bridge part failed", e)
        }
        val store = Store.get(context)
        val dao = store.db
        val lib = store.reloadLibrary()
        val entryErrors = readEntryErrors(store).toMutableList()

        if (lib != null) {
            // Design 29 phase A, before any listing is applied: the known keys and the baseline, and
            // an id that now carries another key (a restore, a new server) reset like a removed entry.
            val reset = dao.newsBeforeTx(lib.keyById)
            if (reset.isNotEmpty()) {
                Log.i("Sync", "Entry ids that now carry another key, reset: ${reset.sorted()}")
                entryErrors.removeAll { it.entry in reset }
            }
            // Listings received from the bridge (step 2: the debug feed). A file read is deleted after
            // phase B, so a crash in between applies it again (the seen rows make that a no-op), and
            // only while it still holds what was read (the debug feed writes from a receiver).
            val read = mutableListOf<Pair<File, String>>()
            val appliedIds = mutableListOf<Long>()
            for (file in store.inboxDir.listFiles().orEmpty()) {
                val id = file.name.removeSuffix(".json").toLongOrNull()
                val entry = id?.let { lib.entry(it) }
                if (entry == null || entry.source == Source.OWN) {
                    file.delete()
                    continue
                }
                val text = runCatching { file.readText() }.getOrNull()
                val listing = text?.let { runCatching { ListingParser.parse(it) }.getOrNull() }
                entryErrors.removeAll { it.entry == entry.id }
                if (listing == null || listing.entryId != entry.id) {
                    entryErrors += EntryError(entry.id, "bad_listing")
                } else {
                    applyListing(store, entry, listing)
                    appliedIds += entry.id
                }
                if (text != null) read += file to text else file.delete()
            }
            // Design 29 phase B: NY and Nytt for this run's listings and every own entry ("Nytt spor"),
            // a new entry's one item and the re-pick, in one transaction.
            val current = newsListed(lib, store)
            val applied = newsApplied(appliedIds, lib.entries, current)
            dao.newsAfterTx(applied, current, System.currentTimeMillis())
            for ((file, text) in read) if (runCatching { file.readText() }.getOrNull() == text) file.delete()
            // Entries the library no longer names at all take their listing, NY state and errors
            // with them; one it names but can't read keeps everything (qa-21-step2-code H1).
            // Positions and bookmarks are never deleted (vibb keeps its state files; the rows are
            // tiny), and neither are design 29's known keys (grow-only).
            val ids = lib.entryIds
            dao.listings().filter { it.entryId !in ids }.forEach { dao.deleteItems(it.entryId); dao.deleteListing(it.entryId) }
            dao.seenEntries().filter { it !in ids }.forEach { dao.deleteSeen(it) }
            dao.pruneNews(ids)
            dao.itemErrors().map { it.entryId }.distinct().filter { it !in ids }.forEach { dao.deleteItemErrors(it) }
            entryErrors.removeAll { it.entry !in ids }
            // Own files' copies of files the library no longer names (a `missing` one is still named, QA #3).
            store.ownCopies().filter { it !in lib.fileIds }.forEach { store.deleteOwnCopy(it) }
        }
        writeEntryErrors(store, entryErrors)
        // Covers and episode pictures (design 27 §2.3, vibb `prune_cache`): only on a parsed library,
        // after the listings are applied; a kept episode's picture stays as long as its audio.
        val want = ArtWants.compute(context)
        if (lib != null) store.pruneCovers(lib.covers, want.keep)

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
        try {
            BridgeSync.wantArt(context, want)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Sync", "The want set didn't reach the launcher", e)
        }
    }

    /** Apply one listing: items replaced, errors against another version dropped (NY is phase B's).
     * A failed item whose url changed is retried at once by the plan (`mayRetry`). */
    private suspend fun applyListing(store: Store, entry: Entry, listing: Listing) {
        val dao = store.db
        dao.replaceListing(
            ListingRow(entry.id, listing.version),
            listing.items.mapIndexed { i, it -> ItemRow(entry.id, it.key, i, it.title, it.url, it.hls, it.durationMs, it.art) },
        )
        dao.deleteStaleItemErrors(entry.id, listing.version)
    }

    /**
     * Every library entry with a parsed listing, and every own entry, as the news rules see it
     * (design 29 §4): its items oldest first, what may be news (only items that can play: a url, or
     * a Storytel book though listed without one, design 26b §3.4), and what can't play (a `no_audio`
     * book, a `missing` own file without a copy).
     */
    private suspend fun newsListed(lib: Library, store: Store): Map<Long, Listed> {
        val snap = store.snapshot()
        val errors = store.db.itemErrors().groupBy { it.entryId }.mapValues { (_, es) -> es.associate { it.itemKey to it.error } }
        return lib.entries.filter { it.source == Source.OWN || it.id in snap.listingVersions }.associate { e ->
            val missing = if (e.source == Source.OWN) lib.filesOf(e.id).filter { it.missing }.map { it.key }.toSet() else emptySet()
            e.id to listedOf(e, snap.listed[e.id].orEmpty(), snap.onPhone[e.id].orEmpty(), missing, errors[e.id].orEmpty())
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
        // The phone's Storytel login: none, refused (the cooldown runs) or ok (design 26b §4.5).
        val storytel = StorytelAccount.get(context).state()
        val storytelEntries = snap.library?.entries.orEmpty().filter { it.source == Source.STORYTEL }.map { it.id }.toSet()
        val states = reportWaiting(plan.states, storytelEntries, waiting, storytel, Downloads.freeBytes(context))
        val errors = store.db.itemErrors().map { ItemError(it.entryId, it.version, it.itemKey, it.error) }
        // Own files and the cover files (episode pictures included, design 27 §2.3).
        val ownBytes = store.ownDir.listFiles().orEmpty().sumOf { it.length() } + store.coversDir.listFiles().orEmpty().sumOf { it.length() }
        val report = stateReport(
            BuildConfig.VERSION_CODE.toLong(),
            snap.library?.version,
            MediaCache.totalBytes(context) + ownBytes,
            readEntryErrors(store),
            errors,
            states,
            storytel,
            // the rooms the speaker cache knows, for the parent's room scope (design 26a §5.5)
            reportedRooms(SpeakerCache(SonosFiles.cache(context)).load().players.mapValues { it.value.name }),
        )
        val text = report.toString()
        reportLock.withLock { Store.writeAtomic(store.reportFile, text.toByteArray()) }
        // To the launcher, for the status report (21 §2 `reportState`).
        try {
            BridgeSync.push(context, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Sync", "The report didn't reach the launcher", e)
        }
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
        // A new entry's item that can't play: its re-pick runs at a sync, so one is asked for now (design 29 §1.1).
        if (repickWanted(dao.news().map { NewsItem(it.entryId, it.key, it.seq) }, entryId, itemKey, code)) Sync.nudge(context)
    }
}
