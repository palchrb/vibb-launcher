package com.kidslauncher.mdm.server

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Step 9 B2 with qa-09-design.md #6: our role-grantable permissions are fixed by policy. */
class OwnPermissionsTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val requested: List<String> = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map(::File).first { it.exists() }
        .let { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it) }
        .getElementsByTagName("uses-permission")
        .let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") } }

    @Test
    fun `decided on the DPM grant state, not on checkSelfPermission`() {
        // Role-granted: checkSelfPermission says granted, the DPM says default - fix it.
        assertTrue(shouldFixOwnPermission(dpmGrantState = 0, selfGranted = true))
        assertTrue(shouldFixOwnPermission(dpmGrantState = 2, selfGranted = false))
        assertFalse(shouldFixOwnPermission(dpmGrantState = GRANT_STATE_GRANTED, selfGranted = true))
    }

    @Test
    fun `every role-grantable permission our manifest requests is fixed`() {
        val fixed = ownPermissionsToFix(requested)
        assertEquals(requested.filter { it in ROLE_GRANTED_PERMISSIONS }.toSet(), fixed)
        for (p in listOf("READ_CONTACTS", "CALL_PHONE", "READ_PHONE_STATE", "READ_CALL_LOG", "CAMERA", "POST_NOTIFICATIONS")) {
            assertTrue(p, "android.permission.$p" in fixed)
        }
        // Not role permissions: left to the other self-grants.
        assertFalse("android.permission.ACCESS_FINE_LOCATION" in fixed)
    }

    @Test
    fun `the keyguard camera bit is ORed in while managed and only ours is cleared`() {
        assertEquals(KEYGUARD_DISABLE_SECURE_CAMERA, keyguardDisabledFeatures(0, managed = true))
        assertEquals(16 or KEYGUARD_DISABLE_SECURE_CAMERA, keyguardDisabledFeatures(16, managed = true))
        assertEquals(16, keyguardDisabledFeatures(16 or KEYGUARD_DISABLE_SECURE_CAMERA, managed = false))
    }

    @Test
    fun `fixed permissions are released only after a hand-back we caused (qa-09-code 4)`() {
        assertTrue(shouldResetOwnPermissions(callsManaged = false, dialerRoleHeld = false, fixedByUs = true))
        assertFalse(shouldResetOwnPermissions(callsManaged = true, dialerRoleHeld = false, fixedByUs = true))
        // The release failed: still our role, keep them fixed or the next try kills us.
        assertFalse(shouldResetOwnPermissions(callsManaged = false, dialerRoleHeld = true, fixedByUs = true))
        assertFalse(shouldResetOwnPermissions(callsManaged = false, dialerRoleHeld = false, fixedByUs = false))
    }

    @Test
    fun `Home comes to front once per real role change, never during a call (qa-09-code 3)`() {
        assertTrue(bringHomeAfterRoleChange(heldBefore = false, heldAfter = true, kioskOn = true, inCall = false))
        assertTrue(bringHomeAfterRoleChange(heldBefore = true, heldAfter = false, kioskOn = true, inCall = false))
        // A TAKE that keeps failing changes nothing: no pull to Home on every apply.
        assertFalse(bringHomeAfterRoleChange(heldBefore = false, heldAfter = false, kioskOn = true, inCall = false))
        assertFalse(bringHomeAfterRoleChange(heldBefore = false, heldAfter = true, kioskOn = true, inCall = true))
        assertFalse(bringHomeAfterRoleChange(heldBefore = false, heldAfter = true, kioskOn = false, inCall = false))
    }
}
