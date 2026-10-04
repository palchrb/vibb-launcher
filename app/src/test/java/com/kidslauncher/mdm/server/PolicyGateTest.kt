package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
            choosePolicy(nothingAllowed, CachedPolicy.Ok(managed), policyEverApplied = true)
        )
    }

    @Test
    fun `without fresh policy the cache applies`() {
        assertEquals(
            PolicyToApply.Apply(managed),
            choosePolicy(null, CachedPolicy.Ok(managed), policyEverApplied = true)
        )
    }

    @Test
    fun `never synced applies no policy so setup works`() {
        assertEquals(
            PolicyToApply.Apply(null),
            choosePolicy(null, CachedPolicy.Absent, policyEverApplied = false)
        )
    }

    @Test
    fun `absent cache after a policy was applied keeps current state`() {
        assertEquals(
            PolicyToApply.KeepCurrentState,
            choosePolicy(null, CachedPolicy.Absent, policyEverApplied = true)
        )
    }

    @Test
    fun `corrupt cache keeps current state`() {
        assertEquals(
            PolicyToApply.KeepCurrentState,
            choosePolicy(null, CachedPolicy.Corrupt("boom"), policyEverApplied = false)
        )
        assertEquals(
            PolicyToApply.KeepCurrentState,
            choosePolicy(null, CachedPolicy.Corrupt("boom"), policyEverApplied = true)
        )
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
    ) = shouldSuspendNewPackage(pkg, decision, overrideActive, OWN, DIALER)

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
    fun `new package with no usable policy fails closed`() {
        assertTrue(suspendNew("org.example.game", PolicyToApply.KeepCurrentState))
    }

    @Test
    fun `own package, system dialer and an active override are never suspended`() {
        assertFalse(suspendNew(OWN, PolicyToApply.KeepCurrentState))
        assertFalse(suspendNew(DIALER, PolicyToApply.KeepCurrentState))
        assertFalse(suspendNew(DIALER, PolicyToApply.Apply(nothingAllowed)))
        assertFalse(suspendNew("org.example.game", PolicyToApply.KeepCurrentState, overrideActive = true))
    }

    private companion object {
        const val OWN = "com.kidslauncher.mdm"
        const val DIALER = "com.android.dialer"
    }
}
