package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.dto.PolicyZone
import com.kidslauncher.mdm.server.dto.ZoneNetwork
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 138: a zone's Wi-Fi - the parent's confirmation and the learned link bound to the router, the neighbours'
 * agreement at every use (D8, with the one-neighbour widening), the cell veto's hook, the strikes by router with two
 * fixes (D7), the Wi-Fi teach (D6) and the report's half (D3). Documentation addresses only.
 */
class ZoneLinksTest {
    private val keys = PlaceKeys("test-key-not-a-secret".toByteArray())
    private val moving = MovingSsids.parse(File("src/main/assets/places/moving_ssids.json").readText())
    private val lat0 = 59.9
    private val lng0 = 10.75
    private val day = 1440
    private fun north(m: Double) = lat0 + m / 111_195.0

    private val router = "00:00:5e:00:53:01"
    private val home = PolicyZone(4, lat0, lng0, 150, listOf(ZoneNetwork(7, "Net-A", router)))
    private val zones = listOf(LinkZone(home, keys.zone(home)))
    private val confirmed = confirmedAps(listOf(home), keys)

    /** Neighbour [i]'s router: its own address prefix. */
    private fun nb(i: Int) = "00:00:5e:00:%02x:01".format(0x60 + i)

    private fun conn(bssid: String = router, ssid: String = "Net-A", security: LinkSecurity = LinkSecurity.PSK, id: Int = 7, unmetered: Boolean = true, mld: String? = null) =
        Connected(bssid, ssid, -45, 5180, unmetered, security, id, mld)

    /** A trusted own row for [bssid] named [ssid], [m] metres north of the zone's centre. */
    private fun row(bssid: String, ssid: String, m: Double, range: Float = 60f): PlaceRow {
        val h = hear(listOf(ScanAp(bssid, ssid, null, 2437, -60, 0)), null, keys, moving, emptyMap(), 0).heard.single()
        return PlaceRow(RowKind.WIFI, h.key, h.device, h.network, h.band, north(m), lng0, 5f, range, 5, 3, 20000, 0, 0)
    }

    private val neighboursHome = listOf(row(nb(1), "Nabo1", 10.0), row(nb(2), "Nabo2", -15.0), row(nb(3), "Nabo3", 20.0))
    private val table = neighboursHome + row(router, "Net-A", 0.0)

    private fun view(c: Connected?, vararg aps: Pair<String, String>) =
        scanView(hear(aps.map { ScanAp(it.first, it.second, null, 2437, -55, 100_000) }, c, keys, moving, emptyMap(), 100_000), 100_000, 0)!!

    private val homeView = view(conn(), nb(1) to "Nabo1", nb(2) to "Nabo2", nb(3) to "Nabo3", router to "Net-A")
    private val inside = LearnedFix(north(10.0), lng0, 40.0, 3, 0, true, emptySet())

    private fun check(
        c: Connected = conn(),
        fix: LearnedFix = inside,
        rows: List<PlaceRow> = table,
        v: ScanView = homeView,
        struck: Set<Long> = emptySet(),
        away: Boolean = false,
    ): LinkBy? {
        val r = routerOf(c, keys)
        return linkCheck(fix, r, linkConn(c, keys), rows, zones, 0, confirmed, struck, r?.let { restWithout(v, rows, 0, it) }) { away }
    }

    @Test
    fun `a confirmation - its router by five octets or its multi-link address, and the name`() {
        assertEquals(LinkBy.CONFIRMED, check())
        assertEquals("the same router's other band", LinkBy.CONFIRMED, check(conn(bssid = "00:00:5e:00:53:02")))
        assertEquals("M4: no network id needed", LinkBy.CONFIRMED, check(conn(id = -1)))
        assertEquals("WPA3", LinkBy.CONFIRMED, check(conn(security = LinkSecurity.SAE)))
        assertNull("another router of the same name", check(conn(bssid = "00:00:5e:00:54:01")))
        assertNull("another name at the router", check(conn(ssid = "Net-B")))
        assertNull("struck", check(struck = setOf(7L)))
        assertNull("open or OWE", check(conn(security = LinkSecurity.OTHER)))
        assertNull("enterprise is never confirmed", check(conn(security = LinkSecurity.EAP)))
        assertNull("metered", check(conn(unmetered = false)))
        assertNull("the cell veto", check(away = true))
        // The multi-link address: a confirmation with one matches another link of that router.
        val mld = "00:00:5e:00:55:10"
        val z = PolicyZone(4, lat0, lng0, 150, listOf(ZoneNetwork(8, "Net-A", "00:00:5e:00:56:01", mld)))
        val r = routerOf(conn(bssid = "00:00:5e:00:57:01", mld = mld), keys)!!
        assertTrue(confirmedFor(r, LinkZone(z, keys.zone(z)), confirmedAps(listOf(z), keys), emptySet()) != null)
    }

    @Test
    fun `M1 - a confirmation takes the fix's centre inside, a learned link surely inside`() {
        val straddling = inside.copy(lat = north(140.0))
        assertEquals(LinkBy.CONFIRMED, check(fix = straddling))
        assertNull(check(fix = inside.copy(lat = north(160.0))))
    }

    @Test
    fun `D8 - the neighbours must agree without the router, the travel router`() {
        // Elsewhere: the neighbours there place the phone 3 km away - no link, whatever the router.
        val far = listOf(row(nb(4), "Hytte1", 3000.0), row(nb(5), "Hytte2", 3010.0), row(nb(6), "Hytte3", 2990.0))
        val cabin = view(conn(), nb(4) to "Hytte1", nb(5) to "Hytte2", nb(6) to "Hytte3", router to "Net-A")
        assertNull(check(rows = table + far, v = cabin))
        // Its guest name on the same device is the router too: left out.
        val guest = view(conn(), router to "Net-A", "00:00:5e:00:53:05" to "Net-A-gjest", nb(9) to "Ukjent")
        val r = routerOf(conn(), keys)!!
        assertEquals(Rest.Nothing, restWithout(guest, table + row("00:00:5e:00:53:05", "Net-A-gjest", 0.0), 0, r))
        // Nothing else placed (an unknown router says nothing): a confirmation stands, unless the router's rows moved.
        // The detached house through the real hearing: the router's two names alone make no scan view, no Wi-Fi fix -
        // the single-router fix (`routerFix`, rev 3) is its path; see its own test.
        val alone = hear(listOf(ScanAp(router, "Net-A", null, 5180, -50, 100_000), ScanAp("00:00:5e:00:53:05", "Net-A-gjest", null, 5180, -52, 100_000)), conn(), keys, moving, emptyMap(), 100_000)
        assertNull(scanView(alone, 100_000, 0))
        assertEquals(Rest.Nothing, restWithout(ScanView(alone.heard, true), table, 0, r))
        val lone = view(conn(), router to "Net-A", nb(9) to "Ukjent", nb(10) to "Ukjent2")
        assertEquals(LinkBy.CONFIRMED, check(v = lone))
        val moved = table.map { if (it.network == keys.network("Net-A", "")) it.copy(moves = 1) else it }
        assertNull(check(v = lone, rows = moved))
        // One placed neighbour: it must lie within the radius and its range.
        val one = view(conn(), router to "Net-A", nb(1) to "Nabo1", nb(9) to "Ukjent")
        assertTrue(restWithout(one, table, 0, r) is Rest.One)
        assertEquals(LinkBy.CONFIRMED, check(v = one))
        val oneFar = table.map { if (it.network == keys.network("Nabo1", "")) it.copy(lat = north(400.0)) else it }
        assertNull(check(v = one, rows = oneFar))
    }

    @Test
    fun `QA M1 - the neighbours' centre needs no majority and no accuracy cap`() {
        // Three weak neighbours at home and two stronger networks nobody placed: learnedFix's majority and cap say no fix,
        // the centre without the router still stands inside.
        val weak = scanView(
            hear(
                listOf(
                    ScanAp(nb(1), "Nabo1", null, 2437, -88, 100_000), ScanAp(nb(2), "Nabo2", null, 2437, -89, 100_000),
                    ScanAp(nb(3), "Nabo3", null, 2437, -90, 100_000), ScanAp(nb(7), "Fremmed1", null, 2437, -40, 100_000),
                    ScanAp(nb(8), "Fremmed2", null, 2437, -41, 100_000), ScanAp(router, "Net-A", null, 5180, -50, 100_000),
                ),
                conn(), keys, moving, emptyMap(), 100_000,
            ),
            100_000, 0,
        )!!
        val r = routerOf(conn(), keys)!!
        val others = ScanView(weak.heard.filter { !r.owns(it) }, true)
        assertNull("the regular rules refuse it", learnedFix(others, table, 0))
        val rest = restWithout(weak, table, 0, r)
        assertTrue(rest is Rest.Fix)
        assertEquals(LinkBy.CONFIRMED, check(v = weak))
    }

    @Test
    fun `QA M2 - the router's other name on the same device holds its own learned link`() {
        val c5 = conn(bssid = "00:00:5e:00:53:02", ssid = "Net-A-5G")
        val unconfirmed = listOf(LinkZone(home.copy(networks = emptyList()), keys.zone(home)))
        var rows = table
        // Net-A's teaches put the router's row in; Net-A-5G is learned on its own LINK row.
        for ((min, today) in listOf(0 to 20000, 60 to 20000, day to 20001)) {
            rows = teachLinks(rows, unconfirmed, lat0, lng0, 10.0, linkConn(conn(), keys)!!.network, min, today, linkConn(conn(), keys)!!.binding, routers = routerOf(conn(), keys)!!.keys)
            rows = teachLinks(rows, unconfirmed, lat0, lng0, 10.0, linkConn(c5, keys)!!.network, min, today, linkConn(c5, keys)!!.binding, routers = routerOf(c5, keys)!!.keys)
        }
        assertEquals(1, rows.count { it.kind == RowKind.LINK_AP })
        val v = view(c5, nb(1) to "Nabo1", nb(2) to "Nabo2", nb(3) to "Nabo3", "00:00:5e:00:53:02" to "Net-A-5G")
        val now = day + 10
        val rc5 = routerOf(c5, keys)!!
        assertEquals(LinkBy.LEARNED, linkCheck(inside, rc5, linkConn(c5, keys), rows, unconfirmed, now, emptyList(), emptySet(), restWithout(v, rows, now, rc5)) { false })
    }

    @Test
    fun `rev 3 - the single-router fix`() {
        val c = conn()
        val r = routerOf(c, keys)!!
        fun heard(vararg aps: Pair<String, String>) = hear(aps.map { ScanAp(it.first, it.second, null, 2437, -55, 100_000) }, c, keys, moving, emptyMap(), 100_000).heard
        val home0 = lat0 to lng0
        fun fix(
            rssi: Int? = -45,
            h: List<Heard> = heard(router to "Net-A"),
            rows: List<PlaceRow> = table,
            struck: Set<Long> = emptySet(),
            away: Boolean = false,
            last: Pair<Double, Double>? = home0,
            conclusive: Boolean = true,
            impossible: Boolean = false,
        ) = routerFix(r, rssi, h, conclusive, zones, confirmed, struck, rows, 0, 100_000, last, { away }, { impossible })
        // Its own row (taught by satellites, range 60 m): the router's position, sqrt(30^2 + 25^2) at -45 dBm; a link fix
        // that never teaches.
        val own = fix()
        assertEquals("fix", own.reason)
        assertEquals(RouterPlace.OWN, own.place)
        assertEquals(lat0, own.fix!!.lat, 1e-6)
        assertEquals(sqrt(30.0 * 30.0 + 25.0 * 25.0), own.fix!!.accuracyM, 0.01)
        assertTrue(own.fix!!.router && own.fix!!.fresh)
        assertEquals(PROVIDER_WIFI, own.fix!!.provider)
        // The level: 50 m at -65, 150 m at -85 or weaker, a straight line between; no level from the scan is -85.
        assertEquals(50.0, routerAccuracyM(-65), 0.01)
        assertEquals(100.0, routerAccuracyM(-75), 0.01)
        assertEquals(150.0, routerAccuracyM(-95), 0.01)
        assertEquals(sqrt(30.0 * 30.0 + 150.0 * 150.0), fix(rssi = null).fix!!.accuracyM, 0.01)
        // No own row: Apple's position for its address, else the zone's centre, sqrt(r^2 + level^2) - never under r.
        val noOwn = neighboursHome
        val centre = fix(rows = noOwn)
        assertEquals(RouterPlace.ZONE, centre.place)
        assertEquals(sqrt(150.0 * 150.0 + 25.0 * 25.0), centre.fix!!.accuracyM, 0.01)
        // The continuity gate: the last reported fix's centre inside the zone - never brought home from elsewhere.
        assertEquals("continuity", fix(last = null).reason)
        assertEquals("continuity", fix(last = north(400.0) to lng0).reason)
        assertEquals("fix", fix(last = north(140.0) to lng0).reason)
        // Every veto.
        assertEquals("unconfirmed", fix(struck = setOf(7L)).reason)
        assertEquals("cell", fix(away = true).reason)
        assertEquals("jump", fix(impossible = true).reason)
        assertEquals("moved", fix(rows = table.map { if (it.network == keys.network("Net-A", "")) it.copy(moves = 1) else it }).reason)
        assertEquals("unconfirmed", routerFix(routerOf(conn(bssid = "00:00:5e:00:54:01"), keys), -45, heard(), true, zones, confirmed, emptySet(), table, 0, 0, home0, { false }).reason)
        // The neighbours' own fix: inside it agrees; outside it contradicts.
        assertEquals("fix", fix(h = heard(router to "Net-A", nb(1) to "Nabo1", nb(2) to "Nabo2", nb(3) to "Nabo3")).reason)
        val far = listOf(row(nb(4), "Hytte1", 3000.0), row(nb(5), "Hytte2", 3010.0), row(nb(6), "Hytte3", 2990.0))
        assertEquals("others", fix(h = heard(router to "Net-A", nb(4) to "Hytte1", nb(5) to "Hytte2", nb(6) to "Hytte3"), rows = table + far).reason)
        // Two placed networks outside the zone's radius and their range (no fix of their own, too far apart).
        val apart = listOf(row(nb(4), "Hytte1", 3000.0), row(nb(5), "Hytte2", -3000.0))
        assertEquals("outside", fix(h = heard(router to "Net-A", nb(4) to "Hytte1", nb(5) to "Hytte2"), rows = table + apart).reason)
        // One placed network far from the router: it disagrees.
        val farOne = table.map { if (it.network == keys.network("Nabo1", "")) it.copy(lat = north(900.0)) else it }
        assertEquals("disagree", fix(h = heard(router to "Net-A", nb(1) to "Nabo1"), rows = farOne).reason)
        assertEquals("fix", fix(h = heard(router to "Net-A", nb(1) to "Nabo1")).reason)
        // A conclusive scan of three or more other networks none of them placed near the zone; inconclusive, it says nothing.
        val strangers = heard(router to "Net-A", nb(7) to "Fremmed1", nb(8) to "Fremmed2", nb(9) to "Fremmed3")
        assertEquals("unplaced", fix(h = strangers).reason)
        assertEquals("fix", fix(h = strangers, conclusive = false).reason)
    }

    @Test
    fun `review 5 - after a restart, a router fix on an inconclusive scan is fresh and never withheld`() {
        val f = fix0()
        for (why in listOf(Inconclusive.NO_CONNECTED, Inconclusive.OLD, Inconclusive.UNANSWERED)) {
            // The heartbeat's FIX after a new process (MOVED, may withhold): a fresh link fix is kept, not withheld.
            assertFalse(withheld(mayWithhold(true, com.kidslauncher.mdm.zones.Motion.MOVED, 0), why, freshFix = wifiFresh(f, FixCheck.LINK)))
        }
        val keep = attemptKeep(AttemptPlan.TOGETHER, wifiVerdict(f, FixCheck.LINK, com.kidslauncher.mdm.zones.Motion.MOVED, null, Jump.NONE).step, f, FixCheck.LINK, null, release = true)
        assertEquals(Kept.WIFI, keep.kept)
        assertTrue(wifiFresh(f, keep.check))
    }

    private fun fix0() = routerFix(
        routerOf(conn(), keys), -50, hear(listOf(ScanAp(router, "Net-A", null, 5180, -50, 100_000)), conn(), keys, moving, emptyMap(), 100_000).heard,
        false, zones, confirmed, emptySet(), table, 0, 100_000, lat0 to lng0, { false },
    ).fix!!

    @Test
    fun `QA L4, L5, L7 - an inside teach clears a pending strike, a redacted MLD, a hex name`() {
        val r = routerOf(conn(), keys)!!
        val k = StrikeKey(zones.single().key, r.device)
        val (_, pending) = strikeStep(emptyMap(), setOf(k), 1_000_000)
        val inside = insideKeys(zones, lat0, lng0, 10.0, r, emptyList())
        assertTrue(k in inside)
        assertTrue(strikeStep(pending, setOf(k), 1_090_000, inside).first.isEmpty())
        assertNull(routerOf(conn(mld = "02:00:00:00:00:00"), keys)!!.mld)
        assertEquals("Net-A", zoneWifiOf(conn(mld = "00:00:00:00:00:00"), true)!!.ssid)
        assertNull(zoneWifiOf(conn(mld = "00:00:00:00:00:00"), true)!!.mld)
        assertNull(zoneWifiOf(conn().copy(hexName = true), true))
        assertFalse(routerOf(conn().copy(hexName = true), keys)!!.named)
    }

    @Test
    fun `QA L8 - judged's wiring - the remaining fix, the cell veto, the single-router fix never teaches`() {
        val glue = File("src/main/java/com/kidslauncher/mdm/places/WifiPlaces.kt").readText()
        assertTrue(glue.contains("scanView(hearing(context, store, snap, remember = false), nowElapsed, ask.movedAtElapsedMs)?.let { restWithout(it, rows, now, router) }"))
        assertTrue(glue.contains("val by = if (fix.router) LinkBy.CONFIRMED else linkCheck(fix, router, conn, rows, zones, now, confirmed, struck(context), rest) { z -> cellAway(snap.cells, rows, now, z, fix.accuracyM) }"))
        assertTrue(glue.contains("if (store != null && router != null && !fix.router) ask.teachLater ="))
        assertTrue(glue.contains("ask.teachLater?.takeIf { !disagreement.seen && ask.inconclusive == null && !ask.withheld }"))
        assertEquals(3, Regex("""learned\(\) \?: appleFix\(\) \?: routerOnly\(\)""").findAll(glue).count())
        assertTrue(glue.contains("val early = async { judged ?: judge(appleFix() ?: routerOnly()) }"))
        // The single-router fix's own vetoes: the cell, through the same check.
        assertTrue(glue.contains("away = { z -> cellAway(snap.cells, rows, now, z, routerAccuracyM(scanned ?: ROUTER_LEVEL_UNKNOWN_DBM)) },"))
        assertTrue(glue.contains("val last = LocateCommands.cachedQueued()?.let { it.latitude to it.longitude }"))
        assertTrue(glue.contains("impossible = { f -> jumpGate(f, gnss, TrustedClock.nowMs(), nowElapsed, BootClock.bootCount()) == Jump.IMPOSSIBLE },"))
    }

    @Test
    fun `the learned link - its routers join at inside teaches, it needs the neighbours, no exception`() {
        val c = conn(security = LinkSecurity.PSK)
        val lc = linkConn(c, keys)!!
        val r = routerOf(c, keys)!!
        val unconfirmed = listOf(LinkZone(home.copy(networks = emptyList()), keys.zone(home)))
        var rows = table
        for ((min, today) in listOf(0 to 20000, 60 to 20000, day to 20001)) {
            rows = teachLinks(rows, unconfirmed, lat0, lng0, 10.0, lc.network, min, today, lc.binding, routers = r.keys)
        }
        val now = day + 10
        fun learned(fix: LearnedFix = inside, v: ScanView = homeView, at: Connected = c) =
            linkCheck(fix, routerOf(at, keys), linkConn(at, keys), rows, unconfirmed, now, emptyList(), emptySet(), restWithout(v, rows, now, routerOf(at, keys)!!)) { false }
        assertEquals(LinkBy.LEARNED, learned())
        assertNull("another router of the same name", learned(at = conn(bssid = "00:00:5e:00:54:01")))
        assertNull("straddling", learned(fix = inside.copy(lat = north(140.0))))
        assertNull("nothing else placed: no exception", learned(v = view(c, router to "Net-A", nb(9) to "Ukjent", nb(10) to "Ukjent2")))
        assertTrue(routerLearned(rows, unconfirmed.single(), r, now))
        // A strike blocks the learned router; the link's counters stay.
        val (out, ids) = applyStrikes(rows, unconfirmed, emptyList(), setOf(StrikeKey(unconfirmed.single().key, r.device)), now)
        assertTrue(ids.isEmpty())
        assertFalse(routerLearned(out, unconfirmed.single(), r, now))
        assertTrue(out.any { it.kind == RowKind.LINK && it.linked(now) })
        // A row round-trips.
        val links = rows.filter { it.kind == RowKind.LINK || it.kind == RowKind.LINK_AP }
        assertEquals(links, decodeTable(encodeTable(links)))
        // L6: in use, its lastSeen moves at most once a day.
        val touched = touchRouter(rows, unconfirmed.single(), r, now + 2 * day)
        assertTrue(touched.filter { it.kind == RowKind.LINK_AP }.all { it.lastSeenMin == now + 2 * day })
        assertEquals(touched, touchRouter(touched, unconfirmed.single(), r, now + 2 * day + 60))
    }

    @Test
    fun `D7 - strikes by router, two teaching fixes at least a minute apart`() {
        val r = routerOf(conn(), keys)!!
        val zk = zones.single().key
        // Connected 400 m out (150 + 200 + 25 < 400): a candidate; heard at 2.4 GHz needs the band's 300 m.
        assertEquals(setOf(StrikeKey(zk, r.device)), strikeCandidates(zones, north(400.0), lng0, 25.0, r, emptyList(), confirmed, emptyList(), 0))
        assertTrue(strikeCandidates(zones, north(360.0), lng0, 25.0, r, emptyList(), confirmed, emptyList(), 0).isEmpty())
        val heard = listOf(listOf(r.device) to Band.G24)
        assertTrue(strikeCandidates(zones, north(400.0), lng0, 25.0, null, heard, confirmed, emptyList(), 0).isEmpty())
        assertEquals(1, strikeCandidates(zones, north(500.0), lng0, 25.0, null, heard, confirmed, emptyList(), 0).size)
        assertEquals(1, strikeCandidates(zones, north(400.0), lng0, 25.0, null, listOf(listOf(r.device) to Band.G5), confirmed, emptyList(), 0).size)
        // A same-named router of another prefix strikes nothing.
        val other = routerOf(conn(bssid = "00:00:5e:00:54:01"), keys)!!
        assertTrue(strikeCandidates(zones, north(900.0), lng0, 25.0, other, emptyList(), confirmed, emptyList(), 0).isEmpty())
        // One fix is pending; a second within a minute waits; one 90 s on strikes; after 2 h it starts over.
        val c = setOf(StrikeKey(zk, r.device))
        val (first, p1) = strikeStep(emptyMap(), c, 1_000_000)
        assertTrue(first.isEmpty())
        assertTrue(strikeStep(p1, c, 1_030_000).first.isEmpty())
        assertEquals(c, strikeStep(p1, c, 1_090_000).first)
        assertTrue(strikeStep(p1, c, 1_000_000 + STRIKE_GAP_MAX_MS + 1).first.isEmpty())
        assertEquals(setOf(7L), applyStrikes(emptyList(), zones, confirmed, c, 0).second)
    }

    @Test
    fun `D6 - a Wi-Fi teach needs a fresh unrefused unchecked fix, no disagreement, the neighbours surely inside`() {
        val z = zones.single()
        val rest = restWithout(homeView, table, 0, routerOf(conn(), keys)!!)
        assertTrue(rest is Rest.Fix)
        assertTrue(wifiOccasion(inside, WifiStep.USE, null, false, rest, z, false))
        assertFalse(wifiOccasion(inside.copy(fresh = false), WifiStep.USE, null, false, rest, z, false))
        assertFalse(wifiOccasion(inside, WifiStep.REFUSE, null, false, rest, z, false))
        assertFalse(wifiOccasion(inside, WifiStep.USE, FixCheck.LINK, false, rest, z, false))
        assertFalse(wifiOccasion(inside, WifiStep.USE, null, true, rest, z, false))
        assertFalse(wifiOccasion(inside, WifiStep.USE, null, false, rest, z, true))
        assertFalse(wifiOccasion(inside.copy(lat = north(140.0)), WifiStep.USE, null, false, rest, z, false))
        assertFalse(wifiOccasion(inside, WifiStep.USE, null, false, Rest.Nothing, z, false))
        // `only` teaches the zones the gate let through, and keeps every zone's rows.
        val lc = linkConn(conn(), keys)!!
        val school = PolicyZone(5, north(3000.0), lng0, 150)
        val both = zones + LinkZone(school, keys.zone(school))
        val taught = teachLinks(emptyList(), both, lat0, lng0, 10.0, lc.network, 0, 20000, lc.binding, routers = listOf("aa"), only = emptySet())
        assertTrue(taught.isEmpty())
    }

    @Test
    fun `the owner's case - an Apple fix at home, confirmed, GPS silent - is a fresh link fix`() {
        val apple = inside.copy(provider = PROVIDER_APPLE, source = PlaceSource.APPLE)
        assertEquals(LinkBy.CONFIRMED, check(fix = apple))
        assertEquals(WifiStep.USE, wifiVerdict(apple, FixCheck.LINK, com.kidslauncher.mdm.zones.Motion.MOVED, null, Jump.NONE).step)
        val keep = attemptKeep(AttemptPlan.TOGETHER, WifiStep.USE, apple, FixCheck.LINK, null, release = true)
        assertEquals(Kept.WIFI, keep.kept)
        assertEquals(FixCheck.LINK, keep.check)
        assertTrue(wifiFresh(apple, keep.check))
    }

    @Test
    fun `D3 - the report's network and struck ids`() {
        assertEquals("Net-A", zoneWifiOf(conn(), true)!!.ssid)
        assertNull("no zones", zoneWifiOf(conn(), false))
        assertNull(zoneWifiOf(conn(security = LinkSecurity.OTHER), true))
        assertNull(zoneWifiOf(conn(security = LinkSecurity.EAP), true))
        assertNull(zoneWifiOf(conn(unmetered = false), true))
        assertNull("redacted", zoneWifiOf(conn(bssid = "02:00:00:00:00:00"), true))
        assertNull("hidden", zoneWifiOf(conn(ssid = ""), true))
        assertEquals("00:00:5e:00:53:10", zoneWifiOf(conn(mld = "00:00:5E:00:53:10"), true)!!.mld)
        assertEquals(listOf(7L), struckInPolicy(setOf(7L, 99L), listOf(home)))
    }
}
