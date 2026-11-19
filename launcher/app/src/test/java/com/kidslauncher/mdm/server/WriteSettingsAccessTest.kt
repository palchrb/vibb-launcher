package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Modify system settings" without adb (PLAN fix backlog, 2026-10-24): the Settings row and its report. */
class WriteSettingsAccessTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, path).readText()

    @Test
    fun `the page access - no page only once the window's plan is applied`() {
        fun access(available: Boolean, open: Boolean, applied: Boolean, missing: Boolean = false) =
            systemPageAccess(pageAvailable = available, windowOpen = open, overrideApplied = applied, pageMissing = missing)
        assertEquals(SystemPageAccess.CAN_OPEN, access(available = true, open = true, applied = true))
        assertEquals(SystemPageAccess.CAN_OPEN, access(available = true, open = true, applied = false))
        // Managed, no pause: Android's Settings is hidden - pause first, as the battery row says.
        assertEquals(SystemPageAccess.PAUSE_FIRST, access(available = false, open = false, applied = false))
        assertEquals(SystemPageAccess.PAUSE_FIRST, access(available = false, open = false, applied = true))
        // The pause has started but its apply hasn't unhidden Settings yet (code QA Q-2): a moment, never "no page".
        assertEquals(SystemPageAccess.WAIT, access(available = false, open = true, applied = false))
        // The window's plan is in place and still nothing resolves: the phone has no such page.
        assertEquals(SystemPageAccess.NO_PAGE, access(available = false, open = true, applied = true))
        // Every page resolved but threw ActivityNotFoundException at the tap.
        assertEquals(SystemPageAccess.NO_PAGE, access(available = true, open = true, applied = true, missing = true))
    }

    @Test
    fun `the page is ours by package, through the battery row's resolution`() {
        assertEquals("package:me.vibb.launcher", writeSettingsPageUri("me.vibb.launcher"))
        assertEquals("package:me.vibb.launcher.debug", writeSettingsPageUri("me.vibb.launcher.debug"))
        val access = src("java/com/kidslauncher/mdm/server/WriteSettingsAccess.kt")
        // Our own page first, then Android's list; each through systemPageIntent (MATCH_SYSTEM_ONLY +
        // dialogTarget: an unsuspended system package that isn't ours - during the pause).
        val own = access.indexOf("Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse(writeSettingsPageUri(context.packageName)))")
        val plain = access.indexOf("systemPageIntent(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))")
        assertTrue(own > 0 && plain > own)
        assertEquals(2, Regex("""systemPageIntent\(""").findAll(access).count())
        val battery = src("java/com/kidslauncher/mdm/server/BatteryExemption.kt")
        assertTrue("PackageManager.MATCH_SYSTEM_ONLY" in battery)
        assertTrue("dialogTarget(resolved, context.packageName)" in battery)
        assertTrue("fun dialogIntent(context: Context): Intent? = systemPageIntent(" in battery)
    }

    @Test
    fun `write_settings_granted is reported under the server's key and left out when unknown`() {
        for (granted in listOf(true, false)) {
            val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, writeSettingsGranted = granted)
            val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
            assertEquals("$granted", json["write_settings_granted"].toString())
        }
        val unknown = StatusReportRequest(lockReason = "NONE", kioskEngaged = true)
        val unknownJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), unknown)).jsonObject
        assertTrue("write_settings_granted" !in unknownJson)
        assertTrue("writeSettingsGranted = WriteSettingsAccess.granted(context)" in src("java/com/kidslauncher/mdm/server/MdmSyncWorker.kt"))
    }
}
