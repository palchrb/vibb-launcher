package com.kidslauncher.mdm.ui.wallpaper

import android.widget.TextView

/** The wallpaper's ink on a kid screen's text: colour, and a shadow where the scrim alone can't
 * reach 4.5:1 (QA 08 #7). */
object KidInk {
    fun label(view: TextView, ink: InkChoice, dim: Boolean = false) {
        view.setTextColor(if (dim) ink.inkDim else ink.ink)
        if (ink.labelShadow) {
            val shadow = if (ink.darkInk) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
            view.setShadowLayer(4f * view.resources.displayMetrics.density, 0f, 0f, shadow)
        } else {
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
    }
}
