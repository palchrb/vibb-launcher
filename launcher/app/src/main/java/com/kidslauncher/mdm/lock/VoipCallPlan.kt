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
): VoipNoticeKind = when {
    category == CATEGORY_CALL && hasFullScreenIntent -> VoipNoticeKind.RINGING
    category == CATEGORY_CALL && fsiDenied -> VoipNoticeKind.FSI_DENIED
    foregroundService && (channelId == ELEMENT_CALL_CHANNEL || category == CATEGORY_CALL) -> VoipNoticeKind.IN_CALL
    else -> VoipNoticeKind.NONE
}

/** The exemption in force (CE prefs `voip_call` without [ringEndedElapsedMs]): one package at a time. */
data class VoipRecord(
    val packageName: String,
    /** Its start by every clock ([timedWindowActive], cap [VOIP_CAP_MS]); a reboot ends it. */
    val start: WindowStart,
    /** When its ring ended (the grace counts from here); `null` while ringing or not yet seen. */
    val ringEndedElapsedMs: Long? = null,
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
 * - the record's package rings: RINGING; else another allowed package rings while no call lives:
 *   a new record, RINGING (one call at a time - a second app's ring during a call is ignored);
 * - the ring ended: [VOIP_GRACE_MS] of IN_CALL and pinned (Element cancels the ring before its
 *   call service starts - a re-front or an unpin in that gap would end the answered call);
 * - the call's foreground service: pinned, IN_CALL while the audio mode is IN_COMMUNICATION (or
 *   in the grace), else NONE - the lock comes back but the package stays;
 * - [VOIP_CAP_MS] after the start: NONE (the lock comes back), still pinned while the call lives;
 * - before the listener reported in this process (a crash, an update): the stored record keeps
 *   the package pinned and IN_CALL for [VOIP_GRACE_MS], then only while the audio mode says call;
 * - another boot (or elapsed time going backwards) ends the record.
 */
fun voipExemption(stored: VoipRecord?, i: VoipInputs): VoipVerdict {
    val record = stored?.takeIf { sameBoot(it.start, i) }
    if (record != null && record.packageName in i.ringing) {
        return VoipVerdict(record.copy(ringEndedElapsedMs = null), VoipPhase.RINGING, record.packageName)
    }
    val current = record?.let { continueCall(it, i) }
    if (current != null) return current
    val ring = i.ringing.minOrNull() ?: return NO_VOIP
    val start = WindowStart(untilWallMs = i.nowWallMs + VOIP_CAP_MS, elapsedStartMs = i.nowElapsedMs, bootCount = i.bootCount)
    return VoipVerdict(VoipRecord(ring, start), VoipPhase.RINGING, ring)
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
        return if (startup || i.audioInCommunication) live(exempt = true) else null
    }
    val ringEnded = record.ringEndedElapsedMs ?: i.nowElapsedMs
    val next = record.copy(ringEndedElapsedMs = ringEnded)
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
