package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.UpstreamChooser.afterAnswer
import com.kidslauncher.mdm.server.UpstreamChooser.afterOdd
import com.kidslauncher.mdm.server.UpstreamChooser.afterTransport
import com.kidslauncher.mdm.server.UpstreamChooser.needsCanary
import com.kidslauncher.mdm.server.UpstreamChooser.releaseProbe
import com.kidslauncher.mdm.server.UpstreamChooser.reset
import com.kidslauncher.mdm.server.UpstreamChooser.route
import com.kidslauncher.mdm.server.UpstreamChooser.withCanary
import com.kidslauncher.mdm.server.dto.DnsUpstreamReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 36 §5.4: the pure backup rule, the outcome mapping that feeds it, the network reset and the
 * status field it reports. */
class UpstreamChooserTest {
    private val healthy = ChooserState(sinceMs = 0)
    private val ok = CanaryVerdict(atMs = 0, ok = true, reason = null)

    @Test
    fun `the back-off doubles from 60 s to the 15 min cap`() {
        var s = healthy
        val skips = (1..7).map { i ->
            val now = i * 10_000_000L
            s = afterTransport(s, s.gen, now, BackupReason.CONNECT)
            (s.skipUntilMs - now) / 1000
        }
        assertEquals(listOf(60L, 120, 240, 480, 900, 900, 900), skips)
        assertTrue(s.backup)
        assertEquals(BackupReason.CONNECT, s.reason)
    }

    @Test
    fun `a burst of failures from one generation counts once`() {
        val gen = healthy.gen
        var s = healthy
        repeat(10) { s = afterTransport(s, gen, 1_000, BackupReason.TIMEOUT) }
        assertEquals(1, s.failures)
        assertEquals(gen + 1, s.gen)
        assertEquals(61_000, s.skipUntilMs)
        assertEquals(1_000, s.sinceMs)
        // A late answer from before the period doesn't end it either.
        assertSame(s, afterAnswer(s, gen, 2_000))
    }

    @Test
    fun `one probe per back-off, the rest go to the backup until it returns`() {
        val period = afterTransport(healthy, 0, 0, BackupReason.TLS)
        assertEquals(UpstreamRoute.PRIMARY, route(healthy, 5).first)
        assertEquals(UpstreamRoute.BACKUP, route(period, 59_999).first)
        val (probe, probing) = route(period, 60_000)
        assertEquals(UpstreamRoute.PROBE, probe)
        assertTrue(probing.probing)
        assertEquals(UpstreamRoute.BACKUP, route(probing, 60_001).first)
        // The probe answers: healthy again, failures cleared.
        val back = afterAnswer(probing, probing.gen, 60_500)
        assertFalse(back.backup)
        assertEquals(0, back.failures)
        assertEquals(60_500, back.sinceMs)
        assertNull(back.reason)
        // The probe fails: the next back-off, the period's start kept.
        val again = afterTransport(probing, probing.gen, 60_500, BackupReason.CONNECT)
        assertEquals(2, again.failures)
        assertEquals(60_500 + 120_000, again.skipUntilMs)
        assertFalse(again.probing)
        assertEquals(0, again.sinceMs)
        // A probe that couldn't try (its address not looked up yet) gives the slot back.
        assertFalse(releaseProbe(probing, probing.gen).probing)
        assertSame(again, releaseProbe(again, probing.gen))
    }

    @Test
    fun `an odd answer from a healthy server stands, never the backup`() {
        val s = afterOdd(healthy, healthy.gen, 1_000, ok)
        assertFalse(s.backup)
        assertEquals(OddAnswer.BLOCKED, oddAnswer(OddKind.REFUSED))
        assertEquals(OddAnswer.SERVFAIL, oddAnswer(OddKind.SERVFAIL))
        assertEquals(OddAnswer.SERVFAIL, oddAnswer(OddKind.TIMEOUT))
        // A healthy canary on the probe ends the period.
        val probing = route(afterTransport(healthy, 0, 0, BackupReason.CONNECT), 60_000).second
        assertFalse(afterOdd(probing, probing.gen, 60_100, ok).backup)
    }

    @Test
    fun `a failed canary starts a period with its reason`() {
        val refused = afterOdd(healthy, healthy.gen, 1_000, CanaryVerdict(1_000, false, BackupReason.REFUSED))
        assertTrue(refused.backup)
        assertEquals(BackupReason.REFUSED, refused.reason)
        assertEquals(61_000, refused.skipUntilMs)
        val silent = afterOdd(healthy, healthy.gen, 1_000, CanaryVerdict(1_000, false, BackupReason.CONNECT))
        assertEquals(BackupReason.CONNECT, silent.reason)
    }

    @Test
    fun `the canary's answer becomes its verdict`() {
        fun answer(rcode: Int) = Attempt.Answer(org.xbill.DNS.Message().apply { header.rcode = rcode }.toWire())
        assertEquals(CanaryVerdict(5, true, null), canaryVerdict(answer(org.xbill.DNS.Rcode.NOERROR), 5))
        assertEquals(BackupReason.TIMEOUT, canaryVerdict(answer(org.xbill.DNS.Rcode.NXDOMAIN), 5).reason)
        assertEquals(BackupReason.REFUSED, canaryVerdict(Attempt.Odd(OddKind.REFUSED, null), 5).reason)
        assertEquals(BackupReason.TIMEOUT, canaryVerdict(Attempt.Odd(OddKind.SERVFAIL, null), 5).reason)
        assertEquals(BackupReason.TIMEOUT, canaryVerdict(Attempt.Odd(OddKind.TIMEOUT, null), 5).reason)
        assertEquals(BackupReason.TLS, canaryVerdict(Attempt.Transport(BackupReason.TLS), 5).reason)
    }

    @Test
    fun `a canary verdict is reused for 60 s`() {
        assertTrue(needsCanary(healthy, 0))
        val s = withCanary(healthy, CanaryVerdict(10_000, true, null))
        assertFalse(needsCanary(s, 69_999))
        assertTrue(needsCanary(s, 70_000))
    }

    @Test
    fun `a reset tries the primary again at once`() {
        val period = withCanary(afterTransport(healthy, 0, 0, BackupReason.CONNECT), CanaryVerdict(0, false, BackupReason.CONNECT))
        val s = reset(period, 5_000)
        assertEquals(ChooserState(gen = period.gen + 1, sinceMs = 5_000), s)
        assertEquals(UpstreamRoute.PRIMARY, route(s, 5_001).first)
        // A healthy state keeps when it became healthy.
        assertEquals(0, reset(healthy, 5_000).sinceMs)
    }

    @Test
    fun `timeouts split at the written request, TLS and connect failures are transport`() {
        assertEquals(BackupReason.TLS, transportReason(java.io.IOException(SSLHandshakeException("x")), connected = true))
        assertEquals(BackupReason.CONNECT, transportReason(SocketTimeoutException(), connected = false))
        assertEquals(BackupReason.TIMEOUT, transportReason(SocketTimeoutException(), connected = true))
        assertEquals(OddKind.TIMEOUT, (afterWriteOutcome(SocketTimeoutException()) as Attempt.Odd).kind)
        assertEquals(Attempt.Transport(BackupReason.CONNECT), afterWriteOutcome(java.io.EOFException()))
    }

    @Test
    fun `a network reset is a new best network or one that gained VALIDATED`() {
        val wifi = NetworkSnapshot(handle = 100, validated = false)
        assertTrue(networkChanged(null, wifi))
        assertTrue(networkChanged(wifi, wifi.copy(validated = true)))
        assertTrue(networkChanged(wifi, NetworkSnapshot(handle = 200, validated = true)))
        assertFalse(networkChanged(wifi, wifi))
        assertFalse(networkChanged(wifi.copy(validated = true), wifi))
        assertFalse(networkChanged(wifi, null))
    }

    @Test
    fun `the status field is the contract's, and dns_custom_v1 is announced`() {
        val since = 1_000_000L
        val s = afterTransport(healthy, 0, since, BackupReason.TLS)
        // Design 67: an ongoing outage since 55 s after the backup period began.
        val down = ReachState(key = "custom:4", failures = 3, firstFailMs = since + 55_000, lastFailMs = since + 58_000, downSinceMs = since + 55_000)
        val report = upstreamReport(s, rev = 4, reach = down, elapsedNow = since + 60_000, wallNow = 1_792_312_405_000)
        assertEquals(DnsUpstreamReport(4, "backup", 1_792_312_345_000, "tls", 1_792_312_400_000, null), report)
        val contract = javaClass.classLoader!!.getResource("dns_upstream_status.json")!!.readText()
        val encoded = ServerJson.encodeToString(
            StatusReportRequest.serializer(),
            StatusReportRequest(
                lockReason = "NONE", kioskEngaged = true, capabilities = listOf(DNS_CUSTOM_CAPABILITY, DNS_BYPASS_CAPABILITY), dnsUpstream = report,
            ),
        )
        assertEquals(contract.trimEnd(), encoded)
        assertTrue(DNS_CUSTOM_CAPABILITY in STATUS_CAPABILITIES)
        // Healthy: "custom", never a reason; no outage, no times.
        val back = upstreamReport(afterAnswer(s, s.gen, since + 9_000), 4, ReachState(), since + 10_000, 2_000_000)
        assertEquals(DnsUpstreamReport(4, "custom", 1_999_000, null, null, null), back)
    }
}
