package me.vibb.music.debugfeed

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.model.JSON
import me.vibb.music.spotify.SpotifyEndpoints
import java.io.File

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and
 * SourceScanTest). The fake Spotify for the emulator (design 26c §9.3, docs/testing/emulator.md §6l,
 * scripts/fake-spotify.py): the one debug switch `SpotifyEndpoints.debugBase` is assigned here and
 * nowhere else, from the debug feed's config.json (`spotify_base`, e.g. "http://10.0.2.2:8731") - by the
 * receiver, and at every process start by [DebugStartup], as DebugStorytel's. Its six readers in
 * SpotifyEndpoints.kt take it only for 10.0.2.2 or loopback, so a config.json left on a real phone never
 * sends a token elsewhere.
 */
object DebugSpotify {
    private const val TAG = "VibbMusicDebugFeed"

    /** `spotify_base` from debug-feed/config.json, or none (no file, no key): Spotify's own hosts. */
    fun applyBase(context: Context) {
        val dir = context.getExternalFilesDir("debug-feed") ?: return
        val config = runCatching { JSON.parseToJsonElement(File(dir, "config.json").readText()) as? JsonObject }.getOrNull()
        val base = runCatching { config?.get("spotify_base")?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (SpotifyEndpoints.debugBase != base) {
            SpotifyEndpoints.debugBase = base
            Log.i(TAG, "spotify: ${base ?: "the real hosts"}")
        }
    }
}
