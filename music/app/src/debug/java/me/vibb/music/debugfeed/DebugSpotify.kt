package me.vibb.music.debugfeed

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.model.JSON
import me.vibb.music.spotify.SpotifyEndpoints
import me.vibb.music.spotify.SpotifyRecorder
import me.vibb.music.spotify.SpotifyRecording
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/*
 * Debug build only (src/debug; never in a release APK: `checkReleaseHasNoDebugFeed` and
 * SourceScanTest). The fake Spotify for the emulator (design 26c §9.3, docs/testing/emulator.md §6l,
 * scripts/fake-spotify.py): the one debug switch `SpotifyEndpoints.debugBase` is assigned here and
 * nowhere else, from the debug feed's config.json (`spotify_base`, e.g. "http://10.0.2.2:8731") - by the
 * receiver, and at every process start by [DebugStartup], as DebugStorytel's. Its six readers in
 * SpotifyEndpoints.kt take it only for 10.0.2.2 or loopback, so a config.json left on a real phone never
 * sends a token elsewhere.
 *
 * And the state recorder (design 26c §9.4, step 6; qa-26c-step5b-code, "the state-recording gap"): with
 * `"record_spotify_states": true` in config.json, `SpotifyRecording.recorder` (assigned here and nowhere else)
 * appends one JSON line per posted state - `{"t": <elapsed ms>, "state": <the compact JSON>}` - and per page event
 * or call of ours by name - `{"t": ..., "event": "ready" | "error playback" | "play from 0 to 2 at 0 ms" | "queue
 * 51" | "pause" | "next" ...}` - to `debug-feed/sdk_states/<start time>.jsonl` (adb-pullable, outside the app's
 * data). Never a token (none passes through a state) and never the SDK's error text; never logcat (§7.1).
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

    /** `record_spotify_states` from debug-feed/config.json: the recorder on (a new file) or off. */
    fun applyRecorder(context: Context) {
        val dir = context.getExternalFilesDir("debug-feed") ?: return
        val config = runCatching { JSON.parseToJsonElement(File(dir, "config.json").readText()) as? JsonObject }.getOrNull()
        val on = runCatching { config?.get("record_spotify_states")?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
        if (on == (SpotifyRecording.recorder != null)) return
        val recorder = if (on) StateFile(File(File(dir, "sdk_states"), "${stamp()}.jsonl")) else null
        SpotifyRecording.recorder = recorder
        Log.i(TAG, "spotify: state recording ${recorder?.let { "to sdk_states/${it.file.name}" } ?: "off"}")
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** One JSONL file, appended on its own thread (the renderer calls on the main thread). */
    private class StateFile(val file: File) : SpotifyRecorder {
        private val writer = Executors.newSingleThreadExecutor()

        override fun state(atMs: Long, json: String) {
            // the page's JSON again as one compact line (it parsed as an SdkState already; anything else is dropped)
            val compact = runCatching { JSON.parseToJsonElement(json).toString() }.getOrNull() ?: return
            append("{\"t\":$atMs,\"state\":$compact}")
        }

        override fun event(atMs: Long, name: String) = append("{\"t\":$atMs,\"event\":${JsonPrimitive(name)}}")

        private fun append(line: String) {
            writer.execute {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line + "\n")
                }
            }
        }
    }
}
