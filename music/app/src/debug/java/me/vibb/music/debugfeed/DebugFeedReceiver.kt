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
 *   debug-feed/covers/<hash>          cover JPEGs (checked against their hash)
 *   debug-feed/config.json            optional: {"mobile_data": true, "storytel_base": "http://10.0.2.2:8730"}
 *                                     (storytel_base: the fake Storytel, design 26b §4.6 - the one debug
 *                                     switch; also read at every process start, DebugStartup)
 *   debug-feed/storytel.json          optional: {"email": "k@example.invalid", "password": "hemmelig"} - FAKE
 *                                     values only: seeds a Storytel login for a run without a launcher (the
 *                                     next generation, so a re-seed ends a cooldown like a new save), and is
 *                                     deleted once read (it sits on shared storage; design 26b §4.6, QA L13)
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
 * docs/testing/emulator.md §6f has the steps (§6g for Sonos).
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
            config?.get("mobile_data")?.jsonPrimitive?.booleanOrNull?.let { store.mobileData = it }
        }
        DebugStorytel.applyBase(context)
        File(dir, "storytel.json").takeIf { it.isFile }?.let { f -> DebugStorytel.seedLogin(context, f) }
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
