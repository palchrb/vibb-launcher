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
    fun `every row state and its text`() {
        for (access in SystemPageAccess.entries) {
            assertEquals(WriteSettingsRow.GRANTED, writeSettingsRowState(true, access))
            assertEquals(WriteSettingsRow.UNKNOWN, writeSettingsRowState(null, access))
        }
        assertEquals(WriteSettingsRow.CAN_OPEN, writeSettingsRowState(false, SystemPageAccess.CAN_OPEN))
        assertEquals(WriteSettingsRow.PAUSE_FIRST, writeSettingsRowState(false, SystemPageAccess.PAUSE_FIRST))
        assertEquals(WriteSettingsRow.WAIT, writeSettingsRowState(false, SystemPageAccess.WAIT))
        assertEquals(WriteSettingsRow.NO_PAGE, writeSettingsRowState(false, SystemPageAccess.NO_PAGE))
        // Q-3: each state has its own text.
        assertEquals(R.string.settings_mdm_write_settings_granted, writeSettingsText(WriteSettingsRow.GRANTED))
        assertEquals(R.string.settings_mdm_write_settings_unknown, writeSettingsText(WriteSettingsRow.UNKNOWN))
        assertEquals(R.string.settings_mdm_write_settings_open, writeSettingsText(WriteSettingsRow.CAN_OPEN))
        assertEquals(R.string.settings_mdm_write_settings_pause_first, writeSettingsText(WriteSettingsRow.PAUSE_FIRST))
        assertEquals(R.string.settings_mdm_write_settings_wait, writeSettingsText(WriteSettingsRow.WAIT))
        assertEquals(R.string.settings_mdm_write_settings_no_page, writeSettingsText(WriteSettingsRow.NO_PAGE))
    }

    /** Q-3: the fragment's glue - the window, the applied plan, the tap guard, the sync on return, the re-reads. */
    @Test
    fun `the fragment feeds the rule what the plan says`() {
        val fragment = src("java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt")
        val access = fragment.substringAfter("private fun pageAccess(").substringBefore("\n    )\n")
        assertTrue("windowOpen = RestrictionsPause.isActive() || com.kidslauncher.mdm.server.OfflineOverride.isActive()," in access)
        assertTrue("overrideApplied = AppEnforcer.lastEnforcedOverride == true," in access)
        assertEquals(1, Regex("""systemPageAccess\(""").findAll(fragment).count())
        assertEquals(2, Regex("""pageAccess\((WriteSettingsAccess|FingerprintEnrol)\.pageIntents\(context\)""").findAll(fragment).count())
        // The tap opens the page only when the grant is known to be missing.
        assertTrue("if (WriteSettingsAccess.granted(context) == false) {" in fragment)
        // A change on return is reported at once.
        val back = fragment.substringAfter("private val writeSettingsLauncher").substringBefore("override fun onCreatePreferences")
        assertTrue("if (WriteSettingsAccess.granted(context) != writeSettingsBeforePage) {" in back)
        assertTrue("SyncRunner.request(context.applicationContext, \"write_settings\")" in back)
        assertTrue("row.summary = getString(writeSettingsText(writeSettingsRowState(WriteSettingsAccess.granted(context), access)))" in fragment)
        // Q-2: read again after each apply while shown, and when the pause starts.
        assertTrue("AppEnforcer.addAppliedListener(appliedListener)" in fragment.substringAfter("override fun onResume()"))
        assertTrue("AppEnforcer.removeAppliedListener(appliedListener)" in fragment.substringAfter("override fun onPause()"))
        val paused = fragment.substringAfter("restrictionsPaused.isChecked = true").substringBefore("}")
        assertTrue("updateSystemPageRows()" in paused)
        val enforcer = src("java/com/kidslauncher/mdm/server/AppEnforcer.kt")
        val applied = enforcer.indexOf("lastEnforcedOverride = overrideActive")
        assertTrue(applied > enforcer.indexOf("lastEnforcedPlayState = playState"))
        assertTrue(enforcer.indexOf("appliedListeners.forEach { it() }", applied) > applied)
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
        val open = fragment.substringAfter("private fun openFirstPage(").substringBefore("private fun updateFingerprintRow")
        assertTrue("for (page in pages)" in open)
        assertTrue("catch (e: ActivityNotFoundException)" in open)
        assertTrue("openFirstPage(pages, writeSettingsLauncher)" in fragment)
        // Re-read on every resume, as the battery row.
        val resume = fragment.substringAfter("override fun onResume()").substringBefore("private fun openFirstPage")
        assertTrue("updateSystemPageRows()" in resume)
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
