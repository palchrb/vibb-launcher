package com.kidslauncher.mdm.server

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
    fun `every row state`() {
        assertEquals(WriteSettingsRow.GRANTED, writeSettingsRowState(true, pageAvailable = false, windowOpen = false, pageMissing = false))
        assertEquals(WriteSettingsRow.GRANTED, writeSettingsRowState(true, pageAvailable = true, windowOpen = true, pageMissing = true))
        assertEquals(WriteSettingsRow.UNKNOWN, writeSettingsRowState(null, pageAvailable = true, windowOpen = true, pageMissing = false))
        assertEquals(WriteSettingsRow.CAN_OPEN, writeSettingsRowState(false, pageAvailable = true, windowOpen = true, pageMissing = false))
        // Managed, no pause: Android's Settings is hidden - pause first, as the battery row says.
        assertEquals(WriteSettingsRow.PAUSE_FIRST, writeSettingsRowState(false, pageAvailable = false, windowOpen = false, pageMissing = false))
        // During the pause Settings is shown: nothing resolving means the phone has no such page.
        assertEquals(WriteSettingsRow.NO_PAGE, writeSettingsRowState(false, pageAvailable = false, windowOpen = true, pageMissing = false))
        // Both pages resolved but threw ActivityNotFoundException at the tap.
        assertEquals(WriteSettingsRow.NO_PAGE, writeSettingsRowState(false, pageAvailable = true, windowOpen = true, pageMissing = true))
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
    fun `the row sits next to the battery row and tries the next page on ActivityNotFoundException`() {
        val prefs = src("res/xml/preferences.xml")
        val batteryRow = prefs.indexOf("android:key=\"settings_mdm_background_connection\"")
        val row = prefs.indexOf("android:key=\"settings_mdm_write_settings\"")
        assertTrue(batteryRow > 0 && row > batteryRow && row < prefs.indexOf("settings_play_install_mode"))
        val fragment = src("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        val open = fragment.substringAfter("private fun openWriteSettingsPage(").substringBefore("private fun updateWriteSettingsRow")
        assertTrue("for (page in pages)" in open)
        assertTrue("catch (e: ActivityNotFoundException)" in open)
        // Re-read on every resume, as the battery row.
        val resume = fragment.substringAfter("override fun onResume()").substringBefore("private fun openWriteSettingsPage")
        assertTrue("updateWriteSettingsRow()" in resume)
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
