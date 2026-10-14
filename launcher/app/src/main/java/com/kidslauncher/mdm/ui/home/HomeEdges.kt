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
 * The strength View.draw multiplies the fading-edge length by: nothing when the list can't scroll
 * past that edge, else the edge's length over the one [lengthDp] the view has (at most 1).
 */
fun fadeStrength(canScroll: Boolean, edgeDp: Float, lengthDp: Float = FADE_LENGTH_DP): Float =
    if (!canScroll || lengthDp <= 0f) 0f else (edgeDp / lengthDp).coerceIn(0f, 1f)

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

/** The room one Home contact needs: the avatar with its call badge and focus ring on both sides. */
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

/** [contactItemDp] in whole pixels, rounded down so [count] items never exceed the content width. */
fun contactItemPx(layout: ContactRowLayout, count: Int, contentWidthDp: Float, density: Float): Int =
    if (layout.scrolls || count <= 0) {
        (layout.itemDp * density).toInt()
    } else {
        min((layout.itemDp * density).toInt(), floor(max(0f, contentWidthDp) * density / count).toInt())
    }
