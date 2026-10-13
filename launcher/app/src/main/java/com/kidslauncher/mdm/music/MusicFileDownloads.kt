package com.kidslauncher.mdm.music

import android.content.Context
import android.os.PowerManager
import android.os.storage.StorageManager
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.AppDownloads
import com.kidslauncher.mdm.server.DownloadGate
import com.kidslauncher.mdm.server.ResumeAction
import com.kidslauncher.mdm.server.downloadGate
import com.kidslauncher.mdm.server.parseContentRange
import com.kidslauncher.mdm.server.resumeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

private const val LOG_TAG = "MusicFiles"

/** What one attempt at an own file did. */
enum class FetchResult {
    /** The whole file is here and matches its size and SHA-256. */
    DONE,

    /** The whole file came, but not with the library's size or SHA-256 (it was deleted). */
    MISMATCH,

    /** 404: not this phone's any more, or the server lost it. */
    GONE,

    /** The network stopped qualifying mid-file: the partial and its ETag stay for the next run. */
    PAUSED,

    /** A network error or a server error: the next trigger tries again. */
    FAILED,
}

/** A partial download's first ETag and size (design 13: resumable only with them). */
interface PartialState {
    fun get(): Pair<String?, Long?>
    fun set(etag: String?, total: Long?)
}

/**
 * One own file (design 13's rules, from its pure functions): resume with Range and the first ETag as
 * If-Match, 412/416/404 as there, then the size and SHA-256. [mayGo] is asked again every
 * [checkEveryBytes] while the body is copied, so a download started on Wi-Fi stops when the phone
 * moves to mobile data or roams - over tsnet the stream itself would survive the switch
 * (qa-21-step3-code H2). Pure but for its files; MusicFileDownloadsTest.
 */
class OwnFileFetch(
    private val server: MusicServer,
    private val partial: File,
    private val ready: File,
    private val state: PartialState,
    private val mayGo: () -> Boolean,
    private val checkEveryBytes: Long = 1L shl 20,
) {
    fun run(file: LibraryFile): FetchResult {
        var (etag, total) = state.get()
        var have = if (partial.isFile) partial.length() else 0L
        // Without the first ETag a partial can't be resumed safely.
        if (have > 0 && (etag == null || have > file.size)) {
            partial.delete()
            have = 0
        }
        for (attempt in 0..1) {
            val response = try {
                server.file(file.id, if (have > 0) "bytes=$have-" else null, if (have > 0) etag else null).execute()
            } catch (e: IOException) {
                return FetchResult.FAILED
            }
            val body = response.body() ?: response.errorBody()
            when (resumeAction(response.code(), have, total, response.headers()["Content-Range"])) {
                ResumeAction.APPEND, ResumeAction.TRUNCATE -> {
                    val append = response.code() == 206
                    if (!append) {
                        etag = response.headers()["ETag"]
                        total = parseContentRange(response.headers()["Content-Range"])?.second ?: body?.contentLength()?.takeIf { it >= 0 }
                        state.set(etag, total)
                    }
                    val input = body?.byteStream() ?: return FetchResult.FAILED
                    val copied = try {
                        input.use { FileOutputStream(partial, append).use { out -> copyGated(input, out) } }
                    } catch (e: IOException) {
                        return FetchResult.FAILED
                    }
                    if (!copied) return FetchResult.PAUSED
                    return finish(file)
                }
                ResumeAction.COMPLETE -> {
                    body?.close()
                    return finish(file)
                }
                ResumeAction.RESTART -> {
                    body?.close()
                    partial.delete()
                    state.set(null, null)
                    have = 0
                    etag = null
                    total = null
                }
                ResumeAction.GONE -> {
                    body?.close()
                    partial.delete()
                    state.set(null, null)
                    return FetchResult.GONE
                }
                ResumeAction.RETRY -> {
                    body?.close()
                    return FetchResult.FAILED
                }
            }
        }
        return FetchResult.FAILED
    }

    /** Copies until the end (true) or until [mayGo] says no (false), asking every [checkEveryBytes]. */
    private fun copyGated(input: java.io.InputStream, out: java.io.OutputStream): Boolean {
        val buf = ByteArray(64 * 1024)
        var sinceCheck = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return true
            out.write(buf, 0, n)
            sinceCheck += n
            if (sinceCheck >= checkEveryBytes) {
                sinceCheck = 0
                if (!mayGo()) return false
            }
        }
    }

    private fun finish(file: LibraryFile): FetchResult {
        state.set(null, null)
        if (partial.length() != file.size || sha256(partial) != file.sha256) {
            partial.delete()
            return FetchResult.MISMATCH
        }
        return if (partial.renameTo(ready)) FetchResult.DONE else FetchResult.FAILED
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The parent's own files for the music app (design 21 §2/§3, QA #8): downloaded one at a time, one
 * ahead of the handover (the next starts when the music app has taken the last), catalog APKs
 * first, with [OwnFileFetch]: the gate (NOT_METERED unless the phone's music mobile-data switch is
 * on, never roaming, room for the file) is checked before each file and again while it is copied.
 * A file that fails its hash twice waits a day, so one bad file never holds back the rest. A run ends
 * when the network stops qualifying; the next sync or handover starts the next one. Its own small
 * runner (not AppDownloads), so design 22's new transport replaces one interface.
 */
object MusicFileDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var again = false

    /** Start a run (or have the running one look again). */
    fun request(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (running) {
                again = true
                return
            }
            running = true
        }
        scope.launch { runLoop(app) }
    }

    private fun gate(app: Context, file: LibraryFile): DownloadGate {
        val partial = MusicStore.partialFile(app, file.id)
        val have = if (partial.isFile) partial.length() else 0L
        return downloadGate(AppDownloads.currentCaps(app), !MusicSync.downloadMobileData(), null, System.currentTimeMillis(), have, file.size, allocatable(app))
    }

    private fun runLoop(app: Context) {
        val wake = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibb:music_files")
        wake?.setReferenceCounted(false)
        try {
            while (true) {
                synchronized(this) { again = false }
                val head = MusicStore.head(app) ?: break
                val now = System.currentTimeMillis()
                val skip = head.files.filter { f ->
                    val (failures, at) = MusicStore.hashFailures(app, f.id, f.sha256)
                    hashFailureSkips(failures, at, now)
                }.map { it.id }.toSet()
                val next = nextOwnFile(head, MusicStore.handedOver(app), MusicStore.ready(app), skip) ?: break
                if (AppDownloads.busy()) {
                    Log.i(LOG_TAG, "Catalog downloads first - own files wait for the next sync")
                    break
                }
                val gate = gate(app, next)
                if (gate != DownloadGate.GO) {
                    Log.i(LOG_TAG, "Own file ${next.id} waits: ${gate.wire}")
                    break
                }
                val mdm = LauncherPreferences.mdm()
                val url = mdm.serverUrl()
                val token = mdm.deviceToken()
                if (url.isNullOrBlank() || token.isNullOrBlank()) break
                wake?.acquire(10 * 60_000L)
                val fetch = OwnFileFetch(
                    createMusicServer(url, token),
                    MusicStore.partialFile(app, next.id),
                    MusicStore.readyFile(app, next.id),
                    object : PartialState {
                        override fun get() = MusicStore.partialState(app, next.id)
                        override fun set(etag: String?, total: Long?) = MusicStore.setPartialState(app, next.id, etag, total)
                    },
                    mayGo = {
                        wake?.acquire(10 * 60_000L) // renewed while bytes flow (design 13)
                        gate(app, next) == DownloadGate.GO
                    },
                )
                when (fetch.run(next)) {
                    FetchResult.DONE -> {
                        MusicStore.clearHashFailures(app, next.id)
                        MusicStore.setReady(app, MusicStore.ready(app) + (next.id to next.sha256))
                        MusicSync.nudge(app)
                    }
                    FetchResult.MISMATCH -> {
                        Log.w(LOG_TAG, "Own file ${next.id} doesn't match its size or SHA-256")
                        MusicStore.recordHashFailure(app, next.id, next.sha256, System.currentTimeMillis())
                    }
                    FetchResult.GONE -> {
                        Log.w(LOG_TAG, "Own file ${next.id}: 404 - not this phone's, or the server lost it")
                        break
                    }
                    FetchResult.PAUSED -> {
                        Log.i(LOG_TAG, "Own file ${next.id}: the network stopped qualifying - resumes later")
                        break
                    }
                    FetchResult.FAILED -> break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Own-file run failed", e)
        } finally {
            if (wake?.isHeld == true) wake.release()
            val relaunch = synchronized(this) {
                if (again) {
                    again = false
                    true
                } else {
                    running = false
                    false
                }
            }
            if (relaunch) scope.launch { runLoop(app) }
        }
    }

    private fun allocatable(context: Context): Long? = try {
        val sm = context.getSystemService(StorageManager::class.java)
        sm.getAllocatableBytes(sm.getUuidForPath(context.noBackupFilesDir))
    } catch (e: Exception) {
        null
    }
}
