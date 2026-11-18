package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.push.BACKSTOP_INTERVAL_MS
import com.kidslauncher.mdm.push.backstopDelayMs
import com.kidslauncher.mdm.server.dto.LocationPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.PolicyZone
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyTest {
    private val minute = 60_000L

    @Test
    fun `off never touches location, even on request`() {
        val off = LocationPolicy("off", 30)
        assertEquals(LocationAction.NONE, locationAction(off, forced = false, sinceLastFreshMs = Long.MAX_VALUE))
        assertEquals(LocationAction.NONE, locationAction(off, forced = true, sinceLastFreshMs = 0))
    }

    @Test
    fun `on request takes a fresh fix only for a locate or ring command`() {
        val onRequest = LocationPolicy("on_request", 30)
        assertEquals(LocationAction.NONE, locationAction(onRequest, false, Long.MAX_VALUE))
        assertEquals(LocationAction.FRESH, locationAction(onRequest, true, 0))
        assertEquals("unknown modes count as on request", LocationAction.NONE, locationAction(LocationPolicy("weird", 5), false, Long.MAX_VALUE))
    }

    @Test
    fun `interval takes a fresh fix when due, the cached one otherwise`() {
        val every30 = LocationPolicy("interval", 30)
        assertEquals(LocationAction.CACHED, locationAction(every30, false, 29 * minute))
        assertEquals(LocationAction.FRESH, locationAction(every30, false, 30 * minute))
        assertEquals(LocationAction.FRESH, locationAction(every30, true, 0))
        assertEquals("clock went back", LocationAction.FRESH, locationAction(every30, false, -1))
        assertEquals("at least 10 minutes", LocationAction.CACHED, locationAction(LocationPolicy("interval", 1), false, 9 * minute))
    }

    @Test
    fun `no location policy reads as only when asked`() {
        assertEquals(LocationAction.NONE, locationAction(null, false, 60 * minute))
        assertEquals(LocationAction.FRESH, locationAction(null, true, 0))
    }

    @Test
    fun `the locate answer says what happened`() {
        assertEquals(false to "location is off for this device", locateResultMessage(LocationAction.NONE, null, null))
        assertEquals(false to "no location fix", locateResultMessage(LocationAction.FRESH, null, null))
        assertEquals(true to "fix ±12 m, 3 s old", locateResultMessage(LocationAction.FRESH, 12.7f, 3))
        assertEquals(true to "fix, 0 s old", locateResultMessage(LocationAction.FRESH, null, -4))
    }

    @Test
    fun `location off switches system location off, the hardening switch turns it on otherwise`() {
        assertEquals(false, systemLocationTarget(LocationPolicy("off", 30), hardeningForcesOn = true))
        assertEquals(false, systemLocationTarget(LocationPolicy("off", 30), hardeningForcesOn = false))
        assertEquals(true, systemLocationTarget(LocationPolicy("on_request", 30), hardeningForcesOn = true))
        assertEquals(null, systemLocationTarget(LocationPolicy("interval", 30), hardeningForcesOn = false))
        assertEquals(true, systemLocationTarget(null, hardeningForcesOn = true))
    }

    /** Upstream 619f2bfab6 + 395a926122: the parent's `locate` asks fused first and waits longer; the rest is design 64's. */
    @Test
    fun `locate's fix is fused first and longer, the sync's own fixes keep GPS first and 15 s`() {
        val locate = preciseFix(explicit = true)
        assertEquals(
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            locate.providers,
        )
        assertTrue("past getCurrentLocation's 30 s cap: one update instead", locate.oneUpdate && locate.boundMs > 30_000L)
        assertEquals(60_000L, locate.boundMs)
        val own = preciseFix(explicit = false)
        assertEquals(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), own.providers)
        assertEquals(15_000L, own.boundMs)
        assertFalse(own.oneUpdate)
    }

    // Security review P-M1 (2026-11-17 apps review): the location under every policy the phone may enforce.

    private val managed = PolicyResponse(allowlist = listOf("org.example.music"), kioskDesired = true)
    private val zone = PolicyZone(id = 7, latitude = 59.9, longitude = 10.7, radiusM = 150)

    /** What a fallback enforces once the accepted [policy]'s plan went through the prefs and the cache is unusable. */
    private fun fallbackFrom(policy: PolicyResponse): PolicyResponse? {
        val stored = LastEnforcedPlan.decode(LastEnforcedPlan.encode(LastEnforcedPlan.of(policy)))
        val decision = choosePolicy(null, CachedPolicy.Corrupt("boom"), policyEverApplied = true, lastEnforced = stored)
        assertTrue(decision is PolicyToApply.Fallback)
        return decision.policy
    }

    @Test
    fun `a fallback keeps the parent's location choice, zones included`() {
        for (chosen in listOf(
            LocationPolicy("off", 30),
            LocationPolicy("on_request", 45),
            LocationPolicy("interval", 60, listOf(zone)),
        )) {
            assertEquals(chosen, enforcedLocationPolicy(fallbackFrom(managed.copy(locationPolicy = chosen))))
        }
        // With no cache at all (absent after a policy was applied) the same.
        val off = LocationPolicy("off", 30)
        val plan = LastEnforcedPlan.of(managed.copy(locationPolicy = off))
        assertEquals(off, enforcedLocationPolicy(choosePolicy(null, CachedPolicy.Absent, true, plan).policy))
    }

    @Test
    fun `under a fallback the parent's Off stays off - no fix, not even for a locate, and Location goes off`() {
        val policy = enforcedLocationPolicy(fallbackFrom(managed.copy(locationPolicy = LocationPolicy("off", 30))))
        assertEquals(LocationAction.NONE, locationAction(policy, forced = false, sinceLastFreshMs = Long.MAX_VALUE))
        assertEquals(LocationAction.NONE, locationAction(policy, forced = true, sinceLastFreshMs = Long.MAX_VALUE))
        assertEquals(false, systemLocationTarget(policy, hardeningForcesOn = true))
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(sseConnected = true, locationPolicy = policy, sinceLastFreshLocationMs = Long.MAX_VALUE))
    }

    @Test
    fun `with no parent choice known a fallback is only when asked - never a fix every 10 minutes`() {
        val written = LastEnforcedPlan.decode("""{"allowlist":["org.example.music"],"kiosk_desired":true}""")!!
        for (fallback in listOf(
            // A plan written before P-M1's fix carries no location policy.
            choosePolicy(null, CachedPolicy.Corrupt("boom"), policyEverApplied = true, lastEnforced = written).policy,
            // Neither the cache nor the plan reads.
            choosePolicy(null, CachedPolicy.Corrupt("boom"), policyEverApplied = true, lastEnforced = null).policy,
            // A fallback from a policy without one.
            fallbackFrom(managed),
        )) {
            val policy = enforcedLocationPolicy(fallback)
            assertEquals("on_request", policy?.mode)
            assertTrue(policy!!.zones.isEmpty())
            assertEquals(LocationAction.NONE, locationAction(policy, forced = false, sinceLastFreshMs = Long.MAX_VALUE))
            assertEquals("the parent's locate still answers", LocationAction.FRESH, locationAction(policy, forced = true, sinceLastFreshMs = 0))
            assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(true, policy, Long.MAX_VALUE))
        }
    }

    @Test
    fun `fresh and cached policies go by their own location policy, a phone never managed by none`() {
        val every20 = LocationPolicy("interval", 20)
        val withIt = managed.copy(locationPolicy = every20)
        assertEquals(every20, enforcedLocationPolicy(choosePolicy(withIt, CachedPolicy.Absent, false, null).policy))
        assertEquals(every20, enforcedLocationPolicy(choosePolicy(null, CachedPolicy.Ok(withIt), true, null).policy))
        assertEquals("on_request", enforcedLocationPolicy(choosePolicy(null, CachedPolicy.Ok(managed), true, null).policy)?.mode)
        assertNull(enforcedLocationPolicy(choosePolicy(null, CachedPolicy.Absent, policyEverApplied = false, lastEnforced = null).policy))
    }
}
