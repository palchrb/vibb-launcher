package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.zones.Heartbeat
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.STILL_CEILING_MS
import com.kidslauncher.mdm.zones.heartbeatDue
import com.kidslauncher.mdm.zones.stillCeilingPassed
import com.kidslauncher.mdm.zones.stillVouched
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 134 revision 4 (D20-D25), pure: the connected router as a still witness - the note's rows 13-19 (the Jelly
 * Star's day, an unsettled AP, the level 20 dB off, a redacted BSSID, after a move, the mismatch veto, the 24-h
 * ceiling) and the other reasons. Documentation MACs (00:00:5e:00:53:xx), a fake key.
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

    private fun read(c: Connected?, read: LinkRead = if (c != null) LinkRead.READ else LinkRead.NONE, answering: List<ScanAp> = emptyList()) =
        linkAp(read, c, keys, answering)

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
        ceilingPassed: Boolean = false,
    ) = stillWitness(motion, vouch, inBoot, link, now, rows, nowMin, ceilingPassed)

    @Test
    fun `row 13 - the Jelly Star's day - connected to the home router while the list is poor, renewed`() {
        val link = link(-42)!!
        // 15:31: the cap try's list holds one AP heard before the request, not the router - inconclusive (D2), so the
        // print and the scan decide nothing; the connection still witnesses (its level the connection's own, `link`).
        val request = ScanRequest(50_000_000L, ScanAsk.ANSWERED)
        val poor = listOf(ScanAp("00:00:5e:00:55:04", "Naboen", null, 2437, -80, 49_000_000L))
        val answer = scanAnswer(poor, request, router, 0L, 50_009_000L)
        assertEquals(Inconclusive.OLD, answer.inconclusive)
        val now = read(home(rssi = -45), answering = answer.answering)
        assertFalse(now.ap!!.fresh)
        assertEquals(StillWitness.RENEWS, witness(now, link = link))
        // 22:25: two APs, still not the router: the same.
        assertEquals(StillWitness.RENEWS, witness(read(home(rssi = -47)), link = link))
    }

    @Test
    fun `code QA H1 - the answering scan's level of the connected BSSID wins over the connection's`() {
        // The connection's level is stale (-45, polled before the screen went off); the fresh scan hears it at -63.
        val fresh = listOf(ScanAp(router.uppercase(), "Hjemme", null, 5180, -63, 50_005_000L), ScanAp(router5, "Hjemme", null, 2437, -40, 50_005_000L))
        val now = read(home(rssi = -45), answering = fresh)
        assertTrue(now.ap!!.fresh)
        assertEquals(-63, now.ap!!.rssi)
        assertEquals(StillWitness.LEVEL, witness(now))
        // Not in the answer: the connection's level, marked not fresh.
        assertEquals(-45, read(home(rssi = -45), answering = fresh.drop(1)).ap!!.rssi)
        // The vouch's link takes the scan's level the same way.
        assertEquals(-63, vouchLinkOf(vouchTime, boot, now.ap, moving = false)!!.rssi)
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
    fun `row 15 - the level 20 dB off is no renewal, 15 dB is - and an unknown level refuses`() {
        assertEquals(StillWitness.LEVEL, witness(read(home(rssi = -62))))
        assertEquals(StillWitness.STRONGER, witness(read(home(rssi = -22))))
        assertEquals(StillWitness.RENEWS, witness(read(home(rssi = -57))))
        assertEquals(StillWitness.RENEWS, witness(read(home(rssi = -27))))
        assertEquals(StillWitness.LEVEL, witness(read(home(rssi = -58))))
        // Code QA L2: WifiInfo.INVALID_RSSI (-127) is no level - no renewal, and a vouch read with it leaves no link.
        assertEquals(StillWitness.NO_LEVEL, witness(read(home(rssi = -127))))
        assertEquals(StillWitness.NO_LEVEL, witness(read(home(rssi = 3))))
        assertNull(link(rssi = -127))
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
        // Code QA L1: the vouch's AP vetoes it when it renews, or is more than 15 dB stronger than at the vouch.
        assertTrue(mismatchVetoed(false, witness(read(home(rssi = -45)))))
        assertTrue(mismatchVetoed(false, witness(read(home(rssi = -22)))))
        // Weaker by more than 15 dB (the far edge of its reach): it acts.
        assertFalse(mismatchVetoed(false, witness(read(home(rssi = -62)))))
        // A renewal vetoes it as before (code QA M1).
        assertTrue(mismatchVetoed(true, null))
        // Not connected, unsettled, another binding, no link, an unknown level: it acts.
        assertFalse(mismatchVetoed(false, witness(read(null))))
        assertFalse(mismatchVetoed(false, witness(read(home()), rows = listOf(row(router, days = 1)))))
        assertFalse(mismatchVetoed(false, witness(read(home(id = 9)))))
        assertFalse(mismatchVetoed(false, witness(read(home()), link = null)))
        assertFalse(mismatchVetoed(false, witness(read(home(rssi = -127)))))
        assertFalse(mismatchVetoed(false, null))
        // And with a readable BSSID a fresh answer without the connected AP is inconclusive anyway (D2).
        val fresh = listOf("00:00:5e:00:56:01", "00:00:5e:00:57:01", "00:00:5e:00:58:01").map { ScanAp(it, "Nabo", null, 5180, -60, 50_005_000L) }
        assertEquals(Inconclusive.NO_CONNECTED, scanAnswer(fresh, ScanRequest(50_000_000L, ScanAsk.ANSWERED), router, 0L, 50_009_000L).inconclusive)
    }

    @Test
    fun `row 19 - the witness never moves the 24-h ceiling`() {
        val interval = 10 * minute
        val vouch = vouchTime
        // On its router all day: the witness renews every 4 h (the last at 04:30, 21 h on) - the 4 h count from the renewal, the ceiling
        // from the vouch (code QA M1).
        val renewed = vouch + 21 * 60 * minute
        val atCeiling = vouch + STILL_CEILING_MS
        assertFalse(stillCeilingPassed(atCeiling - 1, vouch))
        assertTrue(stillCeilingPassed(atCeiling, vouch))
        assertFalse(stillCeilingPassed(vouch - minute, vouch))
        // Under the ceiling a renewal's 4 h is STILL; at the ceiling it isn't - the beat tries (FIX), and the witness
        // refuses (`ceiling`), so the try is an ordinary one.
        assertEquals(Heartbeat.STILL, heartbeatDue(atCeiling - minute, 0L, interval, Motion.STILL, renewed, null, renewed, 0L, 0, vouch))
        assertEquals(Heartbeat.FIX, heartbeatDue(atCeiling, 0L, interval, Motion.STILL, renewed, null, renewed, 0L, 0, vouch))
        assertEquals(StillWitness.CEILING, witness(read(home()), ceilingPassed = true))
        // That try failed (capFails 1): QUIET between the next tries, no `still`.
        assertEquals(Heartbeat.QUIET, heartbeatDue(atCeiling + interval / 2, 0L, interval, Motion.STILL, renewed, null, atCeiling, 0L, 1, vouch))
        assertTrue(stillVouched(Motion.STILL, atCeiling - minute, renewed, 0, ceilingFromMs = vouch))
        assertFalse(stillVouched(Motion.STILL, atCeiling, renewed, 0, ceilingFromMs = vouch))
        assertFalse(stillVouched(Motion.STILL, atCeiling + interval, renewed, 1, ceilingFromMs = vouch))
        // A zone link's or an Apple fix's renewal moves both (the ceiling anchor is the later of it and the vouch).
        assertEquals(Heartbeat.STILL, heartbeatDue(atCeiling, 0L, interval, Motion.STILL, renewed, null, renewed, 0L, 0, renewed))
        // Without a separate anchor (the default) nothing changes from before.
        assertEquals(Heartbeat.STILL, heartbeatDue(atCeiling + interval, 0L, interval, Motion.STILL, atCeiling, null, atCeiling, 0L, 0))
    }

    @Test
    fun `the binding, the AP, the vouch and the boot`() {
        // Design 131's binding: open/OWE, metered, an unknown saved-network id; another binding than at the vouch.
        assertEquals(StillWitness.BINDING, witness(read(home(security = LinkSecurity.OTHER))))
        assertEquals(StillWitness.BINDING, witness(read(home(unmetered = false))))
        assertEquals(StillWitness.BINDING, witness(read(home(id = -1))))
        assertEquals(StillWitness.BINDING, witness(read(home(id = 9))))
        assertEquals(StillWitness.BINDING, witness(read(home(security = LinkSecurity.EAP))))
        // WPA2 and WPA3 personal are one class (design 131): the router moved to WPA3 keeps its binding.
        assertEquals(StillWitness.RENEWS, witness(read(home(security = LinkSecurity.SAE))))
        assertEquals(StillWitness.RENEWS, witness(read(home(security = LinkSecurity.EAP)), link = link(c = home(security = LinkSecurity.EAP))))
        // Code QA M2: the same AP only - the router's other band and a mesh node, settled or not, are not the vouch's.
        assertEquals(StillWitness.OTHER, witness(read(home(bssid = router5, rssi = -50))))
        assertEquals(StillWitness.OTHER, witness(read(home(bssid = meshNode)), rows = settledRows + row(meshNode)))
        // The link belongs to its vouch and its boot.
        assertEquals(StillWitness.NO_LINK, witness(read(home()), link = link(at = vouchTime - 60 * minute)))
        assertEquals(StillWitness.NO_LINK, witness(read(home()), link = link(inBoot = boot - 1)))
        assertEquals(StillWitness.NO_VOUCH, witness(read(home()), vouch = null))
        // An MLO router: another link BSSID of the same MLD address is the same AP; its device is the MLD address.
        val mld = "00:00:5e:00:53:f0"
        val mloRows = listOf(row(router, mld = mld))
        val mloLink = link(c = home(mld = mld))
        assertEquals(StillWitness.RENEWS, witness(read(home(bssid = "00:00:5e:00:59:01", mld = mld)), rows = mloRows, link = mloLink))
        assertEquals(StillWitness.OTHER, witness(read(home(bssid = "00:00:5e:00:59:01", mld = "00:00:5e:00:59:f0")), rows = mloRows, link = mloLink))
        assertEquals(StillWitness.UNSETTLED, witness(read(home(mld = mld)), rows = settledRows, link = mloLink))
        assertEquals("the connected AP", Renewal.CONNECTED.phrase)
    }

    private fun source(path: String) = File("src/main/java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `source - beside the zone link, the vouch's link, the ceiling, the sync on every Regularly phone`() {
        val glue = source("places/WifiPlaces.kt")
        val sync = source("server/MdmSyncWorker.kt")
        // D24: in LEARNED_FIRST, after the zone link and before GPS, only with no learned fix judged.
        val link = glue.indexOf("judged == null && renews(app, store, snap.await(), ask) -> ask.vouched.also { ask.renewed = true }")
        val witness = glue.indexOf("judged == null && store != null && witness(app, store, snap.await(), ask) == StillWitness.RENEWS -> ask.vouched.also {")
        assertTrue(link > 0 && witness > link && witness < glue.indexOf("keep(gps(GPS_MS), early.await())", witness))
        assertTrue(glue.contains("ask.renewedBy = Renewal.CONNECTED"))
        // D20.1: the vouch's link from the attempt's last read (its scan's level first), in memory, cleared with the table.
        assertTrue(glue.contains("ask.linkAp = store?.let { linkAp(ended.link, ended.connected, it.keys, ended.answer?.answering.orEmpty()).ap }"))
        assertTrue(glue.contains("val now = linkAp(snap.link, snap.connected, store.keys, snap.answer?.answering.orEmpty())"))
        assertTrue(glue.contains("vouchLink = vouchLinkOf(fresh.timeMs, BootClock.bootCount(), ask.linkAp, moving)"))
        assertTrue(Regex("""vouchLink = null\s+lastLearned = null""").containsMatchIn(glue))
        // Code QA H1: the log says whether the level was fresh, and the screen's state (PlacesTest's Log check covers it).
        assertTrue(glue.contains("level=\${if (ap.fresh) \"scan\" else \"link\"} screen=\$screen"))
        assertTrue(sync.contains("val renewable = if (stillOn && beat == Heartbeat.FIX && motion == Motion.STILL) lastFreshQueued else null"))
        assertTrue(sync.contains("Log.i(ZONE_LOG_TAG, \"Location heartbeat: STILL renewed by \" + places.renewedBy.phrase)"))
        // Code QA M1: the ceiling's own anchor - a connected-AP renewal never moves it.
        assertTrue(sync.contains("val ceilingFrom = if (stillOn) maxOf(lastFreshFix, ZoneWatch.ceilingRenewedMs(context)) else lastOwnFix"))
        assertTrue(sync.contains("ZoneWatch.stampVouchRenewed(context, now, movesCeiling = places.renewedBy != com.kidslauncher.mdm.places.Renewal.CONNECTED)"))
        assertTrue(sync.contains("ceilingFromMs = ceilingFrom,"))
        assertTrue(sync.contains("reportStill(stillOn, action, motion, now, stillFrom, capFails, cachedIsVouch, ceilingFrom)"))
        assertTrue(sync.contains("stillVouched(motion, now, stillFrom, capFailsNow, ceilingFromMs = ceilingFrom)"))
        assertTrue(sync.contains("stillCeilingPassed = stillOn && com.kidslauncher.mdm.zones.stillCeilingPassed(now, ceilingFrom),"))
        assertTrue(source("zones/ZoneWatch.kt").contains(".remove(KEY_RENEWED).remove(KEY_CEILING_RENEWED)"))
    }
}
