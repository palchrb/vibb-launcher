package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.RuleContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 17 (QA #12 with #1-#11): which calls ring over the lock, and the exemption's lifetime. */
class VoipCallPlanTest {
    private val element = "io.element.android.x"
    private val signal = "org.thoughtcrime.securesms"

    // ---- the gate (QA #8) ---------------------------------------------------------------------

    private fun contact(app: String) = RuleContact(name = "Pappa", number = "+4791234567", messageApp = app, messageAddress = "@pappa:vibb.me")

    @Test
    fun `managed calls on - the contacts' messaging apps, never the SMS app`() {
        val rules = CallRules(callsEnabled = true, smsEnabled = true, contacts = listOf(contact("element"), contact("sms")))
        assertEquals(setOf(element), voipCandidates(CallPolicyState.Managed(rules)))
    }

    @Test
    fun `calls off, a no-calls time rule (folded into the effective state) or unknown rules - none`() {
        val off = CallRules(callsEnabled = false, contacts = listOf(contact("element")))
        assertEquals(emptySet<String>(), voipCandidates(CallPolicyState.Managed(off)))
        assertEquals(emptySet<String>(), voipCandidates(CallPolicyState.UnknownFailClosed))
    }

    @Test
    fun `unmanaged calls - the known VoIP messengers (apps managed + PIN lock only)`() {
        val known = voipCandidates(CallPolicyState.Unmanaged)
        assertTrue(element in known && signal in known)
        assertTrue("never a self-managed game or WhatsApp", "com.whatsapp" !in known)
    }

    // ---- notifications -------------------------------------------------------------------------

    @Test
    fun `ringing, in call or FSI denied - from category, channel and flags only`() {
        assertEquals(VoipNoticeKind.RINGING, voipNoticeKind("call", "ringing", foregroundService = true, hasFullScreenIntent = true, fsiDenied = false))
        assertEquals(VoipNoticeKind.FSI_DENIED, voipNoticeKind("call", "ringing", false, hasFullScreenIntent = false, fsiDenied = true))
        assertEquals(VoipNoticeKind.IN_CALL, voipNoticeKind(null, ELEMENT_CALL_CHANNEL, foregroundService = true, hasFullScreenIntent = false, fsiDenied = false))
        assertEquals(VoipNoticeKind.IN_CALL, voipNoticeKind("call", "ongoing", foregroundService = true, hasFullScreenIntent = false, fsiDenied = false))
        assertEquals("the channel without a service isn't a call", VoipNoticeKind.NONE, voipNoticeKind(null, ELEMENT_CALL_CHANNEL, false, false, false))
        assertEquals("a message with a full-screen intent isn't a ring", VoipNoticeKind.NONE, voipNoticeKind("msg", "messages", false, true, false))
        assertEquals(VoipNoticeKind.NONE, voipNoticeKind("call", "missed", false, false, false))
    }

    // ---- the lifetime (QA #4/#5) ---------------------------------------------------------------

    private val boot = 7
    private val t0Wall = 1_800_000_000_000L
    private val t0 = 100_000L

    private fun at(
        elapsed: Long,
        ringing: Set<String> = emptySet(),
        inCall: Set<String> = emptySet(),
        audio: Boolean = false,
        seen: Boolean = true,
        unverifiedSince: Long = 0L,
        bootCount: Int = boot,
    ) = VoipInputs(ringing, inCall, seen, unverifiedSince, audio, t0Wall + (elapsed - t0), elapsed, bootCount)

    @Test
    fun `ring, the answer gap, the call, the end`() {
        val ring = voipExemption(null, at(t0, ringing = setOf(element)))
        assertEquals(VoipPhase.RINGING, ring.phase)
        assertEquals(element, ring.pinned)
        val record = ring.record!!
        assertEquals(element, record.packageName)
        // Answered in the app: the ring is gone before its call service is up - still exempt and pinned.
        val gap = voipExemption(record, at(t0 + 30_000))
        assertEquals(VoipPhase.IN_CALL, gap.phase)
        assertEquals(element, gap.pinned)
        assertEquals(t0 + 30_000, gap.record!!.ringEndedElapsedMs)
        val gapLater = voipExemption(gap.record, at(t0 + 30_000 + VOIP_GRACE_MS - 1))
        assertEquals(VoipPhase.IN_CALL, gapLater.phase)
        // The call: its service notification plus the audio mode.
        val call = voipExemption(gapLater.record, at(t0 + 60 * 60_000L, inCall = setOf(element), audio = true))
        assertEquals(VoipVerdict(gap.record, VoipPhase.IN_CALL, element), call)
        // Hung up: the service is gone - over at once (the grace is only after the ring).
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(call.record, at(t0 + 61 * 60_000L)))
    }

    @Test
    fun `declined or timed out - over after the grace`() {
        val ring = voipExemption(null, at(t0, ringing = setOf(element)))
        val ended = voipExemption(ring.record, at(t0 + 20_000))
        assertEquals(VoipPhase.IN_CALL, ended.phase)
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(ended.record, at(t0 + 20_000 + VOIP_GRACE_MS)))
    }

    @Test
    fun `the audio mode only ever narrows - any app can hold IN_COMMUNICATION (QA 5)`() {
        val ring = voipExemption(null, at(t0, ringing = setOf(element)))
        val ended = voipExemption(ring.record, at(t0 + 10_000)).record
        // Service up, audio normal after the grace: the lock comes back, the package stays pinned.
        val quiet = voipExemption(ended, at(t0 + 10_000 + VOIP_GRACE_MS, inCall = setOf(element), audio = false))
        assertEquals(VoipPhase.NONE, quiet.phase)
        assertEquals(element, quiet.pinned)
        // Audio alone never makes a call.
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(null, at(t0, audio = true)))
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(ended, at(t0 + 10_000 + VOIP_GRACE_MS, audio = true)))
    }

    @Test
    fun `the 3 h cap brings the lock back but never unpins a live call`() {
        val ring = voipExemption(null, at(t0, ringing = setOf(element)))
        val record = voipExemption(ring.record, at(t0 + 5_000)).record
        val capped = voipExemption(record, at(t0 + VOIP_CAP_MS, inCall = setOf(element), audio = true))
        assertEquals(VoipPhase.NONE, capped.phase)
        assertEquals(element, capped.pinned)
        val before = voipExemption(record, at(t0 + VOIP_CAP_MS - 1, inCall = setOf(element), audio = true))
        assertEquals(VoipPhase.IN_CALL, before.phase)
        // A wall clock set back can't stretch it: elapsed time counts too.
        val stretched = VoipInputs(emptySet(), setOf(element), true, 0L, true, t0Wall, t0 + VOIP_CAP_MS, boot)
        assertEquals(VoipPhase.NONE, voipExemption(record, stretched).phase)
    }

    @Test
    fun `a reboot ends it`() {
        val record = voipExemption(null, at(t0, ringing = setOf(element))).record
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(record, at(5_000L, inCall = setOf(element), audio = true, bootCount = boot + 1)))
        assertEquals("elapsed time going backwards is another boot", VoipVerdict(null, VoipPhase.NONE, null),
            voipExemption(record, at(t0 - 1, inCall = setOf(element), audio = true)))
    }

    @Test
    fun `process start - the stored record stays pinned until the listener reports (QA 4)`() {
        val stored = voipExemption(null, at(t0, ringing = setOf(element))).record!!.copy(ringEndedElapsedMs = null)
        val start = t0 + 600_000L
        val early = voipExemption(stored, at(start + 1_000, seen = false, unverifiedSince = start))
        assertEquals(VoipVerdict(stored, VoipPhase.IN_CALL, element), early)
        // No listener after the grace: only while the audio mode says call.
        assertEquals(VoipPhase.IN_CALL, voipExemption(stored, at(start + VOIP_GRACE_MS, seen = false, unverifiedSince = start, audio = true)).phase)
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(stored, at(start + VOIP_GRACE_MS, seen = false, unverifiedSince = start)))
        // The listener reports the call service: as before.
        assertEquals(VoipPhase.IN_CALL, voipExemption(stored, at(start + 2_000, inCall = setOf(element), audio = true)).phase)
    }

    @Test
    fun `one call at a time - a second app's ring during a call is ignored, after it a ring starts anew`() {
        val ring = voipExemption(null, at(t0, ringing = setOf(element)))
        val call = voipExemption(ring.record, at(t0 + 5_000, inCall = setOf(element), audio = true))
        val second = voipExemption(call.record, at(t0 + 60_000, ringing = setOf(signal), inCall = setOf(element), audio = true))
        assertEquals(VoipPhase.IN_CALL, second.phase)
        assertEquals(element, second.pinned)
        val after = voipExemption(second.record, at(t0 + 120_000, ringing = setOf(signal)))
        assertEquals(VoipPhase.RINGING, after.phase)
        assertEquals(signal, after.pinned)
        assertEquals(t0 + 120_000, after.record!!.start.elapsedStartMs)
        // The same app ringing again keeps its record (and its cap).
        val again = voipExemption(call.record, at(t0 + 90_000, ringing = setOf(element), inCall = setOf(element)))
        assertEquals(VoipPhase.RINGING, again.phase)
        assertEquals(call.record!!.start, again.record!!.start)
        assertNull(again.record!!.ringEndedElapsedMs)
    }

    // ---- the system dialer's call (QA #7) and the ring (QA #1) ------------------------------------

    @Test
    fun `only managed calls hold the lock open - a self-managed VoIP call never does`() {
        assertEquals(true, managedCallActive(inManagedCall = true, audioMode = 0))
        assertEquals("WhatsApp/Signal self-managed: isInCall would say yes", false, managedCallActive(false, AUDIO_MODE_IN_COMMUNICATION))
        assertEquals("no READ_PHONE_STATE: a telephony call's audio mode", true, managedCallActive(null, AUDIO_MODE_IN_CALL))
        assertEquals(false, managedCallActive(null, AUDIO_MODE_IN_COMMUNICATION))
        assertEquals(false, managedCallActive(null, 0))
    }

    @Test
    fun `the lock rings only while LOCKED, unsilenced, and never during another call, the emergency flow or an alarm (qa-16-17 1, 5)`() {
        assertTrue(voipRingWanted(ringing = true, locked = true, silenced = false, otherCall = false, emergencyFlow = false, alarmRinging = false))
        assertFalse("a phone call (emergency included) or our call", voipRingWanted(true, true, false, otherCall = true, emergencyFlow = false, alarmRinging = false))
        assertFalse("the emergency dialer flow", voipRingWanted(true, true, false, false, emergencyFlow = true, alarmRinging = false))
        assertFalse("a ringing alarm", voipRingWanted(true, true, false, false, false, alarmRinging = true))
        assertFalse("silenced for this ring", voipRingWanted(true, true, silenced = true, otherCall = false, emergencyFlow = false, alarmRinging = false))
        assertFalse("unlocked: the app's own notification rings", voipRingWanted(true, locked = false, silenced = false, otherCall = false, emergencyFlow = false, alarmRinging = false))
        assertFalse(voipRingWanted(ringing = false, locked = true, silenced = false, otherCall = false, emergencyFlow = false, alarmRinging = false))
        // The power button silences a ringing lock; a ring that began unlocked starts at that screen-off.
        assertTrue(screenOffSilencesRing(lockedBefore = true, ringing = true))
        assertFalse(screenOffSilencesRing(lockedBefore = false, ringing = true))
        assertFalse(screenOffSilencesRing(lockedBefore = true, ringing = false))
    }

    @Test
    fun `the lock's ring follows the ringer mode and Do Not Disturb`() {
        assertEquals(RingPlan(sound = true, vibrate = true), ringPlan(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_ALL, false))
        assertEquals(RingPlan(sound = false, vibrate = true), ringPlan(RINGER_MODE_VIBRATE, INTERRUPTION_FILTER_ALL, false))
        assertEquals(RingPlan(sound = false, vibrate = false), ringPlan(RINGER_MODE_SILENT, INTERRUPTION_FILTER_ALL, false))
        assertEquals(RingPlan(sound = false, vibrate = false), ringPlan(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_NONE, true))
        assertEquals(RingPlan(sound = false, vibrate = false), ringPlan(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_ALARMS, true))
        assertEquals("DND with calls from anyone", RingPlan(sound = true, vibrate = true), ringPlan(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_PRIORITY, true))
        assertEquals("DND with contacts only: the caller is no contact", RingPlan(sound = false, vibrate = false),
            ringPlan(RINGER_MODE_NORMAL, INTERRUPTION_FILTER_PRIORITY, false))
        assertEquals("unknown filter: the ringer mode", RingPlan(sound = true, vibrate = true), ringPlan(RINGER_MODE_NORMAL, 0, false))
    }
}
