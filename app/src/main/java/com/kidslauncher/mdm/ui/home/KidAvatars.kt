package com.kidslauncher.mdm.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    /**
     * An app icon as a full circle: an adaptive icon's layers drawn edge to edge (the system's
     * own mask is replaced by a circle), anything else centred on a light circle.
     */
    fun roundAppIcon(context: Context, key: String, icon: () -> Drawable, sizePx: Int): Drawable {
        val bitmap = iconCache.get(key) ?: run {
            val drawable = icon()
            val bmp = createBitmap(sizePx, sizePx)
            val canvas = Canvas(bmp)
            if (drawable is AdaptiveIconDrawable) {
                // Layers are 108 dp with a 72 dp safe zone: draw them 1.5× and centred.
                val extra = sizePx / 4
                listOfNotNull(drawable.background, drawable.foreground).forEach {
                    it.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
                    it.draw(canvas)
                }
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kid_icon_fallback) }
                canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
                val inset = sizePx / 8
                drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
                drawable.draw(canvas)
            }
            bmp.also { iconCache.put(key, it) }
        }
        return RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
    }
}
