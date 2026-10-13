package me.vibb.music.spotify

/*
 * The debug build's state recorder (design 26c §9.4; qa-26c-step5b-code, "the state-recording gap"): real
 * sequences of the SDK's states for `sdk_states/` - §6l part B records them on the emulator with the user's
 * account - never through the log (§7.1: no state JSON in a log line, debug or not). The main source set holds
 * only the hook, null: [SpotifyRecording.recorder] is assigned in `src/debug` alone (`DebugSpotify.kt`, while the
 * debug feed's config.json says `"record_spotify_states": true`), so a release records nothing - it has no debug
 * code (`checkReleaseHasNoDebugFeed`) - and SourceScanTest pins that nothing in main assigns it.
 */

/** Where the renderer's recorded lines go (the debug feed's JSONL file). Called on the main thread; must not block. */
interface SpotifyRecorder {
    /** One posted state at [atMs] (the elapsed clock): the compact JSON as the page posted it (capped, parsed). */
    fun state(atMs: Long, json: String)

    /** A page event or one of our own calls at [atMs], by name only (`ready`, `error playback`, `queue 51`). */
    fun event(atMs: Long, name: String)
}

/** The recorder's one hook: null (nothing recorded) unless the debug build switched it on. */
object SpotifyRecording {
    @Volatile
    var recorder: SpotifyRecorder? = null
}
