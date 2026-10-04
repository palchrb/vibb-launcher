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
    fun `default apps are locked only with our dialer in place while managed`() {
        assertTrue(lockDefaultApps(managed, roleHeld = true))
        assertTrue(lockDefaultApps(failClosed, roleHeld = true))
        assertFalse(lockDefaultApps(managed, roleHeld = false))
        assertFalse(lockDefaultApps(unmanaged, roleHeld = true))
    }
}
