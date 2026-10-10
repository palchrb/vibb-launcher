package com.kidslauncher.mdm.music

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.ui.resolveLauncherLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.security.MessageDigest

private const val LOG_TAG = "MusicSync"

/** Size limits (21 §3, 21b §5): the library, one listing, one cover. */
const val MAX_LIBRARY_BYTES = 4 * 1024 * 1024
const val MAX_LISTING_BYTES = 2 * 1024 * 1024
const val MAX_COVER_BYTES = 10 * 1024 * 1024

/**
 * The music part of the launcher's sync (design 21 §3, 21b §5), after the wallpapers and only with a
 * music app installed: a new `library_version` -> the library (If-None-Match, ≤ 4 MB, `v == 1`);
 * the listings whose version changed (≤ 2 MB each, any version stored), the ones it no longer names
 * deleted; the covers; the own files queued (MusicFileDownloads); then the managed config and one
 * nudge. A failure keeps what is stored and is tried again at the next sync - it never touches the
 * policy. The launcher never talks to NRK or RSS hosts.
 */
object MusicSync {
    suspend fun sync(context: Context, policy: PolicyResponse?, fetch: Boolean) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val pkg = MusicStore.installedPackage(app) ?: return@withContext
        val music = musicPolicy(policy?.music)
        var changed = false
        if (fetch && music != null) {
            try {
                changed = fetchAll(app, music)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Music sync failed - the next sync tries again", e)
            }
            MusicFileDownloads.request(app)
        }
        val restrictionsChanged = writeRestrictions(app, policy, music)
        if (changed || restrictionsChanged) nudge(app, pkg)
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
            val response = server.library(stored?.version)
            when {
                response.code() == 304 -> response.body()?.close()
                response.code() == 404 -> {
                    response.body()?.close()
                    if (MusicStore.libraryFile(app).delete()) changed = true
                }
                response.isSuccessful -> {
                    val bytes = response.body()?.let { read(it, MAX_LIBRARY_BYTES) }
                    if (bytes != null && libraryHead(String(bytes)) != null) {
                        MusicStore.writeAtomic(MusicStore.libraryFile(app), bytes)
                        changed = true
                    } else {
                        Log.w(LOG_TAG, "The library isn't a v1 library of at most 4 MB - keeping the old one")
                    }
                }
                else -> {
                    response.body()?.close()
                    Log.w(LOG_TAG, "Library: HTTP ${response.code()}")
                }
            }
        }
        val head = MusicStore.head(app)
        val listings = MusicStore.listings(app)
        for (id in listingsToDelete(head, MusicStore.itemsDir(app).listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }.toSet())) {
            if (MusicStore.listingFile(app, id).delete()) changed = true
        }
        if (head == null) {
            // No library: nothing of it stays (covers, files waiting for the music app, partials).
            MusicStore.coversDir(app).listFiles().orEmpty().forEach { it.delete() }
            MusicStore.filesDir(app).listFiles().orEmpty().forEach { it.delete() }
            MusicStore.setReady(app, emptyMap())
        } else {
            for (id in listingsToFetch(head, listings)) {
                val response = server.listing(id, listings[id])
                if (response.isSuccessful && response.code() != 304) {
                    val bytes = response.body()?.let { read(it, MAX_LISTING_BYTES) }
                    if (bytes != null && listingVersionOf(String(bytes)) != null) {
                        MusicStore.writeAtomic(MusicStore.listingFile(app, id), bytes)
                        changed = true
                    }
                } else {
                    response.body()?.close()
                    if (response.code() != 304) Log.w(LOG_TAG, "Listing $id: HTTP ${response.code()}")
                }
            }
            changed = syncCovers(app, server, head) || changed
            val stale = readyFilesToDelete(head, MusicStore.ready(app))
            if (stale.isNotEmpty()) {
                stale.forEach { MusicStore.readyFile(app, it).delete() }
                MusicStore.setReady(app, MusicStore.ready(app) - stale)
            }
        }
        return changed
    }

    private suspend fun syncCovers(app: Context, server: MusicServer, head: LibraryHead): Boolean {
        var changed = false
        MusicStore.coversDir(app).listFiles().orEmpty().filter { it.name !in head.covers }.forEach { it.delete() }
        for (hash in head.covers) {
            val file = MusicStore.coverFile(app, hash)
            if (file.isFile) continue
            val response = server.cover(hash)
            if (!response.isSuccessful) {
                response.body()?.close()
                continue
            }
            val bytes = response.body()?.let { read(it, MAX_COVER_BYTES) }
            if (bytes != null && sha256(bytes) == hash) {
                MusicStore.writeAtomic(file, bytes)
                changed = true
            }
        }
        return changed
    }

    /** At most [max] bytes of [body], or null when it is longer. */
    private fun read(body: ResponseBody, max: Int): ByteArray? = body.use {
        val bytes = it.byteStream().readNBytes(max + 1)
        bytes.takeIf { b -> b.size <= max }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
                music == null -> continue // an older server: keep what is there
                else -> bundleOf(restrictionsFor(music, app.packageName, resolveLauncherLocale(policy.launcherUi?.language)))
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

    private fun bundleOf(values: Map<String, Any>): Bundle = Bundle().apply {
        for ((k, v) in values) {
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is String -> putString(k, v)
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

    /** The bridge's nudge (21 §2): an explicit broadcast that only starts the music app's sync. */
    fun nudge(context: Context, pkg: String? = MusicStore.installedPackage(context)) {
        pkg ?: return
        context.sendBroadcast(Intent(ACTION_BRIDGE_CHANGED).setPackage(pkg))
    }
}
