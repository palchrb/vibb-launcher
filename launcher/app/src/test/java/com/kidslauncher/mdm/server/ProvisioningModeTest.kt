package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** QR provisioning on Android 12+ (fix round 2026-10-06): mode answer and manifest entries. */
class ProvisioningModeTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    @Test
    fun `fully managed when offered or when nothing is listed, else cancel`() {
        assertEquals(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE, PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        assertEquals(1, chooseProvisioningMode(null))
        assertEquals(1, chooseProvisioningMode(emptyList()))
        assertEquals(1, chooseProvisioningMode(listOf(2, 1)))
        assertNull(chooseProvisioningMode(listOf(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)))
    }

    @Test
    fun `both activities are exported, guarded by BIND_DEVICE_ADMIN, with the platform actions`() {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        val activities = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file).getElementsByTagName("activity")
        val byName = (0 until activities.length).map { activities.item(it) as Element }.associateBy { it.getAttributeNS(ns, "name") }
        for ((name, action) in mapOf(
            ".server.ProvisioningModeActivity" to "android.app.action.GET_PROVISIONING_MODE",
            ".server.PolicyComplianceActivity" to "android.app.action.ADMIN_POLICY_COMPLIANCE",
        )) {
            val activity = byName.getValue(name)
            assertEquals("true", activity.getAttributeNS(ns, "exported"))
            assertEquals("android.permission.BIND_DEVICE_ADMIN", activity.getAttributeNS(ns, "permission"))
            val actions = activity.getElementsByTagName("action")
            assertTrue((0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(ns, "name") == action })
            assertEquals("", activity.getAttributeNS(ns, "directBootAware"))
        }
    }
}
