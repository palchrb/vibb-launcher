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

/**
 * The parent's own files for the music app (design 21 §2/§3, QA #8): downloaded one at a time, one
 * ahead of the handover (the next starts when the music app has taken the last), catalog APKs
 * first. Design 13's rules, from its pure functions: resumable with Range and the first ETag as
 * If-Match, 412/416/404 handled as there, a size and SHA-256 check, NOT_METERED unless the phone's
 * music mobile-data switch is on, never while roaming, room for the file. A run stops when the
 * network stops qualifying; the next sync, a handover or a `wantFiles` starts the next one. Its own
 * small runner (not AppDownloads), so design 22's new transport replaces one interface.
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

    private suspend fun runLoop(app: Context) {
        val wake = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibb:music_files")
        wake?.setReferenceCounted(false)
        try {
            while (true) {
                synchronized(this) { again = false }
                val head = MusicStore.head(app) ?: break
                val next = nextOwnFile(head, MusicStore.handedOver(app), MusicStore.ready(app).keys) ?: break
                if (AppDownloads.busy()) {
                    Log.i(LOG_TAG, "Catalog downloads first - own files wait for the next sync")
                    break
                }
                val partial = MusicStore.partialFile(app, next.id)
                val have = if (partial.isFile) partial.length() else 0L
                val gate = downloadGate(AppDownloads.currentCaps(app), !MusicSync.mobileData(), null, System.currentTimeMillis(), have, next.size, allocatable(app))
                if (gate != DownloadGate.GO) {
                    Log.i(LOG_TAG, "Own file ${next.id} waits: ${gate.wire}")
                    break
                }
                wake?.acquire(10 * 60_000L)
                if (!download(app, next)) break
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

    /** One file: true when the run may go on (it finished, or was dropped), false to stop. */
    private fun download(app: Context, file: LibraryFile): Boolean {
        val mdm = LauncherPreferences.mdm()
        val url = mdm.serverUrl()
        val token = mdm.deviceToken()
        if (url.isNullOrBlank() || token.isNullOrBlank()) return false
        val server = createMusicServer(url, token)
        val partial = MusicStore.partialFile(app, file.id)
        var (etag, total) = MusicStore.partialState(app, file.id)
        var have = if (partial.isFile) partial.length() else 0L
        // Without the first ETag a partial can't be resumed safely.
        if (have > 0 && (etag == null || have > file.size)) {
            partial.delete()
            have = 0
        }
        for (restart in 0..1) {
            val call = server.file(file.id, if (have > 0) "bytes=$have-" else null, if (have > 0) etag else null)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Own file ${file.id}: network", e)
                return false
            }
            val body = response.body()
            when (resumeAction(response.code(), have, total, response.headers()["Content-Range"])) {
                ResumeAction.APPEND, ResumeAction.TRUNCATE -> {
                    val append = response.code() == 206
                    if (!append) {
                        etag = response.headers()["ETag"]
                        total = parseContentRange(response.headers()["Content-Range"])?.second ?: body?.contentLength()?.takeIf { it >= 0 }
                        MusicStore.setPartialState(app, file.id, etag, total)
                    }
                    try {
                        body?.byteStream()?.use { input ->
                            FileOutputStream(partial, append).use { out -> input.copyTo(out, 64 * 1024) }
                        } ?: return false
                    } catch (e: IOException) {
                        Log.w(LOG_TAG, "Own file ${file.id}: interrupted at ${partial.length()} bytes - resumes later", e)
                        return false
                    }
                    return finish(app, file, partial)
                }
                ResumeAction.COMPLETE -> {
                    body?.close()
                    return finish(app, file, partial)
                }
                ResumeAction.RESTART -> {
                    body?.close()
                    partial.delete()
                    MusicStore.setPartialState(app, file.id, null, null)
                    have = 0
                    etag = null
                    total = null
                }
                ResumeAction.GONE -> {
                    body?.close()
                    Log.w(LOG_TAG, "Own file ${file.id}: 404 - not this phone's, or the server lost it")
                    partial.delete()
                    return false
                }
                ResumeAction.RETRY -> {
                    body?.close()
                    Log.w(LOG_TAG, "Own file ${file.id}: HTTP ${response.code()}")
                    return false
                }
            }
        }
        return false
    }

    /** The whole file: its size and SHA-256 must match the library, then it waits for the music app. */
    private fun finish(app: Context, file: LibraryFile, partial: File): Boolean {
        val ok = partial.length() == file.size && sha256(partial) == file.sha256
        MusicStore.setPartialState(app, file.id, null, null)
        if (!ok) {
            Log.w(LOG_TAG, "Own file ${file.id} doesn't match its size or SHA-256 - downloading again")
            partial.delete()
            return false
        }
        if (!partial.renameTo(MusicStore.readyFile(app, file.id))) return false
        MusicStore.setReady(app, MusicStore.ready(app) + (file.id to file.sha256))
        MusicSync.nudge(app)
        return true
    }

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
