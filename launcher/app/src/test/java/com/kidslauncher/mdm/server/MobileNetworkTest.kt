package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The round "Mobilnett" (design 94, design 95 rev. 3): the shared contract `mobile_network.json` (byte-identical with
 * `server/testdata/`, whose `policy` the server's `tests::mobile_network::the_contract` serves from saved choices),
 * the pure rules and the single owner of always-on. */
class MobileNetworkTest {
    private val contractText = javaClass.classLoader!!.getResource("mobile_network.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun rows(name: String): List<JsonObject> = contract[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String): String? = this[key]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    private fun JsonObject.bool(key: String): Boolean? = this[key]!!.jsonPrimitive.booleanOrNull

    @Test
    fun `the served policy rows decode`() {
        val (off, phone) = rows("policy").map { ServerJson.decodeFromString(PolicyResponse.serializer(), it.toString()) }
        assertEquals(Triple("off", "on", true), Triple(off.dataRoaming, off.appsMobile, off.mobileSettingsLocked))
        assertEquals(Triple("phone", "vibb", false), Triple(phone.dataRoaming, phone.appsMobile, phone.mobileSettingsLocked))
    }

    @Test
    fun `the roaming lock and the settings lock follow every row`() {
        for (row in rows("roaming_locked")) {
            assertEquals("$row", row.bool("locked"), dataRoamingLocked(row.str("value"), row.bool("override")!!))
        }
        for (row in rows("settings_locked")) {
            assertEquals("$row", row.bool("locked"), mobileSettingsLocked(row.bool("value"), row.bool("override")!!))
        }
    }

    /** Every shape row through the same rules `apply()` uses, and always-on's lockdown for it. */
    @Test
    fun `every shape row decides the shape, the reason and lockdown`() {
        for (row in rows("shapes")) {
            val override = row.bool("override")!!
            val inputs = VpnInputs(
                filterOn = row.bool("filter")!! && !override,
                parentSink = parentSinkOn(row.str("apps_mobile"), override),
                roamingLocked = dataRoamingLocked(row.str("data_roaming"), override),
            )
            val net = BestNet(cellular = row.bool("cellular")!!, roaming = row.bool("roaming")!!)
            val shape = vpnShape(inputs, net)
            assertEquals("$row", row.str("shape"), shape.wire)
            assertEquals("$row", row.str("sink"), sinkReason(inputs, net).wire)
            assertEquals("$row", row.bool("lockdown"), alwaysOnFor(shape).lockdown)
            assertEquals("$row", shape != VpnShape.NONE, vpnConfigLocked(shape))
        }
    }

    /** Design 95 §2.2: lockdown is the sink's alone, and `apply()` never writes it off on a doubt about the network. */
    @Test
    fun `the single owner never drops the sink's lockdown on a doubt`() {
        assertEquals(listOf(true), VpnShape.entries.filter { alwaysOnFor(it).lockdown }.map { it == VpnShape.SINK })
        assertFalse(alwaysOnFor(VpnShape.NONE).ownPackage)
        val bareVibb = VpnInputs(filterOn = true, parentSink = true, roamingLocked = false)
        assertEquals(VpnShape.SINK, applyShape(bareVibb, net = null, persisted = VpnShape.SINK))
        assertEquals(VpnShape.FILTER, applyShape(bareVibb, net = null, persisted = VpnShape.FILTER))
        assertEquals(VpnShape.FILTER, applyShape(bareVibb, BestNet(cellular = false, roaming = false), VpnShape.SINK))
        // A pause (no sink reason left) ends it whatever the network.
        val lifted = VpnInputs(filterOn = false, parentSink = false, roamingLocked = false)
        assertEquals(VpnShape.NONE, applyShape(lifted, net = null, persisted = VpnShape.SINK))
    }

    @Test
    fun `the stored inputs round trip and a bad record reads as none`() {
        val inputs = VpnInputs(filterOn = true, parentSink = false, roamingLocked = true)
        assertEquals(inputs, MobileNetworkRuntime.decodeInputs(MobileNetworkRuntime.encodeInputs(inputs)))
        assertEquals(null, MobileNetworkRuntime.decodeInputs("1,0"))
        assertEquals(null, MobileNetworkRuntime.decodeInputs(null))
    }

    @Test
    fun `the relock takes a SIM reading on once per boot, never unlocked`() {
        assertEquals(setOf(2), dataRoamingRelock(true, mapOf(1 to false, 2 to true), emptySet()))
        assertEquals(emptySet<Int>(), dataRoamingRelock(true, mapOf(1 to false, 2 to true), setOf(2)))
        assertEquals(emptySet<Int>(), dataRoamingRelock(true, mapOf(1 to false, 2 to null), emptySet()))
        assertEquals(emptySet<Int>(), dataRoamingRelock(false, mapOf(2 to true), emptySet()))
    }

    @Test
    fun `the report encodes exactly the contract's`() {
        val report = contract["report"]!!.jsonObject
        val roaming = DataRoamingReport(
            locked = true, roaming = true, country = "se", network = "24001", enabled = false, dataActive = false, modems = 2,
        )
        val mobile = MobileDataReport(enabled = false, sink = SinkReason.PARENT.wire, settingsLocked = true)
        assertEquals(report["data_roaming"], ServerJson.encodeToJsonElement(DataRoamingReport.serializer(), roaming))
        assertEquals(report["mobile_data"], ServerJson.encodeToJsonElement(MobileDataReport.serializer(), mobile))
        // No home location leaves the phone; a 901 network is named even when it counts as home.
        assertEquals(Pair(null, null), roamingPlace(false, "no", "24201"))
        assertEquals(Pair(null, "901012"), roamingPlace(false, "dk", "901012"))
        assertEquals(Pair("se", "24001"), roamingPlace(true, "SE", "24001"))
        assertEquals(Pair(null, null), roamingPlace(true, "", ""))
    }

    @Test
    fun `this launcher claims both capabilities`() {
        val capabilities = contract["capabilities"]!!.jsonObject
        assertEquals(capabilities["data_roaming"]!!.jsonPrimitive.content, DATA_ROAMING_CAPABILITY)
        assertEquals(capabilities["mobile_data"]!!.jsonPrimitive.content, MOBILE_DATA_CAPABILITY)
        assertTrue(DATA_ROAMING_CAPABILITY in STATUS_CAPABILITIES)
        assertTrue(MOBILE_DATA_CAPABILITY in STATUS_CAPABILITIES)
    }

    /** Design 94 §3: a fallback keeps the parent's choices; an older record fails closed. */
    @Test
    fun `the last enforced plan keeps the choices, an older record locks`() {
        val policy = PolicyResponse(dataRoaming = null, appsMobile = "vibb", mobileSettingsLocked = false)
        val kept = LastEnforcedPlan.decode(ServerJson.encodeToString(LastEnforcedPlan.serializer(), LastEnforcedPlan.of(policy)))!!.toPolicy()
        assertEquals(Triple(null, "vibb", false), Triple(kept.dataRoaming, kept.appsMobile, kept.mobileSettingsLocked))
        val old = LastEnforcedPlan.decode("{}")!!.toPolicy()
        assertEquals(Triple("off", null, true), Triple(old.dataRoaming, old.appsMobile, old.mobileSettingsLocked))
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/mobile_network.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
