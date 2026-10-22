package me.vibb.music.debugfeed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.model.JSON
import me.vibb.music.storytel.StorytelEndpoints
import java.io.File

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and
 * SourceScanTest). The fake Storytel for the emulator (design 26b §4.6, docs/testing/emulator.md §6i):
 * the one debug switch `StorytelEndpoints.debugBase` is assigned here and nowhere else, from the
 * debug feed's config.json - by the receiver, and at every process start by [DebugStartup] (the
 * download worker often runs in a fresh process, which must ask the fake too, never the real
 * Storytel).
 */
object DebugStorytel {
    private const val TAG = "VibbMusicDebugFeed"

    /** `storytel_base` from debug-feed/config.json, or none (no file, no key): the real hosts. */
    fun applyBase(context: Context) {
        val dir = context.getExternalFilesDir("debug-feed") ?: return
        val config = runCatching { JSON.parseToJsonElement(File(dir, "config.json").readText()) as? JsonObject }.getOrNull()
        val base = config?.get("storytel_base")?.jsonPrimitive?.contentOrNull
        if (StorytelEndpoints.debugBase != base) {
            StorytelEndpoints.debugBase = base
            Log.i(TAG, "storytel: ${base ?: "the real hosts"}")
        }
    }

    /**
     * `storytel.json`, a fake login from before design 58: the phone no longer takes a login (an unmanaged run asks
     * the fake's `/fake/jwt`, design 58 §5.6), so it is deleted unread - it sits on shared storage - with one fixed line.
     */
    fun dropLogin(f: File) {
        f.delete()
        Log.w(TAG, "storytel.json: the phone takes no login since design 58 - deleted unread")
    }
}

/**
 * Assigns the debug switches at process start, before any worker or service runs (a debug-only
 * provider): Storytel's (design 26b §4.6) and Spotify's (design 26c §9.3, [DebugSpotify]), and Spotify's state
 * recorder (design 26c §9.4).
 */
class DebugStartup : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { runCatching { DebugStorytel.applyBase(it) } }
        context?.let { runCatching { DebugSpotify.applyBase(it) } }
        context?.let { runCatching { DebugSpotify.applyRecorder(it) } }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
