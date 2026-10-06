package com.kidslauncher.mdm.calls

import android.telecom.Call
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emulator run 2026-10-06: no "ongoing call" notification or call screen after the call. */
class CallUiReconcileTest {
    @Test
    fun `no calls or only disconnected ones clear the call UI`() {
        assertTrue(callUiShouldClear(emptyList(), null))
        assertTrue(callUiShouldClear(listOf(Call.STATE_DISCONNECTED), null))
        assertTrue(callUiShouldClear(listOf(Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTED), true))
    }

    @Test
    fun `telecom without a call clears even a call we still think is live`() {
        assertTrue(callUiShouldClear(listOf(Call.STATE_ACTIVE), false))
    }

    @Test
    fun `a live call keeps it`() {
        for (state in listOf(Call.STATE_RINGING, Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_HOLDING, Call.STATE_DISCONNECTING, Call.STATE_CONNECTING)) {
            assertFalse(callUiShouldClear(listOf(state), true))
            assertFalse(callUiShouldClear(listOf(Call.STATE_DISCONNECTED, state), null))
        }
    }
}
