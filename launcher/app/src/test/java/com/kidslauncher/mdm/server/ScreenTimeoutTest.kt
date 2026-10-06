package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Auto-lock (emulator run 2026-10-06): what the parent's screen timeout does on the phone. */
class ScreenTimeoutTest {
    @Test
    fun `the parent's choices are applied in milliseconds`() {
        for (seconds in listOf(15, 30, 60, 120, 300, 600)) {
            assertEquals(ScreenTimeoutAction.Enforce(seconds * 1000L), screenTimeoutAction(true, seconds, false))
        }
    }

    @Test
    fun `out of range values are clamped, never 'never'`() {
        assertEquals(ScreenTimeoutAction.Enforce(15_000L), screenTimeoutAction(true, 5, false))
        assertEquals(ScreenTimeoutAction.Enforce(600_000L), screenTimeoutAction(true, Int.MAX_VALUE, false))
        assertEquals(ScreenTimeoutAction.Enforce(60_000L), screenTimeoutAction(true, 0, false))
        assertEquals(ScreenTimeoutAction.Enforce(60_000L), screenTimeoutAction(true, -1, false))
    }

    @Test
    fun `an older server and the override release it, no policy keeps it`() {
        assertEquals(ScreenTimeoutAction.Release, screenTimeoutAction(true, null, false))
        assertEquals(ScreenTimeoutAction.Release, screenTimeoutAction(true, 60, true))
        assertEquals(ScreenTimeoutAction.Keep, screenTimeoutAction(false, null, false))
        assertEquals(ScreenTimeoutAction.Keep, screenTimeoutAction(false, null, true))
    }
}
