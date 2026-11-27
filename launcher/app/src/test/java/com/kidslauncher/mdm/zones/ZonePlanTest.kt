package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.LocationAction as L
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

    private val late = 4 * 60 * minute + 19 * minute
    private val inside = QueuedFix(59.9139, 10.7522, 20f, 0)
    private val outside = QueuedFix(59.9229, 10.7522, 20f, 0) // 1 km north
    private val edge = QueuedFix(59.91525, 10.7522, 20f, 0) // on the 150 m edge: unsure

    @Test
    fun `a late echo to the side the phone already knows is no crossing`() {
        val zones = listOf(home)
        // The owner's Jelly (2026-11-27): lying still at home, the registration's "entering" 4 h 19 min late.
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside))
        // A real enter from outside, and leaving while inside, are crossings.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = outside))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = late, reported = inside))
        // An "exiting" while the phone already reads outside changes nothing either - unless a newer kept fix is inside.
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = late, reported = outside))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = late, reported = outside, kept = inside))
        // No known side - no fix, an unsure one, none with accuracy - keeps the crossing.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = edge))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside.copy(accuracy = null)))
        // A newer kept fix surely on the other side wins: the server doesn't have it yet; an unsure one doesn't count.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside, kept = outside))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside, kept = edge))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside, kept = inside))
        // A kept fix alone is no side the server knows.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, kept = inside))
        // Another zone's fence is still not ours, whatever the fix.
        assertEquals(FenceVerdict.NOT_OURS, fenceVerdict(9, zones, entering = true, sinceRegisteredMs = late, reported = inside))
    }

    @Test
    fun `a Wi-Fi fix alone is no known inside, a coarse fix never one, and a sure outside beats the window`() {
        val zones = listOf(home)
        // Code QA M1: a print/Wi-Fi fix nothing confirmed never says inside (the server's WifiAlone); confirmed it does.
        val wifiInside = inside.copy(provider = "vibb_wifi")
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = wifiInside))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside.copy(provider = "vibb_apple")))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = wifiInside.copy(check = "gnss")))
        // Outside it counts: an "exiting" against a Wi-Fi fix alone outside is the echo.
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = late, reported = outside.copy(provider = "vibb_wifi")))
        // A coarse fix is never inside; far outside it is sure (20 km), near it is unsure.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = inside.copy(provider = "vibb_cell", accuracy = 1000f)))
        val farCell = QueuedFix(home.latitude + 0.3, home.longitude, 1000f, 0, "vibb_apple_cell") // ~33 km north
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = false, sinceRegisteredMs = late, reported = farCell))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = late, reported = farCell))
        // Code QA L2: the 60 s window holds only while the phone's sure side isn't outside.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = 5, reported = outside))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = null, reported = farCell))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = 5, reported = inside, kept = outside))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = 5, reported = edge))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, entering = true, sinceRegisteredMs = 5, reported = inside))
    }

    @Test
    fun `a first enter since the registration on a phone still since it is the echo, whatever the fix's accuracy`() {
        val zones = listOf(home)
        // Code QA M2, the owner's case: 50 m from the centre, +-120 m - unsure in the 150 m zone.
        val unsure = QueuedFix(home.latitude + 0.00045, home.longitude, 120f, 0)
        assertNull(sureSide(unsure, home))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, true, late, reported = unsure, firstSinceRegistration = true, stillSinceRegistration = true))
        // Not still since, or a second broadcast: the other rules.
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, true, late, reported = unsure, firstSinceRegistration = true, stillSinceRegistration = false))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, true, late, reported = unsure, firstSinceRegistration = false, stillSinceRegistration = true))
        assertEquals(FenceVerdict.ECHO, fenceVerdict(7, zones, true, late, reported = inside, firstSinceRegistration = false, stillSinceRegistration = false))
        // Never against a sure outside, never for an "exiting".
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, true, late, reported = outside, firstSinceRegistration = true, stillSinceRegistration = true))
        assertEquals(FenceVerdict.CROSSING, fenceVerdict(7, zones, false, late, reported = unsure, firstSinceRegistration = true, stillSinceRegistration = true))

        // Still since the registration: registered at 1 000 s in a process started at 990 s, now 16 000 s.
        val reg = 1_000_000L
        val start = 990_000L
        val now = 16_000_000L
        // Nothing fired in this process (the gate's own start stamp is no move), or the last move came before it.
        assertTrue(stillSinceRegistration(Motion.STILL, reg, start, now, fires = 0, movedAtElapsedMs = reg + 15))
        assertTrue(stillSinceRegistration(Motion.STILL, reg, start, now, fires = 2, movedAtElapsedMs = reg - 1))
        // A motion fire (or a mark, a refused arm) after the registration.
        assertFalse(stillSinceRegistration(Motion.STILL, reg, start, now, fires = 1, movedAtElapsedMs = reg + 60_000))
        // D7's distrust or no sensor, a fire since the last fresh fix, a fix on the move, no anchor: not STILL.
        assertFalse(stillSinceRegistration(Motion.NO_SENSOR, reg, start, now, fires = 0, movedAtElapsedMs = 0))
        assertFalse(stillSinceRegistration(Motion.STILL_SINCE_TRY, reg, start, now, fires = 0, movedAtElapsedMs = 0))
        assertFalse(stillSinceRegistration(Motion.MOVED, reg, start, now, fires = 0, movedAtElapsedMs = 0))
        // Another process's registration (a gap nothing watched), or a stamp of another boot.
        assertFalse(stillSinceRegistration(Motion.STILL, start - 1, start, now, fires = 0, movedAtElapsedMs = 0))
        assertFalse(stillSinceRegistration(Motion.STILL, now + 1, start, now, fires = 0, movedAtElapsedMs = 0))
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
        fun beat(lastBeat: Long, motion: Motion, lastFresh: Long = lastBeat, keptAge: Long? = interval + minute, lastTry: Long = lastFresh) =
            heartbeatDue(now, lastBeat, interval, motion, lastFresh, keptAge, lastTry, stillSinceMs = lastFresh)
        // Not due yet.
        assertEquals(Heartbeat.NOT_DUE, beat(now - 29 * minute, Motion.MOVED))
        // Due: moved, or no sensor (today's behaviour), or not known (a new process counts as moved).
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.NO_SENSOR))
        // The clock went back: due.
        assertEquals(Heartbeat.FIX, beat(now + minute, Motion.MOVED, lastFresh = now - interval))
        // Still since the last fresh fix: the cached fix, until 4 h of it; then the cap's try.
        assertEquals(Heartbeat.STILL, beat(now - 30 * minute, Motion.STILL, lastFresh = now - 3 * 60 * minute))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.STILL, lastFresh = now - STILL_SKIP_MAX_MS))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.STILL, lastFresh = now + minute))
        // A failed try since doesn't make STILL_SINCE_TRY vouch.
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.STILL_SINCE_TRY, lastFresh = now - 60 * minute, lastTry = now - 30 * minute))
        // A kept fix (passive, a crossing's) younger than the interval is the heartbeat's fix.
        assertEquals(Heartbeat.KEPT, beat(now - 30 * minute, Motion.MOVED, keptAge = 5 * minute))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED, keptAge = interval))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED, keptAge = null))
        assertEquals(Heartbeat.FIX, beat(now - 30 * minute, Motion.MOVED, keptAge = -minute))
        // Never stamped (a new install): due at once.
        assertEquals(Heartbeat.FIX, heartbeatDue(now, 0, interval, Motion.MOVED, 0, null, 0, 0))
        // Q-4: our own fix that landed 5 s after the last beat isn't a kept fix (none waits): a due
        // beat right after the interval fixes, it doesn't skip.
        val lastBeat = now - interval - 1_000
        assertEquals(Heartbeat.FIX, heartbeatDue(now, lastBeat, interval, Motion.MOVED, lastBeat + 5_000, null, lastBeat + 5_000, 0))
        assertEquals(4 * 60 * minute, STILL_SKIP_MAX_MS)
    }

    /**
     * Design 124 D5's evenings, beat by beat as the sync runs them: every 30 min a heartbeat, a `FIX`
     * stamps the try, its outcome goes to the flags; [fixAt] says which tries find a fix, [fireAt]
     * when the trigger fires. The tries.
     */
    private fun evening(fixAt: Set<Int>, fireAt: Set<Int> = emptySet(), tryStampLagMs: Long = 0): List<Int> {
        val interval = 30 * minute
        val base = 1_800_000_000_000L
        fun at(hhmm: Int) = base + ((hhmm / 100 + if (hhmm < 1200) 24 else 0) * 60 + hhmm % 100) * minute
        // Still at 18:00 (a good fix), moved at 20:10 - the walk home.
        var flags = MotionFlags().afterTry(0, at(1800), heartbeat = true, nowMs = at(1800)).moved(0)
        var lastBeat = at(2000)
        var lastTry = at(1800)
        var lastFresh = at(1800)
        var capFails = 0
        val tries = mutableListOf<Int>()
        val beats = (20 * 60 + 30..30 * 60 step 30).map { m -> (m / 60 % 24) * 100 + m % 60 }
        for (hhmm in beats) {
            val t = at(hhmm)
            if (hhmm in fireAt) flags = flags.moved(0)
            val motion = flags.motion(sensor = true, anchor = true)
            val fires = flags.fires
            val beat = heartbeatDue(t, lastBeat, interval, motion, lastFresh, null, lastTry, flags.stillSinceMs, capFails)
            if (beat != Heartbeat.NOT_DUE) lastBeat = t
            if (beat != Heartbeat.FIX) continue
            tries += hhmm
            lastTry = t + tryStampLagMs
            val fresh = if (hhmm in fixAt) t else null
            if (fresh != null) lastFresh = fresh
            flags = flags.afterTry(fires, fresh, heartbeat = true, nowMs = t)
            capFails = stillCapFailsAfter(capFails, beat, motion, fresh != null, renewed = false, held = true)
        }
        return tries
    }

    @Test
    fun `a failed try while the phone hasn't moved backs off, up to 4 h`() {
        // The walk home, then every try indoors fails: six tries a night, not eighteen.
        assertEquals(listOf(2030, 2100, 2130, 2230, 30, 430), evening(fixAt = emptySet()))
        // The evening's fix succeeds: STILL to 00:30; the cap's try fails - the next two beats, then 1 h, 2 h, 4 h
        // (amended 2026-11-25), STILL all along.
        assertEquals(listOf(2030, 30, 100, 130, 230, 430), evening(fixAt = setOf(2030)))
        // A move always gives a try at the next beat, and the back-off starts over from it (QUIET, as before).
        assertEquals(listOf(2030, 30, 100, 130, 200, 230, 300, 400, 600), evening(fixAt = setOf(2030), fireAt = setOf(200)))
        // The §1 cap gap (a regression row): the cap's try failed - no GPS start every beat after it: after the
        // fourth failed try STILL waits 2 h, and never QUIET while the sensor hasn't fired.
        val now = 1_800_000_000_000L
        val interval = 30 * minute
        val fresh = now - STILL_SKIP_MAX_MS - 30 * minute
        assertEquals(Heartbeat.STILL, heartbeatDue(now, now - interval, interval, Motion.STILL, fresh, null, now - interval, fresh, capFails = 4))
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.STILL, fresh, null, now - interval, fresh, capFails = 1))
        // The first try at the cap is due at once, whatever tried meanwhile (a failed locate 10 min ago).
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.STILL, fresh, null, now - 10 * minute, fresh))
        // QA M1: the back-off counts from the try as the sync stamps it - the beat's own now
        // (`ZoneWatch.stampBeat(.., tried)`). A try stamped 50 ms after its beat (LocationManager's stamp)
        // would make the beat one interval on QUIET, and the evening skip whole intervals.
        val beat = now - interval
        assertEquals(Heartbeat.FIX, heartbeatDue(now, beat, interval, Motion.STILL_SINCE_TRY, 0, null, beat, beat))
        assertEquals(Heartbeat.QUIET, heartbeatDue(now, beat, interval, Motion.STILL_SINCE_TRY, 0, null, beat + 50, beat))
        assertEquals(listOf(2030, 2130, 2300, 200), evening(fixAt = emptySet(), tryStampLagMs = 50))
        // The back-off: as long as already still and failing, within the interval and 4 h.
        assertEquals(interval, stillBackoffMs(interval, lastTryMs = 1_000, stillSinceMs = 5_000))
        assertEquals(2 * 60 * minute, stillBackoffMs(interval, lastTryMs = 2 * 60 * minute, stillSinceMs = 0))
        assertEquals(STILL_SKIP_MAX_MS, stillBackoffMs(interval, lastTryMs = 9 * 60 * minute, stillSinceMs = 0))
        // An interval past 4 h is never shortened.
        assertEquals(6 * 60 * minute, stillBackoffMs(6 * 60 * minute, lastTryMs = 9 * 60 * minute, stillSinceMs = 0))
        // The clock set back (the last try in the future): due.
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.STILL_SINCE_TRY, 0, null, now + minute, now))
    }

    /** What a heartbeat try's attempt brings: a genuinely fresh fix; nothing; an unchecked Wi-Fi fix (`notFresh`); a
     * last-known fallback (a fix of another app's, 5 min old). */
    private enum class TryResult { FRESH, NONE, UNCHECKED_WIFI, LAST_KNOWN }

    /** One beat of [stillDay]: minutes from 07:30, the beat's own `HHmm`, its verdict, whether its report says `still`,
     * and the cap's failed tries after it. */
    private data class StillBeat(val m: Int, val at: Int, val beat: Heartbeat, val still: Boolean, val fails: Int)

    /**
     * Design 124 D5 amended 2026-11-25, the owner's still day (a regression row): a fresh fix at 07:30 and then no
     * fire; beats every 10 min, each a few seconds late (the backstop's wake), so 24 beats are 4 h 1 min. The cap's
     * try at 11:31 renews the vouch, the one at 15:32 fails - and every try after it unless [results] says otherwise
     * (by the beat's own HHmm); [fireAt]: the beats before which the sensor fires. The glue as the sync runs it: the
     * cached fix (a cap try keeps the vouch unless [capTryTakes], code QA H1; another try caches what it brings), the
     * failed cap try's re-send ([capTryResends]) and `still` between beats only with the vouch itself (code QA L4).
     */
    private fun stillDay(
        results: Map<Int, TryResult> = emptyMap(),
        fireAt: Set<Int> = emptySet(),
        hours: Int = 24,
    ): List<StillBeat> {
        val interval = 10 * minute
        val step = interval + 2_500
        val base = 1_800_000_000_000L - 1_800_000_000_000L % (24 * 60 * minute) + (7 * 60 + 30) * minute
        fun hhmm(t: Long) = ((t - base) / minute + 7 * 60 + 30).let { m -> (m / 60 % 24) * 100 + m % 60 }.toInt()
        var flags = MotionFlags().afterTry(0, base, heartbeat = true, nowMs = base)
        var lastBeat = base
        var lastTry = base
        var lastFresh = base
        var cached = base
        var renewedAt = 0L
        var capFails = 0
        val out = mutableListOf<StillBeat>()
        var t = base
        while (t < base + hours * 60 * minute) {
            t += step
            val at = hhmm(t)
            if (at in fireAt) flags = flags.moved(0)
            val motion = flags.motion(sensor = true, anchor = true)
            val stillFrom = maxOf(lastFresh, renewedAt)
            val beat = heartbeatDue(t, lastBeat, interval, motion, stillFrom, null, lastTry, flags.stillSinceMs, capFails)
            lastBeat = t
            if (beat != Heartbeat.FIX) {
                val still = reportStill(true, L.CACHED, motion, t, stillFrom, capFails, cachedIsVouch = cached == lastFresh)
                out += StillBeat(((t - base) / minute).toInt(), at, beat, still, capFails)
                continue
            }
            lastTry = t
            // The Jelly's 11:31: an Apple fix renewed the vouch (no fresh fix).
            val renewed = at == 1131
            val result = results[at] ?: TryResult.NONE
            val capTry = motion == Motion.STILL
            val fixTime = when (result) {
                TryResult.FRESH, TryResult.UNCHECKED_WIFI -> t
                // A cap try never falls back to a last-known fix; another try does.
                TryResult.LAST_KNOWN -> if (capTry) null else t - 5 * minute
                TryResult.NONE -> null
            }
            val notFresh = result == TryResult.UNCHECKED_WIFI
            if (capTry) {
                if (capTryTakes(fixTime, t, notFresh, renewed) && !renewed && fixTime != null) cached = fixTime
            } else if (fixTime != null) {
                cached = fixTime
            }
            val fresh = if (result == TryResult.FRESH) t else null
            if (renewed) renewedAt = t
            if (fresh != null) lastFresh = fresh
            flags = flags.afterTry(flags.fires, fresh, heartbeat = true, nowMs = t)
            capFails = stillCapFailsAfter(capFails, beat, motion, fresh != null, renewed, held = true)
            val capFailed = capTry && fresh == null && !renewed
            val resend = capFailed && capTryResends(cached, lastFresh, held = true, stillVouched(motion, t, maxOf(lastFresh, renewedAt), capFails))
            out += StillBeat(((t - base) / minute).toInt(), at, beat, renewed || resend, capFails)
        }
        return out
    }

    @Test
    fun `a failed try at the cap is no move - STILL goes on, the tries back off`() {
        val day = stillDay()
        // The tries: the cap's at 11:31 (renewed) and 15:32 (failed), then the next two beats, 1 h, 2 h, every 4 h.
        assertEquals(listOf(1131, 1532, 1542, 1552, 1652, 1852, 2253, 254, 655), day.filter { it.beat == Heartbeat.FIX }.map { it.at })
        // Every beat says still - the failed tries re-send the vouch too (code QA H1); no QUIET, the PWA's "Hasn't
        // moved since" never lapses.
        assertTrue(day.all { it.still })
        assertTrue(day.filter { it.beat != Heartbeat.FIX }.all { it.beat == Heartbeat.STILL })
        assertEquals(1, day.single { it.at == 1532 }.fails)
        // Code QA H1: an unchecked Wi-Fi fix or a last-known fix at 15:32 never replaces the vouch - it is re-sent with
        // still, the count 1, and the day goes on exactly as with nothing.
        for (result in listOf(TryResult.UNCHECKED_WIFI, TryResult.LAST_KNOWN)) {
            val other = stillDay(results = mapOf(1532 to result))
            val tried = other.single { it.at == 1532 }
            assertTrue(result.name, tried.beat == Heartbeat.FIX && tried.still && tried.fails == 1)
            assertEquals(result.name, day, other)
        }
        // A good fix starts it over: the 16:52 try finds one - STILL to 20:53, the cap's try, then the next two beats.
        val good = stillDay(results = mapOf(1652 to TryResult.FRESH))
        assertEquals(listOf(1131, 1532, 1542, 1552, 1652, 2053, 2103, 2113, 2213), good.filter { it.beat == Heartbeat.FIX }.map { it.at }.take(9))
        assertEquals(0, good.single { it.at == 1652 }.fails)
        // A fire: the next beat tries (MOVED), its failures back off as before (QUIET, no still) until a fresh fix;
        // after it the cap's failures count from none again.
        val fired = stillDay(results = mapOf(1822 to TryResult.FRESH), fireAt = setOf(1702))
        val firedTries = fired.filter { it.beat == Heartbeat.FIX }.map { it.at }
        assertEquals(listOf(1131, 1532, 1542, 1552, 1652, 1702, 1712, 1722, 1742, 1822, 2223, 2233, 2243, 2344), firedTries.take(14))
        assertTrue(fired.filter { it.at in 1703..1821 && it.beat != Heartbeat.FIX }.all { it.beat == Heartbeat.QUIET && !it.still })
        assertTrue(fired.filter { it.at in 1823..2222 }.all { it.beat == Heartbeat.STILL && it.still })
        assertEquals(1, fired.single { it.at == 2223 }.fails)
        // Code QA M1: the vouch (renewed at 11:31, 241 min after 07:30) keeps still for STILL_CEILING_MS at most; then
        // QUIET with the fix's own age, and the tries go on every 4 h without still.
        val ceiling = 241 + (STILL_CEILING_MS / minute).toInt()
        val long = stillDay(hours = 32)
        assertTrue(long.filter { it.m < ceiling }.all { it.still })
        assertTrue(long.filter { it.m >= ceiling }.none { it.still })
        assertTrue(long.filter { it.m >= ceiling && it.beat != Heartbeat.FIX }.all { it.beat == Heartbeat.QUIET })
        assertEquals(1, long.count { it.m >= ceiling && it.beat == Heartbeat.FIX })
        assertEquals(24 * 60 * minute, STILL_CEILING_MS)
        // The schedule: the next two beats, 1 h, 2 h, then 4 h; never under the interval.
        val interval = 10 * minute
        assertEquals(listOf(interval, interval, 60 * minute, 120 * minute, STILL_SKIP_MAX_MS, STILL_SKIP_MAX_MS), (1..6).map { capRetryMs(interval, it) })
        assertEquals(90 * minute, capRetryMs(90 * minute, 3))
        // The count: a failed try at or past the cap while STILL adds one; a fresh fix or a renewal clears it; a fire
        // during the attempt, another motion or another beat leaves it.
        assertEquals(3, stillCapFailsAfter(2, Heartbeat.FIX, Motion.STILL, fresh = false, renewed = false, held = true))
        assertEquals(0, stillCapFailsAfter(2, Heartbeat.FIX, Motion.STILL, fresh = true, renewed = false, held = true))
        assertEquals(0, stillCapFailsAfter(2, Heartbeat.FIX, Motion.STILL, fresh = false, renewed = true, held = true))
        assertEquals(2, stillCapFailsAfter(2, Heartbeat.FIX, Motion.STILL, fresh = false, renewed = false, held = false))
        assertEquals(2, stillCapFailsAfter(2, Heartbeat.FIX, Motion.STILL_SINCE_TRY, fresh = false, renewed = false, held = true))
        assertEquals(2, stillCapFailsAfter(2, null, Motion.STILL, fresh = false, renewed = false, held = true))
        // Unchanged: no sensor (or distrusted) fixes every due beat, the fired cases keep QUIET, whatever the count.
        val now = 1_800_000_000_000L
        val vouch = now - STILL_SKIP_MAX_MS - minute
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.NO_SENSOR, vouch, null, now - minute, vouch, capFails = 3))
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.MOVED, vouch, null, now - minute, vouch, capFails = 3))
        assertEquals(Heartbeat.QUIET, heartbeatDue(now, now - interval, interval, Motion.STILL_SINCE_TRY, vouch, null, now - minute, vouch - minute, capFails = 3))
        // The ceiling: waiting past it is QUIET, a due try still a FIX.
        val old = now - STILL_CEILING_MS
        assertEquals(Heartbeat.STILL, heartbeatDue(now, now - interval, interval, Motion.STILL, old + minute, null, now - minute, old, capFails = 5))
        assertEquals(Heartbeat.QUIET, heartbeatDue(now, now - interval, interval, Motion.STILL, old, null, now - minute, old, capFails = 5))
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.STILL, old, null, now - STILL_SKIP_MAX_MS, old, capFails = 5))
        // The clock set back before the vouch: due.
        assertEquals(Heartbeat.FIX, heartbeatDue(now, now - interval, interval, Motion.STILL, now + minute, null, now - minute, now, capFails = 3))
    }

    @Test
    fun `a cap try keeps the vouch unless it brings a genuinely fresh fix`() {
        val now = 1_800_000_000_000L
        // Code QA H1: what the cap try takes - a fix made for the request, or the renewed vouch.
        assertTrue(capTryTakes(now + 5_000, now, notFresh = false, renewed = false))
        assertTrue(capTryTakes(null, now, notFresh = false, renewed = true))
        assertFalse(capTryTakes(now + 5_000, now, notFresh = true, renewed = false))
        assertFalse(capTryTakes(now - FRESH_FIX_SLACK_MS - 1, now, notFresh = false, renewed = false))
        assertFalse(capTryTakes(null, now, notFresh = false, renewed = false))
        // Failed, it re-sends the vouch with still only when the cached fix is the vouch, no fire came and the vouch
        // holds; else no fix at all (P-L3).
        val vouch = now - 9 * 60 * minute
        assertTrue(capTryResends(vouch, vouch, held = true, vouched = true))
        assertFalse(capTryResends(vouch + minute, vouch, held = true, vouched = true))
        assertFalse(capTryResends(null, vouch, held = true, vouched = true))
        assertFalse(capTryResends(vouch, vouch, held = false, vouched = true))
        assertFalse(capTryResends(vouch, vouch, held = true, vouched = false))
        // Code QA L4: past the cap still goes only with the vouch itself (never a promoted kept fix); within the 4 h as
        // before.
        assertFalse(reportStill(true, L.CACHED, Motion.STILL, now, vouch, capFails = 1, cachedIsVouch = false))
        assertTrue(reportStill(true, L.CACHED, Motion.STILL, now, vouch, capFails = 1, cachedIsVouch = true))
        assertTrue(reportStill(true, L.CACHED, Motion.STILL, now, now - minute, capFails = 0, cachedIsVouch = false))
        assertFalse(stillVouched(Motion.STILL, now, now - STILL_CEILING_MS, capFails = 1))
        assertTrue(stillVouched(Motion.STILL, now, now - STILL_CEILING_MS + 1, capFails = 1))
    }

    @Test
    fun `the motion flags - a fire during an attempt is never erased`() {
        val t = 1_800_000_000_000L
        // A new process: moved; no sensor or a distrusted one: none; no anchor: moved.
        val start = MotionFlags()
        assertEquals(Motion.MOVED, start.motion(sensor = true, anchor = true))
        assertEquals(Motion.NO_SENSOR, start.motion(sensor = false, anchor = true))
        // A fresh fix, from any path: still since its time.
        val still = start.afterTry(0, freshFixMs = t, heartbeat = false, nowMs = t + 5_000)
        assertEquals(MotionFlags(false, false, 0, t), still)
        assertEquals(Motion.STILL, still.motion(sensor = true, anchor = true))
        assertEquals(Motion.MOVED, still.motion(sensor = true, anchor = false))
        // A fire (or a mark): moved, counted.
        val moved = still.moved(0)
        assertEquals(MotionFlags(true, true, 1, t), moved)
        // L7: a failed locate, low-battery or crossing fix clears nothing.
        assertEquals(moved, moved.afterTry(1, freshFixMs = null, heartbeat = false, nowMs = t + minute))
        // The heartbeat's failed try: still since it, but no vouch - and a second failure keeps the first instant.
        val tried = moved.afterTry(1, freshFixMs = null, heartbeat = true, nowMs = t + minute)
        assertEquals(MotionFlags(true, false, 1, t + minute), tried)
        assertEquals(Motion.STILL_SINCE_TRY, tried.motion(sensor = true, anchor = true))
        assertEquals(tried, tried.afterTry(1, freshFixMs = null, heartbeat = true, nowMs = t + 30 * minute))
        // M1: a fire during the attempt (the count moved past the snapshot) - neither a fix nor a failure clears it.
        val firedDuring = tried.moved(0)
        assertEquals(firedDuring, firedDuring.afterTry(1, freshFixMs = t + 2 * minute, heartbeat = true, nowMs = t + 2 * minute))
        assertEquals(firedDuring, firedDuring.afterTry(1, freshFixMs = null, heartbeat = true, nowMs = t + 2 * minute))
        assertEquals(Motion.MOVED, firedDuring.motion(sensor = true, anchor = true))
    }

    @Test
    fun `a fix on the move vouches for nothing - the moving car the sensor may not hear`() {
        val t = 1_800_000_000_000L
        val interval = 30 * minute
        val start = QueuedFix(59.9139, 10.7522, 20f, t)
        // A speed decides: over 1.5 m/s is on the move.
        assertTrue(fixMoving(20f, start, null, interval))
        assertFalse(fixMoving(0.3f, start, null, interval))
        assertFalse(fixMoving(MOVING_SPEED_MPS, start, null, interval))
        // Without one: 1 km from the last fresh fix 10 min before is on the move, 150 m isn't; a last fresh fix
        // from before the interval, or none, proves nothing.
        val km = QueuedFix(59.9229, 10.7522, 20f, t + 10 * minute)
        assertTrue(fixMoving(null, km, start, interval))
        assertFalse(fixMoving(null, QueuedFix(59.91525, 10.7522, 20f, t + 10 * minute), start, interval))
        assertFalse(fixMoving(null, km.copy(timeMs = t + interval + minute), start, interval))
        assertFalse(fixMoving(null, km, null, interval))
        // The heartbeat: a car's fix at 20 m/s, then no fire - the next due beat fixes; a fix at 0.3 m/s - STILL.
        fun nextBeat(speed: Float): Heartbeat {
            val flags = MotionFlags().afterTry(0, t, heartbeat = true, nowMs = t, moving = fixMoving(speed, start, null, interval))
            return heartbeatDue(t + interval, t, interval, flags.motion(sensor = true, anchor = true), t, null, t, flags.stillSinceMs)
        }
        assertEquals(Heartbeat.FIX, nextBeat(20f))
        assertEquals(Heartbeat.STILL, nextBeat(0.3f))
        // A failed try after the car's fix is no QUIET either: every due beat fixes until a fix at rest.
        val failed = MotionFlags().afterTry(0, t, true, t, moving = true).afterTry(0, null, heartbeat = true, nowMs = t + interval)
        assertEquals(Motion.MOVED, failed.motion(sensor = true, anchor = true))
        assertEquals(Motion.STILL, failed.afterTry(0, t + 2 * interval, true, t + 2 * interval, moving = false).motion(sensor = true, anchor = true))
    }

    private val interval = com.kidslauncher.mdm.server.dto.LocationPolicy(mode = "interval", intervalMinutes = 60, zones = listOf(home))

    @Test
    fun `the still rule is on for every Regularly phone, the zone watch only with zones`() {
        val noZones = interval.copy(zones = emptyList())
        for (policy in listOf(interval, noZones)) {
            assertTrue(stillWatchActive(owner = true, managed = true, policy = policy))
            assertFalse(stillWatchActive(owner = false, managed = true, policy = policy))
            assertFalse(stillWatchActive(owner = true, managed = false, policy = policy))
            assertFalse(stillWatchActive(owner = true, managed = true, policy = policy.copy(mode = "on_request")))
            assertFalse(stillWatchActive(owner = true, managed = true, policy = policy.copy(mode = "off")))
        }
        assertFalse(stillWatchActive(owner = true, managed = true, policy = null))
        assertTrue(zoneWatchActive(owner = true, managed = true, policy = interval))
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = noZones))
        assertFalse(zoneWatchActive(owner = false, managed = true, policy = interval))
        assertFalse(zoneWatchActive(owner = true, managed = false, policy = interval))
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = interval.copy(mode = "on_request")))
        assertFalse(zoneWatchActive(owner = true, managed = true, policy = null))
    }

    @Test
    fun `not Regularly, not owner or not managed - the location step is locationAction, for every input`() {
        val now = 1_800_000_000_000L
        val policies = listOf(
            null,
            com.kidslauncher.mdm.server.dto.LocationPolicy(),
            interval.copy(zones = emptyList()),
            interval.copy(zones = emptyList(), intervalMinutes = 10),
            interval.copy(mode = "on_request", zones = emptyList()),
            interval.copy(mode = "off", zones = emptyList()),
            // The gate is the caller's: with stillOn false even a Regularly policy is locationAction's.
            interval,
        )
        val ages = listOf(-minute, 0L, minute, 9 * minute, 10 * minute, 59 * minute, 60 * minute, 61 * minute, 5 * 60 * minute)
        for (policy in policies) for (forced in listOf(false, true)) for (precise in listOf(false, true)) for (age in ages)
            for (motion in Motion.values()) for (kept in listOf(null, 0L, minute)) for (beat in listOf(0L, now - minute, now)) {
                val got = syncLocationAction(policy, stillOn = false, forced = forced, precise = precise, nowMs = now,
                    lastOwnFixMs = now - age, lastBeatMs = beat, motion = motion, keptFixAgeMs = kept, lastFreshFixMs = now - age)
                assertEquals(com.kidslauncher.mdm.server.locationAction(policy, forced, age), got.action)
                assertNull(got.beat)
            }
        // ... and the backstop counts from our last own fix; on a Regularly phone from the beat.
        assertEquals(5L, backstopAnchorMs(stillOn = false, lastBeatMs = 9, lastOwnFixMs = 5))
        assertEquals(9L, backstopAnchorMs(stillOn = true, lastBeatMs = 9, lastOwnFixMs = 5))
        // ... and no still flag (Q-3).
        assertFalse(reportStill(false, com.kidslauncher.mdm.server.LocationAction.CACHED, Motion.STILL, now, now - minute))
    }

    @Test
    fun `on a Regularly phone the heartbeat decides, zones or not, a command and a crossing fix`() {
        val now = 1_800_000_000_000L
        for (policy in listOf(interval, interval.copy(zones = emptyList()))) {
            fun act(forced: Boolean = false, precise: Boolean = false, beat: Long = now - 61 * minute, motion: Motion = Motion.MOVED, kept: Long? = null,
                    fresh: Long = now - 61 * minute) =
                syncLocationAction(policy, stillOn = true, forced = forced, precise = precise, nowMs = now,
                    lastOwnFixMs = now - 61 * minute, lastBeatMs = beat, motion = motion, keptFixAgeMs = kept, lastFreshFixMs = fresh,
                    stillSinceMs = now - 61 * minute).let { it.action to it.beat }
            assertEquals(L.FRESH to null, act(forced = true, precise = true))
            assertEquals(L.FRESH_BALANCED to null, act(forced = true))
            assertEquals(L.FRESH_BALANCED to Heartbeat.FIX, act())
            assertEquals(L.CACHED to Heartbeat.NOT_DUE, act(beat = now - minute))
            assertEquals(L.CACHED to Heartbeat.KEPT, act(kept = 5 * minute))
            assertEquals(L.CACHED to Heartbeat.STILL, act(motion = Motion.STILL))
            // F-3: the 4 h count from the last genuinely fresh fix - failed attempts (the own-fix stamp
            // 61 min ago) don't restart it; the cap's try is a FIX when the back-off has passed.
            assertEquals(L.FRESH_BALANCED to Heartbeat.FIX, act(motion = Motion.STILL, fresh = now - STILL_SKIP_MAX_MS))
            assertEquals(L.FRESH_BALANCED to Heartbeat.FIX, act(motion = Motion.STILL_SINCE_TRY, fresh = 0))
        }
        // The back-off rides along for the log line.
        assertEquals(60 * minute, syncLocationAction(interval, true, false, false, now, now - 61 * minute, now - 61 * minute, Motion.MOVED, null, 0, now).backoffMs)
        // Only Regularly has a heartbeat: on request and Off are locationAction's.
        assertEquals(L.NONE, syncLocationAction(interval.copy(mode = "on_request"), true, false, false, now, 0, 0, Motion.MOVED, null, 0).action)
    }

    @Test
    fun `a re-sent fix is still current only while the sensor says so`() {
        val now = 1_800_000_000_000L
        assertTrue(reportStill(true, L.CACHED, Motion.STILL, now, now - 3 * 60 * minute))
        assertFalse(reportStill(true, L.CACHED, Motion.STILL, now, now - STILL_SKIP_MAX_MS))
        assertFalse(reportStill(true, L.CACHED, Motion.MOVED, now, now - minute))
        // A failed try since the move vouches for nothing: the fix's own age shows.
        assertFalse(reportStill(true, L.CACHED, Motion.STILL_SINCE_TRY, now, now - minute))
        assertFalse(reportStill(true, L.CACHED, Motion.NO_SENSOR, now, now - minute))
        assertFalse(reportStill(true, L.FRESH_BALANCED, Motion.STILL, now, now - minute))
        assertFalse(reportStill(true, L.CACHED, Motion.STILL, now, now + minute))
        // Amended 2026-11-25: past the 4 h after a failed try, still while the sensor hasn't fired - never otherwise.
        assertTrue(reportStill(true, L.CACHED, Motion.STILL, now, now - 9 * 60 * minute, capFails = 1))
        assertFalse(reportStill(true, L.CACHED, Motion.STILL_SINCE_TRY, now, now - 9 * 60 * minute, capFails = 1))
        assertFalse(reportStill(true, L.CACHED, Motion.NO_SENSOR, now, now - 9 * 60 * minute, capFails = 1))
        assertFalse(reportStill(true, L.FRESH_BALANCED, Motion.STILL, now, now - 9 * 60 * minute, capFails = 1))
        assertFalse(reportStill(false, L.CACHED, Motion.STILL, now, now - 9 * 60 * minute, capFails = 1))
        assertFalse(reportStill(true, L.CACHED, Motion.STILL, now, now + minute, capFails = 1))
    }

    @Test
    fun `two contradictions in a week distrust the sensor for a week`() {
        val vouched = QueuedFix(59.9139, 10.7522, 20f, 0)
        // 1 km away, both within 100 m: a contradiction; 200 m away is within the slack.
        assertTrue(motionContradiction(vouched, QueuedFix(59.9229, 10.7522, 20f, 1)))
        assertFalse(motionContradiction(vouched, QueuedFix(59.9157, 10.7522, 20f, 1)))
        // An accuracy over 100 m, or none, or nothing vouched: proves nothing.
        assertFalse(motionContradiction(vouched, QueuedFix(59.9229, 10.7522, 150f, 1)))
        assertFalse(motionContradiction(vouched.copy(accuracy = 101f), QueuedFix(59.9229, 10.7522, 20f, 1)))
        assertFalse(motionContradiction(vouched.copy(accuracy = null), QueuedFix(59.9229, 10.7522, 20f, 1)))
        assertFalse(motionContradiction(null, QueuedFix(59.9229, 10.7522, 20f, 1)))
        val t = 1_800_000_000_000L
        val day = 24 * 60 * minute
        // One: kept, not distrusted; a second within 7 days: distrusted 7 days, the list starts over.
        assertEquals(MotionTrust(listOf(t), 0), trustAfterContradiction(emptyList(), t))
        assertEquals(MotionTrust(emptyList(), t + 6 * day + MOTION_DISTRUST_MS), trustAfterContradiction(listOf(t), t + 6 * day))
        // A week apart: the old one is gone (and one from the future - the clock set back - too).
        assertEquals(MotionTrust(listOf(t + 7 * day), 0), trustAfterContradiction(listOf(t), t + 7 * day))
        assertEquals(MotionTrust(listOf(t), 0), trustAfterContradiction(listOf(t + day), t))
        assertTrue(motionDistrusted(t + 1, t))
        assertTrue(motionDistrusted(t + MOTION_DISTRUST_MS, t))
        assertFalse(motionDistrusted(t, t))
        assertFalse(motionDistrusted(0, t))
        // Never longer than a week from now, whatever the clock did.
        assertFalse(motionDistrusted(t + MOTION_DISTRUST_MS + 1, t))
    }

    @Test
    fun `the balanced fix is kept within 200 m, else the better of it and GPS`() {
        assertTrue(balancedEnough(25f))
        assertTrue(balancedEnough(PASSIVE_ACCURACY_FLOOR_M))
        assertFalse(balancedEnough(201f))
        assertFalse(balancedEnough(null))
        assertFalse(balancedEnough(Float.NaN))
        // GPS replaces it only when it is more accurate; a missing accuracy is the worst.
        assertTrue(gpsBetter(1_500f, 12f))
        assertFalse(gpsBetter(1_500f, 2_000f))
        assertFalse(gpsBetter(300f, 300f))
        assertTrue(gpsBetter(null, 12f))
        assertFalse(gpsBetter(1_500f, null))
        assertFalse(gpsBetter(null, null))
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
    fun `design 125b C10 - a coarse fix is never inside and outside only far`() {
        fun cell(m: Double, accuracy: Float, provider: String = "vibb_cell") = north(m, accuracy).copy(provider = provider)
        val big = home.copy(radiusM = 2000)
        // Inside by geometry: GPS inside, a cell fix unsure.
        assertEquals(true, sureSide(north(0.0, accuracy = 500f), big))
        assertEquals(null, sureSide(cell(0.0, 500f), big))
        assertEquals(null, sureSide(cell(0.0, 500f, "vibb_apple_cell"), big))
        // 1 km away within 30 m: GPS outside, a cell fix (under 20 km past the edge) unsure.
        assertEquals(null, sureSide(cell(1000.0, 30f), home))
        // 19 km past the edge within 1 km unsure, 21 km outside; within 10 km three accuracies count.
        assertEquals(null, sureSide(cell(150.0 + 19_000.0, 1000f), home))
        assertEquals(false, sureSide(cell(150.0 + 21_000.0, 1000f), home))
        assertEquals(null, sureSide(cell(150.0 + 25_000.0, 10_000f), home))
        assertEquals(false, sureSide(cell(150.0 + 31_000.0, 10_000f), home))
        // A near cell fix never starts a crossing check.
        assertEquals(CheckVerdict.KEEP, checkVerdict(north(1000.0), cell(30.0, 500f), listOf(big)))
        // A GLA-on phone's `network` fix is not coarse (Q8).
        assertEquals(true, sureSide(north(0.0, accuracy = 500f).copy(provider = "network"), big))
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
        // Q-2: the zone watch gates the fences, the series and the queue; design 124 D1: the still rule
        // (every Regularly phone) the heartbeat, the motion gate and the backstop.
        assertTrue(sync.contains("val zonesOn = ZoneWatch.zonesOn(context, decision.policy)"))
        assertTrue(sync.contains("val stillOn = ZoneWatch.stillOn(context, decision.policy)"))
        assertTrue(sync.contains("val zoneFix = ZoneWatch.takeFreshWanted()?.takeIf { zonesOn }"))
        assertTrue(sync.contains("val sync = syncLocationAction("))
        assertTrue(sync.contains("stillOn = stillOn,"))
        assertFalse(sync.contains("heartbeatDue("))
        val watch = source("zones/ZoneWatch.kt")
        // G8: as device owner and managed only.
        assertTrue(watch.contains("fun zonesOn(context: Context, policy: PolicyResponse?): Boolean = gate(context, policy, ::zoneWatchActive)"))
        assertTrue(watch.contains("fun stillOn(context: Context, policy: PolicyResponse?): Boolean = gate(context, policy, ::stillWatchActive)"))
        assertTrue(watch.contains("rule(owner, managed, policy?.locationPolicy)"))
        // Design 125 D3: the passive feed on every Regularly phone (it teaches), its kept slot with zones only.
        assertTrue(watch.contains("val still = stillOn(app, policy)"))
        assertTrue(watch.contains("PassiveFixes.update(app, on = still, keep = on)"))
        // D2: the sensor on every Regularly phone (kept armed for the series with zones), stopped
        // - and so moved - only when not Regularly.
        assertTrue(Regex("""if \(still\) \{\s+MotionGate\.watch\(app, zones = on\)\s+\} else \{\s+MotionGate\.stop\(app\)""").containsMatchIn(watch))
        assertTrue(watch.contains("ZoneFences.sync(app, zones, watching = on)"))
        // G7: a fence's zones only from a Regularly policy.
        assertTrue(watch.contains("currentPolicyDecision().policy?.locationPolicy?.takeIf { it.mode == \"interval\" }?.zones.orEmpty()"))
        // G2: the backstop counts from the beat on a Regularly phone.
        val backstop = source("push/BackstopAlarm.kt")
        assertTrue(backstop.contains("val stillOn = com.kidslauncher.mdm.zones.ZoneWatch.stillOn(context, policy)"))
        assertTrue(Regex("""backstopAnchorMs\(\s*stillOn,\s*com\.kidslauncher\.mdm\.zones\.ZoneWatch\.lastBeatMs\(context\),\s*LauncherPreferences\.mdm\(\)\.lastActiveLocationFetchAtMs\(\),\s*\)""").containsMatchIn(backstop))
        // G3: every due call and every fresh fix stamps the beat; a fix also the last try, at the same
        // instant, and the back-off counts from that stamp (QA M1), never LocationManager's later one.
        assertTrue(sync.contains("if (action.fresh || (beat != null && beat != Heartbeat.NOT_DUE)) ZoneWatch.stampBeat(context, now, tried = action.fresh)"))
        assertTrue(sync.contains("lastOwnFixMs = if (stillOn) ZoneWatch.lastTryMs(context) else lastOwnFix,"))
        assertTrue(Regex("""val edit = prefs\(context\)\.edit\(\)\.putLong\(KEY_LAST_BEAT, nowMs\)\s+if \(tried\) edit\.putLong\(KEY_LAST_TRY, nowMs\)""").containsMatchIn(watch))
        // G1/Q-6, D3: the flags clear only after a genuinely fresh own fix (pinned with F-3's stamp below),
        // or the heartbeat's own failed try; against the fire count read before the attempt (M1).
        val seriesSource = source("zones/ZoneSeries.kt")
        assertTrue(seriesSource.contains("val fresh = freshOwnFix(location.time, requestedAt)"))
        assertTrue(Regex("""val firesAtStart = MotionGate\.snapshot\(context\)\.fires\s+val location = LocateCommands\.seriesFix\(context\)""").containsMatchIn(seriesSource))
        assertTrue(Regex("""if \(verdict == CheckVerdict\.KEEP\) \{\s+if \(fresh\) \{[\s\S]{0,200}MotionGate\.afterTry\(context, firesAtStart, location\.time, heartbeat = false, nowMs = requestedAt, moving = moving\)\s+(//[^\n]*\s+)*MotionGate\.afterFreshFix\(context, firesAtStart, location\.trustedTimeMs\(\), moving, placeAgainst\(previousFresh, fix\), noSensor = !\(MotionGate\.hasSensor\(context\) && ZoneWatch\.motionTrusted\(context\)\)\)\s+\}\s+return\s+\}""").containsMatchIn(seriesSource))
        // Since when the phone is still (code QA H1, N1/N2): both paths compare with the last fresh fix read before this
        // fix is stamped, and tell the origin when no trusted sensor watched (D7's distrust ends with no older time).
        assertTrue(Regex("""val previousFresh = ZoneWatch\.lastFreshFix\(context\)[\s\S]{0,400}if \(fresh\) ZoneWatch\.stampFreshFix\(context, fix\)""").containsMatchIn(seriesSource))
        assertTrue(Regex("""val previousFresh = ZoneWatch\.lastFreshFix\(context\)[\s\S]{0,400}if \(fresh != null\) ZoneWatch\.stampFreshFix\(""").containsMatchIn(sync))
        assertTrue(Regex("""val held = MotionGate\.afterTry\([^\n]*\n(\s*//[^\n]*\n)*\s*if \(held && fresh != null\) \{\s+MotionGate\.afterFreshFix\(context, motionNow\.fires, fresh\.trustedTimeMs\(\), moving, placeAgainst\(previousFresh, fresh\.toQueued\(\)\), noSensor = motion == Motion\.NO_SENSOR\)\s+\}""").containsMatchIn(sync))
        assertEquals(1, Regex("""MotionGate\.afterFreshFix\(""").findAll(sync).count())
        // The moving car: a series check's fix on the move vouches for nothing either.
        assertTrue(seriesSource.contains("val moving = fixMoving(if (location.hasSpeed()) location.speed else null, fix, lastFresh, CHECK_MAX_DELAY_MS)"))
        assertTrue(Regex("""val lastFresh = previous\?\.takeIf \{ it\.timeMs == ZoneWatch\.lastFreshFixMs\(context\) \}\s+if \(fresh\) ZoneWatch\.stampFreshFix""").containsMatchIn(seriesSource))
        assertTrue(Regex("""val motionNow = if \(stillOn\) MotionGate\.snapshot\(context\) else null[\s\S]*val attempted = LocateCommands\.currentLocation\(""").containsMatchIn(sync))
        assertTrue(sync.contains("val held = MotionGate.afterTry(context, motionNow.fires, fresh?.time, heartbeat = beat == Heartbeat.FIX, nowMs = now, moving = moving, withheld = places.withheld)"))
        // The moving car: a fresh fix's speed, else its distance from the last fresh fix within the interval.
        assertTrue(sync.contains("fixMoving(if (fresh.hasSpeed()) fresh.speed else null, fresh.toQueued(), lastFreshQueued, intervalMs)"))
        // F-3: a fence crossing and a series' side change are motion; the 4 h count from the last
        // genuinely fresh own fix, stamped only with freshOwnFix.
        assertTrue(Regex("""if \(verdict != FenceVerdict\.CROSSING\) return[\s\S]{0,300}MotionGate\.markMoved\(\)""").containsMatchIn(source("zones/ZoneWatch.kt")))
        assertTrue(Regex("""MotionGate\.markMoved\(\)\s+LocateCommands\.promoteKept\(context\)""").containsMatchIn(seriesSource))
        assertTrue(seriesSource.contains("if (fresh) ZoneWatch.stampFreshFix(context, fix)"))
        // Design 125 M2: a learned fix from the 2-min fallback and a renewed vouch are no fresh fix.
        assertTrue(sync.contains("val fresh = attempted?.takeIf { freshOwnFix(it.time, now) && !places.notFresh && !places.renewed }\n        if (fresh != null) ZoneWatch.stampFreshFix(context, fresh.toQueued(LocateCommands.providerOf(fresh)))"))
        assertTrue(sync.contains("val lastFreshFix = if (stillOn) ZoneWatch.lastFreshFixMs(context) else lastOwnFix"))
        // Design 125 D12: the still skip counts from the later of the fresh fix and a zone link's renewal.
        assertTrue(sync.contains("val stillFrom = if (stillOn) maxOf(lastFreshFix, ZoneWatch.vouchRenewedMs(context)) else lastOwnFix"))
        assertTrue(sync.contains("lastFreshFixMs = stillFrom,"))
        assertEquals(1, Regex("""stampFreshFix\(""").findAll(sync).count())
        // D7: a fresh fix while the gate said STILL is checked against the fix it vouched for (QA L2),
        // and only when no fire came during the attempt (QA L1).
        assertTrue(sync.contains("val lastFreshQueued = if (motionNow != null && action.fresh) LocateCommands.cachedQueued()?.takeIf { it.timeMs == lastFreshFix } else null"))
        // Design 125 D15: only a satellite fix vouches, and a learned fix never contradicts; design 134 D18: after a mismatch too.
        assertTrue(sync.contains("val vouched = lastFreshQueued?.takeIf { d7Applies(motion, motionNow?.mismatched == true) && it.provider in VOUCHING_PROVIDERS }"))
        assertTrue(sync.contains("if (held && fresh != null && satellite && motionContradiction(vouched, fresh.toQueued())) ZoneWatch.noteContradiction(context)"))
        assertTrue(sync.contains("private val VOUCHING_PROVIDERS = setOf(\"gps\", \"fused\")"))
        assertTrue(watch.contains("motionSensor = MotionGate.hasSensor(app) && motionTrusted(app),"))
        // D4: no anchor is moved; the anchor's end disarms and marks it (QA L3), and Off marks it.
        val sensorsSource = source("zones/ZoneSensors.kt")
        assertTrue(sensorsSource.contains("flags.motion(trusted, com.kidslauncher.mdm.server.CommandListenerService.isRunning)"))
        assertTrue(sensorsSource.contains("val trusted = hasSensor(context) && ZoneWatch.motionTrusted(context)"))
        assertTrue(Regex("""fun anchorStopped\(context: Context\) = disarm\(context\)""").containsMatchIn(sensorsSource))
        assertTrue(Regex("""private fun disarm\(context: Context\) \{\s+val l = listener\s+listener = null\s+flags = flags\.moved\(SystemClock\.elapsedRealtime\(\)\)""").containsMatchIn(sensorsSource))
        assertTrue(Regex("""override fun onDestroy\(\) \{[\s\S]*MotionGate\.anchorStopped\(applicationContext\)[\s\S]*super\.onDestroy\(\)""").containsMatchIn(source("server/CommandListenerService.kt")))
        assertTrue(Regex("""fun clearCachedFix\(context: Context\) \{\s+com\.kidslauncher\.mdm\.zones\.MotionGate\.markMoved\(\)""").containsMatchIn(source("server/LocateCommands.kt")))
        // A refused registration marks moved: nothing watches, nothing may vouch.
        // Since when it is still: the arm and the refusal move the origin too.
        assertTrue(Regex("""if \(armed\) \{[\s\S]{0,300}\} else \{[\s\S]{0,80}flags = flags\.moved\(SystemClock\.elapsedRealtime\(\)\)\s+moveOrigin\(context\.applicationContext\) \{ it\.unwatched\(\) \}\s+Log\.w\(LOG_TAG, "Motion trigger refused"\)""").containsMatchIn(sensorsSource))
        // D9: balanced first only with the network provider on (and fused there), GPS when it isn't good enough.
        val balancedFirst = Regex("""private suspend fun balancedFirst\([\s\S]*?\n    }""").find(source("server/LocateCommands.kt"))!!.value
        assertTrue(balancedFirst.contains("val balanced = if (balancedOn) requestBalancedFix(context, lm)?.takeIf { ZoneWatch.accept(context, it) } else null"))
        assertTrue(balancedFirst.contains("val gps = if (askGps) requestFreshFix(context, lm, preciseFix(false))?.takeIf { ZoneWatch.accept(context, it) } else null"))
        assertTrue(balancedFirst.contains("lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)"))
        assertTrue(balancedFirst.contains("!balancedOn -> \"gps\""))
        // F-2: the cooldown and the budget in the glue; a fence crossing starts a series at once.
        assertTrue(seriesSource.contains("if (before == null && !seriesMayStart(fromFence, now, lastEndedAtMs, left)) return"))
        assertTrue(seriesSource.contains("seriesEnd(running, now, ZoneWatch.checksLeftToday(app))"))
        assertTrue(Regex("""return\s+\}\s+ZoneWatch\.spendCheck\(app\)""").containsMatchIn(seriesSource))
        assertTrue(source("zones/ZoneWatch.kt").contains("ZoneSeries.launch { ZoneSeries.onMotion(app, fromFence = true) }"))
        // F-4 (F16): the next check is armed in `finally`, so a failed check never ends the series.
        assertTrue(Regex("""\} finally \{[\s\S]{0,400}if \(still\) arm\(app, next\)""").containsMatchIn(seriesSource))
        // F-4: the motion fire's work leaves the sensor callback's main thread.
        assertTrue(Regex("""ZoneSeries\.launch \{\s+ZoneSeries\.onMotion\(context\)\s+ensureArmed\(context, "series"\)\s+\}""").containsMatchIn(source("zones/ZoneSensors.kt")))
        // Q-1: the kept fix is promoted only at a due KEPT or a crossing's sync, and by a series candidate.
        assertTrue(sync.contains("if (beat == Heartbeat.KEPT || crossing) LocateCommands.promoteKept(context)"))
        assertEquals(1, Regex("""promoteKept\(""").findAll(sync).count())
        // Q-3: the still flag rides the report.
        assertTrue(sync.contains("zoneWatch = ZoneWatch.report(context, still = location.still, wifi = zoneWifi),"))
        assertTrue(sync.contains("val cachedIsVouch = LocateCommands.cachedQueued()?.timeMs == lastFreshFix"))
        assertTrue(sync.contains("val stillBeat = reportStill(stillOn, action, motion, now, stillFrom, capFails, cachedIsVouch, ceilingFrom)"))
        // Code QA H1: the cap try keeps the vouch (LocateCommands' cap branch) and, failed, re-sends it or drops the fix.
        assertTrue(sync.contains("val capTry = beat == Heartbeat.FIX && motion == Motion.STILL"))
        assertTrue(sync.contains("capTryAtMs = if (capTry) now else null,"))
        assertTrue(sync.contains("capFailed = capTry && fresh == null && !places.renewed"))
        assertTrue(sync.contains("capTryResends(LocateCommands.cachedQueued()?.timeMs, lastFreshFix, held, stillVouched(motion, now, stillFrom, capFailsNow, ceilingFromMs = ceilingFrom))"))
        assertTrue(sync.contains("val location = attempted?.takeIf { !capFailed || capResend }"))
        assertTrue(sync.contains("val still = stillBeat || places.renewed || capResend"))
        val locateSource = source("server/LocateCommands.kt")
        assertTrue(locateSource.contains("return freshOrLastKnown(context, action, explicit, ::reportable, places, capTryAtMs)"))
        assertTrue(Regex("""if \(capTryAtMs != null\) \{\s+val takes = com\.kidslauncher\.mdm\.zones\.capTryTakes\(requested\?\.time, capTryAtMs, places\?\.notFresh == true, places\?\.renewed == true\)\s+if \(takes && requested != null\) saveCachedFix\(context, requested\)\s+TrustedClock\.readGnss\(context\)\s+return if \(takes\) requested else loadCachedFix\(\)\?\.takeIf\(reportable\)\s+\}\s+val fresh = requested \?: try \{""").containsMatchIn(locateSource))
        // Design 124 D5 amended 2026-11-25: the cap's failed tries, read before the decision, counted after the
        // attempt; a fresh fix and a renewal clear them.
        assertTrue(sync.contains("val capFails = if (stillOn) ZoneWatch.stillCapFails(context) else 0"))
        assertTrue(sync.contains("capFails = capFails,"))
        assertTrue(sync.contains("val capFailsNow = stillCapFailsAfter(capFails, beat, motion, fresh != null, places.renewed, held)"))
        assertTrue(Regex("""fun stampFreshFix\(context: Context, fix: QueuedFix\) \{[\s\S]{0,250}.remove\(KEY_CAP_FAILS\)\.remove\(KEY_INCONCLUSIVE\)\.commit\(\)""").containsMatchIn(watch))
        assertTrue(watch.contains("val e = prefs(context).edit().putLong(KEY_RENEWED, nowMs).remove(KEY_CAP_FAILS)"))
        // Design 134 revision 4 (code QA M1): a renewal moves the 24-h ceiling's anchor unless the connected AP made it.
        assertTrue(watch.contains("if (movesCeiling) e.putLong(KEY_CEILING_RENEWED, nowMs)"))
        assertTrue(sync.contains("still = still,"))
        // Q-5: Off (and no zones) clears the queue before the report and sends no extras.
        assertTrue(sync.contains("if (queueOff) ZoneWatch.clearQueue(context)"))
        assertTrue(sync.contains("val extraFixes = if (queueOff) null else extraLocations(ZoneWatch.queue(context), location.fix)"))
        // Q-7: the record (and its stamp) before the first add; a partial set has no fingerprint (G6).
        assertTrue(Regex("""saveFenceRecord\(context, FenceRecord\("", plan\.add\.map \{ it\.id \}, registeredAt\)\)[\s\S]{0,200}for \(zone in plan\.add\)""").containsMatchIn(fences))
        assertTrue(fences.contains("val fingerprint = if (added.size == plan.add.size) fenceFingerprint(plan.add) else \"\""))
        assertTrue(watch.contains("val record = markFenceSeen(app, zoneId)"))
        assertTrue(watch.contains("val since = record?.let { now - it.registeredAtElapsed }"))
        // The late echo: the reported and kept fixes read together, the zone's first broadcast, the gate still since.
        assertTrue(watch.contains("val verdict = fenceVerdict(zoneId, zones, entering, since, reported, kept, first, still)"))
        assertTrue(watch.contains("val (reported, kept) = LocateCommands.reportedAndKept()"))
        // G5/Q-9: the mock count by the recent fixes' keys.
        assertTrue(watch.contains("mockKeysAfter(recent, mockKey(location.time, location.latitude, location.longitude)) ?: return"))
        // Un-managed releases every fence, the feed, the series, the queue and the kept fix; a new
        // enrollment forgets the watch's state too (Q-8).
        assertTrue(source("server/AppEnforcer.kt").contains("if (!managedForHardening) com.kidslauncher.mdm.zones.ZoneWatch.release(context)"))
        assertTrue(source("server/Enrollment.kt").contains("com.kidslauncher.mdm.zones.ZoneWatch.forget(context)"))
        // Design r10: both enrollment paths (and the lock's "Ny oppsettkode") go through the one Enrollment.enroll.
        assertTrue(source("server/Provisioning.kt").contains("Enrollment.enroll(context, extras.serverUrl, extras.enrollmentCode, connectTailnet = false)"))
        assertTrue(source("ui/settings/launcher/SettingsFragmentLauncher.kt").contains("Enrollment.enroll(app, serverUrl, enrollmentCode)"))
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
        assertTrue(Regex("""fun afterTry\(context: Context, firesAtStart: Int, freshFixMs: Long\?, heartbeat: Boolean, nowMs: Long, moving: Boolean, withheld: Boolean = false\): Boolean \{\s+val held = synchronized\(this\) \{\s+flags = flags\.afterTry\(firesAtStart, freshFixMs, heartbeat, nowMs, moving, withheld\)\s+flags\.fires == firesAtStart""").containsMatchIn(sensors))
        assertTrue(Regex("""synchronized\(this\) \{\s+flags = flags\.moved\(SystemClock\.elapsedRealtime\(\)\)\s+listener = null""").containsMatchIn(sensors))
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
        // Only promoteKept and the own-fix save write the cached fix; clearCachedFix (location Off, P-L3) removes it.
        assertEquals(3, Regex("""keys\(\)\.cachedLocationJson\(\)""").findAll(locate).count())
        assertTrue(locate.contains("requestFreshFix(context, lm, preciseFix(explicit && action == LocationAction.FRESH))?.takeIf { ZoneWatch.accept(context, it) }"))
        assertTrue(locate.contains(".filter { ZoneWatch.accept(context, it) && reportable(it) }"))
    }
}
