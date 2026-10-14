package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.TIME_RULES_CAPABILITY
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.TimeState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Design 34 P13 (QA L8): the status report's `time_state.zone` - the phone's IANA zone, so the
 * server can show a rule's end ("Leggetid until 07:00") when it is Phone Setup's. The test checks
 * the **encoded JSON** (`ServerJson` leaves defaults out; a set value must be in it), not only the
 * data class.
 */
class TimeStateZoneTest {
    private fun state(zone: String?) = TimeState(
        day = "2026-10-14", usedMinutes = 42, budgetMinutes = 90, extraMinutes = 0, activeRuleId = 7,
        activeRuleName = "Leggetid", callsBlocked = false, lockReason = "BEDTIME", liftsActive = emptyList(),
        zone = zone,
    )

    private fun encoded(zone: String?): String = ServerJson.encodeToString(
        StatusReportRequest.serializer(),
        StatusReportRequest(
            lockReason = "BEDTIME", kioskEngaged = true, capabilities = listOf("call_policy_v1", TIME_RULES_CAPABILITY),
            timeState = state(zone),
        ),
    )

    /** The exact body the server's end-to-end test (`server/src/tests/devices_list.rs`,
     * `a_launcher_report_with_its_zone_shows_the_rules_end`) posts - keep the two equal. */
    @Test
    fun the_zone_is_in_the_encoded_json() {
        assertEquals(
            "{\"lock_reason\":\"BEDTIME\",\"kiosk_engaged\":true,\"capabilities\":[\"call_policy_v1\",\"time_rules_v1\"]," +
                "\"time_state\":{\"day\":\"2026-10-14\",\"used_minutes\":42,\"budget_minutes\":90,\"extra_minutes\":0," +
                "\"active_rule_id\":7,\"active_rule_name\":\"Leggetid\",\"calls_blocked\":false,\"lock_reason\":\"BEDTIME\"," +
                "\"lifts_active\":[],\"zone\":\"Europe/Oslo\"}}",
            encoded("Europe/Oslo"),
        )
        val timeState = ServerJson.parseToJsonElement(encoded("Europe/Oslo")).jsonObject["time_state"]!!.jsonObject
        assertEquals("Europe/Oslo", timeState["zone"]!!.jsonPrimitive.content)
    }

    @Test
    fun no_zone_is_left_out() {
        val timeState = ServerJson.parseToJsonElement(encoded(null)).jsonObject["time_state"]!!.jsonObject
        assertFalse(timeState.containsKey("zone"))
        // An older report's shape still decodes (the field is optional).
        val old = "{\"day\":\"2026-10-14\",\"used_minutes\":0,\"budget_minutes\":null,\"extra_minutes\":0," +
            "\"active_rule_id\":null,\"active_rule_name\":null,\"calls_blocked\":false,\"lock_reason\":\"NONE\",\"lifts_active\":[]}"
        assertNull(ServerJson.decodeFromString(TimeState.serializer(), old).zone)
    }

    @Test
    fun the_reported_zone_is_the_ids_iana_name() {
        assertEquals("Europe/Oslo", reportedZone(ZoneId.of("Europe/Oslo")))
        assertEquals("America/Argentina/Buenos_Aires", reportedZone(ZoneId.of("America/Argentina/Buenos_Aires")))
        // Whatever the machine's zone is, it is reported and is a valid id that reads back as itself.
        val here = reportedZone(ZoneId.systemDefault())
        assertNotNull(here)
        assertEquals(ZoneId.systemDefault(), ZoneId.of(here!!))
        // An offset id is sent as it is: the server shows no end for it (never Phone Setup's IANA name).
        assertEquals("Z", reportedZone(ZoneOffset.UTC))
        assertEquals("+01:00", reportedZone(ZoneOffset.ofHours(1)))
        assertEquals(64, MAX_REPORTED_ZONE_LENGTH)
    }

    /** The runtime fills it from the zone it counts the day in. */
    @Test
    fun the_runtime_reports_the_zone_it_counts_in() {
        val path = "main/java/com/kidslauncher/mdm/timerules/TimeRulesRuntime.kt"
        val source = listOf(File("src/$path"), File("app/src/$path")).first { it.exists() }.readText()
        assertTrue(source.contains("private fun zone(): ZoneId = ZoneId.systemDefault()"))
        val report = source.substringAfter("fun report(context: Context").substringBefore("\n    }\n")
        assertTrue(report, report.contains("val zone = zone()"))
        assertTrue(report, report.contains("zone = reportedZone(zone),"))
    }
}
