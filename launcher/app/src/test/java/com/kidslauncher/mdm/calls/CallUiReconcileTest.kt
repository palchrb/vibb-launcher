package com.kidslauncher.mdm.calls

import android.telecom.Call
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator run 2026-10-06: no "ongoing call" notification or call screen after the call - and
 * (qa-fixround-2026-10-06 #1) never dropping a call that is still being placed, such as a quick
 * 112 redial that is CONNECTING when the previous call's timer fires.
 */
class CallUiReconcileTest {
    private val live = listOf(
        Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_DIALING,
        Call.STATE_RINGING, Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_PULLING_CALL,
        Call.STATE_DISCONNECTING, Call.STATE_AUDIO_PROCESSING, Call.STATE_SIMULATED_RINGING,
    )

    @Test
    fun `no calls or only disconnected ones clear the call UI`() {
        assertTrue(callUiShouldClear(emptyList(), emptyList()))
        assertTrue(callUiShouldClear(listOf(Call.STATE_DISCONNECTED), emptyList()))
        assertTrue(callUiShouldClear(listOf(Call.STATE_DISCONNECTED), listOf(Call.STATE_DISCONNECTED)))
    }

    @Test
    fun `a connecting emergency redial keeps the UI, in our list or only in the service's`() {
        // The old call ended, the 112 redial is still connecting (radio on, domain selection).
        assertFalse(callUiShouldClear(listOf(Call.STATE_DISCONNECTED, Call.STATE_CONNECTING), emptyList()))
        // Telecom added it to the service but onCallAdded hasn't reached our list yet.
        assertFalse(callUiShouldClear(listOf(Call.STATE_DISCONNECTED), listOf(Call.STATE_CONNECTING)))
        assertFalse(callUiShouldClear(emptyList(), listOf(Call.STATE_NEW)))
    }

    @Test
    fun `every state but DISCONNECTED is live`() {
        for (state in live) {
            assertFalse("$state", callUiShouldClear(listOf(state), emptyList()))
            assertFalse("$state", callUiShouldClear(emptyList(), listOf(state)))
        }
    }
}
