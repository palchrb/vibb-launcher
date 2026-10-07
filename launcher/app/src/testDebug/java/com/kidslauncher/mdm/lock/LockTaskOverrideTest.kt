package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LockTaskSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The debug build's design 16d lock-task override (src/debug): pure, so the debug variant's tests only. */
class LockTaskOverrideTest {
    private val kiosk = LockTaskSetting(packages = setOf("me.vibb.launcher.debug", "com.android.deskclock"), features = 119, statusBarDisabled = false, createWindowsBlocked = false)
    private val locked = kiosk.copy(features = 113, statusBarDisabled = true, createWindowsBlocked = true)

    @Test
    fun `parsing - unknown bits and invalid names dropped, nothing to change is no override`() {
        assertEquals(
            LockTaskOverride(4, 8, setOf("com.google.android.apps.nexuslauncher", "com.android.launcher3")),
            lockTaskOverride(4, 8 or 0x100, " com.google.android.apps.nexuslauncher, ,com.android.launcher3,not a package,x"),
        )
        assertNull(lockTaskOverride(0, 0, null))
        assertNull(lockTaskOverride(0x100, 0, " , "))
    }

    @Test
    fun `features - computed minus clear plus add, keyguard never cleared`() {
        // 16d row 2: gesture nav with HOME (and OVERVIEW) off in kiosk.
        assertEquals(113, applyLockTaskOverride(kiosk, LockTaskOverride(clearFeatures = 4 or 8 or 2)).features)
        assertEquals("keyguard (32) stays", 119, applyLockTaskOverride(kiosk, LockTaskOverride(clearFeatures = 32)).features)
        assertEquals("OVERVIEW back on", 127, applyLockTaskOverride(kiosk, LockTaskOverride(addFeatures = 8)).features)
        assertEquals("the block bit off", 55, applyLockTaskOverride(kiosk, LockTaskOverride(clearFeatures = 64)).features)
    }

    @Test
    fun `OVERVIEW and NOTIFICATIONS need HOME - dropped, never refused by the platform`() {
        assertEquals("HOME cleared takes NOTIFICATIONS (2) with it", 113, applyLockTaskOverride(kiosk, LockTaskOverride(clearFeatures = 4)).features)
        assertEquals("OVERVIEW added without HOME is dropped", 113, applyLockTaskOverride(locked, LockTaskOverride(addFeatures = 8)).features)
        assertEquals("with HOME added too it stays", 113 or 4 or 8, applyLockTaskOverride(locked, LockTaskOverride(addFeatures = 4 or 8)).features)
    }

    @Test
    fun `packages join an existing list only, the rest of the setting untouched`() {
        val extra = LockTaskOverride(extraPackages = setOf("com.google.android.apps.nexuslauncher"))
        assertEquals(kiosk.packages!! + "com.google.android.apps.nexuslauncher", applyLockTaskOverride(kiosk, extra).packages)
        val unpinned = kiosk.copy(packages = null)
        assertNull("kiosk off and unlocked: nothing pinned", applyLockTaskOverride(unpinned, extra).packages)
        val out = applyLockTaskOverride(locked, extra)
        assertEquals(locked.statusBarDisabled, out.statusBarDisabled)
        assertEquals(locked.createWindowsBlocked, out.createWindowsBlocked)
        assertSame("no override: the computed setting", locked, applyLockTaskOverride(locked, null))
    }

    @Test
    fun `features for the log`() {
        assertEquals("113 [SYSTEM_INFO, GLOBAL_ACTIONS, KEYGUARD, BLOCK_ACTIVITY_START_IN_TASK]", describeLockTaskFeatures(113))
        assertEquals("0 []", describeLockTaskFeatures(0))
    }
}
