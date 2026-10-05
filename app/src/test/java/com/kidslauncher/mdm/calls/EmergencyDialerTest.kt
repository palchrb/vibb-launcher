package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** qa-09-design.md #1: the lock screens' fallback is the system emergency dialer, explicitly. */
class EmergencyDialerTest {
    @Test
    fun `the first system emergency dialer wins, never a third-party one`() {
        val fake = ResolvedComponent("com.evil.dialer", "Evil", system = false)
        val phone = ResolvedComponent("com.android.phone", "com.android.phone.EmergencyDialer", system = true)
        assertEquals(phone, chooseEmergencyDialer(listOf(fake, phone)))
        assertEquals(phone, chooseEmergencyDialer(listOf(null, phone)))
        assertNull(chooseEmergencyDialer(listOf(fake, null)))
        assertNull(chooseEmergencyDialer(emptyList()))
    }

    @Test
    fun `both emergency-dial actions are tried, the system API one first`() {
        assertEquals(listOf("android.intent.action.DIAL_EMERGENCY", "com.android.phone.EmergencyDialer.DIAL"), EmergencyDialer.ACTIONS)
    }
}
