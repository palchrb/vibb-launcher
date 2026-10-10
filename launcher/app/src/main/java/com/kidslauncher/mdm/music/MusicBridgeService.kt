package com.kidslauncher.mdm.music

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.AppDownloads
import com.kidslauncher.mdm.server.currentPolicyDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.vibb.bridge.IMusicBridge
import me.vibb.bridge.IStorytelCallback
import java.io.File

private const val LOG_TAG = "MusicBridge"

/**
 * The bridge the music app binds (design 21 §2, 21b §5): a bound service, not a ContentProvider, so
 * the launcher's own update only disconnects the player instead of killing it. Exported behind the
 * launcher's signature permission `<applicationId>.permission.MUSIC_BRIDGE`, and every call checks
 * that the caller is the music app ([callerAllowed]). Files go out read-only; the Storytel login is
 * fetched on request and answered in memory only.
 */
class MusicBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder = binder

    private fun check() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())?.toList()
        if (!callerAllowed(packages)) throw SecurityException("not the music app: $packages")
    }

    private fun readOnly(file: File): ParcelFileDescriptor? =
        if (file.isFile) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null

    private val binder = object : IMusicBridge.Stub() {
        override fun version(): Int {
            check()
            return 1
        }

        override fun openLibrary(haveVersion: String?): ParcelFileDescriptor? {
            check()
            val head = MusicStore.head(this@MusicBridgeService) ?: return null
            return if (head.version == haveVersion) null else readOnly(MusicStore.libraryFile(this@MusicBridgeService))
        }

        override fun openCover(hash: String?): ParcelFileDescriptor? {
            check()
            if (hash == null || !Regex("[0-9a-f]{64}").matches(hash)) return null
            return readOnly(MusicStore.coverFile(this@MusicBridgeService, hash))
        }

        override fun openFile(id: Long): ParcelFileDescriptor? {
            check()
            if (id !in MusicStore.ready(this@MusicBridgeService)) return null
            return readOnly(MusicStore.readyFile(this@MusicBridgeService, id))
        }

        override fun releaseFile(id: Long, sha256: String?) {
            check()
            val app = this@MusicBridgeService
            val ready = MusicStore.ready(app)
            if (sha256 != null && ready[id] == sha256) {
                MusicStore.readyFile(app, id).delete()
                MusicStore.setReady(app, ready - id)
            }
            if (sha256 != null) MusicStore.setHandedOver(app, MusicStore.handedOver(app) + (id to sha256))
            MusicFileDownloads.request(app)
        }

        override fun wantFiles(ids: LongArray?) {
            check()
            val app = this@MusicBridgeService
            MusicStore.setHandedOver(app, MusicStore.handedOver(app) - (ids ?: LongArray(0)).toSet())
            MusicFileDownloads.request(app)
        }

        override fun getStorytelLogin(haveGeneration: Long, callback: IStorytelCallback?) {
            check()
            callback ?: return
            scope.launch { answerStorytel(haveGeneration, callback) }
        }

        override fun reportState(state: Bundle?) {
            check()
            val app = this@MusicBridgeService
            val report = state?.getString("report") ?: return
            MusicStore.setReport(app, report, System.currentTimeMillis())
            // The files the music app holds are handed over, whatever this launcher remembers (a
            // reinstall of the launcher, files fed another way).
            val ids = state.getLongArray("held_files")
            val shas = state.getStringArray("held_sha256")
            if (ids != null && shas != null && ids.size == shas.size) {
                MusicStore.setHandedOver(app, ids.indices.associate { ids[it] to shas[it] })
                MusicFileDownloads.request(app)
            }
        }

        override fun setNowPlaying(nowPlaying: Bundle?) {
            check()
            MusicNowPlaying.store(this@MusicBridgeService, nowPlaying)
        }

        override fun setForeground(token: IBinder?, inFront: Boolean) {
            check()
            MusicForeground.set(token, inFront)
        }

        override fun listingVersion(entryId: Long): String? {
            check()
            return MusicStore.listings(this@MusicBridgeService)[entryId]
        }

        override fun openListing(entryId: Long): ParcelFileDescriptor? {
            check()
            return readOnly(MusicStore.listingFile(this@MusicBridgeService, entryId))
        }
    }

    /**
     * The Storytel login (21 §2): when the music app's generation isn't the policy's (`!=`, QA #7 -
     * a DB restore may lower it), fetched over the tailnet and answered through the callback, in
     * memory only. Generation 0, or no login on the server: "delete yours". A failure answers
     * nothing; the music app asks again at its next sync.
     */
    private suspend fun answerStorytel(have: Long, callback: IStorytelCallback) {
        val want = musicPolicy(currentPolicyDecision().policy?.music)?.storytelGeneration ?: return
        if (want == have) return
        try {
            if (want == 0L) {
                callback.onLogin(0, null, null)
                return
            }
            val mdm = LauncherPreferences.mdm()
            val url = mdm.serverUrl() ?: return
            val token = mdm.deviceToken() ?: return
            val response = createMusicServer(url, token).storytel()
            when {
                response.code() == 404 -> {
                    response.body()?.close()
                    callback.onLogin(0, null, null)
                }
                response.isSuccessful -> {
                    val body = response.body()?.use { it.string() } ?: return
                    val o = Json.parseToJsonElement(body) as? JsonObject ?: return
                    val generation = o["generation"]?.jsonPrimitive?.longOrNull ?: return
                    val email = o["email"]?.jsonPrimitive?.content ?: return
                    val password = o["password"]?.jsonPrimitive?.content ?: return
                    callback.onLogin(generation, email, password)
                }
                else -> response.body()?.close()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Storytel login not answered (${e.javaClass.simpleName})")
        }
    }
}

/**
 * What plays, from the music app's `setNowPlaying` (21 §3/§4.6): kept in CE prefs for Home and the
 * lock (step 4), never sent to the server; and whether a music app update must wait.
 */
object MusicNowPlaying {
    /** The Bundle's keys. */
    const val ENTRY = "entry"
    const val ITEM = "item"
    const val POSITION_MS = "position_ms"
    const val DURATION_MS = "duration_ms"
    const val PLAYING = "playing"
    const val ART = "art"
    private const val MAX_ART_BYTES = 64 * 1024

    fun store(context: Context, nowPlaying: Bundle?) {
        val prefs = MusicStore.prefs(context)
        val wasPlaying = prefs.getLong("playing_since", -1) >= 0
        val playing = nowPlaying?.getBoolean(PLAYING) == true
        prefs.edit().apply {
            if (nowPlaying == null) {
                remove("np_entry"); remove("np_item"); remove("np_position"); remove("np_duration")
            } else {
                putString("np_entry", nowPlaying.getString(ENTRY)?.take(200))
                putString("np_item", nowPlaying.getString(ITEM)?.take(300))
                putLong("np_position", nowPlaying.getLong(POSITION_MS))
                putLong("np_duration", nowPlaying.getLong(DURATION_MS))
            }
            if (playing && !wasPlaying) putLong("playing_since", System.currentTimeMillis())
            if (!playing) remove("playing_since")
            putLong("np_at", System.currentTimeMillis())
        }.apply()
        val art = nowPlaying?.getByteArray(ART)?.takeIf { it.size <= MAX_ART_BYTES }
        val artFile = MusicStore.nowPlayingArt(context)
        if (art != null) runCatching { MusicStore.writeAtomic(artFile, art) } else artFile.delete()
        // A music app update waited for the pause (21 §3).
        if (wasPlaying && !playing) AppDownloads.request(context, "music paused")
    }

    /** Must the APK [file] wait (it is the music app's, and the music app plays)? */
    fun deferInstall(context: Context, file: File): Boolean {
        val playingSince = MusicStore.playingSince(context) ?: return false
        val pkg = runCatching { context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName }.getOrNull()
        return installDeferredForPlayback(pkg, playingSince, System.currentTimeMillis())
    }
}

/**
 * Whether the player is in front (21 §3 time rules, step 4 reads it): its token dies with the
 * music app's process, which clears it. Screen-off and the time-rule wiring come with step 4.
 */
object MusicForeground {
    @Volatile
    var inFront: Boolean = false
        private set

    private var token: IBinder? = null
    private val death = IBinder.DeathRecipient { clear() }

    @Synchronized
    fun set(newToken: IBinder?, front: Boolean) {
        if (newToken != token) {
            runCatching { token?.unlinkToDeath(death, 0) }
            token = newToken
            runCatching { newToken?.linkToDeath(death, 0) }.onFailure { token = null }
        }
        inFront = front && token != null
    }

    @Synchronized
    fun clear() {
        runCatching { token?.unlinkToDeath(death, 0) }
        token = null
        inFront = false
    }
}

/** `music_state` for the status report (21 §1.3): null without a music app. */
object MusicStatus {
    fun report(context: Context): JsonObject? {
        val pkg = MusicStore.installedPackage(context) ?: return null
        val (report, at) = MusicStore.report(context)
        return musicState(pkg, MusicStore.versionCode(context, pkg), report, at, System.currentTimeMillis())
    }
}
