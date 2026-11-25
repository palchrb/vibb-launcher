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
        assertTrue(root.resolve("server/MdmSyncWorker.kt").readText().contains("return judgeCached(decoded, everManaged(mdm.policyEverApplied(), EnrolmentWitness.policyAppliedNow()), plan, mdm.callsManagedLast())"))
        val times = root.resolve("timerules/TimeRulesStore.kt").readText()
        assertTrue(times.contains("judgeCached(\n                decodeCached("))
        assertTrue(times.contains("settings_mdm_calls_managed_last_key"))
        assertTrue(times.contains("com.kidslauncher.mdm.server.EnrolmentWitness.policyApplied(context)"))
    }

    /** The plan written with every accepted policy keeps a null app list through its own encode and decode, so a phone
     * whose apps are unmanaged reads its saved copy as usable after a restart too. */
    @Test
    fun `a null app list survives the last-enforced plan's round trip`() {
        val stored = LastEnforcedPlan.decode(LastEnforcedPlan.encode(LastEnforcedPlan.of(unmanaged)))
        assertNull(stored!!.allowlist)
        assertEquals(CachedPolicy.Ok(unmanaged), judgeCached(CachedPolicy.Ok(unmanaged), true, stored))
        val managedPlan = LastEnforcedPlan.decode(LastEnforcedPlan.encode(LastEnforcedPlan.of(managed)))
        assertEquals(CachedPolicy.Corrupt(CachedPolicy.NO_ALLOWLIST), judgeCached(CachedPolicy.Ok(unmanaged), true, managedPlan))
    }

    /** A phone that manages calls: a saved copy without call_policy is unreadable, whatever its app list. Where its
     * apps are unmanaged, a fresh policy without an app list is still taken; a copy unreadable for its app list or one
     * that doesn't decode still rejects one. */
    @Test
    fun `a saved copy without call_policy on a phone that manages calls counts as unreadable`() {
        val calls = com.kidslauncher.mdm.server.dto.CallPolicy(callsMode = "managed", smsMode = "managed")
        val unmanagedPlan = LastEnforcedPlan.of(unmanaged)
        // Calls managed, apps not: the copy `{}` is unreadable, its apps unmanaged.
        val bare = judgeCached(CachedPolicy.Ok(PolicyResponse()), true, unmanagedPlan, callsManagedLast = true)
        assertEquals(CachedPolicy.Corrupt(CachedPolicy.NO_CALL_POLICY, appsManaged = false), bare)
        assertEquals("cache_corrupt", policyState(FreshOutcome.UNREACHABLE, bare, true))
        assertEquals(PolicyToApply.Fallback(unmanagedPlan.toPolicy()), choosePolicy(null, bare, true, unmanagedPlan))
        val fresh = unmanaged.copy(callPolicy = calls)
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(fresh, bare, policyEverApplied = true, callsManagedLast = true))
        // ... and without the calls flag, the same copy stays usable.
        assertEquals(CachedPolicy.Ok(PolicyResponse()), judgeCached(CachedPolicy.Ok(PolicyResponse()), true, unmanagedPlan))
        // With an app list: unreadable, its apps managed - a fresh policy without an app list is rejected.
        val withApps = judgeCached(CachedPolicy.Ok(managed), true, null, callsManagedLast = true)
        assertEquals(CachedPolicy.Corrupt(CachedPolicy.NO_CALL_POLICY, appsManaged = true), withApps)
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(fresh, withApps, policyEverApplied = true, callsManagedLast = true))
        // A copy with call_policy is unchanged.
        val full = CachedPolicy.Ok(managed.copy(callPolicy = calls))
        assertEquals(full, judgeCached(full, true, null, callsManagedLast = true))
        // Corrupt for its app list, or a copy that doesn't decode: managed, a fresh policy without an app list rejected.
        for (corrupt in listOf(CachedPolicy.Corrupt(CachedPolicy.NO_ALLOWLIST), CachedPolicy.Corrupt("SerializationException"))) {
            assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(fresh, corrupt, policyEverApplied = true, callsManagedLast = true))
        }
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

    @Test
    fun `the preferences lost on a phone the witness says had a policy - the fallback, never no policy`() {
        val applied = """{"enrolled":true,"policy_applied":true,"server_url":"https://vibb.example.org"}"""
        assertEquals(
            PolicyToApply.Fallback(NOTHING_ALLOWED_FALLBACK),
            choosePolicy(null, CachedPolicy.Absent, everManaged(false, witnessPolicyApplied(applied)), lastEnforced = null),
        )
        assertEquals(PolicyToApply.Apply(null), choosePolicy(null, CachedPolicy.Absent, everManaged(false, false), null))
        // The witness file: none is a new phone, one that doesn't read is both facts, the address read back.
        assertFalse(witnessPolicyApplied(null))
        assertFalse(witnessEnrolled(null))
        assertTrue(witnessPolicyApplied(applied))
        assertTrue(witnessEnrolled(applied))
        for (broken in listOf("""{"enrolled":tr""", "")) {
            assertTrue(witnessPolicyApplied(broken))
            assertTrue(witnessEnrolled(broken))
        }
        assertEquals("https://vibb.example.org", witnessServerUrl(applied))
        assertNull(witnessServerUrl("{"))
        assertNull(witnessServerUrl(null))
    }

    /** A new phone enrolled but with no first policy yet sets up as before: no policy, Settings open. */
    @Test
    fun `a witness of the enrolment alone is no applied policy`() {
        val enrolledOnly = """{"enrolled":true,"server_url":"https://vibb.example.org"}"""
        assertTrue(witnessEnrolled(enrolledOnly))
        assertFalse(witnessPolicyApplied(enrolledOnly))
        val ever = everManaged(false, witnessPolicyApplied(enrolledOnly))
        assertEquals(PolicyToApply.Apply(null), choosePolicy(null, CachedPolicy.Absent, ever, null))
        assertEquals(
            com.kidslauncher.mdm.ui.settings.SettingsAccess.OPEN,
            com.kidslauncher.mdm.ui.settings.settingsAccess(ever, pinConfigured = false, lockedOut = false),
        )
        // The enrolment's write keeps an applied policy, and only storeAcceptedPolicy writes one.
        val main = listOf("src/main", "app/src/main").map { java.io.File(it) }.first { it.isDirectory }
        val witness = java.io.File(main, "java/com/kidslauncher/mdm/server/EnrolmentWitness.kt").readText()
        assertTrue("write(context) { it.copy(enrolled = true, serverUrl = serverUrl?.trim()) }" in witness)
        val all = java.io.File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "EnrolmentWitness.recordPolicyApplied(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("MdmSyncWorker.kt"), all)
    }

    /** Every reading of "a policy was applied" that decides what to enforce counts the witness too. */
    @Test
    fun `the sync, the cache, the offline decision, the enrolment and Settings ask the witness`() {
        val main = listOf("src/main", "app/src/main").map { java.io.File(it) }.first { it.isDirectory }
        fun src(path: String) = java.io.File(main, "java/com/kidslauncher/mdm/$path").readText()
        val sync = src("server/MdmSyncWorker.kt")
        val reads = Regex("""mdm(\(\))?\.policyEverApplied\(\)""").findAll(sync).count()
        val witnessed = Regex("""everManaged\((LauncherPreferences\.)?mdm(\(\))?\.policyEverApplied\(\), EnrolmentWitness\.policyApplied(Now\(\)|\(context\))\)""")
            .findAll(sync).count()
        assertEquals(4, witnessed)
        assertEquals("every read of the flag", reads, witnessed)
        assertTrue("EnrolmentWitness.recordPolicyApplied(context, LauncherPreferences.mdm().serverUrl())" in sync.substringAfter("private fun storeAcceptedPolicy("))
        val enrol = src("server/Enrollment.kt")
        assertTrue("everManaged(mdmBefore.policyEverApplied(), EnrolmentWitness.policyApplied(context))" in enrol)
        assertTrue("mdm.enrolled(true)\n            EnrolmentWitness.recordEnrolled(context, serverUrl)" in enrol)
        assertTrue("EnrolmentWitness.policyApplied(this)" in src("ui/settings/SettingsActivity.kt").substringAfter("private fun currentAccess()"))
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
