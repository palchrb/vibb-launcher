package com.kidslauncher.mdm

import com.kidslauncher.mdm.ui.quickcontrols.WifiSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSignalTest {
    @Test fun `each level has its own drawable`() {
        val ids = (0..4).map { WifiSignal.drawable(it) }
        assertEquals(5, ids.toSet().size)
        assertEquals(R.drawable.ic_wifi_signal_0, ids[0])
        assertEquals(R.drawable.ic_wifi_signal_4, ids[4])
    }

    @Test fun `out of range levels clamp`() {
        assertEquals(WifiSignal.drawable(0), WifiSignal.drawable(-3))
        assertEquals(WifiSignal.drawable(4), WifiSignal.drawable(9))
        assertEquals(0, WifiSignal.level(-1))
        assertEquals(4, WifiSignal.level(7))
    }
}
