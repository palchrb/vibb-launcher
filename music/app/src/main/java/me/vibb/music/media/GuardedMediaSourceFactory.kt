package me.vibb.music.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * The player's media sources: public-only (MediaCache's guarded client) unless the item is tagged
 * [ALLOW_PRIVATE] (an entry that may live on the home LAN, logic/Addresses.kt `publicOnly`).
 * Fails closed: an untagged item is public-only.
 */
@OptIn(UnstableApi::class)
class GuardedMediaSourceFactory(context: Context) : MediaSource.Factory {
    private val publicOnly = DefaultMediaSourceFactory(MediaCache.playbackFactory(context, publicOnly = true))
    private val anyAddress = DefaultMediaSourceFactory(MediaCache.playbackFactory(context, publicOnly = false))

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        publicOnly.setDrmSessionManagerProvider(drmSessionManagerProvider)
        anyAddress.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        publicOnly.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        anyAddress.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun getSupportedTypes(): IntArray = publicOnly.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        (if (mediaItem.localConfiguration?.tag === ALLOW_PRIVATE) anyAddress else publicOnly).createMediaSource(mediaItem)

    companion object {
        /** The tag of an item that may come from a private address. */
        val ALLOW_PRIVATE = Any()
    }
}
