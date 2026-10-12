package com.kidslauncher.mdm.ui.wallpaper

import com.kidslauncher.mdm.ui.home.contrastRatio
import com.kidslauncher.mdm.ui.home.relativeLuminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Legible text on any wallpaper (design 08 §3, QA 08 #7) - pure, tested in WallpaperInkTest.
 * The ink (labels, badge rings, status-bar icons) is white or #1B1B1F, whichever contrasts more
 * with the wallpaper's worst case; when even that is under 4.5:1 a scrim (black under white ink,
 * white under dark ink) is laid over the wallpaper, as light as possible; where the cap can't get
 * there, labels also get a shadow.
 */

const val INK_LIGHT: Int = 0xFFFFFFFF.toInt()
const val INK_DARK: Int = 0xFF1B1B1F.toInt()
const val LABEL_CONTRAST = 4.5
const val MAX_SCRIM = 0.45
/** Photos always get at least this much scrim: a busy picture behind small labels. */
const val MIN_IMAGE_SCRIM = 0.15

data class InkChoice(
    /** [INK_LIGHT] or [INK_DARK]. */
    val ink: Int,
    /** Scrim opacity 0..[MAX_SCRIM]: black for light ink, white for dark ink. */
    val scrimAlpha: Double,
    /** The scrim can't reach 4.5:1 alone: labels get a shadow (or pill) as well. */
    val labelShadow: Boolean,
) {
    val darkInk: Boolean get() = ink == INK_DARK
    /** Ink at 75 %, for secondary text. */
    val inkDim: Int get() = (0xBF shl 24) or (ink and 0xFFFFFF)
    /** The scrim as ARGB (transparent when [scrimAlpha] is 0). */
    val scrimArgb: Int get() {
        val a = (scrimAlpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (if (darkInk) 0xFFFFFF else 0x000000)
    }
}

/** sRGB channel value 0..1 → linear. */
private fun toLinear(v: Double): Double = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

/** Linear luminance → the sRGB value of a grey with that luminance. */
private fun toSrgb(lum: Double): Double {
    val l = lum.coerceIn(0.0, 1.0)
    return if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055
}

/**
 * Luminance of a background of luminance [lum] under a scrim of [alpha] - blended as Android
 * draws it, in sRGB values, black (`towardsWhite = false`) or white.
 */
fun underScrim(lum: Double, alpha: Double, towardsWhite: Boolean): Double {
    val v = toSrgb(lum)
    val blended = if (towardsWhite) v * (1 - alpha) + alpha else v * (1 - alpha)
    return toLinear(blended)
}

private fun scrimFor(worstLum: Double, darkInk: Boolean, minAlpha: Double): Pair<Double, Boolean> {
    val inkLum = if (darkInk) relativeLuminance(INK_DARK) else 1.0
    var alpha = minAlpha
    while (alpha <= MAX_SCRIM + 1e-9) {
        if (contrastRatio(inkLum, underScrim(worstLum, alpha, towardsWhite = darkInk)) >= LABEL_CONTRAST) {
            return alpha to false
        }
        alpha += 0.01
    }
    return MAX_SCRIM to true
}

/**
 * The ink for a background whose luminance lies between [darkestLum] and [lightestLum]: white is
 * judged against the lightest part, dark ink against the darkest; the one with more contrast
 * wins, then the lightest scrim (at least [minScrim]) that gets it to 4.5:1.
 */
fun inkFor(darkestLum: Double, lightestLum: Double, minScrim: Double = 0.0): InkChoice {
    val white = contrastRatio(1.0, lightestLum)
    val dark = contrastRatio(relativeLuminance(INK_DARK), darkestLum)
    val darkInk = dark > white
    val worst = if (darkInk) darkestLum else lightestLum
    val (alpha, shadow) = scrimFor(worst, darkInk, minScrim)
    return InkChoice(if (darkInk) INK_DARK else INK_LIGHT, alpha, shadow)
}

/** A colour or gradient: its stops are the whole range (worst stop per ink). */
fun inkForColours(colours: List<Int>): InkChoice {
    val lums = colours.map(::relativeLuminance)
    return inkFor(lums.minOrNull() ?: 0.0, lums.maxOrNull() ?: 0.0)
}

fun inkForFill(fill: WallpaperFill): InkChoice? = when (fill) {
    is WallpaperFill.Solid -> inkForColours(listOf(fill.argb))
    is WallpaperFill.Gradient -> inkForColours(listOf(fill.from, fill.to))
    is WallpaperFill.Image -> null // needs the pixels: inkForImage
}

/**
 * The PIN lock keeps its light inks on every background (design 25, "Lock background"): its cards
 * and keys are dark and opaque; on the wallpaper itself sit the clock and prompt (white), the date
 * and the "more" line (`kid_core_dim`, #FBE4DC at 78 %), "Foreldrekode" (white at the view's 75 %)
 * and the glance page's red "Nødsamtale" (`kid_emergency_text`) - [LOCK_INKS]. So only a black
 * scrim: the lightest (at least [minScrim]) that gives *every* one of them 4.5:1 against the
 * background's lightest part (qa-25b-25f M3 - it was white only, and the emergency link got 2:1
 * on Sky and Sunset), at most [LOCK_MAX_SCRIM], which gets there even on white. Judged at the
 * scrim's 8-bit alpha, as drawn.
 */
const val LOCK_MAX_SCRIM = 0.8

/** The lock's inks on the wallpaper, ARGB (alpha = how they are drawn: the colour's or the view's). */
val LOCK_INKS: List<Int> = listOf(
    0xFFFFFFFF.toInt(), // kid_ink: the clock, the keypad's prompt
    0xC7FBE4DC.toInt(), // kid_core_dim: the date, "+ N varsler til"
    0xBFFFFFFF.toInt(), // kid_ink at alpha 0.75: "Foreldrekode"
    0xFFFF8A8A.toInt(), // kid_emergency_text: the glance page's "Nødsamtale"
)

/** The contrast of [inkArgb] (its alpha blended as Android draws it, in sRGB values) on a grey of
 * luminance [backgroundLum]. */
fun inkContrastOn(inkArgb: Int, backgroundLum: Double): Double {
    val a = ((inkArgb ushr 24) and 0xFF) / 255.0
    val bg = toSrgb(backgroundLum)
    fun channel(shift: Int): Double = toLinear(a * (((inkArgb shr shift) and 0xFF) / 255.0) + (1 - a) * bg)
    val lum = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    return contrastRatio(lum, backgroundLum)
}

fun lockScrim(lightestLum: Double, minScrim: Double = 0.0, inks: List<Int> = LOCK_INKS): Double {
    var alpha = minScrim
    while (alpha < LOCK_MAX_SCRIM) {
        // The scrim as drawn: its 8-bit alpha.
        val drawn = ((alpha * 255).toInt().coerceIn(0, 255)) / 255.0
        val ground = underScrim(lightestLum, drawn, towardsWhite = false)
        if (inks.all { inkContrastOn(it, ground) >= LABEL_CONTRAST }) return alpha
        alpha += 0.01
    }
    return LOCK_MAX_SCRIM
}

/** [lockScrim] for a colour or gradient (its lightest stop). */
fun lockScrimForColours(colours: List<Int>): Double = lockScrim(colours.maxOfOrNull(::relativeLuminance) ?: 0.0)

/** [lockScrim] for a photo: its light side (mean + one standard deviation), at least [MIN_IMAGE_SCRIM]. */
fun lockScrimForImage(stats: LuminanceStats): Double = lockScrim(min(1.0, stats.mean + stats.sd), MIN_IMAGE_SCRIM)

/** A black scrim of [alpha] as ARGB. */
fun blackScrimArgb(alpha: Double): Int = ((alpha * 255).toInt().coerceIn(0, 255)) shl 24

/** Mean and standard deviation of the pixels' luminance, averaged in linear light. */
data class LuminanceStats(val mean: Double, val sd: Double)

fun luminanceStats(argbPixels: IntArray): LuminanceStats {
    if (argbPixels.isEmpty()) return LuminanceStats(0.0, 0.0)
    var sum = 0.0
    var sumSq = 0.0
    for (p in argbPixels) {
        val l = relativeLuminance(p)
        sum += l
        sumSq += l * l
    }
    val mean = sum / argbPixels.size
    val variance = max(0.0, sumSq / argbPixels.size - mean * mean)
    return LuminanceStats(mean, sqrt(variance))
}

/** A photo: judged on mean ± one standard deviation (a busy photo counts as lighter and darker
 * than its average), always with at least [MIN_IMAGE_SCRIM]. */
fun inkForImage(stats: LuminanceStats): InkChoice =
    inkFor(
        darkestLum = max(0.0, stats.mean - stats.sd),
        lightestLum = min(1.0, stats.mean + stats.sd),
        minScrim = MIN_IMAGE_SCRIM,
    )
