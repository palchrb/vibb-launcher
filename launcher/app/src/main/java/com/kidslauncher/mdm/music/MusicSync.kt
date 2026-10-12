package com.kidslauncher.mdm.music

import android.Manifest
import android.app.BroadcastOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.AppDownloads
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.ui.resolveLauncherLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.IOException
import java.security.MessageDigest

private const val LOG_TAG = "MusicSync"

/** Size limits (21 §3, 21b §5): the library, one listing, one cover. */
const val MAX_LIBRARY_BYTES = 4 * 1024 * 1024
const val MAX_LISTING_BYTES = 2 * 1024 * 1024
const val MAX_COVER_BYTES = 10 * 1024 * 1024

/**
 * The music part of the launcher's sync (design 21 §3, 21b §5), only with a music app installed:
 * inside the sync only what needs no network - the managed config (written when it differs,
 * cleared without a policy) and, when it changed, the nudge. The fetch (the library on a new
 * `library_version`, changed listings, covers, then the own files) runs after the sync, in its own
 * one-at-a-time runner ([MusicCourier]), so a big first fill never holds a parent's lock or ring
 * behind it (qa-21-step3-code M5). The launcher never talks to NRK or RSS hosts.
 */
object MusicSync {
    suspend fun sync(context: Context, policy: PolicyResponse?, fetch: Boolean) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val pkg = MusicStore.installedPackage(app)
        if (pkg == null) {
            MusicStore.clearAll(app)
            return@withContext
        }
        val music = musicPolicy(policy?.music)
        if (writeRestrictions(app, policy, music)) nudge(app, pkg)
        grantNotifications(app, managed = policy != null)
        if (fetch && music != null) MusicCourier.request(app, music)
    }

    /**
     * The music app's "N nye i Musikk" notification is what Home's badge counts (21 QA #6): while a
     * policy exists the device owner grants `POST_NOTIFICATIONS` (no dialog for the kid); without
     * one the choice goes back to the phone ([notificationGrantState]).
     */
    private fun grantNotifications(app: Context, managed: Boolean) {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(app.packageName)) return
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        val wanted = notificationGrantState(managed)
        for (pkg in MUSIC_PACKAGES.filter { MusicStore.isInstalled(app, it) }) {
            try {
                if (dpm.getPermissionGrantState(admin, pkg, Manifest.permission.POST_NOTIFICATIONS) != wanted) {
                    dpm.setPermissionGrantState(admin, pkg, Manifest.permission.POST_NOTIFICATIONS, wanted)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't set the music app's notification permission", e)
            }
        }
    }

    /**
     * The managed config (21 §2), for each installed music app: written when it differs, cleared
     * when the phone has no policy. Only the device owner writes it. True when it changed.
     */
    private fun writeRestrictions(app: Context, policy: PolicyResponse?, music: MusicPolicy?): Boolean {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(app.packageName)) return false
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        var changed = false
        for (pkg in MUSIC_PACKAGES.filter { MusicStore.isInstalled(app, it) }) {
            val desired = when {
                policy == null -> Bundle()
                music == null -> continue // an older or newer server: keep what is there
                else -> restrictionsBundle(restrictionsFor(music, app.packageName, resolveLauncherLocale(policy.launcherUi?.language)))
            }
            val current = try {
                dpm.getApplicationRestrictions(admin, pkg)
            } catch (e: Exception) {
                continue
            }
            if (!sameBundle(current, desired)) {
                try {
                    dpm.setApplicationRestrictions(admin, pkg, desired)
                    changed = true
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't write the music app's restrictions", e)
                }
            }
        }
        return changed
    }

    /** [values] as a Bundle: Boolean, Int and String only (`restrictionValueStorable`, H1). */
    fun restrictionsBundle(values: Map<String, Any>): Bundle = Bundle().apply {
        for ((k, v) in values) {
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is String -> putString(k, v)
                else -> throw IllegalArgumentException("$k: ${v.javaClass.simpleName} isn't stored by setApplicationRestrictions")
            }
        }
    }

    @Suppress("DEPRECATION") // Bundle.get: the values are plain booleans, numbers and strings
    private fun sameBundle(a: Bundle, b: Bundle): Boolean =
        a.keySet() == b.keySet() && a.keySet().all { a.get(it) == b.get(it) }

    /** The parent's mobile-data switch for music, as the cached policy says. */
    fun mobileData(): Boolean = try {
        musicPolicy(currentPolicyDecision().policy?.music)?.mobileData == true
    } catch (e: Exception) {
        false
    }

    /**
     * The bridge's nudge (21 §2): an explicit broadcast that only starts the music app's sync. It
     * shares the launcher's identity, which the music app checks (qa-21-step3-code L7), and reaches a
     * just-installed app never opened yet (L10).
     */
    fun nudge(context: Context, pkg: String? = MusicStore.installedPackage(context)) {
        pkg ?: return
        val intent = Intent(ACTION_BRIDGE_CHANGED).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * The courier's fetches (21 §3, 21b §5), one run at a time after the sync: the library, changed
 * listings (≤ 2 MB each, any version stored, the unnamed ones deleted), covers (hash-checked), stale
 * ready files deleted, then the own files. A request that fails is logged and the next one goes on
 * (L9); what was written still nudges the music app. A failure keeps what is stored and is tried
 * again after the next sync.
 */
object MusicCourier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var again: MusicPolicy? = null

    fun request(context: Context, music: MusicPolicy) {
        val app = context.applicationContext
        synchronized(this) {
            if (running) {
                again = music
                return
            }
            running = true
        }
        scope.launch { run(app, music) }
    }

    private suspend fun run(app: Context, first: MusicPolicy) {
        var music: MusicPolicy? = first
        while (music != null) {
            try {
                if (fetchAll(app, music)) MusicSync.nudge(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Music fetch failed - the next sync tries again", e)
            }
            MusicFileDownloads.request(app)
            music = synchronized(this) {
                val next = again
                again = null
                if (next == null) running = false
                next
            }
        }
    }

    private suspend fun fetchAll(app: Context, music: MusicPolicy): Boolean {
        val mdm = LauncherPreferences.mdm()
        val url = mdm.serverUrl()
        val token = mdm.deviceToken()
        if (url.isNullOrBlank() || token.isNullOrBlank()) return false
        val server = createMusicServer(url, token)
        var changed = false
        val stored = MusicStore.head(app)
        if (music.libraryVersion == null) {
            // Nothing ticked on this phone: the music app gets an empty library from the restrictions.
            if (MusicStore.libraryFile(app).delete()) changed = true
        } else if (music.libraryVersion != stored?.version) {
            changed = fetchLibrary(app, server, stored?.version) || changed
        }
        val head = MusicStore.head(app)
        val onDisk = MusicStore.itemsDir(app).listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }.toSet()
        for (id in listingsToDelete(head, onDisk)) {
            if (MusicStore.deleteListing(app, id)) changed = true
        }
        if (head == null) {
            // No library: nothing of it stays (covers, pictures, files waiting for the music app, partials).
            MusicStore.clearWantArt(app)
            MusicStore.coversDir(app).listFiles().orEmpty().forEach { it.delete() }
            MusicStore.filesDir(app).listFiles().orEmpty().forEach { it.delete() }
            MusicStore.setReady(app, emptyMap())
            return changed
        }
        val listings = MusicStore.listings(app)
        for (id in listingsToFetch(head, listings)) {
            changed = fetchListing(app, server, id, listings[id]) || changed
        }
        changed = syncCovers(app, server, head) || changed
        changed = syncArt(app, server, music) || changed
        val ready = MusicStore.ready(app)
        val stale = readyFilesToDelete(head, ready, MusicStore.handedOver(app))
        if (stale.isNotEmpty()) {
            stale.forEach { MusicStore.readyFile(app, it).delete() }
            MusicStore.setReady(app, ready - stale)
        }
        return changed
    }

    private suspend fun fetchLibrary(app: Context, server: MusicServer, have: String?): Boolean = guarded("library") {
        val response = server.library(have)
        when {
            response.code() == 304 -> {
                response.body()?.close()
                false
            }
            response.code() == 404 -> {
                response.body()?.close()
                MusicStore.libraryFile(app).delete()
            }
            response.isSuccessful -> {
                val bytes = response.body()?.let { read(it, MAX_LIBRARY_BYTES) }
                if (bytes != null && libraryHead(String(bytes)) != null) {
                    MusicStore.writeAtomic(MusicStore.libraryFile(app), bytes)
                    true
                } else {
                    Log.w(LOG_TAG, "The library isn't a v1 library of at most 4 MB - keeping the old one")
                    false
                }
            }
            else -> {
                response.body()?.close()
                Log.w(LOG_TAG, "Library: HTTP ${response.code()}")
                false
            }
        }
    }

    private suspend fun fetchListing(app: Context, server: MusicServer, id: Long, have: String?): Boolean = guarded("listing $id") {
        val response = server.listing(id, have)
        if (response.isSuccessful && response.code() != 304) {
            val bytes = response.body()?.let { read(it, MAX_LISTING_BYTES) }
            val version = bytes?.let { listingVersionOf(String(it)) }
            if (bytes != null && version != null) {
                MusicStore.putListing(app, id, version, bytes)
                true
            } else {
                false
            }
        } else {
            response.body()?.close()
            if (response.code() != 304) Log.w(LOG_TAG, "Listing $id: HTTP ${response.code()}")
            false
        }
    }

    private suspend fun syncCovers(app: Context, server: MusicServer, head: LibraryHead): Boolean {
        var changed = false
        // A cover the library no longer names goes, unless the music app wants it as an episode picture.
        val present = MusicStore.coversDir(app).list().orEmpty().toSet()
        artToDelete(present, head.covers, MusicStore.wantArt(app)).forEach { MusicStore.coverFile(app, it).delete() }
        for (hash in head.covers) {
            if (MusicStore.coverFile(app, hash).isFile) continue
            changed = (fetchCover(app, server, hash, MAX_COVER_BYTES) == true) || changed
        }
        return changed
    }

    /** The pictures fetchable now (design 27 §2.2, QA M6): what [artToFetch] allows on this network. */
    fun artFetchable(app: Context, want: WantArt, music: MusicPolicy): List<String> {
        val caps = AppDownloads.currentCaps(app)
        return artToFetch(
            want,
            has = MusicStore.coversDir(app).list().orEmpty().toSet(),
            failures = MusicStore.artFailures(app),
            online = caps != null && caps.internet && caps.physical,
            unmetered = caps?.notMetered == true,
            mobileData = music.mobileData && caps?.notRoaming == true,
            max = ART_PER_RUN,
            nowMs = System.currentTimeMillis(),
        )
    }

    /**
     * Episode pictures (design 27 §2.2), after the covers: the music app's want set, through the
     * cover route (≤ 1 MB, hash-checked), at most [ART_PER_RUN], `now` first. An HTTP error or a wrong
     * hash counts toward the day's wait; a network error doesn't (it is tried again with the next run).
     * Art in neither the library's covers nor the want set goes: the launcher holds a picture only
     * until the music app has copied it (its next want set leaves it out).
     */
    private suspend fun syncArt(app: Context, server: MusicServer, music: MusicPolicy): Boolean {
        val want = MusicStore.wantArt(app)
        var changed = false
        for (hash in artFetchable(app, want, music)) {
            when (fetchCover(app, server, hash, MAX_ART_BYTES)) {
                true -> {
                    MusicStore.clearArtFailure(app, hash)
                    changed = true
                }
                false -> MusicStore.recordArtFailure(app, hash, System.currentTimeMillis())
                null -> Unit
            }
        }
        val head = MusicStore.head(app)
        val present = MusicStore.coversDir(app).list().orEmpty().toSet()
        artToDelete(present, head?.covers.orEmpty(), MusicStore.wantArt(app)).forEach { MusicStore.coverFile(app, it).delete() }
        return changed
    }

    /** One cover or picture: true = stored, false = refused (HTTP error, too big, wrong hash), null = a network error. */
    private suspend fun fetchCover(app: Context, server: MusicServer, hash: String, max: Int): Boolean? = try {
        val response = server.cover(hash)
        if (!response.isSuccessful) {
            response.body()?.close()
            false
        } else {
            val bytes = response.body()?.let { read(it, max) }
            if (bytes != null && MusicSync.sha256(bytes) == hash) {
                MusicStore.writeAtomic(MusicStore.coverFile(app, hash), bytes)
                true
            } else {
                false
            }
        }
    } catch (e: IOException) {
        Log.w(LOG_TAG, "cover $hash: ${e.javaClass.simpleName} - tried again after the next sync")
        null
    }

    /** One request: a network error is logged and the fetch goes on with the next (L9). */
    private suspend fun guarded(what: String, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: IOException) {
        Log.w(LOG_TAG, "$what: ${e.javaClass.simpleName} - tried again after the next sync")
        false
    }

    /** At most [max] bytes of [body], or null when it is longer. */
    private fun read(body: ResponseBody, max: Int): ByteArray? = body.use {
        val bytes = it.byteStream().readNBytes(max + 1)
        bytes.takeIf { b -> b.size <= max }
    }
}
