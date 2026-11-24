package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.DnsUpstreamReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 36 §4.2: the status report's `dns_upstream` against the shared contract
 * `dns_upstream_status.json` (byte-identical with `server/testdata/`, whose
 * `custom_dns::dns_upstream_status_contract` posts it). The encoded JSON must be exactly the file
 * (`ServerJson` leaves defaults out), and a report without the field leaves it out.
 */
class DnsUpstreamStatusTest {
    private val contract = javaClass.classLoader!!.getResource("dns_upstream_status.json")!!.readText()

    @Test
    fun the_report_encodes_exactly_the_contract() {
        val rows = ServerJson.parseToJsonElement(contract).jsonObject
        val custom = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true,
            dnsUpstream = DnsUpstreamReport(
                rev = 4, inUse = "backup", sinceMs = 1_792_312_345_000, reason = "tls",
                unreachableSinceMs = 1_792_312_400_000, unreachableUntilMs = null,
            ),
        )
        assertEquals(rows["custom"].toString(), ServerJson.encodeToString(StatusReportRequest.serializer(), custom))
        // Provider mode names the provider that answered last.
        val provider = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true,
            dnsUpstream = providerReport(ReachState(key = "quad9"), 0, 0, answering = "mullvad"),
        )
        assertEquals(rows["provider"].toString(), ServerJson.encodeToString(StatusReportRequest.serializer(), provider))
        val none = ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true))
        assertFalse(ServerJson.parseToJsonElement(none).jsonObject.containsKey("dns_upstream"))
    }

    @Test
    fun the_contract_is_identical_to_the_servers_copy() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/dns_upstream_status.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }
}
