package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** Emulator run 2026-10-06: no heads-up over our own call screen. */
class CallNotificationModeTest {
    @Test
    fun `silent while our call screen is visible`() {
        for (ringing in listOf(true, false)) {
            assertEquals(CallNotificationMode(silent = true, onlyAlertOnce = true), callNotificationMode(true, false, true, ringing))
            assertEquals(CallNotificationMode(silent = true, onlyAlertOnce = true), callNotificationMode(true, true, true, ringing))
        }
    }

    @Test
    fun `a call posted while the screen isn't visible alerts as before`() {
        assertEquals(CallNotificationMode(silent = false, onlyAlertOnce = false), callNotificationMode(false, false, true, true))
    }

    @Test
    fun `leaving the screen alerts again only for a ringing call with the display on`() {
        assertEquals(CallNotificationMode(silent = false, onlyAlertOnce = true, fresh = true), callNotificationMode(false, true, true, true))
        assertEquals(CallNotificationMode(silent = false, onlyAlertOnce = true), callNotificationMode(false, true, false, true))
        assertEquals(CallNotificationMode(silent = false, onlyAlertOnce = true), callNotificationMode(false, true, true, false))
    }
}
