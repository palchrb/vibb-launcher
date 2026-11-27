package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.ZoneWatchReport
import com.kidslauncher.mdm.server.dto.ZoneWifiReport
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 64 step 2: the status report's `extra_locations` and `zone_watch` against the shared
 * contract `zone_status_report.json` (in `contracts/core/`, whose
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
            // Design 131: a Wi-Fi fix a linked network confirmed, and one of the Wi-Fi alone (no `check`).
            location = LocationReport(59.91417, 10.7522, 40.0f, "2026-10-24T08:14:05Z", provider = "vibb_wifi", check = "link"),
            extraLocations = listOf(
                LocationReport(59.9229, 10.7522, 30.0f, "2026-10-24T08:02:00Z", provider = "vibb_apple"),
                LocationReport(59.91417, 10.7522, 25.0f, "2026-10-24T08:12:03.250Z", provider = "fused"),
            ),
            zoneWatch = ZoneWatchReport(fences = 1, motionSensor = true, locationOn = true, backgroundLocation = true, mockIgnored = 2, wifiDisagreed = 3, still = true, stillSinceAgeMs = 2_045_000L,
                // Design 138 D3: the connected network for the zone page's Confirm, and a struck confirmation.
                wifi = ZoneWifiReport("Net-A", "00:00:5e:00:53:01", "00:00:5e:00:53:10"), wifiStruck = listOf(7L),
            ),
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
}
