package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 125 step 2: the shared contract `wifi_lookup/contract.json` (byte-identical with
 * `server/testdata/wifi_lookup/`, whose `integrations::wifi_lookup::tests` produces its answers): the policy decoded,
 * the request encoded exactly, every server answer and every `phone_answers` row mapped to the phone's action. */
class WifiLookupContractTest {
    private val contractText = javaClass.classLoader!!.getResource("wifi_lookup/contract.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun JsonObject.bodyText() = this["body"]?.takeIf { it != JsonNull }?.toString()

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/wifi_lookup/contract.json", "../server/testdata/wifi_lookup/contract.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    @Test
    fun `the policy rows decode, and a policy without the key is off`() {
        for ((name, row) in contract["policy"]!!.jsonObject) {
            val policy = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist": [], "wifi_lookup": $row}""")
            assertEquals(name, row.jsonObject["on"]!!.jsonPrimitive.content == "true", wifiLookupOn(policy))
        }
        assertEquals(false, wifiLookupOn(ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist": []}""")))
        assertEquals(false, wifiLookupOn(null))
    }

    @Test
    fun `the request is encoded exactly`() {
        val request = contract["request"]!!
        val bssids = request.jsonObject["bssids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(request, ServerJson.encodeToJsonElement(WifiLookupRequest.serializer(), WifiLookupRequest(bssids)))
    }

    @Test
    fun `every phone answer maps to its action`() {
        for (row in contract["phone_answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val step = appleStep(row["status"]!!.jsonPrimitive.int, row.bodyText())
            when (row["action"]!!.jsonPrimitive.content) {
                "use" -> {
                    assertTrue(case, step is AppleStep.Use)
                    assertEquals(case, row["body"]!!.jsonObject["aps"]!!.jsonArray.size, (step as AppleStep.Use).aps.size)
                }
                "off" -> assertEquals(case, AppleStep.Off, step)
                "wait" -> assertEquals(case, AppleStep.Wait(row["wait_s"]!!.jsonPrimitive.long * 1000), step)
                else -> error("an action the test doesn't know: $case")
            }
        }
    }

    @Test
    fun `every server answer is one the phone reads`() {
        for (row in contract["answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val status = row["status"]!!.jsonPrimitive.int
            val step = appleStep(status, row.bodyText())
            when (status) {
                200 -> {
                    val aps = (step as AppleStep.Use).aps
                    // Every row the phone stores: a usable BSSID, a month it reads, an accuracy in range.
                    for (ap in aps) {
                        assertEquals(case, ap.bssid, usableBssid(ap.bssid))
                        assertTrue(case, appleMonthMin(ap.month) != null && ap.accM in 1..5000)
                    }
                }
                409 -> assertEquals(case, AppleStep.Off, step)
                429 -> assertEquals(case, AppleStep.Wait(APPLE_WAIT_MS), step)
                503 -> assertEquals(case, AppleStep.Wait(row["body"]!!.jsonObject["retry_after_s"]!!.jsonPrimitive.long * 1000), step)
                400 -> assertEquals(case, AppleStep.Wait(APPLE_WAIT_MS), step)
                else -> error("a status the test doesn't know: $case")
            }
        }
    }
}
