package com.kidslauncher.mdm.assistant

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.SystemPageAccess
import com.kidslauncher.mdm.server.timedWindowActive
import com.kidslauncher.mdm.server.WindowStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 98 §3: "Fullfør oppsett"'s rows, the default-apps window's ends and comparison, the steps left. */
class AssistantSetupTest {
    @Test
    fun `the assistant row - chosen, or the page's access`() {
        assertEquals(AssistantRow.CHOSEN, assistantRowState(true, SystemPageAccess.PAUSE_FIRST))
        assertEquals(AssistantRow.CAN_OPEN, assistantRowState(false, SystemPageAccess.CAN_OPEN))
        assertEquals(AssistantRow.PAUSE_FIRST, assistantRowState(false, SystemPageAccess.PAUSE_FIRST))
        assertEquals(AssistantRow.WAIT, assistantRowState(false, SystemPageAccess.WAIT))
        assertEquals(AssistantRow.NO_PAGE, assistantRowState(false, SystemPageAccess.NO_PAGE))
        assertEquals(R.string.settings_assistant_chosen, assistantRowText(AssistantRow.CHOSEN))
        // Shown while bit 64 is on or the role is held.
        assertTrue(assistantRowShown(bit = true, roleHeld = false))
        assertTrue(assistantRowShown(bit = false, roleHeld = true))
        assertFalse(assistantRowShown(bit = false, roleHeld = false))
    }

    @Test
    fun `the Wi-Fi row and the call filter row`() {
        assertEquals(WifiApmRow.KEPT, wifiApmRowState(true, false))
        assertEquals(WifiApmRow.TURNS_OFF, wifiApmRowState(false, true))
        assertEquals(WifiApmRow.CAN_START, wifiApmRowState(null, true))
        assertEquals(WifiApmRow.PAUSE_FIRST, wifiApmRowState(null, false))
        assertTrue(screeningRowShown(callsManaged = true, screeningHeld = true, redirectionHeld = false))
        assertFalse(screeningRowShown(callsManaged = true, screeningHeld = true, redirectionHeld = true))
        assertFalse(screeningRowShown(callsManaged = false, screeningHeld = false, redirectionHeld = false))
    }

    @Test
    fun `the window closes on the role, the page, 5 minutes, the pause's end and a reboot`() {
        // The role or the page's answer store it expired; 5 minutes and a reboot make it inactive by BootClock's rule.
        assertTrue(windowCloses(stored = true, active = false, pauseOpen = true))
        assertTrue(windowCloses(stored = true, active = true, pauseOpen = false))
        assertFalse(windowCloses(stored = true, active = true, pauseOpen = true))
        assertFalse(windowCloses(stored = false, active = false, pauseOpen = false))
        val start = WindowStart(untilWallMs = 1_000_000 + ASSISTANT_PICK_MS, elapsedStartMs = 50_000, bootCount = 3)
        assertTrue(timedWindowActive(start, 1_060_000, 110_000, 3, ASSISTANT_PICK_MS))
        assertFalse(timedWindowActive(start, 1_000_000 + ASSISTANT_PICK_MS, 50_000 + ASSISTANT_PICK_MS, 3, ASSISTANT_PICK_MS))
        assertFalse(timedWindowActive(start, 1_060_000, 10_000, 4, ASSISTANT_PICK_MS))
        // The role or the page: the stored copy's wall end is 0.
        assertFalse(timedWindowActive(start.copy(untilWallMs = 0), 1_060_000, 110_000, 3, ASSISTANT_PICK_MS))
    }

    @Test
    fun `the comparison reports what Vibb can't take back`() {
        val before = DefaultAppsSnapshot(home = true, dialer = true, sms = true, callScreening = true, callRedirection = true, browser = "org.mozilla.firefox")
        assertEquals(emptyList<String>(), movedRoles(before, before))
        // The dialer and SMS are taken back by the apply; never reported.
        assertEquals(emptyList<String>(), movedRoles(before, before.copy(dialer = false, sms = false)))
        assertEquals(listOf("home", "call_screening", "browser"), movedRoles(before, before.copy(home = false, callScreening = false, browser = "com.android.chrome")))
        assertEquals(listOf("call_redirection"), movedRoles(before, before.copy(callRedirection = false)))
        // A role that wasn't Vibb's before isn't Vibb's loss.
        assertEquals(emptyList<String>(), movedRoles(before.copy(callScreening = false), before.copy(callScreening = false)))
        assertTrue(movedRoles(before, before.copy(home = false)).all { it in setOf("home", "call_screening", "call_redirection", "browser") })
    }

    @Test
    fun `the steps left follow the summary card's rule`() {
        assertEquals(0, setupStepsLeft(true, false, false, airplaneBit = false, roleHeld = false, wifiKept = null, screeningNeeded = false))
        assertEquals(1, setupStepsLeft(false, false, false, false, false, null, false))
        assertEquals(1, setupStepsLeft(true, true, false, false, false, null, false))
        assertEquals(1, setupStepsLeft(true, false, false, airplaneBit = true, roleHeld = false, wifiKept = null, screeningNeeded = false))
        assertEquals(1, setupStepsLeft(true, false, false, airplaneBit = true, roleHeld = true, wifiKept = null, screeningNeeded = false))
        // Wi-Fi set up either way counts as done.
        assertEquals(0, setupStepsLeft(true, false, false, airplaneBit = true, roleHeld = true, wifiKept = false, screeningNeeded = false))
        assertEquals(2, setupStepsLeft(false, false, false, airplaneBit = true, roleHeld = false, wifiKept = null, screeningNeeded = false))
        assertEquals(1, setupStepsLeft(true, false, null, false, false, null, screeningNeeded = true))
    }
}
