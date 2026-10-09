package me.vibb.music.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import androidx.media3.common.ParserException
import me.vibb.music.BuildConfig
import me.vibb.music.logic.hlsCacheKey
import me.vibb.music.logic.hlsKeyPrefix
import me.vibb.music.logic.isPrivateAddress
import me.vibb.music.logic.itemErrorCode
import me.vibb.music.logic.urlDirectory
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The one download cache (design 21 §4.5): a Media3 [SimpleCache] in noBackupFilesDir that never
 * evicts by itself (the keep/prune rule decides), written only by downloads; playback reads it and
 * streams the rest without caching (21 §4.5 "streams aren't cached"). Keys: [itemCacheKey] for a
 * progressive item, and per HLS resource the item plus its relative path ([hlsCacheKey], QA #2).
 */
@OptIn(UnstableApi::class)
object MediaCache {
    @Volatile private var cache: SimpleCache? = null

    /** HLS playlist directory -> the item's cache key; registered before an HLS item plays or downloads. */
    private val hlsRoots = ConcurrentHashMap<String, String>()

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.noBackupFilesDir, "media"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    private val keyFactory = CacheKeyFactory { spec ->
        spec.key ?: hlsCacheKey(hlsRoots, spec.uri.toString()) ?: spec.uri.toString()
    }

    /** Make the HLS resources under [url]'s directory key on [itemCacheKey]. */
    fun registerHls(itemCacheKey: String, url: String) {
        urlDirectory(url)?.let { hlsRoots[it] = itemCacheKey }
    }

    /**
     * HTTP from the source. Two clients: one for LAN-capable entries, and one for public-only
     * entries ([publicOnly]) that refuses a private address - in its [Dns] (every name, every
     * redirect hop) and again in a network interceptor on the address actually connected to (IP
     * literals never reach [Dns]). A refusal is an IOException: `item_errors` code `network`.
     */
    private val lanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val publicClient: OkHttpClient by lazy {
        lanClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(PublicDns)
            .addNetworkInterceptor { chain ->
                val address = chain.connection()?.route()?.socketAddress?.address
                if (address == null || isPrivateAddress(address)) throw PrivateAddressException(chain.request().url.host)
                chain.proceed(chain.request())
            }
            .build()
    }

    /** The system's answers without the private ones; none left = refused. */
    private object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filterNot(::isPrivateAddress).ifEmpty { throw PrivateAddressException(hostname) }
    }

    /** A public entry's item would connect to a private address (21b §2.7). */
    class PrivateAddressException(host: String) : UnknownHostException("$host: private address refused for a public entry")

    /** A captive portal's HTML page is never audio (21b §2.5 `bad_media`). */
    private fun http(publicOnly: Boolean): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(if (publicOnly) publicClient else lanClient)
            .setUserAgent("VibbMusic/${BuildConfig.VERSION_NAME}")
            .setContentTypePredicate { type -> !type.lowercase().startsWith("text/html") }

    /** For the player: cache first (read only), then the network or a local file. */
    fun playbackFactory(context: Context, publicOnly: Boolean): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(get(context))
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http(publicOnly)))
        .setCacheWriteDataSinkFactory(null)
        .setCacheKeyFactory(keyFactory)

    /** For downloads: written into the cache. */
    fun downloadFactory(context: Context, publicOnly: Boolean): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(get(context))
        .setUpstreamDataSourceFactory(http(publicOnly))
        .setCacheKeyFactory(keyFactory)

    /** Delete an item's audio. */
    fun remove(context: Context, cacheKey: String, hls: Boolean) {
        val c = get(context)
        if (hls) {
            val prefix = hlsKeyPrefix(cacheKey)
            c.keys.filter { it.startsWith(prefix) }.forEach { c.removeResource(it) }
        } else {
            c.removeResource(cacheKey)
        }
    }

    /** Bytes cached for an item. */
    fun cachedBytes(context: Context, cacheKey: String, hls: Boolean): Long {
        val c = get(context)
        val keys = if (hls) c.keys.filter { it.startsWith(hlsKeyPrefix(cacheKey)) } else listOf(cacheKey)
        return keys.sumOf { k -> c.getCachedSpans(k).sumOf { it.length } }
    }

    fun totalBytes(context: Context): Long = get(context).cacheSpace

    /** The `item_errors` code of a failed download or stream (21b §4.3). */
    fun errorCode(error: Throwable): String {
        var e: Throwable? = error
        while (e != null) {
            when (e) {
                is HttpDataSource.InvalidResponseCodeException -> return itemErrorCode(e.responseCode, false)
                is HttpDataSource.InvalidContentTypeException, is ParserException -> return itemErrorCode(null, true)
            }
            e = e.cause
        }
        return itemErrorCode(null, false)
    }
}
