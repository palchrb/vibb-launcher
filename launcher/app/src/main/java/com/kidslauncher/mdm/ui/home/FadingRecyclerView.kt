package com.kidslauncher.mdm.ui.home

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * Home's grid with fading edges of two lengths (design 34 P4, QA L6). Android has one
 * `fadingEdgeLength` for both edges, so the layout sets [FADE_LENGTH_DP] and each edge scales it
 * by [fadeStrength]: [FADE_TOP_DP] at the top, [FADE_BOTTOM_DP] at the bottom, and nothing at an
 * edge the grid can't scroll past. The fade is an alpha fade (View.draw's own), so it works over
 * the system wallpaper too. Touch, scrolling and the item touch listener are RecyclerView's own.
 *
 * With `clipToPadding=false` the grid draws into its bottom padding, so the fade covers the
 * padding as well (as AbsListView does) - otherwise a faded row would reappear below the fade.
 */
class FadingRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun getTopFadingEdgeStrength(): Float =
        fadeStrength(canScrollVertically(-1), FADE_TOP_DP)

    override fun getBottomFadingEdgeStrength(): Float =
        fadeStrength(canScrollVertically(1), FADE_BOTTOM_DP)

    override fun isPaddingOffsetRequired(): Boolean = !clipToPadding

    override fun getTopPaddingOffset(): Int = if (clipToPadding) 0 else -paddingTop

    override fun getBottomPaddingOffset(): Int = if (clipToPadding) 0 else paddingBottom

    override fun getLeftPaddingOffset(): Int = if (clipToPadding) 0 else -paddingLeft

    override fun getRightPaddingOffset(): Int = if (clipToPadding) 0 else paddingRight
}
