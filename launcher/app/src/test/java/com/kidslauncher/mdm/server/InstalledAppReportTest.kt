package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 7 §2.1.2: the status report's `installed_apps[].battery_exempt` against the shared contract
 * `installed_apps_battery.json` (byte-identical with `server/testdata/`, which the server's
 * `intake_limits::the_battery_state_of_a_catalog_app_is_kept` posts). `ServerJson` leaves the
 * unknowns out, so an app outside the protected set has no field at all.
 */
class InstalledAppReportTest {
    private val contract = javaClass.classLoader!!.getResource("installed_apps_battery.json")!!.readText()

    @Test
    fun `the report encodes exactly the contract`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            installedApps = listOf(
                InstalledApp("org.fossify.clock", "Clock", preinstalled = false, installer = "me.vibb.launcher", batteryExempt = false),
                InstalledApp("io.element.android.x", "Element X", preinstalled = false, installer = "me.vibb.launcher", batteryExempt = true),
                InstalledApp("org.example.game", "Game", preinstalled = false, installer = "com.android.vending"),
                InstalledApp("com.android.chrome", "Chrome", preinstalled = true),
            ),
        )
        assertEquals(contract.trimEnd(), ServerJson.encodeToString(StatusReportRequest.serializer(), report))
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/installed_apps_battery.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }
}
