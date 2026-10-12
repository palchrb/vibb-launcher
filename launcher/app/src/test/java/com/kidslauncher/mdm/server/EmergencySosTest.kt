package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 25b-H: five power presses on the lock showed "Emergency information is not available" (the
 * kiosk block's BlockedAppActivity) - SystemUI starts the SOS activity as a new task, and its
 * package wasn't pinned. It is now a lock helper (kiosk on and off), never restricted, never listed.
 */
class EmergencySosTest {
    private val pixelSos = "com.google.android.apps.safetyhub"
    private val aospSos = "com.android.emergency"
    private val settings = "com.android.settings"
    private val own = "me.vibb.launcher"
    private val dialer = "com.android.dialer"

    @Test
    fun `the SOS package is SystemUI's choice - the preferred handler, else the first`() {
        assertEquals(aospSos, emergencySosHandler(listOf(aospSos), emptySet()))
        assertEquals(pixelSos, emergencySosHandler(listOf(aospSos, pixelSos), emptySet(), preferred = pixelSos))
        assertEquals("a preferred package that doesn't handle it: SystemUI takes the first", aospSos,
            emergencySosHandler(listOf(aospSos), emptySet(), preferred = pixelSos))
        assertNull(emergencySosHandler(emptyList(), emptySet()))
        // Never Settings, the camera or Play - and no other handler instead (SystemUI wouldn't start it).
        assertNull(emergencySosHandler(listOf(settings, aospSos), setOf(settings)))
        assertNull(emergencySosHandler(listOf("com.google.android.gms"), emptySet()))
        assertEquals("com.android.systemui.action.LAUNCH_EMERGENCY", ACTION_LAUNCH_EMERGENCY)
    }

    @Test
    fun `it is pinned while LOCKED - kiosk off through the lock's helpers, kiosk on through the kiosk list`() {
        val kioskOff = pinLockHelpers(null, null, null, null, emptySet(), emergencySos = ResolvedHelper(aospSos, system = true))
        assertEquals(setOf(aospSos), kioskOff)
        assertEquals(emptySet<String>(), pinLockHelpers(null, null, null, null, emptySet(), emergencySos = ResolvedHelper(aospSos, system = false)))
        assertTrue(aospSos in lockTaskHelpers(mapOf(HelperKind.EMERGENCY_SOS to ResolvedHelper(aospSos, system = true)), emptySet()))
        val controllable = listOf(own, "org.example.game", aospSos)
        for (block in listOf(false, true)) {
            for (locked in listOf(false, true)) {
                val plan = computeEnforcementPlan(
                    listOf("org.example.game"), true, 0, false, controllable, own, dialer,
                    scheduleLocked = locked, blockActivityStart = block, emergencySos = aospSos,
                )
                assertTrue("block $block locked $locked", aospSos in plan.kioskPackages!!)
                assertTrue(aospSos in plan.neverRestrict)
                assertFalse(aospSos in plan.hide)
                assertFalse(aospSos in plan.suspend)
                // The LOCKED pass keeps the kiosk list as it is: the SOS package stays pinned.
                val whileLocked = lockTaskWhileLocked(plan.kioskPackages, plan.lockTaskFeatures, false, true, own, emptySet())
                assertTrue(aospSos in whileLocked.packages!!)
            }
        }
        // Kiosk off: no list from the plan, still never restricted.
        val off = computeEnforcementPlan(listOf("org.example.game"), false, 0, false, controllable, own, dialer, emergencySos = aospSos)
        assertNull(off.kioskPackages)
        assertFalse(aospSos in off.hide)
        // Unresolved: an ordinary package.
        assertTrue(aospSos in computeEnforcementPlan(listOf("org.example.game"), true, 0, false, controllable, own, dialer).hide)
    }

    @Test
    fun `an update of it is never suspended, and it is never on the kid's list`() {
        val managed = PolicyToApply.Apply(PolicyResponse(allowlist = listOf("org.example.game"), kioskDesired = true))
        for (locked in listOf(false, true)) {
            assertFalse(shouldSuspendNewPackage(pixelSos, managed, false, own, dialer, scheduleLocked = locked, emergencySos = pixelSos))
            assertTrue(shouldSuspendNewPackage(pixelSos, managed, false, own, dialer, scheduleLocked = locked))
        }
        for (installMode in listOf(false, true)) {
            assertFalse(AppFilter.kidListable(pixelSos, null, null, installMode, emergencySos = pixelSos))
        }
        assertTrue(AppFilter.kidListable("org.example.game", null, null, false, emergencySos = pixelSos))
    }
}
