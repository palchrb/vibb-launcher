package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.util.zip.GZIPInputStream

/** Design 95 §3.5 (step 95-T2): the heartbeat report against the shared contract `status_heartbeat.json`. */
class StatusReportsTest {
    private val text = javaClass.classLoader!!.getResource("status_heartbeat.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(text).jsonObject

    /** The report the contract's `full` and `heartbeat` encode. */
    private val tls = ServerTlsReport(
        enforcing = false,
        seen = ServerTlsSeen(true, "ISRG Root X1", "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "wifi", 1794560000000),
        outside = emptyList(),
    )
    private val report = StatusReportRequest(
        lockReason = "NONE",
        kioskEngaged = true,
        installedApps = listOf(InstalledApp("org.example.app", "Example", false, "me.vibb.launcher", 7)),
        appVersion = "0.40.0",
        appVersionCode = 400009,
        capabilities = listOf(STATUS_HEARTBEAT_CAPABILITY),
        battery = BatteryReport(80, charging = false, low = false),
        serverTls = tls,
    )
    private val moved = report.copy(
        lockReason = "TIME_RULE",
        battery = BatteryReport(79, charging = true, low = false),
        serverTls = tls.copy(seen = tls.seen!!.copy(atMs = 1794561800000)),
    )

    @Test
    fun `the contract is the server's, and the full report and the heartbeat encode exactly`() {
        val serverCopy = File("../../server/testdata/status_heartbeat.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals(serverCopy.readText(), text)
        assertEquals(STATUS_HEARTBEAT_CAPABILITY, contract["capability"]!!.jsonPrimitive.content)
        assertTrue(STATUS_HEARTBEAT_CAPABILITY in STATUS_CAPABILITIES)
        assertEquals(HEARTBEAT_FIELDS, contract["heartbeat_fields"]!!.jsonArray.map { it.jsonPrimitive.content })

        val json = reportJson(report)
        val hash = reportHash(json)
        assertEquals(contract["full"], fullBody(json, hash))
        // The heartbeat fields moved, the hash didn't: the contract's heartbeat names the full report.
        val movedJson = reportJson(moved)
        assertEquals(hash, reportHash(movedJson))
        assertEquals(contract["heartbeat"], heartbeatBody(movedJson, hash))
        // Any other field moving changes it.
        assertNotEquals(hash, reportHash(reportJson(report.copy(kioskEngaged = false))))
        assertNotEquals(hash, reportHash(reportJson(report.copy(installedApps = emptyList()))))

        // The policy's keys; an older server's policy says neither.
        val policy = ServerJson.decodeFromString(PolicyResponse.serializer(), contract["policy"].toString())
        assertTrue(policy.statusHeartbeat && policy.statusGzip)
        val older = ServerJson.decodeFromString(PolicyResponse.serializer(), "{}")
        assertFalse(older.statusHeartbeat || older.statusGzip)

        val answer = contract["answers"]!!.jsonObject["full_report"].toString().toByteArray()
        assertTrue(isFullReportAnswer(200, answer))
        assertFalse(isFullReportAnswer(200, """{"wanted_logos": []}""".toByteArray()))
        assertFalse(isFullReportAnswer(204, null))
    }

    private val owner = "owner-a"
    private val acks = HeartbeatAckStore(ParentPinStoreTest.FakePrefs())
    private var now = 1_000_000L
    private val posted = mutableListOf<Pair<JsonObject, String?>>()

    private fun answer(code: Int, text: String = ""): Response<ResponseBody> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://vibb.example/api/devices/status").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("x").build()
        val body = text.toResponseBody("application/json".toMediaType())
        return if (code in 200..299) Response.success(body, raw) else Response.error(body, raw)
    }

    private fun send(report: StatusReportRequest, heartbeat: Boolean = true, gzip: Boolean = false, answers: List<Response<ResponseBody>>): SentReport =
        runBlocking {
            var calls = 0
            sendReport(report, heartbeat, gzip, owner, acks, { now }, { it?.use { b -> b.bytes() } }) { body: RequestBody, encoding: String? ->
                val buffer = Buffer().also { body.writeTo(it) }
                val bytes = if (encoding == "gzip") GZIPInputStream(buffer.inputStream()).readBytes() else buffer.readByteArray()
                posted += ServerJson.parseToJsonElement(bytes.decodeToString()).jsonObject to encoding
                answers[calls++]
            }
        }

    @Test
    fun `a heartbeat goes only after a full report the server took, and a full report when asked`() {
        // First: the full report with its hash; taken, so acked.
        send(report, answers = listOf(answer(204)))
        assertTrue(posted.last().first.containsKey("hash"))
        assertFalse(posted.last().first.containsKey("heartbeat"))
        // Only the heartbeat fields moved: a heartbeat.
        now += 30 * 60 * 1000L
        send(moved, answers = listOf(answer(204)))
        assertEquals(JsonPrimitive(true), posted.last().first["heartbeat"])
        assertNull(posted.last().first["installed_apps"])
        // The server can't expand it: the full report at once, in the same sync.
        val sent = send(moved, answers = listOf(answer(200, """{"full_report": true}"""), answer(204)))
        assertEquals(204, sent.code)
        assertTrue(posted.last().first.containsKey("installed_apps"))
        // Another field moved: a full report.
        send(report.copy(kioskEngaged = false), answers = listOf(answer(204)))
        assertFalse(posted.last().first.containsKey("heartbeat"))
        // Six hours after the last full report: a full report whatever the hash.
        now += FULL_REPORT_EVERY_MS
        send(report.copy(kioskEngaged = false), answers = listOf(answer(204)))
        assertFalse(posted.last().first.containsKey("heartbeat"))
        // An older server (the policy says nothing): no hash, never a heartbeat, the ack gone.
        send(report.copy(kioskEngaged = false), heartbeat = false, answers = listOf(answer(204)))
        assertFalse(posted.last().first.containsKey("hash"))
        assertNull(acks.get())
    }

    @Test
    fun `a report is gzip'd only when the policy says so and it is over 1 KiB`() {
        val big = report.copy(installedApps = (1..40).map { InstalledApp("org.example.app$it", "Example $it", false) })
        send(big, gzip = true, answers = listOf(answer(204)))
        assertEquals("gzip", posted.last().second)
        send(big.copy(battery = BatteryReport(1, charging = false, low = true)), gzip = true, answers = listOf(answer(204)))
        assertEquals(JsonPrimitive(true), posted.last().first["heartbeat"])
        assertNull(posted.last().second)
        send(big.copy(kioskEngaged = false), gzip = false, answers = listOf(answer(204)))
        assertNull(posted.last().second)
    }
}
