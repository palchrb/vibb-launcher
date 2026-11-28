package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.TimeState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Design 34 P13 (QA L8): the status report's `time_state.zone` - the phone's IANA zone, so the
 * server can show a rule's end ("Leggetid until 07:00") when it is Phone Setup's. The test checks
 * the **encoded JSON** (`ServerJson` leaves defaults out; a set value must be in it) against the
 * shared contract `time_status_report.json` (in `contracts/core/`, whose
 * end-to-end test `devices_list::a_launcher_report_with_its_zone_shows_the_rules_end` posts it).
 */
class TimeStateZoneTest {
    private val contract = javaClass.classLoader!!.getResource("time_status_report.json")!!.readText()

    private fun state(zone: String?) = TimeState(
        day = "2026-10-14", usedMinutes = 42, budgetMinutes = 90, extraMinutes = 0, activeRuleId = 7,
        activeRuleName = "Leggetid", callsBlocked = false, lockReason = "BEDTIME", liftsActive = emptyList(),
        zone = zone, clockSkewMs = 0, clockTrust = "server", unanchoredMs = 0, storageFull = true,
        // Design 139 §4.4: the usage access and the groups, minutes only.
        usageAccess = true,
        groups = listOf(
            com.kidslauncher.mdm.server.dto.GroupReport(3, 40, 15, closed = false),
            com.kidslauncher.mdm.server.dto.GroupReport(4, 60, 0, closed = true),
        ),
    )

    /** The contract's clock fields: checked by the server, at the time it reported. */
    private val clock = com.kidslauncher.mdm.server.ClockReport(0, com.kidslauncher.mdm.server.ClockTrust.SERVER, 0)

    private fun encoded(timeState: TimeState): String = ServerJson.encodeToString(
        StatusReportRequest.serializer(),
        StatusReportRequest(
            lockReason = "BEDTIME", kioskEngaged = true,
            timeState = timeState,
        ),
    )

    /** A snapshot counted in [zone] with the contract's ledger and rule. */
    private fun snapshot(zone: ZoneId): TimeRulesRuntime.Snapshot {
        val rule = TimeRule(id = 7, name = "Leggetid", kind = KIND_BEDTIME, callsAllowed = true, days = List(7) { null })
        val lock = TimeLock(reason = LockReason.BEDTIME, rules = listOf(rule), callsAllowed = true)
        val ledger = ScreenTimeLedger(day = "2026-10-14", usedMs = 42 * 60_000L)
        val policy = TimePolicy(rules = listOf(rule), dailyBudgetMinutes = List(7) { 90 })
        val groups = listOf(
            GroupState(3, "Spill", setOf("org.example.game"), 60, 40 * 60_000L + 59_000L, 15, GroupStatus.OPEN),
            GroupState(4, "Video", setOf("org.example.video"), 60, 60 * 60_000L, 0, GroupStatus.USED_UP, lifted = true),
        )
        return TimeRulesRuntime.Snapshot(
            lock, policy, ledger, budgetUse(ledger, policy), emptyList(), Clocks(0, 0, 1), zone, storageFull = true,
            groups = groups, usageAccess = true,
        )
    }

    @Test
    fun the_zone_is_in_the_encoded_json() {
        assertEquals(contract.trimEnd(), encoded(state("Europe/Oslo")))
        val timeState = ServerJson.parseToJsonElement(contract).jsonObject["time_state"]!!.jsonObject
        assertEquals("Europe/Oslo", timeState["zone"]!!.jsonPrimitive.content)
    }

    /** The storage line: `storage_full` is sent only while the ledger can't be saved. */
    @Test
    fun storage_full_is_left_out_while_false() {
        val timeState = ServerJson.parseToJsonElement(encoded(state("Europe/Oslo").copy(storageFull = false))).jsonObject["time_state"]!!.jsonObject
        assertFalse(timeState.containsKey("storage_full"))
    }

    @Test
    fun no_zone_is_left_out() {
        val timeState = ServerJson.parseToJsonElement(encoded(state(null))).jsonObject["time_state"]!!.jsonObject
        assertFalse(timeState.containsKey("zone"))
        // An older report's shape still decodes (the field is optional).
        val old = "{\"day\":\"2026-10-14\",\"used_minutes\":0,\"budget_minutes\":null,\"extra_minutes\":0," +
            "\"active_rule_id\":null,\"active_rule_name\":null,\"calls_blocked\":false,\"lock_reason\":\"NONE\",\"lifts_active\":[]}"
        assertNull(ServerJson.decodeFromString(TimeState.serializer(), old).zone)
    }

    /** QA L1: the report's zone is the snapshot's own - the one its day and rule were counted in -,
     * never a second read of the system zone. A zone that isn't this machine's tells them apart. */
    @Test
    fun the_report_carries_the_zone_the_snapshot_was_counted_in() {
        val here = ZoneId.systemDefault().id
        val other = listOf("Pacific/Chatham", "Asia/Kathmandu").first { it != here }
        assertNotEquals(here, other)
        val report = TimeRulesRuntime.timeStateOf(snapshot(ZoneId.of(other)), LockReason.BEDTIME)
        assertEquals(other, report.zone)
        assertEquals(contract.trimEnd(), encoded(TimeRulesRuntime.timeStateOf(snapshot(ZoneId.of("Europe/Oslo")), LockReason.BEDTIME, clock)))
    }

    /** The runtime reads the system zone once per snapshot and hands it down: no other read in
     * `snapshot`, `ledger` or `loadLedger` (their bodies only use the parameter). */
    @Test
    fun the_runtime_reads_the_zone_once_per_snapshot() {
        val path = "main/java/com/kidslauncher/mdm/timerules/TimeRulesRuntime.kt"
        val source = listOf(File("src/$path"), File("app/src/$path")).first { it.exists() }.readText()
        for (fn in listOf("fun snapshot(", "fun ledger(", "private fun loadLedger(", "fun timeStateOf(")) {
            val body = source.substringAfter(fn).substringAfter("{").substringBefore("\n    }\n")
            assertFalse("$fn reads the system zone again", body.contains("zone()"))
        }
        assertTrue(source.contains("zone: ZoneId = zone(),\n        calls: CallPolicyState? = null,\n    ): Snapshot"))
    }
}
