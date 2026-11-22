package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 126 A7: open networks Vibb adds get a non-persistent random address, secured ones Android's default. */
class JoinMacSettingTest {
    @Suppress("DEPRECATION")
    @Test
    fun `an open network gets a fresh address, a secured one keeps Android's`() {
        assertEquals(android.net.wifi.WifiConfiguration.RANDOMIZATION_NON_PERSISTENT, joinMacSetting(secured = false))
        assertEquals(2, joinMacSetting(secured = false))
        assertNull(joinMacSetting(secured = true))
    }
}
