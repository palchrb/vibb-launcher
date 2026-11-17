package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 108 §5.1's register, unregister and ack decisions and §5.5's origin rewrite, from `up_rules.json`. */
class UpRulesTest {
    private val rules = UpFixtures.rules

    private fun decisionText(decision: RegisterDecision): String = when (decision) {
        is RegisterDecision.Refuse -> "refuse:${decision.reason}"
        is RegisterDecision.Known -> "known:${decision.row.reg}"
        is RegisterDecision.Update -> "update:${decision.row.reg}"
        is RegisterDecision.Retry -> "retry:${decision.row.reg}"
        RegisterDecision.New -> "new"
    }

    @Test
    fun `every register row decides as written`() {
        for (case in rules["register"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["case"]!!.jsonPrimitive.content
            val extra = case["extra_rows"]?.jsonObject?.let { e ->
                val pkg = e["pkg"]!!.jsonPrimitive.content
                (0 until e["count"]!!.jsonPrimitive.long.toInt()).map { i -> UpFixtures.row("%032x".format(1000 + i), pkg) }
            }.orEmpty()
            val churn = case["churn"]?.jsonObject?.mapValues { (_, c) ->
                val at = c.jsonObject["at_ms"]!!.jsonPrimitive.long
                List(c.jsonObject["count"]!!.jsonPrimitive.long.toInt()) { at + it }
            }.orEmpty()
            val own = case["row_extra"]?.jsonArray?.map { ServerJson.decodeFromJsonElement(UpRow.serializer(), it) }.orEmpty()
            val decision = UpRules.registerDecision(
                UpFixtures.view(case["view"]!!.jsonPrimitive.content),
                case["pkg"]!!.jsonPrimitive.content,
                case["token"]!!.jsonPrimitive.content,
                UpFixtures.rows + extra + own,
                churn,
                UpFixtures.long(case["now_ms"], 10_000_000),
                UpFixtures.string(case["vapid"]),
            )
            assertEquals(name, case["decision"]!!.jsonPrimitive.content, decisionText(decision))
            assertEquals(name, UpFixtures.string(case["pending_vapid"]), (decision as? RegisterDecision.Known)?.pendingVapid)
        }
    }

    @Test
    fun `every refusal maps to its connector reason`() {
        for ((case, reason) in rules["failed_reason"]!!.jsonObject) {
            assertEquals(case, reason.jsonPrimitive.content, UpRules.failedReason(case))
        }
    }

    @Test
    fun `unregister and ack take only the owner's token`() {
        for (case in rules["unregister"]!!.jsonArray.map { it.jsonObject }) {
            val row = UpRules.unregisterDecision(case["sender"]!!.jsonPrimitive.content, case["token"]!!.jsonPrimitive.content, UpFixtures.rows)
            assertEquals(case["case"]!!.jsonPrimitive.content, UpFixtures.string(case["row"]), row?.reg)
        }
        for (case in rules["ack"]!!.jsonArray.map { it.jsonObject }) {
            val row = UpRules.ackDecision(UpFixtures.string(case["sender"]), case["token"]!!.jsonPrimitive.content, UpFixtures.rows)
            assertEquals(case["case"]!!.jsonPrimitive.content, UpFixtures.string(case["row"]), row?.reg)
        }
    }

    @Test
    fun `a changed origin rewrites every confirmed endpoint (M4)`() {
        for (case in rules["rewrite"]!!.jsonArray.map { it.jsonObject }) {
            val rewritten = UpRules.rewrite(UpFixtures.rows, case["origin"]!!.jsonPrimitive.content).associate { it.reg to it.endpoint }
            val want = case["rewritten"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(case["case"]!!.jsonPrimitive.content, want, rewritten)
        }
    }

    @Test
    fun `the phone's keys and secrets have the spec's forms`() {
        repeat(20) {
            val secret = UpRules.newSecret()
            assertTrue(secret, Regex("^[A-Za-z0-9_-]{27}$").matches(secret))
            assertTrue(Regex("^[0-9a-f]{32}$").matches(UpRules.newReg()))
            assertTrue(UpRules.endpointValid(UpRules.endpointFor("https://phones.example.com", secret), secret))
        }
        assertFalse(UpRules.endpointValid("https://phones.example.com/up/FakeSecretForTheContract001", "FakeSecretForTheContract002"))
        assertFalse(UpRules.endpointValid("http://phones.example.com/up/FakeSecretForTheContract001", "FakeSecretForTheContract001"))
    }

    @Test
    fun `the description is cut to 100 bytes on a character boundary, the token and vapid checked`() {
        val long = "@" + "ø".repeat(80) + ":example.org"
        val cut = UpRules.cutMessage(long)!!
        assertTrue(cut.toByteArray().size <= 100)
        assertTrue(long.startsWith(cut))
        assertEquals("@emma:example.org", UpRules.cutMessage(" @emma:example.org "))
        assertEquals(null, UpRules.cutMessage("  "))
        assertTrue(UpRules.validToken("a".repeat(100)))
        assertFalse(UpRules.validToken("a".repeat(101)))
        assertFalse(UpRules.validToken(""))
        assertTrue(UpRules.validVapid("B" + "A".repeat(86)))
        assertFalse(UpRules.validVapid("B" + "A".repeat(85) + "="))
        assertFalse(UpRules.validVapid("A".repeat(86)))
    }

    private fun kinds(sends: List<UpRules.UpSend>) = sends.map { it.kind }

    @Test
    fun `every liveness row pings, re-sends or buries as written (Q1, L3)`() {
        for (case in rules["liveness"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["case"]!!.jsonPrimitive.content
            val reg = case["reg"]!!.jsonPrimitive.content
            val now = case["now_ms"]!!.jsonPrimitive.long
            val row = UpFixtures.rowWith(reg, case["row"]!!.jsonObject)
            val result = UpRules.liveness(listOf(row), case["boot"]!!.jsonPrimitive.long.toInt(), now)
            assertEquals(name, UpFixtures.strings(case["send"]), kinds(result.sends))
            assertEquals(name, case["dead"]!!.jsonPrimitive.content == "true", result.dead.isNotEmpty())
            val send = result.sends.singleOrNull() ?: continue
            assertEquals(name, now, send.row.pingLastSendMs)
            assertEquals(name, send.id, send.row.pingId)
            if (send.kind == "resend") assertEquals(name, row.pingId, send.id) else assertEquals(name, now, send.row.pingSentMs)
        }
    }

    @Test
    fun `every refresh row is sent or waits as written (section 3, 3)`() {
        for (case in rules["refresh"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["case"]!!.jsonPrimitive.content
            val row = UpFixtures.rowWith(case["reg"]!!.jsonPrimitive.content, case["row"]!!.jsonObject)
            val now = case["now_ms"]!!.jsonPrimitive.long
            val refresh = case["refresh"]!!.jsonArray.map { ServerJson.decodeFromJsonElement(UpRefresh.serializer(), it) }
            val sends = UpRules.refreshes(listOf(row), refresh, now, case["in_use"]!!.jsonPrimitive.content == "true")
            assertEquals(name, case["send"]!!.jsonPrimitive.content == "true", sends.isNotEmpty())
            sends.singleOrNull()?.let { assertEquals(name, now, it.row.refreshMs) }
        }
    }

    @Test
    fun `a pass sends one NEW_ENDPOINT per row, a due ping carrying the refresh (L2)`() {
        for (case in rules["pass"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["case"]!!.jsonPrimitive.content
            val row = UpFixtures.rowWith(case["reg"]!!.jsonPrimitive.content, case["row"]!!.jsonObject)
            val now = case["now_ms"]!!.jsonPrimitive.long
            val refresh = case["refresh"]!!.jsonArray.map { ServerJson.decodeFromJsonElement(UpRefresh.serializer(), it) }
            val pass = UpRules.pass(listOf(row), refresh, case["boot"]!!.jsonPrimitive.long.toInt(), now, case["in_use"]!!.jsonPrimitive.content == "true")
            assertEquals(name, UpFixtures.strings(case["sends"]), kinds(pass.sends))
            assertEquals(name, case["dead"]!!.jsonPrimitive.content == "true", pass.dead.isNotEmpty())
            val want = case["refresh_ms"]!!.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.long
            pass.sends.singleOrNull()?.let {
                assertEquals(name, want, it.row.refreshMs)
                if (it.kind != "refresh") assertEquals(name, it.row.pingId, it.id)
            }
        }
    }
}
