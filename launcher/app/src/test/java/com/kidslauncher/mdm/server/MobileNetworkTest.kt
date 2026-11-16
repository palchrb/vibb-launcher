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

    /** A key the older rows don't carry reads as `null`. */
    private fun JsonObject.opt(key: String): String? = if (containsKey(key)) str(key) else null

    @Test
    fun `the served policy rows decode`() {
        val (fresh, home, off) = rows("policy").map { ServerJson.decodeFromString(PolicyResponse.serializer(), it.toString()) }
        // Design 97 D1: a new phone is Bare Vibb abroad, the derived roaming "off" for an older launcher.
        assertEquals(AbroadChoice.VIBB, abroadChoice(fresh.abroad, fresh.dataRoaming))
        assertEquals(Triple("off", "on", true), Triple(fresh.dataRoaming, fresh.appsMobile, fresh.mobileSettingsLocked))
        assertEquals(AbroadChoice.HOME, abroadChoice(home.abroad, home.dataRoaming))
        assertEquals(Triple("phone", "vibb", false), Triple(home.dataRoaming, home.appsMobile, home.mobileSettingsLocked))
        assertEquals(AbroadChoice.OFF, abroadChoice(off.abroad, off.dataRoaming))
    }

    @Test
    fun `the choice reads every row and fails closed`() {
        for (row in rows("abroad_choice")) {
            assertEquals("$row", row.str("choice"), abroadChoice(row.str("abroad"), row.str("data_roaming")).wire)
        }
    }

    @Test
    fun `where the phone is follows every row`() {
        for (row in rows("where")) {
            val where = whereOf(
                airplane = row.bool("airplane")!!,
                roaming = row.bool("roaming")!!,
                network = row.str("network"),
                simOperator = row.str("sim_operator"),
                netCc = row.str("net_cc"),
                simCc = row.str("sim_cc"),
                inService = if (row.containsKey("in_service")) row.bool("in_service") else null,
            )
            assertEquals("$row", row.str("where"), where.wire)
        }
    }

    @Test
    fun `the kid's row follows every row`() {
        for (row in rows("kid_row")) {
            val choice = AbroadChoice.entries.first { it.wire == row.str("choice") }
            val where = Where.entries.first { it.wire == row.str("where") }
            val shown = kidMobileRow(
                shown = row.bool("shown")!!,
                kidOff = row.str("kid") == "off",
                lift = row.bool("lift")!!,
                parentVibb = row.bool("parent_vibb")!!,
                where = where,
                choice = choice,
                dataEnabled = row.bool("data_enabled"),
            )
            assertEquals("$row", row.str("row"), shown.wire)
        }
    }

    @Test
    fun `the roaming lock and the settings lock follow every row`() {
        for (row in rows("roaming_locked")) {
            val choice = abroadChoice(null, row.str("value"))
            assertEquals("$row", row.bool("locked"), roamingLocked(choice, row.bool("override")!!))
        }
        for (row in rows("settings_locked")) {
            assertEquals("$row", row.bool("locked"), mobileSettingsLocked(row.bool("value"), row.bool("override")!!))
        }
    }

    /** Every shape row through the same rules `apply()` uses, and always-on's lockdown for it. The rows from before
     * design 97 carry no `abroad`, `where` or `kid`: an older server's rows, read through [abroadChoice]. A row with
     * `cellular: null` is a moment with no network seen, with the shape last decided as `persisted`. */
    @Test
    fun `every shape row decides the shape, the reason and lockdown`() {
        for (row in rows("shapes")) {
            val override = row.bool("override")!!
            val choice = abroadChoice(row.opt("abroad"), row.str("data_roaming"))
            val inputs = VpnInputs(
                filterOn = row.bool("filter")!! && !override,
                parentSink = parentSinkOn(row.str("apps_mobile"), override),
                roamingLocked = roamingLocked(choice, override),
                abroadArmed = abroadArmed(choice, override),
                kidOff = row.opt("kid") == "off",
            )
            val net = row.bool("cellular")?.let {
                BestNet(
                    cellular = it,
                    roaming = row.bool("roaming")!!,
                    abroad = row.opt("where") == "abroad",
                    unsure = it && row.opt("where") == "unknown",
                )
            }
            val persisted = VpnShape.of(row.opt("persisted"))
            val shape = applyShape(inputs, net, persisted)
            if (net != null && persisted == null) assertEquals("$row", vpnShape(inputs, net), shape)
            assertEquals("$row", row.str("shape"), shape.wire)
            assertEquals("$row", row.str("sink"), reportedSink(inputs, net, persisted).wire)
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

    /** Code QA H1/T1: the service decides with the same rule - a start with no network yet (a boot, airplane mode)
     * keeps the recorded sink, and its watcher's last network wins over a fresh read. */
    @Test
    fun `the service's start keeps the recorded sink when it sees no network`() {
        val bareVibb = VpnInputs(filterOn = true, parentSink = true, roamingLocked = true)
        assertEquals(VpnShape.SINK, serviceShape(bareVibb, watched = null, live = null, persisted = VpnShape.SINK))
        val wifi = BestNet(cellular = false, roaming = false)
        val cell = BestNet(cellular = true, roaming = false)
        assertEquals(VpnShape.FILTER, serviceShape(bareVibb, watched = wifi, live = cell, persisted = VpnShape.SINK))
        assertEquals(VpnShape.SINK, serviceShape(bareVibb, watched = null, live = cell, persisted = VpnShape.FILTER))
        // The roaming reason alone with the filter off (code QA M2): the pass shape keeps the watcher, not NONE.
        val roamingOnly = VpnInputs(filterOn = false, parentSink = false, roamingLocked = true)
        assertEquals(VpnShape.PASS, serviceShape(roamingOnly, watched = wifi, live = wifi, persisted = VpnShape.SINK))
    }

    /** Code QA M1/T2: a switch to the sink writes lockdown after the tunnel, whether or not it came up, and retries a
     * failure; a switch away writes lockdown off first. */
    @Test
    fun `a failed switch to the sink fails closed and retries`() {
        assertEquals(null, alwaysOnBeforeEstablish(VpnShape.SINK))
        assertEquals(VpnShape.SINK, alwaysOnAfterEstablish(VpnShape.SINK))
        assertTrue(alwaysOnFor(alwaysOnAfterEstablish(VpnShape.SINK)!!).lockdown)
        assertTrue(retriesFailedEstablish(VpnShape.SINK, established = false))
        assertFalse(retriesFailedEstablish(VpnShape.SINK, established = true))
        for (shape in listOf(VpnShape.FILTER, VpnShape.PASS)) {
            assertEquals(shape, alwaysOnBeforeEstablish(shape))
            assertEquals(null, alwaysOnAfterEstablish(shape))
            assertFalse(retriesFailedEstablish(shape, established = false))
        }
    }

    /** Code QA L7: with no network seen the held sink still names its reason. */
    @Test
    fun `the report names the held sink`() {
        val roaming = VpnInputs(filterOn = true, parentSink = false, roamingLocked = true)
        assertEquals(SinkReason.ROAMING, reportedSink(roaming, net = null, persisted = VpnShape.SINK))
        assertEquals(SinkReason.OFF, reportedSink(roaming, net = null, persisted = VpnShape.FILTER))
        assertEquals(SinkReason.PARENT, reportedSink(roaming.copy(parentSink = true), net = null, persisted = VpnShape.SINK))
    }

    /** Design 97 code QA L1: a kid's Off at home under Bare Vibb (the abroad sink armed) stays `kid` through a tunnel,
     * while that reason is still configured; a reason no longer configured falls to the order. */
    @Test
    fun `a held sink keeps naming the reason it was decided for`() {
        val home = VpnInputs(filterOn = true, parentSink = false, roamingLocked = false, abroadArmed = true, kidOff = true)
        val cell = BestNet(cellular = true, roaming = false)
        assertEquals(SinkReason.KID, sinkReasonToHold(home, cell, VpnShape.SINK))
        assertEquals(null, sinkReasonToHold(home, cell.copy(unsure = true), VpnShape.SINK))
        assertEquals(null, sinkReasonToHold(home, null, VpnShape.SINK))
        assertEquals(null, sinkReasonToHold(home.copy(kidOff = false), cell, VpnShape.FILTER))
        assertEquals(SinkReason.KID, reportedSink(home, net = null, persisted = VpnShape.SINK, held = SinkReason.KID))
        assertEquals(SinkReason.KID, reportedSink(home, cell.copy(unsure = true), VpnShape.SINK, held = SinkReason.KID))
        assertEquals(SinkReason.ABROAD, reportedSink(home, net = null, persisted = VpnShape.SINK))
        assertEquals(SinkReason.ABROAD, reportedSink(home.copy(kidOff = false), net = null, persisted = VpnShape.SINK, held = SinkReason.KID))
        // A network seen and placed decides by itself.
        assertEquals(SinkReason.KID, reportedSink(home, cell, VpnShape.SINK, held = SinkReason.ABROAD))
    }

    /** Design 97 code QA L8: a roaming network that can't be placed sinks while the abroad sink is armed. */
    @Test
    fun `an unplaced roaming network counts as abroad while armed`() {
        val bareVibb = VpnInputs(filterOn = true, parentSink = false, roamingLocked = false, abroadArmed = true)
        val unplaced = BestNet(cellular = true, roaming = true, unsure = true)
        assertEquals(SinkReason.ABROAD, sinkReason(bareVibb, unplaced))
        assertEquals(SinkReason.OFF, sinkReason(bareVibb, unplaced.copy(roaming = false)))
        assertEquals(SinkReason.OFF, sinkReason(bareVibb.copy(abroadArmed = false), unplaced))
    }

    /** Design 97 code QA L7: telephony is read again for a capability change, else at most every 10 s. */
    @Test
    fun `the watcher reads telephony on a change and at most every ten seconds`() {
        assertTrue(readsTelephony(sameCaps = false, sinceLastReadMs = 0))
        assertFalse(readsTelephony(sameCaps = true, sinceLastReadMs = TELEPHONY_REREAD_MS - 1))
        assertTrue(readsTelephony(sameCaps = true, sinceLastReadMs = TELEPHONY_REREAD_MS))
        assertTrue(readsTelephony(sameCaps = true, sinceLastReadMs = -1))
    }

    @Test
    fun `the stored inputs round trip and a bad record reads as none`() {
        val inputs = VpnInputs(filterOn = true, parentSink = false, roamingLocked = true)
        assertEquals(inputs, MobileNetworkRuntime.decodeInputs(MobileNetworkRuntime.encodeInputs(inputs)))
        assertEquals(null, MobileNetworkRuntime.decodeInputs("1,0"))
        assertEquals(null, MobileNetworkRuntime.decodeInputs(null))
    }

    /** Design 97 (Q97-M3): rc.1's record stays exactly three fields, so an rc.1 launcher after a rollback reads what it
     * knows - code QA L2: its third field rc.1's own reading of the derived `data_roaming`, so under Bare Vibb abroad
     * rc.1 keeps the sink -; the new inputs ride in a second record with the roaming lock itself, and its absence (the
     * first start after the upgrade) is rc.1's rule. */
    @Test
    fun `the second record survives an upgrade and a rollback`() {
        val inputs = VpnInputs(filterOn = true, parentSink = false, roamingLocked = false, abroadArmed = true, kidOff = true)
        val first = MobileNetworkRuntime.encodeInputs(inputs)
        assertEquals("1,0,1", first)
        // rc.1 decoding the untouched first record: its roaming lock, which keeps a persisted sink.
        assertTrue(MobileNetworkRuntime.decodeInputs(first)!!.roamingLocked)
        val second = MobileNetworkRuntime.encodeInputsR4(inputs)
        assertEquals("1,1,0", second)
        assertEquals(inputs, MobileNetworkRuntime.withInputsR4(MobileNetworkRuntime.decodeInputs(first)!!, second))
        // The upgrade: no second record yet.
        val upgraded = MobileNetworkRuntime.withInputsR4(MobileNetworkRuntime.decodeInputs("0,1,1")!!, null)
        assertEquals(VpnInputs(filterOn = false, parentSink = true, roamingLocked = true), upgraded)
        assertEquals(upgraded, MobileNetworkRuntime.withInputsR4(upgraded, "1"))
    }

    @Test
    fun `the relock takes a SIM reading on at most twice per process, never unlocked`() {
        assertEquals(setOf(2), dataRoamingRelock(true, mapOf(1 to false, 2 to true), emptyMap()))
        assertEquals(setOf(2), dataRoamingRelock(true, mapOf(1 to false, 2 to true), mapOf(2 to 1)))
        assertEquals(emptySet<Int>(), dataRoamingRelock(true, mapOf(1 to false, 2 to true), mapOf(2 to RELOCK_TRIES)))
        assertEquals(emptySet<Int>(), dataRoamingRelock(true, mapOf(1 to false, 2 to null), emptyMap()))
        assertEquals(emptySet<Int>(), dataRoamingRelock(false, mapOf(2 to true), emptyMap()))
    }

    @Test
    fun `the report encodes exactly the contract's`() {
        val report = contract["report"]!!.jsonObject
        val roaming = DataRoamingReport(
            locked = false, roaming = true, country = "se", network = "24001", enabled = true, dataActive = true, modems = 2,
            whereIs = Where.ABROAD.wire,
        )
        val mobile = MobileDataReport(enabled = true, sink = SinkReason.ABROAD.wire, settingsLocked = true, kid = "off")
        assertEquals(report["data_roaming"], ServerJson.encodeToJsonElement(DataRoamingReport.serializer(), roaming))
        assertEquals(report["mobile_data"], ServerJson.encodeToJsonElement(MobileDataReport.serializer(), mobile))
        // No home location leaves the phone; a 901 network is named even when it counts as home.
        assertEquals(Pair(null, null), roamingPlace(false, "no", "24201"))
        assertEquals(Pair(null, "901012"), roamingPlace(false, "dk", "901012"))
        assertEquals(Pair("se", "24001"), roamingPlace(true, "SE", "24001"))
        assertEquals(Pair(null, null), roamingPlace(true, "", ""))
    }

    @Test
    fun `this launcher claims the capabilities`() {
        val capabilities = contract["capabilities"]!!.jsonObject
        assertEquals(capabilities["data_roaming"]!!.jsonPrimitive.content, DATA_ROAMING_CAPABILITY)
        assertEquals(capabilities["mobile_data"]!!.jsonPrimitive.content, MOBILE_DATA_CAPABILITY)
        assertEquals(capabilities["mobile_network"]!!.jsonPrimitive.content, MOBILE_NETWORK_CAPABILITY)
        assertTrue(DATA_ROAMING_CAPABILITY in STATUS_CAPABILITIES)
        assertTrue(MOBILE_DATA_CAPABILITY in STATUS_CAPABILITIES)
        assertTrue(MOBILE_NETWORK_CAPABILITY in STATUS_CAPABILITIES)
    }

    /** Design 94 §3: a fallback keeps the parent's choices; an older record fails closed. */
    @Test
    fun `the last enforced plan keeps the choices, an older record locks`() {
        val policy = PolicyResponse(dataRoaming = null, abroad = "home", appsMobile = "vibb", mobileSettingsLocked = false)
        val kept = LastEnforcedPlan.decode(ServerJson.encodeToString(LastEnforcedPlan.serializer(), LastEnforcedPlan.of(policy)))!!.toPolicy()
        assertEquals(Triple(null, "vibb", false), Triple(kept.dataRoaming, kept.appsMobile, kept.mobileSettingsLocked))
        assertEquals(AbroadChoice.HOME, abroadChoice(kept.abroad, kept.dataRoaming))
        // Design 97 code QA L3: the kid's Mobildata bit survives a fallback; an older record has none.
        val withKid = PolicyResponse(quickControlsMask = 1L or QuickControlFeature.MOBILE_DATA)
        assertEquals(withKid.quickControlsMask, LastEnforcedPlan.of(withKid).toPolicy().quickControlsMask)
        val old = LastEnforcedPlan.decode("{}")!!.toPolicy()
        assertEquals(0L, old.quickControlsMask)
        assertEquals(Triple("off", null, true), Triple(old.dataRoaming, old.appsMobile, old.mobileSettingsLocked))
        assertEquals(AbroadChoice.OFF, abroadChoice(old.abroad, old.dataRoaming))
    }

    @Test
    fun `the service decides with serviceShape and switches with the pure steps`() {
        val vpn = listOf(java.io.File("src/main/java/com/kidslauncher/mdm/server/KidVpnService.kt"), java.io.File("app/src/main/java/com/kidslauncher/mdm/server/KidVpnService.kt"))
            .first { it.exists() }.readText()
        val wanted = vpn.substringAfter("private fun wantedShape(): VpnShape {").substringBefore("\n    }\n")
        assertTrue(wanted, "serviceShape(" in wanted && "storedShape()" in wanted)
        val switch = vpn.substringAfter("private fun switchTo(wanted: VpnShape): Boolean {").substringBefore("\n    }\n")
        assertTrue(switch.indexOf("alwaysOnBeforeEstablish(wanted)") in 0 until switch.indexOf("establish(wanted)"))
        assertTrue(switch.indexOf("val established = establish(wanted)") < switch.indexOf("alwaysOnAfterEstablish(wanted)"))
        // Every establish goes through switchTo.
        assertEquals(1, Regex("""\bestablish\(wanted\)""").findAll(vpn).count())
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/mobile_network.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
