package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRulesTest {

    private val mamma = RuleContact(1, "Mamma", "+4791234567", inbound = true, outbound = true, showOnHome = true)
    private val pappa = RuleContact(2, "Pappa", "+4790000002", inbound = true, outbound = true)
    private val bestemor = RuleContact(3, "Bestemor", "+4790000003", inbound = true, outbound = false)
    private val mormor = RuleContact(4, "Mormor", "+4790000004", inbound = false, outbound = true)
    private val opplysning = RuleContact(5, "1881", "1881", inbound = false, outbound = true)
    private val rules = CallRules(
        callsEnabled = true, smsEnabled = true, defaultCc = "47",
        contacts = listOf(mamma, pappa, bestemor, mormor, opplysning),
    )
    private val managed = CallPolicyState.Managed(rules)
    private val disabled = CallPolicyState.Managed(rules.copy(callsEnabled = false))
    private val failClosed = CallPolicyState.UnknownFailClosed
    private val unmanaged = CallPolicyState.Unmanaged

    /** A platform that knows the Norwegian emergency numbers. */
    private val platform: (String) -> Boolean? = { it in setOf("112", "110", "113") }
    private val platformThrows: (String) -> Boolean? = { null }

    private fun emergency(raw: String?, p: (String) -> Boolean? = platform) = Emergency.isEmergencyOutgoing(raw, "47", p)

    private fun out(raw: String?, state: CallPolicyState = managed) = decideOutgoing(raw, state, emergency(raw))

    private fun incoming(
        raw: String?,
        state: CallPolicyState = managed,
        presentationAllowed: Boolean = true,
        verificationFailed: Boolean = false,
        window: Boolean = false,
    ) = decideIncoming(raw, presentationAllowed, verificationFailed, state) { window }

    // Outgoing

    @Test
    fun `emergency is always allowed`() {
        for (state in listOf(unmanaged, managed, disabled, failClosed)) {
            for (number in listOf("112", "110", "113", "1 1 2")) {
                assertEquals("$number in $state", Verdict.ALLOW, out(number, state))
            }
        }
    }

    @Test
    fun `the platform throwing still makes 112 an emergency`() {
        assertTrue(emergency("112", platformThrows))
        assertTrue(emergency("911", platformThrows))
        assertEquals(Verdict.ALLOW, decideOutgoing("112", failClosed, emergency("112", platformThrows)))
    }

    @Test
    fun `emergency matching is exact and the static list is the country's real one`() {
        for (number in listOf("1121234", "112#", "*112", "+112", "08", "000", "118", "999")) {
            assertFalse(number, emergency(number, platformThrows))
        }
        // The platform knows more than the static list (e.g. roaming); it's ORed in.
        assertTrue(Emergency.isEmergencyOutgoing("999", "47") { it == "999" })
    }

    @Test
    fun `outbound contact is allowed, anyone else is blocked`() {
        assertEquals(Verdict.ALLOW, out("+4791234567"))
        assertEquals(Verdict.ALLOW, out("1881"))
        assertEquals(Verdict.BLOCK, out("+4799999999"))
        assertEquals(Verdict.BLOCK, out("99999999"))
    }

    @Test
    fun `national and international forms match`() {
        for (form in listOf("91234567", "912 34 567", "+47 912 34 567", "004791234567")) {
            assertEquals(form, Verdict.ALLOW, out(form))
            assertEquals(form, Verdict.ALLOW, incoming(form))
        }
        // No suffix matching: the same 8 digits abroad are someone else.
        assertEquals(Verdict.BLOCK, out("+4691234567"))
        assertEquals(Verdict.BLOCK, incoming("+4691234567"))
    }

    @Test
    fun `MMI and USSD codes, voicemail and unparseable numbers are blocked when managed`() {
        for (raw in listOf("*21*91234567#", "#31#91234567", "*#06#", "91234567,1", null)) {
            assertEquals("$raw", Verdict.BLOCK, out(raw))
            assertEquals("$raw", Verdict.ALLOW, out(raw, unmanaged))
        }
    }

    @Test
    fun `inbound-only contact can call in but not be called`() {
        assertEquals(Verdict.BLOCK, out(bestemor.number))
        assertEquals(Verdict.ALLOW, incoming(bestemor.number))
        // ...and outbound-only the other way round.
        assertEquals(Verdict.ALLOW, out(mormor.number))
        assertEquals(Verdict.BLOCK, incoming(mormor.number))
    }

    @Test
    fun `calls off blocks an allowlisted number both ways`() {
        assertEquals(Verdict.BLOCK, out(mamma.number, disabled))
        assertEquals(Verdict.BLOCK, incoming(mamma.number, disabled))
    }

    @Test
    fun `fail closed blocks everything but emergency`() {
        assertEquals(Verdict.BLOCK, out(mamma.number, failClosed))
        assertEquals(Verdict.BLOCK, incoming(mamma.number, failClosed))
        assertEquals(Verdict.ALLOW, out("112", failClosed))
    }

    @Test
    fun `unmanaged allows everything`() {
        assertEquals(Verdict.ALLOW, out("+4799999999", unmanaged))
        assertEquals(Verdict.ALLOW, incoming(null, unmanaged, presentationAllowed = false))
    }

    // Incoming

    @Test
    fun `withheld numbers are blocked when managed`() {
        assertEquals(Verdict.BLOCK, incoming(null))
        assertEquals(Verdict.BLOCK, incoming(mamma.number, presentationAllowed = false))
    }

    @Test
    fun `failed caller-ID verification is blocked even for a contact`() {
        assertEquals(Verdict.BLOCK, incoming(mamma.number, verificationFailed = true))
    }

    @Test
    fun `the callback window lets anyone call, withheld too`() {
        for (state in listOf(managed, disabled, failClosed)) {
            assertEquals(Verdict.ALLOW, incoming("+4799999999", state, window = true))
            assertEquals(Verdict.ALLOW, incoming(null, state, presentationAllowed = false, window = true))
        }
    }

    @Test
    fun `the call log is not read for an allowed contact`() {
        var asked = false
        decideIncoming(mamma.number, true, false, managed) { asked = true; false }
        assertFalse(asked)
        decideIncoming("+4799999999", true, false, managed) { asked = true; false }
        assertTrue(asked)
    }

    // Callback window (QA blocker 2)

    private val now = 10_000_000_000L
    private val minute = 60_000L

    private fun window(vararg calls: LoggedCall, recordedUntil: Long? = null, p: (String) -> Boolean? = platform) =
        callbackWindowUntil(now, calls.toList(), recordedUntil, p)

    @Test
    fun `a connected emergency call opens the window for an hour`() {
        val call = LoggedCall("112", startMs = now - 59 * minute, durationSec = 0)
        assertNull("no connection, no window", window(call))
        val connected = LoggedCall("112", startMs = now - 60 * minute, durationSec = 60)
        // Ended 59 minutes ago.
        assertEquals(now - 59 * minute + CALLBACK_WINDOW_MS, window(connected))
        val old = LoggedCall("112", startMs = now - 62 * minute, durationSec = 60)
        assertNull("ended 61 minutes ago", window(old))
    }

    @Test
    fun `fake emergency numbers never open the window`() {
        for (number in listOf("08", "000", "118", "911", "999", "1121234", "112#")) {
            assertNull(number, window(LoggedCall(number, now - minute, 30)))
        }
    }

    @Test
    fun `the static list never opens the window`() {
        assertNull(window(LoggedCall("112", now - minute, 30), p = platformThrows))
    }

    @Test
    fun `the InCallService record also opens it`() {
        assertEquals(now + 59 * minute, window(recordedUntil = now + 59 * minute))
        assertNull(window(recordedUntil = now - minute))
    }

    /** QA step 2 #4: the recorded window is checked by elapsed time and boot count too. */
    @Test
    fun `a clock change or reboot can't reopen the recorded window`() {
        val start = com.kidslauncher.mdm.server.WindowStart(untilWallMs = now + 60 * minute, elapsedStartMs = 1_000, bootCount = 5)
        fun open(wall: Long, elapsed: Long, boot: Int) =
            com.kidslauncher.mdm.server.timedWindowActive(start, wall, elapsed, boot, CALLBACK_WINDOW_MS)
        assertTrue(open(now + minute, 1_000 + minute, 5))
        // Two hours later, with the wall clock set back to just after the call: still closed.
        assertFalse(open(now + minute, 1_000 + 120 * minute, 5))
        // After a reboot: closed.
        assertFalse(open(now + minute, 1_000 + minute, 6))
    }

    @Test
    fun `call log entries outside their own hour don't open the window`() {
        // An old connected call and a newer unconnected one: closed.
        assertNull(window(LoggedCall("112", now - 3 * 60 * minute, 30), LoggedCall("112", now - minute, 0)))
    }

    // Unknown direction (QA step 2 #5)

    @Test
    fun `unknown-direction calls are kept only for emergency or contacts allowed either way`() {
        fun unknown(raw: String?, state: CallPolicyState = managed) = decideUnknownDirection(raw, state, emergency(raw))
        assertEquals(Verdict.ALLOW, unknown(bestemor.number)) // inbound only
        assertEquals(Verdict.ALLOW, unknown(mormor.number)) // outbound only
        assertEquals(Verdict.BLOCK, unknown("+4799999999"))
        assertEquals(Verdict.BLOCK, unknown(null))
        assertEquals(Verdict.BLOCK, unknown(mamma.number, disabled))
        assertEquals(Verdict.BLOCK, unknown(mamma.number, failClosed))
        assertEquals(Verdict.ALLOW, unknown("112", failClosed))
        assertEquals(Verdict.ALLOW, unknown("+4799999999", unmanaged))
    }

    // Dial target (QA step 2 #6)

    @Test
    fun `the stored number is dialled when the typed string differs`() {
        fun target(raw: String?, state: CallPolicyState = managed) = outgoingDialTarget(raw, state, emergency(raw))
        assertEquals("+4791234567", target("91234567"))
        assertEquals("+4791234567", target("091234567"))
        assertEquals("+4791234567", target("0047 91234567"))
        assertNull("already the stored form", target("+47 912 34 567"))
        assertNull(target("1881"))
        assertNull("emergency is never rewritten", target("112"))
        assertNull("not allowed: nothing to rewrite", target("+4799999999"))
        assertNull("inbound-only isn't dialled", target(bestemor.number.removePrefix("+47")))
        assertNull(target("91234567", unmanaged))
    }

    @Test
    fun `stranger calls are allowed at 59 minutes and blocked at 61`() {
        fun at(minutesAgo: Long) = decideIncoming("+4799999999", true, false, managed) {
            callbackWindowUntil(now, listOf(LoggedCall("112", now - minutesAgo * minute, 0 + 1)), null, platform) != null
        }
        assertEquals(Verdict.ALLOW, at(59))
        assertEquals(Verdict.BLOCK, at(61))
    }

    // Rules

    @Test
    fun `phone book and home buttons come from the flags in order`() {
        assertEquals(listOf(mamma, pappa, mormor, opplysning), rules.phoneBook)
        assertEquals(listOf(mamma), rules.homeContacts)
        assertEquals(mamma, rules.contactFor("912 34 567"))
        assertNull(rules.contactFor("*21#"))
    }

    @Test
    fun `an empty rules object denies everything`() {
        val empty = CallPolicyState.Managed(CallRules())
        assertEquals(Verdict.BLOCK, out(mamma.number, empty))
        assertEquals(Verdict.BLOCK, incoming(mamma.number, empty))
        assertEquals(Verdict.ALLOW, out("112", empty))
    }
}
