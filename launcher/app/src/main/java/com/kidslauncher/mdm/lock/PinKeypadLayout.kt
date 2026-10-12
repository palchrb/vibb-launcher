package com.kidslauncher.mdm.lock

/**
 * Key sizing for [PinLockActivity]'s keypad (emulator run 2026-10-06: 480x854 px at 240 dpi,
 * about 320x569 dp, cut rows 7-8-9 and 0 off). Four rows of three round keys with [GAP_DP]
 * between rows; a key is as large as the keypad's measured share of the screen allows, at most
 * [MAX_DP] (mockup Lock.dc.html). Since design 25 the keypad stage has no clock or date to give
 * way (the glance page has them), so a keypad that can't fit [MIN_DP] keys shrinks them down to
 * [FLOOR_DP] rather than clip ([finalKeySizePx]) - Emergency call and "Parent code" are outside the
 * keypad and always shown.
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
}
