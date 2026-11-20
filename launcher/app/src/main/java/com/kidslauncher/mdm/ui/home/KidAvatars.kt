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
import android.graphics.drawable.LayerDrawable
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.appdisplay.AppDisplayEntry
import com.kidslauncher.mdm.appdisplay.AppGlyphs
import com.kidslauncher.mdm.appdisplay.OWN_ICON
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.appdisplay.appIconKey
import com.kidslauncher.mdm.appdisplay.appIconKeyApp
import com.kidslauncher.mdm.calls.ContactMissed
import com.kidslauncher.mdm.calls.ContactPhotos
import com.kidslauncher.mdm.calls.MessagePackages
import com.kidslauncher.mdm.calls.RuleContact

/** Drawing helpers shared by Home, the phone book and the contact sheet. */
/**
 * Whether the icon cache's [cacheKey] is one of [packageName]'s ([KidAvatars.evictPackage]). A key whose text doesn't
 * hold the quoted package name (a serialized app key spells it `"<package>"`) is skipped before the decode
 * (qa-appload-debounce-code Q-4: the provisioning storm asks this of every key once per callback).
 */
internal fun iconKeyIsPackage(cacheKey: String, packageName: String): Boolean {
    if (!cacheKey.contains("\"$packageName\"")) return false
    return try {
        (AbstractAppInfo.deserialize(appIconKeyApp(cacheKey)) as? AppInfo)?.packageName == packageName
    } catch (e: Exception) {
        false
    }
}

/** Home's ring's outline: 1 dp just outside the avatar's edge, in the name pill's outline colour. */
private const val HOME_RING_OUTLINE_DP = 1f

/** The ring's width (as `bg_kid_contact_ring`). */
private const val HOME_RING_DP = 3f

/** The name pill's outline. */
private const val NAME_PILL_OUTLINE_DP = 1f

/** Where a contact's pills are drawn (the phone book's rows have none since variant D) (design 106 §2.2): the glyph's size in each. */
enum class BadgeSize(val glyphDp: Float) {
    /** Home's 76/64 dp avatars: 24 dp pills, 14 sp. */
    HOME(15f),

    /** The contact sheet's 132 dp avatar: 30 dp pills, 15 sp. */
    SHEET(18f),
}

object KidAvatars {

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /**
     * A contact's round avatar: the cached photo ([ContactPhotos]) cropped to a circle, or the
     * initial on its palette colour ([avatarStyle]).
     */
    fun bindContact(photo: ImageView, initial: TextView, contact: RuleContact, isEmergency: Boolean, initialSp: Float, thumb: Boolean = false) {
        val context = photo.context
        val style = avatarStyle(contact, isEmergency)
        // Design 79 §3.7: the rows take the row-sized copy, the card and the call screen the full square; §5.4: the kid's
        // own picture wins over the parent's - both from memory only (the snapshot and the decoded cache).
        val bitmap = ContactPhotos.cachedShown(context, shownPhotoOf(context, contact, isEmergency), thumb)
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

    /** What [contact]'s avatar shows ([shownPhoto]): the kid's picture from [KidContactPhotos]' snapshot (memory only),
     * else the parent's; none for an emergency number. */
    fun shownPhotoOf(context: Context, contact: RuleContact, isEmergency: Boolean): com.kidslauncher.mdm.calls.ShownPhoto =
        com.kidslauncher.mdm.calls.shownPhoto(contact, com.kidslauncher.mdm.calls.KidContactPhotos.recordFor(context, contact), isEmergency)

    /** Whether [contact]'s picture is decoded already (a screen's "rebind once it is" key; memory only). */
    fun photoReady(context: Context, contact: RuleContact, thumb: Boolean = false): Boolean =
        ContactPhotos.cachedShown(context, shownPhotoOf(context, contact, isEmergency = false), thumb) != null

    /**
     * Sizes an `item_kid_contact` for Home ([contactRow]): the avatar ([CONTACT_TOP_ROOM_DP] below the cell's top), the
     * badges' frame 12 dp wider and 6 dp taller (design 106 §2.2: both pills stay 24 dp at either avatar size and grow
     * inward over the avatar), and the name pill [NAME_PILL_OVERLAP_DP] over the avatar's bottom edge.
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
        (item.findViewById<View>(R.id.contact_name).layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin =
            dp(context, (CONTACT_TOP_ROOM_DP + avatarDp - NAME_PILL_OVERLAP_DP).toFloat())
    }

    /**
     * Home's ring on a contact's avatar (mockup A, PLAN "Home: contacts with a name pill"): in [style]'s ring colour -
     * dark on a light wallpaper, white otherwise ([namePillStyle]) - over the photo's or the disc's edge, so a photo
     * on a busy wallpaper has an edge; hidden for an emergency contact, whose red ring ([bindContact]) is its edge
     * already. Only Home calls this; the other screens have no `contact_ring`. A 1 dp outline in [style]'s outline
     * colour lies just outside the avatar's edge, so either ring holds over a busy wallpaper: the ring view grows 1 dp
     * past the avatar (the avatar doesn't clip, `item_kid_contact.xml`) and the ring itself is inset by the same 1 dp.
     */
    fun bindHomeRing(ring: View, isEmergency: Boolean, style: NamePillStyle) {
        ring.visibility = if (isEmergency) View.GONE else View.VISIBLE
        val context = ring.context
        val edge = dp(context, HOME_RING_OUTLINE_DP)
        (ring.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(-edge, -edge, -edge, -edge)
            ring.layoutParams = it
        }
        val outline = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(edge, style.outline)
        }
        val band = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, HOME_RING_DP), style.ring)
        }
        ring.background = LayerDrawable(arrayOf(outline, band)).apply { setLayerInset(1, edge, edge, edge, edge) }
    }

    /**
     * A Home contact's name on its pill ([style]: [namePillStyle] of Android's hint on Home's ground): a 1 dp outline,
     * the name in the pill's ink with no text shadow, and a soft shadow - the pill has the avatar's elevation
     * ([PressFeedback.contact]) so it draws over the avatar, its shadow softened by [NamePillStyle.shadow]. Its corners
     * are [namePillCornerPx] of the line height the view lays out with (after [com.kidslauncher.mdm.ui.WholeWordTextView]
     * fitted the size; set again at each layout): one line fully rounded, two lines a rounded rectangle.
     */
    fun bindNamePill(name: TextView, style: NamePillStyle) {
        val context = name.context
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = namePillCornerPx(name.lineHeight, name.paddingTop)
            setColor(style.pill)
            setStroke(dp(context, NAME_PILL_OUTLINE_DP), style.outline)
        }
        name.background = pill
        name.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val text = view as TextView
            val radius = namePillCornerPx(text.lineHeight, text.paddingTop)
            if (pill.cornerRadius != radius) {
                pill.cornerRadius = radius
                text.invalidateOutline()
            }
        }
        name.setTextColor(style.text)
        name.setShadowLayer(0f, 0f, 0f, 0)
        name.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        name.outlineSpotShadowColor = style.shadow
        name.outlineAmbientShadowColor = style.shadow
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

    /**
     * A contact avatar's two pills (design 106 §2, A5): the rust [missedView] with the phone-x glyph and the missed
     * calls, the teal [unreadView] with the chat glyph and the unread messages - each hidden at 0, never a sum. [size]
     * sets the glyph's bounds in code (the drawables are 24 dp).
     */
    fun bindContactBadge(missedView: TextView, unreadView: TextView, badge: ContactBadge, size: BadgeSize) {
        bindPill(missedView, badge.missedText, R.drawable.ic_kid_missed_call, size)
        bindPill(unreadView, badge.unreadText, R.drawable.ic_kid_badge_message, size)
    }

    private fun bindPill(pill: TextView, text: String?, glyphRes: Int, size: BadgeSize) {
        pill.visibility = if (text == null) View.GONE else View.VISIBLE
        pill.text = text
        if (text == null) return
        val glyph = ContextCompat.getDrawable(pill.context, glyphRes)?.mutate()
        val px = dp(pill.context, size.glyphDp)
        glyph?.setBounds(0, 0, px, px)
        pill.setCompoundDrawablesRelative(glyph, null, null, null)
    }

    /** What TalkBack reads for a contact avatar: the name, its missed calls, its unread messages. */
    fun contactDescription(context: Context, name: String, contact: ContactBadge): String {
        val head = if (contact.missed > 0) {
            context.resources.getQuantityString(R.plurals.contact_missed, contact.missed, name, contact.missed)
        } else {
            name
        }
        return if (contact.unread > 0) {
            head + ", " + context.resources.getQuantityString(R.plurals.contact_unread, contact.unread, contact.unread)
        } else {
            head
        }
    }

    /** The contact sheet's lines under the name (verification N8): the missed calls ([missedText]), then "1 ulest
     * melding" - both parts in words, never the badge's bare sum. `null` = none. */
    fun sheetLines(context: Context, missed: ContactMissed?, unread: Int): String? = listOfNotNull(
        missedText(context, missed),
        context.resources.getQuantityString(R.plurals.contact_unread, unread, unread).takeIf { unread > 0 },
    ).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** The sheet's missed-call line: the phone's own, then (round L5) the missed Element X calls, one line each. */
    fun missedText(context: Context, missed: ContactMissed?): String? {
        if (missed == null) return null
        val lines = listOfNotNull(
            missed.phone?.takeIf { it.count > 0 }?.let { MissedCallText.describe(context, it) },
            missed.element?.takeIf { it.count > 0 }?.let {
                MissedCallText.describeIn(context, it, AppDisplay.shownLabel(context, MessagePackages.ELEMENT_X))
            },
        )
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private val iconCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Cache key of a rendered app icon: the app, the size and the density (QA 08 #5), plus the
     * parent's glyph and colour (design 14, [appIconKey]) - one key for the lookup and the render. */
    fun iconKey(context: Context, key: String, sizePx: Int): String = iconKey(context, key, sizePx, displayOf(key))

    /** [iconKey] for a choice already read - the render keys and draws the same one (qa-14-code #1). */
    private fun iconKey(context: Context, key: String, sizePx: Int, display: AppDisplayEntry?): String =
        appIconKey(key, sizePx, context.resources.displayMetrics.densityDpi, display)

    /**
     * Launcher round 7 §4 (the Fossify comparison): drops every rendered icon of [packageName] - every
     * size, density and parent choice - so an app that was updated (a new icon, a monochrome layer
     * added) is drawn again on Home, the glance page and the time-rule screen. From the
     * `LauncherApps` callbacks, before the app list reloads; cheap (a scan of the cache's keys).
     */
    fun evictPackage(packageName: String) {
        try {
            iconCache.snapshot().keys
                .filter { iconKeyIsPackage(it, packageName) }
                .forEach { iconCache.remove(it) }
        } catch (e: Exception) {
            android.util.Log.w("KidAvatars", "Icon eviction failed: ${e.javaClass.simpleName}")
        }
    }

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
        val drawable = privateCopy(context, icon())
        val monochrome = (drawable as? AdaptiveIconDrawable)?.monochrome
        // The state order is the pure, tested iconRender (qa-34-s1-code L4).
        val bmp = when (val render = iconRender(display, monochrome != null)) {
            IconRender.OwnLogo -> drawOwnLogo(context, drawable, sizePx)
            is IconRender.Glyph ->
                renderSymbolIcon(context, render.glyphRes, render.tile ?: toneOf(drawable, packageName), sizePx)
            IconRender.VibbMono -> drawVibbMono(drawable, monochrome!!, packageName, sizePx)
        }
        iconCache.put(cacheKey, bmp)
        return bmp
    }

    /** Vibb style: the monochrome layer in white on the app's tone. */
    private fun drawVibbMono(drawable: Drawable, monochrome: Drawable, packageName: String?, sizePx: Int): Bitmap {
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
        return bmp
    }

    /**
     * The app's own logo (design 34 P1), never the monochrome layer, never tinted: an adaptive
     * icon's background and foreground layers in their original colours, 1.5x (108 dp layers with a
     * 72 dp safe zone) and centred; a legacy icon inset 1/8 on the light neutral disc
     * (`kid_icon_fallback`). Clipped to the circle. Also the logo the status report names and uploads (design 87,
     * [com.kidslauncher.mdm.appdisplay.AppLogos]) - one drawing, not a second.
     */
    internal fun drawOwnLogo(context: Context, drawable: Drawable, sizePx: Int): Bitmap {
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
     * The tone behind the app's white glyph (design 34 P1): [iconTone] of the icon's colours -
     * [swatchesOf] the opaque pixels of a 48 px render of its full-colour layers, counted directly
     * (androidx Palette's default filter would drop orange and brown, qa-34-s1-code L2) - and the
     * package; the package's hash tone when the render fails.
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
        // Not androidx Palette: its default filter drops orange and brown (qa-34-s1-code L2).
        val pixels = IntArray(SEED_PX * SEED_PX)
        small.getPixels(pixels, 0, SEED_PX, 0, 0, SEED_PX, SEED_PX)
        small.recycle()
        iconTone(swatchesOf(pixels), packageName)
    } catch (e: Exception) {
        hashedTone(packageName)
    }

    private const val SEED_PX = 48


    /** The rendered icon as a circle for an ImageView. */
    fun circular(context: Context, bitmap: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
}
