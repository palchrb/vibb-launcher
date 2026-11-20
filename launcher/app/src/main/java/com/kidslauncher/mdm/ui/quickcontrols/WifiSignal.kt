package com.kidslauncher.mdm.ui.quickcontrols

import com.kidslauncher.mdm.R

/** The Wi-Fi page's signal indicator: a level of 0..4 (out of range clamped) to its Material Symbols drawable. */
object WifiSignal {
    const val MAX_LEVEL = 4

    fun level(raw: Int): Int = raw.coerceIn(0, MAX_LEVEL)

    fun drawable(raw: Int): Int = when (level(raw)) {
        0 -> R.drawable.ic_wifi_signal_0
        1 -> R.drawable.ic_wifi_signal_1
        2 -> R.drawable.ic_wifi_signal_2
        3 -> R.drawable.ic_wifi_signal_3
        else -> R.drawable.ic_wifi_signal_4
    }
}
