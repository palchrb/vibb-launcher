package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.GroupReport
import com.kidslauncher.mdm.server.dto.TimeState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Design 139 §5.2: the shared contract `app_groups.json` (in `contracts/core/`; the server's `tests::app_groups`
 * serves its `policy` and keeps its `report_kept`): the launcher decodes the policy's groups and a group lift, and
 * sends the report's `usage_access` and `groups` in that shape.
 */
class AppGroupsContractTest {
    private val contract = ServerJson.parseToJsonElement(
        javaClass.classLoader!!.getResource("app_groups.json")!!.readText(),
    ).jsonObject

    @Test
    fun the_policy_decodes() {
        val policy = ServerJson.decodeFromJsonElement(TimePolicy.serializer(), contract.getValue("policy"))
        assertEquals(
            listOf(AppGroup(1, "Spill", listOf("org.example.game", "org.example.puzzle"), listOf(60, 60, 60, 60, 60, 120, 120))),
            policy.groups,
        )
        val lift = policy.lifts.single()
        assertEquals(TARGET_GROUP, lift.target)
        assertEquals(1L, lift.groupId)
        assertEquals(15, lift.minutes)
        assertEquals("Mamma", lift.by)
        assertEquals(1793034600000L, lift.atMs)
        assertEquals(true, groupsLimit(policy))
    }

    @Test
    fun the_report_is_the_contracts() {
        val state = TimeState(
            day = "2026-11-27", usedMinutes = 0, budgetMinutes = null, extraMinutes = 0, activeRuleId = null,
            activeRuleName = null, callsBlocked = false, lockReason = "NONE", liftsActive = emptyList(),
            usageAccess = true, groups = listOf(GroupReport(1, 40, 15, closed = false)),
        )
        val sent = ServerJson.encodeToJsonElement(TimeState.serializer(), state).jsonObject
        val report = contract.getValue("report").jsonObject
        assertEquals(report, JsonObject(sent.filterKeys { it in report.keys }))
        // No groups and an unreadable access: both keys left out.
        val none = ServerJson.encodeToJsonElement(TimeState.serializer(), state.copy(usageAccess = null, groups = emptyList())).jsonObject
        assertFalse(none.containsKey("usage_access"))
        assertFalse(none.containsKey("groups"))
    }
}
