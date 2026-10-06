package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.play.PLAY_NEVER_RESTRICT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementPlanTest {

    private val controllable = listOf(OWN, DIALER, "org.example.music", "org.example.game", "com.android.chrome", SMS)

    private fun plan(
        allowlist: List<String>?,
        kioskDesired: Boolean = true,
        features: Long = 0,
        overrideActive: Boolean = false,
        dialer: String? = DIALER,
        calls: CallPolicyState = CallPolicyState.Unmanaged,
        ourDialer: Boolean = true,
        locked: Boolean = false,
        alarm: String? = null,
        ime: Set<String> = emptySet(),
        usable: Set<String> = emptySet(),
        noCalls: Boolean = false,
        rules: Boolean = false,
        budget: Boolean = false,
    ) = computeEnforcementPlan(
        allowlist, kioskDesired, features, overrideActive, controllable, OWN, dialer,
        callState = calls, ourDialerActive = ourDialer, smsPackages = setOf(SMS, "not.installed"),
        scheduleLocked = locked, alarmApp = alarm, inputMethods = ime,
        lockUsableApps = usable, ruleBlocksCalls = noCalls, timeRulesSet = rules, budgetSet = budget,
    )

    private val callsOn = CallPolicyState.Managed(CallRules(callsEnabled = true, smsEnabled = true))
    private val callsOff = CallPolicyState.Managed(CallRules(callsEnabled = false, smsEnabled = true))
    private val smsOff = CallPolicyState.Managed(CallRules(callsEnabled = true, smsEnabled = false))
    private val failClosed = CallPolicyState.UnknownFailClosed
    private val allCallStates = listOf(CallPolicyState.Unmanaged, callsOn, callsOff, smsOff, failClosed)

    @Test
    fun `null allowlist suspends nothing and has no kiosk`() {
        val plan = plan(null)
        assertTrue(plan.suspend.isEmpty())
        assertNull(plan.kioskPackages)
    }

    @Test
    fun `empty allowlist suspends everything except own and dialer and pins only own`() {
        val plan = plan(emptyList())
        assertEquals(setOf("org.example.music", "org.example.game", "com.android.chrome", SMS), plan.suspend)
        assertEquals(setOf(OWN), plan.kioskPackages)
    }

    @Test
    fun `allowlist suspends the rest and pins allowed plus own`() {
        val plan = plan(listOf("org.example.music"))
        assertEquals(setOf("org.example.game", "com.android.chrome", SMS), plan.suspend)
        assertEquals(setOf("org.example.music", OWN), plan.kioskPackages)
    }

    @Test
    fun `kiosk not desired means no pinning but still suspends`() {
        val plan = plan(listOf("org.example.music"), kioskDesired = false)
        assertNull(plan.kioskPackages)
        assertEquals(setOf("org.example.game", "com.android.chrome", SMS), plan.suspend)
    }

    @Test
    fun `override active suspends nothing and has no kiosk`() {
        val plan = plan(emptyList(), overrideActive = true)
        assertTrue(plan.suspend.isEmpty())
        assertNull(plan.kioskPackages)
    }

    @Test
    fun `own package is never suspended`() {
        for (allowlist in listOf(null, emptyList(), listOf("org.example.music"))) {
            assertFalse(OWN in plan(allowlist).suspend)
            assertTrue(OWN in plan(allowlist).neverRestrict)
        }
    }

    /** QA #1: the preloaded dialer is the in-call UI for emergency calls. */
    @Test
    fun `system dialer is never suspended or hidden, managed or not`() {
        for (allowlist in listOf(null, emptyList(), listOf("org.example.music"))) {
            for (kiosk in listOf(true, false)) {
                for (override in listOf(true, false)) {
                    for (calls in allCallStates) {
                        val plan = plan(allowlist, kioskDesired = kiosk, overrideActive = override, calls = calls)
                        assertFalse("suspended for $allowlist $calls", DIALER in plan.suspend)
                        assertTrue(DIALER in plan.neverRestrict)
                    }
                }
            }
        }
    }

    /** QA 02 criterion T8. */
    @Test
    fun `system dialer is never pinned while calls are managed, even if allowlisted`() {
        for (calls in listOf(callsOn, callsOff, smsOff, failClosed)) {
            assertFalse(DIALER in plan(listOf(DIALER, "org.example.music"), calls = calls).kioskPackages.orEmpty())
            assertEquals(LOCK_TASK_FEATURE_KEYGUARD, plan(emptyList(), calls = calls).lockTaskFeatures and LOCK_TASK_FEATURE_KEYGUARD)
        }
    }

    @Test
    fun `with calls managed, our dialer replaces the outgoing-call restriction`() {
        // Our dialer screens outgoing calls: allowed contacts can be called.
        assertFalse(plan(emptyList(), calls = callsOn).restrictOutgoingCalls)
        assertFalse(plan(null, calls = callsOn).restrictOutgoingCalls)
        // Without it the system dialer's keypad is unscreened: emergency only.
        assertTrue(plan(emptyList(), calls = callsOn, ourDialer = false).restrictOutgoingCalls)
        assertTrue(plan(listOf(DIALER), calls = callsOn, ourDialer = false).restrictOutgoingCalls)
        // Calls off, or rules unknown: emergency only.
        assertTrue(plan(emptyList(), calls = callsOff).restrictOutgoingCalls)
        assertTrue(plan(null, calls = failClosed).restrictOutgoingCalls)
    }

    @Test
    fun `override and pause never lift call rules`() {
        assertTrue(plan(emptyList(), overrideActive = true, calls = callsOff).restrictOutgoingCalls)
        assertTrue(plan(emptyList(), overrideActive = true, calls = failClosed).restrictOutgoingCalls)
        assertTrue(plan(emptyList(), overrideActive = true, calls = smsOff).restrictSms)
        assertTrue(plan(emptyList(), overrideActive = true, calls = callsOn).denyCallPermissions)
        // ...while app restrictions are lifted.
        assertEquals(emptySet<String>(), plan(emptyList(), overrideActive = true, calls = callsOn).suspend)
    }

    @Test
    fun `SMS off restricts SMS and suspends the SMS apps whatever the allowlist says`() {
        for (allowlist in listOf(null, emptyList(), listOf(SMS))) {
            for (override in listOf(true, false)) {
                val plan = plan(allowlist, overrideActive = override, calls = smsOff)
                assertTrue(plan.restrictSms)
                assertTrue("$allowlist $override", SMS in plan.suspend)
                assertFalse("only installed controllable packages", "not.installed" in plan.suspend)
            }
        }
        assertTrue(plan(null, calls = failClosed).restrictSms)
        assertFalse(plan(listOf(SMS), calls = callsOn).restrictSms)
        assertFalse(SMS in plan(listOf(SMS), calls = callsOn).suspend)
        assertFalse(plan(listOf(SMS)).restrictSms)
    }

    @Test
    fun `the dialer and our own package are never suspended as SMS apps`() {
        val plan = computeEnforcementPlan(
            null, true, 0, false, controllable, OWN, DIALER,
            callState = smsOff, smsPackages = setOf(OWN, DIALER, SMS),
        )
        assertEquals(setOf(SMS), plan.suspend)
    }

    /** QA step 2 #4: the callback window reads wall-clock call-log times. */
    @Test
    fun `date and time stay locked while calls are managed, override or not`() {
        assertTrue(plan(null, calls = callsOn).lockDateTime)
        assertTrue(plan(emptyList(), overrideActive = true, calls = failClosed).lockDateTime)
        assertFalse(plan(emptyList(), overrideActive = true).lockDateTime)
    }

    @Test
    fun `call permissions are denied whenever calls are managed`() {
        assertFalse(plan(emptyList()).denyCallPermissions)
        for (calls in listOf(callsOn, callsOff, smsOff, failClosed)) {
            assertTrue(plan(null, calls = calls).denyCallPermissions)
        }
    }

    @Test
    fun `system dialer is not pinned on our account`() {
        assertFalse(DIALER in plan(emptyList()).kioskPackages.orEmpty())
        assertFalse(DIALER in plan(listOf("org.example.music")).kioskPackages.orEmpty())
        // Only an explicit parent choice pins it.
        assertTrue(DIALER in plan(listOf(DIALER)).kioskPackages.orEmpty())
    }

    @Test
    fun `unknown system dialer exempts nothing extra`() {
        val plan = plan(emptyList(), dialer = null)
        assertTrue(DIALER in plan.suspend)
        assertEquals(setOf(OWN) + PLAY_NEVER_RESTRICT, plan.neverRestrict)
    }

    /** QA step 1 #1: an unsuspended dialer must not be a free keypad. */
    @Test
    fun `outgoing calls are restricted while managed unless the dialer is allowlisted`() {
        assertTrue(plan(emptyList()).restrictOutgoingCalls)
        assertTrue(plan(listOf("org.example.music")).restrictOutgoingCalls)
        assertTrue(plan(listOf("org.example.music"), kioskDesired = false).restrictOutgoingCalls)
        assertFalse(plan(listOf(DIALER)).restrictOutgoingCalls)
        assertFalse(plan(null).restrictOutgoingCalls)
        assertFalse(plan(emptyList(), overrideActive = true).restrictOutgoingCalls)
        // No known system dialer: nothing is exempt, so any dialer is simply suspended.
        assertFalse(plan(emptyList(), dialer = null).restrictOutgoingCalls)
    }

    @Test
    fun `date and time are locked while managed and released otherwise`() {
        assertTrue(plan(emptyList()).lockDateTime)
        assertTrue(plan(listOf("org.example.music"), kioskDesired = false).lockDateTime)
        assertFalse(plan(null).lockDateTime)
        assertFalse(plan(listOf("org.example.music"), overrideActive = true).lockDateTime)
    }

    @Test
    fun `call permission targets are third-party apps asking for call permissions`() {
        val requested = mapOf(
            "org.example.voip" to listOf("android.permission.INTERNET", "android.permission.CALL_PHONE"),
            "org.example.answer" to listOf("android.permission.ANSWER_PHONE_CALLS"),
            "org.example.game" to listOf("android.permission.INTERNET"),
            OWN to listOf("android.permission.CALL_PHONE"),
        )
        assertEquals(setOf("org.example.voip", "org.example.answer"), callPermissionTargets(requested, OWN))
    }

    @Test
    fun `sms packages include the default SMS app`() {
        assertTrue("com.example.sms" in smsPackages("com.example.sms"))
        assertTrue(SMS in smsPackages(null))
        assertTrue("com.android.stk" in smsPackages(null))
    }

    @Test
    fun `keyguard is always forced on`() {
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD, plan(emptyList(), features = 0).lockTaskFeatures)
        assertEquals(63, plan(emptyList(), features = 63).lockTaskFeatures)
        assertEquals(1 or LOCK_TASK_FEATURE_KEYGUARD, plan(emptyList(), features = 1).lockTaskFeatures)
    }

    // Schedule lock (bedtime / outside screen time) - qa-security P0 #3

    private val everythingButOursAndDialer = setOf("org.example.music", "org.example.game", "com.android.chrome", SMS)

    @Test
    fun `schedule lock suspends every app but ours and the dialer, whatever the allowlist`() {
        for (allowlist in listOf(null, emptyList(), listOf("org.example.music"), listOf("org.example.music", DIALER, SMS))) {
            for (calls in allCallStates) {
                val plan = plan(allowlist, calls = calls, locked = true)
                assertEquals("$allowlist $calls", everythingButOursAndDialer, plan.suspend)
                assertEquals(setOf(OWN, DIALER) + PLAY_NEVER_RESTRICT, plan.neverRestrict)
            }
        }
    }

    @Test
    fun `schedule lock pins only our own package`() {
        assertEquals(setOf(OWN), plan(listOf("org.example.music"), locked = true).kioskPackages)
        // An allowlisted system dialer stays pinned while calls are unmanaged (its in-call UI),
        // never while they are managed.
        assertEquals(setOf(OWN, DIALER), plan(listOf(DIALER, "org.example.music"), locked = true).kioskPackages)
        assertEquals(setOf(OWN), plan(listOf(DIALER, "org.example.music"), calls = callsOn, locked = true).kioskPackages)
        assertEquals(setOf(OWN), plan(listOf("org.example.music"), calls = callsOn, locked = true).kioskPackages)
        // Kiosk itself still follows the server: no kiosk wanted, no pinning.
        assertNull(plan(listOf("org.example.music"), kioskDesired = false, locked = true).kioskPackages)
        assertNull(plan(null, locked = true).kioskPackages)
    }

    @Test
    fun `schedule lock leaves the call rules as they are`() {
        for (allowlist in listOf(null, emptyList(), listOf(DIALER))) {
            for (calls in allCallStates) {
                for (ourDialer in listOf(true, false)) {
                    val open = plan(allowlist, calls = calls, ourDialer = ourDialer)
                    val locked = plan(allowlist, calls = calls, ourDialer = ourDialer, locked = true)
                    val case = "$allowlist $calls $ourDialer"
                    assertEquals(case, open.restrictOutgoingCalls, locked.restrictOutgoingCalls)
                    assertEquals(case, open.restrictSms, locked.restrictSms)
                    assertEquals(case, open.denyCallPermissions, locked.denyCallPermissions)
                    assertEquals(case, open.lockDateTime, locked.lockDateTime)
                }
            }
        }
    }

    @Test
    fun `schedule lock suspends allowed apps without hiding them`() {
        val plan = plan(listOf("org.example.music"), locked = true)
        assertTrue("org.example.music" in plan.suspend)
        assertEquals(setOf("org.example.game", "com.android.chrome", SMS), plan.hide)
        assertTrue(plan.suspend.containsAll(plan.hide))
        // Unmanaged allowlist: suspended, nothing hidden.
        assertTrue(plan(null, locked = true).hide.isEmpty())
        // Without the lock, suspended = hidden as before.
        val open = plan(listOf("org.example.music"))
        assertEquals(open.suspend, open.hide)
    }

    @Test
    fun `schedule lock spares the alarm app but the allowlist still applies to it`() {
        val clock = "org.example.game"
        assertFalse(clock in plan(listOf(clock), locked = true, alarm = clock).suspend)
        assertFalse(clock in plan(null, locked = true, alarm = clock).suspend)
        // Not allowlisted: suspended and hidden as usual, lock or not.
        val notAllowed = plan(listOf("org.example.music"), locked = true, alarm = clock)
        assertTrue(clock in notAllowed.suspend && clock in notAllowed.hide)
        // Never pinned.
        assertFalse(clock in plan(listOf(clock), locked = true, alarm = clock).kioskPackages.orEmpty())
    }

    @Test
    fun `keyboards are never suspended or hidden`() {
        val ime = "com.android.chrome"
        for (locked in listOf(true, false)) {
            for (allowlist in listOf(null, emptyList(), listOf("org.example.music"))) {
                val plan = plan(allowlist, locked = locked, ime = setOf(ime))
                assertFalse(ime in plan.suspend)
                assertFalse(ime in plan.hide)
                assertTrue(ime in plan.neverRestrict)
            }
        }
    }

    @Test
    fun `an override or pause lifts the schedule lock`() {
        val plan = plan(listOf("org.example.music"), overrideActive = true, locked = true)
        assertTrue(plan.suspend.isEmpty())
        assertNull(plan.kioskPackages)
        // ...but not SMS-off, a call rule.
        assertEquals(setOf(SMS), plan(listOf("org.example.music"), overrideActive = true, calls = smsOff, locked = true).suspend)
    }

    // Time rules (handy step 6)

    @Test
    fun `a rule's exempt apps stay usable and pinned, if the allowlist allows them`() {
        val plan = plan(listOf("org.example.music", "org.example.game"), locked = true, usable = setOf("org.example.music", "com.android.chrome"))
        assertFalse("org.example.music" in plan.suspend)
        assertTrue("org.example.game" in plan.suspend)
        assertTrue("not allowlisted: still suspended and hidden", "com.android.chrome" in plan.hide)
        assertEquals(setOf(OWN, "org.example.music"), plan.kioskPackages)
        // Unmanaged allowlist: spared, no kiosk.
        assertFalse("com.android.chrome" in plan(null, locked = true, usable = setOf("com.android.chrome")).suspend)
        // No lock: usable apps mean nothing.
        assertEquals(plan(listOf("org.example.music")), plan(listOf("org.example.music"), usable = setOf("org.example.game")))
    }

    @Test
    fun `a rule without calls restricts outgoing calls and unpins the dialer, unless overridden`() {
        for (calls in allCallStates) {
            assertTrue("$calls", plan(listOf(DIALER), calls = calls, locked = true, noCalls = true).restrictOutgoingCalls)
        }
        assertEquals(setOf(OWN), plan(listOf(DIALER, "org.example.music"), locked = true, noCalls = true).kioskPackages)
        assertFalse(plan(listOf(DIALER), overrideActive = true, locked = true, noCalls = true).restrictOutgoingCalls)
        assertFalse("only while locked", plan(listOf(DIALER), noCalls = true).restrictOutgoingCalls)
    }

    @Test
    fun `date and time stay locked whenever there are time rules, override or calls unmanaged`() {
        assertFalse(plan(null).lockDateTime)
        assertTrue("bedtime-only phone", plan(null, rules = true).lockDateTime)
        assertTrue("during an override", plan(listOf("org.example.music"), overrideActive = true, rules = true).lockDateTime)
        assertFalse(plan(listOf("org.example.music"), overrideActive = true).lockDateTime)
    }

    @Test
    fun `overlay windows are blocked while a budget is set, not under an override`() {
        assertTrue(plan(null, budget = true).restrictCreateWindows)
        assertFalse(plan(null, budget = true, overrideActive = true).restrictCreateWindows)
        assertFalse(plan(listOf("org.example.music"), rules = true).restrictCreateWindows)
    }

    private companion object {
        const val OWN = "me.vibb.launcher"
        const val DIALER = "com.android.dialer"
        const val SMS = "com.google.android.apps.messaging"
    }
}
