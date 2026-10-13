package me.vibb.music.media

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import me.vibb.music.BuildConfig
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Store
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.hlsKeyPrefix
import me.vibb.music.logic.hlsResourceKey
import me.vibb.music.logic.isPrivateAddress
import me.vibb.music.logic.itemErrorCode
import me.vibb.music.logic.itemOfResource
import me.vibb.music.logic.sourceChanged
import me.vibb.music.logic.streamAllowed
import me.vibb.music.logic.throughWrites
import me.vibb.music.sync.netCaps
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The one audio cache (design 21 §4.5): a Media3 [SimpleCache] in noBackupFilesDir that never evicts
 * by itself (the keep/prune rule and the parent's storage limit decide, design 30 §1.8). The download
 * job writes it, and so does a stream as it plays (design 30 §1.4: through the [ThroughSink], above
 * depth 0, above the free floor); the player reads it first. Each item has its own [keyFactory] (QA #2,
 * qa-21-step2-code S3): a progressive item is one resource keyed "<entry key>:<item key>", an HLS
 * item's playlists and segments are keyed by their path under it.
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
     * next attempt mints again). That rests on two things (qa-26b-step3-code L5): no redirect, and the
     * minted URL being OkHttp's canonical form (`StorytelApi.mintedUrl`) - `CacheDataSource` writes
     * `exo_redir` whenever the request's uri differs from `OkHttpDataSource.getUri()`, which is the
     * request's canonical `HttpUrl`, so a raw Location would land there with no redirect at all.
     */
    private val noRedirectClients: Map<Boolean, OkHttpClient> by lazy {
        mapOf(true to publicClient, false to lanClient).mapValues { (_, c) -> c.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    }

    /**
     * The client for [publicOnly] (the guarded public one, else the LAN one): the Storytel client's
     * transport (design 26b §4.1, QA M2), which sets its own User-Agent and redirect rule per request.
     */
    fun httpClient(publicOnly: Boolean): OkHttpClient = if (publicOnly) publicClient else lanClient

    /**
     * Only http(s) upstream for NRK and RSS (a playlist can't open a file: or content: URI); an HTML
     * answer is never audio (21b §2.5 `bad_media`). Behind the [LengthGuard], for the player and the
     * job alike (design 30 §1.6, M5).
     */
    private fun http(context: Context, publicOnly: Boolean, followRedirects: Boolean = true): DataSource.Factory {
        val okhttp = OkHttpDataSource.Factory(if (followRedirects) httpClient(publicOnly) else noRedirectClients.getValue(publicOnly))
            .setUserAgent("VibbMusic/${BuildConfig.VERSION_NAME}")
            .setContentTypePredicate { type -> !type.lowercase().startsWith("text/html") }
        val cache = get(context)
        return DataSource.Factory { LengthGuard(okhttp.createDataSource()) { key -> storedLength(cache, key) } }
    }

    /** [upstream] behind the [StreamGate]: the streaming switch, asked at every open (design 30 §1.6, L4). */
    private fun gated(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val app = context.applicationContext
        val store = Store.get(app)
        return DataSource.Factory { StreamGate(upstream.createDataSource()) { streamAllowed(netCaps(app), store.streamMobileData) } }
    }

    /**
     * For the player: the item's copy first, then the network through the [StreamGate] - or, for
     * [copyOnly] (a Storytel book, design 26b §4.4: download-only), the copy and nothing else: no
     * upstream at all (Media3 then reads through a placeholder that fails to open), so a missing part
     * is an error, never a request. What streams is written to the cache only through [through] (design
     * 30 §1.4, the [ThroughSink]; null = read only), and a cache error never fails playback
     * (`FLAG_IGNORE_CACHE_ON_ERROR`: the read goes on uncached).
     */
    fun playbackFactory(context: Context, publicOnly: Boolean, keys: CacheKeyFactory, copyOnly: Boolean = false, through: ThroughSink.Factory? = null): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(if (copyOnly) null else gated(context, http(context, publicOnly)))
            .setCacheWriteDataSinkFactory(if (copyOnly) null else through)
            .setCacheKeyFactory(keys)
            .setFlags(if (copyOnly) 0 else CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** For an item whose cached bytes are stale (design 30 §1.6, `ItemTag.uncached`): plain http through the gate, no cache. */
    fun uncachedFactory(context: Context, publicOnly: Boolean): DataSource.Factory = gated(context, http(context, publicOnly))

    /**
     * The cache and nothing else (design 30 §1.5, settle): no upstream, no sink, and a plain
     * `createDataSource()` - never `FLAG_BLOCK_ON_CACHE` - so a read never waits on a lock.
     */
    fun copyOnlyFactory(context: Context, keys: CacheKeyFactory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(get(context))
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)
        .setCacheKeyFactory(keys)

    /** For downloads: written into the cache. [followRedirects] false for a Storytel book (above). */
    fun downloadFactory(context: Context, publicOnly: Boolean, keys: CacheKeyFactory, followRedirects: Boolean = true): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(http(context, publicOnly, followRedirects))
            .setCacheKeyFactory(keys)

    /**
     * The sink of item [ref] (design 30 §1.4): it writes at an open only while [throughWrites] says so -
     * [through] (NRK/RSS), the entry's depth in the current library, the free space now - and makes the
     * item's not-done row ([url], [hls]) before its first write.
     */
    fun throughSink(context: Context, ref: ItemRef, through: Boolean, cacheKey: String, url: String, hls: Boolean): ThroughSink.Factory {
        val app = context.applicationContext
        val store = Store.get(app)
        return ThroughSink.Factory(
            get(app),
            decide = { throughWrites(through, store.library()?.entry(ref.entryId)?.cache, freeBytes(app)) },
            firstWrite = { store.db.insertThrough(DownloadRow(ref.entryId, ref.itemKey, cacheKey, url, hls, false, 0, null, null)) },
        )
    }

    /**
     * The free space where the cache lives (noBackupFilesDir): vibb's `_free_bytes` (`disk_usage(...).free`),
     * not what the system could clear for us - the floors keep the card from filling, they don't make room.
     */
    @SuppressLint("UsableSpace")
    fun freeBytes(context: Context): Long = context.noBackupFilesDir.usableSpace

    /** The stored total length of cache resource [key], or null when unknown. */
    fun storedLength(cache: Cache, key: String): Long? = ContentMetadata.getContentLength(cache.getContentMetadata(key)).takeIf { it >= 0 }

    /** Did the playback fail at the [StreamGate] (the streaming switch, design 30 §1.6)? */
    fun gated(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { it is StreamGateException }

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

    /**
     * Bytes cached per item of [cacheKeys], in one pass over the cache's keys (design 30 §1.8): an HLS
     * item's resources grouped by its [me.vibb.music.logic.hlsKeyPrefix] ([itemOfResource]).
     */
    fun bytesByItem(context: Context, cacheKeys: Set<String>): Map<String, Long> {
        val c = get(context)
        val bytes = HashMap<String, Long>()
        for (key in c.keys) {
            val item = itemOfResource(key, cacheKeys) ?: continue
            bytes[item] = (bytes[item] ?: 0L) + c.getCachedSpans(key).sumOf { it.length }
        }
        return bytes
    }

    /** Every resource key in the cache. */
    fun resourceKeys(context: Context): Set<String> = get(context).keys.toSet()

    /** Delete one cache resource (the orphan sweep, design 30 §1.8). */
    fun removeResource(context: Context, key: String) = get(context).removeResource(key)

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

/**
 * The playback factory's cache sink (design 30 §1.4): Media3's [CacheDataSink], deciding at every open
 * - one per hole of a progressive item, one per HLS segment or playlist - from live state ([decide]).
 * Declined, the open writes nothing (the same no-op as [CacheDataSink]'s unknown-length path). Before
 * its first write it makes the item's row ([firstWrite], M1), so every byte it writes has one. A failure
 * of either is a decline: a stream never fails for its cache.
 */
@OptIn(UnstableApi::class)
class ThroughSink(cache: Cache, private val decide: () -> Boolean, private val firstWrite: () -> Unit) : DataSink {
    private val sink = CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
    private var writing = false
    private var rowMade = false

    override fun open(dataSpec: DataSpec) {
        writing = try {
            decide().also { if (it && !rowMade) firstWrite() }
        } catch (e: Exception) {
            false
        }
        if (!writing) return
        rowMade = true
        sink.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (writing) sink.write(buffer, offset, length)
    }

    override fun close() {
        if (!writing) return
        writing = false
        sink.close()
    }

    /** One sink per data source the player opens for the item. */
    class Factory(private val cache: Cache, private val decide: () -> Boolean, private val firstWrite: () -> Unit) : DataSink.Factory {
        override fun createDataSink(): DataSink = ThroughSink(cache, decide, firstWrite)
    }
}

/** The streaming switch refused a request (design 30 §1.6): "Ikke lastet ned - trenger Wi-Fi", never an item error. */
class StreamGateException : IOException("streaming is not allowed on this network now")

/**
 * The player's upstream (design 30 §1.6, L4): every open asks [allowed] (`streamAllowed` with the
 * streaming switch, live) - a copy's holes and the next item's preload included, which ExoPlayer opens
 * whatever the queue's own check said. The download factory has none: the job has its own gate.
 */
@OptIn(UnstableApi::class)
class StreamGate(private val upstream: DataSource, private val allowed: () -> Boolean) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!allowed()) throw StreamGateException()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

/**
 * The length guard (design 30 §1.6, M5) around the http source, for the player and the job: a source
 * is assumed to serve the same bytes for every request, and a host that stitches ads per request breaks
 * that. A response at a position above 0 whose total length differs from the stored length of the
 * request's cache key ([storedLength]; `CacheDataSource` sets the key) is a changed source
 * ([sourceChanged]): a synthetic 416, so the job takes its `http_416` path (drop the partial, fetch from
 * 0) and the player plays the item on uncached. Same-length re-stitching isn't caught.
 */
@OptIn(UnstableApi::class)
class LengthGuard(private val upstream: HttpDataSource, private val storedLength: (String) -> Long?) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val key = dataSpec.key
        if (dataSpec.position > 0 && key != null) {
            val headers = upstream.responseHeaders
            val changed = sourceChanged(
                storedLength(key), upstream.responseCode, header(headers, "Content-Range"),
                header(headers, "Content-Length")?.trim()?.toLongOrNull(), dataSpec.position,
            )
            if (changed) {
                upstream.close()
                throw HttpDataSource.InvalidResponseCodeException(416, "the source changed under a partial copy", null, headers, dataSpec, ByteArray(0))
            }
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    private fun header(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}
