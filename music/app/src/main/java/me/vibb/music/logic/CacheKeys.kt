package me.vibb.music.logic

import java.net.URI

/*
 * Cache keys of downloaded audio (design 21 §4.5, QA #2). A progressive item is one resource keyed
 * "<entry key>:<item key>" (Media3's customCacheKey). Media3 has no custom key for HLS, so every
 * playlist and segment is keyed by the item plus its path relative to the playlist's directory,
 * without the query: a re-resolved URL (a new token or CDN path) finds the same bytes. Pure;
 * CacheKeysTest.
 */

/** The cache key of an item's audio. */
fun itemCacheKey(entryKey: String, itemKey: String): String = "$entryKey:$itemKey"

/** The prefix of every HLS resource of the item with [itemCacheKey]. */
fun hlsKeyPrefix(itemCacheKey: String): String = "hls:$itemCacheKey/"

/** `scheme://authority/dir/` of [url], without query or fragment; null when it isn't absolute http(s). */
fun urlDirectory(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https" || uri.rawAuthority == null) return null
    val path = uri.rawPath.orEmpty()
    return "$scheme://${uri.rawAuthority}${path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" }}"
}

/** `scheme://authority/path` of [url], without query or fragment. */
private fun withoutQuery(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (uri.rawAuthority == null) return null
    return "$scheme://${uri.rawAuthority}${uri.rawPath.orEmpty()}"
}

/**
 * The key of [resourceUrl] when it belongs to one of [roots] (playlist directory ->
 * item cache key, the longest matching directory wins); null when it belongs to none.
 */
fun hlsCacheKey(roots: Map<String, String>, resourceUrl: String): String? {
    val plain = withoutQuery(resourceUrl) ?: return null
    val root = roots.keys.filter { plain.startsWith(it) }.maxByOrNull { it.length } ?: return null
    return hlsKeyPrefix(roots.getValue(root)) + plain.removePrefix(root)
}

/** The HLS rendition a download fetches: the highest bitrate up to this, else the lowest. */
const val HLS_MAX_BITRATE = 192_000

/** Index of the variant to download from [bitrates] (bits/s; 0 or less = unknown, tried last). */
fun pickVariant(bitrates: List<Int>): Int {
    val fitting = bitrates.withIndex().filter { it.value in 1..HLS_MAX_BITRATE }
    if (fitting.isNotEmpty()) return fitting.maxBy { it.value }.index
    val known = bitrates.withIndex().filter { it.value > 0 }
    return if (known.isNotEmpty()) known.minBy { it.value }.index else 0
}
