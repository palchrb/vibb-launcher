package me.vibb.music.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * One entry's listing (design 21b §4.2): `GET /api/devices/music/entries/{id}/items`, oldest first,
 * pinned by server/testdata/music_listing.json (byte-identical in src/test/resources). The server
 * built it; the phone only checks what would be unsafe to use (21b QA #13): keys, http(s) URLs and
 * art hashes (64 lowercase hex, design 27).
 * A bad item is dropped, not the listing - but a listing without an `items` array, or one where
 * fewer than half of the items parse, is refused: applied, it would prune copies that can't be
 * fetched again (qa-21-step2-code M5, QA #3). The sync then reports `bad_listing` and keeps the old one.
 */

private val ITEM_KEY = Regex("[A-Za-z0-9_-]{1,64}")
private val ART_HASH = Regex("[0-9a-f]{64}")
private const val MAX_URL = 2000

/**
 * A listed episode. [url] null = not available at the source now (21b §1 `gone`). [art] is the hash
 * of the episode's own picture (design 27 §1.5, fetched like a cover), null = the entry's cover.
 */
data class ListingItem(
    val key: String,
    val title: String,
    val url: String?,
    val hls: Boolean,
    val durationMs: Long?,
    val art: String?,
)

data class Listing(val entryId: Long, val version: String, val items: List<ListingItem>)

@Serializable
private data class ItemDto(
    val key: String,
    val title: String? = null,
    val url: String? = null,
    val hls: Boolean = false,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val art: String? = null,
)

/** [url] if it is an http(s) URL without userinfo, at most 2000 characters; else null. */
fun safeUrl(url: String?): String? {
    if (url == null || url.length > MAX_URL) return null
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.rawUserInfo != null || uri.host.isNullOrEmpty()) return null
    return url
}

object ListingParser {
    /** The listing, or null when [text] isn't a `v: 1` listing with (mostly) readable items. */
    fun parse(text: String): Listing? {
        val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if (root["v"]?.jsonPrimitive?.intOrNull != 1) return null
        val entry = (root["entry"] as? JsonPrimitive)?.longOrNull ?: return null
        val version = (root["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val raw = root["items"] as? JsonArray ?: return null
        val items = raw.mapNotNull { e ->
            val d = runCatching { JSON.decodeFromJsonElement<ItemDto>(e) }.getOrNull() ?: return@mapNotNull null
            if (!ITEM_KEY.matches(d.key)) return@mapNotNull null
            ListingItem(
                d.key,
                d.title?.trim().takeUnless { it.isNullOrEmpty() } ?: d.key,
                safeUrl(d.url),
                d.hls,
                d.durationMs?.takeIf { it > 0 },
                // A hash, never a URL: an older server's URL is dropped (design 27 §1.5).
                d.art?.takeIf { ART_HASH.matches(it) },
            )
        }.distinctBy { it.key }
        if (items.size * 2 < raw.size) return null
        return Listing(entry, version, items)
    }
}
