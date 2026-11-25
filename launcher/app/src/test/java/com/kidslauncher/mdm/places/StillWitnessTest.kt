package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.Motion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 134 revision 4 (D20-D25), pure: the connected router as a still witness - the note's rows 13-18 (the Jelly
 * Star's day, an unsettled AP, the level 20 dB off, a redacted BSSID, after a move, the mismatch veto) and the other
 * reasons. Documentation MACs (00:00:5e:00:53:xx), a fake key.
 */
class StillWitnessTest {
    private val keys = PlaceKeys("test-key-not-a-secret".toByteArray())
    private val minute = 60_000L
    private val boot = 7

    private val router = "00:00:5e:00:53:01"
    private val router5 = "00:00:5e:00:53:02"
    private val meshNode = "00:00:5e:00:54:01"

    /** The vouch at 07:30 and the minutes the rows count in. */
    private val vouchTime = 1_800_000_000_000L - 1_800_000_000_000L % (24 * 60 * minute) + (7 * 60 + 30) * minute
    private val nowMin = (vouchTime / minute).toInt() + 8 * 60

    private fun home(bssid: String = router, rssi: Int = -42, security: LinkSecurity = LinkSecurity.PSK, id: Int = 7, unmetered: Boolean = true, mld: String? = null) =
        Connected(bssid, "Hjemme", rssi, 5180, unmetered, security, id, mld)

    /** An own Wi-Fi row of [bssid] (its device the first five octets, or [mld]). */
    private fun row(bssid: String, days: Int = 3, occasions: Int = 4, moves: Int = 0, blockedUntilMin: Int = 0, mld: String? = null) =
        PlaceRow(RowKind.WIFI, keys.ap(bssid), device = keys.device(bssid, mld), network = keys.network("Hjemme", ""), days = days, occasions = occasions, moves = moves, blockedUntilMin = blockedUntilMin)

    private val settledRows = listOf(row(router), row(router5))

    private fun read(c: Connected?, read: LinkRead = if (c != null) LinkRead.READ else LinkRead.NONE) = linkAp(read, c, keys)

    /** The link the 07:30 fix left: connected to the home router at [rssi]. */
    private fun link(rssi: Int = -42, at: Long = vouchTime, inBoot: Int = boot, c: Connected = home(rssi = rssi)) =
        vouchLinkOf(at, inBoot, read(c).ap, moving = false)

    private fun witness(
        now: LinkApRead,
        motion: Motion = Motion.STILL,
        rows: List<PlaceRow> = settledRows,
        link: VouchLink? = link(),
        vouch: Long? = vouchTime,
        inBoot: Int = boot,
    ) = stillWitness(motion, vouch, inBoot, link, now, rows, nowMin)

    @Test
    fun `row 13 - the Jelly Star's day - connected to the home router while the list is poor, renewed`() {
        val link = link(-42)!!
        // 15:31: the cap try's list holds one AP heard before the request, not the router - inconclusive (D2), so the
        // print and the scan decide nothing; the connection still witnesses.
        val request = ScanRequest(50_000_000L, ScanAsk.ANSWERED)
        val poor = listOf(ScanAp("00:00:5e:00:55:04", "Naboen", null, 2437, -80, 49_000_000L))
        assertEquals(Inconclusive.OLD, scanAnswer(poor, request, router, 0L, 50_009_000L).inconclusive)
        assertEquals(StillWitness.RENEWS, witness(read(home(rssi = -45)), link = link))
        // 22:25: two APs, still not the router: the same.
        assertEquals(StillWitness.RENEWS, witness(read(home(rssi = -47)), link = link))
        // Its other band (one device, design 125 D5) witnesses too, against the same level.
        assertEquals(StillWitness.RENEWS, witness(read(home(bssid = router5, rssi = -50)), link = link))
    }

    @Test
    fun `row 14 - an unsettled AP never witnesses`() {
        assertEquals(StillWitness.UNSETTLED, witness(read(home()), rows = listOf(row(router, days = 1))))
        assertEquals(StillWitness.UNSETTLED, witness(read(home()), rows = listOf(row(router, occasions = 1))))
        assertEquals(StillWitness.UNSETTLED, witness(read(home()), rows = listOf(row(router), row(router5, moves = 1))))
        assertEquals(StillWitness.UNSETTLED, witness(read(home()), rows = listOf(row(router, blockedUntilMin = nowMin + 60))))
        assertEquals(StillWitness.UNSETTLED, witness(read(home()), rows = emptyList()))
        // An Apple row never makes it settled.
        val apple = PlaceRow(RowKind.APPLE, keys.ap(router), device = keys.device(router, null), days = 9, occasions = 9)
        assertEquals(StillWitness.UNSETTLED, witness(read(home()), rows = listOf(apple)))
    }

    @Test
    fun `row 15 - the level 20 dB off is no renewal, 15 dB is`() {
        assertEquals(StillWitness.LEVEL, witness(read(home(rssi = -62))))
        assertEquals(StillWitness.LEVEL, witness(read(home(rssi = -22))))
        assertEquals(StillWitness.RENEWS, witness(read(home(rssi = -57))))
        assertEquals(StillWitness.LEVEL, witness(read(home(rssi = -58))))
    }

    @Test
    fun `row 16 - a redacted, unreadable or locally administered BSSID never witnesses`() {
        assertEquals(StillWitness.UNREADABLE, witness(read(home(bssid = "02:00:00:00:00:00"))))
        assertEquals(StillWitness.UNREADABLE, witness(read(null, LinkRead.UNREADABLE)))
        assertEquals(StillWitness.UNREADABLE, witness(read(home(bssid = "not-a-mac"))))
        assertEquals(StillWitness.LOCAL, witness(read(home(bssid = "02:00:5e:00:53:09"))))
        assertEquals(StillWitness.NONE, witness(read(null)))
        // A vouch whose attempt read none leaves no link.
        assertNull(vouchLinkOf(vouchTime, boot, read(home(bssid = "02:00:00:00:00:00")).ap, moving = false))
        assertEquals(StillWitness.NO_LINK, witness(read(home()), link = null))
    }

    @Test
    fun `row 17 - after a move the connected AP is no witness`() {
        for (m in listOf(Motion.MOVED, Motion.STILL_SINCE_TRY, Motion.NO_SENSOR)) {
            assertEquals(m.name, StillWitness.MOVED, witness(read(home()), motion = m))
        }
        // A vouch taken on the move leaves no link (QA H3's car router).
        assertNull(vouchLinkOf(vouchTime, boot, read(home()).ap, moving = true))
    }

    @Test
    fun `row 18 - the mismatch veto`() {
        val settled = setOf("r-a", "m-a", "n1-a", "n2-a")
        fun heard(device: String, dbm: Int) = Heard("$device-a", device, "net-$device", Band.G5, dbm, 0L)
        val print = takePrint(vouchTime, 1_000L, boot, 2_000L, (vouchTime / minute).toInt(), listOf(heard("r", -40), heard("m", -55), heard("n1", -70), heard("n2", -78)), settled)!!
        val elsewhere = printScan(listOf(heard("x", -50), heard("y", -60), heard("z", -70)), settled)
        assertEquals(MismatchClause.WIFI, printMismatch(print, elsewhere, false))
        // The connected, bound, settled AP of the vouch vetoes it - renewing, or refused by its level alone.
        assertTrue(mismatchVetoed(false, witness(read(home(rssi = -45)))))
        assertTrue(mismatchVetoed(false, witness(read(home(rssi = -62)))))
        // A renewal vetoes it as before (code QA M1).
        assertTrue(mismatchVetoed(true, null))
        // Not connected, unsettled, another binding, no link: it acts.
        assertFalse(mismatchVetoed(false, witness(read(null))))
        assertFalse(mismatchVetoed(false, witness(read(home()), rows = listOf(row(router, days = 1)))))
        assertFalse(mismatchVetoed(false, witness(read(home(id = 9)))))
        assertFalse(mismatchVetoed(false, witness(read(home()), link = null)))
        assertFalse(mismatchVetoed(false, null))
        // And with a readable BSSID a fresh answer without the connected AP is inconclusive anyway (D2).
        val fresh = listOf("00:00:5e:00:56:01", "00:00:5e:00:57:01", "00:00:5e:00:58:01").map { ScanAp(it, "Nabo", null, 5180, -60, 50_005_000L) }
        assertEquals(Inconclusive.NO_CONNECTED, scanAnswer(fresh, ScanRequest(50_000_000L, ScanAsk.ANSWERED), router, 0L, 50_009_000L).inconclusive)
    }

    @Test
    fun `the binding, the device, the vouch and the boot`() {
        // Design 131's binding: open/OWE, metered, an unknown saved-network id; another binding than at the vouch.
        assertEquals(StillWitness.BINDING, witness(read(home(security = LinkSecurity.OTHER))))
        assertEquals(StillWitness.BINDING, witness(read(home(unmetered = false))))
        assertEquals(StillWitness.BINDING, witness(read(home(id = -1))))
        assertEquals(StillWitness.BINDING, witness(read(home(id = 9))))
        assertEquals(StillWitness.BINDING, witness(read(home(security = LinkSecurity.EAP))))
        // WPA2 and WPA3 personal are one class (design 131): the router moved to WPA3 keeps its binding.
        assertEquals(StillWitness.RENEWS, witness(read(home(security = LinkSecurity.SAE))))
        // WPA3 at the vouch and now, and an enterprise network on its settled device, witness.
        assertEquals(StillWitness.RENEWS, witness(read(home(security = LinkSecurity.SAE)), link = link(c = home(security = LinkSecurity.SAE))))
        assertEquals(StillWitness.RENEWS, witness(read(home(security = LinkSecurity.EAP)), link = link(c = home(security = LinkSecurity.EAP))))
        // Another device (a mesh node), settled or not: no level for it.
        assertEquals(StillWitness.OTHER, witness(read(home(bssid = meshNode)), rows = settledRows + row(meshNode)))
        // The link belongs to its vouch and its boot.
        assertEquals(StillWitness.NO_LINK, witness(read(home()), link = link(at = vouchTime - 60 * minute)))
        assertEquals(StillWitness.NO_LINK, witness(read(home()), link = link(inBoot = boot - 1)))
        assertEquals(StillWitness.NO_VOUCH, witness(read(home()), vouch = null))
        // An MLO router: its device is the MLD address, as a scan result's.
        val mld = "00:00:5e:00:53:f0"
        val mloRows = listOf(row(router, mld = mld))
        val mloLink = link(c = home(mld = mld))
        assertEquals(StillWitness.RENEWS, witness(read(home(bssid = "00:00:5e:00:59:01", mld = mld)), rows = mloRows, link = mloLink))
        assertEquals(StillWitness.UNSETTLED, witness(read(home(mld = mld)), rows = settledRows, link = mloLink))
        assertEquals("the connected AP", Renewal.CONNECTED.phrase)
    }

    private fun source(path: String) = File("src/main/java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `source - beside the zone link, the vouch's link, the sync on every Regularly phone`() {
        val glue = source("places/WifiPlaces.kt")
        val sync = source("server/MdmSyncWorker.kt")
        // D24: in LEARNED_FIRST, after the zone link and before GPS, only with no learned fix judged.
        val link = glue.indexOf("judged == null && renews(app, store, snap.await(), ask) -> ask.vouched.also { ask.renewed = true }")
        val witness = glue.indexOf("judged == null && store != null && witness(app, store, snap.await(), ask) == StillWitness.RENEWS -> ask.vouched.also {")
        assertTrue(link > 0 && witness > link && witness < glue.indexOf("keep(gps(GPS_MS), early.await())", witness))
        assertTrue(glue.contains("ask.renewedBy = Renewal.CONNECTED"))
        // D20.1: the vouch's link from the attempt's last read, kept in memory with each fresh fix, cleared with the table.
        assertTrue(glue.contains("ask.linkAp = store?.let { linkAp(ended.link, ended.connected, it.keys).ap }"))
        assertTrue(glue.contains("vouchLink = vouchLinkOf(fresh.timeMs, BootClock.bootCount(), ask.linkAp, moving)"))
        assertTrue(Regex("""vouchLink = null\s+lastLearned = null""").containsMatchIn(glue))
        // The witness logs its reason and levels, never the BSSID (PlacesTest's Log check covers the line).
        assertTrue(glue.contains("Log.i(LOG_TAG, \"connected witness no reason=\${w.wire}\$diff\")"))
        assertTrue(sync.contains("val renewable = if (stillOn && beat == Heartbeat.FIX && motion == Motion.STILL) lastFreshQueued else null"))
        assertTrue(sync.contains("Log.i(ZONE_LOG_TAG, \"Location heartbeat: STILL renewed by \" + places.renewedBy.phrase)"))
    }
}
