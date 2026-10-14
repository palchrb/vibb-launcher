package com.kidslauncher.mdm.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.appdisplay.AppDisplayEntry
import com.kidslauncher.mdm.appdisplay.AppGlyphs
import com.kidslauncher.mdm.appdisplay.OWN_ICON
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.appdisplay.appIconKey
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
            photo.setBackgroundResource(R.drawable.bg_kid_circle)
            photo.backgroundTintList = null
            photo.setImageDrawable(
                RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
            )
            initial.visibility = View.GONE
        } else {
            photo.setImageDrawable(null)
            if (isEmergency) {
                // The tonal red disc with a red ring, on every surface (qa-34-s3-code L2): the
                // dark disc alone is ~1.1-1.3:1 on the night, so the ring gives it its edge.
                photo.backgroundTintList = null
                photo.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(style.colors.background.toInt())
                    setStroke(dp(context, EMERGENCY_RING_DP), style.colors.ink.toInt())
                }
            } else {
                // A recycled view (the phone book's grid) may still hold the emergency disc.
                photo.setBackgroundResource(R.drawable.bg_kid_circle)
                photo.backgroundTintList = ColorStateList.valueOf(style.colors.background.toInt())
            }
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

    /**
     * The green call badge with a ring in the wallpaper's ink (white on dark grounds, near-black on
     * light ones - [com.kidslauncher.mdm.ui.wallpaper.InkChoice]), so it stays legible on any
     * wallpaper (emulator run 2026-10-06; it was a ring in the fixed ground colour).
     */
    fun bindCallBadge(badge: View, ink: com.kidslauncher.mdm.ui.wallpaper.InkChoice) {
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(badge.context.getColor(R.color.kid_call_badge))
            setStroke(dp(badge.context, 2f), ink.ink)
        }
        badge.visibility = View.VISIBLE
    }

    /** The ring around the sheet's photo, in the contact's colour ([avatarRingColour]). */
    fun ring(context: Context, contact: RuleContact, isEmergency: Boolean): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 4f), avatarRingColour(avatarStyle(contact, isEmergency).colors, isEmergency).toInt())
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

    /** Cache key of a rendered app icon: the app, the size and the density (QA 08 #5), plus the
     * parent's glyph and colour (design 14, [appIconKey]) - one key for the lookup and the render. */
    fun iconKey(context: Context, key: String, sizePx: Int): String = iconKey(context, key, sizePx, displayOf(key))

    /** [iconKey] for a choice already read - the render keys and draws the same one (qa-14-code #1). */
    private fun iconKey(context: Context, key: String, sizePx: Int, display: AppDisplayEntry?): String =
        appIconKey(key, sizePx, context.resources.displayMetrics.densityDpi, display)

    /** An already-rendered icon, or null. Never renders: safe on the main thread. */
    fun cachedAppIcon(context: Context, key: String, sizePx: Int): Bitmap? =
        iconCache.get(iconKey(context, key, sizePx))

    /** The parent's choice for the app behind a serialized [AppInfo] key (design 14), if any. */
    fun displayOf(key: String): AppDisplayEntry? {
        if (AppDisplay.map.isEmpty()) return null
        val packageName = try {
            (AbstractAppInfo.deserialize(key) as? AppInfo)?.packageName
        } catch (e: Exception) {
            null
        }
        return AppDisplay.entry(packageName)
    }

    /**
     * An app icon as a circle (design 08 §1; the three states of design 34 P1) - **off the main
     * thread only** (loading, drawing and the palette pass are too slow for `onBind`;
     * [HomeActivity] calls this from its `refreshApps` pass):
     * 1. the app's own logo (the parent chose [OWN_ICON], checked first): [drawOwnLogo];
     * 2. a parent glyph: [renderSymbolIcon] on the colour's tile, or the app's tone for `auto`;
     * 3. Vibb style (the default): an adaptive icon with a monochrome layer - that glyph in white on
     *    [iconTone] of the icon's colours; any other icon - [drawOwnLogo] (nothing to tone).
     * The result is cached by app, size and density, plus the parent's choice ([appIconKey]).
     */
    fun renderAppIcon(context: Context, key: String, icon: () -> Drawable, sizePx: Int): Bitmap {
        // The parent's choice (design 14, 34) - read once, so a sync in between can't store one
        // choice's bitmap under the other's key (qa-14-code #1).
        val display = displayOf(key)
        val cacheKey = iconKey(context, key, sizePx, display)
        iconCache.get(cacheKey)?.let { return it }
        val packageName = packageOf(key)
        // Our own copy: the drawer draws the same cached icon object on the main thread, so the
        // background pass must not change its bounds or tint (qa-08-code.md #5).
        if (display?.ownLogo == true) {
            return drawOwnLogo(context, privateCopy(context, icon()), sizePx).also { iconCache.put(cacheKey, it) }
        }
        val glyph = display?.icon?.let { AppGlyphs.ICONS[it] }
        if (display != null && glyph != null) {
            val tile = AppGlyphs.COLORS[display.color]?.tile
                ?: toneOf(privateCopy(context, icon()), packageName)
            return renderSymbolIcon(context, glyph, tile, sizePx).also { iconCache.put(cacheKey, it) }
        }
        val drawable = privateCopy(context, icon())
        val monochrome = (drawable as? AdaptiveIconDrawable)?.monochrome
        if (drawable !is AdaptiveIconDrawable || monochrome == null) {
            return drawOwnLogo(context, drawable, sizePx).also { iconCache.put(cacheKey, it) }
        }
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = toneOf(drawable, packageName) }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, circle)
        // A layer of our private copy; mutate() anyway so its constant state isn't shared.
        val mono = monochrome.mutate()
        mono.setTintMode(PorterDuff.Mode.SRC_IN)
        mono.setTint(Color.WHITE)
        val extra = sizePx / 4
        mono.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
        mono.draw(canvas)
        iconCache.put(cacheKey, bmp)
        return bmp
    }

    /**
     * The app's own logo (design 34 P1), never the monochrome layer, never tinted: an adaptive
     * icon's background and foreground layers in their original colours, 1.5x (108 dp layers with a
     * 72 dp safe zone) and centred; a legacy icon inset 1/8 on the light neutral disc
     * (`kid_icon_fallback`). Clipped to the circle.
     */
    private fun drawOwnLogo(context: Context, drawable: Drawable, sizePx: Int): Bitmap {
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        val clip = Path().apply { addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (drawable is AdaptiveIconDrawable) {
            val extra = sizePx / 4
            listOfNotNull(drawable.background, drawable.foreground).forEach {
                it.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
                it.draw(canvas)
            }
        } else {
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.kid_icon_fallback) }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, disc)
            val inset = sizePx / 8
            drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            drawable.draw(canvas)
        }
        return bmp
    }

    /** The package of a serialized [AppInfo] key, or null. */
    private fun packageOf(key: String): String? = try {
        (AbstractAppInfo.deserialize(key) as? AppInfo)?.packageName
    } catch (e: Exception) {
        null
    }

    /**
     * The parent's icon (design 14): a [tile] circle (an [AppGlyphs.COLORS] tile - one of the
     * tones - or the app's tone; white on every tone reaches 5.3:1) with the white glyph at about
     * 55 %. Off the main thread, like [renderAppIcon].
     */
    fun renderSymbolIcon(context: Context, glyphRes: Int, tile: Int, sizePx: Int): Bitmap {
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tile }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, circle)
        val glyph = ContextCompat.getDrawable(context, glyphRes)?.mutate()
        if (glyph != null) {
            glyph.setTint(Color.WHITE)
            val inset = (sizePx * (1 - GLYPH_FRACTION) / 2).toInt()
            glyph.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            glyph.draw(canvas)
        }
        return bmp
    }

    /** The glyph's share of the tile. */
    private const val GLYPH_FRACTION = 0.55f

    /** A new drawable from the icon's constant state, mutated (falls back to the original only
     * when it has none - then nothing else shares a state with it either). */
    private fun privateCopy(context: Context, drawable: Drawable): Drawable =
        drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable

    /**
     * The tone behind the app's white glyph (design 34 P1): [iconTone] of the icon's palette
     * swatches (a 48 px render of its full-colour layers) and the package; the package's hash tone
     * when the palette fails.
     */
    private fun toneOf(drawable: Drawable, packageName: String?): Int = try {
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
        iconTone(palette.swatches.map { ToneSwatch(it.rgb, it.population) }, packageName)
    } catch (e: Exception) {
        hashedTone(packageName)
    }

    private const val SEED_PX = 48


    /** The rendered icon as a circle for an ImageView. */
    fun circular(context: Context, bitmap: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
}
