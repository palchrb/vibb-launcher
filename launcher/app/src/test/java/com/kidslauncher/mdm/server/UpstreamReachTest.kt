package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.UpstreamReach.afterAnswer
import com.kidslauncher.mdm.server.UpstreamReach.afterFailure
import com.kidslauncher.mdm.server.UpstreamReach.networkLost
import com.kidslauncher.mdm.server.UpstreamReach.rekey
import com.kidslauncher.mdm.server.dto.DnsUpstreamReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 67 §3: "DNS can't be reached" - the window, its ends, the glue's kinds and the report. */
class UpstreamReachTest {
    private val key = "custom:4"
    private fun fail(s: ReachState, vararg at: Long): ReachState = at.fold(s) { acc, now -> afterFailure(acc, key, now) }

    @Test
    fun `at least 3 failures over at least 30 s, dated from the first`() {
        assertNull(fail(ReachState(), 0, 10_000).downSinceMs)
        assertNull("3 within 29 s", fail(ReachState(), 0, 10_000, 29_000).downSinceMs)
        assertEquals(1_000L, fail(ReachState(), 1_000, 11_000, 31_000).downSinceMs)
        // Failures 20 s apart still count; the next failure keeps the outage as it is.
        val down = fail(ReachState(), 0, 20_000, 40_000)
        assertEquals(0L, down.downSinceMs)
        assertEquals(down, afterFailure(down, key, 900_000))
    }

    @Test
    fun `an answer between resets the count, an answer ends an outage`() {
        assertNull(fail(afterAnswer(fail(ReachState(), 0, 10_000), key, 20_000), 30_000, 40_000).downSinceMs)
        val ended = afterAnswer(fail(ReachState(), 0, 20_000, 40_000), key, 50_000)
        assertEquals(ReachState(key = key, lastOutage = Outage(0, 50_000)), ended)
    }

    @Test
    fun `a failure more than 60 s after the last starts the count again`() {
        val twoHours = 2 * 3_600_000L
        assertNull(fail(ReachState(), 0, twoHours, twoHours + 30_000).downSinceMs)
        assertEquals(twoHours, fail(ReachState(), 0, twoHours, twoHours + 20_000, twoHours + 40_000).downSinceMs)
    }

    @Test
    fun `offline is no outage - a failure without a validated network leaves the state, losing the network ends one`() {
        var now = 0L
        val tracker = ReachTracker(elapsed = { now }, log = {})
        repeat(5) {
            tracker.after(Resolved(null, answered = false), key, validated = false)
            now += 20_000
        }
        assertEquals(ReachState(), tracker.get())
        val validatedAt = now
        repeat(3) {
            tracker.after(Resolved(null, answered = false), key, validated = true)
            now += 20_000
        }
        assertEquals(validatedAt, tracker.get().downSinceMs)
        tracker.networkLost()
        assertEquals(ReachState(key = key, lastOutage = Outage(validatedAt, now)), tracker.get())
        // Wi-Fi validated -> nothing or an unvalidated one is the loss; validated -> validated isn't.
        val wifi = NetworkSnapshot(1, validated = true)
        assertEquals(true, validatedLost(wifi, null))
        assertEquals(true, validatedLost(wifi, NetworkSnapshot(2, validated = false)))
        assertEquals(false, validatedLost(wifi, NetworkSnapshot(2, validated = true)))
        assertEquals(false, validatedLost(null, wifi))
        assertEquals(networkLost(ReachState(), 5), ReachState())
    }

    @Test
    fun `other servers end an ongoing outage into the last one and keep it`() {
        val down = fail(ReachState(), 0, 20_000, 40_000)
        val rev5 = rekey(down, "custom:5", 60_000)
        assertEquals(ReachState(key = "custom:5", lastOutage = Outage(0, 60_000)), rev5)
        // The new servers' first failure counts afresh; the ended outage stays.
        val provider = afterFailure(rev5, "quad9", 70_000)
        assertEquals(ReachState(key = "quad9", failures = 1, firstFailMs = 70_000, lastFailMs = 70_000, lastOutage = Outage(0, 60_000)), provider)
        assertEquals(provider, rekey(provider, "quad9", 80_000))
        assertEquals("custom:4", reachKey(CustomUpstream(UpstreamTarget.Dot("192.0.2.10", 853), UpstreamTarget.Dot("192.0.2.11", 853), rev = 4), "quad9"))
        assertEquals("quad9", reachKey(null, "quad9"))
        assertEquals("cloudflare", reachKey(null, null))
    }

    @Test
    fun `concurrent failures and answers are applied by CAS, none lost`() {
        val now = AtomicLong(0)
        val tracker = ReachTracker(elapsed = { now.get() }, log = {})
        val start = CountDownLatch(1)
        val threads = List(8) {
            thread {
                start.await()
                repeat(500) { tracker.after(Resolved(null, answered = false), key, validated = true) }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(4_000, tracker.get().failures)
        now.set(30_000)
        tracker.after(Resolved(null, answered = false), key, validated = true)
        assertEquals(0L, tracker.get().downSinceMs)
    }

    @Test
    fun `the kinds - dropped is a failure whatever the cause, relayed bytes an answer`() {
        assertEquals(ReachKind.FAILED, reachKind(relayed(Attempt.Unprotected)))
        assertEquals(ReachKind.FAILED, reachKind(relayed(Attempt.Odd(OddKind.TIMEOUT, null))))
        assertEquals(ReachKind.FAILED, reachKind(relayed(Attempt.Transport(BackupReason.TLS))))
        assertEquals(ReachKind.ANSWERED, reachKind(relayed(Attempt.Answer(byteArrayOf(1)))))
        assertEquals(ReachKind.ANSWERED, reachKind(relayed(Attempt.Odd(OddKind.REFUSED, byteArrayOf(1)))))
        assertEquals(ReachKind.NEITHER, reachKind(Resolved(byteArrayOf(1), answered = false)))
    }

    @Test
    fun `the report - ongoing, ended, none, and provider mode`() {
        val wall = 1_792_312_405_000L
        val down = fail(ReachState(), 55_000, 75_000, 95_000)
        assertEquals(Pair(wall - 45_000, null), outageTimes(down, elapsedNow = 100_000, wallNow = wall))
        val ended = afterAnswer(down, key, 98_000)
        assertEquals(Pair(wall - 45_000, wall - 2_000), outageTimes(ended, 100_000, wall))
        assertEquals(Pair(null, null), outageTimes(ReachState(), 100_000, wall))
        assertEquals(
            DnsUpstreamReport(rev = 0, inUse = "provider", sinceMs = null, reason = null, unreachableSinceMs = wall - 45_000, unreachableUntilMs = wall - 2_000),
            providerReport(ended, 100_000, wall),
        )
    }
}
