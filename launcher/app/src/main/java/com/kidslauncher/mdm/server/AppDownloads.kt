package com.kidslauncher.mdm.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.storage.StorageManager
import android.util.Log
import com.kidslauncher.mdm.notifyAppInstallResult
import com.kidslauncher.mdm.notifyAppInstalling
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.dto.AppDownloadEntry
import com.kidslauncher.mdm.server.dto.AppDownloadsReport
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import retrofit2.Call
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val LOG_TAG = "AppDownloads"
private const val DIR = "app_downloads"
private const val PREFS = "app_downloads"
private const val KEY_RECORDS = "records"
private const val RELEASE_TAG_HEADER = "X-Release-Tag"

/** The download wake lock times out by itself; progress renews it. */
private const val WAKE_LOCK_MS = 5 * 60_000L
private const val WAKE_LOCK_RENEW_MS = 60_000L
/** How often a running download checks that its record is still the one wanted. */
private const val RECORD_CHECK_MS = 2_000L
/** After a failed attempt (stall, server error) the runner tries again after these delays, then
 * waits for the next trigger (a sync, a network change, process start). */
private val RETRY_DELAYS_MS = listOf(30_000L, 60_000L, 120_000L)
/** At most this many attempts per run - a guard against a restart loop. */
private const val MAX_STEPS_PER_RUN = 20
/** A release that changed on the server under a download asks for the list again at most this
 * often. */
private const val RELIST_MIN_GAP_MS = 10 * 60_000L

/**
 * The download records (design 13 §6): CE prefs `app_downloads`, every change written with
 * `commit()`. Two threads use it (the sync, the runner) - every access is synchronized.
 */
object AppDownloadStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<DownloadRecord> {
        val raw = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            ServerJson.decodeFromString<List<DownloadRecord>>(raw)
        } catch (e: Exception) {
            // Unreadable: no records - the files are swept and the next sync enqueues again.
            Log.w(LOG_TAG, "Download records don't decode", e)
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, records: List<DownloadRecord>): Boolean {
        val ok = prefs(context).edit().putString(KEY_RECORDS, ServerJson.encodeToString(records)).commit()
        if (!ok) Log.w(LOG_TAG, "Couldn't write the download records")
        return ok
    }

    @Synchronized
    fun get(context: Context, appId: Long): DownloadRecord? = load(context).firstOrNull { it.appId == appId }

    /** Replaces [record]'s app's record if it is still the same release; returns what is stored. */
    @Synchronized
    fun update(context: Context, record: DownloadRecord): DownloadRecord {
        val records = load(context)
        if (records.none { it.appId == record.appId && it.tag == record.tag }) return record
        save(context, records.map { if (it.appId == record.appId) record else it })
        return record
    }

    /** Reads, changes and writes the records in one step under the lock (qa-13-code #3): a runner
     * step can't land between the read and the write and be overwritten. */
    @Synchronized
    fun mutate(context: Context, transform: (List<DownloadRecord>) -> List<DownloadRecord>): List<DownloadRecord> {
        val before = load(context)
        val after = transform(before)
        if (after != before) save(context, after)
        return after
    }

    /** Drops [appId]'s record ([tag] `null` = whichever release it is for). */
    @Synchronized
    fun remove(context: Context, appId: Long, tag: String? = null) {
        val records = load(context)
        val kept = records.filterNot { it.appId == appId && (tag == null || it.tag == tag) }
        if (kept.size != records.size) save(context, kept)
    }
}

/**
 * Catalog downloads outside the sync (design 13 §4, with the QA review): one download at a time on
 * its own coroutine, started through the anchor service (FGS procstate keeps the network in Doze),
 * with its own `kidslauncher:download` wake lock (5 min, renewed on progress, released while
 * waiting) and no overall timeout - OkHttp's 10 s read timeout makes a stall a pause. Each run
 * takes the downloads whose gate ([downloadGate]) says GO, catalog apps first; the rest wait for
 * a trigger: every sync, process start, and a default-network callback while something waits. A
 * network that stops qualifying cancels the running request ([Call.cancel]); the partial file stays
 * and resumes with `Range` + `If-Match`. A finished file is checked against the server's SHA-256,
 * then a catalog app is installed here ([AppInstaller.installSilently]) and our own update is
 * handed to the night window ([SelfUpdate], committed as the sync's last step).
 */
object AppDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Held by a catalog install from `createSession` to `commit()` and by our own update's commit
     * (QA #3): the self-update waits for a session copy in progress, and the runner opens no
     * session once [SelfUpdate.committedInThisProcess] is set (this process is about to be
     * replaced; the download is kept for the next one).
     */
    val installMutex = Mutex()

    /** The parent's switch as the last sync enforced it (`null` = no sync yet in this process:
     * read from the cached policy). */
    @Volatile
    var wifiOnly: Boolean? = null

    private var running = false
    private var again = false

    private class Active(val record: DownloadRecord, val fileName: String) {
        @Volatile var call: Call<ResponseBody>? = null
        /** Why the network callback stopped it (`null` = it didn't). */
        @Volatile var pausedFor: DownloadGate? = null
        @Volatile var installing = false
    }

    @Volatile
    private var active: Active? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Whether the last look (run or callback) found a download that may run - a callback only
     * starts a run on the edge to "yes". */
    @Volatile
    private var anyGoLast = false

    @Volatile
    private var lastRelistMs = 0L

    private enum class Outcome {
        /** Something happened (finished, installed, dropped, restarted): look again at once. */
        PROGRESS,
        /** The network stopped qualifying (or there's no room): the gates decide again. */
        PAUSED,
        /** A stall, a server error: retry after a delay. */
        FAILED,
        /** Our own update is being committed: nothing more in this process. */
        STOP,
    }

    fun dir(context: Context): File = File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    private fun partial(context: Context, record: DownloadRecord) = File(dir(context), partialFileName(record.appId, record.tag))

    fun wifiOnlyNow(): Boolean = wifiOnly ?: try {
        (currentPolicyDecision().policy?.appUpdatesWifiOnly == true).also { wifiOnly = it }
    } catch (e: Exception) {
        false
    }

    // ---- triggers ------------------------------------------------------------------------------

    /** Application.initRest (process start): the sweep that needs no list, then a run if anything waits. */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                sweepWithoutList(app)
                if (AppDownloadStore.load(app).isNotEmpty()) request(app, "start")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Download init failed", e)
            }
        }
    }

    /** Starts a run through the anchor service (it also revives a dead anchor). */
    fun request(context: Context, reason: String) {
        val app = context.applicationContext
        if (!CommandListenerService.requestDownloads(app, reason)) runInService(app, reason)
    }

    /** From [CommandListenerService] (or [request] when the anchor can't start): one run at a
     * time; a request during a run makes it look again at the end. */
    fun runInService(context: Context, reason: String) {
        val app = context.applicationContext
        synchronized(this) {
            if (running) {
                again = true
                return
            }
            running = true
        }
        scope.launch { runLoop(app, reason) }
    }

    // ---- the sync ------------------------------------------------------------------------------

    /** After a successful list fetch: the records become exactly [wanted] (QA #4: the only place
     * that drops a release because the list no longer has it), then the files are swept. */
    fun reconcile(context: Context, wanted: List<WantedDownload>) {
        val app = context.applicationContext
        AppDownloadStore.mutate(app) { current ->
            // The install state as it is now, under the lock - not the sync's earlier snapshot: a
            // release the runner committed or handed over meanwhile is not wanted again (qa-13-code #3).
            val now = System.currentTimeMillis()
            val state = TrackedAppUpdateState.load()
            val pendingTag = TrackedAppUpdateState.pendingEntry()?.second?.releaseTag
            val still = wanted.filter {
                releaseStillWanted(it.tag, it.isLauncher, state[it.appId.toString()], pendingTag, now, INSTALL_ATTEMPT_TIMEOUT_MS)
            }
            reconcileRecords(current, still, now)
        }
        sweepFiles(app)
        sweepLegacy(app)
        // The switch may have just come on while a download runs on mobile data.
        active?.let { onNetwork(app, currentCaps(app)) }
    }

    /** Without a list (process start, a failed fetch): only records of installed releases, files
     * without a record, the old cache files. */
    fun sweepWithoutList(context: Context) {
        val app = context.applicationContext
        AppDownloadStore.mutate(app) { records ->
            val installedTags = TrackedAppUpdateState.load().mapNotNull { (key, state) -> key.toLongOrNull()?.let { it to state.lastInstalledTag } }.toMap()
            records - installedRecords(records, installedTags).toSet()
        }
        sweepFiles(app)
        sweepLegacy(app)
    }

    private fun sweepFiles(app: Context) {
        val names = dir(app).list()?.toList().orEmpty()
        for (name in sweepFiles(names, AppDownloadStore.load(app), active?.fileName)) {
            Log.i(LOG_TAG, "Removing $name")
            File(dir(app), name).delete()
        }
    }

    /** Older builds' `cacheDir/tracked_app_*.apk` (cancelled or failed downloads were never
     * deleted), once an hour old. */
    private fun sweepLegacy(app: Context) {
        val files = app.cacheDir.listFiles().orEmpty().map { it.name to it.lastModified() }
        for (name in legacyCacheFilesToDelete(files, System.currentTimeMillis())) {
            Log.i(LOG_TAG, "Removing the old cache file $name")
            File(app.cacheDir, name).delete()
        }
    }

    /** The status report's `app_downloads`: every record (at most 20), what it waits for, how far
     * it is, and the network. */
    fun report(context: Context): AppDownloadsReport {
        val app = context.applicationContext
        val caps = currentCaps(app)
        val wifiOnly = wifiOnlyNow()
        val allocatable = allocatableBytes(app)
        val now = System.currentTimeMillis()
        val running = active
        val entries = downloadOrder(AppDownloadStore.load(app)).take(20).map { record ->
            val file = partial(app, record)
            val have = if (file.isFile) file.length() else 0L
            val state = if (running != null && running.record.appId == record.appId && running.record.tag == record.tag) {
                if (running.installing) "installing" else "downloading"
            } else {
                downloadGate(caps, wifiOnly, anyNetworkAtMs(record.isLauncher, record.firstSeenMs), now, have, record.total, allocatable).wire
            }
            AppDownloadEntry(
                trackedAppId = record.appId,
                releaseTag = record.tag,
                state = state,
                bytes = have,
                total = record.total,
                sinceMs = record.firstSeenMs,
                anyNetworkAtMs = anyNetworkAtMs(record.isLauncher, record.firstSeenMs),
            )
        }
        return AppDownloadsReport(wifiOnly = wifiOnly, network = networkLabel(caps), entries = entries)
    }

    // ---- the network ---------------------------------------------------------------------------

    /** The default network: our VPN's capabilities while it is up (with the underlying transport
     * merged in), else the physical network's. `null` = none. */
    fun currentCaps(context: Context): NetCaps? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        if (cm == null || network == null) null else cm.getNetworkCapabilities(network)?.let(::netCaps)
    } catch (e: Exception) {
        null
    }

    private fun netCaps(c: NetworkCapabilities) = NetCaps(
        internet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        notMetered = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        notRoaming = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        physical = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
    )

    private fun allocatableBytes(context: Context): Long? = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage?.getAllocatableBytes(storage.getUuidForPath(context.noBackupFilesDir))
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun startWatching(app: Context) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onNetwork(app, netCaps(networkCapabilities))
            }

            override fun onLost(network: Network) {
                onNetwork(app, null)
            }
        }
        try {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the network", e)
        }
    }

    @Synchronized
    private fun stopWatching(app: Context) {
        val cb = callback ?: return
        callback = null
        try {
            app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't stop watching the network", e)
        }
    }

    /** The default network changed (ConnectivityThread): a running download that no longer
     * qualifies is cancelled (a Wi-Fi -> cellular switch only changes the VPN's capabilities,
     * there is no onLost - QA #1); a waiting one that now does starts a run. */
    private fun onNetwork(app: Context, caps: NetCaps?) {
        try {
            val wifiOnly = wifiOnlyNow()
            val now = System.currentTimeMillis()
            val running = active
            if (running != null) {
                val gate = downloadGate(caps, wifiOnly, anyNetworkAtMs(running.record.isLauncher, running.record.firstSeenMs), now, 0, null, null)
                if (gate != DownloadGate.GO && !running.installing && running.pausedFor == null) {
                    Log.i(LOG_TAG, "Pausing ${running.record.name}: ${gate.wire}")
                    running.pausedFor = gate
                    running.call?.cancel()
                }
                return
            }
            val anyGo = AppDownloadStore.load(app).any {
                downloadGate(caps, wifiOnly, anyNetworkAtMs(it.isLauncher, it.firstSeenMs), now, 0, null, null) == DownloadGate.GO
            }
            val edge = anyGo && !anyGoLast
            anyGoLast = anyGo
            if (edge) request(app, "network")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Network change handling failed", e)
        }
    }

    // ---- the run -------------------------------------------------------------------------------

    private suspend fun runLoop(app: Context, reason: String) {
        Log.i(LOG_TAG, "Downloads ($reason)")
        var failures = 0
        var steps = 0
        var rechecks = 0
        try {
            while (true) {
                synchronized(this) { again = false }
                val records = AppDownloadStore.load(app)
                if (records.isEmpty()) {
                    stopWatching(app)
                    anyGoLast = false
                    break
                }
                startWatching(app)
                val caps = currentCaps(app)
                val wifiOnly = wifiOnlyNow()
                val allocatable = allocatableBytes(app)
                val now = System.currentTimeMillis()
                val gates = records.associate { record ->
                    val file = partial(app, record)
                    val have = if (file.isFile) file.length() else 0L
                    record.appId to downloadGate(caps, wifiOnly, anyNetworkAtMs(record.isLauncher, record.firstSeenMs), now, have, record.total, allocatable)
                }
                val next = downloadOrder(records).firstOrNull { gates[it.appId] == DownloadGate.GO }
                anyGoLast = next != null
                if (next == null) {
                    // A network that began to qualify after the caps were read left the callback no
                    // edge (qa-13-code #6): look once more now that anyGoLast says "none".
                    val again = currentCaps(app)
                    if (again != caps && rechecks++ < 3 && records.any {
                            downloadGate(again, wifiOnly, anyNetworkAtMs(it.isLauncher, it.firstSeenMs), now, 0, null, null) == DownloadGate.GO
                        }
                    ) {
                        continue
                    }
                    Log.i(LOG_TAG, "Waiting: " + records.joinToString { "${it.name} ${gates[it.appId]?.wire}" })
                    break
                }
                if (selfUpdateCommittingNow()) {
                    // Our own update is going in: this process ends soon; the next one carries on.
                    Log.i(LOG_TAG, "Our own update is being committed - downloads wait for the next process")
                    break
                }
                if (++steps > MAX_STEPS_PER_RUN) {
                    Log.w(LOG_TAG, "Too many attempts in one run - waiting for the next trigger")
                    break
                }
                acquireWakeLock(app)
                when (attempt(app, next)) {
                    Outcome.PROGRESS -> failures = 0
                    Outcome.PAUSED -> {}
                    Outcome.STOP -> break
                    Outcome.FAILED -> {
                        if (failures >= RETRY_DELAYS_MS.size) {
                            Log.w(LOG_TAG, "Download keeps failing - waiting for the next trigger")
                            break
                        }
                        releaseWakeLock()
                        delay(RETRY_DELAYS_MS[failures++])
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Download run failed", e)
        } finally {
            releaseWakeLock()
            val relaunch = synchronized(this) {
                if (again) {
                    again = false
                    true
                } else {
                    running = false
                    false
                }
            }
            if (relaunch) scope.launch { runLoop(app, "again") }
        }
    }

    private suspend fun attempt(app: Context, record: DownloadRecord): Outcome {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val token = mdm.deviceToken()
        if (serverUrl.isNullOrBlank() || token.isNullOrBlank()) return Outcome.STOP
        val file = partial(app, record)
        // The install state may have moved since the sync queued this (qa-13-code #3).
        val state = TrackedAppUpdateState.load()
        val pendingTag = TrackedAppUpdateState.pendingEntry()?.second?.releaseTag
        if (!releaseStillWanted(record.tag, record.isLauncher, state[record.appId.toString()], pendingTag, System.currentTimeMillis(), INSTALL_ATTEMPT_TIMEOUT_MS)) {
            Log.i(LOG_TAG, "${record.name} ${record.tag} is installed, refused or installing - dropping the download")
            AppDownloadStore.remove(app, record.appId, record.tag)
            file.delete()
            return Outcome.PROGRESS
        }
        val current = Active(record, file.name)
        active = current
        try {
            // A fresh client per attempt: it picks up the tailnet proxy as it is now (QA #5).
            return download(app, record, file, current, createMdmApi(serverUrl, token))
        } finally {
            active = null
        }
    }

    private suspend fun download(app: Context, record: DownloadRecord, file: File, current: Active, api: MdmApi): Outcome {
        var rec = record
        var have = if (file.isFile) file.length() else 0L
        // Without the first ETag a partial can't be resumed safely (200 from an older server).
        if (have > 0 && (rec.etag == null || rec.total?.let { have > it } == true)) {
            file.delete()
            have = 0
        }
        // Complete already (the process died between the last byte and the install).
        if (have > 0 && rec.total == have) return finish(app, rec, file, api, current)

        val call = api.downloadTrackedApp(rec.downloadUrl, if (have > 0) "bytes=$have-" else null, if (have > 0) rec.etag else null)
        current.call = call
        if (current.pausedFor != null) return Outcome.PAUSED
        notifyAppInstalling(app, rec.appId, rec.name)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            return stopped(app, rec, current, e)
        }
        try {
            if (releaseTagMismatch(response.headers()[RELEASE_TAG_HEADER], rec.tag)) {
                // A sync on the server replaced the file since our list (QA #2): never save B as A.
                Log.i(LOG_TAG, "${rec.name}: the server has another release now - asking for the list again")
                drop(app, rec, file)
                relist(app)
                return Outcome.PROGRESS
            }
            val contentRange = response.headers()["Content-Range"]
            val action = resumeAction(response.code(), have, rec.total, contentRange)
            when (action) {
                ResumeAction.GONE -> {
                    // Deselected, or the file is gone on the server: not again before the backoff.
                    Log.w(LOG_TAG, "${rec.name}: 404 - dropping the download")
                    drop(app, rec, file)
                    TrackedAppUpdateState.recordFailed(app, rec.appId.toString(), rec.tag)
                    // The server's 404 also means "ask for the list again" (qa-13-code #4).
                    relist(app)
                    return Outcome.PROGRESS
                }
                ResumeAction.RETRY -> {
                    Log.w(LOG_TAG, "${rec.name}: HTTP ${response.code()} - trying again later")
                    notifyAppInstallResult(app, rec.appId, rec.name, success = true)
                    return Outcome.FAILED
                }
                ResumeAction.RESTART -> {
                    Log.i(LOG_TAG, "${rec.name}: HTTP ${response.code()} - the partial file doesn't fit, starting over")
                    file.delete()
                    AppDownloadStore.update(app, rec.copy(etag = null, total = null))
                    notifyAppInstallResult(app, rec.appId, rec.name, success = true)
                    return Outcome.PROGRESS
                }
                ResumeAction.COMPLETE -> return finish(app, rec, file, api, current)
                ResumeAction.TRUNCATE -> have = 0
                ResumeAction.APPEND -> {}
            }
            val body = response.body() ?: return Outcome.FAILED
            val total = if (action == ResumeAction.APPEND) {
                parseContentRange(contentRange)?.second
            } else {
                body.contentLength().takeIf { it > 0 }
            }
            val etag = response.headers()["ETag"]?.takeIf { !it.startsWith("W/") }
            if (rec.etag != etag || rec.total != total) {
                // Committed before the first byte: a partial is only ever resumed against the
                // ETag of the response that wrote it.
                rec = AppDownloadStore.update(app, rec.copy(etag = etag, total = total))
            }
            if (total != null) {
                val allocatable = allocatableBytes(app)
                if (allocatable != null && !enoughSpace(have, total, allocatable)) {
                    Log.w(LOG_TAG, "${rec.name}: not enough free space - waiting")
                    notifyAppInstallResult(app, rec.appId, rec.name, success = true)
                    return Outcome.PAUSED
                }
            }
            write(app, rec, file, body, have, total, api)?.let { return it }
        } catch (e: IOException) {
            return stopped(app, rec, current, e)
        } finally {
            response.body()?.close()
            response.errorBody()?.close()
        }
        if (rec.total != null && file.length() != rec.total) {
            Log.w(LOG_TAG, "${rec.name}: the response ended early - resuming later")
            notifyAppInstallResult(app, rec.appId, rec.name, success = true)
            return Outcome.FAILED
        }
        return finish(app, rec, file, api, current)
    }

    /** Writes the body (appending from [have], or from the start); `null` = the body is written. */
    private suspend fun write(app: Context, rec: DownloadRecord, file: File, body: ResponseBody, have: Long, total: Long?, api: MdmApi): Outcome? {
        var written = have
        var lastPercent = -5
        var lastRenew = System.currentTimeMillis()
        var lastCheck = lastRenew
        FileOutputStream(file, have > 0).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (total != null && written > total) {
                        Log.w(LOG_TAG, "${rec.name}: longer than announced - starting over")
                        file.delete()
                        AppDownloadStore.update(app, rec.copy(etag = null, total = null))
                        return Outcome.PROGRESS
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastRenew >= WAKE_LOCK_RENEW_MS) {
                        lastRenew = now
                        acquireWakeLock(app)
                    }
                    if (now - lastCheck >= RECORD_CHECK_MS) {
                        lastCheck = now
                        // The sync may have dropped or replaced this release meanwhile (QA #4).
                        if (AppDownloadStore.get(app, rec.appId)?.tag != rec.tag) {
                            Log.i(LOG_TAG, "${rec.name} ${rec.tag} is no longer wanted - stopping")
                            output.close()
                            file.delete()
                            notifyAppInstallResult(app, rec.appId, rec.name, success = true)
                            return Outcome.PROGRESS
                        }
                    }
                    if (total != null && total > 0) {
                        val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                        if (percent >= lastPercent + 5) {
                            lastPercent = percent
                            try {
                                api.reportInstallProgress(InstallProgressReport(rec.appId, percent))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(LOG_TAG, "Couldn't report the download progress", e)
                            }
                        }
                    }
                }
                output.flush()
                output.fd.sync()
            }
        }
        return null
    }

    /** The request was cancelled (the network stopped qualifying) or failed (stall, reset). The
     * partial file stays either way. */
    private fun stopped(app: Context, rec: DownloadRecord, current: Active, e: IOException): Outcome {
        notifyAppInstallResult(app, rec.appId, rec.name, success = true)
        val paused = current.pausedFor
        return if (paused != null) {
            Log.i(LOG_TAG, "${rec.name} paused (${paused.wire}) - the partial file is kept")
            Outcome.PAUSED
        } else {
            Log.w(LOG_TAG, "${rec.name}: download interrupted - resuming later", e)
            Outcome.FAILED
        }
    }

    /** The whole file is here: the hash, then install it (or hand our own update to the window). */
    private suspend fun finish(app: Context, rec: DownloadRecord, file: File, api: MdmApi, current: Active): Outcome {
        val sha256 = try {
            SelfUpdate.sha256(file)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "${rec.name}: couldn't read the download", e)
            return Outcome.FAILED
        }
        if (rec.sha256 != null && !sha256.equals(rec.sha256, ignoreCase = true)) {
            file.delete()
            // Counted per release, across records and backoffs (qa-13-code #4).
            val failures = TrackedAppUpdateState.recordHashMismatch(app, rec.appId.toString(), rec.tag)
            if (failures >= MAX_HASH_FAILURES) {
                Log.w(LOG_TAG, "${rec.name}: the download failed its hash again - waiting out the backoff")
                AppDownloadStore.remove(app, rec.appId, rec.tag)
                TrackedAppUpdateState.recordFailed(app, rec.appId.toString(), rec.tag)
                notifyAppInstallResult(app, rec.appId, rec.name, success = false)
                reportFailure(api, rec.appId)
            } else {
                // Not installed, no backoff: downloaded again at once (design 13 QA #2) - with the
                // list asked again first, so a re-upload under the same label brings its own hash
                // (qa-13-code #4).
                Log.w(LOG_TAG, "${rec.name}: the download doesn't match the server's hash - starting over")
                AppDownloadStore.update(app, rec.copy(etag = null, total = null))
                notifyAppInstallResult(app, rec.appId, rec.name, success = true)
                relist(app)
            }
            return Outcome.PROGRESS
        }
        return if (rec.isLauncher) storeSelfUpdate(app, rec, file, sha256) else install(app, rec, file, api, current)
    }

    private suspend fun install(app: Context, rec: DownloadRecord, file: File, api: MdmApi, current: Active): Outcome {
        val key = rec.appId.toString()
        current.installing = true
        val started = installMutex.withLock {
            if (selfUpdateCommittingNow()) return@withLock null
            // In flight from the commit until AppInstallReceiver's result (design 13 §4).
            TrackedAppUpdateState.recordAttemptStarted(app, key)
            notifyAppInstalling(app, rec.appId, rec.name)
            AppInstaller.installSilently(app, file, key, rec.name, isLauncher = false, releaseTag = rec.tag)
        }
        return when (started) {
            null -> {
                Log.i(LOG_TAG, "Our own update is being committed - ${rec.name} installs after it")
                Outcome.STOP
            }
            InstallStart.COMMITTED -> {
                // The session has its own copy: AppInstallReceiver deletes the file and records
                // the result (a failure waits out the backoff, then downloads again).
                AppDownloadStore.remove(app, rec.appId, rec.tag)
                Log.i(LOG_TAG, "Installing ${rec.name} ${rec.tag}")
                Outcome.PROGRESS
            }
            InstallStart.FAILED, InstallStart.DEFERRED -> {
                // The install couldn't start (installSilently deleted the file): the hourly backoff
                // of a failed release, then a new download - not one per sync (qa-13-code #2).
                TrackedAppUpdateState.recordFailed(app, key, rec.tag)
                AppDownloadStore.remove(app, rec.appId, rec.tag)
                notifyAppInstallResult(app, rec.appId, rec.name, success = false)
                reportFailure(api, rec.appId)
                Outcome.PROGRESS
            }
        }
    }

    /** Our own update: moved into `self_update/` and kept pending - the sync's last step commits it
     * in the night window (step 11). */
    private fun storeSelfUpdate(app: Context, rec: DownloadRecord, file: File, sha256: String): Outcome {
        val stored = synchronized(SelfUpdate) {
            val target = SelfUpdate.newFile(app)
            if (!file.renameTo(target)) return@synchronized false
            TrackedAppUpdateState.recordPending(
                app,
                rec.appId.toString(),
                PendingSelfUpdate(rec.tag, target.name, target.length(), sha256, System.currentTimeMillis(), rec.name),
            )
            true
        }
        if (!stored) {
            Log.w(LOG_TAG, "Couldn't move the launcher update into place")
            file.delete()
            return Outcome.FAILED
        }
        AppDownloadStore.remove(app, rec.appId, rec.tag)
        // Nothing is installing yet: the "Installing" notification comes back at the commit.
        notifyAppInstallResult(app, rec.appId, rec.name, success = true)
        Log.i(LOG_TAG, "Downloaded launcher ${rec.tag} - waiting for the update window")
        SelfUpdate.onPendingStored(app)
        return Outcome.PROGRESS
    }

    /** Our own update's commit may be running in this process (qa-13-code #1): the flag alone stays
     * set after a failed commit whose result came back here; the launcher's attempt doesn't. */
    private fun selfUpdateCommittingNow(): Boolean {
        if (!SelfUpdate.committedInThisProcess) return false
        val launcherKey = TrackedAppUpdateState.pendingEntry()?.first
        val attempt = launcherKey?.let { TrackedAppUpdateState.load()[it]?.attemptStartedAtMs }
        return selfUpdateCommitting(true, attempt, System.currentTimeMillis(), INSTALL_ATTEMPT_TIMEOUT_MS)
    }

    private fun drop(app: Context, rec: DownloadRecord, file: File) {
        AppDownloadStore.remove(app, rec.appId, rec.tag)
        file.delete()
        notifyAppInstallResult(app, rec.appId, rec.name, success = true)
    }

    /** The list again through a sync, at most every 10 minutes (a server that keeps changing must
     * not keep the phone syncing). */
    private fun relist(app: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRelistMs < RELIST_MIN_GAP_MS && now >= lastRelistMs) return
        lastRelistMs = now
        SyncRunner.request(app, "download_release_changed")
    }

    private suspend fun reportFailure(api: MdmApi, appId: Long) {
        try {
            api.reportInstallProgress(InstallProgressReport(appId, percent = 0, failed = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't report the install failure", e)
        }
    }

    // ---- the wake lock -------------------------------------------------------------------------

    @Synchronized
    private fun acquireWakeLock(context: Context) {
        try {
            val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidslauncher:download")
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
            lock?.acquire(WAKE_LOCK_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't take the download wake lock", e)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't release the download wake lock", e)
        }
    }
}
