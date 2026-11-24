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
        contacts = listOf(
            RuleContact(id = 1, name = "Mamma", number = "+4791234567", inbound = true, outbound = true),
            RuleContact(id = 2, name = "Skolen", number = "2222", inbound = true),
        ),
        ownNumbers = listOf(OwnNumberRule(slot = 0, number = "+4740000001")),
    )

    private data class Row(val number: String?, val expected: BlockedClass?, val e164: String? = null)

    /** Every form Android may store, to its class (`null`: the kid's own block, kept): a contact in any spelling, the
     * phone's own number, the emergency numbers; other numbers, short numbers, e-mail addresses and codes stay. */
    private val rows = listOf(
        Row("+4791234567", BlockedClass.CONTACT),
        Row("91234567", BlockedClass.CONTACT),
        Row("0047 912 34 567", BlockedClass.CONTACT),
        Row("912-34-567", BlockedClass.CONTACT),
        // Only Android's own E.164 spelling matches.
        Row("x", BlockedClass.CONTACT, e164 = "+4791234567"),
        Row("2222", BlockedClass.CONTACT),
        Row("40000001", BlockedClass.CONTACT),
        Row("112", BlockedClass.EMERGENCY),
        Row("113", BlockedClass.EMERGENCY),
        Row("911", BlockedClass.EMERGENCY),
        Row("+4799999999", null),
        Row("+46701234567", null, e164 = "+46701234567"),
        Row("4791234567", null),
        Row("1881", null),
        Row("1121234", null),
        Row("spam@example.com", null),
        Row("*21#", null),
        Row("", null, e164 = ""),
        Row(null, null),
    )

    @Test
    fun `each entry gets its class or stays`() {
        for (row in rows) {
            assertEquals(row.number, row.expected, blockedClass(BlockedRow(0, row.number, row.e164), rules))
        }
        // Without readable rules no number is a contact's, but the emergency numbers still are.
        assertEquals(null, blockedClass("+4791234567", null))
        assertEquals(BlockedClass.EMERGENCY, blockedClass("112", null))
        // The country's own emergency numbers by the rules' country code.
        assertEquals(null, blockedClass("113", rules.copy(defaultCc = "46")))
        assertEquals(BlockedClass.EMERGENCY, blockedClass("114", rules.copy(defaultCc = "45")))
    }

    @Test
    fun `managed only the rows that hit the rules are cleared and unmanaged none`() {
        val list = rows.mapIndexed { i, row -> BlockedRow(i.toLong() + 10, row.number, row.e164) }
        val managed = blockedNumbersPlan(list, managed = true, wasManaged = true, rules = rules)
        val hits = list.zip(rows).filter { it.second.expected != null }
        assertEquals(hits.map { it.first.id }, managed.delete.map { it.first })
        assertEquals(hits.map { it.second.expected }, managed.delete.map { it.second })
        assertEquals(rows.size - hits.size, managed.kept)
        assertEquals(false, managed.initial)
        for (was in listOf(true, false)) {
            assertEquals(
                BlockedPlan(emptyList(), kept = list.size),
                blockedNumbersPlan(list, managed = false, wasManaged = was, rules = rules),
            )
        }
        assertEquals(BlockedPlan(emptyList()), blockedNumbersPlan(emptyList(), managed = true, wasManaged = true, rules = rules))
    }

    /** A number the parent adds as a contact later is cleared by the next sweep (after that apply). */
    @Test
    fun `a block becomes a hit once its number is a contact`() {
        val list = listOf(BlockedRow(1, "98765432"))
        assertEquals(emptyList<Pair<Long, BlockedClass>>(), blockedNumbersPlan(list, true, true, rules).delete)
        val added = rules.copy(contacts = rules.contacts + RuleContact(id = 3, name = "Bestemor", number = "+4798765432"))
        assertEquals(listOf(1L to BlockedClass.CONTACT), blockedNumbersPlan(list, true, true, added).delete)
    }

    /** The first sweep after the phone becomes managed clears by the same rule - the parent's switch, not the kid's
     * doing - and is counted apart, even when the list is empty. */
    @Test
    fun `the first managed sweep is the initial one`() {
        val list = listOf(BlockedRow(1, "+4791234567"), BlockedRow(2, "1881"))
        val first = blockedNumbersPlan(list, managed = true, wasManaged = false, rules = rules)
        assertEquals(listOf(1L), first.delete.map { it.first })
        assertEquals(1, first.kept)
        assertEquals(true, first.initial)
        assertEquals(true, blockedNumbersPlan(emptyList(), managed = true, wasManaged = false, rules = rules).initial)
        assertEquals(false, blockedNumbersPlan(list, managed = true, wasManaged = true, rules = rules).initial)
        assertEquals(false, blockedNumbersPlan(list, managed = false, wasManaged = false, rules = rules).initial)
    }

    /** The gate takes Android's page only while neither channel is unmanaged; else the kid manages their own blocks. */
    @Test
    fun `the gate is on only while both channels are managed`() {
        fun managed(calls: ChannelMode, sms: ChannelMode) =
            CallPolicyState.Managed(rules.copy(callsMode = calls, smsMode = sms))
        assertEquals(true, blockedGateOn(managed(ChannelMode.MANAGED, ChannelMode.MANAGED)))
        assertEquals(true, blockedGateOn(managed(ChannelMode.MANAGED, ChannelMode.OFF)))
        assertEquals(true, blockedGateOn(managed(ChannelMode.OFF, ChannelMode.MANAGED)))
        assertEquals(false, blockedGateOn(managed(ChannelMode.MANAGED, ChannelMode.UNMANAGED)))
        assertEquals(false, blockedGateOn(managed(ChannelMode.UNMANAGED, ChannelMode.MANAGED)))
        assertEquals(false, blockedGateOn(managed(ChannelMode.UNMANAGED, ChannelMode.UNMANAGED)))
        assertEquals(false, blockedGateOn(CallPolicyState.Unmanaged))
        assertEquals(true, blockedGateOn(CallPolicyState.UnknownFailClosed))
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
        val cleared = mapOf(BlockedClass.CONTACT to 1, BlockedClass.EMERGENCY to 2)
        val read = BlockedReport(
            true, 0, cleared, java.time.Instant.parse("2026-11-23T20:15:00Z").toEpochMilli(), clearedInitial = 2, kept = 3,
        )
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
        assertTrue(runtime.contains("setGateEnabled(context, blockedGateOn(state))"))
        // The initial sweep never moves the clearing time the server's warning reads.
        val initial = runtime.substringAfter("if (plan.initial) {").substringBefore("return")
        assertTrue(initial.contains("KEY_WAS_MANAGED, true"))
        assertTrue(!initial.contains("KEY_LAST"))
    }
}
