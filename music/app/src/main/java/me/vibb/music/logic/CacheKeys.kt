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
internal fun withoutQuery(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (uri.rawAuthority == null) return null
    return "$scheme://${uri.rawAuthority}${uri.rawPath.orEmpty()}"
}

/**
 * Is [a] the same source as [b] (design 30 §1.6, L5)? Two http(s) URLs are when they are equal without
 * their query and fragment ([withoutQuery], the normalisation the HLS keys use: a re-resolved NRK token
 * or a podcast host's new tracking query keeps a partial copy); anything else (a Storytel source id
 * `storytel:book:<cid>`) only when the whole strings are.
 */
fun sameSource(a: String, b: String): Boolean {
    if (a == b) return true
    if (!isHttp(a) || !isHttp(b)) return false
    val plain = withoutQuery(a) ?: return false
    return plain == withoutQuery(b)
}

private fun isHttp(url: String): Boolean = url.substringBefore(':').lowercase().let { it == "http" || it == "https" }

/**
 * The item cache key in [cacheKeys] that the cache resource [resourceKey] belongs to: itself (a
 * progressive item), or the one whose [hlsKeyPrefix] it starts with; null when none.
 */
fun itemOfResource(resourceKey: String, cacheKeys: Set<String>): String? {
    if (resourceKey in cacheKeys) return resourceKey
    if (!resourceKey.startsWith("hls:")) return null
    var slash = resourceKey.indexOf('/', 4)
    while (slash >= 0) {
        val candidate = resourceKey.substring(4, slash)
        if (candidate in cacheKeys) return candidate
        slash = resourceKey.indexOf('/', slash + 1)
    }
    return null
}

/**
 * The key of one HLS resource of the item with [itemCacheKey], whose playlist is [playlistUrl]: its
 * path relative to the playlist's directory, else its host and path ("@host/path"), never the
 * query - under [hlsKeyPrefix], so deleting the item finds every resource. One key factory per item
 * (qa-21-step2-code S3), no shared map.
 */
fun hlsResourceKey(itemCacheKey: String, playlistUrl: String, resourceUrl: String): String {
    val plain = withoutQuery(resourceUrl) ?: resourceUrl.substringBefore('?')
    val dir = urlDirectory(playlistUrl)
    val rel = if (dir != null && plain.startsWith(dir)) plain.removePrefix(dir) else "@" + plain.substringAfter("://")
    return hlsKeyPrefix(itemCacheKey) + rel
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
