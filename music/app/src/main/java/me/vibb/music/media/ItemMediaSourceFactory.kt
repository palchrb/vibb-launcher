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

/** What the player needs of an item beyond its URI: its cache key (null = an own file on the
 * phone), whether it is HLS, whether it must come from public addresses only, and whether it plays
 * from its copy only (a Storytel book, design 26b §4.4: no upstream, never a request). */
data class ItemTag(val cacheKey: String?, val hls: Boolean, val publicOnly: Boolean, val copyOnly: Boolean = false)

/**
 * The player's media sources, one data source per item ([ItemTag]): an own file reads the phone's
 * file and nothing else; an NRK or RSS item reads its copy, then http(s) only - through the guarded
 * client unless its entry is `lan`; a Storytel book ([ItemTag.copyOnly]) reads its copy and nothing
 * else. An item without a tag is public-only (fails closed).
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
        val tag = config?.tag as? ItemTag ?: ItemTag(config?.customCacheKey ?: mediaItem.mediaId, false, true)
        val factory = if (tag.cacheKey == null) {
            local
        } else {
            val keys = MediaCache.keyFactory(tag.cacheKey, config?.uri.toString(), tag.hls)
            DefaultMediaSourceFactory(MediaCache.playbackFactory(app, tag.publicOnly, keys, copyOnly = tag.copyOnly))
        }
        drm?.let { factory.setDrmSessionManagerProvider(it) }
        policy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(mediaItem)
    }
}
