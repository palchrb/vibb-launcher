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

    /** The 131 gate: a scan's capabilities to the security Vibb saves - WPA2 with Android's WPA3 upgrade, WPA3 alone
     * only where the router offers nothing else, never an enterprise or WEP network. */
    @Suppress("DEPRECATION")
    @Test
    fun `a scanned network is saved with the security its capabilities say`() {
        val psk = android.net.wifi.WifiConfiguration.SECURITY_TYPE_PSK
        val sae = android.net.wifi.WifiConfiguration.SECURITY_TYPE_SAE
        val open = android.net.wifi.WifiConfiguration.SECURITY_TYPE_OPEN
        val rows = listOf(
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]" to (WifiSecurity.PSK to psk),
            "[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS][MFPC]" to (WifiSecurity.PSK to psk),
            "[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]" to (WifiSecurity.PSK to psk),
            "[RSN-SAE-CCMP][ESS][MFPR][MFPC]" to (WifiSecurity.SAE to sae),
            "[ESS]" to (WifiSecurity.OPEN to open),
            "" to (WifiSecurity.OPEN to open),
            "[RSN-OWE-CCMP][ESS][MFPR][MFPC]" to (WifiSecurity.OPEN to open),
            "[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]" to (WifiSecurity.UNSUPPORTED to null),
            "[WEP][ESS]" to (WifiSecurity.UNSUPPORTED to null),
            "[WAPI-PSK]" to (WifiSecurity.UNSUPPORTED to null),
        )
        for ((caps, want) in rows) {
            assertEquals(caps, want.first, wifiSecurityOf(caps))
            assertEquals(caps, want.second, joinSecurityType(wifiSecurityOf(caps)))
        }
        assertEquals(2, psk)
        assertEquals(4, sae)
        assertEquals(0, open)
    }

    @Test
    fun `a secured network needs a password, an open one none, an unsupported one is never joined`() {
        assertTrue(joinAllowed(WifiSecurity.OPEN, password = null))
        assertTrue(joinAllowed(WifiSecurity.OPEN, password = ""))
        assertTrue(joinAllowed(WifiSecurity.PSK, password = "hunter22"))
        assertTrue(joinAllowed(WifiSecurity.SAE, password = "hunter22"))
        assertFalse(joinAllowed(WifiSecurity.PSK, password = ""))
        assertFalse(joinAllowed(WifiSecurity.SAE, password = null))
        assertFalse(joinAllowed(WifiSecurity.UNSUPPORTED, password = "hunter22"))
    }
}
