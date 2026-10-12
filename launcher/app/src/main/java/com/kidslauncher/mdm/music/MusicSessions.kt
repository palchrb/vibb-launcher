package com.kidslauncher.mdm.music

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.badges.BadgeListenerService
import com.kidslauncher.mdm.lock.VisibleSender

private const val LOG_TAG = "MusicSessions"

/**
 * The music app's live media session for Home's card and the PIN lock's media view (design 21
 * §3): `MediaSessionManager.getActiveSessions` through [BadgeListenerService] and its change
 * listener, filtered to [MUSIC_PACKAGES]. Without notification-listener access (granted with adb
 * at provisioning) there are no media surfaces. Watched only while Home or the lock shows one
 * ([addListener]); main thread, except [pauseBeforeSuspend].
 */
object MusicSessions {
    /** What the surfaces may know now: [access] to the sessions at all, and the music app's
     * session with its [controller] and cover [art] (null when none is active). */
    class Reading(val access: Boolean, val session: MusicSession?, val controller: MediaController?, val art: Bitmap? = null)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var manager: MediaSessionManager? = null
    private var watched: MediaController? = null

    var reading = Reading(access = false, session = null, controller = null)
        private set

    private fun component(context: Context) = ComponentName(context, BadgeListenerService::class.java)

    fun addListener(context: Context, listener: () -> Unit) {
        listeners += listener
        if (manager == null) watch(context.applicationContext)
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) unwatch()
    }

    private fun watch(app: Context) {
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, component(app), main)
            manager = m
            pick(m.getActiveSessions(component(app)))
        } catch (e: SecurityException) {
            // No notification-listener access: no media surfaces (tried again at the next show).
            reading = Reading(access = false, session = null, controller = null)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the media sessions", e)
            reading = Reading(access = false, session = null, controller = null)
        }
    }

    private fun unwatch() {
        manager?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessionsChanged) } }
        manager = null
        watched?.let { runCatching { it.unregisterCallback(callback) } }
        watched = null
        reading = Reading(access = false, session = null, controller = null)
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pick(controllers.orEmpty())
        changed()
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update(newArt = false)
        override fun onMetadataChanged(metadata: MediaMetadata?) = update(newArt = true)
        override fun onSessionDestroyed() {
            watched?.let { runCatching { it.unregisterCallback(this) } }
            watched = null
            reading = Reading(access = true, session = null, controller = null)
            changed()
        }
    }

    private fun pick(controllers: List<MediaController>) {
        val music = controllers.firstOrNull { it.packageName in MUSIC_PACKAGES }
        if (music?.sessionToken != watched?.sessionToken) {
            watched?.let { runCatching { it.unregisterCallback(callback) } }
            watched = music
            music?.registerCallback(callback, main)
        }
        reading = Reading(access = true, session = music?.let(::sessionOf), controller = music, art = art(music?.metadata))
    }

    /** The session changed: its state (and, with new metadata, its art) read once here - the
     * surfaces' ticks only read [reading]. */
    private fun update(newArt: Boolean) {
        val c = watched ?: return
        reading = Reading(access = true, session = sessionOf(c), controller = c, art = if (newArt) art(c.metadata) else reading.art)
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun sessionOf(c: MediaController): MusicSession {
        val state = c.playbackState
        val meta = c.metadata
        return MusicSession(
            packageName = c.packageName,
            play = sessionPlay(state?.state),
            entry = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            item = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            positionMs = state?.position ?: 0,
            positionAtElapsedMs = state?.lastPositionUpdateTime ?: 0,
            speed = state?.playbackSpeed ?: 1f,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
        )
    }

    /** The session's art (Media3 puts the cover's bitmap in the platform metadata), else null. */
    private fun art(meta: MediaMetadata?): Bitmap? {
        meta ?: return null
        return listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { runCatching { meta.getBitmap(it) }.getOrNull() }
    }

    /**
     * Play or pause the live session (its transport controls - nothing is opened). A pause always
     * goes; play, next and previous only while the music app can be used, looked up at the tap
     * (qa-21-step4-code M1: a time rule may have suspended it since the surface was drawn).
     */
    fun playPause(context: Context, controller: MediaController?, session: MusicSession?) {
        val c = controller ?: return
        val controls = c.transportControls
        runCatching {
            when {
                session?.playing == true -> controls.pause()
                usable(context, c.packageName) -> controls.play()
            }
        }
    }

    fun next(context: Context, controller: MediaController?) {
        val c = controller ?: return
        if (usable(context, c.packageName)) runCatching { c.transportControls.skipToNext() }
    }

    fun previous(context: Context, controller: MediaController?) {
        val c = controller ?: return
        if (usable(context, c.packageName)) runCatching { c.transportControls.skipToPrevious() }
    }

    /**
     * The launcher saw packages suspended or unsuspended (`LauncherApps.Callback`, main thread): a
     * music app among them re-renders the shown surfaces, which look its state up again.
     */
    fun packagesChanged(packages: List<String>?) {
        if (packages.orEmpty().any { it in MUSIC_PACKAGES } && listeners.isNotEmpty()) changed()
    }

    /**
     * Before `AppEnforcer.apply()` suspends the music app (a time rule without it as an exempt
     * app), its session is paused (21 §3) - one look at the active sessions, any thread. Without
     * listener access the music app pauses itself on `MY_PACKAGE_SUSPENDED` (QA #8).
     */
    fun pauseBeforeSuspend(context: Context, pkg: String) {
        try {
            val m = context.getSystemService(MediaSessionManager::class.java) ?: return
            for (c in m.getActiveSessions(component(context)).filter { it.packageName == pkg }) {
                if (sessionPlay(c.playbackState?.state) == SessionPlay.PLAYING) c.transportControls.pause()
            }
        } catch (e: SecurityException) {
            // No listener access: the music app's own MY_PACKAGE_SUSPENDED receiver pauses it.
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't pause $pkg before suspending it", e)
        }
    }

    /** The music app can be used now: installed, and not suspended (a time rule, the allowlist). */
    fun usable(context: Context, pkg: String?): Boolean {
        pkg ?: return false
        return try {
            !context.packageManager.isPackageSuspended(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Home's card on a live session: the session's own activity (the player), sent from the visible
     * Home - `ALLOW_IF_VISIBLE` (QA #8, as `VoipCalls`). Never used by the lock.
     */
    fun openSessionActivity(context: Context, controller: MediaController?): Boolean {
        val intent = controller?.sessionActivity ?: return false
        return try {
            intent.send(context, 0, null, null, null, null, VisibleSender.options())
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't open the player", e)
            false
        }
    }

    /**
     * Home's card: the player - on the snapshot (no live session, e.g. after a restart) told to
     * continue ([resume], `EXTRA_RESUME`: the music app's own resume rule). [pkg] is the installed
     * music app.
     */
    fun openPlayer(context: Context, pkg: String, resume: Boolean): Boolean = try {
        context.startActivity(
            Intent().setClassName(pkg, MUSIC_PLAYER_ACTIVITY)
                .putExtra(MUSIC_EXTRA_OPEN, MUSIC_OPEN_PLAYER)
                .apply { if (resume) putExtra(MUSIC_EXTRA_RESUME, true) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't open the player", e)
        false
    }

    /** The snapshot's art (≤ 64 KB from `setNowPlaying`), decoded once per change. */
    private var artCache: Pair<Long, Bitmap?>? = null

    fun snapshotArt(context: Context): Bitmap? {
        val file = MusicStore.nowPlayingArt(context)
        val stamp = if (file.isFile) file.lastModified() xor file.length() else -1L
        artCache?.takeIf { it.first == stamp }?.let { return it.second }
        val bitmap = if (stamp == -1L) null else runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
        artCache = stamp to bitmap
        return bitmap
    }
}

/**
 * Step 4's process-wide part (from `Application.initRest`, main thread): the volume cap's receivers,
 * the call rule (design 26a §6.7) and "the player is in front" ends at every screen-off (21 §3).
 */
object MusicMedia {
    fun init(app: Context) {
        try {
            VolumeCap.init(app)
            MusicCalls.init(app)
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = MusicForeground.screenOff()
                },
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Music media setup failed", e)
        }
    }
}
