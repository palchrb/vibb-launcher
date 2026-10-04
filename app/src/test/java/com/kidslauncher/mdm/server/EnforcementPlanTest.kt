package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementPlanTest {

    private val controllable = listOf(OWN, DIALER, "org.example.music", "org.example.game", "com.android.chrome")

    private fun plan(
        allowlist: List<String>?,
        kioskDesired: Boolean = true,
        features: Long = 0,
        overrideActive: Boolean = false,
        dialer: String? = DIALER,
    ) = computeEnforcementPlan(allowlist, kioskDesired, features, overrideActive, controllable, OWN, dialer)

    @Test
    fun `null allowlist suspends nothing and has no kiosk`() {
        val plan = plan(null)
        assertTrue(plan.suspend.isEmpty())
        assertNull(plan.kioskPackages)
    }

    @Test
    fun `empty allowlist suspends everything except own and dialer and pins only own`() {
        val plan = plan(emptyList())
        assertEquals(setOf("org.example.music", "org.example.game", "com.android.chrome"), plan.suspend)
        assertEquals(setOf(OWN), plan.kioskPackages)
    }

    @Test
    fun `allowlist suspends the rest and pins allowed plus own`() {
        val plan = plan(listOf("org.example.music"))
        assertEquals(setOf("org.example.game", "com.android.chrome"), plan.suspend)
        assertEquals(setOf("org.example.music", OWN), plan.kioskPackages)
    }

    @Test
    fun `kiosk not desired means no pinning but still suspends`() {
        val plan = plan(listOf("org.example.music"), kioskDesired = false)
        assertNull(plan.kioskPackages)
        assertEquals(setOf("org.example.game", "com.android.chrome"), plan.suspend)
    }

    @Test
    fun `override active suspends nothing and has no kiosk`() {
        val plan = plan(emptyList(), overrideActive = true)
        assertTrue(plan.suspend.isEmpty())
        assertNull(plan.kioskPackages)
    }

    @Test
    fun `own package is never suspended`() {
        for (allowlist in listOf(null, emptyList(), listOf("org.example.music"))) {
            assertFalse(OWN in plan(allowlist).suspend)
            assertTrue(OWN in plan(allowlist).neverRestrict)
        }
    }

    /** QA #1: the preloaded dialer is the in-call UI for emergency calls. */
    @Test
    fun `system dialer is never suspended or hidden, managed or not`() {
        for (allowlist in listOf(null, emptyList(), listOf("org.example.music"))) {
            for (kiosk in listOf(true, false)) {
                for (override in listOf(true, false)) {
                    val plan = plan(allowlist, kioskDesired = kiosk, overrideActive = override)
                    assertFalse("suspended for $allowlist", DIALER in plan.suspend)
                    assertTrue(DIALER in plan.neverRestrict)
                }
            }
        }
    }

    @Test
    fun `system dialer is not pinned on our account`() {
        assertFalse(DIALER in plan(emptyList()).kioskPackages.orEmpty())
        assertFalse(DIALER in plan(listOf("org.example.music")).kioskPackages.orEmpty())
        // Only an explicit parent choice pins it.
        assertTrue(DIALER in plan(listOf(DIALER)).kioskPackages.orEmpty())
    }

    @Test
    fun `unknown system dialer exempts nothing extra`() {
        val plan = plan(emptyList(), dialer = null)
        assertTrue(DIALER in plan.suspend)
        assertEquals(setOf(OWN), plan.neverRestrict)
    }

    /** QA step 1 #1: an unsuspended dialer must not be a free keypad. */
    @Test
    fun `outgoing calls are restricted while managed unless the dialer is allowlisted`() {
        assertTrue(plan(emptyList()).restrictOutgoingCalls)
        assertTrue(plan(listOf("org.example.music")).restrictOutgoingCalls)
        assertTrue(plan(listOf("org.example.music"), kioskDesired = false).restrictOutgoingCalls)
        assertFalse(plan(listOf(DIALER)).restrictOutgoingCalls)
        assertFalse(plan(null).restrictOutgoingCalls)
        assertFalse(plan(emptyList(), overrideActive = true).restrictOutgoingCalls)
        // No known system dialer: nothing is exempt, so any dialer is simply suspended.
        assertFalse(plan(emptyList(), dialer = null).restrictOutgoingCalls)
    }

    @Test
    fun `date and time are locked while managed and released otherwise`() {
        assertTrue(plan(emptyList()).lockDateTime)
        assertTrue(plan(listOf("org.example.music"), kioskDesired = false).lockDateTime)
        assertFalse(plan(null).lockDateTime)
        assertFalse(plan(listOf("org.example.music"), overrideActive = true).lockDateTime)
    }

    @Test
    fun `keyguard is always forced on`() {
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD, plan(emptyList(), features = 0).lockTaskFeatures)
        assertEquals(63, plan(emptyList(), features = 63).lockTaskFeatures)
        assertEquals(1 or LOCK_TASK_FEATURE_KEYGUARD, plan(emptyList(), features = 1).lockTaskFeatures)
    }

    private companion object {
        const val OWN = "com.kidslauncher.mdm"
        const val DIALER = "com.android.dialer"
    }
}
