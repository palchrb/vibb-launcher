package com.kidslauncher.mdm.music

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
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
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.badges.BadgeListenerService
import com.kidslauncher.mdm.lock.VisibleSender
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.effectiveAllowlist

private const val LOG_TAG = "MusicSessions"

/**
 * The live media sessions for Home's card and the PIN lock's media view (design 21 §3):
 * `MediaSessionManager.getActiveSessions` through [BadgeListenerService] and its change listener. One
 * watcher, two picks: Home's card reads Vibb music's session ([Reading.session], [Reading.controller],
 * [Reading.art] - [MusicTrust], by name and signer); the lock reads every active session
 * ([Reading.live]) and picks its own (`lockTilePick`, 2026-11-04: any app the parent allowed and
 * that is usable now). Without notification-listener access (granted with adb at provisioning)
 * there are no media surfaces. Watched only while Home or the lock shows one ([addListener]); main
 * thread, except [playingSessions] and [pauseBeforeSuspend].
 */
object MusicSessions {
    /** One active session: [vibb] - Vibb music ([MusicTrust]); another app's [session] is already cut
     * (`foreignSession`) and its [art] scaled down ([FOREIGN_ART_MAX_PX]). */
    class Live(val session: MusicSession, val controller: MediaController, val art: Bitmap?, val vibb: Boolean)

    /** What the surfaces may know now: [access] to the sessions at all, and Vibb music's session
     * with its [controller] and cover [art] (null when none is active) - Home's card; [live]: every
     * active session in the platform's order (Vibb music's among them) - the lock's pick. */
    class Reading(
        val access: Boolean,
        val session: MusicSession?,
        val controller: MediaController?,
        val art: Bitmap? = null,
        val live: List<Live> = emptyList(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    /** The listeners that hear every app's session (the lock); the rest hear only Vibb music's (Home). */
    private val everyAppListeners = mutableSetOf<() -> Unit>()
    private var manager: MediaSessionManager? = null
    private var watched: List<Watched> = emptyList()

    var reading = Reading(access = false, session = null, controller = null)
        private set

    private fun component(context: Context) = ComponentName(context, BadgeListenerService::class.java)

    /**
     * [everyApp]: told of any app's session change (the lock); else only of Vibb music's (Home's card).
     * Other apps' sessions are watched only while such a listener exists (code QA M1): Home alone
     * watches Vibb music's, as before 2026-11-04.
     */
    fun addListener(context: Context, listener: () -> Unit, everyApp: Boolean = false) {
        listeners += listener
        val firstEveryApp = everyApp && everyAppListeners.isEmpty()
        if (everyApp) everyAppListeners += listener
        when {
            manager == null -> watch(context.applicationContext)
            firstEveryApp -> repick()
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
        val lastEveryApp = everyAppListeners.remove(listener) && everyAppListeners.isEmpty()
        when {
            listeners.isEmpty() -> unwatch()
            lastEveryApp -> repick()
        }
    }

    /** Moves on at every change to the sessions list and every suspension of an app with a session:
     * the lock looks its usable apps up again only then (code QA M1), never at a playback update. */
    var lockGeneration = 0
        private set

    /** The watched sessions again, with or without other apps' (an `everyApp` listener came or went). */
    private fun repick() {
        val m = manager ?: return
        val app = appContext ?: return
        try {
            pick(m.getActiveSessions(component(app)))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the media sessions", e)
        }
    }

    /** For the signer check of the session pick ([MusicTrust], S-L8 with code QA L3). */
    @Volatile
    private var appContext: Context? = null

    private fun watch(app: Context) {
        appContext = app
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
        watched.forEach { it.unregister() }
        watched = emptyList()
        reading = Reading(access = false, session = null, controller = null)
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pick(controllers.orEmpty())
        changed(vibb = true)
    }

    /** A session's metadata as the surfaces use it (the strings and the duration - the bitmap isn't kept). */
    private class Meta(val entry: String?, val item: String?, val durationMs: Long)

    private fun metaOf(meta: MediaMetadata?) = Meta(
        entry = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        item = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
    )

    /**
     * One active session, watched with its own callback: its state and metadata come from the
     * callback's arguments (code QA M1: no binder read per playback update), the art only with new
     * metadata - the surfaces' ticks only read [reading].
     */
    private class Watched(val controller: MediaController, val vibb: Boolean) {
        private var state: PlaybackState? = controller.playbackState
        private var meta: Meta
        var art: Bitmap?
        var session: MusicSession
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                this@Watched.state = state
                update(this@Watched)
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                newMetadata(metadata)
                update(this@Watched)
            }

            override fun onSessionDestroyed() = destroyed(this@Watched)
        }

        init {
            val metadata = controller.metadata
            meta = metaOf(metadata)
            art = art(metadata, vibb)
            session = sessionOf(controller.packageName, state, meta, vibb)
        }

        private fun newMetadata(metadata: MediaMetadata?) {
            meta = metaOf(metadata)
            art = art(metadata, vibb)
        }

        fun rebuild() {
            session = sessionOf(controller.packageName, state, meta, vibb)
        }

        /** Vibb music's state and art read again at a change of the sessions list (as before 2026-11-04). */
        fun reread() {
            state = controller.playbackState
            newMetadata(controller.metadata)
            rebuild()
        }

        fun register() = controller.registerCallback(callback, main)
        fun unregister() = runCatching { controller.unregisterCallback(callback) }
    }

    /** The sessions to watch: Vibb music's always, other apps' only while the lock listens. */
    private fun pick(controllers: List<MediaController>) {
        val app = appContext
        val everyApp = everyAppListeners.isNotEmpty()
        val kept = watched.associateBy { it.controller.sessionToken }
        val next = controllers.mapNotNull { c ->
            kept[c.sessionToken]?.also { if (it.vibb) it.reread() }
                ?: (app != null && MusicTrust.trusted(app, c.packageName)).let { vibb ->
                    if (vibb || everyApp) Watched(c, vibb).also { it.register() } else null
                }
        }.filter { it.vibb || everyApp }
        watched.filter { w -> next.none { it === w } }.forEach { it.unregister() }
        watched = next
        lockGeneration++
        publish()
    }

    /** Home's pick (the first Vibb music session, as before 2026-11-04) and the lock's list, from [watched]. */
    private fun publish() {
        val music = watched.firstOrNull { it.vibb }
        reading = Reading(
            access = true,
            session = music?.session,
            controller = music?.controller,
            art = music?.art,
            live = watched.map { Live(it.session, it.controller, it.art, it.vibb) },
        )
    }

    private fun isHomePick(w: Watched) = watched.firstOrNull { it.vibb } === w

    private fun update(w: Watched) {
        if (watched.none { it === w }) return
        w.rebuild()
        publish()
        changed(vibb = isHomePick(w))
    }

    private fun destroyed(w: Watched) {
        if (watched.none { it === w }) return
        val home = isHomePick(w)
        w.unregister()
        watched = watched.filter { it !== w }
        publish()
        changed(vibb = home)
    }

    /** [vibb]: Vibb music's session (Home's) changed - every listener; else only the lock's. */
    private fun changed(vibb: Boolean) = listeners.toList().filter { vibb || it in everyAppListeners }.forEach { it() }

    private fun sessionOf(pkg: String, state: PlaybackState?, meta: Meta, vibb: Boolean): MusicSession {
        val session = MusicSession(
            packageName = pkg,
            play = sessionPlay(state?.state),
            entry = meta.entry,
            item = meta.item,
            positionMs = state?.position ?: 0,
            positionAtElapsedMs = state?.lastPositionUpdateTime ?: 0,
            speed = state?.playbackSpeed ?: 1f,
            durationMs = meta.durationMs,
        )
        // S-L8: another app's metadata is untrusted - its texts cut, its times bounded.
        return if (vibb) session else foreignSession(session)
    }

    /** The session's art (Media3 puts the cover's bitmap in the platform metadata), else null;
     * another app's scaled down to [FOREIGN_ART_MAX_PX] (an oversized bitmap must never reach the lock's draw). */
    private fun art(meta: MediaMetadata?, vibb: Boolean): Bitmap? {
        meta ?: return null
        val art = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { runCatching { meta.getBitmap(it) }.getOrNull() }
        if (vibb || art == null) return art
        val long = maxOf(art.width, art.height)
        if (long <= FOREIGN_ART_MAX_PX) return art
        val scale = FOREIGN_ART_MAX_PX.toFloat() / long
        return runCatching {
            Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
        }.getOrNull()
    }

    /**
     * The apps of [live] whose session the lock may show and act on (`lockAppUsable`): not suspended;
     * another app also not hidden by the device owner and on the allowlist when the phone has one (the
     * last accepted policy's, less the relock's blocked apps - read once per call). Looked up at the
     * lock's start, at a change of the sessions list or a suspension ([lockGeneration]) and at a tap -
     * never at a playback update or a tick; any failure is "no".
     */
    fun lockUsableApps(context: Context, live: List<Live>): Set<String> {
        if (live.isEmpty()) return emptySet()
        val allowlist by lazy { effectiveAllowlist(currentPolicyDecision().policy?.allowlist, WindowRelock.blockedPackages(context)) }
        val dpm by lazy { context.getSystemService(DevicePolicyManager::class.java) }
        val browsers by lazy { browserPackages(context) }
        return live.filter { l ->
            val pkg = l.session.packageName
            usable(context, pkg) && (
                l.vibb || try {
                    val hidden = dpm.isApplicationHidden(ComponentName(context, MdmDeviceAdminReceiver::class.java), pkg)
                    val category = context.packageManager.getApplicationInfo(pkg, 0).category
                    val media = lockMediaApp(category, browser = pkg in browsers)
                    lockAppUsable(pkg, vibb = false, suspended = false, hidden = hidden, allowlist = allowlist, ownPackage = context.packageName, mediaApp = media)
                } catch (e: Exception) {
                    false
                }
                )
        }.map { it.session.packageName }.toSet()
    }

    /**
     * The web browsers ([lockMediaApp]): the apps that open both a plain https and a plain http link no app claims
     * ([lockBrowsers] of [BROWSER_PROBE_HTTPS] and [BROWSER_PROBE_HTTP], `CATEGORY_BROWSABLE`, every match - the
     * launcher holds QUERY_ALL_PACKAGES; QA M2 of lock-media-apps-2: a browser declares both, a faker with one
     * host-less https filter drops out). The default browser is one of them (RoleManager names another app's role only
     * to a privileged caller). None on any failure.
     */
    private fun browserPackages(context: Context): Set<String> = try {
        fun handlers(url: String): Set<String> {
            val probe = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            return context.packageManager.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        }
        lockBrowsers(handlers(BROWSER_PROBE_HTTPS), handlers(BROWSER_PROBE_HTTP))
    } catch (e: Exception) {
        emptySet()
    }

    /** Links of the reserved `.invalid` domain: only an app that opens any https (any http) link resolves them. */
    private const val BROWSER_PROBE_HTTPS = "https://example.invalid/"
    private const val BROWSER_PROBE_HTTP = "http://example.invalid/"

    /**
     * Play or pause the live session - nothing is opened. A pause always goes; play, next and previous
     * only while the session's app can be used, looked up at the tap (qa-21-step4-code M1: a time rule
     * may have suspended it since the surface was drawn). [vibb]: Vibb music's session, sent by its
     * transport controls as before; another app's goes as a media button ([tileMediaKey], E9 M1).
     */
    fun playPause(context: Context, controller: MediaController?, session: MusicSession?, vibb: Boolean) {
        val c = controller ?: return
        when {
            session?.playing == true -> send(context, c, TileAction.PAUSE, vibb)
            usable(context, c.packageName) -> send(context, c, TileAction.PLAY, vibb)
        }
    }

    fun next(context: Context, controller: MediaController?, vibb: Boolean) {
        val c = controller ?: return
        if (usable(context, c.packageName)) send(context, c, TileAction.NEXT, vibb)
    }

    fun previous(context: Context, controller: MediaController?, vibb: Boolean) {
        val c = controller ?: return
        if (usable(context, c.packageName)) send(context, c, TileAction.PREVIOUS, vibb)
    }

    /** Every tile tap's number ([send], main thread): a fallback check acts only for the newest (QA M1). */
    private var tileTap = 0

    /**
     * [action] on [c]: its transport control for Vibb music; for another app the media button
     * ([tileMediaKey]; a skip the session doesn't advertise as the transport control), the transport
     * control instead when the session takes no button, and a play or pause the session hasn't followed
     * after [TILE_FALLBACK_MS] again as the transport control ([tileFallback]; only while no newer tap
     * came, a play only while the app is still usable). Main thread.
     */
    private fun send(context: Context, c: MediaController, action: TileAction, vibb: Boolean) {
        val tap = ++tileTap
        val advertised = runCatching { c.playbackState?.actions ?: 0L }.getOrDefault(0L)
        val key = tileMediaKey(vibb, action, advertised)
        if (key == null || !runCatching { mediaButton(c, key) }.getOrDefault(false)) {
            runCatching { control(c, action) }
            return
        }
        if (action != TileAction.PLAY && action != TileAction.PAUSE) return
        val app = context.applicationContext
        main.postDelayed({
            runCatching {
                val playing = sessionPlay(c.playbackState?.state) == SessionPlay.PLAYING
                if (tileFallback(action, playing, newest = tap == tileTap) && (action == TileAction.PAUSE || usable(app, c.packageName))) control(c, action)
            }
        }, TILE_FALLBACK_MS)
    }

    private fun control(c: MediaController, action: TileAction) {
        val controls = c.transportControls
        when (action) {
            TileAction.PLAY -> controls.play()
            TileAction.PAUSE -> controls.pause()
            TileAction.NEXT -> controls.skipToNext()
            TileAction.PREVIOUS -> controls.skipToPrevious()
        }
    }

    /** A media button's press and release on [c]'s own session (never the system's media-button route): sent once the
     * press was taken - the action runs on it, so a release that fails never makes a skip go twice (QA L-a). */
    private fun mediaButton(c: MediaController, key: Int): Boolean {
        val at = SystemClock.uptimeMillis()
        val down = KeyEvent(at, at, KeyEvent.ACTION_DOWN, key, 0)
        if (!c.dispatchMediaButtonEvent(down)) return false
        runCatching { c.dispatchMediaButtonEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)) }
        return true
    }

    /**
     * The launcher saw packages suspended or unsuspended (`LauncherApps.Callback`, main thread): a
     * music app among them re-renders the shown surfaces, another app with a live session the lock
     * (both look its state up again).
     */
    fun packagesChanged(packages: List<String>?) {
        if (listeners.isEmpty()) return
        val changedApps = packages.orEmpty()
        when {
            changedApps.any { it in trustedMusicNames(com.kidslauncher.mdm.BuildConfig.DEBUG) } -> {
                lockGeneration++
                changed(vibb = true)
            }
            changedApps.any { pkg -> reading.live.any { it.session.packageName == pkg } } -> {
                lockGeneration++
                changed(vibb = false)
            }
        }
    }

    /**
     * Every app's playing sessions now, by package (`sessionPlay` PLAYING: buffering and skips too) -
     * one look at the active sessions for an `apply()` pass that is about to suspend a package (any
     * thread). Without listener access none: nothing is paused, and the music app pauses itself on
     * `MY_PACKAGE_SUSPENDED` (QA #8).
     */
    fun playingSessions(context: Context): Map<String, List<MediaController>> = try {
        val m = context.getSystemService(MediaSessionManager::class.java)
        m?.getActiveSessions(component(context)).orEmpty()
            .filter { sessionPlay(it.playbackState?.state) == SessionPlay.PLAYING }
            .groupBy { it.packageName }
    } catch (e: SecurityException) {
        emptyMap()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the media sessions before a suspension", e)
        emptyMap()
    }

    /**
     * Before `AppEnforcer.apply()` suspends an app (a time rule without it as an exempt app, the
     * used-up budget), its playing [sessions] are paused (21 §3; every app's since 2026-11-04) - a
     * pause only, on that app's own session; any thread. On a Sonos room the session's pause stays a
     * Pause (design 26a §6.1).
     */
    fun pauseBeforeSuspend(sessions: List<MediaController>) {
        for (c in sessions) {
            try {
                c.transportControls.pause()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't pause ${c.packageName} before suspending it", e)
            }
        }
    }

    /** The app can be used now: installed, and not suspended (a time rule, the budget, the allowlist). */
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
        // Security review S-L1: only the player's own activity - never a PendingIntent of another app.
        val intent = controller?.sessionActivity
            ?.takeIf { com.kidslauncher.mdm.lock.intentOfApp(it.creatorPackage, controller.packageName) } ?: return false
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
 * the call rule (design 26a §6.7), the headset watch of the listening window (design 28; its own
 * try, and its stamps don't depend on it) and "the player is in front" ends at every screen-off (21 §3).
 */
object MusicMedia {
    fun init(app: Context) {
        // The call rule in its own try: the flashlight's call edges hang on it too (design 41), so a
        // failure in the volume cap's setup must not take them along.
        try {
            MusicCalls.init(app)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Call-edge setup failed", e)
        }
        try {
            VolumeCap.init(app)
            MusicListening.init(app)
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
