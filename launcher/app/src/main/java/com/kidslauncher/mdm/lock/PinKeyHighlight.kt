package com.kidslauncher.mdm.lock

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The PIN key's top highlight (design 34 P7): a thin light arc, [PinKeypadLayout.HIGHLIGHT_STROKE_DP]
 * wide at [density] (1 dp, as the mockups draw it - qa-34-s8-code N3), along the top of the key's oval,
 * drawn over `bg_pin_key` (translucent, so the lock's wallpaper and scrim still show through the key -
 * an opaque key with a highlight under it would not). [PinKeypadLayout.HIGHLIGHT_START_DEG] and
 * [PinKeypadLayout.HIGHLIGHT_SWEEP_DEG] place it, [PinKeypadLayout.HIGHLIGHT_ARGB] colours it. Nothing
 * animates; it draws the same in every state.
 */
class PinKeyHighlight(density: Float) : Drawable() {
    private val stroke = PinKeypadLayout.HIGHLIGHT_STROKE_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = PinKeypadLayout.HIGHLIGHT_ARGB
    }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Half the stroke inside the bounds, so the arc lies on the oval's edge, not outside it.
        val half = stroke / 2
        oval.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        canvas.drawArc(oval, PinKeypadLayout.HIGHLIGHT_START_DEG, PinKeypadLayout.HIGHLIGHT_SWEEP_DEG, false, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha * (PinKeypadLayout.HIGHLIGHT_ARGB ushr 24) / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
