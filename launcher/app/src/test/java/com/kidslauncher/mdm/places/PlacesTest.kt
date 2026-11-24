package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.dto.PolicyZone
import com.kidslauncher.mdm.zones.distanceM
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.log10
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 125 step 1 on the phone: the pure rules over `wifi_places.json` (usable APs, who teaches, the accuracy) and in
 * code (pairing, rows, moves, the lookup, zone links, cells, the counts, the codec), a synthetic town whose indoor fixes
 * must hold their 68 % accuracy, design QA H2's rows, and by source the glue's load-bearing lines.
 */
class PlacesTest {
    private val keys = PlaceKeys("test-key-not-a-secret".toByteArray())
    private val moving = MovingSsids.parse(File("src/main/assets/places/moving_ssids.json").readText())
    private val rows = Json.parseToJsonElement(File("src/test/resources/wifi_places.json").readText()).jsonObject
    private fun JsonElement.str() = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement.dbl() = if (this is JsonNull) null else jsonPrimitive.double
    private fun mac(i: Int) = "00:00:5e:00:53:%02x".format(i)
    private val day = 1440

    // --- D4 ---

    @Test
    fun `usable - every row`() {
        for (r in rows["usable"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(r["why"]!!.str(), r["usable"]!!.jsonPrimitive.boolean, usableAp(r["bssid"]!!.str(), r["ssid"]!!.str(), moving))
        }
    }

    @Test
    fun `usable - every entry of the moving list is moving`() {
        val sections = Json.parseToJsonElement(File("src/main/assets/places/moving_ssids.json").readText()).jsonObject["sections"]!!.jsonObject
        assertTrue("the Norwegian slot is there, empty until seen in the field", sections["norwegian"]!!.jsonObject["exact"]!!.jsonArray.isEmpty())
        var n = 0
        for ((_, section) in sections) {
            fun list(name: String) = section.jsonObject[name]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val ssids = list("words").map { "My $it 42" } + list("prefixes").map { it + "x1" } + list("suffixes").map { "Kari $it" } +
                list("exact") + list("contains").map { "x$it" } + list("first_words").map { "$it 9" }
            for (ssid in ssids) {
                n++
                assertFalse(ssid, usableAp(mac(1), ssid, moving))
            }
        }
        assertTrue(n > 150)
        assertEquals(listOf("WIFIonICE", "wifionice"), listOf("WIFIonICE", "wifionice").filter { moving.moving(it, mac(1)) })
    }

    @Test
    fun `the connected AP - a dead WifiInfo is no connection`() {
        assertNull(connectedBssid(null))
        assertNull(connectedBssid("02:00:00:00:00:00"))
        assertEquals(mac(1), connectedBssid("00:00:5E:00:53:01"))
    }

    // --- D1, D2 ---

    @Test
    fun `teach - every row`() {
        for (r in rows["teach"]!!.jsonArray.map { it.jsonObject }) {
            val fix = TeachFix(59.9, 10.75, r["accuracy"]!!.dbl()?.toFloat(), r["speed"]!!.dbl()?.toFloat(), 0, r["provider"]!!.str(),
                r["satellites"]!!.dbl()?.toInt(), r["mock"]!!.jsonPrimitive.boolean)
            assertEquals(r["why"]!!.str(), r["teaches"]!!.jsonPrimitive.boolean, teaches(fix, release = r["release"]!!.jsonPrimitive.boolean))
        }
    }

    private fun gps(elapsed: Long, acc: Float = 8f, speed: Float? = 1f, lat: Double = 59.9, lng: Double = 10.75) =
        TeachFix(lat, lng, acc, speed, elapsed, "gps", 9, false)

    /** Each AP its own device (an MLO address each: the documentation block has one five-octet prefix). */
    private fun scan(vararg aps: Pair<Int, Int>, at: Long = 10_000, ssid: (Int) -> String = { "Hus$it" }, freq: Int = 2437) =
        aps.map { (i, rssi) -> ScanAp(mac(i), ssid(i), mac(0x80 + i), freq, rssi, at) }

    @Test
    fun `pairing - the nearest stream fix within a second, the error grows with speed and time`() {
        val h = hear(scan(1 to -50, 2 to -60, at = 10_400), null, keys, moving, emptyMap(), 11_000)
        val stream = listOf(gps(9_000, lat = 59.0), gps(10_000, acc = 6f, speed = 2f, lat = 59.9), gps(11_000, lat = 60.0))
        val s = pairings(h, stream, PAIR_STREAM_MS)
        assertEquals(2, s.size)
        assertTrue(s.all { it.lat == 59.9 })
        assertEquals(6 + 2 * 0.4, s[0].accEff, 1e-6)
        // Nothing within a second of the stream: nothing taught.
        assertTrue(pairings(h, listOf(gps(8_000), gps(12_000)), PAIR_STREAM_MS).isEmpty())
        // A single fix within +-30 s, speed unknown: 1.5 m/s.
        val single = pairings(h, listOf(gps(30_000, acc = 5f, speed = null)), PAIR_SINGLE_MS)
        assertEquals(5 + 1.5 * 19.6, single[0].accEff, 1e-6)
        assertTrue(pairings(h, listOf(gps(41_000)), PAIR_SINGLE_MS).isEmpty())
        // accEff over 40 m teaches nothing.
        assertTrue(pairings(h, listOf(gps(40_000, acc = 20f, speed = 1f)), PAIR_SINGLE_MS).isEmpty())
    }

    @Test
    fun `pairing - the 60 strongest, suspect results and stale scans teach nothing, the cell pairs within 30 s`() {
        val many = hear(scan(*(0 until 80).map { it to -40 - it }.toTypedArray()), null, keys, moving, emptyMap(), 10_000)
        val s = pairings(many, listOf(gps(10_000)), PAIR_STREAM_MS)
        assertEquals(TEACH_APS_MAX, s.size)
        assertEquals(-40, s.maxOf { it.heard.rssi })
        assertEquals(-99, s.minOf { it.heard.rssi })
        // The stale guard: a newer result at the very same level as before is suspect (never taught).
        val six = (1..6).map { it to -40 - 5 * it }
        val first = hear(scan(*six.toTypedArray(), at = 1_000), null, keys, moving, emptyMap(), 1_000)
        val two = six.map { (i, rssi) -> i to if (i <= 2) rssi else rssi - 1 }
        val again = hear(scan(*two.toTypedArray(), at = 5_000), null, keys, moving, first.readings, 5_000)
        assertEquals(listOf(true, true, false, false, false, false), again.heard.map { it.suspect })
        assertFalse("a few repeats are noise (code QA H1)", again.stale)
        assertEquals(4, pairings(again, listOf(gps(5_000)), PAIR_STREAM_MS).size)
        // Five of six repeated (>= 5 and >= 80 % of those heard before): stale, nothing taught.
        val five = six.map { (i, rssi) -> i to if (i <= 5) rssi else rssi - 1 }
        val stale = hear(scan(*five.toTypedArray(), at = 5_000), null, keys, moving, first.readings, 5_000)
        assertTrue(stale.stale)
        assertTrue(pairings(stale, listOf(gps(5_000)), PAIR_STREAM_MS).isEmpty())
        // The same list read twice is no repeat (its own time).
        assertFalse(hear(scan(*six.toTypedArray(), at = 1_000), null, keys, moving, first.readings, 9_000).stale)
        // A still phone: two scans of 30 APs with 2 dB of noise on whole-number levels are not stale.
        val rng = Random(3)
        val levels = (1..30).map { it to -45 - it }
        fun noisy(at: Long) = scan(*levels.map { (i, l) -> i to l + Math.round(rng.nextGaussian() * 2).toInt() }.toTypedArray(), at = at)
        val a = hear(noisy(1_000), null, keys, moving, emptyMap(), 1_000)
        val b = hear(noisy(31_000), null, keys, moving, a.readings, 31_000)
        assertTrue(b.heard.count { it.suspect } <= 15)
        assertFalse(b.stale)
        // The serving cell pairs within 30 s whatever the teacher, without the 40 m cap.
        val cell = Heard(keys.cell("242|01|1001|50001"), "c", "c", Band.CELL, 0, 20_000, kind = RowKind.CELL)
        val withCell = Hearing(listOf(cell), false, emptyMap())
        assertEquals(1, pairings(withCell, listOf(gps(1_000)), PAIR_STREAM_MS).size)
    }

    @Test
    fun `the refusal's reason, a fix too fast, the results paired with the best teacher`() {
        assertNull(teachRefusal(gps(0), release = true))
        assertEquals("nosat", teachRefusal(gps(0).copy(satellites = null), true))
        assertEquals("coarse", teachRefusal(gps(0, acc = 30f), true))
        assertEquals("fast", teachRefusal(gps(0, speed = 4f), true))
        assertEquals("mock", teachRefusal(gps(0).copy(mock = true), true))
        assertEquals("provider", teachRefusal(gps(0).copy(provider = "fused"), true))
        assertTrue(tooFastToTeach(gps(0, speed = 3.5f)))
        assertFalse(tooFastToTeach(gps(0, speed = null)))
        // Code QA M1: only results after the last fire and within the window of the best teacher.
        val h = hear(scan(1 to -50, at = 9_500) + scan(2 to -55, at = 12_000) + scan(3 to -60, at = 4_000), null, keys, moving, emptyMap(), 13_000)
        assertEquals(listOf(h.heard[0]), pairedWith(h.heard, gps(10_000), PAIR_STREAM_MS, 0))
        assertEquals(listOf(h.heard[0], h.heard[1]), pairedWith(h.heard, gps(10_000), PAIR_SINGLE_MS, 5_000))
        assertTrue(pairedWith(h.heard, gps(10_000), PAIR_SINGLE_MS, 12_500).isEmpty())
    }

    @Test
    fun `the attempt's plan - D14's table`() {
        val rows = listOf(
            Triple(AttemptPath.HEARTBEAT, com.kidslauncher.mdm.zones.Motion.MOVED, false) to AttemptPlan.TOGETHER,
            Triple(AttemptPath.HEARTBEAT, com.kidslauncher.mdm.zones.Motion.NO_SENSOR, false) to AttemptPlan.TOGETHER,
            Triple(AttemptPath.HEARTBEAT, com.kidslauncher.mdm.zones.Motion.STILL_SINCE_TRY, false) to AttemptPlan.LEARNED_FIRST,
            Triple(AttemptPath.HEARTBEAT, com.kidslauncher.mdm.zones.Motion.STILL, false) to AttemptPlan.LEARNED_FIRST,
            Triple(AttemptPath.HEARTBEAT, com.kidslauncher.mdm.zones.Motion.STILL, true) to AttemptPlan.TOGETHER,
            Triple(AttemptPath.CONFIRM, com.kidslauncher.mdm.zones.Motion.MOVED, false) to AttemptPlan.GPS_FIRST,
            Triple(AttemptPath.CONFIRM, com.kidslauncher.mdm.zones.Motion.STILL, true) to AttemptPlan.GPS_FIRST,
            Triple(AttemptPath.LOCATE, com.kidslauncher.mdm.zones.Motion.STILL, false) to AttemptPlan.LOCATE,
            Triple(AttemptPath.LOCATE, com.kidslauncher.mdm.zones.Motion.MOVED, true) to AttemptPlan.LOCATE,
        )
        for ((input, plan) in rows) assertEquals("$input", plan, attemptPlan(input.first, input.second, input.third))
    }

    @Test
    fun `lastSeen moves at most once a day, and an unchanged table is the same table`() {
        val row = PlaceRow(RowKind.WIFI, "a", lastSeenMin = 1000)
        val rows = listOf(row, row.copy(key = "b"))
        assertTrue(touchSeen(rows, setOf("a"), 1000 + SEEN_STEP_MIN - 1) === rows)
        assertEquals(listOf(1000 + SEEN_STEP_MIN, 1000), touchSeen(rows, setOf("a"), 1000 + SEEN_STEP_MIN).map { it.lastSeenMin })
    }

    @Test
    fun `the connected AP is added when the results lack it, a metered one goes`() {
        val connected = Connected(mac(9), "Hjemme", -45, 5180, unmetered = true)
        val h = hear(scan(1 to -50, 2 to -60), connected, keys, moving, emptyMap(), 7_000)
        assertEquals(3, h.heard.size)
        assertTrue(h.heard.single { it.connected }.let { it.elapsedMs == 7_000L && it.band == Band.G5 })
        val metered = hear(scan(1 to -50, 9 to -40), connected.copy(unmetered = false), keys, moving, emptyMap(), 7_000)
        assertEquals(1, metered.heard.size)
        assertTrue(metered.heard.none { it.connected })
        assertEquals(2, hear(scan(1 to -50, 2 to -60), connected.copy(bssid = "02:00:00:00:00:00"), keys, moving, emptyMap(), 7_000).heard.size)
    }

    // --- D5-D8 ---

    private fun sighting(i: Int, lat: Double, lng: Double, accEff: Double = 10.0, rssi: Int = -60, freq: Int = 2437) =
        Sighting(hear(listOf(ScanAp(mac(i), "Hus$i", null, freq, rssi, 0)), null, keys, moving, emptyMap(), 0).heard.single(), lat, lng, accEff)

    private val lat0 = 59.9
    private val lng0 = 10.75
    private fun north(m: Double) = lat0 + m / 111_195.0

    @Test
    fun `update - centroid, band floors, occasions and days`() {
        val (r1, c1) = teachRow(null, sighting(1, lat0, lng0), 1000, 20000)
        assertEquals(RowChange.NEW, c1)
        assertEquals(100f, r1.rangeM)
        assertEquals(60f, teachRow(null, sighting(2, lat0, lng0, freq = 5180), 1000, 20000).first.rangeM)
        assertEquals(1, r1.occasions)
        // 20 min later, 40 m north: the centroid moves, no new occasion, the same day.
        val (r2, c2) = teachRow(r1, sighting(1, north(40.0), lng0), 1020, 20000)
        assertEquals(RowChange.TAUGHT, c2)
        assertEquals(20.0, distanceM(lat0, lng0, r2.lat, r2.lng), 0.5)
        assertEquals(1, r2.occasions)
        // 40 min after the last occasion, the next day: an occasion and a day.
        val r3 = teachRow(r2, sighting(1, north(40.0), lng0), 1040, 20001).first
        assertEquals(2, r3.occasions)
        assertEquals(2, r3.days)
        // The range grows to cover a sighting, beyond the floor (never capped below the band's reach).
        val r4 = teachRow(r3, sighting(1, north(200.0), lng0, accEff = 20.0), 1100, 20001).first
        assertTrue(r4.rangeM > 150f)
        // The weight is capped: the newest ~20 sightings dominate.
        var r = r1
        repeat(100) { r = teachRow(r, sighting(1, lat0, lng0), 1000, 20000).first }
        assertEquals(WEIGHT_CAP, r.weight)
    }

    @Test
    fun `update - a move per band, two moves within 60 days block for 60 days, then it restarts`() {
        val home = teachRow(null, sighting(1, lat0, lng0, accEff = 10.0), 1000, 20000).first
        // 2.4 GHz: 300 m beyond the error.
        assertEquals(RowChange.TAUGHT, teachRow(home, sighting(1, north(305.0), lng0, accEff = 10.0), 1100, 20000).second)
        val (moved, c) = teachRow(home, sighting(1, north(320.0), lng0, accEff = 10.0), 1100, 20000)
        assertEquals(RowChange.MOVED, c)
        assertEquals(1, moved.moves)
        assertEquals(1, moved.occasions)
        assertEquals(north(320.0), moved.lat, 1e-9)
        // 5 GHz: 150 m.
        val five = teachRow(null, sighting(2, lat0, lng0, freq = 5180), 1000, 20000).first
        assertEquals(RowChange.MOVED, teachRow(five, sighting(2, north(170.0), lng0, freq = 5180), 1100, 20000).second)
        // A second move within 60 days: blocked for 60 days, a tombstone never taught.
        val (blocked, c2) = teachRow(moved, sighting(1, lat0, lng0), 1200 + 30 * day, 20030)
        assertEquals(RowChange.BLOCKED, c2)
        assertTrue(blocked.blocked(1200 + 89 * day))
        assertEquals(RowChange.SKIPPED, teachRow(blocked, sighting(1, lat0, lng0), 1200 + 31 * day, 20031).second)
        assertFalse(blocked.trusted(1200 + 31 * day))
        // After the block it restarts.
        val (back, c3) = teachRow(blocked, sighting(1, lat0, lng0), 1200 + 91 * day, 20091)
        assertEquals(RowChange.NEW, c3)
        assertEquals(0, back.moves)
        // A move more than 60 days after the last one is a first move again.
        assertEquals(RowChange.MOVED, teachRow(moved, sighting(1, lat0, lng0), 1100 + 61 * day, 20061).second)
    }

    @Test
    fun `prune - unseen 180 days goes, a blocked row stays while blocked, the least recently seen above the cap`() {
        val base = PlaceRow(RowKind.WIFI, "a", lastSeenMin = 0)
        val kept = prune(listOf(base, base.copy(key = "b", lastSeenMin = 10), base.copy(key = "c", blockedUntilMin = ROW_UNSEEN_MIN + 100)), ROW_UNSEEN_MIN + 5)
        assertEquals(listOf("b", "c"), kept.map { it.key })
        val many = (0 until ROWS_MAX + 10).map { base.copy(key = "k$it", lastSeenMin = 1000 + it) }
        val capped = prune(many, 2000)
        assertEquals(ROWS_MAX, capped.size)
        assertFalse(capped.any { it.key == "k0" })
        assertTrue(capped.any { it.key == "k${ROWS_MAX + 9}" })
    }

    // --- D9 ---

    /** A row learned at metres east/north of the origin, trusted (two occasions). */
    private fun learned(i: Int, east: Double, north: Double, range: Float = 100f, occasions: Int = 5, mld: String? = mac(0x80 + i), freq: Int = 2437, ssid: String = "Hus$i"): PlaceRow {
        val h = hear(listOf(ScanAp(mac(i), ssid, mld, freq, -60, 0)), null, keys, moving, emptyMap(), 0).heard.single()
        val (lat, lng) = Town.ll(east, north)
        return PlaceRow(RowKind.WIFI, h.key, h.device, h.network, h.band, lat, lng, 5f, range, occasions, 3, 20000, 0, 0)
    }

    private fun view(vararg aps: Pair<Int, Int>, at: Long = 100_000, now: Long = 100_000, connected: Connected? = null, ssid: (Int) -> String = { "Hus$it" }) =
        scanView(hear(scan(*aps, at = at, ssid = ssid), connected, keys, moving, emptyMap(), now), now, 0)!!

    @Test
    fun `lookup - the fire rule, a partial scan, the fallback not fresh, the oldest time`() {
        val h = hear(scan(1 to -50, 2 to -55, 3 to -60, at = 90_000) + scan(4 to -70, at = 60_000), null, keys, moving, emptyMap(), 100_000)
        assertTrue(scanView(h, 100_000, 0)!!.fresh)
        assertEquals(3, scanView(h, 100_000, 0)!!.heard.size)
        // A fire at 95 s: nothing heard after it.
        assertNull(scanView(h, 100_000, 95_000))
        // A fire at 80 s: three results after it but not 30 s fresh at 125 s - the fallback.
        val fallback = scanView(h, 125_000, 80_000)!!
        assertFalse(fallback.fresh)
        // Two results only: no lookup.
        assertNull(scanView(hear(scan(1 to -50, 2 to -55, at = 0), null, keys, moving, emptyMap(), 0), 0, -1))
        // Over two minutes: none.
        assertNull(scanView(h, 300_000, 0))
        // The fix's time is the oldest used result's.
        val table = listOf(learned(1, 0.0, 0.0), learned(2, 30.0, 0.0), learned(3, 0.0, 30.0))
        val mixed = hear(scan(1 to -50, at = 80_000) + scan(2 to -55, 3 to -60, at = 95_000), null, keys, moving, emptyMap(), 100_000)
        val fix = learnedFix(scanView(mixed, 100_000, 0)!!, table, 0)!!
        assertEquals(80_000L, fix.elapsedMs)
        assertTrue(fix.fresh)
        assertFalse(learnedFix(fallback, table, 0)!!.fresh)
    }

    @Test
    fun `lookup - one network never gives a fix, a 2-2 split none, untrusted and blocked rows count for nothing`() {
        // A mesh: three nodes, one SSID, each its own device.
        val mesh = (1..3).map { learned(it, it * 10.0, 0.0, mld = mac(0x80 + it), ssid = "Mesh") }
        assertNull(learnedFix(view(1 to -40, 2 to -45, 3 to -50, ssid = { "Mesh" }), mesh, 0))
        // Two networks agree: a fix, which doesn't stand alone after a move.
        val two = mesh + learned(4, 30.0, 20.0)
        val fix = learnedFix(view(1 to -40, 2 to -45, 3 to -50, 4 to -60, ssid = { if (it == 4) "Hus4" else "Mesh" }), two, 0)!!
        assertEquals(2, fix.networks)
        assertFalse(standsAlone(fix, movedSinceFresh = true))
        assertTrue(standsAlone(fix, movedSinceFresh = false))
        // A 2-2 split, a kilometre apart: no fix.
        val split = listOf(learned(1, 0.0, 0.0), learned(2, 20.0, 0.0), learned(3, 1000.0, 0.0), learned(4, 1020.0, 0.0))
        assertNull(learnedFix(view(1 to -50, 2 to -50, 3 to -50, 4 to -50), split, 0))
        // 3-1: the three win, and the fix stands alone after a move.
        val three = split + learned(5, 10.0, 20.0)
        assertEquals(3, learnedFix(view(1 to -50, 2 to -50, 3 to -50, 4 to -50, 5 to -50), three, 0)!!.networks)
        val f = learnedFix(view(1 to -50, 2 to -50, 5 to -50, 3 to -50), three, 0)!!
        assertEquals(3, f.networks)
        assertTrue(standsAlone(f, movedSinceFresh = true))
        assertTrue(distanceM(f.lat, f.lng, Town.ll(10.0, 7.0).first, Town.ll(10.0, 7.0).second) < 20)
        // Untrusted (one occasion) and blocked rows don't count.
        val weak = listOf(learned(1, 0.0, 0.0), learned(2, 20.0, 0.0, occasions = 1), learned(3, 0.0, 20.0).copy(blockedUntilMin = 100))
        assertNull(learnedFix(view(1 to -50, 2 to -50, 3 to -50), weak, 0))
    }

    @Test
    fun `lookup - complete link, not single link`() {
        // A chain 0 - 150 - 300 m: single link would join all; complete link leaves out the far end.
        val chain = listOf(learned(1, 0.0, 0.0, range = 80f), learned(2, 150.0, 0.0, range = 80f), learned(3, 300.0, 0.0, range = 80f), learned(4, 10.0, 10.0, range = 80f))
        val fix = learnedFix(view(1 to -50, 2 to -60, 3 to -70, 4 to -55), chain, 0)!!
        val (lat, lng) = Town.ll(0.0, 0.0)
        assertTrue(distanceM(fix.lat, fix.lng, lat, lng) < 90)
        assertFalse(fix.keys.contains(chain[2].key))
    }

    @Test
    fun `lookup - a false five-octet merge is harmless`() {
        // Two neighbours' routers of one vendor share five octets (no MLO): one device, still two networks.
        val a = learned(1, 0.0, 0.0, ssid = "A", mld = null)
        val b = learned(2, 30.0, 0.0, ssid = "B", mld = null)
        assertEquals(a.device, b.device)
        val table = listOf(a, b, learned(0x40, 0.0, 40.0, mld = mac(0x90), ssid = "C"), learned(0x41, 40.0, 40.0, mld = mac(0x91), ssid = "D"))
        val v = scanView(
            hear(
                listOf(
                    ScanAp(mac(1), "A", null, 2437, -40, 100_000), ScanAp(mac(2), "B", null, 2437, -60, 100_000),
                    ScanAp(mac(0x40), "C", mac(0x90), 2437, -65, 100_000), ScanAp(mac(0x41), "D", mac(0x91), 2437, -70, 100_000),
                ),
                null, keys, moving, emptyMap(), 100_000,
            ),
            100_000, 0,
        )!!
        val fix = learnedFix(v, table, 0)!!
        assertTrue(distanceM(fix.lat, fix.lng, a.lat, a.lng) <= fix.accuracyM)
    }

    @Test
    fun `lookup - the connected network weighs double, a suspect result a quarter`() {
        val table = listOf(learned(1, 0.0, 0.0), learned(2, 60.0, 0.0), learned(3, 30.0, 50.0))
        val plain = learnedFix(view(1 to -60, 2 to -60, 3 to -60), table, 0)!!
        val connected = learnedFix(view(1 to -60, 2 to -60, 3 to -60, connected = Connected(mac(1), "Hus1", -60, 2437, true)), table, 0)!!
        val (lat, lng) = Town.ll(0.0, 0.0)
        assertTrue(distanceM(connected.lat, connected.lng, lat, lng) < distanceM(plain.lat, plain.lng, lat, lng))
        val first = hear(scan(1 to -60, 2 to -61, 3 to -62, at = 1_000), null, keys, moving, emptyMap(), 1_000)
        val again = hear(scan(1 to -60, 2 to -64, 3 to -63, at = 5_000), null, keys, moving, first.readings, 5_000)
        val quarter = learnedFix(scanView(again, 5_000, 0)!!, table, 0)!!
        assertTrue(distanceM(quarter.lat, quarter.lng, lat, lng) > distanceM(plain.lat, plain.lng, lat, lng))
    }

    @Test
    fun `accuracy - every row`() {
        for (r in rows["accuracy"]!!.jsonArray.map { it.jsonObject }) {
            val acc = learnedAccuracy(
                r["spread"]!!.jsonPrimitive.double, r["range_med"]!!.jsonPrimitive.double, r["networks"]!!.jsonPrimitive.int,
                r["median_occasions"]!!.jsonPrimitive.int, r["rssi"]!!.jsonPrimitive.int, Band.valueOf(r["band"]!!.str()!!),
                r["stronger_unknown"]!!.jsonPrimitive.int,
            )
            val want = r["accuracy"]!!.dbl()
            if (want == null) assertNull(r["why"]!!.str(), acc) else assertEquals(r["why"]!!.str(), want, acc!!, 0.01)
        }
    }

    // --- D10 ---

    @Test
    fun `cells - an empty list is a miss, three occasions give a fix, a far cell vetoes`() {
        assertNull(cellFix(null, emptyList(), 0))
        val cell = Heard(keys.cell("242|01|1001|50001"), "c", "c", Band.CELL, 0, 5_000, kind = RowKind.CELL)
        val (lat, lng) = Town.ll(0.0, 0.0)
        var row: PlaceRow? = null
        for (t in 0 until 3) row = teachRow(row, Sighting(cell, lat, lng, 10.0), t * 60, 20000 + t).first
        assertEquals(CELL_RANGE_MIN_M, row!!.rangeM)
        assertEquals(3, row.occasions)
        val fix = cellFix(cell, listOf(row), 200)!!
        assertEquals(PROVIDER_CELL, fix.provider)
        assertEquals(500.0, fix.accuracyM, 0.0)
        assertNull(cellFix(cell, listOf(row.copy(occasions = 2)), 200))
        // A cell's range is capped at 10 km, and it moves at 20 km.
        val wide = teachRow(row, Sighting(cell, Town.ll(0.0, 15_000.0).first, lng, 10.0), 300, 20004).first
        assertEquals(CELL_RANGE_MAX_M, wide.rangeM)
        assertEquals(RowChange.MOVED, teachRow(row, Sighting(cell, Town.ll(0.0, 25_000.0).first, lng, 10.0), 300, 20004).second)
        // The veto: a Wi-Fi fix 3 km from the learned serving cell (range 500 m + 2 km).
        val far = LearnedFix(Town.ll(0.0, 3_000.0).first, lng, 40.0, 3, 0, true, emptySet())
        assertTrue(cellVetoes(cell, listOf(row), far, 200))
        assertFalse(cellVetoes(cell, listOf(row), far.copy(lat = Town.ll(0.0, 2_000.0).first), 200))
        assertFalse(cellVetoes(null, listOf(row), far, 200))
        // Design 125b step 3a: a cell fix is never fresh.
        assertFalse(fix.fresh)
    }

    @Test
    fun `design 125b C9 - cell_report, every row`() {
        val (lat, lng) = Town.ll(0.0, 0.0)
        val now = 10_000_000_000L
        fun north(m: Double) = Town.ll(0.0, m).first
        for (r in rows["cell_report"]!!.jsonArray.map { it.jsonObject }) {
            val why = r["why"]!!.str()
            val fix = LearnedFix(lat, lng, r["accuracy"]!!.dbl()!!, 1, 0, false, emptySet(), PROVIDER_CELL)
            val vouch = r["vouch_m"]!!.dbl()?.let { SeenFix(north(it), lng, r["vouch_acc"]!!.dbl()!!, now - 30 * 60_000L) }
            val fresh = r["fresh_m"]!!.dbl()?.let { SeenFix(north(it), lng, r["fresh_acc"]!!.dbl()!!, now - r["fresh_age_min"]!!.dbl()!!.toLong() * 60_000L) }
            val got = cellReport(fix, r["still"]!!.jsonPrimitive.boolean, vouch, fresh, now, r["locate"]!!.jsonPrimitive.boolean)
            assertEquals(why, r["verdict"]!!.str(), got.name)
        }
    }

    // --- D12 ---

    private val home = PolicyZone(7, lat0, lng0, 150)
    private val zones = listOf(LinkZone(home, keys.zone(home)))
    private val homeNet = "0123456789abcdef"

    private fun inside(rows: List<PlaceRow>, min: Int, today: Int) = teachLinks(rows, zones, lat0, lng0, 10.0, homeNet, setOf(homeNet), min, today)

    @Test
    fun `zone link - learned at three inside occasions on two days, revoked and blocked from outside`() {
        var r = inside(emptyList(), 0, 20000)
        r = inside(r, 10, 20000)
        r = inside(r, 60, 20000)
        assertEquals(2, r.single().occasions)
        assertFalse(r.single().linked(100))
        r = inside(r, day, 20001)
        assertTrue(r.single().linked(day + 1))
        // Not connected inside, or a fix not surely inside: nothing.
        assertEquals(r, teachLinks(r, zones, lat0, lng0, 10.0, null, setOf(homeNet), 2 * day, 20002))
        assertEquals(r, teachLinks(r, zones, north(145.0), lng0, 10.0, homeNet, setOf(homeNet), 2 * day, 20002))
        // Heard surely outside by 200 m: revoked.
        val out = teachLinks(r, zones, north(400.0), lng0, 10.0, null, setOf(homeNet), 2 * day, 20002)
        assertFalse(out.single().linked(2 * day))
        assertEquals(0, out.single().occasions)
        // 300 m out with 10 m accuracy is not surely 200 m outside a 150 m zone: nothing.
        assertEquals(r, teachLinks(r, zones, north(300.0), lng0, 10.0, null, setOf(homeNet), 2 * day, 20002))
        // A second outside sighting within 60 days blocks it for 60 days; inside teaches meanwhile don't count.
        val blocked = teachLinks(out, zones, north(400.0), lng0, 10.0, null, setOf(homeNet), 3 * day, 20003)
        assertTrue(blocked.single().blocked(62 * day))
        assertEquals(blocked, inside(blocked, 4 * day, 20004))
        // After the block it starts over.
        assertEquals(1, inside(blocked, 64 * day, 20064).single().occasions)
        // A zone moved (another key): its links go.
        val moved = PolicyZone(7, north(10.0), lng0, 150)
        assertTrue(teachLinks(r, listOf(LinkZone(moved, keys.zone(moved))), 0.0, 0.0, 10.0, null, emptySet(), 3 * day, 20003).isEmpty())
    }

    @Test
    fun `zone link - it only renews a still vouch surely inside, never with the refresh due`() {
        var r = inside(emptyList(), 0, 20000)
        r = inside(r, 60, 20000)
        r = inside(r, day, 20001)
        val now = day + 10
        val v = Vouched(north(20.0), lng0, 15.0)
        assertTrue(linkRenews(r, zones, v, homeNet, still = true, refreshDue = false, nowMin = now))
        assertFalse("after a move (the cabin MiFi)", linkRenews(r, zones, v, homeNet, still = false, refreshDue = false, nowMin = now))
        assertFalse("the daily refresh", linkRenews(r, zones, v, homeNet, still = true, refreshDue = true, nowMin = now))
        assertFalse("another network", linkRenews(r, zones, v, "fedcba9876543210", still = true, refreshDue = false, nowMin = now))
        assertFalse("not connected", linkRenews(r, zones, v, null, still = true, refreshDue = false, nowMin = now))
        assertFalse("the vouch not surely inside", linkRenews(r, zones, Vouched(north(140.0), lng0, 15.0), homeNet, true, false, now))
        assertFalse("not yet linked", linkRenews(inside(emptyList(), 0, 20000), zones, v, homeNet, true, false, now))
    }

    @Test
    fun `the refresh and the teaching budget`() {
        assertFalse(refreshDue(1_000, 1_000 + REFRESH_MS - 1))
        assertTrue(refreshDue(1_000, 1_000 + REFRESH_MS))
        assertTrue("the clock set back", refreshDue(1_000, 500))
        var b: TeachBudget? = null
        repeat(TEACH_PER_DAY) { b = budgetAfterTeach(b, "2026-11-22") }
        assertEquals(0, teachesLeft(b, "2026-11-22"))
        assertEquals(TEACH_PER_DAY, teachesLeft(b, "2026-11-23"))
    }

    // --- D15, D16 ---

    @Test
    fun `loo - the counts per class over 30 days, and the learned contradiction`() {
        val fix3 = LearnedFix(lat0, lng0, 50.0, 3, 1_000, true, emptySet())
        assertEquals(LooClass.THREE to true, leaveOneOut(fix3, north(40.0), lng0))
        assertEquals(LooClass.TWO to false, leaveOneOut(fix3.copy(networks = 2), north(60.0), lng0))
        assertNull(leaveOneOut(null, lat0, lng0))
        var days = countsWith(emptyList(), 20000) { it.loo(LooClass.THREE, true) }
        days = countsWith(days, 20000) { it.loo(LooClass.THREE, false) }
        days = countsWith(days, 20010) { it.loo(LooClass.TWO, true).loo(LooClass.ZONE, true) }
        days = countsWith(days, 20010) { it.copy(contradictions = it.contradictions + 1) }
        assertEquals("loo 3+=1/2 2=1/1 zone=1/1 own=0/0 apple=0/0 mixed=0/0 err own=- apple=- mixed=- contradictions=1 apple_contradictions=0 cell_contradictions=0", countsLine(days))
        assertEquals("loo 3+=0/0 2=1/1 zone=1/1 own=0/0 apple=0/0 mixed=0/0 err own=- apple=- mixed=- contradictions=1 apple_contradictions=0 cell_contradictions=0", countsLine(countsWith(days, 20030) { it }))
        assertTrue(learnedContradiction(fix3, north(600.0), lng0, 1_000 + 9 * 60_000))
        assertFalse("within 500 m", learnedContradiction(fix3, north(400.0), lng0, 1_000))
        assertFalse("more than 10 min", learnedContradiction(fix3, north(600.0), lng0, 1_000 + 11 * 60_000))
        assertFalse("two networks", learnedContradiction(fix3.copy(networks = 2), north(600.0), lng0, 1_000))
        assertTrue(gpsWins(40.0, 40f))
        assertFalse(gpsWins(40.0, 41f))
        assertFalse(gpsWins(40.0, null))
        assertTrue(gpsWins(null, 80f))
    }

    // --- D18 ---

    @Test
    fun `codec - a round trip, another version dropped`() {
        val table = listOf(learned(1, 10.0, 20.0), learned(2, -5.0, 3.0, mld = mac(0x80), freq = 5180).copy(moves = 1, lastMoveMin = 99, blockedUntilMin = 7),
            PlaceRow(RowKind.LINK, keys.zone(home), network = homeNet, occasions = 3, days = 2, lastDay = 20001, lastTeachMin = 5, lastSeenMin = 6),
            PlaceRow(RowKind.CELL, keys.cell("x"), "0011223344556677", "0011223344556677", Band.CELL, lat0, lng0, 2f, 600f, 3, 1, 1, 2, 3))
        val bytes = encodeTable(table)
        assertEquals(3 + 1 + 4 + 4 * 71, bytes.size)
        val back = decodeTable(bytes)!!
        assertEquals(table.map { it.copy(lat = 0.0, lng = 0.0) }, back.map { it.copy(lat = 0.0, lng = 0.0) })
        table.zip(back).forEach { (a, b) -> assertTrue(distanceM(a.lat, a.lng, b.lat, b.lng) < 0.02) }
        assertNull("another version", decodeTable(bytes.copyOf().also { it[3] = 2 }))
        assertNull("truncated", decodeTable(bytes.copyOf(bytes.size - 1)))
        assertNull("not ours", decodeTable("nope".toByteArray()))
        assertEquals(emptyList<PlaceRow>(), decodeTable(encodeTable(emptyList())))
    }

    // --- The synthetic town (§8) ---

    /**
     * A town of 24 houses on a 40 m grid and a school, each house a dual-band router (one device by its MLO address,
     * the documentation block having one five-octet prefix), the school six APs of one network. Reach: log-distance,
     * 2.4 GHz -20 dBm at 1 m and exponent 3.3 (heard to ~150 m outdoors), 5 GHz -26 dBm and 3.5, 7/10 dB per wall,
     * 6 dB shadowing; heard down to -92 dBm.
     */
    private class Town(seed: Long) {
        data class Ap(val bssid: String, val ssid: String, val mld: String, val x: Double, val y: Double, val freq: Int, val house: Int)

        val rng = Random(seed)
        val houses = (0 until 24).map { (it % 6) * 40.0 to (it / 6) * 40.0 } + listOf(300.0 to 60.0)
        val aps = houses.indices.flatMap { h ->
            val (hx, hy) = houses[h]
            if (h < 24) {
                val (jx, jy) = rng.nextGaussian() * 5 to rng.nextGaussian() * 5
                listOf(2437 to 2 * h, 5180 to 2 * h + 1).map { (f, i) -> Ap(mac(i), "Hus$h", mac(0x80 + h), hx + jx, hy + jy, f, h) }
            } else {
                (0 until 6).map { k -> Ap(mac(0x30 + k), "Skolen", mac(0xa0 + k), hx - 20 + (k % 3) * 20, hy - 15 + (k / 3) * 30, if (k % 2 == 0) 5180 else 2437, h) }
            }
        }

        fun rssi(ap: Ap, x: Double, y: Double, inHouse: Int?): Double {
            val d = maxOf(1.0, Math.hypot(ap.x - x, ap.y - y))
            val g24 = ap.freq < 3000
            val walls = (if (inHouse != ap.house) 1 else 0) + (if (inHouse != null && inHouse != ap.house) 1 else 0)
            // Walls differ: up to 12 dB more, fixed per AP and room.
            val extra = if (walls == 0) 0.0 else (((ap.bssid.hashCode() * 31 + (inHouse ?: -1) * 17) and 0xff) / 255.0) * 12
            return (if (g24) -20.0 else -26.0) - 10 * (if (g24) 3.3 else 3.5) * log10(d) - walls * (if (g24) 7 else 10) - extra + rng.nextGaussian() * 6
        }

        fun scan(x: Double, y: Double, inHouse: Int?, at: Long): List<ScanAp> =
            aps.mapNotNull { ap -> rssi(ap, x, y, inHouse).takeIf { it >= -92 }?.let { ScanAp(ap.bssid, ap.ssid, ap.mld, ap.freq, it.toInt(), at) } }

        companion object {
            private const val LAT0 = 59.9
            private const val LNG0 = 10.75
            fun mac(i: Int) = "00:00:5e:00:53:%02x".format(i)
            fun ll(x: Double, y: Double) = (LAT0 + y / 111_195.0) to (LNG0 + x / (111_195.0 * cos(Math.toRadians(LAT0))))
        }
    }

    /** Every street of the town: five east-west, five north-south. */
    private val allStreets = listOf(-20.0, 20.0, 60.0, 100.0, 140.0).map { y -> (-40..340 step 10).map { it.toDouble() to y } } +
        listOf(-20.0, 100.0, 220.0, 260.0, 340.0).map { x -> (-20..140 step 10).map { x to it.toDouble() } }

    /** Walks along [streets] on four occasions over three days, GPS within ~10 m, a scan every 10 m. */
    private fun taught(town: Town, streets: List<List<Pair<Double, Double>>> = allStreets): List<PlaceRow> {
        var table = emptyList<PlaceRow>()
        var elapsed = 0L
        for ((walk, min) in listOf(0 to 600, 1 to 900, 2 to day + 600, 3 to 2 * day + 960)) {
            var previous = emptyMap<String, Reading>()
            for (street in streets) for ((x, y) in street) {
                elapsed += 8_000
                val h = hear(town.scan(x, y, null, elapsed), null, keys, moving, previous, elapsed)
                previous = h.readings
                val (lat, lng) = Town.ll(x + town.rng.nextGaussian() * 6, y + town.rng.nextGaussian() * 6)
                val fix = TeachFix(lat, lng, 10f, 1.2f, elapsed, "gps", 9, false)
                table = teach(table, pairings(h, listOf(fix), PAIR_STREAM_MS), min, 20000 + walk).rows
            }
        }
        return table
    }

    /** Indoor fixes in every house: how many, how many within their accuracy, the median error and accuracy. */
    private fun indoor(town: Town, table: List<PlaceRow>, now: Int): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for (h in town.houses.indices) repeat(4) {
            val (hx, hy) = town.houses[h]
            val (x, y) = hx + town.rng.nextGaussian() * 4 to hy + town.rng.nextGaussian() * 4
            val v = scanView(hear(town.scan(x, y, h, 1_000), null, keys, moving, emptyMap(), 1_000), 1_000, 0) ?: return@repeat
            val fix = learnedFix(v, table, now) ?: return@repeat
            val (lat, lng) = Town.ll(x, y)
            out += distanceM(fix.lat, fix.lng, lat, lng) to fix.accuracyM
        }
        return out
    }

    /**
     * The binding row (D9), at home (house 7): a point [dx], [dy] metres from it, indoors in a house with no router of
     * its own and never taught there, whose scans hear home - every fix there is outside a zone of [radius] (smaller than
     * the distance) by the server's rule, `d + a > r`. Returns how many fixes were checked.
     */
    private fun neverInside(town: Town, table: List<PlaceRow>, now: Int, dx: Double, dy: Double, radius: Int): Int {
        val (homeX, homeY) = town.houses[7]
        val (zlat, zlng) = Town.ll(homeX, homeY)
        val homeAps = town.aps.filter { it.house == 7 }.map { it.bssid }.toSet()
        var checked = 0
        repeat(200) {
            val (x, y) = homeX + dx + town.rng.nextGaussian() * 3 to homeY + dy + town.rng.nextGaussian() * 3
            val results = town.scan(x, y, 99, 1_000)
            if (results.none { it.bssid in homeAps }) return@repeat
            val fix = learnedFix(scanView(hear(results, null, keys, moving, emptyMap(), 1_000), 1_000, 0) ?: return@repeat, table, now) ?: return@repeat
            checked++
            val d = distanceM(fix.lat, fix.lng, zlat, zlng)
            assertTrue("($dx, $dy) inside a $radius m zone: d=${d.toInt()} a=${fix.accuracyM.toInt()}", d + fix.accuracyM > radius)
        }
        return checked
    }

    private fun townRows(town: Town, table: List<PlaceRow>, label: String) {
        val now = 3 * day
        val fixes = indoor(town, table, now)
        val within = fixes.count { (err, acc) -> err <= acc }
        val median = { l: List<Double> -> l.sorted()[l.size / 2].toInt() }
        println("synthetic town, $label: ${fixes.size} fixes at ${town.houses.size * 4} indoor points, $within within; median error ${median(fixes.map { it.first })} m, median accuracy ${median(fixes.map { it.second })} m")
        assertTrue("$label: most indoor points give a fix", fixes.size >= town.houses.size * 4 * 0.5)
        assertTrue("$label: 68 %: $within of ${fixes.size}", within >= fixes.size * 0.68)
        // The neighbour 120 m from home is never surely inside a 150 m zone at home.
        assertEquals(120.0, town.houses[10].first - town.houses[7].first, 0.0)
        neverInside(town, table, now, 120.0, 0.0, 150)
        // Code QA H2: houses 50 and 70 m from home that hear home's router, zones just smaller than the distance.
        val near = neverInside(town, table, now, 50.0, 0.0, 45) + neverInside(town, table, now, 0.0, 70.0, 65) +
            neverInside(town, table, now, -35.0, 35.0, 45)
        println("synthetic town, $label: $near fixes near home checked")
        assertTrue("$label: the near houses hear home and get fixes", near >= 50)
    }

    @Test
    fun `the synthetic town - indoor fixes hold their 68 percent, a neighbour is never inside home's zone`() {
        val town = Town(42)
        townRows(town, taught(town), "every street")
        // Taught from one street only (y = 20, past home): every row's position leans to that street.
        townRows(town, taught(town, allStreets.take(2).drop(1)), "one street")
    }

    @Test
    fun `H2 - a moved mesh, a cabin MiFi, a moved lone router give no wrong standing fix`() {
        val town = Town(7)
        var table = taught(town)
        val now = 3 * day
        // A mesh at house 0 (three nodes, one SSID), learned on two occasions from the street.
        val mesh = (0 until 3).map { k -> ScanAp(Town.mac(0x60 + k), "Mesh", Town.mac(0xc0 + k), 2437, -45 - 5 * k, 0) }
        for ((i, min) in listOf(600, 900).withIndex()) {
            val h = hear(mesh.map { it.copy(elapsedMs = i * 1000L) }, null, keys, moving, emptyMap(), i * 1000L)
            val (lat, lng) = Town.ll(0.0, -15.0)
            table = teach(table, pairings(h, listOf(TeachFix(lat, lng, 8f, 0f, i * 1000L, "gps", 9, false)), PAIR_STREAM_MS), min, 20000).rows
        }
        // Moved 2 km: the mesh alone is one network - no fix.
        assertNull(learnedFix(scanView(hear(mesh.map { it.copy(elapsedMs = 5_000) }, null, keys, moving, emptyMap(), 5_000), 5_000, 0)!!, table, now))
        // ... with two unknown networks beside it: still none.
        val strangers = listOf(ScanAp("00:00:5e:00:53:e0", "Ny1", null, 2437, -50, 5_000), ScanAp("00:00:5e:00:53:e1", "Ny2", null, 2437, -55, 5_000))
        assertNull(learnedFix(scanView(hear(mesh.map { it.copy(elapsedMs = 5_000) } + strangers, null, keys, moving, emptyMap(), 5_000), 5_000, 0)!!, table, now))
        // Moved into the town's far corner (house 23): if a fix comes, it is right within its accuracy.
        repeat(20) {
            val (hx, hy) = town.houses[23]
            val scan = town.scan(hx, hy, 23, 5_000) + mesh.map { it.copy(elapsedMs = 5_000) }
            val fix = learnedFix(scanView(hear(scan, null, keys, moving, emptyMap(), 5_000), 5_000, 0)!!, table, now) ?: return@repeat
            val (lat, lng) = Town.ll(hx, hy)
            assertTrue("a wrong standing fix", !standsAlone(fix, true) || distanceM(fix.lat, fix.lng, lat, lng) <= fix.accuracyM)
        }
        // The cabin MiFi: linked to home, then alone at the cabin - no fix; a fire on the drive ends any renewal; a
        // teaching fix at the cabin revokes the link.
        val mifi = hear(listOf(ScanAp("00:00:5e:00:53:f0", "Hytta", null, 2437, -40, 0)), null, keys, moving, emptyMap(), 0).heard.single()
        assertNull(scanView(Hearing(listOf(mifi), false, emptyMap()), 0, -1))
        val homeZone = PolicyZone(1, Town.ll(40.0, 40.0).first, Town.ll(40.0, 40.0).second, 150)
        val z = listOf(LinkZone(homeZone, keys.zone(homeZone)))
        var links = teachLinks(emptyList(), z, homeZone.latitude, homeZone.longitude, 8.0, mifi.network, setOf(mifi.network), 0, 20000)
        links = teachLinks(links, z, homeZone.latitude, homeZone.longitude, 8.0, mifi.network, setOf(mifi.network), 60, 20000)
        links = teachLinks(links, z, homeZone.latitude, homeZone.longitude, 8.0, mifi.network, setOf(mifi.network), day, 20001)
        val vouch = Vouched(homeZone.latitude, homeZone.longitude, 10.0)
        assertTrue(linkRenews(links, z, vouch, mifi.network, still = true, refreshDue = false, nowMin = day + 1))
        assertFalse(linkRenews(links, z, vouch, mifi.network, still = false, refreshDue = false, nowMin = day + 1))
        links = teachLinks(links, z, Town.ll(50_000.0, 0.0).first, Town.ll(50_000.0, 0.0).second, 8.0, mifi.network, setOf(mifi.network), day + 200, 20001)
        assertFalse(linkRenews(links, z, vouch, mifi.network, still = true, refreshDue = false, nowMin = day + 300))
        // A moved lone router: one network, no fix; the first GPS fix there moves its row.
        val alone = town.aps.filter { it.house == 23 }.map { ScanAp(it.bssid, it.ssid, it.mld, it.freq, -50, 9_000) }
        assertNull(learnedFix(scanView(hear(alone, null, keys, moving, emptyMap(), 9_000), 9_000, 0) ?: ScanView(emptyList(), true), table, now))
        val lone = table.first { it.network == keys.network("Hus23", it.device) }
        val far = Town.ll(2_000.0, 0.0)
        val moved = teachRow(lone, Sighting(Heard(lone.key, lone.device, lone.network, lone.band, -40, 0), far.first, far.second, 8.0), now, 20003)
        assertEquals(RowChange.MOVED, moved.second)
    }

    // --- Design 125 step 2: the Apple rows (A3, A9) ---

    /** Apple's position for AP [i] at metres east/north, stored at [now] (the month of the epoch's start). */
    private fun appleRows(vararg aps: Triple<Int, Pair<Double, Double>, Int>, now: Int = 0, rows: List<PlaceRow> = emptyList()): List<PlaceRow> =
        storeApple(rows, aps.map { (i, at, acc) -> Town.ll(at.first, at.second).let { (lat, lng) -> AppleAp(mac(i), lat, lng, acc, APPLE_FRESH_MONTHS) } }, emptyList(), keys, now)

    private fun ap(i: Int, east: Double, north: Double, acc: Int = 30) = Triple(i, east to north, acc)

    @Test
    fun `apple - asked only for what the phone lacks, strongest first, at most 40`() {
        val untrusted = learned(2, 0.0, 0.0, occasions = 1)
        val table = listOf(learned(1, 0.0, 0.0), untrusted) + appleRows(ap(3, 0.0, 0.0)) +
            // 4 a tombstone (its own row disagrees).
            reconcileApple(listOf(learned(4, 0.0, 0.0)) + appleRows(ap(4, 900.0, 0.0)), 0).filter { it.kind == RowKind.APPLE }
        val heard = view(1 to -40, 2 to -70, 3 to -45, 4 to -50, 6 to -60, 7 to -55).heard
        // 1 trusted, 3 live, 4 a tombstone: left out; 2 (an untrusted own row), 6 and 7 asked.
        assertEquals(listOf(mac(7), mac(6), mac(2)), appleAsk(heard, table, 0))
        // 90 days on: the Apple row and the tombstone have run out - asked again.
        assertEquals(listOf(mac(3), mac(4), mac(7), mac(6), mac(2)), appleAsk(heard, table, APPLE_ROW_MIN))
        val many = view(*(1..60).map { it to -30 - it }.toTypedArray()).heard
        assertEquals((1..40).map(::mac), appleAsk(many, emptyList(), 0))
    }

    @Test
    fun `apple - the rows stored, a tombstone kept, the cap, the switch off`() {
        val tenth = 60 * 24 * 9
        val rows = storeApple(
            emptyList(),
            listOf(
                AppleAp(mac(1), 59.9, 10.75, 30, 3),
                AppleAp("02:00:5e:00:53:02", 59.9, 10.75, 30, 3),
                AppleAp(mac(3), 59.9, 10.75, 0, 3),
                AppleAp(mac(4), 91.0, 10.75, 30, 3),
                // More months than the server's rule, or fewer than none: no row.
                AppleAp(mac(5), 59.9, 10.75, 30, 4),
                AppleAp(mac(6), 59.9, 10.75, 30, -1),
                // Stale (served while Apple couldn't be asked): a day's grace (code QA M1).
                AppleAp(mac(7), 59.9, 10.75, 30, 0),
            ),
            listOf(mac(8), "02:00:5e:00:53:09"),
            keys,
            tenth,
        )
        val live = rows.filter { it.appleLive(tenth) }
        assertEquals(setOf(keys.ap(mac(1)), keys.ap(mac(7))), live.map { it.key }.toSet())
        assertTrue(live.all { it.kind == RowKind.APPLE && it.rangeM == 30f })
        // Three months left on 1970-01-10: until 1970-04-01 (the server asks Apple again that month).
        assertEquals(monthStartMin(tenth, 3), live.first { it.key == keys.ap(mac(1)) }.lastTeachMin)
        assertEquals(90 * 24 * 60, monthStartMin(tenth, 3))
        assertEquals(tenth + APPLE_STALE_GRACE_MIN, live.first { it.key == keys.ap(mac(7)) }.lastTeachMin)
        assertFalse(live.first { it.key == keys.ap(mac(7)) }.appleLive(tenth + APPLE_STALE_GRACE_MIN))
        // A negative (code QA M2): no position, blocks the ask until the month ends - then asked again.
        val negative = rows.single { it.key == keys.ap(mac(8)) }
        assertFalse(negative.appleLive(tenth))
        assertEquals(monthStartMin(tenth, 1), negative.blockedUntilMin)
        val heard8 = hear(scan(8 to -50), null, keys, moving, emptyMap(), 0).heard
        assertEquals(emptyList<String>(), appleAsk(heard8, rows, tenth))
        assertEquals(listOf(mac(8)), appleAsk(heard8, rows, monthStartMin(tenth, 1)))
        assertEquals(3, rows.size)
        // A clock set back far behind a row's end: not live.
        assertFalse(live.first().appleLive(live.first().lastTeachMin - APPLE_LIVE_MAX_MIN - 1))
        // A tombstone stays one while it blocks; a live row is replaced.
        val stone = reconcileApple(listOf(learned(1, 0.0, 0.0)) + appleRows(ap(1, 900.0, 0.0)), 0)
        assertEquals(0.0, stone.first { it.kind == RowKind.APPLE }.lat, 0.0)
        val again = appleRows(ap(1, 0.0, 0.0), rows = stone)
        assertTrue(again.first { it.kind == RowKind.APPLE }.blocked(0))
        val replaced = appleRows(ap(9, 0.0, 0.0, acc = 50), rows = appleRows(ap(9, 0.0, 0.0, acc = 30)))
        assertEquals(listOf(50f), replaced.filter { it.kind == RowKind.APPLE }.map { it.rangeM })
        // Expired rows go, a tombstone after its block too; at most 3000, the least recently seen out.
        assertTrue(prune(again, APPLE_ROW_MIN).none { it.kind == RowKind.APPLE })
        val crowd = (0 until APPLE_ROWS_MAX + 5).map { PlaceRow(RowKind.APPLE, "k$it", lat = 1.0, lng = 1.0, rangeM = 30f, lastTeachMin = 60 * 24, lastSeenMin = it) }
        val kept = prune(crowd, 0)
        assertEquals(APPLE_ROWS_MAX, kept.size)
        assertTrue(kept.none { it.lastSeenMin < 5 })
        // The switch off drops every Apple row, nothing else.
        assertEquals(listOf(RowKind.WIFI), dropApple(stone).map { it.kind })
        // The store keeps them.
        assertEquals(rows, decodeTable(encodeTable(rows)))
    }

    @Test
    fun `apple - own and Apple rows by trust and agreement`() {
        val now = 0
        val own = listOf(learned(1, 0.0, 0.0, range = 100f), learned(2, 60.0, 0.0), learned(3, 0.0, 60.0))
        val v = view(1 to -50, 2 to -55, 3 to -60)
        val ownFix = learnedFix(v, own, now)!!
        assertEquals(PlaceSource.OWN, ownFix.source)
        assertEquals(PROVIDER_WIFI, ownFix.provider)
        // An untrusted own row doesn't block Apple: Apple's row is used (and asked for while it has none).
        val untrusted = listOf(learned(1, 0.0, 0.0, occasions = 1), learned(2, 60.0, 0.0), learned(3, 0.0, 60.0))
        assertEquals(listOf(mac(1)), appleAsk(v.heard, untrusted, now))
        val mixed = learnedFix(v, untrusted + appleRows(ap(1, 5.0, 5.0)), now)!!
        assertEquals(PlaceSource.MIXED, mixed.source)
        assertEquals(PROVIDER_APPLE, mixed.provider)
        assertEquals(3, mixed.networks)
        // Apple alone.
        val apple = learnedFix(v, appleRows(ap(1, 0.0, 0.0), ap(2, 60.0, 0.0), ap(3, 0.0, 60.0)), now)!!
        assertEquals(PlaceSource.APPLE, apple.source)
        // Trusted and agreeing: the smaller range gives the position (a 100 m own row against Apple's 2.4 GHz floor,
        // 100 m: a tie - the own row).
        val h1 = v.heard.first { it.key == keys.ap(mac(1)) }
        val appleNear = appleRows(ap(1, 40.0, 0.0, acc = 30)).single()
        assertEquals(false, chooseRow(h1, own[0], appleNear, now)!!.second)
        val h5 = hear(scan(1 to -50, freq = 5180), null, keys, moving, emptyMap(), 0).heard.single()
        val own5 = learned(1, 0.0, 0.0, range = 150f, freq = 5180)
        assertEquals(true, chooseRow(h5, own5, appleNear, now)!!.second)
        assertEquals(60f, chooseRow(h5, own5, appleNear, now)!!.first.rangeM)
        // Disagreeing: the own row, and the Apple row a tombstone; an agreeing one never.
        val far = appleRows(ap(1, 400.0, 0.0)).single()
        assertEquals(false, chooseRow(h1, own[0], far, now)!!.second)
        val reconciled = reconcileApple(own + far, now)
        assertTrue(reconciled.first { it.kind == RowKind.APPLE }.blocked(now))
        assertEquals(appleNear, reconcileApple(own + appleNear, now).first { it.kind == RowKind.APPLE })
        // An untrusted own row against it: no tombstone (only ground truth makes one).
        assertEquals(far, reconcileApple(untrusted + far, now).first { it.kind == RowKind.APPLE })
        // An own teach beyond the Apple row's band reach (300 m at 2.4 GHz past the error): a tombstone.
        val (lat, lng) = Town.ll(500.0, 0.0)
        val s = Sighting(h1, lat, lng, 10.0)
        assertTrue(appleTaught(listOf(appleNear), listOf(s), now).single().blocked(now))
        val (nlat, nlng) = Town.ll(200.0, 0.0)
        assertEquals(appleNear, appleTaught(listOf(appleNear), listOf(Sighting(h1, nlat, nlng, 10.0)), now).single())
    }

    @Test
    fun `apple - still means still, the waits, the counts by source`() {
        val vouch = Vouched(59.9, 10.75, 20.0)
        val fix = LearnedFix(59.9, 10.75, 50.0, 3, 0, true, emptySet(), PROVIDER_APPLE, PlaceSource.APPLE)
        val (flat, flng) = Town.ll(60.0, 0.0)
        val off = fix.copy(lat = flat, lng = flng, accuracyM = 30.0)
        assertEquals(StillVerdict.RENEW, appleStill(fix, vouch, still = true))
        assertEquals(StillVerdict.CONTRADICTS, appleStill(off, vouch, still = true))
        assertEquals(StillVerdict.USE, appleStill(off, vouch, still = false))
        // Still with no vouch: an Apple position alone is withheld (code QA L3); an own one is used.
        assertEquals(StillVerdict.WITHHOLD, appleStill(off, null, still = true))
        assertEquals(StillVerdict.USE, appleStill(off.copy(source = PlaceSource.OWN), null, still = true))
        assertEquals(StillVerdict.USE, appleStill(off.copy(source = PlaceSource.OWN), vouch, still = true))
        assertEquals(StillVerdict.CONTRADICTS, appleStill(off.copy(source = PlaceSource.MIXED), vouch, still = true))
        // Still means STILL alone: moved since the vouch with only a failed try after it, Apple may move the phone.
        assertTrue(appleStillMotion(com.kidslauncher.mdm.zones.Motion.STILL))
        assertFalse(appleStillMotion(com.kidslauncher.mdm.zones.Motion.STILL_SINCE_TRY))
        assertFalse(appleStillMotion(com.kidslauncher.mdm.zones.Motion.MOVED))
        assertFalse(appleStillMotion(com.kidslauncher.mdm.zones.Motion.NO_SENSOR))
        // The waits: until the time, a 409 until a policy says on, a wait past a day is a clock set back.
        assertTrue(appleMayAsk(1_000, 0, off = false))
        assertFalse(appleMayAsk(1_000, 2_000, off = false))
        assertFalse(appleMayAsk(1_000, 0, off = true))
        assertTrue(appleMayAsk(1_000, 1_000 + APPLE_WAIT_MAX_MS + 1, off = false))
        // D16 by source, the mean error per source.
        val days = countsWith(emptyList(), 5) { it.looSource(PlaceSource.APPLE, true, 30.0).looSource(PlaceSource.APPLE, false, 90.0).looSource(PlaceSource.OWN, true, 12.0) }
        val line = countsLine(days)
        assertTrue(line, line.contains("own=1/1 apple=1/2 mixed=0/0 err own=12 apple=60 mixed=-"))
    }

    @Test
    fun `the synthetic town with Apple's rows - alone and mixed, a neighbour never inside`() {
        val town = Town(42)
        val now = 3 * day
        // Apple's crowd-sourced positions: off by ~15 m, accuracies 20-80 m.
        val rng = java.util.Random(5)
        val positions = town.aps.map { a ->
            Triple(a.bssid, a.x + rng.nextGaussian() * 15 to a.y + rng.nextGaussian() * 15, 20 + rng.nextInt(60))
        }
        fun apple(rows: List<PlaceRow>) = storeApple(rows, positions.map { (bssid, at, acc) -> Town.ll(at.first, at.second).let { (lat, lng) -> AppleAp(bssid, lat, lng, acc, APPLE_FRESH_MONTHS) } }, emptyList(), keys, now)
        townRows(town, apple(emptyList()), "Apple alone")
        townRows(town, apple(taught(town, allStreets.take(2).drop(1))), "Apple and one street")
        // Every fix from Apple's rows names it.
        val appleOnly = apple(emptyList())
        val providers = town.houses.indices.mapNotNull { h ->
            val (x, y) = town.houses[h]
            scanView(hear(town.scan(x, y, h, 1_000), null, keys, moving, emptyMap(), 1_000), 1_000, 0)?.let { learnedFix(it, appleOnly, now) }?.provider
        }
        assertEquals(setOf(PROVIDER_APPLE), providers.toSet())
    }

    @Test
    fun `source - the Apple ask only on a learned miss with the switch on, the switch from each fresh policy`() {
        val glue = source("places/WifiPlaces.kt")
        // The one ask, behind the switch, the waits and a network (A3); never from the teaching fix or a passive fix.
        assertEquals(1, Regex("""askApple\(app, """).findAll(glue).count())
        assertTrue(glue.contains("if (store == null || !appleAllowed(app)) return null"))
        assertTrue(Regex("""learned\(\) \?: appleFix\(\)""").findAll(glue).count() == 3)
        val teachAlarm = Regex("""internal fun onTeachAlarm\([\s\S]*?\n    }""").find(glue)!!.value
        assertFalse(teachAlarm.contains("apple", ignoreCase = true))
        // The attempt runs only with the network provider off (H1), so the ask does too.
        assertTrue(source("server/LocateCommands.kt").contains("places != null && WifiPlaces.networkOff(lm) ->"))
        assertTrue(source("server/MdmSyncWorker.kt").contains("WifiPlaces.policyAccepted(context, com.kidslauncher.mdm.places.wifiLookupOn(freshPolicy))"))
        assertFalse(source("places/ApplePlan.kt").contains("Log."))
    }

    // --- By source: the glue's load-bearing lines ---

    private fun source(path: String) = File("src/main/java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `source - no identifier in a log line, no neverForLocation, the clears, no scan with the network provider on`() {
        val glue = source("places/WifiPlaces.kt")
        val text = glue + source("places/PlacesPlan.kt")
        var calls = 0
        for (m in Regex("""Log\.\w\(""").findAll(text)) {
            var depth = 1
            var i = m.range.last + 1
            while (depth > 0) depth += when (text[i++]) { '(' -> 1; ')' -> -1; else -> 0 }
            val call = text.substring(m.range.first, i)
            calls++
            assertFalse(call, Regex("""bssid|ssid|cellId|keys\.|\.key\b|secret|\blat|\blng|\.rows""", RegexOption.IGNORE_CASE).containsMatchIn(call))
        }
        assertTrue(calls > 10)
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains(Regex("""NEARBY_WIFI_DEVICES"\s+android:usesPermissionFlags""")))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains(".places.WifiPlacesTeachReceiver"))
        // D19: Off, unmanaged and a new enrollment (release, which forget calls) clear the table.
        assertTrue(Regex("""fun clearCachedFix\(context: Context\) \{\s+com\.kidslauncher\.mdm\.zones\.MotionGate\.markMoved\(\)\s+WifiPlaces\.clear\(context, "off"\)""").containsMatchIn(source("server/LocateCommands.kt")))
        assertTrue(source("zones/ZoneWatch.kt").contains("com.kidslauncher.mdm.places.WifiPlaces.clear(app, \"unenrol\")"))
        assertTrue(Regex("""fun forget\(context: Context\) \{\s+val app = context\.applicationContext\s+release\(app\)""").containsMatchIn(source("zones/ZoneWatch.kt")))
        // H1: the attempt (and every scan) only with the network provider off; the teaching fix checks it too.
        assertTrue(source("server/LocateCommands.kt").contains("places != null && WifiPlaces.networkOff(lm) ->"))
        assertEquals(1, Regex("""wm\.startScan\(\)""").findAll(glue).count())
        assertEquals(3, Regex("""scanNow\(""").findAll(glue).count())
        assertTrue(glue.contains("if (snap.results.none { r -> streamMs.any { abs(it - r.elapsedMs) <= PAIR_STREAM_MS } } && networkOff(lm)) {"))
        assertTrue(glue.contains("ZoneWatch.stillOn(context, currentPolicyDecision().policy) && QuickControls.isLocationEnabled(context) && networkOff(lm)"))
        assertEquals("armed and run only when allowed (L2)", 2, Regex("""if \(!teachAllowed\(app, lm\)""").findAll(glue).count())
        // The passive feed teaches against cached results only, never a scan of ours.
        val passive = Regex("""fun offerPassive\([\s\S]*?\n    }""").find(glue)!!.value
        assertFalse(passive.contains("scanNow") || Regex("""[^.]snapshot\(""").containsMatchIn(passive))
        // A blob a healthy key can't open is cleared (code QA M2), a broken key clears, the rest is transient.
        assertTrue(glue.contains("e is AEADBadTagException || e is IllegalArgumentException -> clearLocked(context, \"unreadable\")"))
        assertTrue(glue.contains("Secrets.box.keyBroken() -> clearLocked(context, \"keybroken\")"))
        assertEquals(2, Regex("""notOpened\(context, e\)""").findAll(glue).count())
        // A cancellation is never swallowed by the attempt.
        assertTrue(glue.contains("} catch (e: CancellationException) {\n            throw e"))
        // The motion fire's time reaches the scan rule; a fire arms the teaching fix.
        assertEquals(5L, com.kidslauncher.mdm.zones.MotionFlags().moved(5).movedAtElapsedMs)
        assertTrue(source("zones/ZoneSensors.kt").contains("context?.let { com.kidslauncher.mdm.places.WifiPlaces.armTeach(it) }"))
        assertTrue(source("zones/ZoneSensors.kt").contains("com.kidslauncher.mdm.places.WifiPlaces.offerPassive(context, location)"))
    }
}
