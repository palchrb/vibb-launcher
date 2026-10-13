package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.server.DownloadGate
import com.kidslauncher.mdm.server.NetCaps
import com.kidslauncher.mdm.server.downloadGate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * The launcher's side of Vibb music (design 21 §2/§3, 21b §5): the courier between the server and
 * the music app. Pure rules here (MusicPlanTest); the Android glue is MusicSync, MusicFileDownloads
 * and MusicBridgeService.
 */

/** The music app's two build names (release, debug). */
val MUSIC_PACKAGES = listOf("me.vibb.music", "me.vibb.music.debug")

/** The bridge's nudge to the music app: carries nothing, only starts its sync. */
const val ACTION_BRIDGE_CHANGED = "me.vibb.music.action.BRIDGE_CHANGED"

/**
 * The music app's player activity and the extras Home's card sends it on a snapshot (21 §3 step 4:
 * open the player and continue). Spelled once in bridge_contract.json, byte-identical in both apps.
 */
const val MUSIC_PLAYER_ACTIVITY = "me.vibb.music.ui.MainActivity"
const val MUSIC_EXTRA_OPEN = "me.vibb.music.extra.OPEN"
const val MUSIC_OPEN_PLAYER = "player"
const val MUSIC_EXTRA_RESUME = "me.vibb.music.extra.RESUME"

/**
 * The call rule's session commands (design 26a §6.7, qa-26a-step3b-code H1), sent as the platform
 * session's custom actions: a call began while the music played / that call is over. The player
 * pauses as its own pause (the kid's sleep timer runs on) and decides itself whether to play again.
 */
const val MUSIC_CALL_HOLD = "me.vibb.music.CALL_HOLD"
const val MUSIC_CALL_RELEASE = "me.vibb.music.CALL_RELEASE"

/** Status-report capability: `music_state` (design 21 §1.3). */
const val MUSIC_CAPABILITY = "music_v1"

/** Restriction keys the launcher writes for the music app (21 §2, managed config). */
object MusicRestrictions {
    const val V = "v"
    const val MANAGED = "managed"
    const val LAUNCHER_PACKAGE = "launcher_package"
    const val LANGUAGE = "language"

    /** Both switches at once (design 30 §1.3): what an older music app reads. */
    const val MOBILE_DATA = "mobile_data"
    const val LIBRARY_VERSION = "library_version"
    const val STORYTEL_GENERATION = "storytel_generation"

    /** The parent's volume cap in force (design 26a §5.3): 1..99 %, 0 = none. The music app clamps its own Sonos sets to it. */
    const val VOLUME_CAP_PCT = "volume_cap_pct"

    /** Design 30 §1.1: streaming off Wi-Fi (an item that isn't downloaded). */
    const val STREAM_MOBILE_DATA = "stream_mobile_data"

    /** Design 30 §1.1: downloads off Wi-Fi, Storytel books included. */
    const val DOWNLOAD_MOBILE_DATA = "download_mobile_data"

    /** The parent's music storage limit in GB (design 30 §1.8): 1..1000, 0 = no limit. */
    const val STORAGE_LIMIT_GB = "storage_limit_gb"
}

private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val HASH = Regex("[0-9a-f]{64}")
private val VERSION = Regex("[0-9a-f]{16}")

/** An older server sends no `storage_limit_gb`: the music storage limit it means is this (design 30 §1.3). */
const val DEFAULT_STORAGE_LIMIT_GB = 2

/** The largest limit the launcher takes from the policy (design 30 §1.3); anything above reads as [DEFAULT_STORAGE_LIMIT_GB]. */
const val MAX_STORAGE_LIMIT_GB = 1000

/**
 * The policy's `music` object (21 §1.3, design 30 §1.3). [libraryVersion] null = nothing ticked on
 * this phone. [streamMobileData] and [downloadMobileData] are the parent's two mobile-data switches;
 * [storageLimitGb] null = no limit.
 */
data class MusicPolicy(
    val libraryVersion: String?,
    val streamMobileData: Boolean,
    val downloadMobileData: Boolean,
    val volumeCapPct: Int?,
    val storageLimitGb: Int?,
    val storytelGeneration: Long,
) {
    /** The old one-switch key (design 30 §1.3): both, so an older music app never spends data the parent turned off. */
    val mobileData: Boolean get() = streamMobileData && downloadMobileData
}

/**
 * `PolicyResponse.music`; null when it isn't an object or any field isn't what 21 §1.3 sends (an
 * older or newer server, or one that couldn't build it): the launcher and the music app then keep
 * what they have. Only a JSON `null` `library_version` means "nothing ticked" - a value the launcher
 * can't read never wipes the kid's downloads or login (qa-21-step3-code M4).
 */
fun musicPolicy(element: JsonElement?): MusicPolicy? {
    val o = element as? JsonObject ?: return null
    val version = when (val v = o["library_version"]) {
        is JsonNull -> null
        is JsonPrimitive -> v.takeIf { it.isString }?.content?.takeIf { VERSION.matches(it) } ?: return null
        else -> return null
    }
    val mobileData = (o["mobile_data"] as? JsonPrimitive)?.booleanOrNull ?: return null
    val stream = mobileSwitch(o, "stream_mobile_data", mobileData) ?: return null
    val download = mobileSwitch(o, "download_mobile_data", mobileData) ?: return null
    val cap = when (val c = o["volume_cap_pct"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> c.intOrNull ?: return null
        else -> return null
    }
    val limit = storageLimitGb(o["storage_limit_gb"])
    val generation = (o["storytel_generation"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: return null
    return MusicPolicy(version, stream, download, cap, limit, generation)
}

/**
 * The storage limit in GB (design 30 §1.3, qa-30-s2-code L1): JSON `null` = no limit, an integer
 * 1..[MAX_STORAGE_LIMIT_GB] = it, absent or anything else = [DEFAULT_STORAGE_LIMIT_GB] - lenient like
 * the server's `storage_limit` and the music app, so the least important field never takes the
 * rest of the music object down with it.
 */
fun storageLimitGb(element: JsonElement?): Int? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> element.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_STORAGE_LIMIT_GB } ?: DEFAULT_STORAGE_LIMIT_GB
    else -> DEFAULT_STORAGE_LIMIT_GB
}

/**
 * The policy's volume cap (design 26a §5.3) with a music object [musicPolicy] refuses kept closed
 * (qa-30-s2-code L1): absent (an older server, or one that couldn't build it) = the policy's
 * none; readable = its cap (null = off); present but refused = [remembered], the last cap read.
 */
fun policyCapPct(music: JsonElement?, remembered: Int?): Int? {
    if (music == null) return null
    val read = musicPolicy(music) ?: return remembered
    return read.volumeCapPct
}

/**
 * One of design 30's two switches: a JSON Boolean is used; absent (an older server) falls back to
 * [mobileData], which meant both; anything else (a string, a number, `null`) is malformed: null,
 * and [musicPolicy] refuses the object like any other bad field.
 */
private fun mobileSwitch(o: JsonObject, key: String, mobileData: Boolean): Boolean? = when (val v = o[key]) {
    null -> mobileData
    is JsonPrimitive -> v.takeIf { !it.isString }?.booleanOrNull
    else -> null
}

/**
 * The policy's `music.spotify` (design 26c §6.2): this phone plays Spotify (it is bound to an
 * account), so the DNS filter lets Spotify's hosts through. Read on its own and leniently: absent
 * (an older server), not a boolean, or no `music` object at all is false - whatever else the object
 * holds.
 */
fun musicSpotify(element: JsonElement?): Boolean =
    ((element as? JsonObject)?.get("spotify") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

/** What the launcher reads of the library (21 §3): its version, covers, own files and listings. */
data class LibraryHead(val version: String, val covers: Set<String>, val files: List<LibraryFile>, val listings: Map<Long, String?>)

data class LibraryFile(val id: Long, val size: Long, val sha256: String, val missing: Boolean)

@Serializable
private data class FileDto(val id: Long, val size: Long, val sha256: String, val missing: Boolean = false)

/** The library's head, or null unless it is a `v: 1` library (the stored one stays). */
fun libraryHead(text: String): LibraryHead? {
    val o = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) return null
    val version = (o["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val covers = (o["covers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { h -> HASH.matches(h) } }.toSet()
    val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { e ->
        runCatching { JSON.decodeFromJsonElement<FileDto>(e) }.getOrNull()
            ?.takeIf { it.id > 0 && it.size >= 0 && HASH.matches(it.sha256) }
            ?.let { LibraryFile(it.id, it.size, it.sha256, it.missing) }
    }
    val listings = (o["entries"] as? JsonArray).orEmpty().mapNotNull { e ->
        val entry = e as? JsonObject ?: return@mapNotNull null
        val id = (entry["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val source = (entry["source"] as? JsonPrimitive)?.content
        if (source == "own") return@mapNotNull null
        id to (entry["items"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { VERSION.matches(it) }
    }.toMap()
    return LibraryHead(version, covers, files, listings)
}

/** A listing's `v` and `version`; null unless it is a `v: 1` listing. */
fun listingVersionOf(text: String): String? {
    val o = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) return null
    return (o["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * The listings to fetch: every entry whose library version ([LibraryHead.listings]) differs from
 * the one stored ([stored], entry id -> version). Any version the server returns is stored (21b
 * QA #20: hashes have no order); a never-listed entry (null) has nothing to fetch.
 */
fun listingsToFetch(head: LibraryHead, stored: Map<Long, String>): List<Long> =
    head.listings.filter { (id, version) -> version != null && stored[id] != version }.keys.sorted()

/** Stored listings the library no longer names (21b §5: deleted). */
fun listingsToDelete(head: LibraryHead?, stored: Set<Long>): Set<Long> =
    if (head == null) stored else stored.filterTo(sortedSetOf()) { it !in head.listings }

/**
 * The own files to download now (21 §2/§3, QA #8): one ahead of the handover - nothing while a
 * downloaded file waits for the music app - and never one the music app has (its id and hash are
 * in its last report, [handedOver]), the server lost (`missing`), or one waiting out its hash
 * failures ([skip]). A ready file the music app already holds doesn't wait for anything
 * (qa-21-step3-code M1: its `releaseFile` was lost) - [readyFilesToDelete] removes it.
 */
fun nextOwnFile(head: LibraryHead, handedOver: Map<Long, String>, ready: Map<Long, String>, skip: Set<Long> = emptySet()): LibraryFile? {
    if (ready.any { (id, sha) -> handedOver[id] != sha }) return null
    return head.files.firstOrNull { f -> !f.missing && handedOver[f.id] != f.sha256 && f.id !in skip }
}

/** Ready files to delete: ones the library no longer lists (or lists with another hash), and ones the
 * music app already holds. */
fun readyFilesToDelete(head: LibraryHead?, ready: Map<Long, String>, handedOver: Map<Long, String> = emptyMap()): Set<Long> {
    val listed = head?.files?.associate { it.id to it.sha256 }.orEmpty()
    return ready.filter { (id, sha) -> listed[id] != sha || handedOver[id] == sha }.keys.toSet()
}

/** A file whose download failed its size or SHA-256 this often waits a day (design 13's `MAX_HASH_FAILURES`). */
const val OWN_FILE_MAX_HASH_FAILURES = 2
const val OWN_FILE_HASH_BACKOFF_MS = 24 * 3_600_000L

/** Is a file that failed its hash [failures] times, last at [lastAt], still skipped at [nowMs]? */
fun hashFailureSkips(failures: Int, lastAt: Long, nowMs: Long): Boolean =
    failures >= OWN_FILE_MAX_HASH_FAILURES && nowMs - lastAt in 0 until OWN_FILE_HASH_BACKOFF_MS

/**
 * The managed config for the music app (21 §2): written when it differs, cleared when unmanaged.
 * Only Boolean, Int and String values: the platform's store (`UserManagerService.writeBundle`)
 * drops the whole bundle for anything else, a Long included (qa-21-step3-code H1).
 * [volumeCapPct] is the cap IN FORCE (`capPctInForce`: the policy's, lifted by the override and the
 * pause), not the policy's: a Sonos room is outside `STREAM_MUSIC`, so the music app clamps its own
 * sets to it (design 26a §5.3).
 */
fun restrictionsFor(policy: MusicPolicy, launcherPackage: String, language: String, volumeCapPct: Int?): Map<String, Any> = linkedMapOf(
    MusicRestrictions.V to 1,
    MusicRestrictions.MANAGED to true,
    MusicRestrictions.LAUNCHER_PACKAGE to launcherPackage,
    MusicRestrictions.LANGUAGE to language,
    MusicRestrictions.MOBILE_DATA to policy.mobileData,
    MusicRestrictions.LIBRARY_VERSION to policy.libraryVersion.orEmpty(),
    MusicRestrictions.STORYTEL_GENERATION to policy.storytelGeneration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    MusicRestrictions.VOLUME_CAP_PCT to volumeCapRestriction(volumeCapPct),
    MusicRestrictions.STREAM_MOBILE_DATA to policy.streamMobileData,
    MusicRestrictions.DOWNLOAD_MOBILE_DATA to policy.downloadMobileData,
    MusicRestrictions.STORAGE_LIMIT_GB to storageLimitRestriction(policy.storageLimitGb),
)

/** The restriction's value for the storage limit (design 30 §1.3): the GB, 0 = no limit. */
fun storageLimitRestriction(limitGb: Int?): Int = limitGb?.takeIf { it > 0 } ?: 0

/** The restriction's value for the cap in force: the percentage while it caps (1..99), else 0 ("none"). */
fun volumeCapRestriction(capPct: Int?): Int = capPct?.takeIf { it in 1..99 } ?: 0

/** The value types `setApplicationRestrictions` stores for a device owner (besides nested bundles and arrays). */
fun restrictionValueStorable(value: Any?): Boolean = value is Boolean || value is Int || value is String

/** Only the music app's two build names may use the bridge (on top of the signature permission). */
fun callerAllowed(callerPackages: List<String>?): Boolean = callerPackages.orEmpty().any { it in MUSIC_PACKAGES }

/** A "playing" with no `setNowPlaying` for this long no longer holds an update back (a stuck player). */
const val PLAYING_STALE_MS = 6 * 3_600_000L

/**
 * A music app update waits while it plays (21 §3, QA #8): [apkPackage] is the APK's package,
 * [playingReportedAt] when the last `setNowPlaying` said "playing" (null = not playing, or the
 * player's process died). Measured from the last report, so hours of play keep holding it.
 */
fun installDeferredForPlayback(apkPackage: String?, playingReportedAt: Long?, nowMs: Long): Boolean =
    apkPackage in MUSIC_PACKAGES && playingReportedAt != null && nowMs - playingReportedAt in 0 until PLAYING_STALE_MS

/** The report has gone stale: the bridge isn't reached any more. */
const val REPORT_STALE_MS = 26 * 3_600_000L

/** The status report's `bridge` word: "bound" while the music app reports, else "unbound". */
fun bridgeWord(lastReportAt: Long?, nowMs: Long): String =
    if (lastReportAt != null && nowMs - lastReportAt in 0 until REPORT_STALE_MS) "bound" else "unbound"

/**
 * `music_state` (21 §1.3, 21b §4.3, 26a §5.5): the music app's last report (known keys only - the
 * server checks them again; `sonos_rooms` = the speakers it knows, for the parent's room scope;
 * `audio_bytes`/`extra_bytes` from a design 30 music app, whose presence tells the PWA the phone reads
 * both mobile-data switches, design 30 §1.3) plus the launcher's own view: the package, its version
 * code and the bridge.
 * Null when no music app is installed.
 */
fun musicState(packageName: String?, versionCode: Long?, report: JsonObject?, lastReportAt: Long?, nowMs: Long): JsonObject? {
    packageName ?: return null
    val keys = listOf(
        "library_version", "cache_bytes", "audio_bytes", "extra_bytes", "downloads_waiting", "entry_errors", "item_errors",
        "downloads", "storytel", "spotify", "sonos_rooms",
    )
    return buildJsonObject {
        put("package", packageName)
        put("version_code", versionCode)
        put("bridge", bridgeWord(lastReportAt, nowMs))
        for (key in keys) report?.get(key)?.takeIf { it !is JsonNull }?.let { put(key, it) }
    }
}

// Episode pictures (design 27 §2.2): the music app's want set, carried like covers.

/** The want set's caps (bridge_contract.json `want_art`, the music app's own). */
const val WANT_ART_MAX = 600
const val WANT_ART_NOW_MAX = 5

/** An episode picture is a 512 px JPEG: at most 1 MB, hash-checked like a cover. */
const val MAX_ART_BYTES = 1024 * 1024

/** At most this many picture GETs per courier run, `now` first (QA M6). */
const val ART_PER_RUN = 50

/** A picture that failed (an HTTP error or its hash) this often waits a day (as [OWN_FILE_HASH_BACKOFF_MS]). */
const val ART_MAX_FAILURES = 2
const val ART_BACKOFF_MS = 24 * 3_600_000L

/** What the music app wants: [now] (the now-playing window: any network) and [later] (kept episodes and Nytt: the audio's gate). */
data class WantArt(val now: List<String>, val later: List<String>) {
    val all: Set<String> get() = (now + later).toSet()

    companion object {
        val NONE = WantArt(emptyList(), emptyList())
    }
}

/** A picture's failures in a row and the last one's time. */
data class ArtFailure(val count: Int, val lastAt: Long)

/**
 * `wantArt`'s lists as the launcher keeps them: 64-hex hashes only, each once ([now] wins), at most
 * [WANT_ART_NOW_MAX] in [now] and [WANT_ART_MAX] in all.
 */
fun wantArtLists(now: List<String?>?, later: List<String?>?): WantArt {
    val n = now.orEmpty().filterNotNull().filter { HASH.matches(it) }.distinct().take(WANT_ART_NOW_MAX)
    val l = later.orEmpty().filterNotNull().filter { HASH.matches(it) && it !in n }.distinct().take(WANT_ART_MAX - n.size)
    return WantArt(n, l)
}

/** Does a picture that failed wait at [nowMs]? */
fun artBackedOff(failure: ArtFailure?, nowMs: Long): Boolean =
    failure != null && failure.count >= ART_MAX_FAILURES && nowMs - failure.lastAt in 0 until ART_BACKOFF_MS

/**
 * The pictures to fetch now, at most [max], `now` first: none without a network ([online]); `now` on
 * any network (roaming too: ~60 KB a picture); `later` only on an unmetered network or with the
 * parent's downloads-on-mobile-data switch (not roaming; design 30 §1.1) - the audio's download
 * gate, since kept art goes with kept audio (21 §4.5). Never one the launcher [has], or one waiting out its failures.
 */
fun artToFetch(
    want: WantArt,
    has: Set<String>,
    failures: Map<String, ArtFailure>,
    online: Boolean,
    unmetered: Boolean,
    downloadMobileData: Boolean,
    max: Int,
    nowMs: Long,
): List<String> {
    if (!online) return emptyList()
    val later = if (unmetered || downloadMobileData) want.later else emptyList()
    return (want.now + later).filter { it !in has && !artBackedOff(failures[it], nowMs) }.distinct().take(max)
}

/**
 * [artToFetch] on the phone's network [caps] (design 27 §2.2, design 30 §1.1): online = INTERNET
 * and a physical transport; `later` on an unmetered network, or with the policy's downloads switch
 * and not roaming. [MusicCourier.artFetchable] only feeds it.
 */
fun artToFetchOn(
    want: WantArt,
    has: Set<String>,
    failures: Map<String, ArtFailure>,
    caps: NetCaps?,
    music: MusicPolicy,
    max: Int,
    nowMs: Long,
): List<String> = artToFetch(
    want,
    has,
    failures,
    online = caps != null && caps.internet && caps.physical,
    unmetered = caps?.notMetered == true,
    downloadMobileData = music.downloadMobileData && caps?.notRoaming == true,
    max = max,
    nowMs = nowMs,
)

/**
 * The gate for one own file (design 13's [downloadGate], design 30 §1.1): Wi-Fi only unless the
 * policy's downloads switch is on; no readable music object ([music] null) = Wi-Fi only.
 * `MusicFileDownloads.gate` only feeds it.
 */
fun ownFileGate(caps: NetCaps?, music: MusicPolicy?, nowMs: Long, haveBytes: Long, totalBytes: Long, allocatableBytes: Long?): DownloadGate =
    downloadGate(caps, wifiOnly = music?.downloadMobileData != true, anyNetworkAt = null, nowMs = nowMs, haveBytes = haveBytes, totalBytes = totalBytes, allocatableBytes = allocatableBytes)

/** Cover files to delete: every one that is neither a library cover nor wanted (held until the music app copied it). */
fun artToDelete(present: Set<String>, libraryCovers: Set<String>, want: WantArt): Set<String> {
    val wanted = want.all
    return present.filterTo(sortedSetOf()) { it !in libraryCovers && it !in wanted }
}

/**
 * The `now` pictures the launcher already holds, when they call for a nudge to the music app: some
 * are held and they aren't the set it was nudged for last ([last]). Null: no nudge (once per set,
 * never a loop).
 */
fun heldNudge(want: WantArt, last: Set<String>, has: (String) -> Boolean): Set<String>? {
    val held = want.now.filter(has).toSet()
    return held.takeIf { it.isNotEmpty() && it != last }
}

/**
 * What a picture's answer means: true = take the bytes (then checked), false = it can't be served
 * (a 4xx: counts toward the day's wait), null = try again later (a 5xx, like a network error: the
 * Pi restarting or a proxy's 502 never puts pictures into a day's wait, code QA L11).
 */
fun artAnswer(status: Int): Boolean? = when (status) {
    in 200..299 -> true
    in 500..599 -> null
    else -> false
}
