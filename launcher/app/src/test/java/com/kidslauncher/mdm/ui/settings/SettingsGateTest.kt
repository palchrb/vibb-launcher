package com.kidslauncher.mdm.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsGateTest {

    @Test
    fun `open during setup, before any policy`() {
        for (pin in listOf(true, false)) {
            for (lockedOut in listOf(true, false)) {
                assertEquals(SettingsAccess.OPEN, settingsAccess(policyEverApplied = false, pinConfigured = pin, lockedOut = lockedOut))
            }
        }
    }

    @Test
    fun `managed phone needs the PIN`() {
        assertEquals(SettingsAccess.REQUIRE_PIN, settingsAccess(policyEverApplied = true, pinConfigured = true, lockedOut = false))
    }

    @Test
    fun `managed phone without a PIN never opens`() {
        // QA step 1 #12: upstream opened Settings to anyone when the server had no PIN.
        assertEquals(SettingsAccess.REFUSE_NO_PIN, settingsAccess(policyEverApplied = true, pinConfigured = false, lockedOut = false))
        assertEquals(SettingsAccess.REFUSE_NO_PIN, settingsAccess(policyEverApplied = true, pinConfigured = false, lockedOut = true))
    }

    @Test
    fun `locked out stays shut`() {
        assertEquals(SettingsAccess.REFUSE_LOCKED_OUT, settingsAccess(policyEverApplied = true, pinConfigured = true, lockedOut = true))
    }

    /** E4's L3: the gate holds across Android's page a row opened - only for the stop that page causes, and back within
     * 10 minutes; any other stop, no open, or later asks again. */
    @Test
    fun `the gate stays open across our own page, within its windows only`() {
        val opened = 100_000L
        assertEquals(true, ownPageKeepsGate(opened, opened + 500, atStop = true))
        assertEquals(false, ownPageKeepsGate(opened, opened + OWN_PAGE_STOP_WINDOW_MS + 1, atStop = true))
        assertEquals(true, ownPageKeepsGate(opened, opened + OWN_PAGE_GATE_MS, atStop = false))
        assertEquals(false, ownPageKeepsGate(opened, opened + OWN_PAGE_GATE_MS + 1, atStop = false))
        assertEquals(false, ownPageKeepsGate(null, opened, atStop = true))
        assertEquals(false, ownPageKeepsGate(null, opened, atStop = false))
        assertEquals(false, ownPageKeepsGate(opened, opened - 1, atStop = false))
    }
}
