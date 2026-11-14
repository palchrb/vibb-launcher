package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 98 §4.4: the shared contract `rule_pause.json` (byte-identical with `server/testdata/`, whose
 * `tests::rule_pause` posts `request` and produces every answer row). */
class RulePauseTest {
    private val contractText = javaClass.classLoader!!.getResource("rule_pause.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    @Test
    fun `the request encodes exactly the contract's`() {
        val request = RulePauseRequest(
            key = "0123456789abcdef0123456789abcdef", minutes = 30, startedAtMs = 1794826200000,
            rules = listOf(RulePauseRuleBody(2, 1794828000000), RulePauseRuleBody(-1, 1794828000000)),
        )
        assertEquals(contract["request"], ServerJson.encodeToJsonElement(RulePauseRequest.serializer(), request))
    }

    @Test
    fun `the served policy decodes with the phone key and copy_of`() {
        val policy = ServerJson.decodeFromJsonElement(PolicyResponse.serializer(), contract["policy"]!!)
        assertTrue(policy.rulePause)
        val time = policy.timePolicy!!
        assertEquals(listOf(2L, null), time.rules.map { it.copyOf })
        assertEquals(listOf(null, "0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef"), time.lifts.map { it.phoneKey })
        assertNull(ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").timePolicy)
        assertEquals(false, ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").rulePause)
    }

    @Test
    fun `the lengths and the capability`() {
        assertEquals(contract["lengths"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }, RULE_LIFT_MINUTES)
        assertEquals(contract["capability"]!!.jsonPrimitive.content, RULE_PAUSE_CAPABILITY)
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        val serverCopy = java.io.File("../../server/testdata/rule_pause.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
