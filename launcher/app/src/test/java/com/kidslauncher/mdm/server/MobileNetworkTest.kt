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
        // Design 97 D1: a new phone is Bare Vibb abroad.
        assertEquals(AbroadChoice.VIBB, abroadChoice(fresh))
        assertEquals(Pair("on", true), Pair(fresh.appsMobile, fresh.mobileSettingsLocked))
        assertEquals(AbroadChoice.HOME, abroadChoice(home))
        assertEquals(Pair("vibb", false), Pair(home.appsMobile, home.mobileSettingsLocked))
        assertEquals(AbroadChoice.OFF, abroadChoice(off))
        // Design 126 A5: "Blokker 2G" on for a new phone, off once saved off.
        assertEquals(listOf(true, false, false), listOf(fresh, home, off).map { it.block2g })
    }

    @Test
    fun `the choice reads every row and fails closed`() {
        for (row in rows("abroad_choice")) {
            val policy = if (row.opt("policy") == "false") null else PolicyResponse(abroad = row.opt("abroad"))
            assertEquals("$row", row.str("choice"), abroadChoice(policy).wire)
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
            val choice = abroadChoice(PolicyResponse(abroad = row.str("abroad")))
            assertEquals("$row", row.bool("locked"), roamingLocked(choice, row.bool("override")!!))
        }
        for (row in rows("settings_locked")) {
            assertEquals("$row", row.bool("locked"), mobileSettingsLocked(row.bool("value"), row.bool("override")!!))
        }
    }

    private fun whereOrNull(wire: String?): Where? = wire?.let { w -> Where.entries.first { it.wire == w } }

    /** Design 126 A5 (§3.6.2, §3.6.4): the 2G rule and the report's word, row by row. */
    @Test
    fun `the 2G block follows every row`() {
        for (row in rows("cellular_2g_blocked")) {
            val kept = whereOrNull(row.str("kept"))
            assertEquals("$row", row.bool("blocked"), cellular2gBlocked(row.bool("value"), row.bool("override")!!, kept))
            assertEquals("$row", row.str("state"), cellular2gState(row.bool("value"), row.bool("override")!!, kept).wire)
        }
    }

    /** Design 126 A5 (§3.6.1): the kept where through `unknown`, and the border fallback on the cells' country. */
    @Test
    fun `the kept where follows every row`() {
        for (row in rows("where_kept")) {
            val kept = where2gKept(
                last = whereOrNull(row.str("last")),
                now = whereOrNull(row.str("now"))!!,
                airplane = row.bool("airplane")!!,
                netCc = row.str("net_cc"),
                simCc = row.str("sim_cc"),
            )
            assertEquals("$row", row.str("kept"), kept?.wire)
        }
    }

    /** Every shape row through the same rules `apply()` uses, and always-on's lockdown for it. The rows from before
     * design 97 carry no `where` or `kid`. A row with `cellular: null` is a moment with no network seen, with the shape
     * last decided as `persisted`. */
    @Test
    fun `every shape row decides the shape, the reason and lockdown`() {
        for (row in rows("shapes")) {
            val override = row.bool("override")!!
            val choice = abroadChoice(PolicyResponse(abroad = row.str("abroad")))
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
     * failure; with lockdown off, a switch between the other shapes writes their always-on first. */
    @Test
    fun `a failed switch to the sink fails closed and retries`() {
        for (lockdownNow in listOf(false, true)) {
            assertEquals(null, alwaysOnBeforeEstablish(VpnShape.SINK, lockdownNow))
            assertEquals(VpnShape.SINK, alwaysOnAfterEstablish(VpnShape.SINK, lockdownNow))
        }
        assertTrue(alwaysOnFor(alwaysOnAfterEstablish(VpnShape.SINK, false)!!).lockdown)
        assertTrue(retriesFailedEstablish(VpnShape.SINK, established = false))
        assertFalse(retriesFailedEstablish(VpnShape.SINK, established = true))
        for (shape in listOf(VpnShape.FILTER, VpnShape.PASS)) {
            assertEquals(shape, alwaysOnBeforeEstablish(shape, lockdownNow = false))
            assertEquals(null, alwaysOnAfterEstablish(shape, lockdownNow = false))
            // Design 116 §3.1 (S-M2): every shape's failure is retried, not only the sink's.
            assertTrue(retriesFailedEstablish(shape, established = false))
        }
        assertFalse(retriesFailedEstablish(VpnShape.NONE, established = false))
    }

    /** One switch's steps as [switchTo] runs them: the always-on written before, the tunnel, the always-on after. */
    private fun steps(wanted: VpnShape, lockdownNow: Boolean): List<Pair<String, VpnShape>> = listOfNotNull(
        alwaysOnBeforeEstablish(wanted, lockdownNow)?.let { "always-on" to it },
        "tunnel" to wanted,
        alwaysOnAfterEstablish(wanted, lockdownNow)?.let { "always-on" to it },
    )

    /** Design 120 fix A: the tunnel first and lockdown changed last, both ways - into the sink lockdown comes on last
     * (as before), out of it lockdown goes off last (a new network every app's Chromium hears). Fail-closed at every
     * step: no state on the way lets traffic outside the VPN that neither end does. */
    @Test
    fun `lockdown changes last both ways and every step stays closed`() {
        // Into the sink from the filter or the pass shape (lockdown off).
        for (from in listOf(VpnShape.FILTER, VpnShape.PASS)) {
            assertEquals(listOf("tunnel" to VpnShape.SINK, "always-on" to VpnShape.SINK), steps(VpnShape.SINK, lockdownNow = false))
            assertClosedAllTheWay(from, false, VpnShape.SINK)
        }
        // Out of the sink (lockdown on): the new tunnel under lockdown, then lockdown off as the very last change.
        for (to in listOf(VpnShape.FILTER, VpnShape.PASS)) {
            val out = steps(to, lockdownNow = true)
            assertEquals(listOf("tunnel" to to, "always-on" to to), out)
            assertFalse(alwaysOnFor(out.last().second).lockdown)
            assertClosedAllTheWay(VpnShape.SINK, true, to)
        }
        // Between the filter and the pass shape nothing about lockdown changes: always-on first, as before.
        assertEquals(listOf("always-on" to VpnShape.PASS, "tunnel" to VpnShape.PASS), steps(VpnShape.PASS, lockdownNow = false))
        assertEquals(listOf("always-on" to VpnShape.FILTER, "tunnel" to VpnShape.FILTER), steps(VpnShape.FILTER, lockdownNow = false))
    }

    /** Traffic can leave outside the VPN while lockdown is off and the tunnel isn't the sink (whose routes take all). */
    private fun assertClosedAllTheWay(from: VpnShape, lockdown: Boolean, to: VpnShape) {
        fun open(tunnel: VpnShape, lockdownOn: Boolean) = !lockdownOn && tunnel != VpnShape.SINK
        var tunnel = from
        var lockdownOn = lockdown
        val states = mutableListOf(open(tunnel, lockdownOn))
        for ((step, shape) in steps(to, lockdown)) {
            if (step == "tunnel") tunnel = shape else lockdownOn = alwaysOnFor(shape).lockdown
            states += open(tunnel, lockdownOn)
        }
        assertEquals(alwaysOnFor(to).lockdown, lockdownOn)
        for (middle in states.drop(1).dropLast(1)) assertFalse("$from -> $to: $states", middle && !(states.first() && states.last()))
    }

    /** Design 120 fix A: apply() doesn't write lockdown off ahead of a running tunnel's switch out of the sink. */
    @Test
    fun `apply leaves lockdown off to the tunnel's switch`() {
        for (shape in listOf(VpnShape.FILTER, VpnShape.PASS)) {
            assertTrue(applyLeavesLockdownToSwitch(shape, lockdownNow = true, tunnelUp = true))
            assertFalse(applyLeavesLockdownToSwitch(shape, lockdownNow = true, tunnelUp = false))
            assertFalse(applyLeavesLockdownToSwitch(shape, lockdownNow = false, tunnelUp = true))
        }
        assertFalse(applyLeavesLockdownToSwitch(VpnShape.SINK, lockdownNow = true, tunnelUp = true))
        // The filter turned off: the VPN goes, nothing to wait for.
        assertFalse(applyLeavesLockdownToSwitch(VpnShape.NONE, lockdownNow = true, tunnelUp = true))
        val enforcer = listOf(java.io.File("src/main/java/com/kidslauncher/mdm/server/AppEnforcer.kt"), java.io.File("app/src/main/java/com/kidslauncher/mdm/server/AppEnforcer.kt"))
            .first { it.exists() }.readText()
        val apply = enforcer.substringAfter("private fun applyVpnRestrictions(").substringBefore("\n    }\n")
        assertTrue(apply, "applyLeavesLockdownToSwitch(shape, MobileNetworkRuntime.lockdownOurs(context), KidVpnService.liveTunnel().first)" in apply)
        assertTrue(apply, "MobileNetworkRuntime.writeAlwaysOn(context, shape, keepLockdown)" in apply)
    }

    /** Design 116's emulator gate: a burst of failed starts (a fresh process's start commands) schedules one retry and
     * leaves the wait at 5 s; only the retry's own failures double it, to 5 min at most. */
    @Test
    fun `only the retry's own failure doubles the wait`() {
        var waitMs = 5_000L
        var pending = false
        val scheduled = mutableListOf<Long>()
        fun fail() {
            reshapeRetryStep(pending, waitMs)?.let { (now, next) ->
                scheduled += now
                waitMs = next
                pending = true
            }
        }
        repeat(5) { fail() }
        assertEquals(listOf(5_000L), scheduled)
        assertEquals(10_000L, waitMs)
        repeat(8) {
            pending = false // the retry ran
            fail()
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), scheduled)
        assertEquals(null, reshapeRetryStep(retryPending = true, delayMs = 5_000L))
        assertEquals(Pair(300_000L, RESHAPE_RETRY_MAX_MS), reshapeRetryStep(retryPending = false, delayMs = 300_000L))
    }

    private val vpn get() = contract["vpn_filter"]!!.jsonObject
    private fun vpnRows(name: String): List<JsonObject> = vpn[name]!!.jsonArray.map { it.jsonObject }
    private fun filterState(wire: String?) = VpnFilterState.entries.first { it.wire == wire }
    private fun JsonObject.long(key: String): Long? = this[key]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content.toLong() }

    /** Design 116 §3.5: the filter's state, its down period, the alarm and the sync, row by row. */
    @Test
    fun `the filter's state, period, alarm and sync follow every row`() {
        assertEquals(VPN_REPORT_AFTER_MS, vpn["report_after_ms"]!!.jsonPrimitive.content.toLong())
        for (row in vpnRows("states")) {
            val state = vpnFilterState(row.bool("filter_on")!!, row.bool("tunnel_up")!!, VpnShape.of(row.str("shape")))
            assertEquals("$row", row.str("state"), state.wire)
        }
        for (row in vpnRows("periods")) {
            val processStart = row.long("process_start_ms")!!
            var period: VpnDownPeriod? = null
            var at = 0L
            for (step in row["steps"]!!.jsonArray.map { it.jsonObject }) {
                at = step.long("at_ms")!!
                val event = VpnFilterEvent.entries.first { it.wire == step.str("event") }
                period = vpnDownPeriod(period, event, filterState(step.str("state")), step.long("boot")!!.toInt(), at, processStart)
            }
            val report = vpnFilterReport(if (period == null) VpnFilterState.UP else VpnFilterState.DOWN, period, at)
            assertEquals(row.str("name"), row.long("down_ms"), report.downMs)
            assertEquals(row.str("name"), row.str("period"), report.period)
            assertEquals(row.str("name"), period, VpnDownPeriod.of(period?.id))
        }
        for (row in vpnRows("alarm")) {
            assertEquals("$row", row.str("action"), vpnRetryAlarmAction(filterState(row.str("state")), row.bool("armed")!!).wire)
        }
        for (row in vpnRows("sync")) {
            val action = vpnFilterSyncDue(
                row.long("down_ms"), row.bool("reported_this_period")!!, filterState(row.str("state")), row.str("last_reported"),
            )
            assertEquals("$row", row.str("action"), action.wire)
        }
    }

    @Test
    fun `the filter's reports encode exactly the contract's`() {
        val reports = vpn["reports"]!!.jsonObject
        val encode = { report: VpnFilterReport -> ServerJson.encodeToJsonElement(VpnFilterReport.serializer(), report) }
        assertEquals(reports["up"], encode(vpnFilterReport(VpnFilterState.UP, null, 9_000)))
        assertEquals(reports["down"], encode(vpnFilterReport(VpnFilterState.DOWN, VpnDownPeriod(5, 3_000), 723_000)))
        assertEquals(reports["off"], encode(vpnFilterReport(VpnFilterState.OFF, VpnDownPeriod(5, 3_000), 723_000)))
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
        val mobile = MobileDataReport(
            enabled = true, sink = SinkReason.ABROAD.wire, settingsLocked = true, kid = "off", cellular2g = Cellular2g.ABROAD.wire,
        )
        assertEquals(report["data_roaming"], ServerJson.encodeToJsonElement(DataRoamingReport.serializer(), roaming))
        assertEquals(report["mobile_data"], ServerJson.encodeToJsonElement(MobileDataReport.serializer(), mobile))
        // No home location leaves the phone; a 901 network is named even when it counts as home.
        assertEquals(Pair(null, null), roamingPlace(false, "no", "24201"))
        assertEquals(Pair(null, "901012"), roamingPlace(false, "dk", "901012"))
        assertEquals(Pair("se", "24001"), roamingPlace(true, "SE", "24001"))
        assertEquals(Pair(null, null), roamingPlace(true, "", ""))
    }

    /** Design 94 §3: a fallback keeps the parent's choices; an older record fails closed. */
    @Test
    fun `the last enforced plan keeps the choices, an older record locks`() {
        val policy = PolicyResponse(abroad = "home", appsMobile = "vibb", mobileSettingsLocked = false)
        val kept = LastEnforcedPlan.decode(ServerJson.encodeToString(LastEnforcedPlan.serializer(), LastEnforcedPlan.of(policy)))!!.toPolicy()
        assertEquals(Pair("vibb", false), Pair(kept.appsMobile, kept.mobileSettingsLocked))
        assertEquals(AbroadChoice.HOME, abroadChoice(kept))
        // Design 97 code QA L3: the kid's Mobildata bit survives a fallback; an older record has none.
        val withKid = PolicyResponse(quickControlsMask = 1L or QuickControlFeature.MOBILE_DATA)
        assertEquals(withKid.quickControlsMask, LastEnforcedPlan.of(withKid).toPolicy().quickControlsMask)
        val old = LastEnforcedPlan.decode("{}")!!.toPolicy()
        assertEquals(0L, old.quickControlsMask)
        assertEquals(Pair(null, true), Pair(old.appsMobile, old.mobileSettingsLocked))
        assertEquals(AbroadChoice.OFF, abroadChoice(old))
    }

    @Test
    fun `the service decides with serviceShape and switches with the pure steps`() {
        val vpn = listOf(java.io.File("src/main/java/com/kidslauncher/mdm/server/KidVpnService.kt"), java.io.File("app/src/main/java/com/kidslauncher/mdm/server/KidVpnService.kt"))
            .first { it.exists() }.readText()
        val wanted = vpn.substringAfter("private fun wantedShape(): VpnShape {").substringBefore("\n    }\n")
        assertTrue(wanted, "serviceShape(" in wanted && "storedShape()" in wanted)
        val switch = vpn.substringAfter("private fun switchTo(wanted: VpnShape): Boolean {").substringBefore("\n    }\n")
        // Design 120 fix A: both steps read lockdown as it is before the switch.
        val lockdownAt = switch.indexOf("val lockdownNow = MobileNetworkRuntime.lockdownOurs(this)")
        assertTrue(switch, lockdownAt in 0 until switch.indexOf("alwaysOnBeforeEstablish(wanted, lockdownNow)"))
        assertTrue(switch.indexOf("alwaysOnBeforeEstablish(wanted, lockdownNow)") in 0 until switch.indexOf("establish(wanted)"))
        assertTrue(switch.indexOf("val established = establish(wanted)") < switch.indexOf("alwaysOnAfterEstablish(wanted, lockdownNow)"))
        // Every establish goes through switchTo.
        assertEquals(1, Regex("""\bestablish\(wanted\)""").findAll(vpn).count())
        // Design 116 (QA L1): one retry chain - a lost tunnel's retry reschedules itself only when no switch ran.
        val retry = vpn.substringAfter("private fun retryTunnel() {").substringBefore("\n    }\n")
        assertTrue(retry, "val switched = startVpn()" in retry && "!switched" in retry)
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/mobile_network.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
