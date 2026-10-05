package com.kidslauncher.mdm.ui.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperApplyPlanTest {

    private val photo = Wallpaper(7, WallpaperFill.Image("ab".repeat(32)), "Hytta")
    private val key = wallpaperKey(photo, 1080, 2400)
    private val today = 20_000L

    @Test
    fun `key covers home, lock and size`() {
        assertTrue(key.contains("home=image:"))
        assertTrue(key.contains("lock=${NAVY.fill.describe()}"))
        assertTrue(key.endsWith("1080x2400"))
        assertFalse(key == wallpaperKey(photo.copy(lockScreen = true), 1080, 2400))
        assertFalse(key == wallpaperKey(photo, 720, 1600))
    }

    @Test
    fun `only while managed and allowed`() {
        assertEquals(ApplyDecision.Skip("unmanaged"), wallpaperApplyPlan(false, true, key, ApplyRecord(), 5, today))
        assertTrue(wallpaperApplyPlan(true, false, key, ApplyRecord(), 5, today) is ApplyDecision.Skip)
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, ApplyRecord(), 5, today))
    }

    @Test
    fun `unmanaged after ours was applied resets once`() {
        val applied = ApplyRecord(appliedKey = key, appliedId = 9, attemptKey = key, attemptDay = today)
        assertEquals(ApplyDecision.Reset, wallpaperApplyPlan(false, true, key, applied, 9, today))
        // After the reset the record is empty: nothing more.
        assertEquals(ApplyDecision.Skip("unmanaged"), wallpaperApplyPlan(false, true, key, ApplyRecord(), 9, today))
    }

    @Test
    fun `nothing to do when it is in place`() {
        val applied = recordAttempt(ApplyRecord(), key, 9, today)
        assertEquals(ApplyRecord(key, 9, key, today), applied)
        assertEquals(ApplyDecision.Skip("in place"), wallpaperApplyPlan(true, true, key, applied, 9, today + 3))
        assertTrue(systemShowsOurs(applied, key, 9))
        assertFalse(systemShowsOurs(applied, key, 10))
        assertFalse(systemShowsOurs(applied, "other", 9))
    }

    @Test
    fun `a refused setBitmap (id 0) is retried once a day, never in a loop`() {
        val failed = recordAttempt(ApplyRecord(), key, 0, today)
        assertEquals(null, failed.appliedKey)
        assertEquals(ApplyDecision.Skip("already tried today"), wallpaperApplyPlan(true, true, key, failed, 0, today))
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, failed, 0, today + 1))
        // A failure keeps what was applied before.
        val before = ApplyRecord(appliedKey = "old", appliedId = 4, attemptKey = "old", attemptDay = today - 1)
        assertEquals(before.copy(attemptKey = key, attemptDay = today), recordAttempt(before, key, 0, today))
    }

    @Test
    fun `a new choice applies at once, even the same day`() {
        val applied = recordAttempt(ApplyRecord(), key, 9, today)
        val navyKey = wallpaperKey(NAVY, 1080, 2400)
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, navyKey, applied, 9, today))
        val back = recordAttempt(applied, navyKey, 10, today)
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, back, 10, today))
    }

    @Test
    fun `changed behind our back is re-applied at most once a day`() {
        val applied = recordAttempt(ApplyRecord(), key, 9, today)
        assertEquals(ApplyDecision.Skip("already tried today"), wallpaperApplyPlan(true, true, key, applied, 12, today))
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, applied, 12, today + 1))
    }
}
