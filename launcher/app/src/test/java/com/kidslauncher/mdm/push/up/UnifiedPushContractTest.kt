package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.UP_EVENT
import com.kidslauncher.mdm.server.decodeCached
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Design 108: the shared contract `unified_push.json` (byte-identical with `server/testdata/`, whose
 * `tests::unified_push` produces its rows): the bodies encoded exactly, every answer and policy row decoded, each
 * refusal mapped to its `failed_reason`. */
class UnifiedPushContractTest {
    private val contractText = javaClass.classLoader!!.getResource("unified_push.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/unified_push.json", "../server/testdata/unified_push.json").map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    @Test
    fun `the capability is claimed`() {
        assertEquals(contract["capability"]!!.jsonPrimitive.content, UNIFIED_PUSH_CAPABILITY)
        assertTrue(UNIFIED_PUSH_CAPABILITY in STATUS_CAPABILITIES)
    }

    @Test
    fun `every policy row decodes, and on follows the switch and the origin`() {
        for ((name, row) in contract["policy"]!!.jsonObject) {
            val policy = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist": [], "unified_push": $row}""")
            val up = policy.unifiedPush!!
            assertEquals(name, setOf("on", "origin", "regs", "stopped", "refresh"), row.jsonObject.keys)
            val refresh = row.jsonObject["refresh"]!!.jsonArray.map { it.jsonObject["reg"]!!.jsonPrimitive.content to it.jsonObject["why"]!!.jsonPrimitive.content }
            assertEquals(name, refresh, up.refresh.map { it.reg to it.why })
            assertEquals(name, refresh, UpView.of(policy).refresh.map { it.reg to it.why })
            assertTrue(name, refresh.all { it.first in up.regs })
            assertEquals(name, row.jsonObject["on"]!!.jsonPrimitive.content == "true", up.on)
            assertEquals(name, row.jsonObject["regs"]!!.jsonArray.map { it.jsonPrimitive.content }, up.regs)
            assertEquals(name, row.jsonObject["stopped"]!!.jsonArray.map { it.jsonPrimitive.content }, up.stopped)
            assertEquals(name, row.jsonObject["on"]!!.jsonPrimitive.content == "true", UpView.of(policy).on)
            assertEquals(name, row.jsonObject["origin"]!!.takeIf { it != JsonNull }?.jsonPrimitive?.content, UpView.of(policy).origin)
        }
        // The stopped row: the app may not register, the other may.
        val stopped = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist": null, "unified_push": ${contract["policy"]!!.jsonObject["stopped"]}}""")
        assertFalse(UpView.of(stopped).mayRegister("org.unifiedpush.example"))
        assertTrue(UpView.of(stopped).mayRegister("io.element.android.x"))
        // An older server: no key, so UP is off and the receiver disabled.
        val older = contract["older_server"]!!.jsonObject
        val cached = (decodeCached(older["policy"].toString()) as CachedPolicy.Ok).policy
        assertEquals(older["on"]!!.jsonPrimitive.content == "true", UpView.of(cached).on)
        assertEquals(null, cached.unifiedPush)
    }

    @Test
    fun `the register bodies encode exactly, every answer decodes, every refusal maps to its reason`() {
        val register = contract["register"]!!.jsonObject
        assertEquals("api/devices/up/registrations", register["path"]!!.jsonPrimitive.content.removePrefix("/"))
        for (key in listOf("body", "body_second", "body_vapid", "body_vapid_changed")) {
            val body = register[key]!!.jsonObject
            val ours = UpRegisterBody(
                body["reg"]!!.jsonPrimitive.content,
                body["secret"]!!.jsonPrimitive.content,
                body["package"]!!.jsonPrimitive.content,
                body["vapid"]?.jsonPrimitive?.content,
            )
            body["vapid"]?.let { assertTrue(key, UpRules.validVapid(it.jsonPrimitive.content)) }
            assertEquals(key, body, ServerJson.encodeToJsonElement(UpRegisterBody.serializer(), ours))
        }
        val reasons = register["failed_reason"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val secret = register["body"]!!.jsonObject["secret"]!!.jsonPrimitive.content
        for (answer in register["answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = answer["case"]!!.jsonPrimitive.content
            val status = answer["status"]!!.jsonPrimitive.int
            val body = answer["body"]!!
            when (status) {
                200 -> {
                    val endpoint = ServerJson.decodeFromJsonElement(UpEndpointAnswer.serializer(), body).endpoint
                    val own = if ("vapid" in case) register["body_vapid"]!!.jsonObject["secret"]!!.jsonPrimitive.content else secret
                    assertTrue(case, UpRules.endpointValid(endpoint, own))
                }
                409 -> {
                    val refused = ServerJson.decodeFromJsonElement(UpRefusalAnswer.serializer(), body).refused
                    assertEquals(case, case, refused)
                    assertEquals(case, reasons.getValue(case), UpRules.failedReason(refused))
                }
                else -> {
                    assertEquals(case, 400, status)
                    assertEquals(case, JsonNull, body)
                    val reason = if (case == "vapid_not_a_point") "invalid" else case
                    assertEquals(case, reasons.getValue(reason), UpRules.failedReason(reason))
                }
            }
        }
        for ((case, reason) in reasons) assertEquals(case, reason, UpRules.failedReason(case))
        assertEquals(UpSpec.REASON_ACTION_REQUIRED, reasons.getValue("conflict"))
        assertEquals(UpSpec.REASON_NETWORK, reasons.getValue("network"))
    }

    @Test
    fun `the messages body encodes exactly and its answer decodes to the bytes`() {
        val messages = contract["messages"]!!.jsonObject
        assertEquals("api/devices/up/messages", messages["path"]!!.jsonPrimitive.content.removePrefix("/"))
        val body = messages["body"]!!.jsonObject
        val acks = body["ack"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(body, ServerJson.encodeToJsonElement(UpAckBody.serializer(), UpAckBody(acks)))
        val answer = ServerJson.decodeFromJsonElement(UpMessagesAnswer.serializer(), messages["answer"]!!)
        assertEquals(messages["answer"]!!.jsonObject["more"]!!.jsonPrimitive.content == "true", answer.more)
        assertEquals(2, answer.messages.size)
        val matrix = String(Base64.getDecoder().decode(answer.messages[0].body))
        val minimised = ServerJson.parseToJsonElement(matrix).jsonObject["notification"]!!.jsonObject
        assertTrue(minimised.keys.containsAll(listOf("event_id", "room_id", "counts", "prio")))
        assertFalse(minimised.keys.any { it in listOf("content", "sender_display_name", "room_name", "room_alias") })
        assertEquals("high", answer.messages[0].urgency)
        assertEquals((0..7).map { it.toByte() }, Base64.getDecoder().decode(answer.messages[1].body).toList())
        // Each answer row plans against a phone that holds both registrations.
        val rows = listOf(UpFixtures.row(answer.messages[0].reg, "io.element.android.x"), UpFixtures.row(answer.messages[1].reg, "org.unifiedpush.example"))
        for (message in answer.messages) assertTrue(UpDelivery.plan(message, rows) { UpAppState.INSTALLED } is UpPlan.Deliver)
    }

    @Test
    fun `the unregister path, the event and the endpoint's form are the launcher's`() {
        val unregister = contract["unregister"]!!.jsonObject
        assertEquals("/api/devices/up/registrations/c0ffee00000000000000000000000001", unregister["path"]!!.jsonPrimitive.content)
        assertEquals(204, unregister["status"]!!.jsonPrimitive.int)
        val event = contract["event"]!!.jsonObject
        assertEquals(UP_EVENT, event["name"]!!.jsonPrimitive.content)
        assertEquals("up", event["data"]!!.jsonPrimitive.content)
        val pattern = Regex(contract["endpoint"]!!.jsonObject["pattern"]!!.jsonPrimitive.content)
        val secret = UpRules.newSecret()
        val endpoint = UpRules.endpointFor("https://phones.example.com", secret)
        assertTrue(pattern.matches(endpoint))
        assertTrue(UpRules.endpointValid(endpoint, secret))
        assertFalse(UpRules.endpointValid("https://phones.example.com/up/$secret/x", secret))
    }
}
