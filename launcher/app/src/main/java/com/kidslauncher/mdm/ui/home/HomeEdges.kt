package com.kidslauncher.mdm.ui.home

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Design 34 P4 (docs/design/34-launcher-polish.md at the monorepo root): Home's edges and labels.
 * The top under the status bar stays empty (the user's answer); what changes is that the grid fades
 * at the edges it can scroll past, its labels wrap to two lines, and the contacts row never runs
 * past the screen's edge (the third contact's call badge was cut off). Pure, so the rules are
 * plain JUnit (`HomeEdgesTest`); the views are `FadingRecyclerView`, `item_kid_tile.xml` and
 * `HomeActivity.renderContacts`.
 */

/** The grid's top fade: short, so the row just under the contacts is barely touched. */
const val FADE_TOP_DP = 12f

/** The grid's bottom fade, over the gesture bar. */
const val FADE_BOTTOM_DP = 32f

/**
 * Android has one `fadingEdgeLength` for both edges, so the view gets the longer one
 * (`activity_home.xml`'s `android:fadingEdgeLength`) and each edge scales it by its strength.
 */
const val FADE_LENGTH_DP = 32f

/** The contacts row's left/right fade while it scrolls: short, so the half avatar peeking at the
 * edge (`contactRow`) still reads as "there is more". */
const val CONTACTS_FADE_DP = 16f

/**
 * The strength View.draw multiplies the fading-edge length by (qa-34-s4-code L1): it ramps with
 * the distance still to scroll past that edge, as ScrollView's does, so the fade grows in over the
 * first [edgePx] of a scroll and melts away over the last instead of snapping in one frame. Full,
 * it is the edge's length over the one [lengthPx] the view has (at most 1); nothing when there is
 * nothing left to scroll. All in pixels.
 */
fun fadeStrength(remainingPx: Int, edgePx: Float, lengthPx: Float): Float {
    if (remainingPx <= 0 || edgePx <= 0f || lengthPx <= 0f) return 0f
    return min(1f, remainingPx / edgePx) * min(1f, edgePx / lengthPx)
}

/**
 * The distance left to scroll past an edge: what the layout manager reports (an estimate in
 * pixels), but at least 1 px while [canScroll] says there is more - so an estimate of 0 never
 * hides a real edge - and nothing when it says there isn't.
 */
fun remainingScrollPx(canScroll: Boolean, estimatePx: Int): Int = if (canScroll) max(1, estimatePx) else 0

/** Grid labels wrap to at most two lines at every font scale (QA L6: no row height to compute). */
const val GRID_LABEL_MAX_LINES = 2

/** Nunito's line with `includeFontPadding=false`: (ascender 1011 + descender 353) / 1000 em. */
const val NUNITO_LINE_EM = 1.364f

/** The tile frame's 4 dp of room above the icon for the badge, and the label's 5 dp top margin
 * (`item_kid_tile.xml`, `HomeGridAdapter.applyMetrics`). */
const val TILE_BADGE_ROOM_DP = 4f
const val TILE_LABEL_GAP_DP = 5f

/**
 * A grid tile's height with [lines] label lines at [fontScale] (linear: an upper bound for
 * Android 14's non-linear scaling). A row is as tall as its tallest tile.
 */
fun tileHeightDp(metrics: GridMetrics, fontScale: Float, lines: Int = GRID_LABEL_MAX_LINES): Float =
    metrics.iconDp + TILE_BADGE_ROOM_DP + TILE_LABEL_GAP_DP +
        lines.coerceIn(1, GRID_LABEL_MAX_LINES) * metrics.labelSp * fontScale * NUNITO_LINE_EM

/** How far the call badge's right edge sits past the avatar's (`item_kid_contact.xml`: the frame
 * is 12 dp wider than the avatar, the badge at its end with a 2 dp margin). */
const val CALL_BADGE_OVERHANG_DP = 4f

/** How far P2's focus ring reaches past the avatar (its 2 dp stroke lies inside the 4 dp gap). */
const val FOCUS_RING_OUT_DP = PressFeedback.FOCUS_RING_GAP_DP

/**
 * The room one Home contact needs, the avatar centred in it. The two don't add: the badge
 * overhangs on the end side only and the ring on both sides, so the larger of the two on each side
 * covers both (it over-reserves at the start when the badge is the larger - harmless).
 */
fun contactRoomDp(avatarDp: Int): Float = avatarDp + 2 * max(CALL_BADGE_OVERHANG_DP, FOCUS_RING_OUT_DP)

/**
 * The width of one Home contact. A scrolling row keeps [ContactRowLayout.itemDp] (the peek's
 * spacing). A row that fits shares the content width: each item is one avatar plus a gap, but never
 * more than its share, so the row is never wider than the screen. (Before, three 104 dp items made
 * a 312 dp row in 288 dp, which scrolled by 24 dp and cut the third contact's call badge.)
 */
fun contactItemDp(layout: ContactRowLayout, count: Int, contentWidthDp: Float): Float =
    if (layout.scrolls || count <= 0) {
        layout.itemDp.toFloat()
    } else {
        min(layout.itemDp.toFloat(), max(0f, contentWidthDp) / count)
    }

/**
 * [contactItemDp] in whole pixels, rounded down so [count] items never exceed [contentPx], the
 * row's real width in pixels ([contactsContentPx]; qa-34-s4-code L2).
 */
fun contactItemPx(layout: ContactRowLayout, count: Int, contentPx: Int, density: Float): Int =
    if (layout.scrolls || count <= 0) {
        (layout.itemDp * density).toInt()
    } else {
        min((layout.itemDp * density).toInt(), max(0, contentPx) / count)
    }

/**
 * The contacts row's width in pixels: the measured content width of Home's column once it is laid
 * out ([measuredPx] > 0), else the configuration's estimate ([screenWidthDp] minus the 2 x 16 dp
 * sides, which the window manager may round up) less a pixel of slack (qa-34-s4-code L2).
 */
fun contactsContentPx(measuredPx: Int, screenWidthDp: Int, density: Float): Int =
    if (measuredPx > 0) measuredPx else max(0, floor((screenWidthDp - 32) * density).toInt() - 1)
