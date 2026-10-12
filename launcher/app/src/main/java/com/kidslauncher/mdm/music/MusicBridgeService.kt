package com.kidslauncher.mdm.music

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        override fun getStorytelLogin(haveGeneration: Long, callback: IStorytelCallback?) {
            check()
            callback ?: return
            scope.launch { answerStorytel(haveGeneration, callback) }
        }

        override fun reportState(state: Bundle?) {
            check()
            val app = this@MusicBridgeService
            val report = state?.getString(REPORT) ?: return
            MusicStore.setReport(app, report, System.currentTimeMillis())
            // The files the music app holds replace the handed-over marks (a launcher reinstall, files
            // fed another way, a lost releaseFile), and a ready copy of one of them goes (qa-21-step3-code
            // M1); a file it lost isn't held any more, so it is fetched again.
            val ids = state.getLongArray(HELD_FILES)
            val shas = state.getStringArray(HELD_SHA256)
            if (ids != null && shas != null && ids.size == shas.size) {
                val held = ids.indices.associate { ids[it] to shas[it] }
                MusicStore.setHandedOver(app, held)
                val ready = MusicStore.ready(app)
                val gone = ready.filter { (id, sha) -> held[id] == sha }.keys
                if (gone.isNotEmpty()) {
                    gone.forEach { MusicStore.readyFile(app, it).delete() }
                    MusicStore.setReady(app, ready - gone)
                }
                MusicFileDownloads.request(app)
            }
        }

        override fun setNowPlaying(nowPlaying: Bundle?) {
            check()
            MusicNowPlaying.store(this@MusicBridgeService, nowPlaying)
        }

        override fun setForeground(token: IBinder?, inFront: Boolean) {
            check()
            val app = applicationContext
            // The token dies with the music app's process: it isn't in front, and it doesn't play.
            MusicForeground.set(app, token, inFront) { MusicNowPlaying.playerGone(app) }
        }

        override fun listingVersion(entryId: Long): String? {
            check()
            return MusicStore.listings(this@MusicBridgeService)[entryId]
        }

        override fun openListing(entryId: Long): ParcelFileDescriptor? {
            check()
            return readOnly(MusicStore.listingFile(this@MusicBridgeService, entryId))
        }

        override fun wantArt(now: Array<out String?>?, later: Array<out String?>?) {
            check()
            val app = this@MusicBridgeService
            val want = wantArtLists(now?.toList(), later?.toList())
            scope.launch { wantArtChanged(app, want) }
        }
    }

    /**
     * The music app's want set (design 27 §2.2): stored (both lists at once), then a courier run only
     * when a picture is fetchable now (QA M6) - not held, not backed off, `later` only on an allowed
     * network - with the cached policy's music part.
     */
    private fun wantArtChanged(app: Context, want: WantArt) {
        MusicStore.setWantArt(app, want)
        val music = musicPolicy(currentPolicyDecision().policy?.music) ?: return
        if (MusicCourier.artFetchable(app, want, music).isNotEmpty()) MusicCourier.request(app, music)
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

/** The Bundle keys of `reportState` (shared with the music app: bridge_contract.json). */
const val REPORT = "report"
const val HELD_FILES = "held_files"
const val HELD_SHA256 = "held_sha256"

/**
 * What plays, from the music app's `setNowPlaying` (21 §3/§4.6): kept in CE prefs for Home's card
 * (its snapshot, shown paused while no session is live), never sent to the server; and whether a
 * music app update must wait.
 */
object MusicNowPlaying {
    /** The Bundle's keys (shared with the music app: bridge_contract.json). */
    const val ENTRY = "entry"
    const val ITEM = "item"
    const val POSITION_MS = "position_ms"
    const val DURATION_MS = "duration_ms"
    const val PLAYING = "playing"
    const val ART = "art"
    private const val MAX_ART_BYTES = 64 * 1024

    /** A music app update was deferred: the next pause (or the player's end) asks for a download run (S3). */
    @Volatile
    var installWaiting = false

    private val main = Handler(Looper.getMainLooper())
    /** Home's card while shown (main thread). */
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /** The last snapshot (entry, item, position, duration), or null at a queue's end or before any. */
    fun snapshot(context: Context): NowPlayingSnapshot? {
        val p = MusicStore.prefs(context)
        val entry = p.getString("np_entry", null)
        val item = p.getString("np_item", null)
        if (entry == null && item == null) return null
        return NowPlayingSnapshot(entry, item, p.getLong("np_position", 0), p.getLong("np_duration", 0))
    }

    fun store(context: Context, nowPlaying: Bundle?) {
        val prefs = MusicStore.prefs(context)
        val playing = nowPlaying?.getBoolean(PLAYING) == true
        val wasPlaying = playing(context)
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            if (nowPlaying == null) {
                remove("np_entry"); remove("np_item"); remove("np_position"); remove("np_duration")
            } else {
                putString("np_entry", nowPlaying.getString(ENTRY)?.take(200))
                putString("np_item", nowPlaying.getString(ITEM)?.take(300))
                putLong("np_position", nowPlaying.getLong(POSITION_MS))
                putLong("np_duration", nowPlaying.getLong(DURATION_MS))
            }
            // Refreshed by every "playing" report (L2): hours of play keep holding the update.
            if (playing) putLong("playing_at", now) else remove("playing_at")
            putLong("np_at", now)
        }.apply()
        val art = nowPlaying?.getByteArray(ART)?.takeIf { it.size <= MAX_ART_BYTES }
        val artFile = MusicStore.nowPlayingArt(context)
        // The 15 s position reports carry the same art: written only when it changed.
        if (art == null) {
            artFile.delete()
        } else if (!(artFile.isFile && artFile.length() == art.size.toLong() && runCatching { artFile.readBytes() }.getOrNull()?.contentEquals(art) == true)) {
            runCatching { MusicStore.writeAtomic(artFile, art) }
        }
        if (!playing) releaseWaitingInstall(context)
        if (playing != wasPlaying) listeningChanged(context)
        changed()
    }

    /** The music app's process died (its token): nothing plays any more; the snapshot stays (shown paused). */
    fun playerGone(context: Context) {
        val wasPlaying = playing(context)
        MusicStore.prefs(context).edit().remove("playing_at").apply()
        releaseWaitingInstall(context)
        if (wasPlaying) listeningChanged(context)
        changed()
    }

    /** The last `setNowPlaying` said "playing" (and the player's process lives): with the player in
     * front, that is listening - never screen time (`musicListening`). */
    fun playing(context: Context): Boolean = MusicStore.playingReportedAt(context) != null

    private fun listeningChanged(context: Context) {
        val app = context.applicationContext
        main.post { com.kidslauncher.mdm.timerules.ScreenTimeTracker.update(app) }
    }

    private fun releaseWaitingInstall(context: Context) {
        if (!installWaiting) return
        installWaiting = false
        AppDownloads.request(context, "music paused")
    }

    /** Must the APK [file] wait (it is the music app's, and the music app plays)? */
    fun deferInstall(context: Context, file: File): Boolean {
        val playingAt = MusicStore.playingReportedAt(context) ?: return false
        val pkg = runCatching { context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName }.getOrNull()
        return installDeferredForPlayback(pkg, playingAt, System.currentTimeMillis()).also { if (it) installWaiting = true }
    }
}

/**
 * Whether the player is in front (21 §3 time rules): the music app sends it from
 * `onTopResumedActivityChanged`, false in multi-window (QA #5). In front while it plays is listening,
 * never screen time (`musicListening`, the user's decision after qa-21-step4-code L2). Cleared at every screen-off ([screenOff]) and when the token
 * dies with the music app's process (which also tells [onGone]); each change re-runs the screen-time
 * tracker.
 */
object MusicForeground {
    @Volatile
    var inFront: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var token: IBinder? = null
    private var onGone: (() -> Unit)? = null
    private val death = IBinder.DeathRecipient {
        val gone = synchronized(this) { onGone }
        clear()
        gone?.invoke()
    }

    @Synchronized
    fun set(context: Context, newToken: IBinder?, front: Boolean, gone: () -> Unit) {
        app = context.applicationContext
        onGone = gone
        if (newToken != token) {
            runCatching { token?.unlinkToDeath(death, 0) }
            token = newToken
            runCatching { newToken?.linkToDeath(death, 0) }.onFailure { token = null }
        }
        changeTo(front && token != null)
    }

    /** The screen went off: nothing is in front until the music app says so again. */
    @Synchronized
    fun screenOff() = changeTo(false)

    @Synchronized
    fun clear() {
        runCatching { token?.unlinkToDeath(death, 0) }
        token = null
        changeTo(false)
    }

    private fun changeTo(front: Boolean) {
        if (front == inFront) return
        inFront = front
        val context = app ?: return
        main.post { com.kidslauncher.mdm.timerules.ScreenTimeTracker.update(context) }
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
