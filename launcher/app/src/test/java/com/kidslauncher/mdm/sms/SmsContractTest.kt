package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.channelModes
import com.kidslauncher.mdm.calls.forCalls
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.calls.toRules
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyMmscHosts
import com.kidslauncher.mdm.server.dto.SmsBlockedCounts
import com.kidslauncher.mdm.server.dto.SmsHeldAnswer
import com.kidslauncher.mdm.server.dto.SmsHeldBody
import com.kidslauncher.mdm.server.dto.SmsHeldRefusal
import com.kidslauncher.mdm.server.dto.SmsHoldFull
import com.kidslauncher.mdm.server.dto.SmsOwnNumber
import com.kidslauncher.mdm.server.dto.SmsState
import com.kidslauncher.mdm.server.smsRestricted
import com.kidslauncher.mdm.sms.engine.SenderClean
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 77 §7: `sms_contract.json`, byte-identical with `server/testdata/` (the server's `sms::tests` run the same
 * rows). The launcher's side: the old server's keys read by D2's table, the modes and the old-launcher view of them,
 * the policy the server sends, the exact `sms_state` and `sms-held` body, the answers, the cleaning and the names.
 */
class SmsContractTest {
    private val text = javaClass.classLoader!!.getResource("sms_contract.json")!!.readText()
    private val contract = Json.parseToJsonElement(text).jsonObject

    private fun rows(key: String) = contract[key]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.boolean

    @Test
    fun `the contract is identical to the servers copy`() {
        val serverCopy = File("../../server/testdata/sms_contract.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }

    @Test
    fun `the capability is the one the launcher claims`() {
        assertEquals(contract.str("capability"), SMS_ENGINE_CAPABILITY)
        assertTrue(SMS_ENGINE_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
    }

    @Test
    fun `an older server's switches read as D2's migration table`() {
        for (row in rows("migration")) {
            val policy = CallPolicy(managed = row.bool("calls_managed"), callsEnabled = row.bool("calls_enabled"), smsEnabled = row.bool("sms_enabled"))
            assertEquals(row.toString(), ChannelMode.of(row.str("calls_mode")) to ChannelMode.of(row.str("sms_mode")), channelModes(policy))
        }
    }

    @Test
    fun `each mode pair - the new launcher applies it, the derived old keys never loosen it`() {
        for (row in rows("modes")) {
            val calls = ChannelMode.of(row.str("calls_mode"))!!
            val sms = ChannelMode.of(row.str("sms_mode"))!!
            val wire = """{"managed":${row.bool("managed")},"calls_enabled":${row.bool("calls_enabled")},"sms_enabled":${row.bool("sms_enabled")}"""
            val new = ServerJson.decodeFromString(CallPolicy.serializer(), "$wire,\"calls_mode\":\"${calls.wire}\",\"sms_mode\":\"${sms.wire}\"}")
            assertEquals(row.toString(), calls to sms, channelModes(new))
            val state = if (calls == ChannelMode.UNMANAGED && sms == ChannelMode.UNMANAGED) CallPolicyState.Unmanaged else CallPolicyState.Managed(new.toRules())
            assertEquals(row.toString(), if (calls == ChannelMode.UNMANAGED) CallPolicyState.Unmanaged else state, state.forCalls())
            assertEquals(row.toString(), sms, state.smsMode)
            // R2-L7: what an older launcher makes of the old keys alone (D2's table) is never looser.
            val (oldCalls, oldSms) = channelModes(ServerJson.decodeFromString(CallPolicy.serializer(), "$wire}"))
            assertTrue(row.toString(), looser(oldCalls, calls).not())
            assertTrue(row.toString(), looser(oldSms, sms).not())
            // And an older launcher never filters SMS: "managed" there is DISALLOW_SMS, messages lost (as the PWA says).
            if (sms != ChannelMode.UNMANAGED) assertTrue(smsRestricted(oldSms, roleHeld = false))
        }
        // The pinned row: calls not managed, SMS managed reads as managed, calls on, SMS off.
        val row = rows("modes").single { it.str("calls_mode") == "unmanaged" && it.str("sms_mode") == "managed" }
        assertEquals(listOf(true, true, false), listOf(row.bool("managed"), row.bool("calls_enabled"), row.bool("sms_enabled")))
    }

    private fun looser(applied: ChannelMode, asked: ChannelMode): Boolean = rank(applied) > rank(asked)

    private fun rank(mode: ChannelMode) = when (mode) {
        ChannelMode.OFF -> 0
        ChannelMode.MANAGED -> 1
        ChannelMode.UNMANAGED -> 2
    }

    @Test
    fun `the policy the server sends decodes into the rules`() {
        val policy = contract["policy"]!!.jsonObject
        val callPolicy = ServerJson.decodeFromString(CallPolicy.serializer(), policy["call_policy"].toString())
        val rules = callPolicy.toRules()
        assertEquals(ChannelMode.MANAGED, rules.callsMode)
        assertEquals(ChannelMode.MANAGED, rules.smsMode)
        val (mamma, bestemor, ambulanse) = rules.contacts
        assertEquals(listOf(true, true, true), listOf(mamma.smsIn, mamma.smsOut, mamma.mms))
        // D3: independent of the calls - Bestemor calls in but may not text in; she may get texts without calls out.
        assertEquals(listOf(true, false, false, true, false), listOf(bestemor.inbound, bestemor.outbound, bestemor.smsIn, bestemor.smsOut, bestemor.mms))
        // R2-H1: the server never sends sms_in for an emergency number.
        assertFalse(ambulanse.smsIn)
        assertEquals(listOf("DNB", "Posten"), rules.smsNames)
        assertFalse(rules.smsLockText)
        assertEquals(listOf(1 to "+4790000001"), callPolicy.ownNumbers.map { it.slot to it.number })
        assertEquals(listOf("00112233445566778899aabbccddeeff"), callPolicy.smsHeldDrop)
        assertEquals(
            PolicyMmscHosts("24201", listOf("mmsc.example.invalid")),
            ServerJson.decodeFromString(PolicyMmscHosts.serializer(), policy["mmsc_hosts_row"].toString()),
        )
    }

    @Test
    fun `sms_state is encoded exactly as the contract's report`() {
        val state = SmsState(
            roleHeld = true, modeApplied = "managed", received = 12, sent = 4,
            blocked = SmsBlockedCounts(number = 0, short = 1, alphanumeric = 2, malformed = 0, empty = 0, group = 0, badLocation = 0, overCap = 1),
            held = 3, released = 1, expired = 1, parseFailures = 0, mmsWaiting = 0, mmsDataAllowed = true, mmscKnown = false,
            lastError = null, ownNumbers = listOf(SmsOwnNumber(1, "+4790000001"), SmsOwnNumber(2, null)), holdFull = SmsHoldFull(numbers = false, names = true),
        )
        assertEquals(
            contract["report"]!!.jsonObject["sms_state"],
            Json.parseToJsonElement(ServerJson.encodeToString(SmsState.serializer(), state)),
        )
    }

    @Test
    fun `the held post is encoded exactly, and the answers decode`() {
        val held = contract["held"]!!.jsonObject
        val request = held["request"]!!.jsonObject
        val body = ServerJson.decodeFromString(SmsHeldBody.serializer(), request.toString())
        assertEquals(listOf("held", "released", "expired", "dropped", "blocked"), body.rows.map { it.state })
        assertEquals(request, Json.parseToJsonElement(ServerJson.encodeToString(SmsHeldBody.serializer(), body)))
        val (taken, capped) = held["answers"]!!.jsonArray.map { ServerJson.decodeFromString(SmsHeldAnswer.serializer(), it.toString()) }
        assertEquals(SmsHeldAnswer(accepted = 5, capped = false), taken)
        assertEquals(SmsHeldAnswer(accepted = 0, capped = true), capped)
        assertEquals(SmsHeldRefusal(0, "sender"), ServerJson.decodeFromString(SmsHeldRefusal.serializer(), held["refusal"].toString()))
    }

    @Test
    fun `the sender is cleaned by its class before it leaves`() {
        for (row in rows("clean")) {
            val kind = SenderKind.valueOf(row.str("type").uppercase())
            val clean = SenderClean.of(kind, row.str("raw"), "47")
            assertEquals(row.toString(), row.str("class") to row.str("sender"), clean.senderClass.wire to clean.sender)
        }
    }

    @Test
    fun `names - a letter required, 1-11 characters, one fold on both sides`() {
        for (row in rows("names")) {
            assertEquals(row.toString(), row.bool("valid"), SmsNames.valid(row.str("name")))
            row["fold"]?.let { assertEquals(row.toString(), it.jsonPrimitive.content, SmsNames.fold(SmsNames.clean(row.str("name")))) }
        }
        for (row in rows("name_matches")) {
            assertEquals(row.toString(), row.bool("match"), SmsNames.same(row.str("listed"), row.str("sender")))
        }
    }
}
