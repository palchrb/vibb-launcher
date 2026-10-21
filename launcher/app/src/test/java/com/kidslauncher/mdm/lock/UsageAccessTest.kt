package com.kidslauncher.mdm.lock

import android.app.AppOpsManager
import com.kidslauncher.mdm.server.ResolvedDialog
import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.LockStateReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 57's prep release (§7.4.1): the Usage access read, the report's two `lock_state` fields
 * against the shared contract `usage_access_status.json` (byte-identical with `server/testdata/`,
 * whose `step10::usage_access_status_contract` posts it), the PIN-gated Settings row, the manifest
 * line that keeps a grant alive, and that nothing reads usage events yet.
 */
class UsageAccessTest {
    private val contract = javaClass.classLoader!!.getResource("usage_access_status.json")!!.readText()

    private fun mainDir() = listOf("src/main", "app/src/main").map(::File).first { it.exists() }

    /** Code only: comments may name what the code must never do. */
    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `access mirrors the service - the op allowed, or default with the permission`() {
        assertTrue(usageAccessGranted(AppOpsManager.MODE_ALLOWED, permissionGranted = false))
        assertTrue(usageAccessGranted(AppOpsManager.MODE_ALLOWED, permissionGranted = true))
        assertTrue(usageAccessGranted(AppOpsManager.MODE_DEFAULT, permissionGranted = true))
        assertFalse(usageAccessGranted(AppOpsManager.MODE_DEFAULT, permissionGranted = false))
        for (mode in listOf(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_FOREGROUND)) {
            assertFalse("$mode", usageAccessGranted(mode, permissionGranted = true))
        }
    }

    @Test
    fun `Android 14 is unsupported, 15 and later are left out`() {
        assertEquals(true, voipOverLockUnsupported(34))
        assertNull(voipOverLockUnsupported(35))
        assertNull(voipOverLockUnsupported(36))
    }

    @Test
    fun `the report encodes exactly the contract, and leaves both fields out when unknown`() {
        val report = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true, capabilities = listOf("pin_lock_v1", VOIP_USAGE_CAPABILITY),
            lockState = LockStateReport(
                active = true, inactive = null, locked = true, failures = 0, backoffUntilMs = null, exemptYields = 0,
                voipFsiDenied = emptyList(), voipRang = emptyList(),
                usageAccessGranted = false, voipOverLockUnsupported = voipOverLockUnsupported(34),
            ),
        )
        assertEquals(contract.trimEnd(), ServerJson.encodeToString(StatusReportRequest.serializer(), report))
        val unknown = LockStateReport(
            active = true, inactive = null, locked = true, failures = 0, backoffUntilMs = null, exemptYields = 0,
            voipFsiDenied = emptyList(), voipRang = emptyList(),
            usageAccessGranted = null, voipOverLockUnsupported = voipOverLockUnsupported(35),
        )
        val keys = ServerJson.parseToJsonElement(ServerJson.encodeToString(LockStateReport.serializer(), unknown)).jsonObject.keys
        assertFalse(keys.toString(), "usage_access_granted" in keys)
        assertFalse(keys.toString(), "voip_over_lock_unsupported" in keys)
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/usage_access_status.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    @Test
    fun `every report says it reports the grant, and fills both fields`() {
        assertTrue(VOIP_USAGE_CAPABILITY in STATUS_CAPABILITIES)
        assertEquals("voip_usage_v1", VOIP_USAGE_CAPABILITY)
        // Not the gating release: nothing says VoIP is gated yet.
        assertFalse(STATUS_CAPABILITIES.any { it.startsWith("voip_gated") })
        val runtime = code(File(mainDir(), "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt"))
        assertTrue(runtime.contains("UsageAccess.report(app)"))
        assertTrue(runtime.contains("usageAccessGranted = usageAccessGranted"))
        assertTrue(runtime.contains("voipOverLockUnsupported = voipOverLockUnsupported"))
    }

    @Test
    fun `the Settings row - Android 15 first, then the grant, then the page`() {
        for (granted in listOf(true, false, null)) {
            for (page in listOf(true, false)) {
                assertEquals(UsageAccessRow.NEEDS_ANDROID_15, usageAccessRowState(granted, 34, page))
            }
        }
        assertEquals(UsageAccessRow.ON, usageAccessRowState(true, 35, pageAvailable = false))
        assertEquals(UsageAccessRow.ON, usageAccessRowState(true, 36, pageAvailable = true))
        assertEquals(UsageAccessRow.UNKNOWN, usageAccessRowState(null, 36, pageAvailable = true))
        assertEquals(UsageAccessRow.CAN_OPEN, usageAccessRowState(false, 36, pageAvailable = true))
        assertEquals(UsageAccessRow.PAUSE_FIRST, usageAccessRowState(false, 36, pageAvailable = false))
    }

    @Test
    fun `the row opens only an unsuspended system page, the package page before the list`() {
        val own = "me.vibb.launcher"
        val details = ResolvedDialog("com.android.settings", "com.android.settings.Settings\$AppUsageAccessSettingsActivity", system = true, suspended = false)
        val list = ResolvedDialog("com.android.settings", "com.android.settings.Settings\$UsageAccessSettingsActivity", system = true, suspended = false)
        assertEquals(UsageAccessPageChoice(UsageAccessPage.FOR_PACKAGE, details), usageAccessPageChoice(details, list, own))
        assertEquals(UsageAccessPageChoice(UsageAccessPage.LIST, list), usageAccessPageChoice(null, list, own))
        // Suspended (a managed phone outside a pause), not a system app, or our own: never.
        val suspended = details.copy(suspended = true)
        assertNull(usageAccessPageChoice(suspended, list.copy(suspended = true), own))
        assertNull(usageAccessPageChoice(details.copy(system = false), list.copy(system = false), own))
        assertNull(usageAccessPageChoice(details.copy(packageName = own), list.copy(packageName = own), own))
        assertEquals(UsageAccessPageChoice(UsageAccessPage.LIST, list), usageAccessPageChoice(suspended, list, own))
        assertNull(usageAccessPageChoice(null, null, own))
    }

    @Test
    fun `the row is wired like the battery row`() {
        val settings = code(File(mainDir(), "java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt"))
        assertTrue(settings.contains("UsageAccessSettings.pageIntent(context)"))
        assertTrue(settings.contains("usageAccessPageLauncher.launch(intent)"))
        // On return: read again, and a change asks for a sync.
        assertTrue(settings.contains("SyncRunner.request(context.applicationContext, \"usage_access\")"))
        val prefs = File(mainDir(), "res/xml/preferences.xml").readText()
        assertTrue(prefs.contains("android:key=\"settings_mdm_calls_over_lock\""))
        val row = code(File(mainDir(), "java/com/kidslauncher/mdm/lock/UsageAccessRow.kt"))
        assertTrue(row.contains("Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse(\"package:\$own\")"))
        assertTrue(row.contains("BatteryExemption.resolveSystemOnly"))
    }

    @Test
    fun `the manifest declares PACKAGE_USAGE_STATS - a build without it would reset the grant`() {
        val android = "http://schemas.android.com/apk/res/android"
        val tools = "http://schemas.android.com/tools"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(mainDir(), "AndroidManifest.xml"))
        val perms = doc.getElementsByTagName("uses-permission")
        val usage = (0 until perms.length).map { perms.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == "android.permission.PACKAGE_USAGE_STATS" }
        assertEquals("ProtectedPermissions", usage.getAttributeNS(tools, "ignore"))
        assertFalse(usage.hasAttributeNS(android, "maxSdkVersion"))
    }

    @Test
    fun `the prep release reads only the app-op - no usage events anywhere`() {
        val access = code(File(mainDir(), "java/com/kidslauncher/mdm/lock/UsageAccess.kt"))
        assertTrue(access.contains("checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS"))
        assertTrue(access.contains("checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)"))
        val banned = listOf("UsageStatsManager", "UsageEvents", "UsageEventsQuery", "queryEvents", "queryUsageStats", "USAGE_STATS_SERVICE")
        val hits = File(mainDir(), "java").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { f -> banned.filter { code(f).contains(it) }.map { "${f.name}: $it" } }
            .toList()
        assertEquals(emptyList<String>(), hits)
    }
}
