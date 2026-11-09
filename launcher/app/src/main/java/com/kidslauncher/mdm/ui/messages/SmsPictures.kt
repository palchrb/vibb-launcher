package com.kidslauncher.mdm.ui.messages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.InputStream

/**
 * A picture part's pixels for the screens (design 77 §6.7, D8): only ever the main process's own JPEG, which the engine's
 * `openPart` hands out - the launcher never decodes an image it didn't encode itself. Decoded down to [maxPx] on its
 * longer side; thumbnails are kept in a small cache by message and part.
 */
object SmsPictures {
    private val thumbnails = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(key: String): Bitmap? = thumbnails.get(key)

    fun keep(key: String, bitmap: Bitmap) {
        thumbnails.put(key, bitmap)
    }

    /** [open] gives a fresh stream each time (the bounds first, then the pixels); `null` when there is none. Not on the
     * main thread. */
    fun decode(open: () -> InputStream?, maxPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (e: Exception) {
            null
        }
    }
}
