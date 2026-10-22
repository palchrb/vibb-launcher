package me.vibb.music.debugfeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.data.Store
import me.vibb.music.model.JSON
import me.vibb.music.sonos.SONOS_PORT
import me.vibb.music.sonos.SonosFiles
import me.vibb.music.sonos.SpeakerCache
import me.vibb.music.sonos.Zone
import me.vibb.music.sync.Inbox
import me.vibb.music.sync.Sync
import java.io.File

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and
 * SourceScanTest). Design 21 §6 step 2: until step 3's bridge, the app is fed by hand. Push a
 * feed into the app's external files dir and broadcast to this receiver (guarded by DUMP, which
 * the shell holds and apps don't):
 *
 *   debug-feed/library.json           the library (the server's /music/library, or its snapshot)
 *   debug-feed/items/<entry id>.json  listings (/music/entries/<id>/items)
 *   debug-feed/files/<file id>        own files' audio (size + SHA-256 checked against the library)
 *   debug-feed/covers/<hash>          cover JPEGs and episode pictures (checked against their hash)
 *   debug-feed/config.json            optional: {"stream_mobile_data": true, "download_mobile_data": false,
 *                                     "storage_limit_gb": 2, "storytel_base": "http://10.0.2.2:8730",
 *                                     "spotify_base": "http://10.0.2.2:8731", "record_spotify_states": true}
 *                                     (design 30: the two switches - a bare "mobile_data" sets both - and
 *                                     the storage limit in GB, 0 = none; storytel_base: the fake Storytel,
 *                                     design 26b §4.6; spotify_base: the fake Spotify, design 26c §9.3 - each
 *                                     the one debug switch of its source; also read at every process start,
 *                                     DebugStartup; record_spotify_states: the web player's
 *                                     states and events into debug-feed/sdk_states/<start>.jsonl, design 26c
 *                                     §9.4, DebugSpotify)
 *   debug-feed/storytel.json          a fake login from before design 58: deleted unread (the phone takes no
 *                                     login; an unmanaged run with storytel_base asks the fake's /fake/jwt)
 *   debug-feed/sonos.json             optional: Sonos speakers merged into the uid -> ip cache, for the
 *                                     emulator's fake speaker (no multicast there, so no SSDP; design 26a
 *                                     §8 step 1): {"players": [{"uid": "RINCON_FAKE0140101400", "name": "Stua",
 *                                     "ip": "10.0.2.2", "port": 1401}]}
 *
 *   adb shell am broadcast -n me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver  (creates the folder)
 *   adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
 *   adb shell am broadcast -n me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
 *
 * It goes through the same checks the bridge will (Inbox) and then runs the sync at once.
 * docs/testing/emulator.md §6f has the steps (§6h for Sonos, §6i for Storytel).
 */
class DebugFeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                load(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "debug feed failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun load(context: Context) {
        val dir = context.getExternalFilesDir("debug-feed") ?: return
        val store = Store.get(context)
        File(dir, "config.json").takeIf { it.isFile }?.let { f ->
            val config = JSON.parseToJsonElement(f.readText()) as? JsonObject
            // Design 30: the two switches and the storage limit (GB, 0 = none); a bare mobile_data (an
            // old script) sets both switches, a key of their own wins.
            config?.get("mobile_data")?.jsonPrimitive?.booleanOrNull?.let {
                store.streamMobileData = it
                store.downloadMobileData = it
            }
            config?.get("stream_mobile_data")?.jsonPrimitive?.booleanOrNull?.let { store.streamMobileData = it }
            config?.get("download_mobile_data")?.jsonPrimitive?.booleanOrNull?.let { store.downloadMobileData = it }
            config?.get("storage_limit_gb")?.jsonPrimitive?.intOrNull?.let { store.storageLimitGb = it }
        }
        DebugStorytel.applyBase(context)
        DebugSpotify.applyBase(context)
        DebugSpotify.applyRecorder(context)
        File(dir, "storytel.json").takeIf { it.isFile }?.let { f -> DebugStorytel.dropLogin(f) }
        File(dir, "sonos.json").takeIf { it.isFile }?.let { f -> seedSonos(context, f) }
        val libraryFile = File(dir, "library.json")
        if (libraryFile.isFile) {
            val ok = Inbox.putLibrary(store, libraryFile.readText())
            Log.i(TAG, "library: ${if (ok) "loaded" else "refused (not a v1 library)"}")
        }
        val library = store.library() ?: run {
            Log.w(TAG, "no library yet")
            return
        }
        File(dir, "items").listFiles().orEmpty().forEach { f ->
            val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@forEach
            Inbox.putListing(store, id, f.readText())
            Log.i(TAG, "listing for entry $id queued")
        }
        File(dir, "covers").listFiles().orEmpty().forEach { f ->
            val ok = f.inputStream().use { Inbox.putCover(store, f.name, it) }
            Log.i(TAG, "cover ${f.name}: ${if (ok) "stored" else "refused (hash)"}")
        }
        for (file in library.files) {
            val f = File(dir, "files/${file.id}")
            if (!f.isFile) continue
            val ok = f.inputStream().use { Inbox.putOwnFile(store, file, it) }
            Log.i(TAG, "own file ${file.id}: ${if (ok) "stored" else "refused (size or SHA-256)"}")
        }
        Sync.nudge(context, settleS = 0)
        Log.i(TAG, "sync queued (${library.entries.size} entries, ${library.files.size} files)")
    }

    /** The fake speaker's rooms into the cache (merged, as an SSDP round would; groups untouched). */
    private fun seedSonos(context: Context, f: File) {
        val root = JSON.parseToJsonElement(f.readText()) as? JsonObject
        val players = (root?.get("players") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val uid = o["uid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val ip = o["ip"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Zone(uid, ip, o["port"]?.jsonPrimitive?.intOrNull ?: SONOS_PORT, o["name"]?.jsonPrimitive?.contentOrNull)
        }
        SpeakerCache(SonosFiles.cache(context)).seed(players)
        Log.i(TAG, "sonos: ${players.size} speaker(s) seeded into the cache")
    }

    private companion object {
        const val TAG = "VibbMusicDebugFeed"
    }
}
