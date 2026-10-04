package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyContact
import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CallPolicyStore's decisions (refreshPlan) and the QA direct-boot fixes (#2, note 3, #8). */
class CallPolicyRefreshTest {

    private val policy = CallPolicy(
        managed = true, callsEnabled = true,
        contacts = listOf(PolicyContact(7, "Mamma", "+4791234567", inbound = true, outbound = true)),
    )
    private val managedCache = CachedPolicy.Ok(PolicyResponse(allowlist = listOf("a"), callPolicy = policy))
    private val managed = CallPolicyState.Managed(policy.toRules())
    private val managedDe = encodeBootPolicy(bootPolicyFor(managed))
    private val unmanagedDe = encodeBootPolicy(bootPolicyFor(CallPolicyState.Unmanaged))
    private val ceManaged = CeRead.Ok(managedCache, true, policy.toRules())

    private fun incoming(state: CallPolicyState, raw: String?, presentation: Boolean = true, failed: Boolean = false) =
        decideIncoming(raw, presentation, failed, state) { false }

    // --- routing (QA #8a) ---

    @Test
    fun `locked reads DE only and writes nothing`() {
        val plan = refreshPlan(ceReadable = false, ce = null, deJson = managedDe, lastCommitted = null)
        assertEquals(PolicySource.BOOT, plan.source)
        assertEquals(Verdict.ALLOW, incoming(plan.state!!, "+4791234567"))
        assertNull(plan.bootWrite)
        assertFalse(plan.repairManagedLast)
        assertEquals(CallPolicyState.UnknownFailClosed, refreshPlan(false, null, null, null).state)
    }

    @Test
    fun `unlocked reads CE and mirrors it to DE`() {
        val plan = refreshPlan(true, ceManaged, null, null)
        assertEquals(PolicySource.CE, plan.source)
        assertEquals(managed, plan.state)
        assertEquals(managedDe, plan.bootWrite)
        // Already mirrored: no write.
        assertNull(refreshPlan(true, ceManaged, managedDe, managedDe).bootWrite)
    }

    @Test
    fun `a failed CE read keeps the state and leaves DE alone`() {
        val plan = refreshPlan(true, CeRead.Failed, managedDe, managedDe)
        assertNull(plan.state)
        assertNull(plan.bootWrite)
    }

    @Test
    fun `CE wins over a stale DE copy`() {
        val stale = encodeBootPolicy(bootPolicyFor(CallPolicyState.Managed(policy.toRules().copy(callsEnabled = false))))
        val plan = refreshPlan(true, ceManaged, stale, stale)
        assertEquals(managed, plan.state)
        assertEquals(managedDe, plan.bootWrite)
    }

    // --- failed DE write is retried (QA #2) ---

    @Test
    fun `after a failed write the next refresh writes again`() {
        // The prefs' in-memory map already shows the new value, but nothing was committed (null).
        assertEquals(managedDe, refreshPlan(true, ceManaged, managedDe, lastCommitted = null).bootWrite)
    }

    // --- wiped CE (QA note 3) ---

    @Test
    fun `wiped CE with a managed DE copy fails closed, not unmanaged, and repairs CE`() {
        val wiped = CeRead.Ok(CachedPolicy.Absent, false, null)
        val plan = refreshPlan(true, wiped, managedDe, managedDe)
        assertEquals(CallPolicyState.UnknownFailClosed, plan.state)
        assertTrue(plan.repairManagedLast)
        assertTrue(plan.bootWrite!!.contains("\"fail_closed\""))
        // And a reset that keeps the policy cache but loses calls_managed_last: still managed.
        val lostFlag = refreshPlan(true, CeRead.Ok(managedCache, false, null), managedDe, managedDe)
        assertEquals(managed, lostFlag.state)
        // A fail-closed DE copy is a witness too (stays fail closed).
        val failClosedDe = encodeBootPolicy(bootPolicyFor(CallPolicyState.UnknownFailClosed))
        assertEquals(CallPolicyState.UnknownFailClosed, refreshPlan(true, wiped, failClosedDe, failClosedDe).state)
    }

    @Test
    fun `only an explicit managed false unmanages a phone the DE copy saw managed`() {
        val explicitOff = CeRead.Ok(
            CachedPolicy.Ok(PolicyResponse(allowlist = listOf("a"), callPolicy = CallPolicy(managed = false))), false, null,
        )
        val plan = refreshPlan(true, explicitOff, managedDe, managedDe)
        assertEquals(CallPolicyState.Unmanaged, plan.state)
        assertFalse(plan.repairManagedLast)
        assertEquals(unmanagedDe, plan.bootWrite)
        // Old server (no call_policy) after a reset: fail closed.
        val oldServer = CeRead.Ok(CachedPolicy.Ok(PolicyResponse(allowlist = listOf("a"))), false, null)
        assertEquals(CallPolicyState.UnknownFailClosed, refreshPlan(true, oldServer, managedDe, managedDe).state)
    }

    @Test
    fun `no witness - never-managed phones stay unmanaged`() {
        val fresh = CeRead.Ok(CachedPolicy.Absent, false, null)
        for (de in listOf(null, unmanagedDe, "garbage")) {
            val plan = refreshPlan(true, fresh, de, de)
            assertEquals(de, CallPolicyState.Unmanaged, plan.state)
            assertFalse(plan.repairManagedLast)
        }
    }

    // --- faithful copies (QA #2, #8d) ---

    @Test
    fun `a contact with an unmatchable number doesn't make the DE copy unreadable`() {
        val odd = CallPolicyState.Managed(
            policy.toRules().copy(
                contacts = policy.toRules().contacts + listOf(
                    RuleContact(8, "Empty", "", inbound = true, outbound = true),
                    RuleContact(9, "Drift", "91234500", inbound = true),
                ),
            ),
        )
        val json = encodeBootPolicy(bootPolicyFor(odd))
        assertTrue(decodeBootPolicy(json) is BootPolicyRead.Ok)
        assertTrue(bootCopyFaithful(odd, json))
        val boot = bootPolicyState(decodeBootPolicy(json))
        for (raw in listOf("+4791234567", "", "91234500", "+4791234500", null)) {
            assertEquals(raw, incoming(odd, raw), incoming(boot, raw))
        }
    }

    @Test
    fun `faithful check catches a copy that would decide differently`() {
        assertTrue(bootCopyFaithful(managed, managedDe))
        assertFalse(bootCopyFaithful(managed, null))
        assertFalse(bootCopyFaithful(managed, "garbage"))
        assertFalse(bootCopyFaithful(managed, unmanagedDe))
        assertFalse(bootCopyFaithful(CallPolicyState.Unmanaged, managedDe))
        assertTrue(bootCopyFaithful(CallPolicyState.UnknownFailClosed, "garbage"))
        val off = encodeBootPolicy(bootPolicyFor(CallPolicyState.Managed(policy.toRules().copy(callsEnabled = false))))
        assertFalse(bootCopyFaithful(managed, off))
    }

    // --- more round trips (QA #8c) ---

    @Test
    fun `round trip with short numbers, failed verification, withheld and another country`() {
        val rules = CallRules(
            callsEnabled = true, defaultCc = "46",
            contacts = listOf(
                RuleContact(1, "Farmor", "+46701234567", inbound = true, outbound = true),
                RuleContact(2, "Voicemail", "1881", inbound = true, outbound = true),
                RuleContact(3, "Norsk", "+4791234567", inbound = true),
            ),
        )
        val ce = CallPolicyState.Managed(rules)
        val json = encodeBootPolicy(bootPolicyFor(ce))
        assertTrue(bootCopyFaithful(ce, json))
        val boot = bootPolicyState(decodeBootPolicy(json))
        for (raw in listOf("0701234567", "+46701234567", "1881", "+4791234567", "91234567", "18810", null)) {
            for (presentation in listOf(true, false)) {
                for (failed in listOf(true, false)) {
                    assertEquals("$raw $presentation $failed", incoming(ce, raw, presentation, failed), incoming(boot, raw, presentation, failed))
                }
            }
            assertEquals(raw, decideOutgoing(raw, ce, false), decideOutgoing(raw, boot, false))
        }
        assertEquals(Verdict.BLOCK, incoming(boot, "+46701234567", failed = true))
        assertEquals(Verdict.ALLOW, incoming(boot, "1881"))
    }

    // --- callback window before unlock (QA #8b) ---

    @Test
    fun `the call log isn't read before the first unlock`() {
        var reads = 0
        val log = listOf(LoggedCall("112", 1_000L, 30))
        assertEquals(emptyList<LoggedCall>(), callLogForWindow(false) { reads++; log })
        assertEquals(0, reads)
        assertEquals(log, callLogForWindow(true) { reads++; log })
        assertEquals(1, reads)
        // Locked, only our own record can open the window.
        val platform: (String) -> Boolean? = { it == "112" }
        val now = 40_000L // the logged call ended at 31 s
        assertNull(callbackWindowUntil(now, callLogForWindow(false) { log }, null, platform))
        assertNotNull(callbackWindowUntil(now, callLogForWindow(false) { log }, now + CALLBACK_WINDOW_MS, platform))
        assertNotNull(callbackWindowUntil(now, callLogForWindow(true) { log }, null, platform))
    }
}
