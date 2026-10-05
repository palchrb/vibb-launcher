package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** qa-09-design.md #1, qa-09-code.md #2: the lock screens' fallback only targets a system
 * emergency dialer that kiosk lets start. */
class EmergencyDialerTest {
    private val dial = "android.intent.action.DIAL_EMERGENCY"
    private val legacy = "com.android.phone.EmergencyDialer.DIAL"
    private val fake = EmergencyCandidate(dial, ResolvedComponent("com.evil.dialer", "Evil", system = false))
    private val googleDialer = EmergencyCandidate(dial, ResolvedComponent("com.google.android.dialer", "EmergencyDialer", system = true))
    private val phone = EmergencyCandidate(legacy, ResolvedComponent("com.android.phone", "com.android.phone.EmergencyDialer", system = true))

    @Test
    fun `never a third-party handler`() {
        assertEquals(listOf(phone), emergencyTargets(listOf(fake, phone), lockTaskActive = false) { true })
    }

    @Test
    fun `in kiosk only permitted packages, the rest falls through to the next candidate`() {
        // The system dialer handles DIAL_EMERGENCY (Google Dialer phones) but isn't pinned: skipped.
        assertEquals(listOf(phone), emergencyTargets(listOf(googleDialer, phone), lockTaskActive = true) { it == "com.android.phone" })
        // Pinned (the block pins the system dialer): tried first.
        assertEquals(listOf(googleDialer, phone), emergencyTargets(listOf(googleDialer, phone), lockTaskActive = true) { true })
        assertEquals(emptyList<EmergencyCandidate>(), emergencyTargets(listOf(googleDialer, phone), lockTaskActive = true) { false })
        // Kiosk off: every system handler.
        assertEquals(listOf(googleDialer, phone), emergencyTargets(listOf(googleDialer, phone), lockTaskActive = false) { false })
    }

    @Test
    fun `both emergency-dial actions are tried, the system API one first`() {
        assertEquals(listOf(dial, legacy), EmergencyDialer.ACTIONS)
    }
}
