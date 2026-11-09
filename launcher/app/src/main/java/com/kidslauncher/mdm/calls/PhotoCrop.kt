package com.kidslauncher.mdm.calls

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The kid's picture framing (design 79 §5.3, `PhotoCropTest`) - pure. The decoded picture covers a square frame; one
 * finger drags it, two pinch it, from 1x (cover) to [photoMaxZoom]; it is clamped after every move so the frame never
 * shows anything but the picture. No rotation, no slider.
 */

/** A picture under this many px on its short side is used at 1x, never enlarged (QA L6). */
const val PHOTO_ZOOM_BASE_PX = 384

/** The most a picture is zoomed past cover. */
const val PHOTO_ZOOM_MAX = 3f

/** The zoom cap of a [width] x [height] picture: `max(1, min(3, shortSide / 384))` (QA L6). */
fun photoMaxZoom(width: Int, height: Int): Float = max(1f, min(PHOTO_ZOOM_MAX, min(width, height) / PHOTO_ZOOM_BASE_PX.toFloat()))

/** The square cut out of the picture, in its px. */
data class PhotoCut(val left: Int, val top: Int, val side: Int)

/**
 * Where the frame is on a [width] x [height] picture: [zoom] past cover (1 = the short side fills the frame) and the
 * frame's centre ([centerX], [centerY]) in the picture's px. Every change returns a new, clamped state.
 */
data class PhotoCrop(val width: Int, val height: Int, val zoom: Float, val centerX: Float, val centerY: Float) {
    private val shortSide: Int get() = min(width, height)

    /** The frame's side in the picture's px. */
    val visibleSide: Float get() = shortSide / zoom

    /** Screen px per picture px for a frame [framePx] wide. */
    fun scale(framePx: Float): Float = framePx / shortSide * zoom

    /** Dragged by ([dx], [dy]) screen px on a [framePx] frame: the picture follows the finger. */
    fun dragged(dx: Float, dy: Float, framePx: Float): PhotoCrop {
        val s = scale(framePx)
        return copy(centerX = centerX - dx / s, centerY = centerY - dy / s).clamped()
    }

    /** Pinched by [factor] around ([focusX], [focusY]) in the frame's screen px: the point under the fingers stays. */
    fun pinched(factor: Float, focusX: Float, focusY: Float, framePx: Float): PhotoCrop {
        val s = scale(framePx)
        val pointX = centerX + (focusX - framePx / 2) / s
        val pointY = centerY + (focusY - framePx / 2) / s
        val nextZoom = (zoom * factor).coerceIn(1f, photoMaxZoom(width, height))
        val next = copy(zoom = nextZoom)
        val s2 = next.scale(framePx)
        return next.copy(centerX = pointX - (focusX - framePx / 2) / s2, centerY = pointY - (focusY - framePx / 2) / s2).clamped()
    }

    /** The zoom within 1..cap, the frame inside the picture. */
    fun clamped(): PhotoCrop {
        val z = zoom.coerceIn(1f, photoMaxZoom(width, height))
        val half = shortSide / z / 2
        return copy(
            zoom = z,
            centerX = centerX.coerceIn(half, width - half),
            centerY = centerY.coerceIn(half, height - half),
        )
    }

    /** The square to keep, in the picture's px, inside it. */
    fun cut(): PhotoCut {
        val side = visibleSide.roundToInt().coerceIn(1, shortSide)
        val left = (centerX - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (centerY - side / 2f).roundToInt().coerceIn(0, height - side)
        return PhotoCut(left, top, side)
    }

    companion object {
        /** A new framing: cover, centred - also what TalkBack's "Bruk bildet" takes. */
        fun centered(width: Int, height: Int): PhotoCrop = PhotoCrop(width, height, 1f, width / 2f, height / 2f)
    }
}
