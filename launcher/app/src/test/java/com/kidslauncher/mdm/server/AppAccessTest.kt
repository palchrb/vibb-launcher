package com.kidslauncher.mdm.server

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 68 §2.3/§3.3: reading the special accesses, and what the status report says. */
class AppAccessTest {

    private fun state(mode: Int, has: Boolean = false, exempt: Boolean = false, target: Int = 36, kind: SpecialAccess = SpecialAccess.MANAGE_MEDIA) =
        accessState(mode, has, exempt, target, kind)

    @Test
    fun `the mode table, DEFAULT read through the permission`() {
        assertEquals(AccessState.GRANTED, state(AppOpsManager.MODE_ALLOWED))
        assertEquals(AccessState.DENIED, state(AppOpsManager.MODE_IGNORED, has = true))
        assertEquals(AccessState.DENIED, state(AppOpsManager.MODE_ERRORED, has = true))
        assertEquals(AccessState.GRANTED, state(AppOpsManager.MODE_DEFAULT, has = true))
        assertEquals(AccessState.DENIED, state(AppOpsManager.MODE_DEFAULT, has = false))
        assertEquals(AccessState.UNKNOWN, state(AppOpsManager.MODE_FOREGROUND))
        // Full-screen notifications: `normal|appop`, so DEFAULT is granted whenever the permission is held (declared).
        assertEquals(AccessState.GRANTED, state(AppOpsManager.MODE_DEFAULT, has = true, kind = SpecialAccess.FULL_SCREEN))
    }

    @Test
    fun `exact alarms - battery exempt, an old target, and USE_EXACT_ALARM has no row`() {
        val alarm = SpecialAccess.EXACT_ALARM
        assertEquals(AccessState.GRANTED, state(AppOpsManager.MODE_ERRORED, exempt = true, kind = alarm))
        assertEquals(AccessState.GRANTED, state(AppOpsManager.MODE_DEFAULT, target = 32, kind = alarm))
        assertEquals(AccessState.DENIED, state(AppOpsManager.MODE_DEFAULT, target = 33, kind = alarm))
        // The battery exemption is an exact-alarm rule only.
        assertEquals(AccessState.DENIED, state(AppOpsManager.MODE_ERRORED, exempt = true))
        assertEquals(listOf(alarm), declaredAccesses(listOf(alarm.permission, "android.permission.CAMERA")))
        assertEquals(emptyList<SpecialAccess>(), declaredAccesses(listOf(alarm.permission, USE_EXACT_ALARM)))
        assertEquals(SpecialAccess.entries, declaredAccesses(SpecialAccess.entries.map { it.permission }))
    }

    @Test
    fun `the report - camera first, declared accesses, nothing is null`() {
        assertEquals("admin_denied", cameraReport(GRANT_STATE_DENIED))
        assertEquals("requested", cameraReport(GRANT_STATE_DEFAULT))
        val report = appAccessReport("requested", listOf(SpecialAccess.EXACT_ALARM to AccessState.UNKNOWN))
        assertEquals(listOf("camera" to "requested", "exact_alarm" to "unknown"), report!!.toList())
        assertNull(appAccessReport(null, emptyList()))
        // The server's keys (`app_permissions::ACCESSES`).
        assertEquals(
            listOf("manage_media", "exact_alarm", "write_settings", "usage_access", "full_screen"),
            SpecialAccess.entries.map { it.key },
        )
    }
}
