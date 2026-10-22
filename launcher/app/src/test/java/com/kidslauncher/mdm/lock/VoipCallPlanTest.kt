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

    private fun src(path: String) =
        listOf(java.io.File("src/main/java/com/kidslauncher/mdm/$path"), java.io.File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }.readText()

    private val whatsapp = "com.whatsapp"
    private val allowed = setOf(element, whatsapp, "com.microsoft.teams")

    @Test
    fun `managed calls on - every allowed app (design r7)`() {
        val rules = CallRules(callsEnabled = true, smsEnabled = true, contacts = listOf(contact("element"), contact("sms")))
        assertEquals(allowed, voipCandidates(CallPolicyState.Managed(rules), allowed, policyKnown = true))
    }

    @Test
    fun `fallbacks - no policy yet or apps unmanaged give the pre-r7 sets, never wider`() {
        val rules = CallRules(callsEnabled = true, smsEnabled = true, contacts = listOf(contact("element"), contact("sms")))
        // Managed calls: the contacts' messaging apps, never the SMS app.
        assertEquals(setOf(element), voipCandidates(CallPolicyState.Managed(rules), allowed, policyKnown = false))
        assertEquals(setOf(element), voipCandidates(CallPolicyState.Managed(rules), null, policyKnown = true))
        // Unmanaged calls: the known messengers.
        val known = voipCandidates(CallPolicyState.Unmanaged, null, policyKnown = true)
        assertEquals(KNOWN_VOIP_PACKAGES, known)
        assertTrue(element in known && signal in known)
        assertTrue("never a self-managed game or WhatsApp", whatsapp !in known)
        assertEquals(KNOWN_VOIP_PACKAGES, voipCandidates(CallPolicyState.Unmanaged, allowed, policyKnown = false))
    }

    @Test
    fun `unmanaged calls with an app list - the allowed apps`() {
        assertEquals(allowed, voipCandidates(CallPolicyState.Unmanaged, allowed, policyKnown = true))
    }

    @Test
    fun `calls off, a no-calls time rule (folded into the effective state) or unknown rules - none, whatever is allowed`() {
        val off = CallRules(callsEnabled = false, contacts = listOf(contact("element")))
        for (known in listOf(true, false)) {
            for (set in listOf(allowed, null)) {
                assertEquals(emptySet<String>(), voipCandidates(CallPolicyState.Managed(off), set, known))
                assertEquals(emptySet<String>(), voipCandidates(CallPolicyState.UnknownFailClosed, set, known))
            }
        }
    }

    @Test
    fun `the allowed set - the effective allowlist, messaging and usable apps, minus the exclusions`() {
        val excluded = setOf("com.google.android.apps.messaging", "com.android.dialer", "com.android.server.telecom", "me.vibb.launcher")
        val set = voipAllowedApps(
            allowlist = listOf(whatsapp, "com.relock.blocked", "com.google.android.apps.messaging", "com.android.dialer", "me.vibb.launcher", whatsapp),
            relockBlocked = setOf("com.relock.blocked"),
            messagingApps = setOf(signal, "com.google.android.apps.messaging"),
            lockUsableApps = setOf("org.fossify.clock", "com.android.server.telecom"),
            excluded = excluded,
        )
        assertEquals(setOf(whatsapp, signal, "org.fossify.clock"), set)
        assertNull("apps unmanaged", voipAllowedApps(null, emptySet(), setOf(signal), emptySet(), excluded))
    }

    @Test
    fun `the exclusion set - SMS apps, the essentials and our own family`() {
        val essential = setOf("com.android.dialer", "com.google.android.deskclock", "com.google.android.inputmethod.latin")
        val own = setOf("me.vibb.launcher", "me.vibb.launcher.debug")
        val excluded = com.kidslauncher.mdm.badges.voipExcluded("org.example.sms", essential, own)
        for (pkg in listOf(
            "org.example.sms", "com.google.android.apps.messaging", "com.android.messaging", "com.android.stk",
            "com.android.phone", "com.android.server.telecom", "com.android.systemui", "android",
            "com.android.dialer", "com.google.android.deskclock", "com.google.android.inputmethod.latin",
            "me.vibb.launcher", "me.vibb.launcher.debug",
        )) {
            assertTrue(pkg, pkg in excluded)
        }
        assertFalse(whatsapp in excluded)
        assertFalse(element in excluded)
    }

    @Test
    fun `no ring is counted - VoipCallPlan holds no per-hour budget`() {
        val source = src("lock/VoipCallPlan.kt")
        for (banned in listOf("PER_HOUR", "perHour", "RINGS_PER", "budget", "limited")) {
            assertFalse(banned, source.contains(banned))
        }
    }

    // ---- voip_rang (design r7 §1.1.3) ---------------------------------------------------------

    @Test
    fun `a ring is recorded once, only while locked`() {
        assertFalse("unlocked", voipRangDue(ringId = 5L, locked = false, recordedRingId = null))
        assertTrue("locked", voipRangDue(5L, locked = true, recordedRingId = null))
        assertFalse("already recorded", voipRangDue(5L, locked = true, recordedRingId = 5L))
        assertTrue("the phone locked during an unlocked ring", voipRangDue(5L, locked = true, recordedRingId = 3L))
        assertFalse("no ring (a non-candidate never rings)", voipRangDue(null, locked = true, recordedRingId = null))
    }

    @Test
    fun `voip_rang keeps 7 days, 8 packages, newest first, valid names`() {
        val day = 24 * 60 * 60_000L
        val now = 100 * day
        val stored = (0 until 10).associate { "org.example.app$it" to now - it * 60_000L } +
            mapOf("org.example.old" to now - 7 * day, "bad name" to now, "org.example.future" to now + 1)
        val entries = voipRangAfter(stored, whatsapp, now)
        assertEquals(VOIP_RANG_MAX, entries.size)
        assertEquals(whatsapp, entries[0].packageName)
        assertEquals(entries.sortedByDescending { it.lastAtMs }, entries)
        assertTrue(entries.none { it.packageName == "org.example.old" || it.packageName == "bad name" || it.packageName == "org.example.future" })
        // The same package again moves to the front, once.
        val again = voipRangAfter(entries.associate { it.packageName to it.lastAtMs }, "org.example.app7", now + 1)
        assertEquals("org.example.app7", again[0].packageName)
        assertEquals(1, again.count { it.packageName == "org.example.app7" })
        // Below the cap only the age rule drops: exactly 7 days old goes, a minute younger stays
        // (qa-l7-a-code 2, mutation e).
        val few = voipRangEntries(mapOf("org.example.edge" to now - 7 * day, "org.example.young" to now - 7 * day + 60_000L), now)
        assertEquals(listOf("org.example.young"), few.map { it.packageName })
        // The prefs' strings round trip, and junk is dropped.
        assertEquals(entries.associate { it.packageName to it.lastAtMs }, decodeVoipRang(encodeVoipRang(entries)))
        assertEquals(mapOf("a.b" to 1L), decodeVoipRang(setOf("a.b=1", "nothing", "=5", "c.d=x")))
    }

    @Test
    fun `the glue - eligible reads the pass's set, every ringer sync notes the ring with the lock mode, the report sends it`() {
        val calls = src("lock/VoipCalls.kt")
        assertTrue(calls.contains("NotificationRuleRuntime.voipAllowed"))
        assertTrue(calls.contains("policyKnown = NotificationRuleRuntime.policy != null"))
        assertFalse("no binder call or raw allowlist in eligible", calls.contains("defaultSmsPackage") || calls.contains(".allowlist"))
        val runtime = src("lock/PinLockRuntime.kt")
        val sync = runtime.substringAfter("fun syncVoipRinger(").substringBefore("\n    }\n")
        assertTrue(sync.contains("VoipCalls.noteRingWhileLocked(app, locked = mode == LockMode.LOCKED)"))
        assertTrue(runtime.contains("voipRang = VoipCalls.rangReport(app)"))
        val rule = src("badges/NotificationRuleRuntime.kt")
        assertTrue("the effective allowlist", rule.contains("relockBlocked = WindowRelock.blockedPackages(context)"))
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

    // ---- design 59b §4: the platform's ring as Android's face ----------------------------------------------------

    @Test
    fun `59b - as the face with Android locked a ring with its full-screen intent gets no lock-up or wake of ours`() {
        assertTrue(voipPlatformRing(face = true, androidLocked = true, hasFullScreen = true))
        assertFalse("FSI_DENIED: the card at once, as before", voipPlatformRing(true, true, hasFullScreen = false))
        assertFalse("Android open", voipPlatformRing(true, androidLocked = false, hasFullScreen = true))
        assertFalse("own mode", voipPlatformRing(face = false, androidLocked = true, hasFullScreen = true))
        assertEquals(LockStep(LockMode.LOCKED), step(LockMode.LOCKED, LockEvent.VoipRinging(platformRing = true)))
        assertEquals("another call still wins", LockStep(LockMode.LOCKED, recheck = true), step(LockMode.LOCKED, LockEvent.VoipRinging(ourCall = true, platformRing = true)))
        assertEquals("own mode unchanged", LockStep(LockMode.LOCKED, showLock = true, wake = true), step(LockMode.LOCKED, LockEvent.VoipRinging()))
        // Never the resumed lock's 17b send for the platform's ring; own mode's due as before.
        assertFalse(voipFsiDue(5L, locked = true, dismissed = false, otherScreen = false, hasFullScreen = true, last = null, platformRing = true))
        assertTrue(voipFsiDue(5L, locked = true, dismissed = false, otherScreen = false, hasFullScreen = true, last = null))
    }

    private fun grace(
        now: Long,
        last: VoipFsiTry? = null,
        resumedSinceRing: Boolean = false,
        interactive: Boolean = true,
        androidLocked: Boolean = true,
        otherScreen: Boolean = false,
        hasFullScreen: Boolean = true,
        locked: Boolean = true,
    ) = voipRingStep(
        1_000L, locked, dismissed = false, otherScreen = otherScreen, hasFullScreen = hasFullScreen, last = last,
        resumedSinceRing = resumedSinceRing, interactive = interactive, androidLocked = androidLocked, nowElapsedMs = now,
    )

    @Test
    fun `59b - the grace always ends in this ring's try - the one heads-up send, the platform's turn, or the dark wake`() {
        assertEquals(VoipGrace(VoipGraceAct.WAIT, 2_000L), grace(now = 2_000L))
        // The heads-up case: in front since the ring's first sight, the screen on, Android locked - the one send.
        assertEquals(VoipGrace(VoipGraceAct.SEND), grace(now = 4_000L, resumedSinceRing = true))
        // The platform had its turn: its screen came (the glance paused), or Android isn't locked.
        assertEquals(VoipGrace(VoipGraceAct.PLATFORM), grace(now = 4_000L, resumedSinceRing = false))
        assertEquals(VoipGrace(VoipGraceAct.PLATFORM), grace(now = 4_000L, resumedSinceRing = true, androidLocked = false))
        // Still dark (DND): the wake and the card, never a send.
        assertEquals(VoipGrace(VoipGraceAct.WAKE), grace(now = 4_000L, resumedSinceRing = true, interactive = false))
        // Another call, the emergency flow or an alarm: look again later.
        assertEquals(VoipGraceAct.WAIT, grace(now = 4_000L, otherScreen = true).act)
        // Done: this ring's try written, unlocked, no full-screen intent (the card at once), no ring.
        assertEquals(VoipGraceAct.NONE, grace(now = 4_000L, last = VoipFsiTry(1_000L, 4_000L, sent = false)).act)
        assertEquals(VoipGraceAct.NONE, grace(now = 4_000L, locked = false).act)
        assertEquals(VoipGraceAct.NONE, grace(now = 4_000L, hasFullScreen = false).act)
        assertEquals(VoipGraceAct.NONE, voipRingStep(null, true, false, false, true, null, true, true, true, 4_000L).act)
        // What the grace writes: sent = false is CARD at once and never a later send.
        val platformTry = VoipFsiTry(1_000L, 4_000L, sent = false)
        assertEquals(VoipRingUi.CARD, voipRingUi(1_000L, true, false, false, true, platformTry, 4_001L))
        assertFalse(voipFsiDue(1_000L, true, false, false, true, platformTry))
        assertEquals("during the grace the plain glance", VoipRingUi.WAIT, voipRingUi(1_000L, true, false, false, true, null, 2_000L))
    }

    @Test
    fun `59b - the grace's timer from the ringing edge, dropped with the ring, and the reopen only in a call`() {
        val runtime = src("lock/PinLockRuntime.kt")
        val phase = runtime.substringAfter("fun onVoipPhase(").substringBefore("\n    }\n")
        assertTrue(phase.contains("if (after != VoipPhase.RINGING) handler.removeCallbacks(voipGrace)"))
        assertTrue(phase.contains("val platform = voipPlatformRing(AndroidLock.faceOn, AndroidLock.refrontHeld(app), VoipCalls.ringHasFullScreen)"))
        assertTrue(phase.contains("LockEvent.VoipRinging(ours, !ours && telecom, emergency, alarm, platformRing = platform)"))
        assertTrue(phase.contains("if (platform) handler.postDelayed(voipGrace, VOIP_PLATFORM_GRACE_MS)"))
        assertTrue(runtime.contains("platformRing = ringId == voipPlatformRingId,"))
        val grace = runtime.substringAfter("private val voipGrace: Runnable = Runnable {").substringBefore("\n    }\n")
        assertTrue(grace.contains("VoipGraceAct.WAIT -> handler.postDelayed(voipGrace, grace.waitMs)"))
        assertTrue(grace.contains("resumedSinceRing = lockResumed && (lockPausedAt ?: Long.MIN_VALUE) < ringId,"))
        assertTrue(grace.contains("show(app, wake = WakeKind.VOIP)"))
        // Q59b-9: as the face the reopen needs IN_COMMUNICATION; own mode always.
        assertTrue(voipReopenDue(face = false, audioInCommunication = false))
        assertTrue(voipReopenDue(face = true, audioInCommunication = true))
        assertFalse(voipReopenDue(face = true, audioInCommunication = false))
        assertTrue(runtime.contains("if (!voipReopenDue(AndroidLock.faceOn, VoipCalls.inCommunication(it))) {"))
    }
}
