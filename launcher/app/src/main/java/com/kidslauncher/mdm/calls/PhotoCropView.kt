package com.kidslauncher.mdm.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * The kid's picture framing (design 79 §5.3): the decoded picture covers a square frame; one finger drags it, two
 * pinch it ([PhotoCrop], pure, clamped after every move); a black 55 % veil with the circle cut out shows what the
 * avatar will show. No slider, no zoom buttons, no rotation. Draws a bitmap the main process encoded itself (design 77
 * D8: the picked file was decoded in the isolated process). Square: its height is its width.
 */
class PhotoCropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var bitmap: Bitmap? = null

    /** Where the frame is; `null` until a picture is set. */
    var crop: PhotoCrop? = null
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val veil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
    private val matrix = Matrix()
    private val hole = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = crop ?: return false
            crop = current.pinched(detector.scaleFactor, detector.focusX, detector.focusY, width.toFloat())
            invalidate()
            return true
        }
    })

    /** Shows [picture] at cover, centred - or as it was framed ([state], after a rotation). */
    fun setPicture(picture: Bitmap, state: PhotoCrop? = null) {
        bitmap = picture
        crop = state?.takeIf { it.width == picture.width && it.height == picture.height }?.clamped()
            ?: PhotoCrop.centered(picture.width, picture.height)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val picture = bitmap ?: return
        val state = crop ?: return
        val frame = width.toFloat()
        val s = state.scale(frame)
        matrix.reset()
        matrix.postTranslate(-state.centerX, -state.centerY)
        matrix.postScale(s, s)
        matrix.postTranslate(frame / 2, frame / 2)
        canvas.drawBitmap(picture, matrix, paint)
        hole.reset()
        hole.fillType = Path.FillType.EVEN_ODD
        hole.addRect(RectF(0f, 0f, frame, frame), Path.Direction.CW)
        hole.addCircle(frame / 2, frame / 2, frame / 2, Path.Direction.CW)
        canvas.drawPath(hole, veil)
    }

    /** One finger drags, two pinch; the gesture is ours alone (the parent may not scroll it away). */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (crop == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> dragging = false
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaler.isInProgress) {
                if (dragging) {
                    crop = crop?.dragged(event.x - lastX, event.y - lastY, width.toFloat())
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
