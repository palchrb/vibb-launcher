package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.TimeRequestAnswer
import com.kidslauncher.mdm.server.dto.TimeRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design r13 §2.4-2.5: the kid's request for more screen time - the shared contract
 * `time_request.json` (byte-identical with `server/testdata/`, whose `time_request::the_contract_answers`
 * posts its request and produces every answer row).
 */
class TimeRequestsTest {
    private val contract = javaClass.classLoader!!.getResource("time_request.json")!!.readText()
    private val parsed = Json.parseToJsonElement(contract).jsonObject

    @Test
    fun `the request encodes to exactly the contract's`() {
        val body = TimeRequestBody(id = "0123456789abcdef0123456789abcdef", askedAtMs = 1_793_034_120_000)
        val encoded = ServerJson.encodeToString(TimeRequestBody.serializer(), body)
        assertEquals(parsed.getValue("request"), Json.parseToJsonElement(encoded))
        assertEquals(parsed.getValue("request").toString(), encoded)
    }

    @Test
    fun `every answer decodes`() {
        val answers = parsed.getValue("answers").jsonArray.map {
            ServerJson.decodeFromString(TimeRequestAnswer.serializer(), it.toString())
        }
        assertEquals(
            listOf(TimeRequestAnswer(true), TimeRequestAnswer(false, "capped"), TimeRequestAnswer(false, "stale")),
            answers,
        )
        // Each row encodes back to itself (no null sent).
        for ((row, answer) in parsed.getValue("answers").jsonArray.zip(answers)) {
            assertEquals(row as JsonObject, Json.parseToJsonElement(ServerJson.encodeToString(TimeRequestAnswer.serializer(), answer)))
        }
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/time_request.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }
}
