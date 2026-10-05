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
    private val forbidden = setOf(settings, camera, dialer)

    private val resolved = mapOf(
        HelperKind.EMERGENCY_DIALER to ResolvedHelper("com.android.phone", system = true),
        HelperKind.TELECOM to ResolvedHelper("com.android.server.telecom", system = true),
        HelperKind.PERMISSION_CONTROLLER to ResolvedHelper("com.google.android.permissioncontroller", system = true),
        HelperKind.CHOOSER to ResolvedHelper("com.android.intentresolver", system = true),
        HelperKind.DOCUMENTS to ResolvedHelper("com.google.android.documentsui", system = true),
        HelperKind.PHOTO_PICKER to ResolvedHelper("com.google.android.providers.media.module", system = true),
        HelperKind.CELL_BROADCAST to ResolvedHelper("com.google.android.cellbroadcastreceiver", system = true),
    )

    @Test
    fun `system helpers resolved on this phone are pinned`() {
        assertEquals(
            setOf(
                "com.android.phone", "com.android.server.telecom", "com.google.android.permissioncontroller",
                "com.android.intentresolver", "com.google.android.documentsui",
                "com.google.android.providers.media.module", "com.google.android.cellbroadcastreceiver",
            ),
            lockTaskHelpers(resolved, forbidden),
        )
    }

    @Test
    fun `never Settings, Play, GMS, GSF, the system dialer or the camera - and never a non-system app`() {
        val hostile = mapOf(
            HelperKind.EMERGENCY_DIALER to ResolvedHelper(dialer, system = true),
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
    fun `helpers never pin the system dialer or Play - the call rules decide the dialer (QA 09 #3)`() {
        for (block in listOf(true, false)) {
            val kiosk = plan(block)!!.kioskPackages!!
            assertFalse(dialer in kiosk)
            assertFalse(kiosk.any { it in PLAY_CORE })
        }
        // Unmanaged calls with an allowlisted dialer: still pinned by the allowlist, as before.
        assertTrue(dialer in plan(true, calls = CallPolicyState.Unmanaged).kioskPackages!!)
        // ...but not during a no-calls rule lock (pinning stays under the call rules).
        assertFalse(
            dialer in computeEnforcementPlan(
                listOf(dialer), true, 0, false, controllable, "com.kidslauncher.mdm", dialer,
                scheduleLocked = true, ruleBlocksCalls = true, blockActivityStart = true, lockTaskHelpers = helpers + dialer,
            ).kioskPackages!!,
        )
    }
}
