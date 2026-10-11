package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 19b phase 1: the "Background connection" row and when it may open Android's dialog. */
class BatteryExemptionTest {
    private val own = "me.vibb.launcher"
    private val settings = ResolvedDialog("com.android.settings", "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations", system = true, suspended = false)

    @Test
    fun `the dialog opens only in an unsuspended system package that isn't ours`() {
        assertEquals(settings, dialogTarget(settings, own))
        // Hidden (a managed phone's Settings): nothing resolves.
        assertNull(dialogTarget(null, own))
        // Suspended: the "app paused" dialog instead - not started.
        assertNull(dialogTarget(settings.copy(suspended = true), own))
        // Not a system package: some app claiming the action.
        assertNull(dialogTarget(settings.copy(system = false), own))
        // Ours, even if it were a system app.
        assertNull(dialogTarget(settings.copy(packageName = own), own))
    }

    @Test
    fun `every row state`() {
        assertEquals(BatteryRow.EXEMPT, batteryRowState(exempt = true, dialogAvailable = false))
        assertEquals(BatteryRow.EXEMPT, batteryRowState(exempt = true, dialogAvailable = true))
        assertEquals(BatteryRow.CAN_ASK, batteryRowState(exempt = false, dialogAvailable = true))
        // Managed, no pause: Android's Settings is hidden - pause first.
        assertEquals(BatteryRow.PAUSE_FIRST, batteryRowState(exempt = false, dialogAvailable = false))
        assertEquals(BatteryRow.UNKNOWN, batteryRowState(exempt = null, dialogAvailable = true))
        assertEquals(BatteryRow.UNKNOWN, batteryRowState(exempt = null, dialogAvailable = false))
    }
}
