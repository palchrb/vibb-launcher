package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.LocationAction as L
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
        // No record yet (the registration under way - the record is written before the adds, Q-7)
        // or a stamp from before a reboot: an "entering" is the echo; leaving still counts.
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = null))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = -1))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = null))
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
        // Q-4: our own fix that landed 5 s after the last beat isn't a kept fix (none waits): a due
        // beat right after the interval fixes, it doesn't skip.
        val lastBeat = now - interval - 1_000
        assertEquals(Heartbeat.FIX, heartbeatDue(now, lastBeat, interval, Motion.MOVED, lastBeat + 5_000, keptFixAgeMs = null))
        assertEquals(4 * 60 * minute, STILL_SKIP_MAX_MS)
    }

    private val interval = com.kidslauncher.mdm.server.dto.LocationPolicy(mode = "interval", intervalMinutes = 60, zones = listOf(home))

    @Test
    fun `the zone watch is on only as owner, managed, Regularly with zones`() {
        assertTrue(zoneWatchActive(owner = true, managed = true, policy = interval))
        assertFalse(zoneWatchActive(owner = false, managed = true, policy = interval))
        assertFalse(zoneWatchActive(owner = true, managed = false, policy = interval))
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = interval.copy(zones = emptyList())))
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = interval.copy(mode = "on_request")))
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = interval.copy(mode = "off")))
        // An older server (no location_policy): no zones.
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = null))
    }

    @Test
    fun `without zones the location step is exactly today's, for every input`() {
        val now = 1_800_000_000_000L
        val policies = listOf(
            null,
            com.kidslauncher.mdm.server.LEGACY_LOCATION_POLICY,
            interval.copy(zones = emptyList()),
            interval.copy(zones = emptyList(), intervalMinutes = 10),
            interval.copy(mode = "on_request", zones = emptyList()),
            interval.copy(mode = "off", zones = emptyList()),
            // The gate is the caller's: with zonesOn false even a policy with zones is today's.
            interval,
        )
        val ages = listOf(-minute, 0L, minute, 9 * minute, 10 * minute, 59 * minute, 60 * minute, 61 * minute, 5 * 60 * minute)
        for (policy in policies) for (forced in listOf(false, true)) for (precise in listOf(false, true)) for (age in ages)
            for (motion in Motion.values()) for (kept in listOf(null, 0L, minute)) for (beat in listOf(0L, now - minute, now)) {
                val got = syncLocationAction(policy, zonesOn = false, forced = forced, precise = precise, nowMs = now,
                    lastOwnFixMs = now - age, lastBeatMs = beat, motion = motion, keptFixAgeMs = kept, lastFreshFixMs = now - age)
                assertEquals(com.kidslauncher.mdm.server.locationAction(policy, forced, age), got.action)
                assertNull(got.beat)
            }
        // ... and the backstop counts from our last own fix, as before.
        assertEquals(5L, backstopAnchorMs(zonesOn = false, lastBeatMs = 9, lastOwnFixMs = 5))
        assertEquals(9L, backstopAnchorMs(zonesOn = true, lastBeatMs = 9, lastOwnFixMs = 5))
        // ... and no still flag (Q-3).
        assertFalse(reportStill(false, com.kidslauncher.mdm.server.LocationAction.CACHED, Motion.STILL, now, now - minute))
    }

    @Test
    fun `with zones the heartbeat decides, a command and a crossing fix`() {
        val now = 1_800_000_000_000L
        fun act(forced: Boolean = false, precise: Boolean = false, beat: Long = now - 61 * minute, motion: Motion = Motion.MOVED, kept: Long? = null,
                fresh: Long = now - 61 * minute) =
            syncLocationAction(interval, zonesOn = true, forced = forced, precise = precise, nowMs = now,
                lastOwnFixMs = now - 61 * minute, lastBeatMs = beat, motion = motion, keptFixAgeMs = kept, lastFreshFixMs = fresh)
        assertEquals(SyncLocation(L.FRESH, null), act(forced = true, precise = true))
        assertEquals(SyncLocation(L.FRESH_BALANCED, null), act(forced = true))
        assertEquals(SyncLocation(L.FRESH_BALANCED, Heartbeat.FIX), act())
        assertEquals(SyncLocation(L.CACHED, Heartbeat.NOT_DUE), act(beat = now - minute))
        assertEquals(SyncLocation(L.CACHED, Heartbeat.KEPT), act(kept = 5 * minute))
        assertEquals(SyncLocation(L.CACHED, Heartbeat.STILL), act(motion = Motion.STILL))
        // F-3: the 4 h count from the last genuinely fresh fix - failed attempts (the own-fix stamp
        // 61 min ago) don't restart it.
        assertEquals(SyncLocation(L.FRESH_BALANCED, Heartbeat.FIX), act(motion = Motion.STILL, fresh = now - STILL_SKIP_MAX_MS))
        assertEquals(SyncLocation(L.FRESH_BALANCED, Heartbeat.FIX), act(motion = Motion.STILL, fresh = 0))
        // Only Regularly has a heartbeat: on request and Off are today's.
        assertEquals(L.NONE, syncLocationAction(interval.copy(mode = "on_request"), true, false, false, now, 0, 0, Motion.MOVED, null, 0).action)
    }

    @Test
    fun `a re-sent fix is still current only while the sensor says so`() {
        val now = 1_800_000_000_000L
        assertTrue(reportStill(true, L.CACHED, Motion.STILL, now, now - 3 * 60 * minute))
        assertFalse(reportStill(true, L.CACHED, Motion.STILL, now, now - STILL_SKIP_MAX_MS))
        assertFalse(reportStill(true, L.CACHED, Motion.MOVED, now, now - minute))
        assertFalse(reportStill(true, L.CACHED, Motion.NO_SENSOR, now, now - minute))
        assertFalse(reportStill(true, L.FRESH_BALANCED, Motion.STILL, now, now - minute))
        assertFalse(reportStill(true, L.CACHED, Motion.STILL, now, now + minute))
    }

    @Test
    fun `a fix is fresh only when made for the request`() {
        assertTrue(freshOwnFix(fixTimeMs = 1_000_000, requestedAtMs = 1_000_000))
        assertTrue(freshOwnFix(fixTimeMs = 1_000_000 - FRESH_FIX_SLACK_MS, requestedAtMs = 1_000_000))
        // The cached fix or an old last-known one handed back by a timeout.
        assertFalse(freshOwnFix(fixTimeMs = 1_000_000 - FRESH_FIX_SLACK_MS - 1, requestedAtMs = 1_000_000))
    }

    @Test
    fun `a mock fix counts once, by its time and place`() {
        val a = mockKey(10, 59.9, 10.7)
        val b = mockKey(20, 59.9, 10.7)
        assertEquals(listOf(a), mockKeysAfter(emptyList(), a))
        assertNull(mockKeysAfter(listOf(a, b), a))
        // Two providers alternating count once each.
        assertNull(mockKeysAfter(mockKeysAfter(listOf(a), b)!!, a))
        assertFalse(mockKey(10, 59.9, 10.7) == mockKey(10, 59.9, 10.8))
        val many = (1..20).map { mockKey(it.toLong(), 0.0, 0.0) }.fold(emptyList<String>()) { acc, k -> mockKeysAfter(acc, k)!! }
        assertEquals(MOCK_KEYS_KEPT, many.size)
        assertEquals(mockKey(20, 0.0, 0.0), many.last())
        assertEquals(8, MOCK_KEYS_KEPT)
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
        // F-2: no check left today ends it first.
        assertEquals(SeriesEnd.BUDGET, seriesEnd(s, 3 * minute, checksLeft = 0))
        assertNull(seriesEnd(s, 3 * minute, checksLeft = 1))
    }

    @Test
    fun `a new series waits out the cooldown, a fence crossing doesn't, the day's budget binds both`() {
        val ended = 100 * minute
        // A motion fire: not within 20 min of the last end.
        assertFalse(seriesMayStart(fromFence = false, nowMs = ended + 19 * minute, lastEndedAtMs = ended, checksLeft = 60))
        assertTrue(seriesMayStart(fromFence = false, nowMs = ended + SERIES_COOLDOWN_MS, lastEndedAtMs = ended, checksLeft = 60))
        assertTrue(seriesMayStart(fromFence = false, nowMs = 5, lastEndedAtMs = null, checksLeft = 60))
        // A reboot (elapsed back to 0): past it.
        assertTrue(seriesMayStart(fromFence = false, nowMs = minute, lastEndedAtMs = ended, checksLeft = 60))
        // A fence crossing: at once.
        assertTrue(seriesMayStart(fromFence = true, nowMs = ended + minute, lastEndedAtMs = ended, checksLeft = 1))
        // No check left today: neither.
        assertFalse(seriesMayStart(fromFence = true, nowMs = ended + minute, lastEndedAtMs = null, checksLeft = 0))
        assertFalse(seriesMayStart(fromFence = false, nowMs = ended + 60 * minute, lastEndedAtMs = ended, checksLeft = 0))
        // The budget: per local day, 60.
        assertEquals(SERIES_CHECKS_PER_DAY, checksLeft(null, "2026-10-24"))
        var budget: CheckBudget? = null
        repeat(59) { budget = budgetAfterCheck(budget, "2026-10-24") }
        assertEquals(1, checksLeft(budget, "2026-10-24"))
        budget = budgetAfterCheck(budget, "2026-10-24")
        assertEquals(0, checksLeft(budget, "2026-10-24"))
        assertEquals(0, checksLeft(budgetAfterCheck(budget, "2026-10-24"), "2026-10-24"))
        // A new day starts again.
        assertEquals(SERIES_CHECKS_PER_DAY, checksLeft(budget, "2026-10-25"))
        assertEquals(CheckBudget("2026-10-25", 1), budgetAfterCheck(budget, "2026-10-25"))
        assertEquals(20 * minute, SERIES_COOLDOWN_MS)
        assertEquals(60, SERIES_CHECKS_PER_DAY)
    }

    @Test
    fun `a check's fix is sent only when it could be a crossing`() {
        val zones = listOf(home)
        // Surely inside before, surely outside now (and back): sent, and the confirm follows.
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(30.0), north(1000.0), zones))
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(1000.0), north(30.0), zones))
        // F-1: the server's own side rule - inside at d + a <= r, outside at d - a >= r.
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(30.0), north(182.0, accuracy = 30f), zones))
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(1000.0), north(118.0, accuracy = 30f), zones))
        // An unsure flip (plain distance over the radius, within its accuracy of it): never sent.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(30.0), north(170.0, accuracy = 30f), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(1000.0), north(140.0, accuracy = 20f), zones))
        // A fix without accuracy is never sure: never sent, however far.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(30.0), north(1000.0, accuracy = null), zones))
        // No reported fix: nothing to compare with.
        assertEquals(CheckVerdict.KEEP, checkVerdict(null, north(140.0, accuracy = 20f), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(null, north(2000.0), zones))
        // An unsure reported fix counts by its plain distance: inside by distance, surely inside now
        // is no change; outside by distance, surely inside now is.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(140.0, accuracy = 50f), north(30.0), zones))
        assertEquals(CheckVerdict.SIDE_CHANGED, checkVerdict(north(160.0, accuracy = 50f), north(30.0), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(100.0, accuracy = null), north(30.0), zones))
        // Same side, clear of the boundary: kept only.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(1000.0), north(2000.0), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(10.0), north(40.0), zones))
        assertEquals(CheckVerdict.KEEP, checkVerdict(null, north(30.0), emptyList()))
        assertEquals(null, sureSide(north(170.0, accuracy = 30f), home))
        assertEquals(true, sureSide(north(118.0, accuracy = 30f), home))
        assertEquals(false, sureSide(north(182.0, accuracy = 30f), home))
        assertEquals(null, sureSide(north(30.0, accuracy = null), home))
        assertEquals(listOf("KEEP", "SIDE_CHANGED"), CheckVerdict.values().map { it.name })
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
        // Q-2: one gate for the sync, the watch and the backstop; without it today's action.
        assertTrue(sync.contains("val zonesOn = ZoneWatch.zonesOn(context, decision.policy)"))
        assertTrue(sync.contains("val zoneFix = ZoneWatch.takeFreshWanted()?.takeIf { zonesOn }"))
        assertTrue(sync.contains("val sync = syncLocationAction("))
        assertFalse(sync.contains("heartbeatDue("))
        val watch = source("zones/ZoneWatch.kt")
        // G8: as device owner and managed only.
        assertTrue(watch.contains("zoneWatchActive(owner, managed, policy?.locationPolicy)"))
        assertTrue(watch.contains("PassiveFixes.update(app, on = on)"))
        assertTrue(watch.contains("if (on) MotionGate.watch(app, zones = true) else MotionGate.stop(app)"))
        assertTrue(watch.contains("ZoneFences.sync(app, zones, watching = on)"))
        // G7: a fence's zones only from a Regularly policy.
        assertTrue(watch.contains("currentPolicyDecision().policy?.locationPolicy?.takeIf { it.mode == \"interval\" }?.zones.orEmpty()"))
        // G2: the backstop counts from the beat only with zones.
        val backstop = source("push/BackstopAlarm.kt")
        assertTrue(backstop.contains("val zonesOn = com.kidslauncher.mdm.zones.ZoneWatch.zonesOn(context, policy)"))
        assertTrue(Regex("""backstopAnchorMs\(\s*zonesOn,\s*com\.kidslauncher\.mdm\.zones\.ZoneWatch\.lastBeatMs\(context\),\s*LauncherPreferences\.mdm\(\)\.lastActiveLocationFetchAtMs\(\),\s*\)""").containsMatchIn(backstop))
        // G3: every due call and every fresh fix stamps the beat.
        assertTrue(sync.contains("if (action.fresh || (beat != null && beat != Heartbeat.NOT_DUE)) ZoneWatch.stampBeat(context, now)"))
        // G1/Q-6: the gate arms only after a genuinely fresh own fix (pinned with F-3's stamp below).
        val seriesSource = source("zones/ZoneSeries.kt")
        assertTrue(seriesSource.contains("val fresh = freshOwnFix(location.time, requestedAt)"))
        assertTrue(Regex("""if \(verdict == CheckVerdict\.KEEP\) \{\s+if \(fresh\) MotionGate\.arm\(context\)\s+return\s+\}""").containsMatchIn(seriesSource))
        // F-3: a fence crossing and a series' side change are motion; the 4 h count from the last
        // genuinely fresh own fix, stamped only with freshOwnFix.
        assertTrue(Regex("""if \(verdict != FenceVerdict\.CROSSING\) return[\s\S]{0,300}MotionGate\.markMoved\(\)""").containsMatchIn(source("zones/ZoneWatch.kt")))
        assertTrue(Regex("""MotionGate\.markMoved\(\)\s+LocateCommands\.promoteKept\(context\)""").containsMatchIn(seriesSource))
        assertTrue(seriesSource.contains("if (fresh) ZoneWatch.stampFreshFix(context, location.time)"))
        assertTrue(Regex("""if \(zonesOn && action\.fresh && freshOwnFix\(location\.time, now\)\) \{\s+ZoneWatch\.stampFreshFix\(context, location\.time\)\s+MotionGate\.arm\(context\)""").containsMatchIn(sync))
        assertTrue(sync.contains("val lastFreshFix = if (zonesOn) ZoneWatch.lastFreshFixMs(context) else lastOwnFix"))
        assertTrue(sync.contains("lastFreshFixMs = lastFreshFix,"))
        assertEquals(1, Regex("""stampFreshFix\(""").findAll(sync).count())
        // F-2: the cooldown and the budget in the glue; a fence crossing starts a series at once.
        assertTrue(seriesSource.contains("if (before == null && !seriesMayStart(fromFence, now, lastEndedAtMs, left)) return"))
        assertTrue(seriesSource.contains("seriesEnd(running, now, ZoneWatch.checksLeftToday(app))"))
        assertTrue(Regex("""return\s+\}\s+ZoneWatch\.spendCheck\(app\)""").containsMatchIn(seriesSource))
        assertTrue(source("zones/ZoneWatch.kt").contains("ZoneSeries.launch { ZoneSeries.onMotion(app, fromFence = true) }"))
        // F-4 (F16): the next check is armed in `finally`, so a failed check never ends the series.
        assertTrue(Regex("""\} finally \{[\s\S]{0,400}if \(still\) arm\(app, next\)""").containsMatchIn(seriesSource))
        // F-4: the motion fire's work leaves the sensor callback's main thread.
        assertTrue(Regex("""ZoneSeries\.launch \{\s+ZoneSeries\.onMotion\(context\)\s+ensureArmed\(context\)\s+\}""").containsMatchIn(source("zones/ZoneSensors.kt")))
        // Q-1: the kept fix is promoted only at a due KEPT or a crossing's sync, and by a series candidate.
        assertTrue(sync.contains("if (beat == Heartbeat.KEPT || crossing) LocateCommands.promoteKept(context)"))
        assertEquals(1, Regex("""promoteKept\(""").findAll(sync).count())
        // Q-3: the still flag rides the report.
        assertTrue(sync.contains("zoneWatch = ZoneWatch.report(context, still = location.still),"))
        assertTrue(sync.contains("still = reportStill(zonesOn, action, motion, now, lastFreshFix),"))
        // Q-5: Off (and no zones) clears the queue before the report and sends no extras.
        assertTrue(sync.contains("if (queueOff) ZoneWatch.clearQueue(context)"))
        assertTrue(sync.contains("val extraFixes = if (queueOff) null else extraLocations(ZoneWatch.queue(context), location.fix)"))
        // Q-7: the record (and its stamp) before the first add; a partial set has no fingerprint (G6).
        assertTrue(Regex("""saveFenceRecord\(context, FenceRecord\("", plan\.add\.map \{ it\.id \}, registeredAt\)\)[\s\S]{0,200}for \(zone in plan\.add\)""").containsMatchIn(fences))
        assertTrue(fences.contains("val fingerprint = if (added.size == plan.add.size) fenceFingerprint(plan.add) else \"\""))
        assertTrue(watch.contains("val since = fenceRecord(app)?.let { SystemClock.elapsedRealtime() - it.registeredAtElapsed }"))
        // G5/Q-9: the mock count by the recent fixes' keys.
        assertTrue(watch.contains("mockKeysAfter(recent, mockKey(location.time, location.latitude, location.longitude)) ?: return"))
        // Un-managed releases every fence, the feed, the series, the queue and the kept fix; a new
        // enrollment forgets the watch's state too (Q-8).
        assertTrue(source("server/AppEnforcer.kt").contains("if (!managedForHardening) com.kidslauncher.mdm.zones.ZoneWatch.release(context)"))
        assertTrue(source("server/Provisioning.kt").contains("com.kidslauncher.mdm.zones.ZoneWatch.forget(context)"))
        assertTrue(source("ui/settings/launcher/SettingsFragmentLauncher.kt").contains("com.kidslauncher.mdm.zones.ZoneWatch.forget(context)"))
        assertTrue(Regex("""fun forget\(context: Context\) \{[\s\S]{0,200}release\(app\)[\s\S]{0,300}\.remove\(KEY_FENCES\)\.remove\(KEY_QUEUE\)\.remove\(KEY_LAST_BEAT\)""").containsMatchIn(watch))
        // A series check is a balanced fused fix only; a candidate is queued and asks for a sync.
        val series = source("zones/ZoneSeries.kt")
        assertTrue(series.contains("LocateCommands.seriesFix(context)"))
        assertTrue(Regex("""ZoneWatch\.enqueue\(context, fix\)\s+SyncRunner\.request\(context, "zone_check"\)""").containsMatchIn(series))
        assertTrue(Regex("""SyncRunner\.request\(context, "zone_check"\)\s+ZoneWatch\.armConfirm\(context\)""").containsMatchIn(series))
        assertTrue(Regex("""android:name="\.zones\.ZoneCheckReceiver"\s+android:exported="false"""").containsMatchIn(manifest))
        assertTrue(sync.contains("extraLocations = extraFixes?.map { it.toReport() }"))
        assertTrue(sync.contains("afterZoneReport(context, extraFixes, location.fix, sent.isSuccessful, queueOff)"))
        // G4: a report that threw leaves its fix queued.
        assertTrue(sync.contains("if (!zoneQueueDone) afterZoneReport(context, extraFixes, location.fix, accepted = false, locationOff = queueOff)"))
        // A passive fix is never sent on its own: the feed only puts it in the kept slot (Q-1), and
        // the slot never writes the cached (reported) fix but in promoteKept.
        val sensors = source("zones/ZoneSensors.kt")
        assertFalse(sensors.contains("SyncRunner"))
        assertTrue(sensors.contains("FixVerdict.KEEP -> LocateCommands.keepFix(context, location)"))
        // G9: a passive mock fix is counted.
        assertTrue(sensors.contains("FixVerdict.MOCK -> ZoneWatch.countMock(context, location)"))
        // Q-6: the trigger's fire and the arm share one monitor.
        assertTrue(Regex("""@Synchronized\s+fun arm\(context: Context\)""").containsMatchIn(sensors))
        assertTrue(Regex("""synchronized\(this\) \{\s+moved = true\s+listener = null""").containsMatchIn(sensors))
        // The mock rule on our own fixes too, fresh and last-known.
        val locate = source("server/LocateCommands.kt")
        val keep = Regex("""fun keepFix\([\s\S]*?\n    }""").find(locate)!!.value
        // F-4 (F12): promoteKept promotes only a kept fix newer than the reported one.
        val promote = Regex("""fun promoteKept\([\s\S]*?\n    }""").find(locate)!!.value
        assertTrue(promote.contains("val promoted = keptUnreported() != null && raw != null"))
        assertTrue(promote.contains("if (promoted) edit.putString(LauncherPreferences.mdm().keys().cachedLocationJson(), raw)"))
        assertTrue(Regex("""if \(cached != null && kept\.timeMs <= cached\) return null""").containsMatchIn(locate))
        assertTrue(keep.contains("putString(KEPT_FIX_KEY"))
        assertFalse(keep.contains("cachedLocationJson"))
        assertFalse(keep.contains("saveCachedFix"))
        // Only promoteKept and the own-fix save write the cached fix.
        assertEquals(2, Regex("""keys\(\)\.cachedLocationJson\(\)""").findAll(locate).count())
        assertTrue(locate.contains("requestFreshFix(context, lm)?.takeIf { ZoneWatch.accept(context, it) }"))
        assertTrue(locate.contains(".filter { ZoneWatch.accept(context, it) }"))
    }
}
