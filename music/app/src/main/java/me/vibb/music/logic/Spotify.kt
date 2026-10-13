package me.vibb.music.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.vibb.music.bridge.TokenResult
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.spotify.SPOTIFY_MODE_CONTEXT
import me.vibb.music.spotify.cutTitle

/*
 * Spotify's rules on the phone (design 26c §4, §5). Step 3 (the contract) brought the library's side:
 * a Spotify entry parses, its items are streamed - never fetched, never kept - and, until the player
 * could play them (step 5b), shown nowhere. Step 4 adds the player's pure rules: the network gate
 * (`spotifyPlayability`, H1), the context item (H3), the play window and the add-to-queue one track
 * ahead, the start's budgets, what each failure shows and reports (§5.8), and the stored outcome
 * (`SpotifyOutcome`, L8). Pure; SpotifyRulesTest.
 */

/**
 * Whether Spotify items show and play (design 26c §11 step 3, 26b step 1's way): false until step 5b
 * built the service's sites and flipped it (a library with Spotify entries parsed and showed nothing,
 * and a Spotify item never joined Nytt while it couldn't be played). [me.vibb.music.data.Snapshot] and
 * the news rules take it as their default; a context-mode entry shows its one synthetic item (step 6,
 * [contextItem], `Snapshot.visible`).
 */
val SPOTIFY_PLAYABLE: Boolean = true

/** A Spotify entry: everything it lists is streamed by the web player (no url, no copy). */
fun isSpotify(entry: Entry): Boolean = entry.source == Source.SPOTIFY

// --- the network gate (§5.10, QA H1) ---------------------------------------------------------------

/**
 * How a Spotify item plays right now: [Playability.STREAM] through the network gate (the parent's
 * **streaming** switch, design 30's `stream_mobile_data`, never roaming - every stream's rule,
 * [streamAllowed]), else [Playability.NEEDS_NETWORK]; never [Playability.GONE] (a Spotify item has no
 * copy and no url by design) and never [Playability.LOCAL]. The downloads switch and the storage limit
 * never apply: Spotify has no downloads and nothing of it is ever cached.
 */
fun spotifyPlayability(caps: NetCaps?, streamMobileData: Boolean): Playability =
    if (streamAllowed(caps, streamMobileData)) Playability.STREAM else Playability.NEEDS_NETWORK

/**
 * The line a start that the gate refuses shows (§5.8): [SpotifyLine.NEEDS_WIFI] when there is a
 * network with internet that the gate refuses (streaming on mobile data not allowed, or roaming), else
 * [SpotifyLine.NEEDS_INTERNET] (offline); null when the gate lets it stream. [streamMobileData]: the
 * streaming switch (design 30), as [spotifyPlayability].
 */
fun spotifyNetworkLine(caps: NetCaps?, streamMobileData: Boolean): SpotifyLine? = when {
    streamAllowed(caps, streamMobileData) -> null
    caps != null && caps.internet && caps.physical -> SpotifyLine.NEEDS_WIFI
    else -> SpotifyLine.NEEDS_INTERNET
}

// --- the debug switch's tokens (§9.3) ------------------------------------------------------------------

/** Every fake access token's shape (§7.1: `FAKE-ACCESS-<n>`, never a real token's). */
const val FAKE_TOKEN_PREFIX = "FAKE-ACCESS-"

/**
 * A bridge token while the debug switch is set (§9.3, qa-26c-step5b-code L2): the Web API calls and the page then go
 * to the debug base, so only a fake token may go with them - a real one (the server's, on a managed phone with a
 * `config.json` left behind) is [TokenResult.Unreachable] and never sent. Every other answer passes.
 */
fun debugSwitchToken(t: TokenResult): TokenResult =
    if (t is TokenResult.Ok && !t.token.startsWith(FAKE_TOKEN_PREFIX)) TokenResult.Unreachable else t

// --- context mode (§5.9, QA H3) ---------------------------------------------------------------------

/** The synthetic item's key: stable across tracks, never a track id (Spotify's are 22 base62 characters). */
const val SPOTIFY_CONTEXT_KEY = "context"

/** A Spotify entry in context mode (§3.3, §5.9): an artist, or a playlist no account may list - Spotify picks the order. */
fun isSpotifyContext(entry: Entry): Boolean = entry.source == Source.SPOTIFY && entry.spotifyMode == SPOTIFY_MODE_CONTEXT

/** [key] is a context-mode entry's synthetic item (§5.9): its row is the live track's place, never done, never a duration. */
fun isContextItem(entry: Entry, key: String): Boolean = isSpotifyContext(entry) && key == SPOTIFY_CONTEXT_KEY

/**
 * A context-mode entry's one item (§5.9, H3): made by the snapshot, so the tile shows, the play order
 * and the stored queue find it, and its position row holds the live track's place. No url, no
 * duration (it is never marked done). Its title is the entry's name until a track has played; then
 * [live]'s (`spotify_context.json`, step 6): the player, the list's one row, the bar and the session
 * after a restart show the track it stopped at.
 */
fun contextItem(entry: Entry, live: SpotifyContextTrack? = null): PlayItem =
    PlayItem(entry.id, SPOTIFY_CONTEXT_KEY, live?.title ?: entry.name, null, false, null)

/**
 * A context's live track (§5.9, step 6): the uri the web player plays (`spotify:track:<id>`, or an episode a
 * playlist context holds) and its title as the server would list it (`"<name> — <artists>"`). The renderer
 * writes it at every track change of a context it plays; a start of a playlist context goes on from it.
 */
data class SpotifyContextTrack(val uri: String, val title: String)

/**
 * `noBackupFilesDir/spotify_context.json` (§5.9): `{"<entry key>": {"uri": "spotify:track:<id>", "title": "<Song —
 * Artist>"}}`, one row per context-mode entry, written by the renderer through the store at every track change (tmp +
 * rename) and dropped at the context's end (`clear_state`). Never a token, an account or a device id. Pure; the
 * store reads and writes it.
 */
object SpotifyContexts {
    private val JSON = Json { ignoreUnknownKeys = true }
    private val KEY = Regex("[A-Za-z0-9_-]{1,64}")
    private val URI = Regex("spotify:(track|episode):[A-Za-z0-9]{1,64}")

    /** A file's text: every well-formed row ([KEY], a track's or an episode's uri, a title cut to 200), junk dropped. */
    fun decode(text: String?): Map<String, SpotifyContextTrack> {
        val root = runCatching { JSON.parseToJsonElement(text ?: return emptyMap()) }.getOrNull() as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, SpotifyContextTrack>()
        for ((key, value) in root) {
            val o = value as? JsonObject ?: continue
            val uri = (o["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val title = (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cutTitle(it) }?.takeIf { it.isNotEmpty() } ?: continue
            if (KEY.matches(key) && URI.matches(uri)) out[key] = SpotifyContextTrack(uri, title)
        }
        return out
    }

    fun encode(rows: Map<String, SpotifyContextTrack>): String = buildJsonObject {
        for ((key, t) in rows) {
            put(key, buildJsonObject {
                put("uri", t.uri)
                put("title", t.title)
            })
        }
    }.toString()

    /**
     * [rows] with [entryKey]'s row set to [track] (null removes it), or null when nothing changes (nothing to
     * write) or the row isn't well formed.
     */
    fun next(rows: Map<String, SpotifyContextTrack>, entryKey: String, track: SpotifyContextTrack?): Map<String, SpotifyContextTrack>? {
        if (!KEY.matches(entryKey)) return null
        val clean = track?.let { SpotifyContextTrack(it.uri, cutTitle(it.title)) }
        if (clean != null && (!URI.matches(clean.uri) || clean.title.isEmpty())) return null
        if (rows[entryKey] == clean) return null
        return if (clean == null) rows - entryKey else rows + (entryKey to clean)
    }
}

// --- the play window and the queue add (§5.4) ------------------------------------------------------

/** At most this many uris go in one play call (our choice: Spotify documents no maximum [unverified]). */
const val SPOTIFY_WINDOW_MAX = 50

/** The uris of one play call: the queue from [index], at most [max]; empty for an index outside it. */
fun playWindow(uris: List<String>, index: Int, max: Int = SPOTIFY_WINDOW_MAX): List<String> =
    if (index !in uris.indices || max < 1) emptyList() else uris.subList(index, minOf(uris.size, index + max))

/**
 * The queue index to add to Spotify's queue now, one track ahead (§5.4, "Past the window"): when the
 * current item [current] is the last one Spotify knows ([known]: the window's end, or the last item
 * added) and the queue of [size] has more; else null.
 */
fun nextQueueAdd(current: Int, known: Int, size: Int): Int? = if (current >= known && known + 1 < size) known + 1 else null

// --- the start's budgets (§5.4, §5.5) ---------------------------------------------------------------

/** A start of ours, everything included: the host, the token, the play call, our first state. */
const val SPOTIFY_START_BUDGET_MS = 25_000L

/** The page, the SDK and its `ready` (create + load + connect). */
const val SPOTIFY_READY_MS = 20_000L

/** One Web API call (play, transfer, queue add). */
const val SPOTIFY_CALL_MS = 10_000L

/** After the play call's 204: our first uri in a playing state. */
const val SPOTIFY_FIRST_STATE_MS = 10_000L

/** After a transfer's 204: a state or `ready` from the SDK before the play is sent again. */
const val SPOTIFY_TRANSFER_SETTLE_MS = 3_000L

/** The page posts the state every 5 s while not paused; a gap of up to 30 s is held as playing. */
const val SPOTIFY_STATE_TICK_MS = 5_000L
const val SPOTIFY_STATE_GAP_MS = 30_000L

/** Steps within this coalesce into one play call (vibb's skip debounce). */
const val SPOTIFY_STEP_COALESCE_MS = 300L

/** The host is destroyed this long after the last Spotify renderer detached or paused. */
const val SPOTIFY_HOST_IDLE_MS = 5 * 60_000L

/** The deadline of a start that begins at [nowMs] (the elapsed clock). */
fun spotifyStartDeadline(nowMs: Long): Long = nowMs + SPOTIFY_START_BUDGET_MS

// --- what the kid sees and the parent reads (§5.8, §4.4) -------------------------------------------

/**
 * The quiet lines (§5.8): step 5a turns them into `RendererState` values with their strings.
 * [UNAVAILABLE] is `SPOTIFY_NEEDS_INTERNET`'s carrier with the quota's text ("Spotify er ikke
 * tilgjengelig nå").
 */
enum class SpotifyLine { NEEDS_WIFI, NEEDS_INTERNET, UNAVAILABLE, ASK_ADULT, NOT_ON_PHONE, ELSEWHERE, STOPPED }

/** The words of `music_state.spotify` (design 26c §4.4; the server keeps exactly these). */
object SpotifyWords {
    const val NONE = "none"
    const val OK = "ok"
    const val REAUTHORIZE = "reauthorize"
    const val REFUSED = "refused"
    const val NOT_PREMIUM = "not_premium"
    const val NO_WIDEVINE = "no_widevine"
    const val UNREACHABLE = "unreachable"
    const val QUOTA = "quota"
    val ALL = listOf(NONE, OK, REAUTHORIZE, REFUSED, NOT_PREMIUM, NO_WIDEVINE, UNREACHABLE, QUOTA)
}

/**
 * What can stop a Spotify start or play (§5.8's table, row by row): the [line] it shows (null: none,
 * the entries leave) and the [outcome] word it stores (null: unchanged). An unplayable track is no
 * problem of the renderer's: it is an item error (`unplayable`) and the queue moves on.
 */
enum class SpotifyProblem(val line: SpotifyLine?, val outcome: String?) {
    /** No Wi-Fi and mobile data not allowed (or roaming). */
    NO_WIFI(SpotifyLine.NEEDS_WIFI, null),

    /** Offline, the SDK script or `ready` not reached in 20 s, the play call unreachable, no state for 30 s. */
    UNREACHABLE(SpotifyLine.NEEDS_INTERNET, SpotifyWords.UNREACHABLE),

    /** The login ended or Spotify refused the app (the token's `reauthorize`). */
    REAUTHORIZE(SpotifyLine.ASK_ADULT, SpotifyWords.REAUTHORIZE),

    /** The play call's 403 other than `PREMIUM_REQUIRED` (the account or the app, QA M5). */
    REFUSED(SpotifyLine.ASK_ADULT, SpotifyWords.REFUSED),

    /** The SDK's `account_error`, or the play call's 403 `PREMIUM_REQUIRED`. */
    NOT_PREMIUM(SpotifyLine.ASK_ADULT, SpotifyWords.NOT_PREMIUM),

    /** The play call's 429 `QUOTA_EXCEEDED`: the developer account's bucket is empty. */
    QUOTA(SpotifyLine.UNAVAILABLE, SpotifyWords.QUOTA),

    /** The SDK's `initialization_error` (no EME/Widevine): the host is destroyed. */
    NO_WIDEVINE(SpotifyLine.NOT_ON_PHONE, SpotifyWords.NO_WIDEVINE),

    /** A confirmed `null` state while playing: the account plays elsewhere; the bookmark is kept. */
    ELSEWHERE(SpotifyLine.ELSEWHERE, null),

    /** The renderer process died, or a crash: the host is destroyed. */
    STOPPED(SpotifyLine.STOPPED, null),

    /** The token's `off` (the phone was unbound): the host cleared, the entries leave with the next library. */
    OFF(null, SpotifyWords.NONE),
}

/**
 * The SDK's error kinds as the page names them (§5.3) to the problem each is, or null for one the
 * renderer reads elsewhere: `authentication` through the token result it already has, `playback`
 * through the tracker (an unplayable track), `autoplay` only logged.
 */
fun sdkErrorProblem(kind: String): SpotifyProblem? = when (kind) {
    "initialization" -> SpotifyProblem.NO_WIDEVINE
    "account" -> SpotifyProblem.NOT_PREMIUM
    "script" -> SpotifyProblem.UNREACHABLE
    else -> null
}

/**
 * The line every Spotify tile shows for a stored [outcome] (§5.8 "The stored outcome"): the account
 * states - `reauthorize`, `refused`, `not_premium`, `no_widevine` - "Spotify - spør en voksen", `quota`
 * "Spotify er ikke tilgjengelig nå", until a play succeeds; null for `none`, `ok`, `unreachable` (a
 * passing state, shown on the loaded tile only).
 */
fun spotifyTileLine(outcome: String?): SpotifyLine? = when (outcome) {
    SpotifyWords.REAUTHORIZE, SpotifyWords.REFUSED, SpotifyWords.NOT_PREMIUM, SpotifyWords.NO_WIDEVINE -> SpotifyLine.ASK_ADULT
    SpotifyWords.QUOTA -> SpotifyLine.UNAVAILABLE
    else -> null
}

/**
 * The phone's last Spotify outcome as stored in `noBackupFilesDir/spotify_state.json` (§4.4, QA L8):
 * `{"outcome": "<word>", "at": <wall ms>}`, written by the renderer only when the word changes
 * ([next]), read by the report and the tiles. Step 5b's `Store` writes it (tmp + rename).
 */
data class SpotifyOutcome(val outcome: String, val atMs: Long) {
    fun encode(): String = buildJsonObject {
        put("outcome", outcome)
        put("at", atMs)
    }.toString()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** A stored file's text; null for junk, a missing field or a word this app doesn't know. */
        fun decode(text: String?): SpotifyOutcome? {
            val root = runCatching { JSON.parseToJsonElement(text ?: return null) }.getOrNull() as? JsonObject ?: return null
            val word = (root["outcome"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val at = (root["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            return if (word in SpotifyWords.ALL) SpotifyOutcome(word, at) else null
        }

        /**
         * What to store after an outcome [word] at [nowMs] (wall clock), given the [stored] one: the new
         * outcome when the word changed (nothing stored counts as `none`), else null (nothing to write).
         * An unknown word is never stored.
         */
        fun next(stored: SpotifyOutcome?, word: String?, nowMs: Long): SpotifyOutcome? {
            if (word == null || word !in SpotifyWords.ALL) return null
            if (word == (stored?.outcome ?: SpotifyWords.NONE)) return null
            return SpotifyOutcome(word, nowMs)
        }

        /**
         * `music_state.spotify` from the stored file's [text] (§4.4, step 5b): its word, else `none` (no
         * file - never played on this phone -, junk, or a word this app doesn't know). Never more than the word.
         */
        fun reported(text: String?): String = decode(text)?.outcome ?: SpotifyWords.NONE
    }
}
