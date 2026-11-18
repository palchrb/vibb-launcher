package com.kidslauncher.mdm.assistant

import com.kidslauncher.mdm.server.timedWindowActive
import com.kidslauncher.mdm.server.WindowStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 98 §3: "Fullfør oppsett"'s rows, the default-apps window's ends and comparison, the steps left. */
class AssistantSetupTest {
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

    /** Design 114 §2.2.1 (QA H1): the stored moved roles read live - each leaves once its role is back; screening and
     * redirection are row 13's. */
    @Test
    fun `a moved role put back leaves the list`() {
        val before = DefaultAppsSnapshot(home = true, dialer = true, sms = true, callScreening = true, callRedirection = true, browser = "org.mozilla.firefox")
        val moved = before.copy(home = false, dialer = false, sms = false, browser = "com.android.chrome")
        val all = listOf("home", "dialer", "sms", "call_screening", "call_redirection", "browser")
        assertEquals(listOf("home", "dialer", "sms", "browser"), stillMoved(all, before, moved))
        assertEquals(emptyList<String>(), stillMoved(all, before, before))
        assertEquals(listOf("browser"), stillMoved(listOf("home", "browser"), before, moved.copy(home = true)))
        assertEquals(listOf("home"), stillMoved(listOf("home", "browser"), before, moved.copy(browser = "org.mozilla.firefox")))
        // A list from an older build (no snapshot kept): home is read back, the browser stays listed.
        assertEquals(listOf("browser"), stillMoved(listOf("home", "browser", "call_screening"), null, before))
    }

    /** §2.2.1: the flow's "Standardapper" opens the pick window without a new snapshot, so the list and the original
     * holders stay (a source check: the window's own code). */
    @Test
    fun `the flow's pick window keeps the snapshot and the list`() {
        val main = listOf("src/main", "app/src/main").map { java.io.File(it) }.first { it.isDirectory }
        val setup = java.io.File(main, "java/com/kidslauncher/mdm/assistant/AssistantSetup.kt").readText()
        val open = setup.substringAfter("fun openPickWindow(").substringBefore("fun endPickWindow(")
        assertTrue("val kept = if (keepSnapshot) movedFrom(context) else null" in open)
        assertTrue("if (kept == null) AirplaneRuntime.storeDefaultAppsChanged(context, emptyList())" in open)
        val flow = java.io.File(main, "java/com/kidslauncher/mdm/setup/SetupFlowActivity.kt").readText()
        assertTrue("openPickWindow(app, keepSnapshot = step == SetupStep.DEFAULT_APPS)" in flow)
    }
}
