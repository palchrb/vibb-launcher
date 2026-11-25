package com.kidslauncher.mdm.assistant

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 98: the shared contract `airplane_mode.json` (in `contracts/core/`, whose
 * `tests::airplane_mode` posts the reports and runs `clean` and `emergency.line`). */
class AirplaneModeTest {
    private val contractText = javaClass.classLoader!!.getResource("airplane_mode.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun `the reports encode exactly the contract's`() {
        val closed = AirplanePeriod(1763150580000, AirplaneBy.KID, 1763183400000, AirplaneBy.KID)
        val report = AirplaneModeReport(
            on = false, goingOn = false, assistant = true, wifiKept = true, lastError = null,
            defaultAppsChanged = emptyList(), periods = listOf(closed),
        )
        assertEquals(contract["report"], ServerJson.encodeToJsonElement(AirplaneModeReport.serializer(), report))
        val open = AirplaneModeReport(
            on = true, goingOn = false, assistant = true, wifiKept = null, lastError = AirplaneError.TIMEOUT,
            defaultAppsChanged = listOf("call_screening", "browser"),
            periods = listOf(
                closed.copy(offBy = AirplaneBy.EMERGENCY),
                AirplanePeriod(1763190000000, AirplaneBy.OTHER, null, null),
            ),
        )
        assertEquals(contract["report_open"], ServerJson.encodeToJsonElement(AirplaneModeReport.serializer(), open))
    }

    /** §4.1: every `rows` row through [airplaneRow] and the section through `controlsSection`. */
    @Test
    fun `the kid's row and the section follow every row`() {
        for (row in contract["rows"]!!.jsonArray.map { it.jsonObject }) {
            val maskElement = row["mask"]!!
            val mask = if (maskElement is JsonNull) null else maskElement.jsonPrimitive.content.toLong()
            val role = row["role"]!!.jsonPrimitive.content.toBoolean()
            val on = row["on"]!!.jsonPrimitive.content.toBoolean()
            val bit = mask != null && mask and com.kidslauncher.mdm.server.QuickControlFeature.AIRPLANE != 0L
            val state = airplaneRow(
                bit, role, row["restricted"]!!.jsonPrimitive.content.toBoolean(), on, row["wifi_up"]!!.jsonPrimitive.content.toBoolean(),
                row.str("busy"), row.str("last_error"), row.str("error_age_ms")?.toLong(),
            )
            assertEquals(row.str("case"), row.str("state"), state.wire)
            val cached = if (mask == null) com.kidslauncher.mdm.server.CachedPolicy.Absent
            else com.kidslauncher.mdm.server.CachedPolicy.Ok(com.kidslauncher.mdm.server.dto.PolicyResponse(quickControlsMask = mask))
            val section = when (com.kidslauncher.mdm.ui.kidsettings.controlsSection(true, cached, role, on)) {
                com.kidslauncher.mdm.ui.kidsettings.ControlsSection.NoneEnabled -> "none"
                com.kidslauncher.mdm.ui.kidsettings.ControlsSection.NoPolicyYet -> "no_policy"
                is com.kidslauncher.mdm.ui.kidsettings.ControlsSection.Rows -> "rows"
                else -> "other"
            }
            assertEquals(row.str("case"), row.str("section"), section)
        }
    }

    /** §4.2 and §4.3: Home's line and the time-rule screen's sheet. */
    @Test
    fun `Home's line and the sheet follow every row`() {
        for (row in contract["home_line"]!!.jsonArray.map { it.jsonObject }) {
            val b = { k: String -> row[k]!!.jsonPrimitive.content.toBoolean() }
            assertEquals(row.toString(), row.str("line"), homeAirplaneLine(b("on"), b("restricted"), b("busy")))
        }
        for (row in contract["sheet"]!!.jsonArray.map { it.jsonObject }) {
            val b = { k: String -> row[k]!!.jsonPrimitive.content.toBoolean() }
            val sheet = ruleSheet(row.str("lock")!!, b("airplane_row"), b("pin_configured"), b("rule_pause"))
            assertEquals(row.str("case"), b("button"), sheet.button)
            assertEquals(row.str("case"), row["rows"]!!.jsonArray.map { it.jsonPrimitive.content }, sheet.rows)
        }
    }

    @Test
    fun `the bit`() {
        assertEquals(contract["bit"]!!.jsonPrimitive.content.toLong(), com.kidslauncher.mdm.server.QuickControlFeature.AIRPLANE)
        assertTrue(contract["rows"]!!.jsonArray.isNotEmpty())
    }
}
