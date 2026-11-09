package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.toRules
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.sms.engine.IncomingVerdict
import com.kidslauncher.mdm.sms.engine.Refusal
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.engine.SmsRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Design 77 D4/D5: the launcher-only vectors `sms_rules.json` (QA L8: the phone judges every message, the server
 * none). Each row is a message or a send against one set of contacts and names; the defaults are SMS "Styrt av Vibb",
 * the rules readable, the role ours, no callback window, no time rule.
 */
class SmsRulesTest {
    private val vectors = Json.parseToJsonElement(javaClass.classLoader!!.getResource("sms_rules.json")!!.readText()).jsonObject

    private fun judge(row: JsonObject): SmsJudge {
        val mode = row["mode"]?.jsonPrimitive?.content ?: "managed"
        val policy = ServerJson.decodeFromString(
            CallPolicy.serializer(),
            """{"calls_mode":"managed","sms_mode":"$mode","contacts":${vectors["contacts"]},"sms_names":${vectors["names"]}}""",
        )
        val rules = row["rules"]?.jsonPrimitive?.content ?: "ok"
        return SmsJudge(
            state = if (rules == "unknown") CallPolicyState.UnknownFailClosed else CallPolicyState.Managed(policy.toRules()),
            roleHeld = row["role"]?.jsonPrimitive?.boolean ?: true,
            ready = rules != "not_ready",
            timeBlocked = { row["time_blocked"]?.jsonPrimitive?.boolean ?: false },
            callbackWindowOpen = { row["window"]?.jsonPrimitive?.boolean ?: false },
            // The emulator's platform answer: nothing beyond the static set.
            platformEmergency = { false },
        )
    }

    @Test
    fun `every incoming vector`() {
        for (row in vectors["incoming"]!!.jsonArray.map { it.jsonObject }) {
            val kind = SenderKind.valueOf(row["kind"]!!.jsonPrimitive.content.uppercase())
            val verdict = when (val v = SmsRules.decideIncoming(kind, row["raw"]!!.jsonPrimitive.content, judge(row))) {
                is IncomingVerdict.Allow -> if (v.unknownSender) "allow_unknown" else "allow"
                IncomingVerdict.HoldRules -> "hold_rules"
                IncomingVerdict.HoldParent -> "hold_parent"
                IncomingVerdict.Block -> "block"
            }
            assertEquals(row["case"]!!.jsonPrimitive.content, row["verdict"]!!.jsonPrimitive.content, verdict)
        }
    }

    @Test
    fun `every send vector`() {
        for (row in vectors["send"]!!.jsonArray.map { it.jsonObject }) {
            val verdict = when (val v = SmsRules.decideSend(row["to"]!!.jsonPrimitive.content, judge(row))) {
                is SmsRules.SendVerdict.Allow -> "allow"
                is SmsRules.SendVerdict.Refused -> v.reason.name.lowercase()
            }
            assertEquals(row["case"]!!.jsonPrimitive.content, row["verdict"]!!.jsonPrimitive.content, verdict)
        }
    }

    @Test
    fun `the release matches the exact sender only - a name never releases a number (D16)`() {
        val judge = judge(JsonObject(emptyMap()))
        assertEquals(true, SmsRules.releases(SenderKind.NUMBER, "+4791234567", judge))
        assertEquals(false, SmsRules.releases(SenderKind.ALPHANUMERIC, "+4791234567", judge))
        assertEquals(true, SmsRules.releases(SenderKind.ALPHANUMERIC, "DNB", judge))
        assertEquals(false, SmsRules.releases(SenderKind.NUMBER, "DNB", judge))
        // An emergency-number contact's texts are never released.
        assertEquals(false, SmsRules.releases(SenderKind.NUMBER, "113", judge))
        // A listed "112" has no letter: never a name.
        assertEquals(false, SmsRules.releases(SenderKind.ALPHANUMERIC, "112", judge))
        assertEquals(Refusal.NOT_ALLOWED, (SmsRules.decideSend(null, judge) as SmsRules.SendVerdict.Refused).reason)
    }
}
