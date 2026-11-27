package me.vibb.music.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.isSealedKey
import me.vibb.music.logic.isStorytelSourceId

/**
 * What the player needs of an item beyond its URI: its cache key (null = an own file on the phone),
 * whether it is HLS, whether it must come from public addresses only, and whether it plays from its
 * copy only (a Storytel book, design 26b §4.4: no upstream, never a request). Design 30 §1.4, §1.6:
 * [through] - an NRK or RSS item, whose stream may be written to the cache as it plays (the static part
 * of `throughWrites`); [uncached] - its cached bytes are stale (`staleThrough`, a changed source), so it
 * plays plain http, no cache read or write; [ref] - the item, for the sink's row.
 */
data class ItemTag(
    val cacheKey: String?,
    val hls: Boolean,
    val publicOnly: Boolean,
    val copyOnly: Boolean = false,
    val through: Boolean = false,
    val uncached: Boolean = false,
    val ref: ItemRef? = null,
)

/** Which factory an item's bytes come through (design 117 §4.5). */
enum class ItemRoute { STORYTEL, LOCAL, UNCACHED, PLAIN }

/**
 * The route of an item with [tag] (null: none), [customCacheKey] and [uri]: a Storytel book - copy-only by its tag, a
 * sealed key by its tag or its custom key, or a Storytel source id - only ever through the Storytel factory; an own
 * file locally; a stale item uncached; everything else through the plain factory with an explicit sink.
 */
fun itemRoute(tag: ItemTag?, customCacheKey: String?, uri: String?): ItemRoute = when {
    tag?.copyOnly == true || isSealedKey(tag?.cacheKey) || isSealedKey(customCacheKey) || isStorytelSourceId(uri) -> ItemRoute.STORYTEL
    tag != null && tag.cacheKey == null -> ItemRoute.LOCAL
    tag?.uncached == true -> ItemRoute.UNCACHED
    else -> ItemRoute.PLAIN
}

/** Is a plain item written through as it plays (design 30 §1.4)? An NRK or RSS item with its ref; else `NoWriteSink`. */
fun writesThrough(tag: ItemTag?): Boolean = tag != null && tag.ref != null && tag.through && !tag.copyOnly

/**
 * The player's media sources, one data source per item ([ItemTag]): an own file reads the phone's
 * file and nothing else; an NRK or RSS item reads its copy, then http(s) only through the streaming
 * gate - through the guarded client unless its entry is `lan` - written to the cache by the
 * `ThroughSink` (design 30 §1.4), or plain http with no cache when [ItemTag.uncached]; a Storytel book
 * ([ItemTag.copyOnly], or any sealed key) reads its sealed copy and nothing else (design 117). An item
 * without a tag is public-only and writes nothing (`NoWriteSink`: fails closed, design 117 §4.5).
 */
@OptIn(UnstableApi::class)
class ItemMediaSourceFactory(context: Context) : MediaSource.Factory {
    private val app = context.applicationContext
    private var drm: DrmSessionManagerProvider? = null
    private var policy: LoadErrorHandlingPolicy? = null
    private val local by lazy { DefaultMediaSourceFactory(DefaultDataSource.Factory(app)) }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { drm = drmSessionManagerProvider }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { policy = loadErrorHandlingPolicy }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val itemTag = config?.tag as? ItemTag
        val url = config?.uri.toString()
        val tag = itemTag ?: ItemTag(config?.customCacheKey ?: mediaItem.mediaId, false, true)
        val factory = when (itemRoute(itemTag, config?.customCacheKey, url)) {
            ItemRoute.STORYTEL -> {
                // Design 117 §4.5: a sealed or Storytel key, by its tag or its custom key, is read only sealed.
                val keys = MediaCache.keyFactory(tag.cacheKey ?: mediaItem.mediaId, url, false)
                DefaultMediaSourceFactory(MediaCache.storytelPlaybackFactory(app, keys, BookKeys.get(app)))
            }
            ItemRoute.LOCAL -> local
            ItemRoute.UNCACHED -> DefaultMediaSourceFactory(MediaCache.uncachedFactory(app, tag.publicOnly))
            ItemRoute.PLAIN -> {
                val cacheKey = tag.cacheKey ?: mediaItem.mediaId
                val keys = MediaCache.keyFactory(cacheKey, url, tag.hls)
                // Always an explicit sink (§4.5, QA L10): the ThroughSink for an NRK or RSS item with its ref, else none.
                val through = itemTag?.ref?.takeIf { writesThrough(itemTag) }?.let { MediaCache.throughSink(app, it, true, cacheKey, url, tag.hls) }
                DefaultMediaSourceFactory(MediaCache.playbackFactory(app, tag.publicOnly, keys, through ?: NoWriteSink.Factory))
            }
        }
        drm?.let { factory.setDrmSessionManagerProvider(it) }
        policy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(mediaItem)
    }
}
