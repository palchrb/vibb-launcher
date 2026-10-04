package com.kidslauncher.mdm.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionsPauseTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `active within the window`() {
        assertTrue(pauseActive(true, now + RESTRICTIONS_PAUSE_DURATION_MS, now))
        assertTrue(pauseActive(true, now + 1, now))
    }

    @Test
    fun `off when the flag is off`() {
        assertFalse(pauseActive(false, now + 1000, now))
    }

    @Test
    fun `expires at the end of the window`() {
        assertFalse(pauseActive(true, now, now))
        assertFalse(pauseActive(true, now - 1, now))
    }

    @Test
    fun `a pause from an older build without an end time is expired`() {
        assertFalse(pauseActive(true, 0, now))
    }

    @Test
    fun `setting the clock back doesn't lengthen a pause`() {
        assertFalse(pauseActive(true, now + RESTRICTIONS_PAUSE_DURATION_MS + 1, now))
    }
}
