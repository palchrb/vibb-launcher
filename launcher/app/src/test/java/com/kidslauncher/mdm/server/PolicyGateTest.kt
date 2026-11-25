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

    // judgeCached

    @Test
    fun `a saved copy without an app list on a managed phone counts as unreadable`() {
        val cached = CachedPolicy.Ok(PolicyResponse())
        val judged = judgeCached(cached, policyEverApplied = true, lastEnforced = LastEnforcedPlan.of(managed))
        assertEquals(CachedPolicy.Corrupt("no_allowlist"), judged)
        // ... and the sync enforces the last-enforced plan, reported as cache_corrupt.
        val plan = LastEnforcedPlan.of(managed)
        assertEquals(PolicyToApply.Fallback(plan.toPolicy()), choosePolicy(null, judged, true, plan))
        assertEquals("cache_corrupt", policyState(FreshOutcome.UNREACHABLE, judged, true))
        // A missing or unreadable plan after a policy was applied: unreadable too, nothing allowed.
        assertEquals(CachedPolicy.Corrupt("no_allowlist"), judgeCached(cached, true, null))
        assertEquals(PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK), choosePolicy(null, judgeCached(cached, true, null), true, null))
        // A fresh policy without an app list is then not accepted either.
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(unmanaged, judged, policyEverApplied = true))
    }

    @Test
    fun `a saved copy without an app list stays usable where apps were never managed`() {
        val cached = CachedPolicy.Ok(unmanaged)
        // A phone that has never had a policy: setup still works.
        assertEquals(cached, judgeCached(cached, policyEverApplied = false, lastEnforced = null))
        assertEquals(PolicyToApply.Apply(unmanaged), choosePolicy(null, judgeCached(cached, false, null), false, null))
        // Apps unmanaged by the parent: the plan written with the copy has no app list either.
        assertEquals(cached, judgeCached(cached, policyEverApplied = true, lastEnforced = LastEnforcedPlan.of(unmanaged)))
        assertEquals("ok", policyState(FreshOutcome.UNREACHABLE, judgeCached(cached, true, LastEnforcedPlan.of(unmanaged)), true))
    }

    @Test
    fun `a saved copy with an app list applies`() {
        val cached = CachedPolicy.Ok(managed)
        assertEquals(cached, judgeCached(cached, policyEverApplied = true, lastEnforced = null))
        assertEquals(PolicyToApply.Apply(managed), choosePolicy(null, judgeCached(cached, true, null), true, null))
        assertEquals(CachedPolicy.Absent, judgeCached(CachedPolicy.Absent, true, null))
        assertEquals(CachedPolicy.Corrupt("x"), judgeCached(CachedPolicy.Corrupt("x"), true, null))
    }

    @Test
    fun `the sync and the time rules read the saved copy through judgeCached`() {
        val root = java.io.File("src/main/java/com/kidslauncher/mdm").takeIf { it.exists() } ?: java.io.File("app/src/main/java/com/kidslauncher/mdm")
        assertTrue(root.resolve("server/MdmSyncWorker.kt").readText().contains("return judgeCached(decoded, LauncherPreferences.mdm().policyEverApplied(), lastEnforcedPlan())"))
        assertTrue(root.resolve("timerules/TimeRulesStore.kt").readText().contains("judgeCached(decodeCached("))
    }

    @Test
    fun `fresh policy wins`() {
        assertEquals(
            PolicyToApply.Apply(nothingAllowed, fromServer = true),
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

    private val lastPlan = LastEnforcedPlan.of(managed.copy(screenTimeoutSeconds = 60))

    @Test
    fun `absent cache after a policy was applied falls back to the last enforced plan`() {
        val decision = choosePolicy(null, CachedPolicy.Absent, policyEverApplied = true, lastEnforced = lastPlan)
        assertEquals(PolicyToApply.Fallback(lastPlan.toPolicy()), decision)
        assertEquals(listOf("org.example.music"), decision.policy?.allowlist)
        assertEquals(true, decision.policy?.kioskDesired)
        assertEquals(60, decision.policy?.screenTimeoutSeconds)
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
    fun `an installed or updated recents provider is never suspended (16d)`() {
        val recents = "com.google.android.apps.nexuslauncher"
        for (locked in listOf(false, true)) {
            assertFalse(shouldSuspendNewPackage(recents, PolicyToApply.Apply(managed), false, OWN, DIALER, scheduleLocked = locked, recentsPackage = recents))
        }
        assertTrue(suspendNew(recents, PolicyToApply.Apply(managed)))
    }

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
        // A plan without a time policy has no rules.
        val none = LastEnforcedPlan.decode("""{"allowlist":[]}""")!!
        assertNull(none.timePolicy)
        assertTrue(KidModeEnforcer.timePolicyOf(none.toPolicy())!!.rules.isEmpty())
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

    // Design 77 D2: once a phone has seen calls_mode/sms_mode, a policy without them is rejected whole.

    private val withModes = managed.copy(
        callPolicy = com.kidslauncher.mdm.server.dto.CallPolicy(callsMode = "managed", smsMode = "managed"),
    )
    private val noModes = managed.copy(callPolicy = com.kidslauncher.mdm.server.dto.CallPolicy())

    @Test
    fun `channel modes - missing after they were seen, or unknown, is rejected`() {
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(noModes, CachedPolicy.Ok(managed), true))
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(withModes, CachedPolicy.Ok(noModes), true))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(noModes, CachedPolicy.Ok(withModes), true))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(noModes, CachedPolicy.Corrupt("x"), true, channelModesSeen = true))
        val half = managed.copy(callPolicy = com.kidslauncher.mdm.server.dto.CallPolicy(callsMode = "managed"))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(half, CachedPolicy.Ok(managed), true))
        val unknown = managed.copy(callPolicy = com.kidslauncher.mdm.server.dto.CallPolicy(callsMode = "managed", smsMode = "filtered"))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(unknown, CachedPolicy.Ok(managed), true))
    }

    private companion object {
        const val OWN = "me.vibb.launcher"
        const val DIALER = "com.android.dialer"
    }
}
