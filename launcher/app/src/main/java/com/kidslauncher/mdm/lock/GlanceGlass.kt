package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.ui.home.contrastRatio
import com.kidslauncher.mdm.ui.home.relativeLuminance
import com.kidslauncher.mdm.ui.wallpaper.WallpaperFill
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Frosted glass B on the lock glance's cards (approved 2026-11-17, PLAN.md "Frosted glass on the lock glance's cards";
 * docs/notes/launcher/lock-and-glance.md, "Frosted glass on the glance's cards") - the pure part, no Android imports,
 * tested in GlanceGlassTest. The mockup's CSS: `background: rgba(22,20,34,.58); backdrop-filter: blur(22px)
 * saturate(140%); box-shadow: inset 0 0 0 1px rgba(255,255,255,.08)`. Here: one small copy of the glance's own ground
 * (its photo under its scrim), saturated and blurred once per ground and size, drawn behind each card at the card's
 * place (lock/GlanceGlassCards.kt), then the tint and the light edge.
 */

/** The tint's colour: `kid_music_tile`, the solid cards' own (#1B1B28; the mockup's #161422 is the same night). */
const val GLASS_TINT_RGB: Int = 0x1B1B28

/** The tint's least alpha, the mockup's 58 % as an 8-bit alpha. */
const val GLASS_MIN_TINT: Int = 148

/** The faint light edge: white at 8 %, 1 dp inside the card (the CSS's 1 px). */
const val GLASS_EDGE_ARGB: Int = 0x14FFFFFF

/** CSS `blur(22px)`: a Gaussian of this standard deviation, in dp. */
const val GLASS_BLUR_DP = 22f

/** The small copy's cell: one pixel of it per this many dp of the screen (a 393x873 dp screen is a 99x219 copy, 87 KB). */
const val GLASS_CELL_DP = 4f

/** CSS `saturate(140%)`. */
const val GLASS_SATURATION = 1.4

/** Every text ink on the glance's cards (alpha = as drawn): `kid_ink`, `kid_core_dim`, `kid_rain`. The tint is raised
 * until each has 4.5:1 on the card over the copy's lightest pixel ([glassTintAlpha]). */
val GLASS_INKS: List<Int> = listOf(0xFFFFFFFF.toInt(), 0xC7FBE4DC.toInt(), 0xFF7CC4F2.toInt())

/** The weather card's raised "now" column (`kid_weather_now`, white at 6 %) - a second ground the inks must read on. */
const val GLASS_RAISED_ARGB: Int = 0x0FFFFFFF

/** Glass only over a picture: the glance's ground is a photo that was decoded. A colour, a gradient, the night mark or a
 * photo that can't be read keep today's solid cards. */
fun glassOn(fill: WallpaperFill?, decoded: Boolean): Boolean = fill is WallpaperFill.Image && decoded

/** The small copy's size for a ground of [widthPx] x [heightPx] at [density]: one pixel per [GLASS_CELL_DP], at least
 * 1x1; null for an empty ground. */
fun glassCopySize(widthPx: Int, heightPx: Int, density: Float): Pair<Int, Int>? {
    if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return null
    val cell = GLASS_CELL_DP * density
    return ceil(widthPx / cell).toInt().coerceAtLeast(1) to ceil(heightPx / cell).toInt().coerceAtLeast(1)
}

/** The blur's standard deviation in the copy's pixels. */
fun glassSigmaCells(): Double = (GLASS_BLUR_DP / GLASS_CELL_DP).toDouble()

/** The box widths whose [n] passes approximate a Gaussian of [sigma] (odd, ascending; Kutskir's "boxes for Gauss"). */
fun gaussBoxes(sigma: Double, n: Int = 3): IntArray {
    if (sigma <= 0.0) return IntArray(n) { 1 }
    val ideal = sqrt(12 * sigma * sigma / n + 1)
    var wl = floor(ideal).toInt()
    if (wl % 2 == 0) wl--
    val wu = wl + 2
    val mIdeal = (12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4.0 * wl - 4)
    val m = mIdeal.roundToInt()
    return IntArray(n) { if (it < m) wl else wu }
}

/** One box pass of [radius] along rows ([horizontal]) or columns, edges clamped; opaque ARGB in, opaque ARGB out. */
private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val size = 2 * radius + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = at(i)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until len) {
            val half = size / 2
            val out = (0xFF shl 24) or (((r + half) / size) shl 16) or (((g + half) / size) shl 8) or ((b + half) / size)
            if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            val gone = at(i - radius)
            val come = at(i + radius + 1)
            r += ((come shr 16) and 0xFF) - ((gone shr 16) and 0xFF)
            g += ((come shr 8) and 0xFF) - ((gone shr 8) and 0xFF)
            b += (come and 0xFF) - (gone and 0xFF)
        }
    }
}

/** A Gaussian blur of [sigma] pixels over a `w`x`h` opaque ARGB image: three box passes each way, edges clamped. */
fun glassBlur(pixels: IntArray, w: Int, h: Int, sigma: Double): IntArray {
    require(pixels.size == w * h)
    val out = pixels.copyOf()
    val between = IntArray(out.size)
    for (box in gaussBoxes(sigma)) {
        val radius = (box - 1) / 2
        if (radius <= 0) continue
        boxPass(out, between, w, h, radius, horizontal = true)
        boxPass(between, out, w, h, radius, horizontal = false)
    }
    return out
}

/** CSS `saturate([amount])` (the filter-effects matrix, Android's `ColorMatrix.setSaturation`), in place, clamped. */
fun glassSaturate(pixels: IntArray, amount: Double) {
    val s = amount
    val rr = 0.213 + 0.787 * s
    val rg = 0.715 - 0.715 * s
    val rb = 0.072 - 0.072 * s
    val gr = 0.213 - 0.213 * s
    val gg = 0.715 + 0.285 * s
    val gb = 0.072 - 0.072 * s
    val br = 0.213 - 0.213 * s
    val bg = 0.715 - 0.715 * s
    val bb = 0.072 + 0.928 * s
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (c(rr * r + rg * g + rb * b) shl 16) or (c(gr * r + gg * g + gb * b) shl 8) or
            c(br * r + bg * g + bb * b)
    }
}

/** The copy as drawn behind the cards, in the CSS's order: [glassBlur], then [glassSaturate]. */
fun glassCopy(pixels: IntArray, w: Int, h: Int): IntArray {
    val out = glassBlur(pixels, w, h, glassSigmaCells())
    glassSaturate(out, GLASS_SATURATION)
    return out
}

/** [top] (ARGB, its alpha as drawn) over the opaque [under], blended in sRGB values as Android draws. */
fun glassOver(top: Int, under: Int): Int {
    val a = ((top ushr 24) and 0xFF) / 255.0
    fun ch(shift: Int): Int {
        val t = (top shr shift) and 0xFF
        val u = (under shr shift) and 0xFF
        return (a * t + (1 - a) * u).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Whether every one of [inks] has 4.5:1 on the opaque [card], and on it under the weather card's raised column
 * ([GLASS_RAISED_ARGB], code QA M2). */
fun glassInksRead(card: Int, inks: List<Int> = GLASS_INKS): Boolean = listOf(card, glassOver(GLASS_RAISED_ARGB, card)).all { ground ->
    val lum = relativeLuminance(ground)
    inks.all { contrastRatio(relativeLuminance(glassOver(it, ground)), lum) >= 4.5 }
}

/** The tint's alpha over a copy whose lightest pixel is [lightest] (from [from] up): the mockup's 58 %
 * ([GLASS_MIN_TINT]), raised only as far as every card ink needs for 4.5:1 there ([glassInksRead]) - at most 255, today's
 * solid tile, which they all read on. */
fun glassTintAlpha(lightest: Int, inks: List<Int> = GLASS_INKS, from: Int = GLASS_MIN_TINT): Int {
    for (alpha in from..255) {
        val card = glassOver((alpha shl 24) or GLASS_TINT_RGB, lightest or (0xFF shl 24))
        if (glassInksRead(card, inks)) return alpha
    }
    return 255
}

/**
 * The tint's alpha over the whole copy: the least that [glassTintAlpha] allows over every one of its pixels. The pixel
 * of the highest luminance alone is not enough (code QA L1: blended in sRGB values, a light grey can make a lighter card
 * than a brighter saturated green), and each channel's maximum would treat a colourful picture as white. So every
 * pixel counts, rounded up per channel to 16 levels (the blend is monotone per channel, so the bound holds) - at most
 * 4096 colours, the lightest first; each needs one check at the alpha so far, a raise only where it fails.
 */
fun glassTintAlphaOver(pixels: IntArray): Int {
    val seen = BooleanArray(4096)
    val colours = ArrayList<Int>()
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        if (seen[key]) continue
        seen[key] = true
        colours += (0xFF shl 24) or ((r or 0x0F) shl 16) or ((g or 0x0F) shl 8) or (b or 0x0F)
    }
    colours.sortByDescending { relativeLuminance(it) }
    var alpha = GLASS_MIN_TINT
    for (c in colours) {
        if (alpha == 255) break
        if (!glassInksRead(glassOver((alpha shl 24) or GLASS_TINT_RGB, c))) alpha = glassTintAlpha(c, from = alpha)
    }
    return alpha
}
