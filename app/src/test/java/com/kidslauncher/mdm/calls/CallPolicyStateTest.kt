package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.FreshVerdict
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.decodeCached
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyContact
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.judgeFresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallPolicyStateTest {

    private val contact = PolicyContact(7, "Mamma", "+4791234567", inbound = true, outbound = true, showOnHome = true)
    private val managedCalls = CallPolicy(managed = true, callsEnabled = true, smsEnabled = false, contacts = listOf(contact))
    private val rules = managedCalls.toRules()
    private val withCalls = PolicyResponse(allowlist = listOf("a"), callPolicy = managedCalls)
    private val unmanagedCalls = PolicyResponse(allowlist = listOf("a"), callPolicy = CallPolicy(managed = false))
    private val oldServer = PolicyResponse(allowlist = listOf("a"))
    private val otherRules = CallRules(callsEnabled = true, contacts = listOf(RuleContact(1, "Old", "+4790000000", inbound = true)))

    @Test
    fun `explicit managed false is Unmanaged`() {
        assertEquals(CallPolicyState.Unmanaged, callPolicyState(CachedPolicy.Ok(unmanagedCalls), false, null))
        assertEquals(CallPolicyState.Unmanaged, callPolicyState(CachedPolicy.Ok(unmanagedCalls), true, otherRules))
    }

    @Test
    fun `no call_policy on a never-managed phone is Unmanaged`() {
        assertEquals(CallPolicyState.Unmanaged, callPolicyState(CachedPolicy.Ok(oldServer), false, null))
        assertEquals(CallPolicyState.Unmanaged, callPolicyState(CachedPolicy.Absent, false, null))
    }

    @Test
    fun `a managed call_policy gives its rules`() {
        val state = callPolicyState(CachedPolicy.Ok(withCalls), true, otherRules)
        assertEquals(CallPolicyState.Managed(rules), state)
        assertEquals(setOf("+4791234567"), (state as CallPolicyState.Managed).rules.outbound)
    }

    @Test
    fun `corrupt cache keeps the last managed rules, else fails closed`() {
        assertEquals(CallPolicyState.Managed(otherRules), callPolicyState(CachedPolicy.Corrupt("x"), true, otherRules))
        assertEquals(CallPolicyState.UnknownFailClosed, callPolicyState(CachedPolicy.Corrupt("x"), true, null))
        assertEquals(CallPolicyState.UnknownFailClosed, callPolicyState(CachedPolicy.Corrupt("x"), false, null))
        // Rules without the flag don't count: the flag is written with them, so they're stale.
        assertEquals(CallPolicyState.UnknownFailClosed, callPolicyState(CachedPolicy.Corrupt("x"), false, otherRules))
    }

    @Test
    fun `absent cache after managed calls keeps the last rules, else fails closed`() {
        assertEquals(CallPolicyState.Managed(otherRules), callPolicyState(CachedPolicy.Absent, true, otherRules))
        assertEquals(CallPolicyState.UnknownFailClosed, callPolicyState(CachedPolicy.Absent, true, null))
        assertEquals(CallPolicyState.Managed(otherRules), callPolicyState(CachedPolicy.Ok(oldServer), true, otherRules))
    }

    @Test
    fun `prefs are only written from an explicit managed value`() {
        assertNull(callPrefsUpdate(oldServer))
        assertEquals(CallPrefsUpdate(false, null), callPrefsUpdate(unmanagedCalls))
        val update = callPrefsUpdate(withCalls)!!
        assertEquals(true, update.callsManagedLast)
        val decoded = decodeCallRules(update.lastCallRules)!!
        assertEquals(rules, decoded)
        // The derived sets aren't part of equals; the fallback rules must still match numbers.
        assertEquals(setOf("+4791234567"), decoded.inbound)
        assertEquals(setOf("+4791234567"), decoded.outbound)
    }

    @Test
    fun `stored rules round trip and garbage is null`() {
        assertEquals(rules, decodeCallRules(encodeCallRules(rules)))
        assertNull(decodeCallRules("{not json"))
        assertNull(decodeCallRules(null))
        // Unknown keys from a newer launcher are ignored; missing ones deny.
        assertEquals(CallRules(), decodeCallRules("""{"future": 1}"""))
    }

    // QA 02 criterion T7: judgeFresh and the explicit managed rule.

    @Test
    fun `missing call_policy after managed calls is suspect`() {
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(oldServer, CachedPolicy.Ok(withCalls), true, callsManagedLast = true))
        // The flag alone is enough (cache corrupt or missing).
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(oldServer, CachedPolicy.Corrupt("x"), true, callsManagedLast = true))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(oldServer, CachedPolicy.Absent, true, callsManagedLast = true))
        // So is a cached managed call_policy without the flag (written by an earlier build).
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(oldServer, CachedPolicy.Ok(withCalls), true, callsManagedLast = false))
    }

    @Test
    fun `missing call_policy on a phone without managed calls is accepted`() {
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(oldServer, CachedPolicy.Ok(unmanagedCalls), true, callsManagedLast = false))
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(oldServer, CachedPolicy.Ok(oldServer), true, callsManagedLast = false))
    }

    @Test
    fun `explicit managed false is accepted after managed calls`() {
        assertEquals(FreshVerdict.ACCEPT, judgeFresh(unmanagedCalls, CachedPolicy.Ok(withCalls), true, callsManagedLast = true))
    }

    @Test
    fun `a rejected response keeps the cached managed rules in force`() {
        val cached = decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), withCalls))
        assertEquals(FreshVerdict.REJECT_SUSPECT, judgeFresh(oldServer, cached, true, callsManagedLast = true))
        // The cache isn't replaced, so the state still comes from it.
        assertEquals(CallPolicyState.Managed(rules), callPolicyState(cached, true, null))
    }
}
