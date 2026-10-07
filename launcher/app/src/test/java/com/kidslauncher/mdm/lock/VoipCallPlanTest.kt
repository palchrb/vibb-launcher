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
        // qa-16-17 #8: an incoming CallStyle posted by a call service without an FSI is a ring, never
        // "the call" (its content intent may answer it).
        assertEquals(VoipNoticeKind.NONE, voipNoticeKind("call", "ringing", foregroundService = true, hasFullScreenIntent = false, fsiDenied = false, incomingCallStyle = true))
        assertEquals(VoipNoticeKind.NONE, voipNoticeKind(null, ELEMENT_CALL_CHANNEL, true, false, false, incomingCallStyle = true))
        assertEquals("with an FSI it still rings", VoipNoticeKind.RINGING, voipNoticeKind("call", "ringing", true, true, false, incomingCallStyle = true))
        assertEquals(VoipNoticeKind.FSI_DENIED, voipNoticeKind("call", "ringing", true, false, fsiDenied = true, incomingCallStyle = true))
        assertEquals(1, CALL_TYPE_INCOMING)
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
        assertNull("the ring is over", call.record!!.ringStartedElapsedMs)
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
        val start = t0 + 600_000L
        // As VoipCalls restores it: the ring ended long ago (stored), or at the latest at this restore.
        val stored = voipExemption(null, at(t0, ringing = setOf(element))).record!!.copy(ringEndedElapsedMs = t0 + 10_000, ringStartedElapsedMs = null)
        val early = voipExemption(stored, at(start + 1_000, seen = false, unverifiedSince = start))
        assertEquals(VoipVerdict(stored, VoipPhase.IN_CALL, element), early)
        // No listener after the grace: over - the audio mode alone never holds it (qa-16-17 #7).
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(stored, at(start + VOIP_GRACE_MS, seen = false, unverifiedSince = start, audio = true)))
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(stored, at(start + VOIP_GRACE_MS, seen = false, unverifiedSince = start)))
        // The listener reports the call service: as before.
        assertEquals(VoipPhase.IN_CALL, voipExemption(stored, at(start + 2_000, inCall = setOf(element), audio = true)).phase)
        // The listener reports nothing: no fresh grace from the stored end (qa-16-17 #7).
        assertEquals(VoipVerdict(null, VoipPhase.NONE, null), voipExemption(stored, at(start + 2_000)))
    }

    @Test
    fun `a ring is the ring screen for at most 2 min, then the lock comes back (qa-16-17 4)`() {
        val ring = voipExemption(null, at(t0, ringing = setOf(element)))
        assertEquals(t0, ring.record!!.ringStartedElapsedMs)
        val still = voipExemption(ring.record, at(t0 + VOIP_RING_LIMIT_MS - 1, ringing = setOf(element)))
        assertEquals(VoipPhase.RINGING, still.phase)
        val stuck = voipExemption(still.record, at(t0 + VOIP_RING_LIMIT_MS, ringing = setOf(element)))
        assertEquals(VoipPhase.NONE, stuck.phase)
        assertEquals("the record (and its pin) stay while the notification does", element, stuck.pinned)
        assertEquals(t0, stuck.record!!.ringStartedElapsedMs)
        // A re-ring after the ring ended is a new ring with its own limit, within the record's cap.
        val ended = voipExemption(still.record, at(t0 + 60_000))
        val again = voipExemption(ended.record, at(t0 + 70_000, ringing = setOf(element)))
        assertEquals(VoipPhase.RINGING, again.phase)
        assertEquals(t0 + 70_000, again.record!!.ringStartedElapsedMs)
        // The 3 h cap bounds rings too.
        val late = VoipRecord(element, ring.record!!.start, ringStartedElapsedMs = t0 + VOIP_CAP_MS - 1_000)
        assertEquals(VoipPhase.NONE, voipExemption(late, at(t0 + VOIP_CAP_MS, ringing = setOf(element))).phase)
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
        assertFalse("17b: the app's call service is up - answered", voipRingWanted(true, true, false, false, false, false, callService = true))
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

    // ---- 17b: the app's own ring screen first, the card as the fallback -------------------------

    @Test
    fun `17b - a ring whose full-screen intent was denied rings when it is an answerable incoming CallStyle`() {
        assertTrue(voipRings(VoipNoticeKind.RINGING, hasAnswer = false, incoming = false))
        assertTrue(voipRings(VoipNoticeKind.FSI_DENIED, hasAnswer = true, incoming = true))
        assertFalse("nothing to answer it with: reported only", voipRings(VoipNoticeKind.FSI_DENIED, hasAnswer = false, incoming = true))
        assertFalse("not an incoming CallStyle (qa-17b-code 2)", voipRings(VoipNoticeKind.FSI_DENIED, hasAnswer = true, incoming = false))
        assertFalse(voipRings(VoipNoticeKind.IN_CALL, hasAnswer = true, incoming = true))
        assertFalse(voipRings(VoipNoticeKind.NONE, hasAnswer = true, incoming = true))
    }

    private fun cs(intent: String) = CallAction(intent, callStyle = true)
    private fun plain(intent: String) = CallAction(intent, callStyle = false)

    @Test
    fun `17b - the answer intent - EXTRA_ANSWER_INTENT, else the one CallStyle action that isn't the decline`() {
        // Element X (androidx core 1.17): extras carry both, actions = [decline, answer] marked by CallStyle,
        // and the content intent *is* the answer intent.
        assertEquals("answer", pickAnswerIntent("answer", "decline", listOf(cs("decline"), cs("answer")), incoming = true))
        assertEquals("no extra: the marked action", "answer", pickAnswerIntent(null, "decline", listOf(cs("decline"), cs("answer")), incoming = true))
        assertEquals("the app's own actions don't count", "answer",
            pickAnswerIntent(null, "decline", listOf(plain("mute"), cs("decline"), cs("answer"), plain("reply")), incoming = true))
        assertEquals("an extra equal to the decline is no answer", "answer",
            pickAnswerIntent("decline", "decline", listOf(cs("decline"), cs("answer")), incoming = true))
        assertEquals("the same intent twice is one", "answer", pickAnswerIntent(null, "decline", listOf(cs("answer"), cs("answer")), incoming = true))
        assertEquals("the extra needs no call type", "answer", pickAnswerIntent("answer", null, emptyList(), incoming = false))
    }

    @Test
    fun `17b - never a guess - ambiguous, decline-only, unmarked actions or an ongoing call's hang-up give none (Answer sends the FSI)`() {
        assertNull("two marked candidates", pickAnswerIntent(null, "decline", listOf(cs("a"), cs("b")), incoming = true))
        assertNull("only the decline", pickAnswerIntent(null, "decline", listOf(cs("decline")), incoming = true))
        // An unmarked action - e.g. one with the content intent - is never picked: no title, no semantic.
        assertNull(pickAnswerIntent(null, "decline", listOf(plain("content"), cs("decline")), incoming = true))
        assertNull(pickAnswerIntent<String>(null, null, emptyList(), incoming = true))
        assertNull("an action without an intent", pickAnswerIntent(null, "decline", listOf(CallAction<String>(null, true), cs("decline")), incoming = true))
        // qa-17b-code #2: an ongoing CallStyle (no decline) has only its hang-up marked.
        assertNull("hang-up, incoming claimed", pickAnswerIntent(null, null, listOf(cs("hangUp")), incoming = true))
        assertNull("hang-up of an ongoing call", pickAnswerIntent(null, null, listOf(cs("hangUp")), incoming = false))
        assertNull("not incoming: no action fallback", pickAnswerIntent(null, "decline", listOf(cs("decline"), cs("answer")), incoming = false))
        assertEquals("key_action_priority", KEY_CALL_STYLE_ACTION)
    }

    @Test
    fun `qa-17b-code 1 - Svar silences only after the answer action, a refused start un-silences`() {
        assertTrue(answerSilences(VoipAnswerSent.ANSWER_ACTION))
        assertFalse("the app's ring screen still rings", answerSilences(VoipAnswerSent.RING_SCREEN))
        assertFalse(answerSilences(VoipAnswerSent.NOTHING))
        assertNull("rings again", silenceAfterRefusedAnswer(silencedRing = 7L, answeredRing = 7L, silencedBeforeAnswer = null))
        assertEquals("a power-button silence of the same ring stays", 7L,
            silenceAfterRefusedAnswer(silencedRing = 7L, answeredRing = 7L, silencedBeforeAnswer = 7L))
        assertEquals("a new ring since: untouched", 9L, silenceAfterRefusedAnswer(silencedRing = 9L, answeredRing = 7L, silencedBeforeAnswer = null))
        assertEquals("no Svar silence on record: untouched", 7L, silenceAfterRefusedAnswer(silencedRing = 7L, answeredRing = null, silencedBeforeAnswer = null))
    }

    @Test
    fun `qa-17b-code 5 - which pauses count as the app's screen having come up`() {
        val now = 100_000L
        assertFalse("our wake activity going over the settling lock",
            pauseIsTry(sendPending = true, wakeCovered = false, wakeAskedAtElapsedMs = now - 50, wakeGone = false, nowElapsedMs = now))
        assertTrue("SystemUI's launch (or power) while the send settles - the wake is long gone",
            pauseIsTry(sendPending = true, wakeCovered = false, wakeAskedAtElapsedMs = now - 900, wakeGone = true, nowElapsedMs = now))
        assertTrue("no wake asked for", pauseIsTry(true, false, null, wakeGone = true, nowElapsedMs = now))
        assertTrue("a wake that never came, past the window",
            pauseIsTry(true, false, now - VOIP_WAKE_WINDOW_MS, wakeGone = false, nowElapsedMs = now))
        assertTrue("the wake was covered before it finished itself", pauseIsTry(false, wakeCovered = true, wakeAskedAtElapsedMs = now - 50, wakeGone = false, nowElapsedMs = now))
        assertFalse("no send settling, the wake not covered", pauseIsTry(false, false, null, true, now))
    }

    @Test
    fun `qa-17b-code 5 - the try after a pause`() {
        val now = 5_000L
        val sent = VoipFsiTry(1L, 4_000L, sent = true)
        assertEquals("our try came up", sent.copy(left = true), voipTryAfterPause(sent, 1L, pauseIsTry = false, nowElapsedMs = now))
        assertEquals(sent.copy(left = true), voipTryAfterPause(sent.copy(left = true), 1L, pauseIsTry = true, nowElapsedMs = now))
        assertEquals("came up by itself: a try, nothing sent", VoipFsiTry(1L, now, sent = false, left = true),
            voipTryAfterPause(null, 1L, pauseIsTry = true, nowElapsedMs = now))
        assertEquals("an old ring's try is replaced", VoipFsiTry(2L, now, sent = false, left = true),
            voipTryAfterPause(sent, 2L, pauseIsTry = true, nowElapsedMs = now))
        assertNull("the wake's own pause: still no try", voipTryAfterPause(null, 1L, pauseIsTry = false, nowElapsedMs = now))
        assertEquals("no ring", sent, voipTryAfterPause(sent, null, pauseIsTry = true, nowElapsedMs = now))
        // Then: never our send again for that ring, the card on the next resume.
        val byItself = voipTryAfterPause(null, 1L, pauseIsTry = true, nowElapsedMs = now)
        assertFalse(voipFsiDue(1L, locked = true, dismissed = false, otherScreen = false, hasFullScreen = true, last = byItself))
        assertEquals(VoipRingUi.CARD, voipRingUi(1L, true, false, false, true, byItself, now))
    }

    private fun due(ringId: Long? = 1L, locked: Boolean = true, dismissed: Boolean = false, other: Boolean = false, fsi: Boolean = true, last: VoipFsiTry? = null) =
        voipFsiDue(ringId, locked, dismissed, other, fsi, last)

    @Test
    fun `17b - the lock sends the app's ring screen once per ring, never over another call, the emergency flow or an alarm`() {
        assertTrue(due())
        assertFalse("no ring", due(ringId = null))
        assertFalse("unlocked: the app's own notification", due(locked = false))
        assertFalse("after Avvis", due(dismissed = true))
        assertFalse("a phone call, emergency, the emergency flow or an alarm is on (QA 5)", due(other = true))
        assertFalse("no full-screen intent: the card", due(fsi = false))
        assertFalse("tried for this ring - never a second time", due(last = VoipFsiTry(1L, 0L, sent = true)))
        assertFalse(due(last = VoipFsiTry(1L, 0L, sent = false)))
        assertTrue("a new ring tries again", due(ringId = 2L, last = VoipFsiTry(1L, 0L, sent = true, left = true)))
    }

    private fun ui(now: Long, last: VoipFsiTry?, ringId: Long? = 1L, locked: Boolean = true, dismissed: Boolean = false, other: Boolean = false, fsi: Boolean = true) =
        voipRingUi(ringId, locked, dismissed, other, fsi, last, now)

    @Test
    fun `17b - the card only as the fallback, after the 1,5 s check`() {
        val sentAt = 50_000L
        val sent = VoipFsiTry(1L, sentAt, sent = true)
        assertEquals("before the try: the plain lock", VoipRingUi.WAIT, ui(sentAt, last = null))
        assertEquals("an old ring's try doesn't count", VoipRingUi.WAIT, ui(sentAt, last = VoipFsiTry(0L, 0L, true, left = true)))
        assertEquals("within 1.5 s: no card flash under the app's screen", VoipRingUi.WAIT, ui(sentAt + VOIP_FSI_CHECK_MS - 1, sent))
        assertEquals("nothing came up (a refused start is silent)", VoipRingUi.CARD, ui(sentAt + VOIP_FSI_CHECK_MS, sent))
        assertEquals("the send threw", VoipRingUi.CARD, ui(sentAt, VoipFsiTry(1L, sentAt, sent = false)))
        assertEquals("the lock is back during the same ring (Back, power)", VoipRingUi.CARD, ui(sentAt + 10, sent.copy(left = true)))
        assertEquals("no full-screen intent (denied): the card at once", VoipRingUi.CARD, ui(sentAt, last = null, fsi = false))
        for ((why, view) in listOf(
            "no ring" to ui(sentAt, sent, ringId = null),
            "unlocked" to ui(sentAt + VOIP_FSI_CHECK_MS, sent, locked = false),
            "after Avvis" to ui(sentAt + VOIP_FSI_CHECK_MS, sent, dismissed = true),
            "another call, the emergency flow or an alarm" to ui(sentAt + VOIP_FSI_CHECK_MS, sent, other = true),
            "denied FSI over a call" to ui(sentAt, last = null, fsi = false, other = true),
        )) {
            assertEquals(why, VoipRingUi.NONE, view)
        }
    }

    @Test
    fun `17b - the came-up check's timing`() {
        assertFalse(voipStartOverdue(sentAtElapsedMs = 1_000L, lockLeft = false, nowElapsedMs = 1_000L))
        assertFalse(voipStartOverdue(1_000L, false, 1_000L + VOIP_FSI_CHECK_MS - 1))
        assertTrue(voipStartOverdue(1_000L, false, 1_000L + VOIP_FSI_CHECK_MS))
        assertFalse("the lock left the front: it came up", voipStartOverdue(1_000L, lockLeft = true, nowElapsedMs = 60_000L))
        assertTrue("a clock going backwards never hides the card", voipStartOverdue(1_000L, false, 999L))
        assertEquals(1_500L, VOIP_FSI_CHECK_MS)
        assertTrue("the settle is well inside the check", VOIP_FSI_SETTLE_MS in 1 until VOIP_FSI_CHECK_MS)
    }
}
