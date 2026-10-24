package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.ZoneWatchReport
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 64 step 2: the status report's `extra_locations` and `zone_watch` against the shared
 * contract `zone_status_report.json` (byte-identical with `server/testdata/`, whose
 * `zone_watch::a_launcher_report_raises_the_arrival` posts it). The encoded JSON must be exactly
 * the file (`ServerJson` leaves defaults out: a fix's `mock` only when true, its `provider` only
 * when known), and a report without them leaves both keys out. The phone judges no zone, so it
 * keeps no copy of `zone_vectors.json` (decision 1: the server decides every crossing).
 */
class ZoneReportTest {
    private val contract = javaClass.classLoader!!.getResource("zone_status_report.json")!!.readText()

    @Test
    fun `the report encodes exactly the contract`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            location = LocationReport(59.91417, 10.7522, 15.0f, "2026-10-24T08:14:05Z", provider = "gps"),
            capabilities = listOf(ZONES_CAPABILITY),
            extraLocations = listOf(
                LocationReport(59.9229, 10.7522, 30.0f, "2026-10-24T08:02:00Z", provider = "fused"),
                LocationReport(59.91417, 10.7522, 25.0f, "2026-10-24T08:12:03.250Z", provider = "fused"),
            ),
            zoneWatch = ZoneWatchReport(fences = 1, motionSensor = true, locationOn = true, backgroundLocation = true, mockIgnored = 2),
        )
        assertEquals(contract.trimEnd(), ServerJson.encodeToString(StatusReportRequest.serializer(), report))
        val none = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertFalse(none.containsKey("extra_locations"))
        assertFalse(none.containsKey("zone_watch"))
    }

    @Test
    fun `a mock fix says so and an unknown provider is left out`() {
        val fix = LocationReport(1.0, 2.0, null, "2026-10-24T08:00:00Z", mock = true)
        assertEquals(
            """{"latitude":1.0,"longitude":2.0,"captured_at":"2026-10-24T08:00:00Z","mock":true}""",
            ServerJson.encodeToString(LocationReport.serializer(), fix),
        )
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/zone_status_report.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }
}
