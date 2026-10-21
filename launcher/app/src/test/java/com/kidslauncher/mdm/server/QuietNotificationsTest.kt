package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PLAN "Fewer persistent notifications" (2026-10-21): the VPN drops its foreground state once the
 * filter is up and always-on ([vpnHoldsForeground], [VpnTunnelState]); a lost tunnel brings the
 * notification back and is retried inside the service (qa-lfix-notifs-code #1); the anchor keeps one
 * notification, "Vibb One" with no body. `startForeground` stays the first thing both services'
 * `onCreate` does (the API-34 rule, launcher/CLAUDE.md "Non-obvious gotchas").
 */
class QuietNotificationsTest {

    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    private fun source(name: String) = file("src/main/java/com/kidslauncher/mdm/server/$name").readText()

    /** The body of `fun <name>(...) {`, up to its closing brace at the same indent. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val indent = text.substring(text.lastIndexOf('\n', start) + 1, start)
        val open = text.indexOf("{\n", start) + 2
        return text.substring(open, text.indexOf("\n$indent}\n", open))
    }

    private fun statements(body: String) = body.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") }

    /** A tunnel token: compared by identity, like the service's descriptors. */
    private class Fd

    /** The state of a started service: onCreate's startForeground done. */
    private fun started() = VpnTunnelState<Fd>().also { it.enteredForeground() }

    @Test
    fun `the VPN holds its foreground state unless the filter is up and always-on`() {
        assertFalse(vpnHoldsForeground(tunnelUp = true, alwaysOnIsOurs = true))
        // The filter down (establish failed, the read loop died): the notification comes back.
        assertTrue(vpnHoldsForeground(tunnelUp = false, alwaysOnIsOurs = true))
        // Up but not the always-on VPN yet (first launch before the first sync).
        assertTrue(vpnHoldsForeground(tunnelUp = true, alwaysOnIsOurs = false))
        assertTrue(vpnHoldsForeground(tunnelUp = false, alwaysOnIsOurs = false))
    }

    @Test
    fun `an established always-on tunnel drops the foreground state once`() {
        val state = started()
        // Before establish: nothing to drop, already foreground.
        assertEquals(VpnSettle.NONE, state.settle { true })
        state.established(Fd(), nowMs = 0)
        // Not always-on yet: held.
        assertEquals(VpnSettle.NONE, state.settle { false })
        assertTrue(state.foreground)
        assertEquals(VpnSettle.DROP, state.settle { true })
        assertFalse(state.foreground)
        assertEquals(VpnSettle.NONE, state.settle { true })
        // Always-on cleared while up (no revoke yet): taken back, and held only once entered.
        assertEquals(VpnSettle.ENTER, state.settle { false })
        assertEquals(VpnSettle.ENTER, state.settle { false })
        state.enteredForeground()
        assertEquals(VpnSettle.NONE, state.settle { false })
    }

    @Test
    fun `an always-on check that throws never drops the notification`() {
        val state = started()
        state.established(Fd(), nowMs = 0)
        assertEquals(VpnSettle.NONE, state.settle { throw IllegalStateException("binder") })
        assertTrue(state.foreground)
    }

    @Test
    fun `a stopped service settles nothing and starts as foreground again`() {
        val state = started()
        state.established(Fd(), nowMs = 0)
        assertEquals(VpnSettle.DROP, state.settle { true })
        assertEquals(VpnStartKind.PLAIN, vpnStartKind(state))
        val tunnel = state.tunnel
        assertEquals(tunnel, state.stop())
        assertTrue(state.stopped)
        assertNull(state.tunnel)
        assertEquals(VpnSettle.NONE, state.settle { false })
        assertFalse(state.retryDue())
        // A start after the stop must not be a plain one into a dying record.
        assertEquals(VpnStartKind.FOREGROUND, vpnStartKind(state))
        assertEquals(VpnStartKind.FOREGROUND, vpnStartKind(null))
    }

    @Test
    fun `any running instance gets a plain start, held in the foreground or not`() {
        val state = started()
        // Foreground (tunnel down, or not always-on yet): plain too - no notification re-posted.
        assertEquals(VpnStartKind.PLAIN, vpnStartKind(state))
        state.established(Fd(), nowMs = 0)
        state.settle { true }
        assertEquals(VpnStartKind.PLAIN, vpnStartKind(state))
    }

    @Test
    fun `a lost tunnel takes the notification back and is retried with a back-off`() {
        val state = started()
        val first = Fd()
        state.established(first, nowMs = 0)
        state.settle { true }
        assertFalse(state.foreground)
        assertEquals(5_000L, state.readLoopEnded(first, nowMs = 60_000))
        assertNull(state.tunnel)
        assertTrue(state.retryDue())
        assertEquals(VpnSettle.ENTER, state.settle { true })
        state.enteredForeground()
        // Failed retries double up to the cap.
        assertEquals(10_000L, state.retryFailed())
        assertEquals(20_000L, state.retryFailed())
        repeat(5) { state.retryFailed() }
        assertEquals(300_000L, state.retryFailed())
        assertEquals(300_000L, state.retryFailed())
        // Back up: no retry due; it drops again.
        val second = Fd()
        state.established(second, nowMs = 100_000)
        assertFalse(state.retryDue())
        assertEquals(VpnSettle.DROP, state.settle { true })
        // A tunnel that held less than 10 minutes keeps the back-off.
        assertEquals(300_000L, state.readLoopEnded(second, nowMs = 100_000 + 599_999))
        // One that held 10 minutes resets it.
        val third = Fd()
        state.established(third, nowMs = 1_000_000)
        assertEquals(5_000L, state.readLoopEnded(third, nowMs = 1_000_000 + 600_000))
    }

    @Test
    fun `a stale or stopped read loop changes nothing`() {
        val state = started()
        val old = Fd()
        val current = Fd()
        state.established(old, nowMs = 0)
        state.established(current, nowMs = 1)
        assertNull(state.readLoopEnded(old, nowMs = 2))
        assertTrue(state.tunnel === current)
        state.stop()
        assertNull(state.readLoopEnded(current, nowMs = 3))
    }

    @Test
    fun `startForeground stays first in both services' onCreate`() {
        for (name in listOf("KidVpnService.kt", "CommandListenerService.kt")) {
            val lines = statements(body(source(name), "override fun onCreate()"))
            assertEquals(name, "super.onCreate()", lines[0])
            assertTrue("$name: ${lines[1]}", lines[1].startsWith("startForeground("))
        }
        val create = statements(body(source("KidVpnService.kt"), "override fun onCreate()"))
        assertEquals("state.enteredForeground()", create[2])
        assertTrue("running = state" in create)
    }

    @Test
    fun `the service wires the state - starts, settles, read loop, loss, retry, stop`() {
        val vpn = source("KidVpnService.kt")
        // Exactly one stopForeground, on the state's DROP.
        assertEquals(1, Regex("""stopForeground\(""").findAll(vpn).count())
        val settle = body(vpn, "private fun settleForeground()")
        assertTrue("when (state.settle { isAlwaysOn })" in settle)
        assertTrue(Regex("""VpnSettle\.DROP -> \{\s*stopForeground\(STOP_FOREGROUND_REMOVE\)""").containsMatchIn(settle))
        assertTrue("VpnSettle.ENTER -> enterForeground()" in settle)
        val enter = statements(body(vpn, "private fun enterForeground()"))
        assertEquals(listOf("try {", "startForeground(VPN_FILTER_NOTIFICATION_ID, buildNotification())", "state.enteredForeground()"), enter.take(3))
        // onStartCommand: the extra answered first, settled last.
        val onStart = statements(body(vpn, "override fun onStartCommand("))
        assertEquals("if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) enterForeground()", onStart[0])
        assertEquals(listOf("settleForeground()", "return START_STICKY"), onStart.takeLast(2))
        // establish() records the tunnel in the state; nothing else holds it.
        assertTrue("state.established(established, SystemClock.elapsedRealtime())" in body(vpn, "private fun startVpn()"))
        assertFalse(Regex("""\bpfd\b""").containsMatchIn(vpn))
        // The read loop: a thrown read and an end of stream both post tunnelLost(fd).
        val loop = body(vpn, "private fun readLoop(")
        assertEquals(2, Regex("""main\.post \{ tunnelLost\(fd\) \}""").findAll(loop).count())
        assertTrue(Regex("""if \(length < 0\) \{[^}]*main\.post \{ tunnelLost\(fd\) \}""").containsMatchIn(loop))
        assertTrue("if (length == 0) continue" in loop)
        // The loss goes through the state first, then settles and schedules the retry.
        val lost = statements(body(vpn, "private fun tunnelLost("))
        assertEquals("val retryMs = state.readLoopEnded(fd, SystemClock.elapsedRealtime()) ?: return", lost[0])
        assertTrue("settleForeground()" in lost)
        assertTrue("scheduleRetry(retryMs)" in lost)
        val retry = statements(body(vpn, "private fun retryTunnel()"))
        assertEquals(listOf("if (!state.retryDue()) return", "startVpn()", "settleForeground()"), retry.take(3))
        assertTrue("val nextMs = state.retryFailed()" in retry)
        assertTrue("scheduleRetry(nextMs)" in retry)
        // One chain at most: a new schedule replaces the pending retry.
        assertEquals(
            listOf("main.removeCallbacks(retry)", "main.postDelayed(retry, delayMs)"),
            statements(body(vpn, "private fun scheduleRetry(")),
        )
        // The stop: the state first; the revoke posts to main.
        assertEquals("val fd = state.stop()", statements(body(vpn, "private fun stopSelfCleanly()"))[0])
        val revoke = body(vpn, "override fun onRevoke()")
        assertTrue(Regex("""main\.post \{\s*stopSelfCleanly\(\)\s*stopSelf\(\)\s*\}""").containsMatchIn(revoke))
        assertTrue(statements(revoke).none { it.startsWith("super.onRevoke(") })
    }

    @Test
    fun `start sends a plain start whenever an instance runs, and the extra on the only foreground start`() {
        val vpn = source("KidVpnService.kt")
        val start = body(vpn, "fun start(context: Context)")
        assertTrue("if (vpnStartKind(running) == VpnStartKind.PLAIN) {" in start)
        assertTrue("context.startService(intent)" in start)
        assertEquals(1, Regex("""startForegroundService\(""").findAll(vpn).count())
        assertTrue("startForegroundService(context, intent.putExtra(EXTRA_FOREGROUND, true))" in start)
    }

    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
        val list = doc.getElementsByTagName("string")
        return (0 until list.length).map { list.item(it) as org.w3c.dom.Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun `the anchor's notification is Vibb One with no body, in en and nb`() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            val s = strings(path)
            assertEquals(path, "Vibb One", s["notification_listener_title"])
            assertFalse(path, "notification_listener_text" in s)
        }
        val build = body(source("CommandListenerService.kt"), "private fun buildNotification()")
        assertTrue(".setContentTitle(getString(R.string.notification_listener_title))" in build)
        assertFalse("setContentText" in build)
        assertTrue("NOTIFICATION_CHANNEL_LISTENER" in build)
        assertTrue("startForeground(COMMAND_LISTENER_NOTIFICATION_ID" in source("CommandListenerService.kt"))
    }

    @Test
    fun `the VPN's notification says it is connecting, with no body, in en and nb`() {
        val en = strings("src/main/res/values/strings.xml")
        val nb = strings("src/main/res/values-nb/strings.xml")
        assertEquals("Content filter connecting…", en["notification_vpn_filter_title"])
        assertEquals("Innholdsfilteret kobler til …", nb["notification_vpn_filter_title"])
        assertFalse("notification_vpn_filter_text" in en)
        assertFalse("notification_vpn_filter_text" in nb)
        // An expression body: from the signature to its .build().
        val build = source("KidVpnService.kt").substringAfter("private fun buildNotification() =").substringBefore(".build()")
        assertTrue(".setContentTitle(getString(R.string.notification_vpn_filter_title))" in build)
        assertFalse("setContentText" in build)
    }
}
