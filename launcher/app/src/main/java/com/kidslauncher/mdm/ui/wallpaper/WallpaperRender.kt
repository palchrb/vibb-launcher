package com.kidslauncher.mdm.ui.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Draws a [WallpaperFill]: the same code for the screens' ground and the system wallpaper. */
object WallpaperRender {
    /** The mockup's CSS `linear-gradient(160deg, from, to)`. */
    private const val GRADIENT_DEGREES = 160.0

    /** A placeholder while a photo isn't decoded (the mockup's grey-blue). */
    const val PHOTO_PLACEHOLDER: Int = 0xFF5C6B7A.toInt()

    fun draw(canvas: Canvas, bounds: Rect, fill: WallpaperFill, bitmap: Bitmap?, paint: Paint) {
        paint.shader = null
        when (fill) {
            is WallpaperFill.Solid -> {
                paint.color = fill.argb
                canvas.drawRect(bounds, paint)
            }
            is WallpaperFill.Gradient -> {
                // CSS: 0° points up, 90° right; the line is long enough to reach the corners.
                val rad = Math.toRadians(GRADIENT_DEGREES)
                val dx = sin(rad).toFloat()
                val dy = (-cos(rad)).toFloat()
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val half = (abs(w * dx) + abs(h * dy)) / 2
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                paint.color = fill.from
                paint.shader = LinearGradient(
                    cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    fill.from, fill.to, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(bounds, paint)
                paint.shader = null
            }
            is WallpaperFill.Image -> {
                if (bitmap == null) {
                    paint.color = PHOTO_PLACEHOLDER
                    canvas.drawRect(bounds, paint)
                } else {
                    canvas.drawBitmap(bitmap, centreCrop(bitmap.width, bitmap.height, bounds), RectF(bounds), paint)
                }
            }
        }
    }

    /** The part of a `w`×`h` image that fills [bounds] without distortion (centre crop). */
    fun centreCrop(w: Int, h: Int, bounds: Rect): Rect {
        val bw = bounds.width().coerceAtLeast(1)
        val bh = bounds.height().coerceAtLeast(1)
        return if (w.toLong() * bh > h.toLong() * bw) {
            val cw = (h.toLong() * bw / bh).toInt()
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = (w.toLong() * bh / bw).toInt()
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
    }

    /** A screen's ground: the wallpaper, then the scrim of its [InkChoice]. */
    class GroundDrawable(
        private val fill: WallpaperFill?,
        private val bitmap: Bitmap?,
        private val scrimArgb: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (fill != null) draw(canvas, bounds, fill, bitmap, paint)
            if ((scrimArgb ushr 24) != 0) {
                paint.shader = null
                paint.color = scrimArgb
                canvas.drawRect(bounds, paint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int =
            if (fill != null) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
    }
}
