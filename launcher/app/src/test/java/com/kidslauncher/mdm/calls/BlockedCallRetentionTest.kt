package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedCallRetentionTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_790_000_000_000L

    @Test
    fun `blocked calls are kept 30 days`() {
        assertEquals(30, BLOCKED_CALL_RETENTION_DAYS)
        assertEquals(now - 30 * day, blockedCallCutoffMs(now))
        // A call 29 days old stays, one 31 days old goes (the delete is `date < cutoff`).
        assertTrue(now - 29 * day >= blockedCallCutoffMs(now))
        assertTrue(now - 31 * day < blockedCallCutoffMs(now))
    }

    @Test
    fun `the prune runs once a day`() {
        assertTrue(blockedCallPruneDue(null, now))
        assertFalse(blockedCallPruneDue(now - day + 1, now))
        assertTrue(blockedCallPruneDue(now - day, now))
        // The clock went back past the last run: run rather than wait for the old date.
        assertTrue(blockedCallPruneDue(now + 1_000, now))
    }

    @Test
    fun `only on a managed, unlocked phone that may write the call log`() {
        assertTrue(blockedCallPruneAllowed(callsManaged = true, unlocked = true, canWriteCallLog = true))
        assertFalse(blockedCallPruneAllowed(callsManaged = false, unlocked = true, canWriteCallLog = true))
        assertFalse(blockedCallPruneAllowed(callsManaged = true, unlocked = false, canWriteCallLog = true))
        assertFalse(blockedCallPruneAllowed(callsManaged = true, unlocked = true, canWriteCallLog = false))
    }

    @Test
    fun `the delete names only blocked calls`() {
        // CallLog.Calls.BLOCKED_TYPE; MISSED (3), REJECTED (5) and OUTGOING (2) are never touched.
        assertEquals(6, android.provider.CallLog.Calls.BLOCKED_TYPE)
    }
}
