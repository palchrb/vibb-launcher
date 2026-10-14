package com.kidslauncher.mdm.lock

import kotlin.math.abs

/**
 * Key sizing for [PinLockActivity]'s keypad (emulator run 2026-10-06: 480x854 px at 240 dpi,
 * about 320x569 dp, cut rows 7-8-9 and 0 off). Four rows of three round keys with [GAP_DP]
 * between rows; a key is as large as the keypad's measured share of the screen allows, at most
 * [MAX_DP] (mockup Lock.dc.html). Since design 25 the keypad stage has no clock or date to give
 * way (the glance page has them), so a keypad that can't fit [MIN_DP] keys shrinks them down to
 * [FLOOR_DP] rather than clip ([finalKeySizePx]) - Emergency call and "Parent code" are outside the
 * keypad and always shown.
 *
 * Design 34 P7 (the spacing rule): one column with nothing overlaid - header, dots, keypad, a gap of
 * at least 16 dp, Emergency call (at least 48 dp, it wraps and grows with the font scale), "Parent
 * code" (at least 44 dp), the inset. Only the keys give way: min(72, (h - 3 x gap) / 4) dp of the
 * keypad's measured height h, floor 32 dp. A digit never outgrows its key ([digitTextPx]).
 */
object PinKeypadLayout {
    const val ROWS = 4
    const val COLUMNS = 3
    const val MAX_DP = 72f
    const val MIN_DP = 48f
    const val GAP_DP = 6f
    /** The last resort: smaller keys rather than a clipped keypad. */
    const val FLOOR_DP = 32f
    /** Horizontal room a key leaves in its column. */
    const val SIDE_SLACK_DP = 8f

    /** The digits' text size (sp), as the font scale makes it - but see [digitTextPx]. */
    const val DIGIT_SP = 30f
    /**
     * The most of a key's height a digit's text size may take (design 34 P7). 0.7 keeps the digit's
     * line box (Nunito's 1.364 em, no font padding) inside the key (0.95 of it), and at font scale 2.0
     * on the Jelly Star (43 dp keys, modelled) the digits are no smaller than their 30 dp at 1.0
     * (qa-34-s8-code N2). The mockups draw 0.42 of the key at every scale; the rule here is "never cut".
     */
    const val DIGIT_SHARE = 0.7f

    /** The key's top highlight ([PinKeyHighlight]): 1 dp of white at 6 % (the mockups' inset 1px .06), over the top third. */
    const val HIGHLIGHT_ARGB = 0x0FFFFFFF
    const val HIGHLIGHT_STROKE_DP = 1f
    const val HIGHLIGHT_START_DEG = 210f
    const val HIGHLIGHT_SWEEP_DEG = 120f

    /** The key size (px) for a keypad of [heightPx] x [widthPx]; may be below [MIN_DP] - see [fits]. */
    fun keySizePx(heightPx: Int, widthPx: Int, density: Float): Int {
        val byHeight = (heightPx - (ROWS - 1) * GAP_DP * density) / ROWS
        val byWidth = widthPx.toFloat() / COLUMNS - SIDE_SLACK_DP * density
        return minOf(MAX_DP * density, byHeight, byWidth).toInt()
    }

    fun fits(keySizePx: Int, density: Float): Boolean = keySizePx >= (MIN_DP * density).toInt()

    /**
     * The size to use: the computed size itself, down to [FLOOR_DP] - forcing 48 dp made the centred
     * keypad overflow and clip its top and bottom rows (qa-fixround-2026-10-06 #4).
     */
    fun finalKeySizePx(computedPx: Int, density: Float): Int = computedPx.coerceAtLeast((FLOOR_DP * density).toInt())

    /** The height (px) four rows of [MIN_DP] keys need. */
    fun minHeightPx(density: Float): Int = ((ROWS * MIN_DP + (ROWS - 1) * GAP_DP) * density).toInt()

    /**
     * The digits' text size (px) on a key of [keySizePx]: the font scale's size ([scaledTextPx], 30 sp)
     * until it would take more than [DIGIT_SHARE] of the key - at font scale 2.0 the keys give way, and a
     * 60 sp digit on a 40 dp key would be cut. At 1.0 and 1.3 on the Jelly Star the keys are large
     * enough that this never bites.
     */
    fun digitTextPx(keySizePx: Int, scaledTextPx: Float): Float = minOf(scaledTextPx, keySizePx * DIGIT_SHARE)

    /** A key's size and its digit's text size (px). */
    data class KeyStyle(val sizePx: Int, val digitPx: Float)

    /**
     * The keys for a keypad of [heightPx] x [widthPx] with the font scale's digit size [scaledDigitPx]:
     * the size ([keySizePx], down to the floor) and the digit capped by it ([digitTextPx]).
     */
    fun keyStyle(heightPx: Int, widthPx: Int, density: Float, scaledDigitPx: Float): KeyStyle {
        val size = finalKeySizePx(keySizePx(heightPx, widthPx, density), density)
        return KeyStyle(size, digitTextPx(size, scaledDigitPx))
    }

    /**
     * Whether a key of [sizePx] showing a digit of [textPx] (`null` for the backspace) differs from
     * [style]. The digit is checked on its own (qa-34-s8-code L1): keys that keep their first 48 dp
     * still get the capped digit.
     */
    fun keyStale(sizePx: Int, textPx: Float?, style: KeyStyle): Boolean =
        sizePx != style.sizePx || (textPx != null && abs(textPx - style.digitPx) > 0.5f)
}
