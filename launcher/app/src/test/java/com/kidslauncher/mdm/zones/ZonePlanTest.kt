package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.ZONES_CAPABILITY
import com.kidslauncher.mdm.server.dto.PolicyZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 64 step 2 on the phone: the fences' plan, the fence broadcast's verdict, the queue of fixes
 * waiting for a report, the heartbeat and the fix filter (the mock rule, the passive feed) - and,
 * by source, the glue's few load-bearing lines. The phone judges no zone, so there are no zone
 * vectors here (the server's `zone_vectors.json` is the server's alone).
 */
class ZonePlanTest {
    private val home = PolicyZone(7, 59.9139, 10.7522, 150)
    private val school = PolicyZone(9, 59.92, 10.76, 300)
    private val minute = 60_000L

    private fun record(zones: List<PolicyZone>, at: Long = 1_000) = FenceRecord(fenceFingerprint(zones), zones.map { it.id }, at)

    @Test
    fun `a new process registers every fence again, the same zones nothing`() {
        // First time: nothing stored, both added.
        assertEquals(FencePlan(emptyList(), listOf(home, school)), zoneFencePlan(listOf(school, home), true, null, freshProcess = true))
        val stored = record(listOf(home, school))
        // The same set in the same process: nothing (the order of the policy doesn't matter).
        assertTrue(zoneFencePlan(listOf(school, home), true, stored, freshProcess = false).isEmpty)
        // A new process (a reboot dropped them, an update may have cancelled our PendingIntents): all again.
        assertEquals(FencePlan(listOf(7L, 9L), listOf(home, school)), zoneFencePlan(listOf(home, school), true, stored, freshProcess = true))
    }

    @Test
    fun `a changed zone re-registers all, none removes all`() {
        val stored = record(listOf(home, school))
        val wider = home.copy(radiusM = 250)
        assertEquals(FencePlan(listOf(7L, 9L), listOf(wider, school)), zoneFencePlan(listOf(wider, school), true, stored, false))
        val moved = home.copy(latitude = 59.95)
        assertEquals(FencePlan(listOf(7L, 9L), listOf(moved, school)), zoneFencePlan(listOf(moved, school), true, stored, false))
        // Deleted down to one zone: ours out, the one left in.
        assertEquals(FencePlan(listOf(7L, 9L), listOf(school)), zoneFencePlan(listOf(school), true, stored, false))
        // [] (the last zone deleted, or Off / Only when asked) and not watching: every stored fence goes.
        assertEquals(FencePlan(listOf(7L, 9L), emptyList()), zoneFencePlan(emptyList(), true, stored, false))
        assertEquals(FencePlan(listOf(7L, 9L), emptyList()), zoneFencePlan(listOf(home, school), false, stored, false))
        assertTrue(zoneFencePlan(emptyList(), false, null, true).isEmpty)
        // A partial record (a refused add) has no fingerprint: the next sync plans again.
        val partial = FenceRecord("", listOf(7L), 1)
        assertEquals(FencePlan(listOf(7L), listOf(home, school)), zoneFencePlan(listOf(home, school), true, partial, false))
        // A zone sent twice is one fence.
        assertEquals(FencePlan(emptyList(), listOf(home)), zoneFencePlan(listOf(home, home), true, null, true))
    }

    @Test
    fun `the fingerprint is the fence, never a name`() {
        assertEquals("7,59.9139,10.7522,150;9,59.92,10.76,300", fenceFingerprint(listOf(school, home)))
    }

    @Test
    fun `the registration's echo and a zone not in the policy are no crossing`() {
        val zones = listOf(home)
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = 0))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = FENCE_ECHO_WINDOW_MS - 1))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = FENCE_ECHO_WINDOW_MS))
        // Leaving is never the echo (the platform fires "exiting" only after inside).
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = 5))
        // No record (-1) or one from before a reboot: no echo window.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = -1))
        assertEquals(FenceVerdict.NOT_OURS, fenceVerdict(9, zones, entering = false, sinceRegisteredMs = 10 * minute))
        assertEquals(FenceVerdict.NOT_OURS, fenceVerdict(7, emptyList(), entering = true, sinceRegisteredMs = 10 * minute))
        assertEquals(60_000L, FENCE_ECHO_WINDOW_MS)
        assertEquals(2 * minute, CONFIRM_DELAY_MS)
    }

    private fun fix(t: Long, lat: Double = 59.9, mock: Boolean = false) = QueuedFix(lat, 10.7, 20f, t, "gps", mock)

    @Test
    fun `the queue keeps each fix once, in time order, the newest twenty`() {
        var q = emptyList<QueuedFix>()
        q = queueWith(q, fix(30))
        q = queueWith(q, fix(10))
        q = queueWith(q, fix(30))
        assertEquals(listOf(fix(10), fix(30)), q)
        // Another place at the same time is another fix (the server's key: time and place).
        assertEquals(3, queueWith(q, fix(30, lat = 60.0)).size)
        val many = (1L..25L).fold(emptyList<QueuedFix>()) { acc, t -> queueWith(acc, fix(t)) }
        assertEquals(ZONE_QUEUE_MAX, many.size)
        assertEquals(6L, many.first().timeMs)
        assertEquals(25L, many.last().timeMs)
        assertEquals(20, ZONE_QUEUE_MAX)
    }

    @Test
    fun `the extras are the queue without the report's own fix`() {
        val q = listOf(fix(10), fix(20), fix(30))
        assertEquals(listOf(fix(10), fix(20)), extraLocations(q, fix(30)))
        assertEquals(q, extraLocations(q, null))
        assertNull(extraLocations(listOf(fix(30)), fix(30)))
        assertNull(extraLocations(emptyList(), fix(30)))
    }

    @Test
    fun `a report the server took empties what it carried, one it didn't keeps its fix`() {
        val q = listOf(fix(10), fix(20))
        // Taken: the extras and the location leave; a fix queued meanwhile stays.
        assertEquals(listOf(fix(25)), queueAfterReport(q + fix(25), sent = q, location = fix(30), accepted = true, locationOff = false))
        // Not taken (no network, a 5xx): the report's own fix waits with the rest - a crossing's fix
        // made in a tunnel arrives with the next report.
        assertEquals(listOf(fix(10), fix(20), fix(30)), queueAfterReport(q, sent = q, location = fix(30), accepted = false, locationOff = false))
        assertEquals(q, queueAfterReport(q, sent = q, location = null, accepted = false, locationOff = false))
        // The same cached fix failing twice is one entry.
        assertEquals(1, queueAfterReport(listOf(fix(30)), emptyList(), fix(30), accepted = false, locationOff = false).size)
        // Location turned off: nothing kept from before is ever sent.
        assertEquals(emptyList<QueuedFix>(), queueAfterReport(q, sent = emptyList(), location = null, accepted = false, locationOff = true))
    }

    @Test
    fun `the heartbeat fixes when due and the phone may have moved`() {
        val now = 1_800_000_000_000L
        val interval = 30 * minute
        fun beat(lastBeat: Long, motion: Motion, lastOwn: Long = lastBeat, keptAge: Long? = interval + minute) =
            heartbeatDue(now, lastBeat, interval, motion, lastOwn, keptAge)
        // Not due yet.
        assertEquals(Heartbeat.NOT_DUE, beat(now - 29 * minute, Motion.MOVED))
        // Due: moved, or no sensor (today's behaviour), or not known (a new process counts as moved).
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.NO_SENSOR))
        // The clock went back: due.
        assertEquals(Heartbeat.FIX, beat(now + minute, Motion.MOVED, lastOwn = now - interval))
        // Still since the last own fix: the cached fix, until 4 h of it.
        assertEquals(Heartbeat.STILL, beat(now - 30 * minute, Motion.STILL, lastOwn = now - 3 * 60 * minute))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.STILL, lastOwn = now - STILL_SKIP_MAX_MS))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.STILL, lastOwn = now + minute))
        // A kept fix (passive, a crossing's) younger than the interval is the heartbeat's fix.
        assertEquals(Heartbeat.KEPT, beat(now - 30 * minute, Motion.MOVED, keptAge = 5 * minute))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED, keptAge = interval))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED, keptAge = null))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED, keptAge = -minute))
        // Never stamped (a new install): due at once.
        assertEquals(Heartbeat.FIX, heartbeatDue(now, 0, interval, Motion.MOVED, 0, null))
        assertEquals(4 * 60 * minute, STILL_SKIP_MAX_MS)
    }

    @Test
    fun `a release build ignores a mock fix, the debug build takes it`() {
        assertEquals(FixVerdict.MOCK, fixVerdict(isMock = true, acceptMock = false))
        assertEquals(FixVerdict.KEEP, fixVerdict(isMock = true, acceptMock = true))
        assertEquals(FixVerdict.KEEP, fixVerdict(isMock = false, acceptMock = false))
    }

    @Test
    fun `a passive fix replaces the cached one only when it is better`() {
        fun worth(cached: Long?, accuracy: Float?, time: Long, mock: Boolean = false, release: Boolean = true) =
            passiveFixWorth(cached, mock, accuracy, time, acceptMock = !release)
        assertEquals(FixVerdict.KEEP, worth(cached = 100, accuracy = 30f, time = 200))
        assertEquals(FixVerdict.KEEP, worth(cached = null, accuracy = 200f, time = 1))
        assertEquals(FixVerdict.INACCURATE, worth(cached = 100, accuracy = 200.5f, time = 200))
        assertEquals(FixVerdict.INACCURATE, worth(cached = 100, accuracy = null, time = 200))
        assertEquals(FixVerdict.INACCURATE, worth(cached = 100, accuracy = Float.NaN, time = 200))
        assertEquals(FixVerdict.NOT_NEWER, worth(cached = 200, accuracy = 10f, time = 200))
        assertEquals(FixVerdict.NOT_NEWER, worth(cached = 300, accuracy = 10f, time = 200))
        // The mock rule first, also for a fix that would be better.
        assertEquals(FixVerdict.MOCK, worth(cached = 100, accuracy = 10f, time = 200, mock = true))
        assertEquals(FixVerdict.KEEP, worth(cached = 100, accuracy = 10f, time = 200, mock = true, release = false))
        assertEquals(200f, PASSIVE_ACCURACY_FLOOR_M)
    }

    /** About [m] metres north of [home]'s centre (a degree of latitude is 111 195 m on the mean sphere). */
    private fun north(m: Double, accuracy: Float? = 20f, time: Long = 0) =
        QueuedFix(home.latitude + m / 111_195.08, home.longitude, accuracy, time)

    @Test
    fun `the distance is haversine, as the server's`() {
        assertEquals(111_195.08, distanceM(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(111.2, distanceM(0.0, 179.9995, 0.0, -179.9995), 0.1)
        assertEquals(1000.0, distanceM(home.latitude, home.longitude, north(1000.0).latitude, home.longitude), 0.01)
        assertEquals(850.0, boundaryDistanceM(north(1000.0), home), 0.01)
        assertEquals(120.0, boundaryDistanceM(north(30.0), home), 0.01)
    }

    @Test
    fun `the next check comes by the distance to the nearest boundary`() {
        val now = 1_000_000_000L
        // No zones: no series at all.
        assertNull(nextCheckDelay(north(0.0, time = now), emptyList(), now))
        // No cached fix: the shortest delay.
        assertEquals(CHECK_MIN_DELAY_MS, nextCheckDelay(null, listOf(home), now))
        // Near a boundary (inside, 30 m from the centre: 120 m to the edge, less 20 m accuracy): 100 m
        // at 15 m/s is under 3 min - the floor.
        assertEquals(CHECK_MIN_DELAY_MS, nextCheckDelay(north(30.0, time = now), listOf(home), now))
        // 10 km out: 9850 m to the edge less 20 m accuracy, at 15 m/s = 655 s.
        val tenKm = 9830 / 15.0 * 1000
        assertEquals(tenKm, nextCheckDelay(north(10_000.0, time = now), listOf(home), now)!!.toDouble(), 2.0)
        // Far away (100 km): capped at the fence's 30 min.
        assertEquals(CHECK_MAX_DELAY_MS, nextCheckDelay(north(100_000.0, time = now), listOf(home), now))
        // The fix's age comes off: the same 10 km fix 5 min old.
        assertEquals(tenKm - 5 * minute, nextCheckDelay(north(10_000.0, time = now - 5 * minute), listOf(home), now)!!.toDouble(), 2.0)
        // A poor fix's accuracy comes off the distance: 10 km +- 9.85 km is the floor.
        assertEquals(CHECK_MIN_DELAY_MS, nextCheckDelay(north(10_000.0, accuracy = 9_850f, time = now), listOf(home), now))
        // The nearest of two zones counts.
        val far = PolicyZone(11, home.latitude + 1.0, home.longitude, 150)
        assertEquals(nextCheckDelay(north(10_000.0, time = now), listOf(home), now), nextCheckDelay(north(10_000.0, time = now), listOf(far, home), now))
        assertEquals(3 * minute, CHECK_MIN_DELAY_MS)
        assertEquals(30 * minute, CHECK_MAX_DELAY_MS)
    }

    @Test
    fun `a series ends after an hour, twenty checks, or when the motion stops`() {
        var s = seriesOnMotion(null, 0, 3 * minute)
        assertEquals(CheckSeries(0, 0, 0, 3 * minute), s)
        // The first check, 3 min on, no new fire: still within twice the delay - runs.
        assertNull(seriesEnd(s, 3 * minute))
        s = seriesAfterCheck(s, 4 * minute)
        // A new fire keeps it going; none for more than twice the last delay ends it.
        s = seriesOnMotion(s, 5 * minute, 99)
        assertEquals(5 * minute, s.lastMotionAtMs)
        assertEquals(0L, s.startedAtMs)
        assertNull(seriesEnd(s, 13 * minute))
        assertEquals(SeriesEnd.STILL, seriesEnd(s, 13 * minute + 1))
        // An hour after the start, however it moves.
        val moving = s.copy(lastMotionAtMs = 59 * minute)
        assertEquals(SeriesEnd.HOUR, seriesEnd(moving, SERIES_MAX_MS))
        // Twenty checks at most.
        assertEquals(SeriesEnd.COUNT, seriesEnd(moving.copy(checks = SERIES_MAX_CHECKS), 30 * minute))
        assertNull(seriesEnd(moving.copy(checks = SERIES_MAX_CHECKS - 1), 59 * minute))
        assertEquals(60 * minute, SERIES_MAX_MS)
        assertEquals(20, SERIES_MAX_CHECKS)
    }

    @Test
    fun `a check's fix is sent only when it could be a crossing`() {
        val zones = listOf(home)
        // Inside before, outside now: another side - sent, and the confirm follows.
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(30.0), north(1000.0), zones))
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(1000.0), north(30.0), zones))
        // Within its accuracy of the boundary: sent (the server may need it in time).
        assertEquals(CheckVerdict.NEAR_BOUNDARY, checkVerdict(north(1000.0), north(170.0, accuracy = 30f), zones))
        assertEquals(CheckVerdict.NEAR_BOUNDARY, checkVerdict(null, north(140.0, accuracy = 20f), zones))
        // Same side, clear of the boundary: kept only.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(1000.0), north(2000.0), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(10.0), north(40.0), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(null, north(2000.0), zones))
        // No accuracy: only on the boundary itself.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(2000.0), north(160.0, accuracy = null), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(null, north(30.0), emptyList()))
    }

    @Test
    fun `the launcher claims the zone watch`() {
        assertTrue(ZONES_CAPABILITY in STATUS_CAPABILITIES)
        assertEquals("zones_v1", ZONES_CAPABILITY)
    }

    private fun source(path: String) = File("src/main/java/com/kidslauncher/mdm/$path").readText()

    @Test
    fun `the glue - fences, receivers and the queue - by source`() {
        val fences = source("zones/ZoneFences.kt")
        // One PendingIntent identity per zone, mutable (AOSP fills in KEY_PROXIMITY_ENTERING), explicit.
        assertTrue(fences.contains("Intent(context, ZoneAlertReceiver::class.java).setAction(ACTION_ZONE).setData(Uri.parse(\"\$ZONE_SCHEME:\$id\"))"))
        assertTrue(fences.contains("PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT"))
        assertTrue(fences.contains("lm.addProximityAlert(zone.latitude, zone.longitude, zone.radiusM.toFloat(), -1L, pendingIntent(context, zone.id))"))
        // Removal: the platform's, then our PendingIntent cancelled.
        assertTrue(Regex("""lm\.removeProximityAlert\(pi\)[\s\S]{0,200}pi\.cancel\(\)""").containsMatchIn(fences))
        // Both receivers are ours alone.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (receiver in listOf(".zones.ZoneAlertReceiver", ".zones.ZoneConfirmReceiver")) {
            assertTrue(receiver, Regex("""android:name="${Regex.escape(receiver)}"\s+android:exported="false"""").containsMatchIn(manifest))
        }
        // The sync: the watch after apply(), a crossing's fresh fix, the queue after the report.
        val sync = source("server/MdmSyncWorker.kt")
        assertTrue(Regex("""AppEnforcer\.apply\(context, decision, fromSync = true\)[\s\S]{0,400}ZoneWatch\.apply\(context, decision\.policy\)""").containsMatchIn(sync))
        assertTrue(sync.contains("val forceFreshLocation = commandFix || zoneFix != null"))
        // §4.4's paths: GPS first only for locate/ring and the crossing's confirm, the rest balanced.
        assertTrue(sync.contains("val preciseFix = commandFix || zoneFix == FreshKind.PRECISE"))
        assertTrue(sync.contains("if (beat == Heartbeat.FIX) LocationAction.FRESH_BALANCED else LocationAction.CACHED"))
        assertTrue(sync.contains("if (it == LocationAction.FRESH && !precise) LocationAction.FRESH_BALANCED else it"))
        // Un-managed and a new enrollment release every fence, the feed, the series and the queue.
        assertTrue(source("server/AppEnforcer.kt").contains("if (!managedForHardening) com.kidslauncher.mdm.zones.ZoneWatch.release(context)"))
        assertTrue(source("server/Provisioning.kt").contains("com.kidslauncher.mdm.zones.ZoneWatch.release(context)"))
        assertTrue(source("ui/settings/launcher/SettingsFragmentLauncher.kt").contains("com.kidslauncher.mdm.zones.ZoneWatch.release(context)"))
        // A series check is a balanced fused fix only; a candidate is queued and asks for a sync.
        val series = source("zones/ZoneSeries.kt")
        assertTrue(series.contains("LocateCommands.seriesFix(context)"))
        assertTrue(Regex("""ZoneWatch\.enqueue\(context, fix\)\s+SyncRunner\.request\(context, "zone_check"\)""").containsMatchIn(series))
        assertTrue(series.contains("if (verdict == CheckVerdict.SIDE_CHANGED) ZoneWatch.armConfirm(context)"))
        assertTrue(Regex("""android:name="\.zones\.ZoneCheckReceiver"\s+android:exported="false"""").containsMatchIn(manifest))
        assertTrue(sync.contains("extraLocations = extraFixes?.map { it.toReport() }"))
        assertTrue(sync.contains("afterZoneReport(context, extraFixes, location.fix, sent.isSuccessful, locationOff)"))
        // A passive fix is never sent on its own: the feed only keeps it as the cached fix.
        val sensors = source("zones/ZoneSensors.kt")
        assertFalse(sensors.contains("SyncRunner"))
        assertTrue(sensors.contains("FixVerdict.KEEP -> LocateCommands.keepFix(context, location)"))
        // The mock rule on our own fixes too, fresh and last-known.
        val locate = source("server/LocateCommands.kt")
        assertTrue(locate.contains("requestFreshFix(context, lm)?.takeIf { ZoneWatch.accept(context, it) }"))
        assertTrue(locate.contains(".filter { ZoneWatch.accept(context, it) }"))
    }
}
