package com.kidslauncher.mdm.server

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The device admin declares exactly the uses-policies its DevicePolicyManager calls and
 * DeviceAdminReceiver callbacks need where AOSP checks the admin's policy (a missing one was a
 * SecurityException for setKeyguardDisabledFeatures on an Android 16 emulator; onPasswordChanged
 * never came, since only limit-password gets ACTION_PASSWORD_CHANGED). Scans every non-test source
 * set, like BackupServiceInvariantsTest.
 */
class AdminPoliciesTest {
    private val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }

    private val productionSources: List<File> = src.listFiles()!!
        .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }

    /** What needs which tag (AOSP DeviceAdminInfo / DevicePolicyManagerService). */
    private val needs: List<Pair<Regex, String>> = listOf(
        Regex("""\.setKeyguardDisabledFeatures\(""") to "disable-keyguard-features",
        Regex("""\.setCameraDisabled\(""") to "disable-camera",
        Regex("""\.lockNow\(\s*\)|\.setMaximumTimeToLock\(""") to "force-lock",
        Regex("""\.wipeData\(|\.wipeDevice\(|\.setMaximumFailedPasswordsForWipe\(""") to "wipe-data",
        Regex("""\.resetPassword\(""") to "reset-password",
        Regex("""\.setPasswordQuality\(|\.setPasswordMinimum\w*\(|\.setPasswordHistoryLength\(|\.isActivePasswordSufficient\(|override fun onPasswordChanged\(""") to "limit-password",
        Regex("""\.setPasswordExpirationTimeout\(|override fun onPasswordExpiring\(""") to "expire-password",
        Regex("""\.setStorageEncryption\(""") to "encrypted-storage",
        Regex("""\.getCurrentFailedPasswordAttempts\(|override fun onPassword(Failed|Succeeded)\(""") to "watch-login",
        Regex("""\.setGlobalProxy\(""") to "set-global-proxy",
    )

    private fun declaredInXml(): Set<String> {
        val xml = File(src, "main/res/xml/device_admin.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val policies = xml.substringAfter("<uses-policies>").substringBefore("</uses-policies>")
        return Regex("""<([a-z-]+)\s*/>""").findAll(policies).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the admin declares exactly the policies the code needs`() {
        val needed = mutableMapOf<String, MutableList<String>>()
        for (file in productionSources) {
            val text = file.readText()
            for ((pattern, tag) in needs) {
                if (pattern.containsMatchIn(text)) needed.getOrPut(tag) { mutableListOf() } += file.name
            }
        }
        assertEquals("needed by $needed", needed.keys, declaredInXml())
        assertEquals(declaredInXml(), DECLARED_ADMIN_POLICIES.keys)
    }

    @Test
    fun `the values are DeviceAdminInfo's`() {
        // USES_POLICY_LIMIT_PASSWORD 0, _FORCE_LOCK 3, _WIPE_DATA 4, _DISABLE_CAMERA 8, _DISABLE_KEYGUARD_FEATURES 9.
        assertEquals(
            mapOf("limit-password" to 0, "force-lock" to 3, "wipe-data" to 4, "disable-camera" to 8, "disable-keyguard-features" to 9),
            DECLARED_ADMIN_POLICIES,
        )
    }

    @Test
    fun `the report lists what isn't granted yet`() {
        assertEquals(emptyList<String>(), pendingAdminPolicies(DECLARED_ADMIN_POLICIES.mapValues { true }))
        assertEquals(
            listOf("limit-password", "disable-keyguard-features"),
            pendingAdminPolicies(
                mapOf("limit-password" to false, "force-lock" to true, "wipe-data" to null, "disable-keyguard-features" to false),
            ),
        )
        val report = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(
                com.kidslauncher.mdm.server.dto.StatusReportRequest.serializer(),
                com.kidslauncher.mdm.server.dto.StatusReportRequest(
                    lockReason = "NONE", kioskEngaged = true, adminPoliciesPending = listOf("disable-keyguard-features"),
                ),
            ),
        ).jsonObject
        assertEquals("[\"disable-keyguard-features\"]", report["admin_policies_pending"].toString())
    }
}
