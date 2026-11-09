package com.kidslauncher.mdm.sms.parse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * The main process's own JPEGs (design 77 D8, D11): every image file the launcher keeps or sends is encoded here from
 * pixels the isolated process handed over - so every image the main process ever decodes is one it encoded itself, and
 * none carries EXIF (GPS included, QA I5): `Bitmap.compress` writes none.
 */
object PictureEncoder {
    class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** A received picture: at most [maxPx] a side (the isolated process already scaled it), quality 85. */
    fun received(bitmap: Bitmap, maxPx: Int = MmsParseProtocol.PICTURE_MAX_PX): Encoded = encode(bitmap, maxPx, QUALITY)

    /**
     * A picture the kid sends (D11): re-encoded under [maxBytes] (the carrier's limit, at most [SEND_DEFAULT_BYTES] -
     * FM lowered its default to 200 KB after reports, FM #815): the quality steps down first, then the size. `null`
     * if even the smallest step doesn't fit.
     */
    fun forSending(bitmap: Bitmap, maxBytes: Int): Encoded? {
        for (px in SEND_SIZES) {
            for (quality in SEND_QUALITIES) {
                val encoded = encode(bitmap, px, quality)
                if (encoded.bytes.size <= maxBytes) return encoded
            }
        }
        return null
    }

    /** The limit a sent picture is re-encoded under: the carrier's maximum less room for the rest of the message, never
     * above [SEND_DEFAULT_BYTES]. [carrierMax] 0 or below = none given. */
    fun sendLimit(carrierMax: Int): Int =
        if (carrierMax <= 0) SEND_DEFAULT_BYTES else minOf(SEND_DEFAULT_BYTES, carrierMax - SEND_HEADROOM_BYTES).coerceAtLeast(SEND_MIN_BYTES)

    /** Scaled to fit [maxPx], drawn on white (a transparent PNG would turn black in a JPEG), compressed. */
    private fun encode(source: Bitmap, maxPx: Int, quality: Int): Encoded {
        val scale = minOf(1f, maxPx.toFloat() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(source, width, height, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
        flat.recycle()
        return Encoded(out.toByteArray(), width, height)
    }

    private const val QUALITY = 85

    /** D11's default for a sent picture (the user's brief: the design's 200 KB). */
    const val SEND_DEFAULT_BYTES = 200 * 1024
    private const val SEND_HEADROOM_BYTES = 8 * 1024
    private const val SEND_MIN_BYTES = 30 * 1024
    private val SEND_SIZES = listOf(1280, 1024, 800, 640, 480)
    private val SEND_QUALITIES = listOf(85, 75, 65, 55)
}
