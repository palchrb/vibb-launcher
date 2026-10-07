package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.MessagePackages
import com.kidslauncher.mdm.calls.messagingAppPackages
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.timedWindowActive

/*
 * VoIP calls over the PIN lock (design 17, QA #12 with #1-#11 and the decisions): Element X (and
 * the other contacts' messaging apps) ring without Telecom, so our screening never sees them. While
 * an allowed app's ringing CALL notification exists the lock wakes itself, rings and shows an
 * "Answer / Decline" card; during the call (the app's call foreground-service notification) the lock
 * steps aside like for a system-dialer call. Pure, tested in VoipCallPlanTest.
 */

/** What the lock sees of a VoIP call: [RINGING] (the card), [IN_CALL] (stepping aside), or nothing. */
enum class VoipPhase { NONE, RINGING, IN_CALL }

/** Element X's call foreground service channel (`CallForegroundService`). */
const val ELEMENT_CALL_CHANNEL = "call_foreground_service_channel"

/** After the ring ends: the answered call's foreground service comes up within this (QA #4). */
const val VOIP_GRACE_MS = 15_000L

/** At most this long; then the lock comes back, but the package stays pinned while its call lives. */
const val VOIP_CAP_MS = 3 * 60 * 60_000L

/** A ring is the lock's ring screen for at most this long from its first sight (qa-16-17-code #4):
 * a CALL notification that never goes away must not keep the lock's exemptions forever. */
const val VOIP_RING_LIMIT_MS = 2 * 60_000L

/** `Notification.CATEGORY_CALL`. */
const val CATEGORY_CALL = "call"

/** The messaging apps whose calls may ring over the lock while calls aren't managed. */
val KNOWN_VOIP_PACKAGES: Set<String> = setOf(MessagePackages.ELEMENT_X) + MessagePackages.SIGNAL

/**
 * The packages whose calls ring over the lock, from the phone path's answer (QA #8): the effective
 * call state (`CallPolicyStore.effectiveState()`, a no-calls time rule folded in - the override and
 * the pause lift a rule's call block, never the call rules). Managed with calls on: the contacts'
 * messaging apps (never the SMS app); unmanaged: the known VoIP messengers; calls off or unknown
 * (fail closed): none. The caller also requires the package to be unsuspended - a package that
 * isn't allowlisted is suspended (and hidden), and a time-rule lock suspends it unless usable.
 */
fun voipCandidates(effective: CallPolicyState): Set<String> = when (effective) {
    CallPolicyState.Unmanaged -> KNOWN_VOIP_PACKAGES
    CallPolicyState.UnknownFailClosed -> emptySet()
    is CallPolicyState.Managed -> if (effective.rules.callsEnabled) messagingAppPackages(effective.rules, null) else emptySet()
}

enum class VoipNoticeKind { NONE, RINGING, IN_CALL, FSI_DENIED }

/**
 * One notification, from category, channel and flags only (never text): a ringing call is a
 * CALL notification with a full-screen intent; one whose full-screen intent Android dropped
 * (no USE_FULL_SCREEN_INTENT, `FLAG_FSI_REQUESTED_BUT_DENIED`, QA #11) can't ring over the lock
 * and is reported; the call itself is the app's foreground-service notification on a call channel
 * (Element: `call_foreground_service_channel`) or of the CALL category.
 */
fun voipNoticeKind(
    category: String?,
    channelId: String?,
    foregroundService: Boolean,
    hasFullScreenIntent: Boolean,
    fsiDenied: Boolean,
    incomingCallStyle: Boolean = false,
): VoipNoticeKind = when {
    category == CATEGORY_CALL && hasFullScreenIntent -> VoipNoticeKind.RINGING
    category == CATEGORY_CALL && fsiDenied -> VoipNoticeKind.FSI_DENIED
    // An incoming CallStyle (`EXTRA_CALL_TYPE` = CALL_TYPE_INCOMING) is a ring, never "the call":
    // its content intent may answer it (qa-16-17-code #8).
    incomingCallStyle -> VoipNoticeKind.NONE
    foregroundService && (channelId == ELEMENT_CALL_CHANNEL || category == CATEGORY_CALL) -> VoipNoticeKind.IN_CALL
    else -> VoipNoticeKind.NONE
}

/** `Notification.CallStyle.CALL_TYPE_INCOMING` (the `EXTRA_CALL_TYPE` int). */
const val CALL_TYPE_INCOMING = 1

/**
 * Whether a notice rings over the lock: a ring with its full-screen intent, or (17b QA #4) an
 * incoming CallStyle ([incoming], `EXTRA_CALL_TYPE`, qa-17b-code #2) whose full-screen intent
 * Android dropped but that can be answered - the card answers it.
 */
fun voipRings(kind: VoipNoticeKind, hasAnswer: Boolean, incoming: Boolean): Boolean =
    kind == VoipNoticeKind.RINGING || (kind == VoipNoticeKind.FSI_DENIED && hasAnswer && incoming)

/**
 * The extra NotificationCompat's CallStyle puts on the Decline and Answer actions it adds to
 * `Notification.actions` (androidx core `NotificationCompat.CallStyle.makeAction`, checked 1.17).
 */
const val KEY_CALL_STYLE_ACTION = "key_action_priority"

/** One `Notification.Action` as the answer pick sees it: its intent and whether CallStyle added it. */
data class CallAction<T>(val intent: T?, val callStyle: Boolean)

/**
 * The ring's answer intent (design 17b, QA #1), never by title or semantic action (CallStyle sets
 * none; `SEMANTIC_ACTION_CALL` means "call back"): the CallStyle's `EXTRA_ANSWER_INTENT` (platform
 * style on 31+ and NotificationCompat both set it), else - only for an [incoming] CallStyle with a
 * decline intent (qa-17b-code #2: an ongoing call's only marked action is its hang-up) - the one
 * action CallStyle added ([KEY_CALL_STYLE_ACTION]) that isn't the decline intent. The content
 * intent is no input - Element's *is* its answer intent, but only this pick decides. `null`:
 * Answer sends the full-screen intent.
 */
fun <T> pickAnswerIntent(answerExtra: T?, decline: T?, actions: List<CallAction<T>>, incoming: Boolean): T? {
    if (answerExtra != null && answerExtra != decline) return answerExtra
    if (!incoming || decline == null) return null
    val added = actions.mapNotNull { action -> action.intent?.takeIf { action.callStyle && it != decline } }.distinct()
    return added.singleOrNull()
}

/** What the card's Svar sent (17b): the answer action, the app's ring screen (no answer action),
 * or nothing. */
enum class VoipAnswerSent { NOTHING, ANSWER_ACTION, RING_SCREEN }

/** Svar silences this ring only once the answer action went out (qa-17b-code #1): the app's own
 * ring screen still rings (its sound is muted while LOCKED). */
fun answerSilences(sent: VoipAnswerSent): Boolean = sent == VoipAnswerSent.ANSWER_ACTION

/** Svar's start was refused (the lock never left the front, qa-17b-code #1): the ring's silence goes
 * back to what it was before Svar - a power-button silence of the same ring stays. */
fun silenceAfterRefusedAnswer(silencedRing: Long?, answeredRing: Long?, silencedBeforeAnswer: Long?): Long? =
    if (answeredRing != null && silencedRing == answeredRing) silencedBeforeAnswer else silencedRing

/** The lock has been resumed this long before it sends the ring's full-screen intent (17b QA #2):
 * the wake activity goes on top of it right after its first resume. */
const val VOIP_FSI_SETTLE_MS = 300L

/** A send from the lock "came up" if the lock left the front within this (17b): a refused
 * background start is silent. */
const val VOIP_FSI_CHECK_MS = 1_500L

/** The lock's try at the app's own ring screen for one ring ([ringId] = `VoipCalls.ringId`). */
data class VoipFsiTry(
    val ringId: Long,
    val sentAtElapsedMs: Long,
    /** The send went out (no exception); `false` also when the app's screen came up without us
     * ([voipTryAfterPause], qa-17b-code #5). */
    val sent: Boolean,
    /** The lock left the front after the send: the app's screen came up (or the screen went off). */
    val left: Boolean = false,
)

/** The wake activity counts as ours this long after the lock asked for it (qa-17b-code #5). */
const val VOIP_WAKE_WINDOW_MS = 2_000L

/**
 * A pause that means the app's ring screen came up without our send (qa-17b-code #5): the lock
 * paused while its send was still settling ([sendPending]), or the wake activity was covered before
 * it finished itself ([wakeCovered]) - SystemUI launching the full-screen intent itself, or the
 * screen going off. Never our own wake activity going over the lock: asked for less than
 * [VOIP_WAKE_WINDOW_MS] ago and not gone yet.
 */
fun pauseIsTry(
    sendPending: Boolean,
    wakeCovered: Boolean,
    wakeAskedAtElapsedMs: Long?,
    wakeGone: Boolean,
    nowElapsedMs: Long,
): Boolean {
    if (wakeCovered) return true
    if (!sendPending) return false
    val wakeComing = wakeAskedAtElapsedMs != null && !wakeGone && nowElapsedMs - wakeAskedAtElapsedMs in 0 until VOIP_WAKE_WINDOW_MS
    return !wakeComing
}

/** The try after the lock (or the wake) left the front: this ring's try is marked left; with none
 * yet, a pause that [pauseIsTry] is one that came up by itself - nothing sent, the card from then on,
 * never our send of the same screen again (qa-17b-code #5). */
fun voipTryAfterPause(last: VoipFsiTry?, ringId: Long?, pauseIsTry: Boolean, nowElapsedMs: Long): VoipFsiTry? = when {
    ringId == null -> last
    last != null && last.ringId == ringId -> if (last.left) last else last.copy(left = true)
    pauseIsTry -> VoipFsiTry(ringId, nowElapsedMs, sent = false, left = true)
    else -> last
}

/** What the lock shows for a ring: nothing, the plain lock while its try at the app's screen is
 * open ([WAIT]), or the ring card. */
enum class VoipRingUi { NONE, WAIT, CARD }

/** After a send from the resumed lock: the app's screen is overdue - the lock never left the front
 * within [VOIP_FSI_CHECK_MS]. */
fun voipStartOverdue(sentAtElapsedMs: Long, lockLeft: Boolean, nowElapsedMs: Long): Boolean =
    !lockLeft && nowElapsedMs - sentAtElapsedMs !in 0 until VOIP_FSI_CHECK_MS

/**
 * Design 17b: a ring over the LOCKED lock shows the app's own ring screen first - the resumed lock
 * sends its full-screen intent once per ring - and our card only as the fallback. Never over
 * another call (ours or Telecom's, emergency included), the emergency flow or an alarm
 * ([otherScreen], 17b QA #5), never after Avvis ([dismissed]).
 */
fun voipFsiDue(
    ringId: Long?,
    locked: Boolean,
    dismissed: Boolean,
    otherScreen: Boolean,
    hasFullScreen: Boolean,
    last: VoipFsiTry?,
): Boolean = ringId != null && locked && !dismissed && !otherScreen && hasFullScreen && last?.ringId != ringId

/**
 * The card is the fallback (17b): at once without a full-screen intent (Android dropped it), else
 * after the try - it threw, the lock came back during the same ring (Back or power on the app's
 * screen; never a second try), or nothing came up within [VOIP_FSI_CHECK_MS]. Until then the plain
 * lock (no card flash under the app's screen).
 */
fun voipRingUi(
    ringId: Long?,
    locked: Boolean,
    dismissed: Boolean,
    otherScreen: Boolean,
    hasFullScreen: Boolean,
    last: VoipFsiTry?,
    nowElapsedMs: Long,
): VoipRingUi = when {
    ringId == null || !locked || dismissed || otherScreen -> VoipRingUi.NONE
    !hasFullScreen -> VoipRingUi.CARD
    last == null || last.ringId != ringId -> VoipRingUi.WAIT
    !last.sent || last.left || voipStartOverdue(last.sentAtElapsedMs, last.left, nowElapsedMs) -> VoipRingUi.CARD
    else -> VoipRingUi.WAIT
}

/** The exemption in force (CE prefs `voip_call`): one package at a time. */
data class VoipRecord(
    val packageName: String,
    /** Its start by every clock ([timedWindowActive], cap [VOIP_CAP_MS]); a reboot ends it. */
    val start: WindowStart,
    /** When its ring ended (the grace counts from here); `null` while ringing or not yet seen.
     * Stored, so a new process doesn't start a fresh grace (qa-16-17-code #7). */
    val ringEndedElapsedMs: Long? = null,
    /** When the current ring was first seen ([VOIP_RING_LIMIT_MS]); `null` after it ended. */
    val ringStartedElapsedMs: Long? = null,
)

data class VoipInputs(
    /** Allowed packages ([voipCandidates], unsuspended) with a ringing CALL notification. */
    val ringing: Set<String>,
    /** Packages with a call foreground-service notification (any: only the record's counts). */
    val inCall: Set<String>,
    /** The notification listener reported in this process; `false` = only the stored record. */
    val listenerSeen: Boolean,
    /** Since when the facts are unverified (process start, or the listener went away). */
    val unverifiedSinceElapsedMs: Long,
    /** `AudioManager.MODE_IN_COMMUNICATION`: only ever an extra AND (any app can hold it, QA #5). */
    val audioInCommunication: Boolean,
    val nowWallMs: Long,
    val nowElapsedMs: Long,
    val bootCount: Int,
)

/** The [record] to keep, what the lock sees ([phase]) and the package kept on the lock-task list
 * with the kiosk off ([pinned] - never dropped while the call may live, QA #4). */
data class VoipVerdict(val record: VoipRecord?, val phase: VoipPhase, val pinned: String?)

private val NO_VOIP = VoipVerdict(null, VoipPhase.NONE, null)

/**
 * The exemption's lifetime (QA #4/#5 and the decisions):
 * - the record's package rings: RINGING - for [VOIP_RING_LIMIT_MS] from the ring's first sight and
 *   within the cap, then NONE (the lock comes back, qa-16-17-code #4); else another allowed package
 *   rings while no call lives: a new record, RINGING (one call at a time - a second app's ring
 *   during a call is ignored);
 * - the ring ended: [VOIP_GRACE_MS] of IN_CALL and pinned (Element cancels the ring before its
 *   call service starts - a re-front or an unpin in that gap would end the answered call);
 * - the call's foreground service: pinned, IN_CALL while the audio mode is IN_COMMUNICATION (or
 *   in the grace), else NONE - the lock comes back but the package stays;
 * - [VOIP_CAP_MS] after the start: NONE (the lock comes back), still pinned while the call lives;
 * - before the listener reported in this process (a crash, an update): the stored record keeps
 *   the package pinned and IN_CALL for [VOIP_GRACE_MS] from the process start, then it ends - the
 *   audio mode alone never holds it (qa-16-17-code #7);
 * - another boot (or elapsed time going backwards) ends the record.
 */
fun voipExemption(stored: VoipRecord?, i: VoipInputs): VoipVerdict {
    val record = stored?.takeIf { sameBoot(it.start, i) }
    if (record != null && record.packageName in i.ringing) {
        // The same ring goes on, or (its last ring ended) a new one starts now.
        val ringStarted = if (record.ringEndedElapsedMs == null) record.ringStartedElapsedMs ?: i.nowElapsedMs else i.nowElapsedMs
        val next = record.copy(ringEndedElapsedMs = null, ringStartedElapsedMs = ringStarted)
        val ringActive = i.nowElapsedMs - ringStarted in 0 until VOIP_RING_LIMIT_MS
        val capActive = timedWindowActive(record.start, i.nowWallMs, i.nowElapsedMs, i.bootCount, VOIP_CAP_MS)
        return VoipVerdict(next, if (ringActive && capActive) VoipPhase.RINGING else VoipPhase.NONE, record.packageName)
    }
    val current = record?.let { continueCall(it, i) }
    if (current != null) return current
    val ring = i.ringing.minOrNull() ?: return NO_VOIP
    val start = WindowStart(untilWallMs = i.nowWallMs + VOIP_CAP_MS, elapsedStartMs = i.nowElapsedMs, bootCount = i.bootCount)
    return VoipVerdict(VoipRecord(ring, start, ringStartedElapsedMs = i.nowElapsedMs), VoipPhase.RINGING, ring)
}

private fun sameBoot(start: WindowStart, i: VoipInputs): Boolean =
    start.bootCount >= 0 && start.bootCount == i.bootCount && i.nowElapsedMs >= start.elapsedStartMs

/** The record's call after its ring, or `null` when it is over. */
private fun continueCall(record: VoipRecord, i: VoipInputs): VoipVerdict? {
    val pkg = record.packageName
    val capActive = timedWindowActive(record.start, i.nowWallMs, i.nowElapsedMs, i.bootCount, VOIP_CAP_MS)
    fun live(exempt: Boolean, next: VoipRecord = record) =
        VoipVerdict(next, if (exempt && capActive) VoipPhase.IN_CALL else VoipPhase.NONE, pkg)
    if (!i.listenerSeen) {
        val startup = i.nowElapsedMs - i.unverifiedSinceElapsedMs in 0 until VOIP_GRACE_MS
        return if (startup) live(exempt = true) else null
    }
    val ringEnded = record.ringEndedElapsedMs ?: i.nowElapsedMs
    val next = record.copy(ringEndedElapsedMs = ringEnded, ringStartedElapsedMs = null)
    val grace = i.nowElapsedMs - ringEnded in 0 until VOIP_GRACE_MS
    return when {
        pkg in i.inCall -> live(exempt = grace || i.audioInCommunication, next)
        grace -> live(exempt = true, next)
        else -> null
    }
}

/**
 * Whether the system dialer has a call the lock steps aside for (QA #7): Telecom's
 * `isInManagedCall` when readable - `isInCall` also counts self-managed ConnectionService calls,
 * which let any allowlisted app with MANAGE_OWN_CALLS hold the lock open with no cap; those go
 * through [voipCandidates] now. Without READ_PHONE_STATE ([inManagedCall] `null`) only a telephony
 * audio mode (`MODE_IN_CALL`) counts, never `MODE_IN_COMMUNICATION` (VoIP).
 */
fun managedCallActive(inManagedCall: Boolean?, audioMode: Int): Boolean = inManagedCall ?: (audioMode == AUDIO_MODE_IN_CALL)

/** `AudioManager.MODE_IN_CALL` / `MODE_IN_COMMUNICATION`, duplicated to stay Android-free. */
const val AUDIO_MODE_IN_CALL = 2
const val AUDIO_MODE_IN_COMMUNICATION = 3

/** `AudioManager.RINGER_MODE_*` and `NotificationManager.INTERRUPTION_FILTER_*`. */
const val RINGER_MODE_SILENT = 0
const val RINGER_MODE_VIBRATE = 1
const val RINGER_MODE_NORMAL = 2
const val INTERRUPTION_FILTER_ALL = 1
const val INTERRUPTION_FILTER_PRIORITY = 2
const val INTERRUPTION_FILTER_NONE = 3
const val INTERRUPTION_FILTER_ALARMS = 4

data class RingPlan(val sound: Boolean, val vibrate: Boolean)

/**
 * Whether the lock's own ring plays now - evaluated on every VoIP, lock-mode and poll change
 * (qa-16-17-code #1/#5): an allowed app rings while LOCKED (also a ring that began unlocked: once
 * the phone locks, the lock's status-bar flags mute the app's own sound), not silenced for this
 * ring (the power button on a ringing lock, or Avvis), and never during another call (ours or
 * any Telecom-managed one, emergency included), the emergency dialer flow or a ringing alarm.
 */
fun voipRingWanted(
    ringing: Boolean,
    locked: Boolean,
    silenced: Boolean,
    otherCall: Boolean,
    emergencyFlow: Boolean,
    alarmRinging: Boolean,
    /** The ringing app's call foreground service is up: answered (17b QA #6). */
    callService: Boolean = false,
): Boolean = ringing && locked && !silenced && !otherCall && !emergencyFlow && !alarmRinging && !callService

/** A screen-off silences the ring only when it finds the lock already LOCKED and ringing - a
 * ring that began unlocked starts ringing at that screen-off instead (qa-16-17-code #5). */
fun screenOffSilencesRing(lockedBefore: Boolean, ringing: Boolean): Boolean = lockedBefore && ringing

/**
 * The lock's own ring (QA #1: the shade flags that keep the lock's status bar closed also mute
 * every notification sound, and Element's ringtone is only its notification sound): the default
 * ringtone and vibration, as the phone's ringer mode and Do Not Disturb allow - DND lets it ring
 * only with calls from anyone allowed ([callsFromAnyone]: the caller has no contact identity).
 */
fun ringPlan(ringerMode: Int, interruptionFilter: Int, callsFromAnyone: Boolean): RingPlan {
    val dndBlocks = when (interruptionFilter) {
        INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_ALARMS -> true
        INTERRUPTION_FILTER_PRIORITY -> !callsFromAnyone
        else -> false
    }
    if (dndBlocks) return RingPlan(sound = false, vibrate = false)
    return when (ringerMode) {
        RINGER_MODE_NORMAL -> RingPlan(sound = true, vibrate = true)
        RINGER_MODE_VIBRATE -> RingPlan(sound = false, vibrate = true)
        else -> RingPlan(sound = false, vibrate = false)
    }
}
