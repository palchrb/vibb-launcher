package com.kidslauncher.mdm.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.palette.graphics.Palette
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.ContactPhotos
import com.kidslauncher.mdm.calls.MissedSummary
import com.kidslauncher.mdm.calls.RuleContact

/** Drawing helpers shared by Home, the phone book and the contact sheet. */
object KidAvatars {

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /**
     * A contact's round avatar: the cached photo ([ContactPhotos]) cropped to a circle, or the
     * initial on its palette colour ([avatarStyle]).
     */
    fun bindContact(photo: ImageView, initial: TextView, contact: RuleContact, isEmergency: Boolean, initialSp: Float) {
        val context = photo.context
        val style = avatarStyle(contact, isEmergency)
        val bitmap = if (isEmergency) null else ContactPhotos.cached(context, contact.photo)
        if (bitmap != null) {
            photo.backgroundTintList = null
            photo.setImageDrawable(
                RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
            )
            initial.visibility = View.GONE
        } else {
            photo.setImageDrawable(null)
            photo.backgroundTintList = ColorStateList.valueOf(style.colors.background.toInt())
            initial.visibility = View.VISIBLE
            initial.text = style.text
            initial.setTextColor(style.colors.ink.toInt())
            // "112" must fit the circle as well as one letter.
            initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (style.text.length > 2) initialSp * 0.7f else initialSp)
        }
    }

    /**
     * Sizes an `item_kid_contact` for Home ([contactRow]): the avatar, a frame 12 dp wider and
     * 6 dp taller for the badges, and the green call badge scaled with the avatar (28 dp at 76).
     */
    fun sizeContact(item: View, avatarDp: Int) {
        val context = item.context
        item.findViewById<View>(R.id.contact_frame).layoutParams.apply {
            width = dp(context, avatarDp + 12f)
            height = dp(context, avatarDp + 6f)
        }
        item.findViewById<View>(R.id.contact_avatar).layoutParams.apply {
            width = dp(context, avatarDp.toFloat())
            height = dp(context, avatarDp.toFloat())
        }
        val scale = avatarDp / 76f
        item.findViewById<View>(R.id.contact_call_badge).apply {
            layoutParams.width = dp(context, 28f * scale)
            layoutParams.height = dp(context, 28f * scale)
            val pad = dp(context, 7f * scale)
            setPadding(pad, pad, pad, pad)
        }
    }

    /** The coloured ring around the sheet's photo, in the contact's colour. */
    fun ring(context: Context, contact: RuleContact, isEmergency: Boolean): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 4f), avatarStyle(contact, isEmergency).colors.background.toInt())
        }

    /** A badge pill: hidden for 0, else the count (missed calls also get their icon). */
    fun bindBadge(badge: TextView, count: Int) {
        val text = badgeText(count)
        badge.visibility = if (text == null) View.GONE else View.VISIBLE
        badge.text = text
    }

    fun missedText(context: Context, summary: MissedSummary?): String? =
        summary?.let { MissedCallText.describe(context, it) }

    private val iconCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Cache key of a rendered app icon: the app, the size and the density (QA 08 #5). */
    fun iconKey(context: Context, key: String, sizePx: Int): String =
        "$key|$sizePx|${context.resources.displayMetrics.densityDpi}"

    /** An already-rendered icon, or null. Never renders: safe on the main thread. */
    fun cachedAppIcon(context: Context, key: String, sizePx: Int): Bitmap? =
        iconCache.get(iconKey(context, key, sizePx))

    /**
     * An app icon as a coloured circle (design 08 §1) - **off the main thread only** (loading,
     * drawing and the palette pass are too slow for `onBind`; [HomeActivity] calls this from its
     * `refreshApps` pass):
     * 1. an adaptive icon with a monochrome layer: that glyph in white on [tileColor] of the
     *    icon's own colour;
     * 2. an adaptive icon without one: its layers edge to edge in the circle;
     * 3. a legacy bitmap icon: inset 1/8 on [tileColor] of its colour.
     * The result is cached by app, size and density.
     */
    fun renderAppIcon(context: Context, key: String, icon: () -> Drawable, sizePx: Int): Bitmap {
        val cacheKey = iconKey(context, key, sizePx)
        iconCache.get(cacheKey)?.let { return it }
        // Our own copy: the drawer draws the same cached icon object on the main thread, so the
        // background pass must not change its bounds or tint (qa-08-code.md #5).
        val drawable = privateCopy(context, icon())
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG)
        val monochrome = (drawable as? AdaptiveIconDrawable)?.monochrome
        when {
            drawable is AdaptiveIconDrawable && monochrome != null -> {
                circle.color = tileColor(seedColour(drawable))
                canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, circle)
                // A layer of our private copy; mutate() anyway so its constant state isn't shared.
                val glyph = monochrome.mutate()
                glyph.setTintMode(PorterDuff.Mode.SRC_IN)
                glyph.setTint(Color.WHITE)
                val extra = sizePx / 4
                glyph.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
                glyph.draw(canvas)
            }
            drawable is AdaptiveIconDrawable -> {
                // Layers are 108 dp with a 72 dp safe zone: draw them 1.5× and centred.
                val extra = sizePx / 4
                listOfNotNull(drawable.background, drawable.foreground).forEach {
                    it.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
                    it.draw(canvas)
                }
            }
            else -> {
                circle.color = tileColor(seedColour(drawable))
                canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, circle)
                val inset = sizePx / 8
                drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
                drawable.draw(canvas)
            }
        }
        iconCache.put(cacheKey, bmp)
        return bmp
    }

    /** A new drawable from the icon's constant state, mutated (falls back to the original only
     * when it has none - then nothing else shares a state with it either). */
    private fun privateCopy(context: Context, drawable: Drawable): Drawable =
        drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable

    /** The icon's own colour: vibrant swatch, else dominant, else grey (a 48 px render). */
    private fun seedColour(drawable: Drawable): Int = try {
        val small = createBitmap(SEED_PX, SEED_PX)
        val canvas = Canvas(small)
        val bounds = drawable.copyBounds()
        if (drawable is AdaptiveIconDrawable) {
            val extra = SEED_PX / 4
            listOfNotNull(drawable.background, drawable.foreground).forEach {
                val b = it.copyBounds()
                it.setBounds(-extra, -extra, SEED_PX + extra, SEED_PX + extra)
                it.draw(canvas)
                it.bounds = b
            }
        } else {
            drawable.setBounds(0, 0, SEED_PX, SEED_PX)
            drawable.draw(canvas)
            drawable.bounds = bounds
        }
        val palette = Palette.from(small).generate()
        small.recycle()
        palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: TILE_GREY
    } catch (e: Exception) {
        TILE_GREY
    }

    private const val SEED_PX = 48

    /** The rendered icon as a circle for an ImageView. */
    fun circular(context: Context, bitmap: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
}
