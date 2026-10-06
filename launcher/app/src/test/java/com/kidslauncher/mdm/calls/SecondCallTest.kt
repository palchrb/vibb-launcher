package com.kidslauncher.mdm.calls

import android.telecom.Call
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix round 2026-10-06: never a second call while one exists - emergency numbers excepted. */
class SecondCallTest {
    private val busy = listOf(Call.STATE_RINGING, Call.STATE_DIALING, Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_SIMULATED_RINGING)
    private val idle = listOf(Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_DISCONNECTED)

    @Test
    fun `no other call - allowed`() {
        assertTrue(secondCallAllowed(emptyList(), emergency = false))
    }

    @Test
    fun `a ringing, dialling, active or held call refuses a second one`() {
        for (state in busy) {
            assertFalse("$state", secondCallAllowed(listOf(state), emergency = false))
            assertFalse("$state", secondCallAllowed(listOf(Call.STATE_DISCONNECTED, state), emergency = false))
        }
    }

    @Test
    fun `emergency numbers always go through`() {
        for (state in busy) assertTrue("$state", secondCallAllowed(listOf(state), emergency = true))
    }

    @Test
    fun `the call being placed and ended calls don't count`() {
        for (state in idle) assertTrue("$state", secondCallAllowed(listOf(state), emergency = false))
    }
}
