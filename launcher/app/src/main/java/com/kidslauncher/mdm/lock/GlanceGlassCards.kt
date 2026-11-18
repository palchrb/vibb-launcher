package com.kidslauncher.mdm.lock

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.View
import androidx.annotation.DrawableRes
import com.kidslauncher.mdm.ui.wallpaper.WallpaperFill
import com.kidslauncher.mdm.ui.wallpaper.WallpaperRender
import kotlin.math.min

/** The glance's ground as the glass reads it: the lock's wallpaper fill, its decoded photo and its black scrim
 * (`WallpaperStore.State.lock`, `lockBitmap`, `lockScrimArgb` - what [lockGround] draws). A [Bitmap] compares by identity. */
data class GlassGround(val fill: WallpaperFill, val bitmap: Bitmap?, val scrimArgb: Int)

/**
 * Frosted glass B on the glance's cards (the rules: lock/GlanceGlass.kt; docs/notes/launcher/lock-and-glance.md,
 * "Frosted glass on the glance's cards"). Each registered card's background is either today's solid drawable or a
 * [GlassDrawable]: the small blurred copy of the glance's ground at the card's own place, the tint, the 1 px light edge.
 *
 * - The copy is made once per ground and root size ([sync]: the key changed), on the main thread before the frame draws
 *   (a ~100x220 render of the ground, three box passes each way: a few ms), never per frame; the old copy is let go,
 *   never recycled (a display list may still hold it - the GC frees it).
 * - Each drawn frame [sync] compares every shown card's place in the root with the place its glass was last drawn
 *   for (a scroll, a layout, a row's swipe) and redraws only a card that moved - one round rect from the copy. A fling
 *   moves the scroll inside the draw, after the pre-draw, so the scroller's scroll listener syncs too (code QA M1).
 * - No picture ([glassOn]: not a decoded photo), an empty root or any fault making the copy: the cards keep today's
 *   solid drawables.
 */
internal class GlanceGlassCards(private val root: View) {

    private class Source(val bitmap: Bitmap, val scaleX: Float, val scaleY: Float, val tintArgb: Int)

    private data class Key(val ground: GlassGround, val width: Int, val height: Int)

    private inner class Card(val view: View, @DrawableRes solidRes: Int) {
        /** Today's look - the layout's drawable, a fresh copy. */
        val solid: Drawable = requireNotNull(view.context.getDrawable(solidRes))
        private val shape: GradientDrawable? =
            (solid as? GradientDrawable) ?: ((solid as? RippleDrawable)?.takeIf { it.numberOfLayers > 0 }?.getDrawable(0) as? GradientDrawable)
        val oval = shape?.shape == GradientDrawable.OVAL
        val radius = shape?.cornerRadius ?: 0f
        val glass = GlassDrawable(this)

        /** The glass, with today's press ripple when today's drawable has one (a row, the torch). */
        val background: Drawable = if (solid is RippleDrawable) {
            val mask = GradientDrawable().apply {
                if (oval) this.shape = GradientDrawable.OVAL else cornerRadius = radius
                setColor(0xFFFFFFFF.toInt())
            }
            RippleDrawable(ColorStateList.valueOf(RIPPLE_ARGB), glass, mask)
        } else {
            glass
        }

        /** The view's own background, not ours (the lit torch's peach). */
        var held = false

        /** The view's top-left in the root's pixels when its glass was last drawn ([Int.MIN_VALUE] = not yet). */
        var x = Int.MIN_VALUE
        var y = Int.MIN_VALUE
    }

    /** One card's frosted ground: the copy through a shader placed at the card's spot, the tint, the light edge. */
    private inner class GlassDrawable(private val card: Card) : Drawable() {
        private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        /** The edge's width: 1 dp, the CSS's 1 px (code QA L2). */
        private val edgePx = root.resources.displayMetrics.density
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgePx
            color = GLASS_EDGE_ARGB
        }
        private val matrix = Matrix()
        private val rect = RectF()
        private var shaderOf: Source? = null
        private var shader: BitmapShader? = null

        private fun radius(): Float =
            if (card.oval) min(bounds.width(), bounds.height()) / 2f else min(card.radius, min(bounds.width(), bounds.height()) / 2f)

        override fun draw(canvas: Canvas) {
            val src = source ?: return
            if (bounds.isEmpty) return
            val r = radius()
            rect.set(bounds)
            if (card.x == Int.MIN_VALUE) {
                // Its place isn't measured yet (code QA L3): today's solid tile for this frame, never a see-through card.
                tint.color = (0xFF shl 24) or GLASS_TINT_RGB
                canvas.drawRoundRect(rect, r, r, tint)
                return
            }
            if (shaderOf !== src) {
                shader = BitmapShader(src.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderOf = src
            }
            val s = shader ?: return
            // The copy's pixel -> the root's pixel -> this view's.
            matrix.setScale(src.scaleX, src.scaleY)
            matrix.postTranslate(-card.x.toFloat(), -card.y.toFloat())
            s.setLocalMatrix(matrix)
            picture.shader = s
            canvas.drawRoundRect(rect, r, r, picture)
            tint.color = src.tintArgb
            canvas.drawRoundRect(rect, r, r, tint)
            val inset = edgePx / 2
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, edge)
        }

        override fun getOutline(outline: Outline) {
            if (card.oval) outline.setOval(bounds) else outline.setRoundRect(bounds, radius())
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val cards = mutableListOf<Card>()
    private var ground: GlassGround? = null
    private var builtFor: Key? = null
    private var source: Source? = null
    private val at = IntArray(2)
    private val rootAt = IntArray(2)

    /** A card on the glance: [solidRes] is its drawable today (its shape, radius and ripple are read from it). */
    fun add(view: View, @DrawableRes solidRes: Int) {
        val card = Card(view, solidRes)
        cards += card
        apply(card)
    }

    /** The glance's ground changed (the lock's wallpaper listener): the copy is made again at the next frame if it differs. */
    fun ground(ground: GlassGround?) {
        this.ground = ground
    }

    /** [view] keeps its own background ([held], the lit torch) or takes the glass again. */
    fun hold(view: View, held: Boolean) {
        val card = cards.firstOrNull { it.view === view } ?: return
        card.held = held
        apply(card)
    }

    /** Whether [view]'s background is the glass now (so its owner sets no solid one). */
    fun drawn(view: View): Boolean = source != null && cards.any { it.view === view && !it.held }

    /** Before each frame is drawn: the copy made again if the ground or the root's size changed, then every shown card
     * that moved redrawn at its new place. */
    fun sync() {
        val g = ground
        val w = root.width
        val h = root.height
        val key = if (g != null && glassOn(g.fill, g.bitmap != null) && w > 0 && h > 0) Key(g, w, h) else null
        if (key != builtFor) {
            builtFor = key
            source = key?.let(::build)
            for (card in cards) {
                card.x = Int.MIN_VALUE
                card.glass.invalidateSelf()
                apply(card)
            }
        }
        if (source == null) return
        root.getLocationInWindow(rootAt)
        for (card in cards) {
            if (card.held || !card.view.isShown) continue
            card.view.getLocationInWindow(at)
            val x = at[0] - rootAt[0]
            val y = at[1] - rootAt[1]
            if (x != card.x || y != card.y) {
                card.x = x
                card.y = y
                card.glass.invalidateSelf()
            }
        }
    }

    private fun apply(card: Card) {
        if (card.held) return
        val view = card.view
        if (source != null) {
            if (view.background !== card.background) view.background = card.background
        } else if (view.background === card.background) {
            view.background = card.solid
        }
    }

    /** The small copy: the ground drawn at the copy's size, blurred, saturated; null on any fault (solid cards). */
    private fun build(key: Key): Source? = try {
        val size = glassCopySize(key.width, key.height, root.resources.displayMetrics.density)
        if (size == null) {
            null
        } else {
            val (cw, ch) = size
            val copy = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(copy)
            canvas.scale(cw / key.width.toFloat(), ch / key.height.toFloat())
            val drawn = WallpaperRender.GroundDrawable(key.ground.fill, key.ground.bitmap, key.ground.scrimArgb)
            drawn.setBounds(0, 0, key.width, key.height)
            drawn.draw(canvas)
            val pixels = IntArray(cw * ch)
            copy.getPixels(pixels, 0, cw, 0, 0, cw, ch)
            val glass = glassCopy(pixels, cw, ch)
            copy.setPixels(glass, 0, cw, 0, 0, cw, ch)
            copy.prepareToDraw()
            Source(copy, key.width / cw.toFloat(), key.height / ch.toFloat(), (glassTintAlphaOver(glass) shl 24) or GLASS_TINT_RGB)
        }
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "Glance glass failed, the cards stay solid: ${t.javaClass.simpleName}")
        null
    }

    private companion object {
        const val LOG_TAG = "GlanceGlass"

        /** Today's press ripple on a row and the torch (`bg_glance_row`, `bg_music_control`). */
        const val RIPPLE_ARGB = 0x33FFFFFF
    }
}
