package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.play.PLAY_CORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 9 B4 with qa-09-design.md #2/#3/#4: the kiosk app block and the system helpers pinned with it. */
class LockTaskHelpersTest {
    private val settings = "com.android.settings"
    private val camera = "com.android.camera2"
    private val dialer = "com.android.dialer"
    private val forbidden = setOf(settings, camera)

    private val resolved = mapOf(
        HelperKind.EMERGENCY_DIALER to ResolvedHelper("com.android.phone", system = true),
        HelperKind.TELECOM to ResolvedHelper("com.android.server.telecom", system = true),
        HelperKind.PERMISSION_CONTROLLER to ResolvedHelper("com.google.android.permissioncontroller", system = true),
        HelperKind.CHOOSER to ResolvedHelper("com.android.intentresolver", system = true),
        HelperKind.DOCUMENTS to ResolvedHelper("com.google.android.documentsui", system = true),
        HelperKind.PHOTO_PICKER to ResolvedHelper("com.google.android.providers.media.module", system = true),
        HelperKind.CELL_BROADCAST to ResolvedHelper("com.google.android.cellbroadcastreceiver", system = true),
        HelperKind.RESOLVER to ResolvedHelper("android", system = true),
    )

    @Test
    fun `system helpers resolved on this phone are pinned`() {
        assertEquals(
            setOf(
                "com.android.phone", "com.android.server.telecom", "com.google.android.permissioncontroller",
                "com.android.intentresolver", "com.google.android.documentsui",
                "com.google.android.providers.media.module", "com.google.android.cellbroadcastreceiver", "android",
            ),
            lockTaskHelpers(resolved, forbidden),
        )
    }

    @Test
    fun `never Settings, Play, GMS, GSF or the camera - and never a non-system app`() {
        val hostile = mapOf(
            HelperKind.TELECOM to ResolvedHelper(settings, system = true),
            HelperKind.CHOOSER to ResolvedHelper(camera, system = true),
            HelperKind.DOCUMENTS to ResolvedHelper("com.android.vending", system = true),
            HelperKind.PHOTO_PICKER to ResolvedHelper("com.google.android.gms", system = true),
            HelperKind.CELL_BROADCAST to ResolvedHelper("com.google.android.gsf", system = true),
            HelperKind.PERMISSION_CONTROLLER to ResolvedHelper("com.evil.picker", system = false),
        )
        assertEquals(emptySet<String>(), lockTaskHelpers(hostile, forbidden))
        assertEquals(emptySet<String>(), lockTaskHelpers(mapOf(HelperKind.TELECOM to null), forbidden))
    }

    @Test
    fun `a forbidden or non-system first match doesn't hide the real helper (qa-09-code 6)`() {
        val telecom = ResolvedHelper("com.android.server.telecom", system = true)
        assertEquals(
            telecom,
            firstHelper(listOf(ResolvedHelper(camera, true), ResolvedHelper("com.evil", false), ResolvedHelper("com.android.vending", true), telecom), forbidden),
        )
        assertEquals(null, firstHelper(listOf(ResolvedHelper(settings, true)), forbidden))
    }

    @Test
    fun `the block bit follows the server switch and never comes from lock_task_features`() {
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD or 1, lockTaskFeatures(1 or 64, blockActivityStart = false))
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD or 1 or 64, lockTaskFeatures(1, blockActivityStart = true))
    }

    private val callsOn = CallPolicyState.Managed(CallRules(callsEnabled = true))
    private val controllable = listOf("com.kidslauncher.mdm", dialer, "org.example.game")
    private val helpers = lockTaskHelpers(resolved, forbidden)

    private fun plan(block: Boolean, locked: Boolean = false, calls: CallPolicyState = callsOn, allow: List<String> = listOf("org.example.game", dialer)) =
        computeEnforcementPlan(
            allow, true, 0, false, controllable, "com.kidslauncher.mdm", dialer,
            callState = calls, ourDialerActive = true, scheduleLocked = locked,
            blockActivityStart = block, lockTaskHelpers = helpers + dialer + "com.android.vending",
        )

    @Test
    fun `helpers are pinned only with the block, also during a time-rule lock`() {
        val on = plan(block = true)
        assertTrue(on.kioskPackages!!.containsAll(helpers))
        assertTrue(on.lockTaskFeatures and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK != 0)
        val locked = plan(block = true, locked = true)
        assertTrue(locked.kioskPackages!!.containsAll(helpers))
        assertTrue("com.android.phone" in locked.kioskPackages!!)
        val off = plan(block = false)
        assertFalse(off.kioskPackages!!.any { it in helpers })
        assertEquals(0, off.lockTaskFeatures and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK)
    }

    @Test
    fun `with the block the system dialer is always pinned - in-call UI and emergency (qa-09-code 1)`() {
        val noCallsLock = computeEnforcementPlan(
            listOf("org.example.game"), true, 0, false, controllable, "com.kidslauncher.mdm", dialer,
            scheduleLocked = true, ruleBlocksCalls = true, blockActivityStart = true, lockTaskHelpers = helpers,
        )
        for (kiosk in listOf(
            plan(true)!!.kioskPackages!!, // calls managed
            plan(true, calls = CallPolicyState.Unmanaged, allow = listOf("org.example.game"))!!.kioskPackages!!,
            plan(true, locked = true)!!.kioskPackages!!,
            noCallsLock.kioskPackages!!,
        )) {
            assertTrue(dialer in kiosk)
            assertFalse(kiosk.any { it in PLAY_CORE })
        }
        // Pinning changes no call rule: its keypad still goes through our redirection/in-call
        // services (managed) or DISALLOW_OUTGOING_CALLS.
        assertTrue(noCallsLock.restrictOutgoingCalls)
        assertTrue(plan(true, calls = CallPolicyState.Unmanaged, allow = listOf("org.example.game")).restrictOutgoingCalls)
        assertEquals(plan(false).restrictOutgoingCalls, plan(true).restrictOutgoingCalls)
        // Without the block, the old rule: never pinned while calls are managed.
        assertFalse(dialer in plan(false).kioskPackages!!)
    }
}

/** Handy step 10 (design §3, QA 10 #1/#10): the lock-task setting while the PIN lock is LOCKED. */
class PinLockTaskTest {
    private val own = "com.kidslauncher.mdm"
    private val kiosk = setOf(own, "org.fossify.calendar", "com.android.phone")
    private val serverFeatures = LOCK_TASK_FEATURE_SYSTEM_INFO or LOCK_TASK_FEATURE_NOTIFICATIONS or
        LOCK_TASK_FEATURE_HOME or LOCK_TASK_FEATURE_OVERVIEW or LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
        LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    private val helpers = setOf("com.android.phone", "com.android.server.telecom", "com.android.dialer", "com.google.android.deskclock")

    @Test
    fun `featuresWhileLocked drops the shade, Home and Recents and keeps the rest`() {
        val locked = featuresWhileLocked(serverFeatures, true)
        assertEquals(0, locked and LOCK_TASK_FEATURE_NOTIFICATIONS)
        assertEquals(0, locked and LOCK_TASK_FEATURE_HOME)
        assertEquals(0, locked and LOCK_TASK_FEATURE_OVERVIEW)
        assertTrue(locked and LOCK_TASK_FEATURE_KEYGUARD != 0)
        assertTrue(locked and LOCK_TASK_FEATURE_SYSTEM_INFO != 0)
        assertTrue(locked and LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0)
        assertTrue(locked and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK != 0)
        assertEquals(serverFeatures, featuresWhileLocked(serverFeatures, false))
        assertTrue("keyguard is forced", featuresWhileLocked(0, true) and LOCK_TASK_FEATURE_KEYGUARD != 0)
    }

    @Test
    fun `kiosk on - the kiosk list is never touched, only the features`() {
        val locked = lockTaskWhileLocked(kiosk, serverFeatures, restrictCreateWindows = false, locked = true, ownPackage = own, lockHelpers = helpers)
        assertEquals(kiosk, locked.packages)
        assertEquals(featuresWhileLocked(serverFeatures, true), locked.features)
        assertTrue(locked.statusBarDisabled)
        assertTrue("no overlays over the lock", locked.createWindowsBlocked)
        val open = lockTaskWhileLocked(kiosk, serverFeatures, restrictCreateWindows = false, locked = false, ownPackage = own, lockHelpers = helpers)
        assertEquals(LockTaskSetting(kiosk, serverFeatures, statusBarDisabled = false, createWindowsBlocked = false), open)
    }

    @Test
    fun `kiosk off - ours plus the helpers only, KEYGUARD, GLOBAL_ACTIONS and SYSTEM_INFO`() {
        val locked = lockTaskWhileLocked(null, serverFeatures, restrictCreateWindows = false, locked = true, ownPackage = own,
            lockHelpers = helpers + com.kidslauncher.mdm.play.PLAY_STORE)
        assertEquals(setOf(own) + helpers, locked.packages)
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_GLOBAL_ACTIONS or LOCK_TASK_FEATURE_SYSTEM_INFO, locked.features)
        assertTrue(locked.statusBarDisabled)
        assertTrue(locked.createWindowsBlocked)
        val open = lockTaskWhileLocked(null, serverFeatures, restrictCreateWindows = true, locked = false, ownPackage = own, lockHelpers = helpers)
        assertEquals(null, open.packages)
        assertFalse(open.statusBarDisabled)
        assertTrue("the budget's own overlay block stays", open.createWindowsBlocked)
    }

    @Test
    fun `the lock's helpers are system packages only, never Settings, the camera or Play`() {
        val got = pinLockHelpers(
            emergencyDialer = ResolvedHelper("com.android.phone", system = true),
            telecom = ResolvedHelper("com.android.server.telecom", system = true),
            systemDialer = ResolvedHelper("com.android.dialer", system = true),
            alarmApp = ResolvedHelper("com.example.thirdpartyclock", system = false),
            forbidden = setOf("com.android.settings"),
        )
        assertEquals(setOf("com.android.phone", "com.android.server.telecom", "com.android.dialer"), got)
        val withRole = pinLockHelpers(
            null, null, ResolvedHelper("com.android.dialer", system = true), null, emptySet(), ourDialerHeld = true,
        )
        assertEquals("our dialer role held: the system dialer's full UI isn't pinned (qa-10-code 5)", emptySet<String>(), withRole)
        val clock = pinLockHelpers(null, null, null, ResolvedHelper("com.google.android.deskclock", system = true), emptySet())
        assertEquals(setOf("com.google.android.deskclock"), clock)
        assertEquals(emptySet<String>(), pinLockHelpers(ResolvedHelper("com.android.settings", true), null, null, null, setOf("com.android.settings")))
    }
}
