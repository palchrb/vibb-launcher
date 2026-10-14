package com.kidslauncher.mdm.ui.home

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Design 34 P1 (docs/design/34-launcher-polish.md at the monorepo root): the six calm tones an app's
 * white glyph sits on, and the pure rule that picks one from the icon's colours. Mid-dark and muted
 * (white >= 5.3:1, against the night ground >= 3.5:1); no amber (it read as brown next to the old
 * mustard, QA L9), no green or red (they mean call and hang-up/emergency). The parent's six colour
 * keys (`app_icons.json`) map onto the same tones.
 */

/** The six tones (opaque ARGB). */
const val TONE_SLATE: Int = 0xFF606B80.toInt()
const val TONE_BLUE: Int = 0xFF496C9C.toInt()
const val TONE_TEAL: Int = 0xFF347470.toInt()
const val TONE_PLUM: Int = 0xFF805B9A.toInt()
const val TONE_ROSE: Int = 0xFFA45166.toInt()
const val TONE_INDIGO: Int = 0xFF6065A9.toInt()

/** Every tone, in the order the package hash picks from. */
val ICON_TONES: List<Int> = listOf(TONE_SLATE, TONE_BLUE, TONE_TEAL, TONE_PLUM, TONE_ROSE, TONE_INDIGO)

/** One colour of an icon and how much of it there is (a palette swatch). */
data class ToneSwatch(val rgb: Int, val population: Int)

/**
 * The hue each chromatic tone stands for (degrees). Slate has no hue of its own - it is for the
 * hash (grey and multi-colour icons) only, so a blue icon never lands on it.
 */
private val HUE_TONES: List<Pair<Float, Int>> = listOf(
    176f to TONE_TEAL,
    215f to TONE_BLUE,
    237f to TONE_INDIGO,
    275f to TONE_PLUM,
    345f to TONE_ROSE,
)

/**
 * Multi-colour icons with a fixed tone: Google's four-colour icons (and the other apps the mockup
 * names), so Home doesn't depend on which swatch a palette pass finds first. Mockup 01.
 */
val FIXED_TONES: Map<String, Int> = mapOf(
    "com.android.chrome" to TONE_BLUE,
    "com.google.android.apps.docs" to TONE_TEAL,
    "com.google.android.apps.photos" to TONE_PLUM,
    "com.google.android.gm" to TONE_ROSE,
    "com.google.android.calendar" to TONE_BLUE,
    "com.google.android.apps.maps" to TONE_INDIGO,
    "com.google.android.googlequicksearchbox" to TONE_BLUE,
    "com.google.android.youtube" to TONE_ROSE,
    "com.google.android.apps.youtube.music" to TONE_ROSE,
    "com.google.android.keep" to TONE_INDIGO,
)

/** HSL saturation at or above this is a colour; below it, grey. */
private const val CHROMA_MIN = 0.25f

/** A colour this dark or this light is a shade, not a colour of the icon. */
private const val LIGHTNESS_MIN = 0.12f
private const val LIGHTNESS_MAX = 0.92f

/** Less of the icon than this in colour: a grey icon. */
private const val CHROMATIC_SHARE_MIN = 0.15f

/** Hues further apart than this are different colours. */
private const val SAME_HUE_DEGREES = 40f

/** A second colour with at least this share of the coloured part: a multi-colour icon. */
private const val SECOND_HUE_SHARE = 0.25f

/**
 * The tone behind an app's white glyph: a fixed tone for the known multi-colour icons
 * ([FIXED_TONES]); for an icon of one dominant hue, the tone nearest that hue; for a multi-colour
 * or a grey icon, a tone by a stable hash of [packageName] (slate without one). Always one of
 * [ICON_TONES].
 */
fun iconTone(swatches: List<ToneSwatch>, packageName: String?): Int {
    packageName?.let { FIXED_TONES[it] }?.let { return it }
    val hashed = hashedTone(packageName)
    val all = swatches.sumOf { max(0, it.population).toLong() }
    val chromatic = swatches.mapNotNull { swatch ->
        val (h, s, l) = hsl(swatch.rgb)
        if (swatch.population > 0 && s >= CHROMA_MIN && l in LIGHTNESS_MIN..LIGHTNESS_MAX) h to swatch.population else null
    }
    val coloured = chromatic.sumOf { it.second.toLong() }
    if (all <= 0 || coloured == 0L || coloured < CHROMATIC_SHARE_MIN * all) return hashed
    val dominant = chromatic.maxByOrNull { it.second }!!.first
    val elsewhere = chromatic.filter { hueDistance(it.first, dominant) > SAME_HUE_DEGREES }.sumOf { it.second.toLong() }
    if (elsewhere >= SECOND_HUE_SHARE * coloured) return hashed
    return nearestTone(dominant)
}

/** A pixel this opaque or more counts; the transparent area around an icon doesn't. */
private const val OPAQUE_ALPHA = 128

/**
 * The icon's colours as swatches, from its pixels (ARGB) directly (qa-34-s1-code L2): androidx
 * Palette's default filter drops orange and brown (hue 10-37° below saturation 0.82) before
 * [iconTone] would see them, so the tone is never Palette's. Pixels under [OPAQUE_ALPHA] are left
 * out; the rest are counted per colour at 4 bits a channel (each bucket's centre as its colour).
 * Black, white and grey stay in: [iconTone]'s own lightness and chroma rules decide on them.
 */
fun swatchesOf(pixels: IntArray): List<ToneSwatch> {
    val counts = HashMap<Int, Int>()
    for (p in pixels) {
        if ((p ushr 24) < OPAQUE_ALPHA) continue
        val bucket = (((p shr 16) and 0xF0) shl 16) or (((p shr 8) and 0xF0) shl 8) or (p and 0xF0)
        counts[bucket] = (counts[bucket] ?: 0) + 1
    }
    return counts.map { (rgb, n) -> ToneSwatch((0xFF shl 24) or rgb or 0x080808, n) }
}

/** How an app icon is drawn (design 34 P1's three states; [iconRender]). */
sealed interface IconRender {
    /** The app's own icon in its own colours, clipped to the circle. */
    data object OwnLogo : IconRender

    /** The parent's glyph [glyphRes] in white on [tile], or on the app's tone when `null`. */
    data class Glyph(val glyphRes: Int, val tile: Int?) : IconRender

    /** Vibb style: the icon's monochrome layer in white on the app's tone. */
    data object VibbMono : IconRender
}

/**
 * The order of design 34 P1 (QA H1, qa-34-s1-code L4) - the parent's own logo first, before the
 * glyph lookup and the monochrome layer (else `own` falls through to the monochrome glyph, the old
 * bug); then a known glyph (an unknown one is Vibb style); then Vibb style: the monochrome glyph on
 * the tone, or, with no monochrome layer, the app's own icon (nothing to tone).
 */
fun iconRender(
    entry: com.kidslauncher.mdm.appdisplay.AppDisplayEntry?,
    hasMonochrome: Boolean,
    glyphs: Map<String, Int> = com.kidslauncher.mdm.appdisplay.AppGlyphs.ICONS,
    tiles: Map<String, Int> = com.kidslauncher.mdm.appdisplay.AppGlyphs.COLORS.mapValues { it.value.tile },
): IconRender {
    if (entry?.ownLogo == true) return IconRender.OwnLogo
    val glyph = entry?.icon?.let { glyphs[it] }
    if (entry != null && glyph != null) return IconRender.Glyph(glyph, tiles[entry.color])
    return if (hasMonochrome) IconRender.VibbMono else IconRender.OwnLogo
}

/** The chromatic tone whose hue is nearest [hue] (degrees, on the circle). */
fun nearestTone(hue: Float): Int = HUE_TONES.minByOrNull { hueDistance(it.first, hue) }!!.second

/** A tone by a stable hash of the package (the JVM's `String.hashCode` is specified), slate for none. */
fun hashedTone(packageName: String?): Int =
    if (packageName.isNullOrEmpty()) TONE_SLATE else ICON_TONES[Math.floorMod(packageName.hashCode(), ICON_TONES.size)]

/** Degrees between two hues on the circle (0-180). */
fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return min(d, 360f - d)
}

/** Hue (degrees), saturation and lightness (0-1) of an opaque ARGB colour. */
fun hsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val l = (mx + mn) / 2
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6 else 0))
        g -> ((b - r) / d + 2)
        else -> ((r - g) / d + 4)
    } * 60f
    return Triple(h, s, l)
}
