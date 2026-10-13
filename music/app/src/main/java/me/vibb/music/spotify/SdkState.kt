package me.vibb.music.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The web player's state as the page posts it (design 26c §5.3): `player.js` compacts the SDK's
 * `WebPlaybackState` into `{"paused", "position", "duration", "uri", "linked", "type", "name",
 * "artists", "next", "prev", "context"}` (at most 8 KB), or `null` when this player isn't the active
 * device. Parsed here, every field capped; a post that isn't one of the two is junk and ignored.
 * Pure; SdkStateTest.
 */

/** What one post of the page says. */
sealed interface SdkSnapshot

/** `null`: this player isn't Spotify's active device (another device plays, or nothing does). */
data object Inactive : SdkSnapshot

/** The SDK's state of this player. */
data class SdkState(
    val paused: Boolean,
    /** As the SDK measured it; never extrapolated here. */
    val positionMs: Long,
    /** Null when the SDK gave none (0 or missing). */
    val durationMs: Long?,
    /** The current track's uri (`spotify:track:<id>`, `spotify:episode:<id>`), null when it gave none or junk. */
    val uri: String?,
    /** The uri Spotify relinked from (`linked_from`; undocumented in the SDK, gone from the Web API). */
    val linkedUri: String?,
    /** `track_window.next_tracks` / `previous_tracks`, at most [NEIGHBOURS] each. */
    val nextUris: List<String>,
    val prevUris: List<String>,
    /** `track`, `episode` or `ad`; null for anything else. */
    val type: String?,
    val name: String?,
    val artists: List<String>,
    /** The context's uri (a playlist or an artist in context mode, design 26c §5.9); null for a list of uris. */
    val contextUri: String?,
) : SdkSnapshot {
    /** The track's title as the server builds the listing's (§3.4): `"<name> — <artist>, <artist>"`. */
    val title: String? get() = name?.let { listingTitle(it, artists) }

    companion object {
        /** The longest post taken (§5.3: `state(json)` at most 8 KB). */
        const val MAX_BYTES = 8 * 1024

        /** At most this many next and previous uris. */
        const val NEIGHBOURS = 3

        private const val NAME_MAX = 200
        private const val ARTISTS_MAX = 10

        /** A position or duration past a day is junk. */
        private const val TIME_MAX = 24L * 3_600_000L

        /** The keys of a posted state, exactly as `player.js` writes them (PlayerJsTest). */
        val KEYS = listOf("paused", "position", "duration", "uri", "linked", "type", "name", "artists", "next", "prev", "context")

        private val TYPES = setOf("track", "episode", "ad")
        private val URI = Regex("spotify:[a-z]{1,16}:[A-Za-z0-9]{1,64}")
        private val CONTEXT = Regex("spotify:[A-Za-z0-9:._-]{1,200}")
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * One post ([text], as `JSON.stringify` wrote it): [Inactive] for `null`, an [SdkState] for an
         * object with a boolean `paused` and a numeric `position`, else null (junk, too long, anything
         * else) - the caller ignores it.
         */
        fun parse(text: String?): SdkSnapshot? {
            if (text == null || text.length > MAX_BYTES) return null
            val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() ?: return null
            if (root is JsonNull) return Inactive
            if (root !is JsonObject) return null
            val paused = (root["paused"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            val position = time(root["position"]) ?: return null
            return SdkState(
                paused = paused,
                positionMs = position,
                durationMs = time(root["duration"])?.takeIf { it > 0 },
                uri = uri(root["uri"]),
                linkedUri = uri(root["linked"]),
                nextUris = uris(root["next"]),
                prevUris = uris(root["prev"]),
                type = text(root["type"])?.takeIf { it in TYPES },
                name = text(root["name"])?.let { cut(it, NAME_MAX) },
                artists = (root["artists"] as? JsonArray).orEmpty().mapNotNull { a -> text(a)?.let { cut(it, NAME_MAX) } }.take(ARTISTS_MAX),
                contextUri = text(root["context"])?.takeIf { CONTEXT.matches(it) },
            )
        }

        private fun text(e: Any?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun uri(e: Any?): String? = text(e)?.takeIf { URI.matches(it) }

        private fun uris(e: Any?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { uri(it) }.take(NEIGHBOURS)

        private fun time(e: Any?): Long? {
            val d = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            if (d.isNaN() || d < 0) return null
            return minOf(d.toLong(), TIME_MAX)
        }

        private fun cut(s: String, max: Int): String = if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))
    }
}

/**
 * The server's listing title (`spotify::track_title` then `music::cut_title(.., 200)`): `"<name> —
 * <artist>, <artist>"` (the name alone without artists), whitespace runs collapsed to one space and
 * trimmed, control characters dropped, at most 200 characters. The tracker compares an SDK state's
 * title with a listed item's through it (§5.4: a relinked copy matched by title and duration).
 */
fun listingTitle(name: String, artists: List<String>): String {
    val names = artists.map { it.trim() }.filter { it.isNotEmpty() }
    val raw = if (names.isEmpty()) name.trim() else "${name.trim()} — ${names.joinToString(", ")}"
    return cutTitle(raw)
}

/**
 * `music::cut_title(input, 200)` exactly: split on Unicode White_Space (Rust's `split_whitespace`), joined
 * by one space, control characters dropped, at most [max] code points.
 */
fun cutTitle(input: String, max: Int = 200): String {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var i = 0
    while (i < input.length) {
        val cp = input.codePointAt(i)
        i += Character.charCount(cp)
        if (whiteSpace(cp)) {
            if (word.isNotEmpty()) words += word.toString().also { word.clear() }
        } else {
            word.appendCodePoint(cp)
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    val out = StringBuilder()
    var count = 0
    val joined = words.joinToString(" ")
    var j = 0
    while (j < joined.length && count < max) {
        val cp = joined.codePointAt(j)
        j += Character.charCount(cp)
        if (Character.getType(cp) == Character.CONTROL.toInt()) continue
        out.appendCodePoint(cp)
        count++
    }
    return out.toString()
}

/** Unicode's White_Space property (Rust's `char::is_whitespace`), which neither of Java's checks is. */
private fun whiteSpace(cp: Int): Boolean =
    cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000
