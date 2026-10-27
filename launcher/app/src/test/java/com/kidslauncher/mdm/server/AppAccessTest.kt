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

    /** The code QA's L5: a read that throws is unknown, never denied; what reads cleanly goes through [accessState]. */
    @Test
    fun `a failed read is unknown`() {
        assertEquals(AccessState.UNKNOWN, readAccess(SpecialAccess.MANAGE_MEDIA) { throw SecurityException("op") })
        assertEquals(AccessState.UNKNOWN, readAccess(SpecialAccess.MANAGE_MEDIA) { throw NullPointerException() })
        assertEquals(
            AccessState.DENIED,
            readAccess(SpecialAccess.MANAGE_MEDIA) { AccessReading(AppOpsManager.MODE_ERRORED, true, false, 36) },
        )
        assertEquals(
            AccessState.GRANTED,
            readAccess(SpecialAccess.EXACT_ALARM) { AccessReading(AppOpsManager.MODE_ERRORED, false, true, 36) },
        )
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

    @Test
    fun `the rows - allowed apps only, every state kept, by name`() {
        // Managed: the effective allowlist; never ours, never one the plan suspends.
        assertEquals(true, accessEligible("app.alextran.immich", false, listOf("app.alextran.immich"), emptySet(), "me.vibb.launcher"))
        // A system app never, even allowed (E4's M3; the report's rule).
        assertEquals(false, accessEligible("com.android.deskclock", true, listOf("com.android.deskclock"), emptySet(), "me.vibb.launcher"))
        assertEquals(false, accessEligible("org.other", false, listOf("app.alextran.immich"), emptySet(), "me.vibb.launcher"))
        assertEquals(false, accessEligible("app.alextran.immich", false, listOf("app.alextran.immich"), setOf("app.alextran.immich"), "me.vibb.launcher"))
        assertEquals(false, accessEligible("me.vibb.launcher", false, null, emptySet(), "me.vibb.launcher"))
        // Unmanaged: every third-party app.
        assertEquals(true, accessEligible("org.other", false, null, emptySet(), "me.vibb.launcher"))
        assertEquals(false, accessEligible("com.android.deskclock", true, null, emptySet(), "me.vibb.launcher"))
        val rows = accessRows(
            listOf("ws.xsoh.etar" to "Etar", "app.alextran.immich" to "Immich"),
            mapOf(
                "app.alextran.immich" to listOf(SpecialAccess.MANAGE_MEDIA to AccessState.GRANTED),
                "ws.xsoh.etar" to listOf(SpecialAccess.FULL_SCREEN to AccessState.UNKNOWN, SpecialAccess.EXACT_ALARM to AccessState.DENIED),
            ),
        )
        assertEquals(
            listOf(
                AccessRow("ws.xsoh.etar", "Etar", SpecialAccess.EXACT_ALARM, AccessState.DENIED),
                AccessRow("ws.xsoh.etar", "Etar", SpecialAccess.FULL_SCREEN, AccessState.UNKNOWN),
                AccessRow("app.alextran.immich", "Immich", SpecialAccess.MANAGE_MEDIA, AccessState.GRANTED),
            ),
            rows,
        )
        assertEquals("app_access:ws.xsoh.etar:exact_alarm", rows[0].key)
    }

    @Test
    fun `the pages - the app's own first, then the plain action, Android's actions`() {
        assertEquals(
            listOf(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA to "package:app.alextran.immich", android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA to null),
            accessPageActions(SpecialAccess.MANAGE_MEDIA, "app.alextran.immich"),
        )
        assertEquals(
            listOf(
                android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            ),
            SpecialAccess.entries.map { accessPageActions(it, "p.q").first().first },
        )
    }

    @Test
    fun `the summary - an approval says how to change it, the rest how to get there`() {
        val a = AccessState.GRANTED
        assertEquals(AccessRowText.APPROVED, accessRowText(a, SystemPageAccess.CAN_OPEN))
        assertEquals(AccessRowText.APPROVED_PAUSE_FIRST, accessRowText(a, SystemPageAccess.PAUSE_FIRST))
        assertEquals(AccessRowText.OPEN, accessRowText(AccessState.DENIED, SystemPageAccess.CAN_OPEN))
        assertEquals(AccessRowText.UNKNOWN_OPEN, accessRowText(AccessState.UNKNOWN, SystemPageAccess.CAN_OPEN))
        assertEquals(AccessRowText.PAUSE_FIRST, accessRowText(AccessState.UNKNOWN, SystemPageAccess.PAUSE_FIRST))
        assertEquals(AccessRowText.WAIT, accessRowText(AccessState.DENIED, SystemPageAccess.WAIT))
        assertEquals(AccessRowText.NO_PAGE, accessRowText(AccessState.DENIED, SystemPageAccess.NO_PAGE))
        val media = SpecialAccess.MANAGE_MEDIA
        assertEquals(com.kidslauncher.mdm.R.string.app_access_open, accessRowTextRes(AccessRowText.OPEN, media))
        // E4's M2: Etar's first start outside a pause crashes - the alarm row says to open the app once in the pause.
        assertEquals(com.kidslauncher.mdm.R.string.app_access_open_exact_alarm, accessRowTextRes(AccessRowText.OPEN, SpecialAccess.EXACT_ALARM))
        assertEquals(com.kidslauncher.mdm.R.string.app_access_approved, accessRowTextRes(AccessRowText.APPROVED, media))
        assertEquals(com.kidslauncher.mdm.R.string.settings_mdm_write_settings_pause_first, accessRowTextRes(AccessRowText.PAUSE_FIRST, media))
        assertEquals(AccessRowText.entries.size, AccessRowText.entries.map { accessRowTextRes(it, media) }.toSet().size)
        assertEquals(SpecialAccess.entries.size, SpecialAccess.entries.map(::accessNameRes).toSet().size)
        assertEquals(com.kidslauncher.mdm.R.string.settings_mdm_write_settings, accessNameRes(SpecialAccess.WRITE_SETTINGS))
    }
}
