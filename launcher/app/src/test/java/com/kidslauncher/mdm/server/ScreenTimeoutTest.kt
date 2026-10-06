package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Auto-lock (emulator run 2026-10-06, qa-fixround-2026-10-06 #3). */
class ScreenTimeoutTest {
    @Test
    fun `the parent's choices are applied in milliseconds while managed`() {
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
    fun `unmanaged, no value, or the override release it`() {
        // A policy without an allowlist or managed calls, no policy, an unenrolled phone.
        assertEquals(ScreenTimeoutAction.Release, screenTimeoutAction(false, 60, false))
        assertEquals(ScreenTimeoutAction.Release, screenTimeoutAction(false, null, false))
        // An older server, or a last-enforced plan from before the field existed.
        assertEquals(ScreenTimeoutAction.Release, screenTimeoutAction(true, null, false))
        assertEquals(ScreenTimeoutAction.Release, screenTimeoutAction(true, 60, true))
    }

    @Test
    fun `the phone's own value is remembered once and put back on release`() {
        // First enforce remembers the phone's value ("never" on the emulator).
        assertEquals(Int.MAX_VALUE.toLong(), previousToRemember(null, Int.MAX_VALUE.toLong()))
        // A later enforce keeps the first remembered value, not ours.
        assertEquals(30_000L, previousToRemember(30_000L, 60_000L))
        // Release puts it back unless it is already in place; nothing remembered, nothing to do.
        assertEquals(30_000L, previousToRestore(30_000L, 60_000L))
        assertNull(previousToRestore(30_000L, 30_000L))
        assertNull(previousToRestore(null, 60_000L))
    }
}
