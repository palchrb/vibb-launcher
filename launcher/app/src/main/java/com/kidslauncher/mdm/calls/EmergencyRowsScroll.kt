package com.kidslauncher.mdm.calls

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * The emergency sheet's rows (design 73, code QA M1): a ScrollView no taller than [maxHeightPx], so six rows at a
 * large font scale or long names scroll instead of pushing Cancel off the screen. [EmergencySheet] sets the cap.
 */
class EmergencyRowsScroll @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val given = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(given, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}
