package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyContact
import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootCallPolicyTest {

    private val mamma = RuleContact(7, "Mamma", "+4791234567", inbound = true, outbound = true, showOnHome = true,
        messageApp = "element", messageAddress = "@mamma:example.org")
    private val pappa = RuleContact(8, "Pappa", "+4798765432", inbound = true, outbound = false)
    private val pizza = RuleContact(9, "Pizza", "+4722222222", inbound = false, outbound = true)
    private val rules = CallRules(callsEnabled = true, smsEnabled = true, defaultCc = "47", contacts = listOf(mamma, pappa, pizza))
    private val managed = CallPolicyState.Managed(rules)

    private fun roundTrip(state: CallPolicyState) = bootPolicyState(decodeBootPolicy(encodeBootPolicy(bootPolicyFor(state))))

    private fun incoming(state: CallPolicyState, raw: String?, presentation: Boolean = true) =
        decideIncoming(raw, presentation, false, state) { false }

    // --- serialization / versioning ---

    @Test
    fun `encoding is the documented v1 shape with sorted numbers and nothing else`() {
        assertEquals(
            """{"v":1,"mode":"managed","calls_enabled":true,"default_cc":"47",""" +
                """"inbound":["+4791234567","+4798765432"],"outbound":["+4722222222","+4791234567"]}""",
            encodeBootPolicy(bootPolicyFor(managed)),
        )
    }

    @Test
    fun `no names, message addresses or SMS flag reach device-protected storage`() {
        val json = encodeBootPolicy(bootPolicyFor(managed))
        for (secret in listOf("Mamma", "Pappa", "mamma:example.org", "element", "sms", "home")) {
            assertFalse(secret, json.contains(secret, ignoreCase = true))
        }
    }

    @Test
    fun `round trip keeps every decision the CE rules make`() {
        val boot = roundTrip(managed) as CallPolicyState.Managed
        assertEquals(rules.inbound, boot.rules.inbound)
        assertEquals(rules.outbound, boot.rules.outbound)
        assertEquals(rules.callsEnabled, boot.rules.callsEnabled)
        for (number in listOf("+4791234567", "91234567", "98765432", "22222222", "+4733333333", null)) {
            assertEquals(number, incoming(managed, number), incoming(boot, number))
            assertEquals(number, decideOutgoing(number, managed, false), decideOutgoing(number, boot, false))
        }
    }

    @Test
    fun `unmanaged and fail-closed round trip`() {
        assertEquals(CallPolicyState.Unmanaged, roundTrip(CallPolicyState.Unmanaged))
        assertEquals(CallPolicyState.UnknownFailClosed, roundTrip(CallPolicyState.UnknownFailClosed))
    }

    @Test
    fun `calls off survives the round trip`() {
        val off = CallPolicyState.Managed(rules.copy(callsEnabled = false))
        assertEquals(Verdict.BLOCK, incoming(roundTrip(off), "+4791234567"))
    }

    @Test
    fun `another format version is not trusted`() {
        val v2 = encodeBootPolicy(bootPolicyFor(managed)).replace("\"v\":1", "\"v\":2")
        assertEquals(BootPolicyRead.UnsupportedVersion(2), decodeBootPolicy(v2))
        assertEquals(CallPolicyState.UnknownFailClosed, bootPolicyState(decodeBootPolicy(v2)))
        val noVersion = encodeBootPolicy(bootPolicyFor(managed)).replace("\"v\":1,", "")
        assertEquals(BootPolicyRead.UnsupportedVersion(null), decodeBootPolicy(noVersion))
        val stringVersion = encodeBootPolicy(bootPolicyFor(managed)).replace("\"v\":1", "\"v\":\"1\"")
        assertEquals(BootPolicyRead.UnsupportedVersion(null), decodeBootPolicy(stringVersion))
    }

    // --- fail closed when missing / corrupt ---

    @Test
    fun `missing DE copy fails closed`() {
        assertEquals(BootPolicyRead.Missing, decodeBootPolicy(null))
        assertEquals(BootPolicyRead.Missing, decodeBootPolicy(""))
        assertEquals(CallPolicyState.UnknownFailClosed, bootPolicyState(decodeBootPolicy(null)))
    }

    @Test
    fun `corrupt DE copies fail closed`() {
        val good = encodeBootPolicy(bootPolicyFor(managed))
        val corrupt = listOf(
            "{",
            "[]",
            "null",
            good.dropLast(1),
            good.replace("\"managed\"", "\"open\""),
            good.replace(",\"calls_enabled\":true", ""),
            good.replace("\"calls_enabled\":true", "\"calls_enabled\":true,\"extra\":1"),
            good.replace("\"default_cc\":\"47\"", "\"default_cc\":\"x\""),
            good.replace("+4722222222", "22222222"),
            good.replace("+4722222222", "*21*+4722222222#"),
            good.replace("\"inbound\":[", "\"inbound\":[null,"),
        )
        for (json in corrupt) {
            assertTrue(json, decodeBootPolicy(json) !is BootPolicyRead.Ok)
            assertEquals(json, CallPolicyState.UnknownFailClosed, bootPolicyState(decodeBootPolicy(json)))
        }
    }

    @Test
    fun `fail closed before unlock blocks every non-emergency incoming call, allows emergency outgoing`() {
        val state = bootPolicyState(decodeBootPolicy("garbage"))
        for (number in listOf("+4791234567", "+4733333333", null)) {
            assertEquals(Verdict.BLOCK, incoming(state, number))
        }
        assertEquals(Verdict.BLOCK, incoming(state, null, presentation = false))
        assertEquals(Verdict.BLOCK, decideOutgoing("+4791234567", state, false))
        assertEquals(Verdict.ALLOW, decideOutgoing("112", state, Emergency.isEmergencyOutgoing("112", "47") { null }))
        // The callback window after an emergency call still opens even when the DE copy is unreadable.
        assertEquals(Verdict.ALLOW, decideIncoming("+4733333333", false, false, state) { true })
    }

    @Test
    fun `managed DE copy before unlock - allowed rings, unknown and withheld are rejected`() {
        val state = roundTrip(managed)
        assertEquals(Verdict.ALLOW, incoming(state, "+4791234567"))
        assertEquals(Verdict.BLOCK, incoming(state, "+4733333333"))
        assertEquals(Verdict.BLOCK, incoming(state, "+4791234567", presentation = false))
        assertEquals(Verdict.BLOCK, incoming(state, null, presentation = false))
        assertEquals(Verdict.BLOCK, incoming(state, "+4722222222")) // outbound-only
    }

    // --- consistency with CE ---

    @Test
    fun `rewrite only when the CE-derived state changed`() {
        val written = bootPolicyRewrite(null, managed)!!
        assertNull(bootPolicyRewrite(written, managed))
        // Order of contacts and names don't matter, numbers do.
        val reordered = CallPolicyState.Managed(rules.copy(contacts = rules.contacts.reversed().map { it.copy(name = "x") }))
        assertNull(bootPolicyRewrite(written, reordered))
        val pappaOff = CallPolicyState.Managed(rules.copy(contacts = listOf(mamma, pappa.copy(inbound = false), pizza)))
        val rewritten = bootPolicyRewrite(written, pappaOff)!!
        assertEquals(Verdict.BLOCK, incoming(bootPolicyState(decodeBootPolicy(rewritten)), "+4798765432"))
        assertTrue(bootPolicyRewrite(written, CallPolicyState.Unmanaged)!!.contains("unmanaged"))
        // A corrupt/foreign DE value is always replaced.
        assertEquals(written, bootPolicyRewrite("garbage", managed))
    }

    @Test
    fun `DE mirrors what callPolicyState derives from the CE cache`() {
        val policy = CallPolicy(
            managed = true, callsEnabled = true, smsEnabled = false,
            contacts = listOf(PolicyContact(7, "Mamma", "+4791234567", inbound = true, outbound = true)),
        )
        val ce = callPolicyState(CachedPolicy.Ok(PolicyResponse(allowlist = listOf("a"), callPolicy = policy)), true, null)
        val boot = bootPolicyState(decodeBootPolicy(encodeBootPolicy(bootPolicyFor(ce))))
        assertEquals((ce as CallPolicyState.Managed).rules.inbound, (boot as CallPolicyState.Managed).rules.inbound)
        // A corrupt CE cache with last rules falls back to them - and so does DE.
        val fallback = callPolicyState(CachedPolicy.Corrupt("x"), true, ce.rules)
        assertEquals(ce.rules.inbound, (roundTrip(fallback) as CallPolicyState.Managed).rules.inbound)
        // A corrupt CE cache without last rules is fail closed - and so is DE.
        assertEquals(CallPolicyState.UnknownFailClosed, roundTrip(callPolicyState(CachedPolicy.Corrupt("x"), true, null)))
    }
}
