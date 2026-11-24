package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 125 step 2: the shared contract `wifi_lookup/contract.json` (byte-identical with
 * `server/testdata/wifi_lookup/`, whose `integrations::wifi_lookup::tests` produces its answers): the rules, the policy
 * decoded, the request encoded exactly, every server answer and every `phone_answers` row mapped to the phone's action,
 * every `phone_rows` row through `storeApple`. */
class WifiLookupContractTest {
    private val contractText = javaClass.classLoader!!.getResource("wifi_lookup/contract.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject
    private val keys = PlaceKeys("test-key-not-a-secret".toByteArray())

    private fun JsonObject.bodyText() = this["body"]?.takeIf { it != JsonNull }?.toString()

    private fun minute(iso: String) = (Instant.parse(iso).epochSecond / 60).toInt()

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/wifi_lookup/contract.json", "../server/testdata/wifi_lookup/contract.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    @Test
    fun `the rules are the phone's constants`() {
        val rules = contract["rules"]!!.jsonObject
        assertEquals(rules["fresh_months"]!!.jsonPrimitive.int, APPLE_FRESH_MONTHS)
        assertEquals(rules["negative_months"]!!.jsonPrimitive.int, APPLE_NEGATIVE_MONTHS)
        assertEquals(rules["stale_grace_h"]!!.jsonPrimitive.int * 60, APPLE_STALE_GRACE_MIN)
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
                    val use = step as AppleStep.Use
                    val body = row["body"]!!.jsonObject
                    assertEquals(case, body["aps"]!!.jsonArray.size, use.aps.size)
                    assertEquals(case, body["unknown"]!!.jsonArray.map { it.jsonPrimitive.content }, use.unknown)
                    assertEquals(case, row["wait_s"]!!.jsonPrimitive.long * 1000, use.waitMs)
                }
                "off" -> assertEquals(case, AppleStep.Off, step)
                "wait" -> assertEquals(case, AppleStep.Wait(row["wait_s"]!!.jsonPrimitive.long * 1000), step)
                else -> error("an action the test doesn't know: $case")
            }
        }
        // A count past the server's rule is no row.
        val tooFresh = appleStep(200, contract["phone_answers"]!!.jsonArray.map { it.jsonObject }.single { it["case"]!!.jsonPrimitive.content == "too_fresh_200" }.bodyText()) as AppleStep.Use
        assertEquals(emptyList<PlaceRow>(), storeApple(emptyList(), tooFresh.aps, tooFresh.unknown, keys, 0))
    }

    @Test
    fun `every server answer is one the phone reads`() {
        for (row in contract["answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val status = row["status"]!!.jsonPrimitive.int
            val step = appleStep(status, row.bodyText())
            val body = row["body"]!!.jsonObject
            when (status) {
                200 -> {
                    val use = step as AppleStep.Use
                    // Only asked BSSIDs, usable; a month count the phone's rule takes; never a month.
                    val asked = row["request"]!!.jsonObject["bssids"]!!.jsonArray.map { it.jsonPrimitive.content }
                    for (ap in use.aps) {
                        assertTrue(case, ap.bssid in asked && usableBssid(ap.bssid) == ap.bssid)
                        assertTrue(case, appleUntilMin(0, ap.freshMonthsLeft) != null && ap.accM in 1..5000)
                    }
                    assertTrue(case, use.unknown.all { it in asked })
                    assertTrue(case, body["aps"]!!.jsonArray.none { "month" in it.jsonObject })
                    assertEquals(case, (body["retry_after_s"]?.jsonPrimitive?.long ?: 0) * 1000, use.waitMs)
                }
                409 -> assertEquals(case, AppleStep.Off, step)
                429 -> assertEquals(case, AppleStep.Wait(APPLE_WAIT_MS), step)
                503 -> assertEquals(case, AppleStep.Wait(body["retry_after_s"]!!.jsonPrimitive.long * 1000), step)
                400 -> assertEquals(case, AppleStep.Wait(APPLE_WAIT_MS), step)
                else -> error("a status the test doesn't know: $case")
            }
        }
    }

    // --- Design 125b: cells ---

    @Test
    fun `the cell rules are the phone's constants`() {
        val rules = contract["rules"]!!.jsonObject
        assertEquals(rules["cells_max"]!!.jsonPrimitive.int, CELLS_MAX)
        assertEquals(rules["cell_row_days"]!!.jsonPrimitive.int * 24 * 60, APPLE_CELL_ROW_MIN)
        assertEquals(rules["cell_negative_days"]!!.jsonPrimitive.int * 24 * 60, APPLE_CELL_NEGATIVE_MIN)
        // The server's fixed answer time (the gate's wait, Apple's deadline and a margin: code QA M2) and 3 s for the
        // network stay within the phone's cells timeout.
        assertTrue(rules["cell_answer_after_s"]!!.jsonPrimitive.long * 1000 + 3_000 <= APPLE_CELL_TIMEOUT_MS)
    }

    @Test
    fun `every cell form is the server's verdict`() {
        for (row in contract["cell_forms"]!!.jsonArray.map { it.jsonObject }) {
            val cell = row["cell"]!!.jsonPrimitive.content
            assertEquals(row["why"]!!.jsonPrimitive.content, row["valid"]!!.jsonPrimitive.boolean, parseCellWire(cell) != null)
        }
    }

    @Test
    fun `the cells request is encoded exactly`() {
        val request = contract["request_cells"]!!
        val cells = request.jsonObject["cells"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(request, ServerJson.encodeToJsonElement(WifiLookupCellsRequest.serializer(), WifiLookupCellsRequest(cells)))
    }

    @Test
    fun `every cell phone answer maps to its action, and every cells answer is one the phone reads`() {
        for (row in contract["cell_phone_answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val step = appleCellStep(row["status"]!!.jsonPrimitive.int, row.bodyText())
            when (row["action"]!!.jsonPrimitive.content) {
                "use" -> assertEquals(case, row["body"]!!.jsonObject["cells"]!!.jsonArray.size, (step as CellStep.Use).cells.size)
                "off" -> assertEquals(case, CellStep.Off, step)
                "wait" -> assertEquals(case, CellStep.Wait(row["wait_s"]!!.jsonPrimitive.long * 1000), step)
                else -> error("an action the test doesn't know: $case")
            }
        }
        for (row in contract["cell_answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val status = row["status"]!!.jsonPrimitive.int
            val step = appleCellStep(status, row.bodyText())
            val body = row["body"]!!.jsonObject
            when (status) {
                200 -> {
                    val use = step as CellStep.Use
                    val asked = row["request"]!!.jsonObject["cells"]!!.jsonArray.map { it.jsonPrimitive.content }
                    // Every asked cell in one list, each an asked string; no age.
                    assertEquals(case, asked.toSet(), (use.cells.map { it.cell } + use.unknown).toSet())
                    assertTrue(case, body["cells"]!!.jsonArray.none { "month" in it.jsonObject || "age" in it.jsonObject })
                }
                409 -> assertEquals(case, CellStep.Off, step)
                429 -> assertEquals(case, CellStep.Wait(body["retry_after_s"]?.jsonPrimitive?.long?.times(1000) ?: APPLE_WAIT_MS), step)
                503 -> assertEquals(case, CellStep.Wait(body["retry_after_s"]!!.jsonPrimitive.long * 1000), step)
                400 -> assertEquals(case, CellStep.Wait(APPLE_WAIT_MS), step)
                else -> error("a status the test doesn't know: $case")
            }
        }
    }

    @Test
    fun `every cell phone row is what storeAppleCells keeps, until when`() {
        for (row in contract["cell_phone_rows"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val now = minute(row["now"]!!.jsonPrimitive.content)
            val answer = ServerJson.decodeFromString(CellLookupAnswer.serializer(), JsonObject(mapOf("cells" to row["cells"]!!, "unknown" to row["unknown"]!!)).toString())
            val stored = storeAppleCells(emptyList(), answer.cells, answer.unknown, keys, now)
            val want = row["rows"]!!.jsonArray.map { it.jsonObject }
            assertEquals(case, want.size, stored.size)
            for (w in want) {
                val got = stored.single { it.key == keys.cell(parseCellWire(w["cell"]!!.jsonPrimitive.content)!!.id) }
                val until = minute(w["until"]!!.jsonPrimitive.content)
                if (w["negative"]?.jsonPrimitive?.boolean == true) {
                    assertEquals(case, until, got.blockedUntilMin)
                } else {
                    assertEquals(case, until, got.lastTeachMin)
                    assertTrue(case, got.appleCellLive(now) && !got.appleCellLive(until))
                    w["range_m"]?.let { assertEquals(case, it.jsonPrimitive.int.toFloat(), got.rangeM) }
                }
            }
        }
    }

    @Test
    fun `every phone row is what storeApple keeps, until when`() {
        for (row in contract["phone_rows"]!!.jsonArray.map { it.jsonObject }) {
            val case = row["case"]!!.jsonPrimitive.content
            val now = minute(row["now"]!!.jsonPrimitive.content)
            val answer = ServerJson.decodeFromString(WifiLookupAnswer.serializer(), JsonObject(mapOf("aps" to row["aps"]!!, "unknown" to row["unknown"]!!)).toString())
            val stored = storeApple(emptyList(), answer.aps, answer.unknown, keys, now)
            val want = row["rows"]!!.jsonArray.map { it.jsonObject }
            assertEquals(case, want.size, stored.size)
            for (w in want) {
                val got = stored.single { it.key == keys.ap(w["bssid"]!!.jsonPrimitive.content) }
                val until = minute(w["until"]!!.jsonPrimitive.content)
                if (w["negative"]?.jsonPrimitive?.boolean == true) {
                    assertEquals(case, until, got.blockedUntilMin)
                    assertTrue(case, !got.appleLive(now) && got.blocked(now) && !got.blocked(until))
                } else {
                    assertEquals(case, until, got.lastTeachMin)
                    assertTrue(case, got.appleLive(now) && got.appleLive(until - 1) && !got.appleLive(until))
                }
            }
        }
    }
}
