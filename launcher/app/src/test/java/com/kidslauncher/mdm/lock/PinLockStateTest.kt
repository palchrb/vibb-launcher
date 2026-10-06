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
            assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(mode, LockEvent.ProcessStart(active = true, interactive = true)))
            assertEquals(LockStep(LockMode.LOCKED), step(mode, LockEvent.ProcessStart(active = true, interactive = false)))
            assertEquals(
                "our call: the lock, then our call screen over it",
                LockStep(LockMode.LOCKED, showLock = true),
                step(mode, LockEvent.ProcessStart(active = true, interactive = true, ourCall = true)),
            )
            assertEquals(
                "never over the system dialer's call",
                LockStep(LockMode.LOCKED),
                step(mode, LockEvent.ProcessStart(active = true, interactive = true, systemCall = true)),
            )
            assertEquals(LockStep(LockMode.DISABLED), step(mode, LockEvent.ProcessStart(false, true)))
        }
    }

    @Test
    fun `boot with Home first - the lock is the 1 s fallback, never left out (design 16 QA 2)`() {
        assertEquals(
            LockStep(LockMode.LOCKED, showLockLater = true),
            step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, homeFirst = true)),
        )
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = false, homeFirst = true)))
        assertEquals(
            "never over the system dialer's call",
            LockStep(LockMode.LOCKED),
            step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, systemCall = true, homeFirst = true)),
        )
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.ProcessStart(active = false, interactive = true, homeFirst = true)))
        assertEquals(1_000L, LOCK_FALLBACK_MS)
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
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.ScreenOff()))
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.ScreenOff()))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.ScreenOff()))
    }

    @Test
    fun `screen off during a call locks whatever the proximity sensor says (qa-10-code 2)`() {
        val ours = step(LockMode.UNLOCKED, LockEvent.ScreenOff(ourCall = true))
        assertEquals("the lock, its resume brings the call screen back", LockStep(LockMode.LOCKED, showLock = true), ours)
        assertEquals(LockStep(LockMode.LOCKED, showCall = true), step(ours.mode, LockEvent.LockResumed(ourCall = true)))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.UNLOCKED, LockEvent.ScreenOff(systemCall = true)))
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.CallsEnded(interactive = true)))
    }

    @Test
    fun `a call ending with the screen off locks - the screen went off during it (qa-10-code 2)`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.CallsEnded(interactive = false)))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.CallsEnded(interactive = true)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.CallsEnded(interactive = false)))
    }

    @Test
    fun `a call answered from the lock ends on the lock`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.CallsEnded()))
    }

    @Test
    fun `the lock resumed during our call brings the call screen to the front (qa-10-code 1)`() {
        assertEquals(LockStep(LockMode.LOCKED, showCall = true), step(LockMode.LOCKED, LockEvent.LockResumed(ourCall = true)))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.LockResumed(ourCall = false)))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.LockResumed(ourCall = true)))
    }

    @Test
    fun `locked during a call - remote lock or screen-on - our call screen ends up in front (qa-10-code 3)`() {
        val remote = step(LockMode.UNLOCKED, LockEvent.RemoteLock(ourCall = true))
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), remote)
        assertTrue(step(remote.mode, LockEvent.LockResumed(ourCall = true)).showCall)
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false, ourCall = true)))
    }

    @Test
    fun `screen on is a backstop only when the lock isn't showing and no system call is on`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false)))
        assertFalse(step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = true)).showLock)
        assertFalse(step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false, systemCall = true)).showLock)
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.ScreenOn(false)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.ScreenOn(false)))
    }

    @Test
    fun `unlock rechecks the time rules`() {
        assertEquals(LockStep(LockMode.UNLOCKED, recheckTimeRules = true), step(LockMode.LOCKED, LockEvent.Unlocked))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.Unlocked))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.Unlocked))
    }

    @Test
    fun `remote lock`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.RemoteLock()))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.UNLOCKED, LockEvent.RemoteLock(systemCall = true)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.RemoteLock()))
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

    // ---- design 17: VoIP calls over the lock ------------------------------------------------

    @Test
    fun `a VoIP ring while LOCKED wakes the lock as its ring screen - unlocked, the app rings itself`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true, wake = true), step(LockMode.LOCKED, LockEvent.VoipRinging))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.VoipRinging))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.VoipRinging))
    }

    @Test
    fun `during a VoIP call the lock is never started over it - like the system dialer's call (QA 2)`() {
        val inCall = VoipPhase.IN_CALL
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.UNLOCKED, LockEvent.ScreenOff(voip = inCall)))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.ScreenOff(voip = inCall)))
        assertEquals("its own FLAG_TURN_SCREEN_ON must not put the lock over it", LockStep(LockMode.LOCKED),
            step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false, voip = inCall)))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, voip = inCall)))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.UNLOCKED, LockEvent.RemoteLock(voip = inCall)))
        // Ringing: the lock (or the app's ring screen after Answer) is in front already.
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.ScreenOn(lockShowing = false, voip = VoipPhase.RINGING)))
        assertEquals("the power button only silences", LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.ScreenOff(voip = VoipPhase.RINGING)))
        assertEquals("ringing while unlocked: a screen-off locks as always", LockStep(LockMode.LOCKED, showLock = true),
            step(LockMode.UNLOCKED, LockEvent.ScreenOff(voip = VoipPhase.RINGING)))
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.RemoteLock(voip = VoipPhase.RINGING)))
    }

    @Test
    fun `the lock resumed during a VoIP call brings the app's call screen back, like ours`() {
        assertEquals(LockStep(LockMode.LOCKED, showVoipCall = true), step(LockMode.LOCKED, LockEvent.LockResumed(ourCall = false, voip = VoipPhase.IN_CALL)))
        assertEquals("our call first", LockStep(LockMode.LOCKED, showCall = true), step(LockMode.LOCKED, LockEvent.LockResumed(ourCall = true, voip = VoipPhase.IN_CALL)))
        assertEquals("ringing: the card is the lock itself", LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.LockResumed(false, VoipPhase.RINGING)))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.LockResumed(false, VoipPhase.IN_CALL)))
    }

    @Test
    fun `a VoIP call ending brings the lock back, as after a phone call`() {
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.LOCKED, LockEvent.VoipEnded(interactive = true)))
        assertEquals(LockStep(LockMode.LOCKED, showLock = true), step(LockMode.UNLOCKED, LockEvent.VoipEnded(interactive = false)))
        assertEquals(LockStep(LockMode.UNLOCKED), step(LockMode.UNLOCKED, LockEvent.VoipEnded(interactive = true)))
        assertEquals(LockStep(LockMode.DISABLED), step(LockMode.DISABLED, LockEvent.VoipEnded(interactive = false)))
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
