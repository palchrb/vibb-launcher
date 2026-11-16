package com.kidslauncher.mdm.calls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.home.KidAvatars

/**
 * The Siste tab's badge (design 106 §3.5): drawn inline after the word, so the label wraps as one piece - a 20 dp rust
 * pill (`kid_badge`, the same in both looks) with its count in 12 sp white Nunito 800, 6 dp after the word. The line
 * keeps the word's own height.
 */
class RecentBadgeSpan(context: Context) : ReplacementSpan() {
    private val heightPx = KidAvatars.dp(context, PILL_DP).toFloat()
    private val gapPx = KidAvatars.dp(context, GAP_DP).toFloat()
    private val padPx = KidAvatars.dp(context, PAD_DP).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kid_badge) }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.kid_badge_ink)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, context.resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.nunito_extrabold)
        textAlign = Paint.Align.CENTER
    }

    private fun pillWidth(text: CharSequence, start: Int, end: Int): Float =
        maxOf(heightPx, ink.measureText(text, start, end) + 2 * padPx)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return (gapPx + pillWidth(text, start, end)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = pillWidth(text, start, end)
        // Centred on the word's own middle, not the line's (a wrapped label's line may be taller).
        val centre = y + (paint.ascent() + paint.descent()) / 2
        val rect = RectF(x + gapPx, centre - heightPx / 2, x + gapPx + width, centre + heightPx / 2)
        canvas.drawRoundRect(rect, heightPx / 2, heightPx / 2, fill)
        canvas.drawText(text, start, end, rect.centerX(), centre - (ink.ascent() + ink.descent()) / 2, ink)
    }

    private companion object {
        const val PILL_DP = 20f
        const val GAP_DP = 6f
        const val PAD_DP = 6f
        const val TEXT_SP = 12f
    }
}
