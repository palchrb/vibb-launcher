package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyEmergencyEntry
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 73: the emergency menu. Only a real emergency number passes the call rules and the locks; any other number
 * is a phone-book call; the first emergency number is the large row; one number is today's dialog; a tap calls.
 * Nothing here dials anything - the functions are pure.
 */
class EmergencyMenuTest {

    /** The phone's check with the platform unknown (it threw): the fixed list alone. */
    private val emergency = { number: String -> Emergency.isEmergencyOutgoing(number, "47") { null } }

    private val mamma = EmergencyEntry("+4791234567", "Mamma")
    private val e112 = EmergencyEntry("112", "")
    private val e113 = EmergencyEntry("113", "Ambulanse")
    private val e110 = EmergencyEntry("110", "Brann")

    private fun managed(callsEnabled: Boolean = true, mammaOutbound: Boolean = true) = CallPolicyState.Managed(
        CallRules(
            callsEnabled = callsEnabled,
            contacts = listOf(RuleContact(id = 1, name = "Mamma", number = mamma.number, inbound = true, outbound = mammaOutbound)),
        ),
    )

    private fun rows(menu: List<EmergencyEntry>, state: CallPolicyState, busy: Boolean = false, locked: Boolean = false) =
        emergencyRows(menu, state, busy, emergency, locked)

    private fun kinds(menu: List<EmergencyEntry>, state: CallPolicyState, busy: Boolean = false, locked: Boolean = false) =
        rows(menu, state, busy, locked).associate { it.entry.number to it.kind }

    @Test
    fun `no menu is 112 alone`() {
        assertEquals(listOf(e112), emergencyMenu(null, "47", emergency))
        assertEquals(listOf(e112), emergencyMenu(emptyList(), "47", emergency))
        assertEquals(DEFAULT_EMERGENCY_MENU, emergencyMenu(null, "47", emergency))
    }

    @Test
    fun `a menu without an emergency number gets 112 first`() {
        assertEquals(listOf(e112, mamma), emergencyMenu(listOf(mamma), "47", emergency))
    }

    @Test
    fun `the parent's order is kept, bad numbers, duplicates and a seventh dropped`() {
        val sent = listOf(e113, e112, EmergencyEntry("*112#", "MMI"), EmergencyEntry("1 1 3", "Again"), e110) +
            (1..5).map { EmergencyEntry("+47900000$it$it", "N$it") }
        val menu = emergencyMenu(sent, "47", emergency)
        assertEquals(EMERGENCY_MENU_MAX, menu.size)
        assertEquals(listOf("113", "112", "110", "+4790000011", "+4790000022", "+4790000033"), menu.map { it.number })
        assertEquals("a long name is clipped", EMERGENCY_NAME_MAX, emergencyMenu(listOf(EmergencyEntry("112", "x".repeat(99))), "47", emergency)[0].name.length)
    }

    @Test
    fun `the first emergency number is the large row on top`() {
        val shown = rows(listOf(mamma, e113, e112), managed())
        assertEquals(listOf(e113, mamma, e112), shown.map { it.entry })
        assertEquals(listOf(true, false, false), shown.map { it.big })
        val default = rows(listOf(e112, e113, e110), managed())
        assertTrue("112 first and largest", default[0].big && default[0].entry == e112)
    }

    @Test
    fun `one number is today's dialog, more are the sheet`() {
        val one = emergencyMenuShape(rows(emergencyMenu(null, "47", emergency), CallPolicyState.UnknownFailClosed))
        assertTrue(one is EmergencyMenuShape.Dialog)
        assertEquals("112", (one as EmergencyMenuShape.Dialog).row.entry.number)
        assertEquals(EmergencyRowKind.EMERGENCY, one.row.kind)
        assertTrue(emergencyMenuShape(rows(listOf(e112, e113), managed())) is EmergencyMenuShape.Sheet)
    }

    @Test
    fun `only real emergency numbers pass when the rules block calls`() {
        val blocked = listOf(
            CallPolicyState.UnknownFailClosed,
            managed(callsEnabled = false),
            withTimeRule(managed(), callsBlocked = true),
        )
        for (state in blocked) {
            val k = kinds(listOf(e112, e113, e110, mamma), state)
            assertEquals("$state", EmergencyRowKind.EMERGENCY, k["112"])
            assertEquals("$state", EmergencyRowKind.EMERGENCY, k["113"])
            assertEquals("$state", EmergencyRowKind.EMERGENCY, k["110"])
            assertEquals("$state", EmergencyRowKind.BLOCKED, k["+4791234567"])
        }
        // Another call live: an emergency number still goes, Mamma waits.
        val busy = kinds(listOf(e112, mamma), managed(), busy = true)
        assertEquals(EmergencyRowKind.EMERGENCY, busy["112"])
        assertEquals(EmergencyRowKind.BLOCKED, busy["+4791234567"])
    }

    @Test
    fun `any other number follows the call rules like a phone-book call`() {
        assertEquals(EmergencyRowKind.ALLOWED, kinds(listOf(e112, mamma), managed())["+4791234567"])
        assertEquals("not in the phone book", EmergencyRowKind.BLOCKED, kinds(listOf(e112, mamma), managed(mammaOutbound = false))["+4791234567"])
        assertEquals(EmergencyRowKind.ALLOWED, kinds(listOf(e112, mamma), CallPolicyState.Unmanaged)["+4791234567"])
        // Legevakt is no emergency number: it follows the rules too.
        val legevakt = EmergencyEntry("116117", "Legevakt")
        assertEquals(EmergencyRowKind.BLOCKED, kinds(listOf(e112, legevakt), managed())["116117"])
        // 113 is Norway's; with Sweden's default it is an ordinary number.
        val swedish = emergencyRows(listOf(e112, e113), managed(), false, { Emergency.isEmergencyOutgoing(it, "46") { null } })
        assertEquals(EmergencyRowKind.BLOCKED, swedish.first { it.entry == e113 }.kind)
    }

    /** The user, 2026-11-01 ("kun ordentlige nødanrop fra låst skjerm"): while the phone is locked only real
     * emergency numbers call, on a managed and an unmanaged phone alike; the others stay, dimmed, "Unlock to call". */
    @Test
    fun `a locked phone calls only real emergency numbers`() {
        for (state in listOf(CallPolicyState.Unmanaged, managed(), CallPolicyState.UnknownFailClosed)) {
            val shown = rows(listOf(e112, e113, mamma), state, locked = true).associateBy { it.entry.number }
            assertEquals("$state", EmergencyRowKind.EMERGENCY, shown.getValue("112").kind)
            assertEquals("$state", EmergencyRowKind.EMERGENCY, shown.getValue("113").kind)
            assertEquals("$state", EmergencyRowKind.LOCKED, shown.getValue("+4791234567").kind)
            assertEquals("$state", EmergencyTap.Nothing, emergencyTap(shown.getValue("+4791234567")))
            assertEquals("$state", EmergencyTap.Emergency("113"), emergencyTap(shown.getValue("113")))
        }
    }

    @Test
    fun `an unlocked phone follows the call rules as before`() {
        assertEquals(EmergencyRowKind.ALLOWED, kinds(listOf(e112, mamma), managed(), locked = false)["+4791234567"])
        assertEquals(EmergencyRowKind.ALLOWED, kinds(listOf(e112, mamma), CallPolicyState.Unmanaged, locked = false)["+4791234567"])
        assertEquals(EmergencyRowKind.BLOCKED, kinds(listOf(e112, mamma), managed(mammaOutbound = false), locked = false)["+4791234567"])
    }

    /** Code QA M1: the rows scroll inside a capped view with Cancel below it; a long name takes two lines at most. */
    @Test
    fun `the sheet's rows scroll and Cancel stays outside`() {
        val sheet = File("src/main/res/layout/sheet_emergency.xml").readText()
        val scroll = sheet.substringAfter("<com.kidslauncher.mdm.calls.EmergencyRowsScroll").substringBefore("</com.kidslauncher.mdm.calls.EmergencyRowsScroll>")
        assertTrue("@+id/emergency_rows" in scroll)
        assertFalse("@+id/emergency_cancel" in scroll)
        assertTrue("@+id/emergency_cancel" in sheet.substringAfter("</com.kidslauncher.mdm.calls.EmergencyRowsScroll>"))
        val name = File("src/main/res/layout/item_emergency_row.xml").readText()
            .substringAfter("@+id/emergency_row_name").substringBefore("/>")
        assertTrue("android:maxLines=\"2\"" in name && "android:ellipsize=\"end\"" in name)
    }

    @Test
    fun `the platform's own emergency number passes too`() {
        val platform = { number: String -> Emergency.isEmergencyOutgoing(number, "47") { it == "999" } }
        val shown = emergencyRows(listOf(EmergencyEntry("999", "")), CallPolicyState.UnknownFailClosed, false, platform)
        assertEquals(EmergencyRowKind.EMERGENCY, shown.single().kind)
        assertEquals(listOf(EmergencyEntry("999", "")), emergencyMenu(listOf(EmergencyEntry("999", "")), "47", platform))
    }

    @Test
    fun `a tap calls at once - the emergency path, the rules' path, or nothing`() {
        val shown = rows(listOf(e112, mamma), managed()).associateBy { it.entry.number }
        assertEquals(EmergencyTap.Emergency("112"), emergencyTap(shown.getValue("112")))
        assertEquals(EmergencyTap.ByRules("+4791234567"), emergencyTap(shown.getValue("+4791234567")))
        val blocked = rows(listOf(e112, mamma), CallPolicyState.UnknownFailClosed).first { it.entry == mamma }
        assertEquals(EmergencyTap.Nothing, emergencyTap(blocked))
    }

    @Test
    fun `a row's words`() {
        val shown = rows(listOf(e112, mamma, EmergencyEntry("+4790000001", "")), managed()).map { emergencyRowText(it, "Nødnummer") }
        assertEquals(EmergencyRowText("112", "Nødnummer"), shown[0])
        assertEquals(EmergencyRowText(null, "Mamma"), shown[1])
        assertEquals(EmergencyRowText(null, "+4790000001"), shown[2])
    }

    @Test
    fun `the store reads the policy's menu, else the one kept with the rules`() {
        val policy = PolicyResponse(callPolicy = CallPolicy(emergencyMenu = listOf(PolicyEmergencyEntry("113", "Ambulanse"))))
        assertEquals(listOf(e113), storedEmergencyMenu(CachedPolicy.Ok(policy), null))
        val kept = CallRules(emergencyMenu = listOf(e110))
        assertEquals(listOf(e110), storedEmergencyMenu(CachedPolicy.Corrupt("x"), kept))
        assertEquals("an older server: none", null, storedEmergencyMenu(CachedPolicy.Ok(PolicyResponse(callPolicy = CallPolicy())), null))
        assertEquals(listOf(e113), CallPolicy(emergencyMenu = listOf(PolicyEmergencyEntry("113", "Ambulanse"))).toRules().emergencyMenu)
    }

    // The shared fixed list (design 73 §4).

    private val text = javaClass.classLoader!!.getResource("emergency_numbers.json")!!.readText()
    private val contract = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the fixed list is the contract's`() {
        val always = contract.getValue("always").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(always, Emergency.ALWAYS)
        val byCountry = contract.getValue("by_country").jsonObject
            .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content }.toSet() }
        assertEquals(byCountry, Emergency.BY_COUNTRY)
    }

    @Test
    fun `the contract's vectors`() {
        val vectors = contract.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 20)
        for (v in vectors) {
            val o = v.jsonObject
            val (input, cc) = o.getValue("input").jsonPrimitive.content to o.getValue("cc").jsonPrimitive.content
            val expected = o.getValue("emergency").jsonPrimitive.boolean
            assertEquals("$input in $cc", expected, Emergency.isEmergencyOutgoing(input, cc) { null })
        }
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        val serverCopy = File("../../server/testdata/emergency_numbers.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }

    @Test
    fun `nothing else is emergency`() {
        assertFalse(emergency("116117"))
        assertFalse(emergency("+4791234567"))
        assertNull(emergencyMenu(listOf(EmergencyEntry("", "")), "47", emergency).firstOrNull { it.number.isEmpty() })
    }
}
