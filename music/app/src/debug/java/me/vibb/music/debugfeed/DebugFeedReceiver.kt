package me.vibb.music.debugfeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.data.Store
import me.vibb.music.model.JSON
import me.vibb.music.sync.Inbox
import me.vibb.music.sync.Sync
import java.io.File

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and
 * DebugFeedAbsentTest). Design 21 §6 step 2: until step 3's bridge, the app is fed by hand. Push a
 * feed into the app's external files dir and broadcast to this receiver (guarded by DUMP, which
 * the shell holds and apps don't):
 *
 *   debug-feed/library.json           the library (the server's /music/library, or its snapshot)
 *   debug-feed/items/<entry id>.json  listings (/music/entries/<id>/items)
 *   debug-feed/files/<file id>        own files' audio (size + SHA-256 checked against the library)
 *   debug-feed/covers/<hash>          cover JPEGs (checked against their hash)
 *   debug-feed/config.json            optional: {"mobile_data": true}
 *
 *   adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
 *   adb shell am broadcast -n me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
 *
 * It goes through the same checks the bridge will (Inbox) and then runs the sync at once.
 * docs/testing/emulator.md has the steps.
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
        val libraryFile = File(dir, "library.json")
        if (libraryFile.isFile) {
            val ok = Inbox.putLibrary(store, libraryFile.readText())
            Log.i(TAG, "library: ${if (ok) "loaded" else "refused (not a v1 library)"}")
        }
        val library = store.library.value ?: run {
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

    private companion object {
        const val TAG = "VibbMusicDebugFeed"
    }
}
