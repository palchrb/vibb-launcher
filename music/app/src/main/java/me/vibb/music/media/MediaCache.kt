package me.vibb.music.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import me.vibb.music.BuildConfig
import me.vibb.music.logic.hlsKeyPrefix
import me.vibb.music.logic.hlsResourceKey
import me.vibb.music.logic.isPrivateAddress
import me.vibb.music.logic.itemErrorCode
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The one download cache (design 21 §4.5): a Media3 [SimpleCache] in noBackupFilesDir that never
 * evicts by itself (the keep/prune rule decides), written only by downloads; playback reads it and
 * streams the rest without caching (21 §4.5 "streams aren't cached"). Each item has its own
 * [keyFactory] (QA #2, qa-21-step2-code S3): a progressive item is one resource keyed
 * "<entry key>:<item key>", an HLS item's playlists and segments are keyed by their path under it.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.noBackupFilesDir, "media"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** The cache keys of one item: [cacheKey] itself, or for HLS ([url] = its playlist) each resource under it. */
    fun keyFactory(cacheKey: String, url: String, hls: Boolean): CacheKeyFactory =
        if (hls) CacheKeyFactory { spec -> spec.key ?: hlsResourceKey(cacheKey, url, spec.uri.toString()) } else CacheKeyFactory { spec -> spec.key ?: cacheKey }

    /**
     * HTTP from the source. Two clients: one for LAN entries (`lan` in the library), and one for
     * every other entry ([me.vibb.music.logic.publicOnly]) that refuses a private address - in its
     * [Dns] (every name, every redirect hop) and again in a network interceptor on the address
     * actually connected to (IP literals never reach [Dns]). A refusal is an IOException: the
     * `item_errors` code `network` (21b §2.7, qa-21b-step1c-code #14).
     */
    private val lanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val publicClient: OkHttpClient by lazy { guarded(lanClient, Dns.SYSTEM) }

    /** [base] refusing private addresses: names resolved by [dns], and the connected address. */
    internal fun guarded(base: OkHttpClient, dns: Dns): OkHttpClient = base.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .dns(PublicDns(dns))
        .addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || isPrivateAddress(address)) throw PrivateAddressException(chain.request().url.host)
            chain.proceed(chain.request())
        }
        .build()

    /** [dns]'s answers without the private ones; none left = refused. */
    internal class PublicDns(private val dns: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            dns.lookup(hostname).filterNot(::isPrivateAddress).ifEmpty { throw PrivateAddressException(hostname) }
    }

    /** A public entry's item would connect to a private address (21b §2.7). */
    class PrivateAddressException(host: String) : UnknownHostException("$host: private address refused for a public entry")

    /**
     * A Storytel book's CDN client (design 26b §4.3): redirects never followed. The minted URL is the
     * CDN's own (the asset call's 302 was captured by the Storytel client), and a followed redirect
     * would put its target - a token in its query - into the cache index (Media3's `exo_redir`). A
     * CDN that redirects anyway fails the attempt with its 3xx status (`http_302`, transient: the
     * next attempt mints again).
     */
    private val noRedirectClients: Map<Boolean, OkHttpClient> by lazy {
        mapOf(true to publicClient, false to lanClient).mapValues { (_, c) -> c.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    }

    /**
     * The client for [publicOnly] (the guarded public one, else the LAN one): the Storytel client's
     * transport (design 26b §4.1, QA M2), which sets its own User-Agent and redirect rule per request.
     */
    fun httpClient(publicOnly: Boolean): OkHttpClient = if (publicOnly) publicClient else lanClient

    /** Only http(s) upstream for NRK and RSS (a playlist can't open a file: or content: URI); an
     * HTML answer is never audio (21b §2.5 `bad_media`). */
    private fun http(publicOnly: Boolean, followRedirects: Boolean = true): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(if (followRedirects) httpClient(publicOnly) else noRedirectClients.getValue(publicOnly))
            .setUserAgent("VibbMusic/${BuildConfig.VERSION_NAME}")
            .setContentTypePredicate { type -> !type.lowercase().startsWith("text/html") }

    /**
     * For the player: the item's copy first (read only), then the network - or, for [copyOnly] (a
     * Storytel book, design 26b §4.4: download-only), the copy and nothing else: no upstream at all
     * (Media3 then reads through a placeholder that fails to open), so a missing part is an error,
     * never a request.
     */
    fun playbackFactory(context: Context, publicOnly: Boolean, keys: CacheKeyFactory, copyOnly: Boolean = false): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(get(context))
        .setUpstreamDataSourceFactory(if (copyOnly) null else http(publicOnly))
        .setCacheWriteDataSinkFactory(null)
        .setCacheKeyFactory(keys)

    /** For downloads: written into the cache. [followRedirects] false for a Storytel book (above). */
    fun downloadFactory(context: Context, publicOnly: Boolean, keys: CacheKeyFactory, followRedirects: Boolean = true): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(http(publicOnly, followRedirects))
            .setCacheKeyFactory(keys)

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
