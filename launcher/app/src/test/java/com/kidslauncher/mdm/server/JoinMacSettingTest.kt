package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `a secured network needs a password, an open one none`() {
        assertTrue(joinAllowed(secured = false, password = null))
        assertTrue(joinAllowed(secured = false, password = ""))
        assertTrue(joinAllowed(secured = true, password = "hunter22"))
        assertFalse(joinAllowed(secured = true, password = ""))
        assertFalse(joinAllowed(secured = true, password = null))
    }
}
