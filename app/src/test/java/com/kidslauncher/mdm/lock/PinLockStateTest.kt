package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handy step 10 (design §3, QA 10 #4/#5): every event in every state. */
class PinLockStateTest {
    private val all = LockMode.entries

    @Test
    fun `process start fails closed and shows the lock at once with the screen on`() {
        for (mode in all) {
            val on = step(mode, LockEvent.ProcessStart(active = true, interactive = true, inCall = false))
            assertEquals(LockStep(LockMode.LOCKED, showLock = true), on)
            assertEquals(
                LockStep(LockMode.LOCKED, showLock = false),
                step(mode, LockEvent.ProcessStart(active = true, interactive = false, inCall = false)),
            )
            assertEquals(
                "not over a call",
                LockStep(LockMode.LOCKED, showLock = false),
                step(mode, LockEvent.ProcessStart(active = true, interactive = true, inCall = true)),
            )
            assertEquals(LockStep(LockMode.DISABLED), step(mode, LockEvent.ProcessStart(false, true, false)))
        }
    }

    @Test
    fun `configuration switches the lock on without throwing the kid out, and off from anywhere`() {
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.DISABLED, LockEvent.Configured(true)))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.Configured(true)))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.Configured(true)))
        for (mode in all) assertEquals(LockStep(LockMode.DISABLED), step(mode, LockEvent.Configured(false)))
    }

    @Test
    fun `screen off locks and starts the lock right away`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.ScreenOff(false, null)))
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.ScreenOff(false, null)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.ScreenOff(false, null)))
    }

    @Test
    fun `screen off at the ear during a call doesn't lock, the power button does - shown when the call ends`() {
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.ScreenOff(inCall = true, proximityNear = true)))
        val power = step(LockMode.UNLOCKED, LockEvent.ScreenOff(inCall = true, proximityNear = false))
        assertEquals(LockStep(LockMode.LOCKED, showLock = false), power)
        assertEquals("unknown proximity = power button", LockMode.LOCKED, step(LockMode.UNLOCKED, LockEvent.ScreenOff(true, null)).mode)
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(power.mode, LockEvent.CallsEnded))
    }

    @Test
    fun `a call answered from the lock ends on the lock`() {
        val duringCall = step(LockMode.LOCKED, LockEvent.ScreenOff(inCall = true, proximityNear = true))
        assertEquals(LockMode.LOCKED, duringCall.mode)
        val ended = step(duringCall.mode, LockEvent.CallsEnded)
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), ended)
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.CallsEnded))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.CallsEnded))
    }

    @Test
    fun `screen on is a backstop only when the lock isn't showing and no call is on`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false, inCall = false)))
        assertFalse(step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = true, inCall = false)).showLock)
        assertFalse(step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false, inCall = true)).showLock)
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.ScreenOn(false, false)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.ScreenOn(false, false)))
    }

    @Test
    fun `unlock rechecks the time rules`() {
        assertEquals(LockStep(LockMode.UNLOCKED, recheckTimeRules = true), step(LockMode.LOCKED, LockEvent.Unlocked))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.Unlocked))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.Unlocked))
    }

    @Test
    fun `remote lock`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.RemoteLock(inCall = false)))
        assertEquals(LockStep(LockMode.LOCKED, showLock = false), step(LockMode.UNLOCKED, LockEvent.RemoteLock(inCall = true)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.RemoteLock(false)))
    }

    @Test
    fun `a time-rule screen started while LOCKED gets the PIN lock on top - both shown`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.TimeRuleShown))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.TimeRuleShown))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.TimeRuleShown))
    }

    @Test
    fun `activation decides why the lock is off`() {
        fun act(managed: Boolean = true, pin: Boolean = true, secure: Boolean = false, kg: Boolean = true, hash: Boolean = true, guard: Boolean = false) =
            lockActivation(managed, pin, secure, kg, hash, guard)
        assertEquals(true to null, act())
        assertEquals(false to LockInactive.NO_PIN, act(pin = false))
        assertEquals(false to LockInactive.NO_PIN, act(pin = false, managed = false))
        assertEquals(false to LockInactive.UNMANAGED, act(managed = false))
        assertEquals(false to LockInactive.ANDROID_CREDENTIAL, act(secure = true, kg = false))
        assertEquals(false to LockInactive.KEYGUARD_NOT_DISABLED, act(kg = false))
        assertEquals(false to LockInactive.CRASH_GUARD, act(guard = true))
        assertEquals("bad hash: on, parent code only", true to LockInactive.BAD_HASH, act(hash = false))
        assertTrue(wantKeyguardDisabled(true, true, false))
        assertFalse(wantKeyguardDisabled(true, true, true))
        assertFalse(wantKeyguardDisabled(false, true, false))
        assertFalse(wantKeyguardDisabled(true, false, false))
    }

    @Test
    fun `PIN entry`() {
        assertEquals(4, kidPinLength(null))
        assertEquals(4, kidPinLength(3))
        assertEquals(6, kidPinLength(6))
        assertEquals(4, kidPinLength(7))
        var entered = ""
        for (c in "12345") entered = appendDigit(entered, c, 4)
        assertEquals("1234", entered)
        assertEquals("123", deleteDigit(entered))
        assertEquals("", deleteDigit(""))
        assertEquals("12", appendDigit("12", 'x', 4))
    }
}
