package com.kidslauncher.mdm

import com.kidslauncher.mdm.server.Attempt
import com.kidslauncher.mdm.server.BtKind
import com.kidslauncher.mdm.server.Budget
import com.kidslauncher.mdm.server.CallMarks
import com.kidslauncher.mdm.server.DotLink
import com.kidslauncher.mdm.server.DotPool
import com.kidslauncher.mdm.server.LEAVE_TAILNET_COMMAND
import com.kidslauncher.mdm.server.OddKind
import com.kidslauncher.mdm.server.TSNET_DEATH_WINDOW_MS
import com.kidslauncher.mdm.server.btAudioClass
import com.kidslauncher.mdm.server.btPairOffered
import com.kidslauncher.mdm.server.cachedFixMaxAgeMs
import com.kidslauncher.mdm.server.cachedFixReportable
import com.kidslauncher.mdm.server.crashStreakAfter
import com.kidslauncher.mdm.server.dto.LocationPolicy
import com.kidslauncher.mdm.server.locationBackOff
import com.kidslauncher.mdm.server.locationStaysOn
import com.kidslauncher.mdm.server.deathAfterConnectIsCrash
import com.kidslauncher.mdm.server.dotAnswers
import com.kidslauncher.mdm.server.dotAttempt
import com.kidslauncher.mdm.server.leaveTailnetAnswer
import com.kidslauncher.mdm.server.leaveTailnetRefusal
import com.kidslauncher.mdm.server.tailnetOnlyServer
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import android.app.ApplicationExitInfo
import android.bluetooth.BluetoothClass
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The launcher's security Lows, the rest (security review 2026-10-21, round L4b): one property pinned per fix. */
class SecurityLowsBTest {
    private val server = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 53))
    private val key = DotPool.Key(server, 853, "dns.example")
    private val query = Message.newQuery(Record.newRecord(Name.fromString("a.example."), Type.A, DClass.IN)).toWire()
    private val answer = Message(query).apply { header.setFlag(Flags.QR.toInt()) }.toWire()

    /** A DoT connection that answers by [reply] (or throws), and remembers being cut. */
    private class FakeLink(private val reply: (ByteArray) -> ByteArray) : DotLink {
        var cut = false
        var asked = 0
        override fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray {
            asked++
            marks.written = true
            return reply(query)
        }
        override fun cut() { cut = true }
    }

    private fun attempt(pool: DotPool?, opened: MutableList<FakeLink>, budget: Budget = Budget(100, 500), next: () -> FakeLink) =
        dotAttempt(query, listOf(server), budget, pool, { key }, log = {}) { _, _, marks ->
            marks.connected = true
            next().also { opened += it }
        }

    @Test
    fun `S-L9 - a DoT connection that answered is kept and asked again, one the server closed is replaced`() {
        val pool = DotPool()
        val opened = mutableListOf<FakeLink>()
        assertTrue(attempt(pool, opened) { FakeLink { answer } } is Attempt.Answer)
        assertTrue(attempt(pool, opened) { FakeLink { answer } } is Attempt.Answer)
        assertEquals("the second query rides the first connection", 1, opened.size)
        assertEquals(2, opened[0].asked)
        assertEquals(1, pool.size())

        // The server closed it while idle: the query is asked again on a fresh connection, which is kept instead.
        val gone = FakeLink { throw EOFException() }
        pool.evictAll()
        pool.give(key, gone)
        assertTrue(attempt(pool, opened) { FakeLink { answer } } is Attempt.Answer)
        assertTrue(gone.cut)
        assertEquals(2, opened.size)
        assertEquals(1, pool.size())

        // An answer to another query (out of step) or garbage is never kept.
        val other = answer.copyOf().also { it[1] = (it[1] + 1).toByte() }
        assertFalse(dotAnswers(query, other))
        assertFalse(dotAnswers(query, answer.copyOf(11)))
        assertTrue(dotAnswers(query, answer))
        pool.evictAll()
        val garbage = FakeLink { ByteArray(12).also { it[5] = 1 } } // one question promised, none there
        assertTrue(attempt(pool, opened) { garbage } is Attempt.Transport)
        assertTrue(garbage.cut)
        assertEquals(0, pool.size())

        // Without a pool (tests, a stopped VPN) every connection is closed after its answer.
        val once = FakeLink { answer }
        assertTrue(attempt(null, opened) { once } is Attempt.Answer)
        assertTrue(once.cut)
    }

    @Test
    fun `S-L9 - a kept connection silent to the deadline is silence, not a retry`() {
        val pool = DotPool()
        val released = CountDownLatch(1)
        val silent = object : DotLink {
            override fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray {
                marks.written = true
                released.await(5, TimeUnit.SECONDS)
                throw IOException("closed")
            }
            override fun cut() = released.countDown()
        }
        pool.give(key, silent)
        val opened = mutableListOf<FakeLink>()
        val outcome = attempt(pool, opened, Budget(50, 80)) { FakeLink { answer } }
        assertTrue("$outcome", outcome is Attempt.Odd && outcome.kind == OddKind.TIMEOUT)
        assertEquals("no fresh connection past the deadline", 0, opened.size)
        assertEquals(0, pool.size())

        // The QA fix round's L1: the read's own timeout (before the watchdog's close) is silence too, not a connection
        // gone - no retry, no transport failure counted.
        val timedOut = object : DotLink {
            override fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray {
                marks.written = true
                throw java.net.SocketTimeoutException("read timed out")
            }
            override fun cut() {}
        }
        pool.give(key, timedOut)
        val read = attempt(pool, opened) { FakeLink { answer } }
        assertTrue("$read", read is Attempt.Odd && read.kind == OddKind.TIMEOUT)
        assertEquals(0, opened.size)
    }

    @Test
    fun `S-L9 - the pool keeps a few connections a short while and nothing once closed`() {
        var now = 0L
        val pool = DotPool(idleMs = 10_000L, maxIdle = 2, nowMs = { now })
        val links = List(3) { FakeLink { answer } }
        links.forEach { pool.give(key, it) }
        assertTrue("the oldest past the cap is closed", links[0].cut)
        assertSame("the newest goes first", links[2], pool.take(key))
        assertNull(pool.take(DotPool.Key(server, 853, "other.example")))
        now = 10_000L
        assertNull("idle too long", pool.take(key))
        assertTrue(links[1].cut)
        // The QA fix round's L4: a give sweeps every server's expired connections, not only its own.
        val other = DotPool.Key(server, 853, "other.example")
        val stale = FakeLink { answer }
        pool.give(other, stale)
        now = 30_000L
        pool.give(key, FakeLink { answer })
        assertTrue(stale.cut)
        assertEquals(1, pool.size())
        val last = FakeLink { answer }
        pool.close()
        pool.give(key, last)
        assertTrue(last.cut)
        assertEquals(0, pool.size())
    }

    private fun src(path: String) = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    @Test
    fun `S-L10 - a death soon after a tsnet connect counts toward the crash guard, Android's kills don't`() {
        val at = 1_793_000_000_000L
        assertTrue(deathAfterConnectIsCrash(at, at + 60_000, ApplicationExitInfo.REASON_CRASH_NATIVE, 6))
        assertTrue("Go's exit(2)", deathAfterConnectIsCrash(at, at + 60_000, ApplicationExitInfo.REASON_EXIT_SELF, 2))
        assertFalse("our own crash handler's exitProcess(1) (QA M1)", deathAfterConnectIsCrash(at, at + 60_000, ApplicationExitInfo.REASON_EXIT_SELF, 1))
        assertFalse(deathAfterConnectIsCrash(at, at + 60_000, ApplicationExitInfo.REASON_EXIT_SELF, null))
        assertTrue("an abort", deathAfterConnectIsCrash(at, at + TSNET_DEATH_WINDOW_MS, ApplicationExitInfo.REASON_SIGNALED, 6))
        assertFalse("past the window", deathAfterConnectIsCrash(at, at + TSNET_DEATH_WINDOW_MS + 1, ApplicationExitInfo.REASON_CRASH_NATIVE, 6))
        assertFalse("a death before this connect", deathAfterConnectIsCrash(at, at - 1, ApplicationExitInfo.REASON_CRASH_NATIVE, 6))
        for (kill in listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_OTHER,
        )) {
            assertFalse("$kill", deathAfterConnectIsCrash(at, at + 1_000, kill, 2))
        }
        assertFalse("no record", deathAfterConnectIsCrash(at, null, null, null))
        assertFalse("no connect", deathAfterConnectIsCrash(0, at, ApplicationExitInfo.REASON_CRASH_NATIVE, 6))

        // The streak: a connect that never returned or died soon after adds one; one that lived on ends it.
        assertEquals(2, crashStreakAfter(1, wasLeftPending = true, lastConnect = null))
        assertEquals(2, crashStreakAfter(1, wasLeftPending = false, lastConnect = true))
        assertEquals(0, crashStreakAfter(1, wasLeftPending = false, lastConnect = false))
        assertEquals(1, crashStreakAfter(1, wasLeftPending = false, lastConnect = null))
        // A connect that returned no longer ends the streak by itself (the old finally reset it to 0).
        val client = src("java/com/kidslauncher/mdm/server/TsnetClient.kt").readText()
        val finally = client.substringAfter("connect(hostname, authKey, stateDir)").substringAfter("} finally {").substringBefore("\n        }")
        assertFalse(finally.contains("CRASH_COUNT_KEY"))
        assertTrue(finally.contains("CONNECT_RETURNED_AT_KEY"))
    }

    @Test
    fun `S-L10 - leave_tailnet's answers are the shared contract's`() {
        val contract = javaClass.classLoader!!.getResource("leave_tailnet.json")!!.readText()
        val parsed = Json.parseToJsonElement(contract).jsonObject
        assertEquals(LEAVE_TAILNET_COMMAND, parsed.getValue("command").jsonPrimitive.content)
        val rows = parsed.getValue("answers").jsonArray.map {
            it.jsonObject.getValue("success").jsonPrimitive.boolean to it.jsonObject.getValue("message").jsonPrimitive.content
        }
        assertEquals(
            rows,
            listOf(leaveTailnetAnswer(true, true), leaveTailnetAnswer(false, true), leaveTailnetAnswer(true, false), leaveTailnetRefusal()),
        )
        assertEquals(leaveTailnetAnswer(true, false), leaveTailnetAnswer(false, false))
        // The QA fix round's M2: a server at a Tailscale address - the phone stays.
        for (url in listOf("http://100.101.102.103:8080", "http://100.64.0.1", "http://100.127.255.254/", "http://[fd7a:115c:a1e0::5]:3000")) {
            assertTrue(url, tailnetOnlyServer(url))
        }
        for (url in listOf("http://100.63.255.255", "http://100.128.0.1", "https://vibb.tail0000.ts.net", "http://192.168.1.2:8080", "http://[fd7a:115c:a1e1::5]", "not a url", null)) {
            assertFalse("$url", tailnetOnlyServer(url))
        }
        val tsnet = src("java/com/kidslauncher/mdm/server/TsnetClient.kt").readText()
        val leave = tsnet.substringAfter("fun leave(context: Context)")
        assertTrue(leave.indexOf("if (tailnetOnlyServer(") in 0 until leave.indexOf("close()"))
        // The report's `tailnet_joined` (the server shows the row only while it is true).
        val report = parsed.getValue("report").jsonObject
        val encoded = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true, tailnetJoined = true)),
        ).jsonObject
        for ((key, value) in report) assertEquals(key, value, encoded[key])
        // The sync runs it, keeps the answer for after the report and builds its client again (nit 1).
        val sync = src("java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        assertTrue(sync.contains("LEAVE_TAILNET_COMMAND -> {\n            val (ok, message) = TsnetClient.leave(context)\n            CommandAnswers.keep(context, pending, ok, message)"))
        assertTrue(sync.contains("if (ranCommand?.command == LEAVE_TAILNET_COMMAND) {\n            apiPath = serverPathNow(serverUrl)\n            api = createMdmApi(serverUrl, deviceToken, path = apiPath)"))
        assertTrue(sync.contains("tailnetJoined = TsnetClient.joined(context),"))
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/leave_tailnet.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    @Test
    fun `P-L2 - Location goes back off after a fix unless the policy needs it, and a ring takes no fix`() {
        val onRequest = LocationPolicy(mode = "on_request")
        assertFalse(locationStaysOn(onRequest, hardeningForcesOn = false, zonesOn = false))
        assertFalse(locationStaysOn(LocationPolicy(mode = "interval"), hardeningForcesOn = false, zonesOn = false))
        assertTrue("Find my device's CONFIG_LOCATION switch", locationStaysOn(onRequest, hardeningForcesOn = true, zonesOn = false))
        assertTrue("the zone watch's fences", locationStaysOn(LocationPolicy(mode = "interval"), hardeningForcesOn = false, zonesOn = true))
        assertFalse("Off is off", locationStaysOn(LocationPolicy(mode = "off"), hardeningForcesOn = true, zonesOn = false))
        assertTrue(locationBackOff(switchedOn = true, staysOn = false))
        assertFalse("it was on before: left on", locationBackOff(switchedOn = false, staysOn = false))
        assertFalse(locationBackOff(switchedOn = true, staysOn = true))
        val sync = src("java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        assertTrue(sync.contains("val commandFix = ranCommand?.command == \"locate\""))
        val locate = src("java/com/kidslauncher/mdm/server/LocateCommands.kt").readText()
        val restore = locate.substringAfter("if (switchedOn) QuickControls.setLocationEnabled(dpm, admin, true)").substringBefore("\n    }\n")
        assertTrue("restored in a finally", restore.contains("} finally {") && restore.contains("locationBackOff(switchedOn, staysOn)"))
        // The fix round's L2: the mark is committed before the switch, and the next sync acts on one a dead process left.
        assertTrue(locate.contains("markSwitchedOn(context, true)\n            QuickControls.setLocationEnabled(dpm, admin, true)"))
        val report = sync.substringAfter("private suspend fun currentLocationReport(")
        assertTrue(report.indexOf("LocateCommands.restoreAfterDeadFix(context, dpm, admin, staysOn)") in 0 until report.indexOf("LocateCommands.currentLocation("))
    }

    @Test
    fun `P-L3 - no cached fix older than the interval is reported, none kept under Off`() {
        val half = LocationPolicy(mode = "interval", intervalMinutes = 30)
        assertEquals(30 * 60_000L, cachedFixMaxAgeMs(half, still = false))
        assertEquals("at least 10 min", 10 * 60_000L, cachedFixMaxAgeMs(LocationPolicy(mode = "interval", intervalMinutes = 1), still = false))
        assertEquals("Only when asked: its interval", 30 * 60_000L, cachedFixMaxAgeMs(LocationPolicy(mode = "on_request"), still = false))
        assertNull("the zone watch's still re-send", cachedFixMaxAgeMs(half, still = true))
        assertTrue(cachedFixReportable(30 * 60_000L, 30 * 60_000L))
        assertFalse("a day old", cachedFixReportable(24 * 3_600_000L, 30 * 60_000L))
        assertTrue(cachedFixReportable(24 * 3_600_000L, null))
        val sync = src("java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        assertTrue(sync.contains("if (locationPolicy?.mode == \"off\") LocateCommands.clearCachedFix(context)"))
        // Every way a stored fix leaves currentLocation goes through the age check.
        val locate = src("java/com/kidslauncher/mdm/server/LocateCommands.kt").readText()
        val body = locate.substringAfter("suspend fun currentLocation(").substringBefore("fun clearCachedFix(")
        assertEquals(2, Regex("""loadCachedFix\(\)\?\.takeIf\((::)?reportable\)""").findAll(body).count())
        assertFalse(Regex("""loadCachedFix\(\)\s*(\n|$|\})""").containsMatchIn(body))
    }

    @Test
    fun `S-L5 - pairing is offered for headphones and speakers only, unless the parent allows any or the override is on`() {
        assertTrue("an A2DP sink", btAudioClass(null, BtKind.A2DP))
        assertTrue("LE Audio", btAudioClass(BluetoothClass.Device.Major.UNCATEGORIZED, BtKind.LE_ONLY))
        assertTrue("found by the scan, no UUIDs yet", btAudioClass(BluetoothClass.Device.Major.AUDIO_VIDEO, BtKind.OTHER))
        for (major in listOf(BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.COMPUTER, BluetoothClass.Device.Major.WEARABLE)) {
            assertFalse("$major", btAudioClass(major, BtKind.OTHER))
        }
        assertFalse(btAudioClass(null, BtKind.OTHER))
        // The QA fix round's L3: a laptop's PipeWire A2DP sink doesn't make it audio.
        assertFalse(btAudioClass(BluetoothClass.Device.Major.COMPUTER, BtKind.A2DP))
        assertFalse(btAudioClass(BluetoothClass.Device.Major.PHONE, BtKind.LE_ONLY))
        assertTrue(btPairOffered(audioClass = true, parentAllowsAny = false, overrideActive = false))
        assertFalse("a friend's phone", btPairOffered(audioClass = false, parentAllowsAny = false, overrideActive = false))
        assertTrue(btPairOffered(audioClass = false, parentAllowsAny = true, overrideActive = false))
        assertTrue("the pause lifts it", btPairOffered(audioClass = false, parentAllowsAny = false, overrideActive = true))
        // The page lists only what it may pair and checks again at the tap; a policy change drops the rest.
        val page = src("java/com/kidslauncher/mdm/ui/quickcontrols/BluetoothDevicesActivity.kt").readText()
        assertTrue(page.contains("if (!info.bonded && !pairOffered(info)) return@launch"))
        val pair = page.substringAfter("private fun onPairTapped(").substringBefore("QuickControls.pairBluetoothDevice(")
        assertTrue(pair.contains("if (!pairOffered(device)) {"))
        assertTrue(page.contains("{ if (!finishIfNotAllowed()) dropUnoffered() }"))
    }
}
