package com.kidslauncher.mdm.ui.day

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/** A calendar's dot inline in the day's key (item_day_legend.xml, 2026-10-27): a [sizePx] circle in [color] centred on
 * the text's middle, with [gapPx] after it before the calendar's name. */
class DotSpan(private val color: Int, private val sizePx: Int, private val gapPx: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = sizePx + gapPx

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val old = paint.color
        paint.color = color
        canvas.drawCircle(x + sizePx / 2f, y + (paint.ascent() + paint.descent()) / 2f, sizePx / 2f, paint)
        paint.color = old
    }
}
