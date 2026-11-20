package com.kidslauncher.mdm.ui.home

/*
 * Home's contacts with a name pill (PLAN "Home: contacts with a name pill, its colour from Android", approved
 * 2026-11-19; mockups "Navn på lapp", "lyst/mørkt/urolig bilde"): the round avatar keeps its ring, the name sits on a
 * pill over the circle's bottom edge, and both take their colour from Android's own reading of the wallpaper
 * (`WallpaperColors.HINT_SUPPORTS_DARK_TEXT` of Home's own ground, read by `WallpaperStore` into its state with the
 * ground). Pure, no Android imports; `NamePillTest`.
 */

/** `WallpaperColors.HINT_SUPPORTS_DARK_TEXT` (API 27, public `getColorHints()` since 31): Android's word that the
 * wallpaper is light enough for dark text. `NamePillTest` keeps it equal to the platform's constant. */
const val HINT_SUPPORTS_DARK_TEXT_BIT = 1

/** The hint from a wallpaper's colour hints; `null` (no colours from Android) stays unknown. */
fun supportsDarkText(colorHints: Int?): Boolean? = colorHints?.let { (it and HINT_SUPPORTS_DARK_TEXT_BIT) != 0 }

/** How far the pill overlaps the avatar's bottom edge, in dp (the mockup's `margin-top: -10px`). */
const val NAME_PILL_OVERLAP_DP = 10

/** The room above the avatar for the rust missed-call pill (`item_kid_contact.xml`'s badge frame is 6 dp taller). */
const val CONTACT_TOP_ROOM_DP = 6

/** The dark pill and ring on a light wallpaper (#2A2630). */
const val NAME_PILL_DARK: Int = 0xFF2A2630.toInt()

/** The white pill on any other wallpaper, and the white ring at 95 % (as the ring drawable had it). */
const val NAME_PILL_WHITE: Int = 0xFFFFFFFF.toInt()
const val NAME_RING_WHITE: Int = 0xF2FFFFFF.toInt()

/** The dark pill's name: white; the white pill's: the contacts' night-blue ink ([AVATAR_INK], #1B1B28). */
const val NAME_TEXT_ON_DARK: Int = 0xFFFFFFFF.toInt()
const val NAME_TEXT_ON_WHITE: Int = AVATAR_INK.toInt()

/**
 * The colours of a contact's pill and ring. [outline]: a 1 dp edge around both, so either version holds over a busy
 * wallpaper (the dark ink at 30 % around white, white at 25 % around the dark one); [shadow]: the pill's shadow colour
 * (its alpha softens Android's elevation shadow - lighter under the dark pill on a light wallpaper, as in the mockup).
 */
data class NamePillStyle(val pill: Int, val text: Int, val ring: Int, val outline: Int, val shadow: Int)

/** On a light wallpaper (dark text supported): the dark pill, white name, dark ring. */
val NAME_PILL_ON_LIGHT = NamePillStyle(
    pill = NAME_PILL_DARK,
    text = NAME_TEXT_ON_DARK,
    ring = NAME_PILL_DARK,
    outline = 0x40FFFFFF,
    shadow = 0x66000000,
)

/** Otherwise, and when Android gives no colours: the white pill, dark name, white ring. */
val NAME_PILL_ON_DARK = NamePillStyle(
    pill = NAME_PILL_WHITE,
    text = NAME_TEXT_ON_WHITE,
    ring = NAME_RING_WHITE,
    outline = 0x4D1B1B28,
    shadow = 0xA6000000.toInt(),
)

/** Android's hint to the pill: dark on a wallpaper that supports dark text, white otherwise and when unknown. */
fun namePillStyle(supportsDarkText: Boolean?): NamePillStyle =
    if (supportsDarkText == true) NAME_PILL_ON_LIGHT else NAME_PILL_ON_DARK

/**
 * Whether Home re-renders the contacts row and the grid after drawing a new ground: the labels' ink or the pills' hint
 * changed. Both come in the one `WallpaperStore.State` with the ground, so the ground and the pills change in the same
 * pass - never a new ground under the old pill colours.
 */
fun callPartsRestyleDue(
    shownInk: com.kidslauncher.mdm.ui.wallpaper.InkChoice,
    shownDarkText: Boolean,
    ink: com.kidslauncher.mdm.ui.wallpaper.InkChoice,
    darkText: Boolean,
): Boolean = ink != shownInk || darkText != shownDarkText

/**
 * The pill's corner radius in px: half the height of a one-line pill ([lineHeightPx] plus [paddingVerticalPx] above
 * and below). One line is fully rounded; a name on two lines gets a rounded rectangle with the same corners, never a
 * stadium whose half-circle ends would cut the corners of a full-width line's first and last letters.
 */
fun namePillCornerPx(lineHeightPx: Int, paddingVerticalPx: Int): Float = lineHeightPx / 2f + paddingVerticalPx
