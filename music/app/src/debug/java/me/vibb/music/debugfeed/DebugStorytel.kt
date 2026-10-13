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
import me.vibb.music.bridge.StorytelLogin
import me.vibb.music.model.JSON
import me.vibb.music.storytel.StorytelEndpoints
import java.io.File

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and
 * SourceScanTest). The fake Storytel for the emulator (design 26b §4.6, docs/testing/emulator.md §6i):
 * the one debug switch `StorytelEndpoints.debugBase` is assigned here and nowhere else, from the
 * debug feed's config.json - by the receiver, and at every process start by [DebugStartup] (the
 * download worker often runs in a fresh process, which must not reach the real Storytel with the
 * fake's login).
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
     * A login for a run without a launcher: `{"email", "password"}`, fake values only, stored as the next
     * generation, then the file deleted (QA L13). The email is never logged; a malformed file is one fixed
     * line, and the rest of the feed still applies.
     */
    fun seedLogin(context: Context, f: File) {
        try {
            // Never the parser's message: it quotes the input, the password included (qa-26b-step3-code L1).
            val o = runCatching { JSON.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
            val email = runCatching { o?.get("email")?.jsonPrimitive?.contentOrNull }.getOrNull()
            val password = runCatching { o?.get("password")?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (email == null || password == null) {
                Log.w(TAG, "storytel.json: not a JSON object with an email and a password - ignored")
                return
            }
            val generation = StorytelLogin.generation(context) + 1
            StorytelLogin.store(context, generation, email, password)
            Log.i(TAG, "storytel: a login seeded (generation $generation)")
        } finally {
            f.delete()
        }
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
