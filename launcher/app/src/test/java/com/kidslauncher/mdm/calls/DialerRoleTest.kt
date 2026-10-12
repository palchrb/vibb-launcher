package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialerRoleTest {

    private val managed = CallPolicyState.Managed(CallRules(callsEnabled = true))
    private val off = CallPolicyState.Managed(CallRules(callsEnabled = false))
    private val failClosed = CallPolicyState.UnknownFailClosed
    private val unmanaged = CallPolicyState.Unmanaged

    @Test
    fun `managed or unknown rules take the role`() {
        for (state in listOf(managed, off, failClosed)) {
            assertEquals(RoleAction.TAKE, dialerRoleAction(state, roleHeld = false, takenByUs = false))
            assertEquals(RoleAction.TAKE, dialerRoleAction(state, roleHeld = false, takenByUs = true))
            assertEquals(RoleAction.NONE, dialerRoleAction(state, roleHeld = true, takenByUs = false))
        }
    }

    @Test
    fun `unmanaged hands back only a role we took`() {
        assertEquals(RoleAction.RELEASE, dialerRoleAction(unmanaged, roleHeld = true, takenByUs = true))
        assertEquals(RoleAction.NONE, dialerRoleAction(unmanaged, roleHeld = true, takenByUs = false))
        assertEquals(RoleAction.NONE, dialerRoleAction(unmanaged, roleHeld = false, takenByUs = true))
    }

    /** QA 02 criterion T13 (the local half). */
    @Test
    fun `an old server releases the role only on a phone whose calls were never managed`() {
        val oldServer = com.kidslauncher.mdm.server.dto.PolicyResponse(allowlist = listOf("a"))
        val never = callPolicyState(com.kidslauncher.mdm.server.CachedPolicy.Ok(oldServer), callsManagedLast = false, lastRules = null)
        assertEquals(RoleAction.RELEASE, dialerRoleAction(never, roleHeld = true, takenByUs = true))
        // After managed calls the old server's response is rejected and the cache keeps the rules;
        // even with only the last rules left, the role stays.
        val after = callPolicyState(com.kidslauncher.mdm.server.CachedPolicy.Corrupt("x"), callsManagedLast = true, lastRules = CallRules())
        assertEquals(RoleAction.NONE, dialerRoleAction(after, roleHeld = true, takenByUs = true))
    }

    @Test
    fun `the role prompt shows at most once a day and only when managed`() {
        val now = 100 * ROLE_PROMPT_INTERVAL_MS
        assertTrue(shouldPromptForRole(managed, false, now, 0))
        assertFalse(shouldPromptForRole(managed, true, now, 0))
        assertFalse(shouldPromptForRole(unmanaged, false, now, 0))
        assertFalse(shouldPromptForRole(managed, false, now, now - ROLE_PROMPT_INTERVAL_MS + 1))
        assertTrue(shouldPromptForRole(managed, false, now, now - ROLE_PROMPT_INTERVAL_MS))
        // A clock set back doesn't block the prompt for good.
        assertTrue(shouldPromptForRole(managed, false, now, now + 1000))
    }

    @Test
    fun `default apps are locked as soon as our dialer role is held while managed`() {
        assertTrue(lockDefaultApps(managed, dialerRoleHeld = true))
        assertTrue(lockDefaultApps(failClosed, dialerRoleHeld = true))
        assertFalse(lockDefaultApps(managed, dialerRoleHeld = false))
        assertFalse(lockDefaultApps(unmanaged, dialerRoleHeld = true))
    }

    @Test
    fun `a role change is signalled once per change (B1)`() {
        val held = RoleSnapshot(dialerHeld = true, redirectionHeld = true)
        assertTrue(roleReportNeeded(null, held))
        assertFalse(roleReportNeeded(held, held))
        assertTrue(roleReportNeeded(held, held.copy(dialerHeld = false)))
        assertTrue(roleReportNeeded(held, held.copy(redirectionHeld = false)))
        assertTrue("security review L7", roleReportNeeded(held, held.copy(screeningHeld = true)))
    }

    /** Security review L7: the call-screening role is asked for too - it filters incoming calls
     * when the launcher isn't the phone app. One prompt at a time, in order. */
    @Test
    fun `the role prompts ask for screening too`() {
        fun prompt(state: CallPolicyState = managed, dialer: Boolean = true, error: Boolean = false, redirection: Boolean = true, screening: Boolean = true) =
            rolePromptFor(state, dialer, error, redirection, screening)
        assertEquals(null, prompt())
        assertEquals(ROLE_DIALER, prompt(dialer = false, error = true, redirection = false, screening = false))
        assertEquals("not the phone app: screening filters incoming calls", ROLE_CALL_SCREENING, prompt(dialer = false, screening = false))
        assertEquals(ROLE_CALL_SCREENING, prompt(state = failClosed, dialer = false, screening = false))
        // QA of cmd-expiry, Medium 2: with our dialer role the default-apps lock is on and Android
        // refuses exclusive-role prompts under it - nothing is prompted, adb grants them.
        assertEquals(null, prompt(redirection = false, screening = false))
        assertEquals(null, prompt(screening = false))
        assertEquals("unmanaged: nothing", null, prompt(state = unmanaged, dialer = false, error = true, screening = false))
        assertEquals("android.app.role.CALL_SCREENING", ROLE_CALL_SCREENING)
        assertEquals("android.app.role.CALL_REDIRECTION", ROLE_CALL_REDIRECTION)
        assertEquals("android.app.role.DIALER", ROLE_DIALER)
    }
}
