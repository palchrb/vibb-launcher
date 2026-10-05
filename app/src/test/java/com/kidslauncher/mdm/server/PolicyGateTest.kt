package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kidslauncher.mdm.timerules.Lift
import com.kidslauncher.mdm.timerules.NO_TIME_RULES
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeWindow

class PolicyGateTest {

    private val managed = PolicyResponse(allowlist = listOf("org.example.music"), kioskDesired = true)
    private val unmanaged = PolicyResponse(allowlist = null)
    private val nothingAllowed = PolicyResponse(allowlist = emptyList(), kioskDesired = true)

    // decodeCached

    @Test
    fun `null and blank cache are Absent`() {
        assertEquals(CachedPolicy.Absent, decodeCached(null))
        assertEquals(CachedPolicy.Absent, decodeCached(""))
        assertEquals(CachedPolicy.Absent, decodeCached("  "))
    }

    @Test
    fun `valid cache decodes`() {
        val json = ServerJson.encodeToString(PolicyResponse.serializer(), managed)
        assertEquals(CachedPolicy.Ok(managed), decodeCached(json))
    }

    @Test
    fun `truncated cache is Corrupt`() {
        val json = ServerJson.encodeToString(PolicyResponse.serializer(), managed)
        assertTrue(decodeCached(json.dropLast(5)) is CachedPolicy.Corrupt)
    }

    @Test
    fun `empty object decodes with all defaults`() {
        assertEquals(CachedPolicy.Ok(PolicyResponse()), decodeCached("{}"))
    }

    // judgeFresh

    @Test
    fun `null allowlist after a managed cache is suspect`() {
        assertEquals(
            FreshVerdict.REJECT_SUSPECT,
            judgeFresh(unmanaged, CachedPolicy.Ok(managed), policyEverApplied = true)
        )
    }

    @Test
    fun `null allowlist against a corrupt cache is suspect`() {
        assertEquals(
            FreshVerdict.REJECT_SUSPECT,
            judgeFresh(unmanaged, CachedPolicy.Corrupt("boom"), policyEverApplied = false)
        )
        assertEquals(
            FreshVerdict.REJECT_SUSPECT,
            judgeFresh(unmanaged, CachedPolicy.Corrupt("boom"), policyEverApplied = true)
        )
    }

    @Test
    fun `null allowlist with a missing cache after a policy was applied is suspect`() {
        assertEquals(
            FreshVerdict.REJECT_SUSPECT,
            judgeFresh(unmanaged, CachedPolicy.Absent, policyEverApplied = true)
        )
    }

    @Test
    fun `an empty allowlist is a legitimate policy`() {
        assertEquals(
            FreshVerdict.ACCEPT,
            judgeFresh(nothingAllowed, CachedPolicy.Ok(managed), policyEverApplied = true)
        )
    }

    @Test
    fun `removing the PIN is accepted`() {
        val withPin = managed.copy(overridePinHash = "aa", overridePinSalt = "bb")
        val pinRemoved = managed.copy(overridePinHash = null, overridePinSalt = null)
        assertEquals(
            FreshVerdict.ACCEPT,
            judgeFresh(pinRemoved, CachedPolicy.Ok(withPin), policyEverApplied = true)
        )
    }

    @Test
    fun `a never-managed phone accepts an unmanaged policy`() {
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(unmanaged, CachedPolicy.Absent, policyEverApplied = false))
        assertEquals(
            FreshVerdict.ACCEPT,
            judgeFresh(unmanaged, CachedPolicy.Ok(unmanaged), policyEverApplied = true)
        )
    }

    @Test
    fun `a managed policy is always accepted`() {
        for (cached in listOf(CachedPolicy.Absent, CachedPolicy.Corrupt("x"), CachedPolicy.Ok(unmanaged))) {
            assertEquals(FreshVerdict.ACCEPT, judgeFresh(managed, cached, policyEverApplied = true))
        }
    }

    // choosePolicy

    @Test
    fun `fresh policy wins`() {
        assertEquals(
            PolicyToApply.Apply(nothingAllowed),
            choosePolicy(nothingAllowed, CachedPolicy.Ok(managed), policyEverApplied = true, lastEnforced = null)
        )
    }

    @Test
    fun `without fresh policy the cache applies`() {
        assertEquals(
            PolicyToApply.Apply(managed),
            choosePolicy(null, CachedPolicy.Ok(managed), policyEverApplied = true, lastEnforced = null)
        )
    }

    @Test
    fun `never synced applies no policy so setup works`() {
        assertEquals(
            PolicyToApply.Apply(null),
            choosePolicy(null, CachedPolicy.Absent, policyEverApplied = false, lastEnforced = null)
        )
    }

    private val lastPlan = LastEnforcedPlan.of(managed.copy(bedtimeStartMinutes = 1260, bedtimeEndMinutes = 420))

    @Test
    fun `absent cache after a policy was applied falls back to the last enforced plan`() {
        val decision = choosePolicy(null, CachedPolicy.Absent, policyEverApplied = true, lastEnforced = lastPlan)
        assertEquals(PolicyToApply.Fallback(lastPlan.toPolicy()), decision)
        assertEquals(listOf("org.example.music"), decision.policy?.allowlist)
        assertEquals(true, decision.policy?.kioskDesired)
        assertEquals(1260, decision.policy?.bedtimeStartMinutes)
    }

    @Test
    fun `corrupt cache falls back to the last enforced plan`() {
        for (everApplied in listOf(true, false)) {
            assertEquals(
                PolicyToApply.Fallback(lastPlan.toPolicy()),
                choosePolicy(null, CachedPolicy.Corrupt("boom"), everApplied, lastPlan)
            )
        }
    }

    /** QA step 1 #3: an override or pause ending with an unusable cache must re-lock, not stay open. */
    @Test
    fun `with no last enforced plan either, nothing is allowed and kiosk is on`() {
        val decision = choosePolicy(null, CachedPolicy.Corrupt("boom"), policyEverApplied = true, lastEnforced = null)
        assertEquals(PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK), decision)
        assertEquals(emptyList<String>(), decision.policy?.allowlist)
        assertEquals(true, decision.policy?.kioskDesired)
    }

    @Test
    fun `last enforced plan round trips and tolerates garbage`() {
        assertEquals(lastPlan, LastEnforcedPlan.decode(LastEnforcedPlan.encode(lastPlan)))
        assertEquals(null, LastEnforcedPlan.decode("{not json"))
        assertEquals(null, LastEnforcedPlan.decode(null))
        assertEquals(LastEnforcedPlan(), LastEnforcedPlan.decode("""{"some_future_field": 1}"""))
    }

    // policyState

    @Test
    fun `policy state reports what happened`() {
        val ok = CachedPolicy.Ok(managed)
        assertEquals("ok", policyState(FreshOutcome.ACCEPTED, CachedPolicy.Corrupt("x"), true))
        assertEquals("ok", policyState(FreshOutcome.UNREACHABLE, ok, true))
        assertEquals("ok", policyState(FreshOutcome.UNREACHABLE, CachedPolicy.Absent, false))
        assertEquals("rejected_suspect", policyState(FreshOutcome.REJECTED_SUSPECT, ok, true))
        assertEquals("fresh_decode_failed", policyState(FreshOutcome.DECODE_FAILED, ok, true))
        assertEquals("server_error", policyState(FreshOutcome.SERVER_ERROR, ok, true))
        assertEquals("cache_corrupt", policyState(FreshOutcome.UNREACHABLE, CachedPolicy.Corrupt("x"), true))
        assertEquals("cache_corrupt", policyState(FreshOutcome.UNREACHABLE, CachedPolicy.Absent, true))
    }

    @Test
    fun `a fresh body that doesn't decode is reported, not treated as unreachable`() {
        val decoded = decodeFresh("""{"allowlist": [], "kiosk_desired": null}""")
        assertTrue(decoded is FreshDecode.Failed)
        assertTrue(decodeFresh("") is FreshDecode.Failed)
        assertTrue(decodeFresh(null) is FreshDecode.Failed)
        assertEquals(FreshDecode.Ok(nothingAllowed), decodeFresh("""{"allowlist": [], "kiosk_desired": true}"""))
    }

    // shouldSuspendNewPackage

    private fun suspendNew(
        pkg: String,
        decision: PolicyToApply,
        overrideActive: Boolean = false,
        locked: Boolean = false,
        usable: Set<String> = emptySet(),
    ) = shouldSuspendNewPackage(pkg, decision, overrideActive, OWN, DIALER, scheduleLocked = locked, lockUsableApps = usable)

    @Test
    fun `new package during the lock is left usable only if the lock and the allowlist allow it`() {
        assertFalse(suspendNew("org.example.music", PolicyToApply.Apply(managed), locked = true, usable = setOf("org.example.music")))
        assertTrue(suspendNew("org.example.game", PolicyToApply.Apply(managed), locked = true, usable = setOf("org.example.game")))
    }

    // Time rules (handy step 6)

    private val timePolicy = TimePolicy(
        rules = listOf(TimeRule(1, "Skole", "school", days = List(7) { TimeWindow(480, 840) })),
        dailyBudgetMinutes = List(7) { 60 },
        lifts = listOf(Lift(5, "rule", 1, 30, 1L)),
    )

    @Test
    fun `a response without time_policy is rejected once the phone has had one`() {
        val fresh = managed.copy(timePolicy = null)
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(fresh, CachedPolicy.Ok(managed.copy(timePolicy = timePolicy)), true))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(fresh, CachedPolicy.Corrupt("x"), true, timePolicySeen = true))
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(fresh, CachedPolicy.Ok(managed), true))
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(managed.copy(timePolicy = NO_TIME_RULES), CachedPolicy.Ok(managed), true, timePolicySeen = true))
    }

    @Test
    fun `the last enforced plan keeps the rules and budget but not the lifts`() {
        val plan = LastEnforcedPlan.of(managed.copy(timePolicy = timePolicy))
        assertEquals(timePolicy.copy(lifts = emptyList()), plan.timePolicy)
        val decoded = LastEnforcedPlan.decode(LastEnforcedPlan.encode(plan))!!
        assertEquals(timePolicy.copy(lifts = emptyList()), decoded.toPolicy().timePolicy)
        // A plan written by an older build has no time policy: its windows are converted.
        val old = LastEnforcedPlan.decode("""{"allowlist":[],"bedtime_start_minutes":1260,"bedtime_end_minutes":420}""")!!
        assertNull(old.timePolicy)
        assertEquals(1, KidModeEnforcer.timePolicyOf(old.toPolicy())!!.rules.size)
    }

    @Test
    fun `new package during the schedule lock is suspended, even if allowlisted`() {
        assertTrue(suspendNew("org.example.music", PolicyToApply.Apply(managed), locked = true))
        assertTrue(suspendNew("org.example.game", PolicyToApply.Apply(unmanaged), locked = true))
        assertFalse(suspendNew(OWN, PolicyToApply.Apply(managed), locked = true))
        assertFalse(suspendNew(DIALER, PolicyToApply.Apply(managed), locked = true))
        assertFalse(suspendNew("org.example.music", PolicyToApply.Apply(managed), overrideActive = true, locked = true))
    }

    @Test
    fun `new package not on the allowlist is suspended`() {
        assertTrue(suspendNew("org.example.game", PolicyToApply.Apply(managed)))
        assertFalse(suspendNew("org.example.music", PolicyToApply.Apply(managed)))
        assertTrue(suspendNew("org.example.game", PolicyToApply.Apply(nothingAllowed)))
    }

    @Test
    fun `new package on an unmanaged or never-synced phone is left alone`() {
        assertFalse(suspendNew("org.example.game", PolicyToApply.Apply(unmanaged)))
        assertFalse(suspendNew("org.example.game", PolicyToApply.Apply(null)))
    }

    @Test
    fun `new package with no usable policy follows the fallback`() {
        assertTrue(suspendNew("org.example.game", PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK)))
        assertTrue(suspendNew("org.example.game", PolicyToApply.Fallback(managed)))
        assertFalse(suspendNew("org.example.music", PolicyToApply.Fallback(managed)))
    }

    @Test
    fun `own package, system dialer and an active override are never suspended`() {
        assertFalse(suspendNew(OWN, PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK)))
        assertFalse(suspendNew(DIALER, PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK)))
        assertFalse(suspendNew(DIALER, PolicyToApply.Apply(nothingAllowed)))
        assertFalse(suspendNew("org.example.game", PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK), overrideActive = true))
    }

    private companion object {
        const val OWN = "com.kidslauncher.mdm"
        const val DIALER = "com.android.dialer"
    }
}
