package com.kidslauncher.mdm.music

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

/** Status-report capability: `music_state` (design 21 §1.3). */
const val MUSIC_CAPABILITY = "music_v1"

/** Restriction keys the launcher writes for the music app (21 §2, managed config). */
object MusicRestrictions {
    const val V = "v"
    const val MANAGED = "managed"
    const val LAUNCHER_PACKAGE = "launcher_package"
    const val LANGUAGE = "language"
    const val MOBILE_DATA = "mobile_data"
    const val LIBRARY_VERSION = "library_version"
    const val STORYTEL_GENERATION = "storytel_generation"
}

private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val HASH = Regex("[0-9a-f]{64}")
private val VERSION = Regex("[0-9a-f]{16}")

/** The policy's `music` object (21 §1.3). [libraryVersion] null = nothing ticked on this phone. */
data class MusicPolicy(val libraryVersion: String?, val mobileData: Boolean, val volumeCapPct: Int?, val storytelGeneration: Long)

/** `PolicyResponse.music` read leniently; null when it isn't an object (an older server, or the
 * server couldn't build it: the music app then keeps what it has). */
fun musicPolicy(element: JsonElement?): MusicPolicy? {
    val o = element as? JsonObject ?: return null
    fun p(key: String) = o[key] as? JsonPrimitive
    return MusicPolicy(
        libraryVersion = p("library_version")?.takeIf { it.isString }?.content?.takeIf { VERSION.matches(it) },
        mobileData = p("mobile_data")?.booleanOrNull ?: false,
        volumeCapPct = p("volume_cap_pct")?.intOrNull,
        storytelGeneration = p("storytel_generation")?.longOrNull ?: 0,
    )
}

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
 * downloaded file waits for the music app - and never one the music app has (its id and hash were
 * handed over) or the server lost (`missing`). [handedOver]: id -> SHA-256 released by the music app.
 */
fun nextOwnFile(head: LibraryHead, handedOver: Map<Long, String>, ready: Set<Long>): LibraryFile? {
    if (ready.isNotEmpty()) return null
    return head.files.firstOrNull { f -> !f.missing && handedOver[f.id] != f.sha256 }
}

/** Ready files the library no longer lists (or lists with another hash): deleted. */
fun readyFilesToDelete(head: LibraryHead?, ready: Map<Long, String>): Set<Long> {
    val listed = head?.files?.associate { it.id to it.sha256 }.orEmpty()
    return ready.filter { (id, sha) -> listed[id] != sha }.keys.toSet()
}

/** The managed config for the music app (21 §2): written when it differs, cleared when unmanaged. */
fun restrictionsFor(policy: MusicPolicy, launcherPackage: String, language: String): Map<String, Any> = linkedMapOf(
    MusicRestrictions.V to 1,
    MusicRestrictions.MANAGED to true,
    MusicRestrictions.LAUNCHER_PACKAGE to launcherPackage,
    MusicRestrictions.LANGUAGE to language,
    MusicRestrictions.MOBILE_DATA to policy.mobileData,
    MusicRestrictions.LIBRARY_VERSION to policy.libraryVersion.orEmpty(),
    MusicRestrictions.STORYTEL_GENERATION to policy.storytelGeneration,
)

/** Only the music app's two build names may use the bridge (on top of the signature permission). */
fun callerAllowed(callerPackages: List<String>?): Boolean = callerPackages.orEmpty().any { it in MUSIC_PACKAGES }

/** A playing snapshot older than this no longer holds an update back (a crashed player). */
const val PLAYING_STALE_MS = 6 * 3_600_000L

/**
 * A music app update waits while it plays (21 §3, QA #8): [apkPackage] is the APK's package,
 * [playingSince] when the last `setNowPlaying` said "playing" (null = not playing).
 */
fun installDeferredForPlayback(apkPackage: String?, playingSince: Long?, nowMs: Long): Boolean =
    apkPackage in MUSIC_PACKAGES && playingSince != null && nowMs - playingSince in 0 until PLAYING_STALE_MS

/** The report has gone stale: the bridge isn't reached any more. */
const val REPORT_STALE_MS = 26 * 3_600_000L

/** The status report's `bridge` word: "bound" while the music app reports, else "unbound". */
fun bridgeWord(lastReportAt: Long?, nowMs: Long): String =
    if (lastReportAt != null && nowMs - lastReportAt in 0 until REPORT_STALE_MS) "bound" else "unbound"

/**
 * `music_state` (21 §1.3, 21b §4.3): the music app's last report (known keys only - the server
 * checks them again) plus the launcher's own view: the package, its version code and the bridge.
 * Null when no music app is installed.
 */
fun musicState(packageName: String?, versionCode: Long?, report: JsonObject?, lastReportAt: Long?, nowMs: Long): JsonObject? {
    packageName ?: return null
    val keys = listOf("library_version", "cache_bytes", "downloads_waiting", "entry_errors", "item_errors", "downloads", "storytel")
    return buildJsonObject {
        put("package", packageName)
        put("version_code", versionCode)
        put("bridge", bridgeWord(lastReportAt, nowMs))
        for (key in keys) report?.get(key)?.takeIf { it !is JsonNull }?.let { put(key, it) }
    }
}
