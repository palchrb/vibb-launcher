package com.kidslauncher.mdm.ui.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.kidslauncher.mdm.server.HardeningRestriction
import com.kidslauncher.mdm.server.dto.HardeningPolicy
import com.kidslauncher.mdm.server.hardeningPlan
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
        val applied = recordAttempt(ApplyRecord(), key, ApplyOutcome(9, true), today)
        assertEquals(ApplyRecord(key, 9, key, today, null), applied)
        assertEquals(ApplyDecision.Skip("in place"), wallpaperApplyPlan(true, true, key, applied, 9, today + 3))
        assertTrue(systemShowsOurs(applied, key, 9))
        assertFalse(systemShowsOurs(applied, key, 10))
        assertFalse(systemShowsOurs(applied, "other", 9))
    }

    @Test
    fun `a refused setBitmap (id 0) is retried once a day, never in a loop`() {
        val failed = recordAttempt(ApplyRecord(), key, ApplyOutcome(0, false), today)
        assertEquals(null, failed.appliedKey)
        assertEquals(ApplyDecision.Skip("already tried today"), wallpaperApplyPlan(true, true, key, failed, 0, today))
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, failed, 0, today + 1))
        // A failure keeps what was applied before.
        val before = ApplyRecord(appliedKey = "old", appliedId = 4, attemptKey = "old", attemptDay = today - 1)
        assertEquals(before.copy(attemptKey = key, attemptDay = today), recordAttempt(before, key, ApplyOutcome(0, false), today))
    }

    @Test
    fun `a new choice applies at once, even the same day`() {
        val applied = recordAttempt(ApplyRecord(), key, ApplyOutcome(9, true), today)
        val navyKey = wallpaperKey(NAVY, 1080, 2400)
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, navyKey, applied, 9, today))
        val back = recordAttempt(applied, navyKey, ApplyOutcome(10, true), today)
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, back, 10, today))
    }

    @Test
    fun `changed behind our back is re-applied at most once a day`() {
        val applied = recordAttempt(ApplyRecord(), key, ApplyOutcome(9, true), today)
        assertEquals(ApplyDecision.Skip("already tried today"), wallpaperApplyPlan(true, true, key, applied, 12, today))
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, key, applied, 12, today + 1))
    }

    private val hash = "ab".repeat(32)

    @Test
    fun `a half-applied photo is remembered, so a reset still finds it`() {
        // Lock screen set, home refused (or the other way round): not "in place", but ours.
        val partial = recordAttempt(ApplyRecord(), key, ApplyOutcome(0, lockOk = true), today)
        assertEquals(null, partial.appliedKey)
        assertEquals(key, partial.partialKey)
        assertTrue(partial.oursMayShow)
        assertEquals(ApplyDecision.Reset, wallpaperApplyPlan(false, true, key, partial, 0, today))
        assertFalse(systemShowsOurs(partial.copy(appliedKey = key, appliedId = 3), key, 3))
        assertEquals(setOf(hash), imagesInKey(partial.partialKey))
        // A later full apply replaces it.
        assertEquals(null, recordAttempt(partial, key, ApplyOutcome(4, true), today).partialKey)
        // Nothing went through: nothing new to remember.
        assertFalse(recordAttempt(ApplyRecord(), key, ApplyOutcome(0, false), today).oursMayShow)
    }

    @Test
    fun `a failed reset is kept and retried`() {
        val applied = recordAttempt(ApplyRecord(), key, ApplyOutcome(9, true), today)
        assertEquals(applied, recordReset(applied, worked = false))
        assertEquals(ApplyDecision.Reset, wallpaperApplyPlan(false, true, key, recordReset(applied, false), 9, today + 5))
        assertEquals(ApplyRecord(), recordReset(applied, worked = true))
    }

    @Test
    fun `a revoked photo is replaced on every pass, not once a day`() {
        val applied = recordAttempt(ApplyRecord(), key, ApplyOutcome(9, true), today)
        assertTrue(revokedImageShows(applied, emptySet()))
        assertFalse(revokedImageShows(applied, setOf(hash)))
        assertFalse(revokedImageShows(ApplyRecord(), emptySet()))
        val navyKey = wallpaperKey(NAVY, 1080, 2400)
        // The replacement was tried today and failed: still Apply (the applier then resets).
        val failed = recordAttempt(applied, navyKey, ApplyOutcome(0, false), today)
        assertEquals(ApplyDecision.Skip("already tried today"), wallpaperApplyPlan(true, true, navyKey, failed, 9, today))
        assertEquals(ApplyDecision.Apply, wallpaperApplyPlan(true, true, navyKey, failed, 9, today, revoked = true))
        assertTrue(revokedImageShows(failed, emptySet()))
    }

    @Test
    fun `unmanaging resets the wallpaper before its restriction is lifted`() {
        val steps = hardeningClearSteps(hardeningPlan(HardeningPolicy(), managed = false))
        val reset = steps.indexOf(ClearStep.ResetWallpaper)
        val clear = steps.indexOf(ClearStep.Clear(HardeningRestriction.SET_WALLPAPER))
        assertTrue(reset >= 0)
        assertEquals(reset + 1, clear)
        assertEquals(HardeningRestriction.entries.size + 1, steps.size)
        // Managed: nothing to lift, no reset.
        assertEquals(emptyList<ClearStep>(), hardeningClearSteps(hardeningPlan(HardeningPolicy(), managed = true))
            .filter { it == ClearStep.ResetWallpaper })
    }
}
