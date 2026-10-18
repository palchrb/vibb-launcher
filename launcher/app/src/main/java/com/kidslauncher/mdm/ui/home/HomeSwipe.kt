package com.kidslauncher.mdm.ui.home

import kotlin.math.abs

/*
 * Home's two swipes (handy step 8, design 08-ui-polish.md; qa-34-s4-code M1 at the monorepo root):
 * up to the drawer, left to the kid's own Settings. Every swipe is decided from its own DOWN: the
 * detector is fed once, from `HomeActivity.dispatchTouchEvent`, in window coordinates - before
 * that it was fed by the grid, the contacts row and the activity, and a DOWN that landed on a
 * contact (the clickable item took it) left the swipe to be decided with the previous touch's.
 * Pure - HomeSwipeTest.
 */

const val SWIPE_UP_MIN_DISTANCE = 100
const val SWIPE_UP_MIN_VELOCITY = 100
const val SWIPE_LEFT_MIN_DISTANCE = 100
const val SWIPE_LEFT_MIN_VELOCITY = 100

/** Where a swipe's DOWN landed. */
enum class SwipeStart {
    /** The contacts row: on a contact or between them. */
    CONTACTS_ROW,
    GRID,
    /** A clickable card outside the grid and the row (the call card, the music card, the
     * banner's "Lock again"): it keeps the whole touch, as it always has - no swipe. */
    CARD,
    /** Anything else on Home. */
    BLANK,
}

enum class HomeSwipe { NONE, DRAWER, KID_SETTINGS }

/**
 * A fling from ([downX], [downY]) to ([upX], [upY]) in one coordinate space (pixels), starting at
 * [start]. Up opens the drawer only once the grid can't scroll further down ([gridCanScrollDown]),
 * so scrolling a long grid doesn't open it; left opens kid Settings, except that a swipe starting in
 * the contacts row stays the row's scroll while the row can still scroll that way
 * ([rowCanScrollRight]: `canScrollHorizontally(1)`). Nothing on the night ground (![contentShown]).
 */
fun homeSwipe(
    downX: Float,
    downY: Float,
    upX: Float,
    upY: Float,
    velocityX: Float,
    velocityY: Float,
    start: SwipeStart,
    gridCanScrollDown: Boolean,
    rowCanScrollRight: Boolean,
    contentShown: Boolean,
): HomeSwipe {
    if (!contentShown || start == SwipeStart.CARD) return HomeSwipe.NONE
    val dx = upX - downX
    val dy = upY - downY
    if (abs(dy) > abs(dx) && -dy > SWIPE_UP_MIN_DISTANCE && abs(velocityY) > SWIPE_UP_MIN_VELOCITY && !gridCanScrollDown) {
        return HomeSwipe.DRAWER
    }
    if (abs(dx) > abs(dy) && -dx > SWIPE_LEFT_MIN_DISTANCE && abs(velocityX) > SWIPE_LEFT_MIN_VELOCITY &&
        !(start == SwipeStart.CONTACTS_ROW && rowCanScrollRight)
    ) {
        return HomeSwipe.KID_SETTINGS
    }
    return HomeSwipe.NONE
}
