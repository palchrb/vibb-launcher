package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallState
import com.kidslauncher.mdm.server.dto.withBlocked
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android's blocked-numbers list: [blockedNumbersPlan]'s rows, and the report's `blocked_*` keys exactly as
 * `blocked_numbers.json` (byte-identical with `server/testdata/`, whose `blocked_numbers::tests` read the same file). */
class BlockedNumbersTest {
    private val contractText = javaClass.classLoader!!.getResource("blocked_numbers.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.exists() }

    private val rules = CallRules(
        callsEnabled = true,
        defaultCc = "47",
        contacts = listOf(RuleContact(id = 1, name = "Mamma", number = "+4791234567", inbound = true, outbound = true)),
    )

    private data class Row(val number: String?, val expected: BlockedClass)

    /** Every form Android may store, to its class: a contact in any spelling, other numbers, short numbers, the rest. */
    private val rows = listOf(
        Row("+4791234567", BlockedClass.CONTACT),
        Row("91234567", BlockedClass.CONTACT),
        Row("0047 912 34 567", BlockedClass.CONTACT),
        Row("+4799999999", BlockedClass.NUMBER),
        Row("+46701234567", BlockedClass.NUMBER),
        Row("2222", BlockedClass.SHORT),
        Row("112", BlockedClass.SHORT),
        Row("spam@example.com", BlockedClass.OTHER),
        Row("*21#", BlockedClass.OTHER),
        Row("", BlockedClass.OTHER),
        Row(null, BlockedClass.OTHER),
    )

    @Test
    fun `each entry gets its class`() {
        for (row in rows) assertEquals(row.number, row.expected, blockedClass(row.number, rules))
        // Without readable rules no number is a contact's.
        assertEquals(BlockedClass.NUMBER, blockedClass("+4791234567", null))
    }

    @Test
    fun `managed every row is cleared with its class and unmanaged none`() {
        val list = rows.mapIndexed { i, row -> BlockedRow(i.toLong() + 10, row.number) }
        val managed = blockedNumbersPlan(list, managed = true, wasManaged = true, rules = rules)
        assertEquals(list.map { it.id }, managed.delete.map { it.first })
        assertEquals(rows.map { it.expected }, managed.delete.map { it.second })
        assertEquals(false, managed.initial)
        for (was in listOf(true, false)) {
            assertEquals(BlockedPlan(emptyList()), blockedNumbersPlan(list, managed = false, wasManaged = was, rules = rules))
        }
        assertEquals(BlockedPlan(emptyList()), blockedNumbersPlan(emptyList(), managed = true, wasManaged = true, rules = rules))
    }

    /** The first sweep after the phone becomes managed clears what was there before - the parent's switch, not the
     * kid's doing - and is counted apart, even when the list is empty. */
    @Test
    fun `the first managed sweep is the initial one`() {
        val list = listOf(BlockedRow(1, "+4791234567"), BlockedRow(2, "2222"))
        val first = blockedNumbersPlan(list, managed = true, wasManaged = false, rules = rules)
        assertEquals(listOf(1L, 2L), first.delete.map { it.first })
        assertEquals(true, first.initial)
        assertEquals(true, blockedNumbersPlan(emptyList(), managed = true, wasManaged = false, rules = rules).initial)
        assertEquals(false, blockedNumbersPlan(list, managed = true, wasManaged = true, rules = rules).initial)
        assertEquals(false, blockedNumbersPlan(list, managed = false, wasManaged = false, rules = rules).initial)
    }

    @Test
    fun `the classes are the contract's`() {
        assertEquals(
            contract["classes"]!!.jsonArray.map { it.jsonPrimitive.content },
            BlockedClass.entries.map { it.wire },
        )
    }

    private fun blockedKeys(state: CallState): JsonObject {
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(CallState.serializer(), state)).jsonObject
        return JsonObject(json.filterKeys { it.startsWith("blocked_") })
    }

    private val base = CallState(
        state = "managed", dialerRoleHeld = true, redirectionRoleHeld = true, screeningRoleHeld = true,
        defaultDialer = null, systemDialer = null, smsRestricted = false, outgoingRestricted = false,
        defaultSmsPackage = null, lastError = null, lastEmergencyCallAt = null, callbackWindowUntil = null,
        callLogReadable = true, bootPolicy = "ok",
    )

    @Test
    fun `the report encodes exactly the contract's keys`() {
        val cleared = mapOf(BlockedClass.CONTACT to 1, BlockedClass.NUMBER to 2, BlockedClass.SHORT to 0, BlockedClass.OTHER to 0)
        val read = BlockedReport(true, 0, cleared, java.time.Instant.parse("2026-11-23T20:15:00Z").toEpochMilli(), clearedInitial = 2)
        assertEquals(contract["call_state"], blockedKeys(base.withBlocked(read)))
        val unread = BlockedReport(false, null, BlockedClass.entries.associateWith { 0 }, null)
        assertEquals(contract["call_state_unreadable"], blockedKeys(base.withBlocked(unread)))
    }

    @Test
    fun `the copies are identical`() {
        val serverCopy = listOf("../../server/testdata/blocked_numbers.json", "../server/testdata/blocked_numbers.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    /** The gate is pinned in apply and the re-seat, and enabled only by the runtime (never left on while unmanaged). */
    @Test
    fun `the gate handles the Blocked numbers action and starts disabled`() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".calls.BlockedNumbersGate\"").substringBefore("</activity>")
        assertTrue(entry.contains("android:enabled=\"false\""))
        assertTrue(entry.contains("android.telecom.action.MANAGE_BLOCKED_NUMBERS"))
        val runtime = File(main, "java/com/kidslauncher/mdm/calls/BlockedNumbersRuntime.kt").readText()
        assertTrue(runtime.contains("setGateEnabled(context, state.managed)"))
        // The initial sweep never moves the clearing time the server's warning reads.
        val initial = runtime.substringAfter("if (plan.initial) {").substringBefore("return")
        assertTrue(initial.contains("KEY_WAS_MANAGED, true"))
        assertTrue(!initial.contains("KEY_LAST"))
    }
}
