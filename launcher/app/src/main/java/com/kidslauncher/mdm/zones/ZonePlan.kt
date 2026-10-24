package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.dto.PolicyZone
import kotlinx.serialization.Serializable

/*
 * Safe zones on the phone (design 64 step 2, `docs/design/64-safe-zones.md` §4) - the pure rules,
 * no Android imports, tested in ZonePlanTest. The phone never judges a zone (decision 1): the
 * platform's fences and the significant-motion sensor are only reasons to take or keep a fix, and
 * every fix goes to the server, whose two-fix rule decides "arrived" and "left". So nothing here
 * computes a side or a distance, and the phone keeps no copy of `zone_vectors.json`.
 */

/** At most this many fixes wait for a report ([queueWith]); the server stores at most 20 too. */
const val ZONE_QUEUE_MAX = 20

/** A passive fix less accurate than this is not kept (§4.3): a cell fix of a kilometre would only
 * replace a better cached one. */
const val PASSIVE_ACCURACY_FLOOR_M = 200f

/** An "entering" this soon after its fence was registered is the registration's own echo (AOSP's
 * `GeofenceRegistration.onActive`: unknown -> inside fires at once when the last fix is inside). */
const val FENCE_ECHO_WINDOW_MS = 60_000L

/** A crossing's confirming fix is taken this long after the first (§4.5): the server's pair needs
 * two sure fixes at least 60 s apart, so the arrival is confirmed within about two minutes. */
const val CONFIRM_DELAY_MS = 2 * 60_000L

/** The heartbeat skips its fix while the phone hasn't moved, but at most this long since the last
 * fix of our own (decision 11): a sensor that never fires can't freeze the trail. */
const val STILL_SKIP_MAX_MS = 4 * 60 * 60_000L

/** The fences we registered (CE prefs `zone_fences`): the policy's zones as registered, their
 * ids, and when (elapsed realtime) - the echo window counts from there. */
@Serializable
data class FenceRecord(val fingerprint: String, val ids: List<Long>, val registeredAtElapsed: Long)

/** What to do with the platform's fences: remove [remove] (our earlier ids), then add [add]. */
data class FencePlan(val remove: List<Long>, val add: List<PolicyZone>) {
    val isEmpty: Boolean get() = remove.isEmpty() && add.isEmpty()
}

/** The zones as a fence sees them - id, centre, radius - in id order; names never come. */
fun fenceFingerprint(zones: List<PolicyZone>): String =
    zones.sortedBy { it.id }.joinToString(";") { "${it.id},${it.latitude},${it.longitude},${it.radiusM}" }

/**
 * The fences for [zones] (decision 10): [watching] = device owner, enrolled and the policy's mode
 * "interval" (the server sends zones only then; an older server none). Idempotent - remove ours,
 * add the current set - whenever this process hasn't registered yet ([freshProcess]: fences live
 * in system_server's memory and are gone after a reboot; an update or a crash may have cancelled
 * our PendingIntents) or the zones changed; nothing otherwise. Not watching: every stored fence
 * goes.
 */
fun zoneFencePlan(zones: List<PolicyZone>, watching: Boolean, stored: FenceRecord?, freshProcess: Boolean): FencePlan {
    val wanted = if (watching) zones.distinctBy { it.id }.sortedBy { it.id } else emptyList()
    val storedIds = stored?.ids.orEmpty()
    if (wanted.isEmpty()) return FencePlan(storedIds, emptyList())
    if (!freshProcess && stored != null && stored.fingerprint == fenceFingerprint(wanted)) return FencePlan(emptyList(), emptyList())
    return FencePlan(storedIds, wanted)
}

/** The fence broadcast's verdict (§4.2): a zone not in the current policy is no crossing, nor an
 * "entering" within [FENCE_ECHO_WINDOW_MS] of the registration ([sinceRegisteredMs], elapsed;
 * negative = a record from before a reboot, no echo). */
enum class FenceVerdict { CROSSING, NOT_OURS, ECHO }

fun fenceVerdict(zoneId: Long, policyZones: List<PolicyZone>, entering: Boolean, sinceRegisteredMs: Long): FenceVerdict = when {
    policyZones.none { it.id == zoneId } -> FenceVerdict.NOT_OURS
    entering && sinceRegisteredMs in 0 until FENCE_ECHO_WINDOW_MS -> FenceVerdict.ECHO
    else -> FenceVerdict.CROSSING
}

/** A fix waiting for a report (CE prefs `zone_queue`): what [com.kidslauncher.mdm.server.dto.LocationReport]
 * sends. Its key is its time and place - the server's dedupe key. */
@Serializable
data class QueuedFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timeMs: Long,
    val provider: String? = null,
    val mock: Boolean = false,
) {
    fun sameFix(other: QueuedFix) = timeMs == other.timeMs && latitude == other.latitude && longitude == other.longitude
}

/** [fix] queued: once (its key), in time order, the newest [max] kept. */
fun queueWith(queue: List<QueuedFix>, fix: QueuedFix, max: Int = ZONE_QUEUE_MAX): List<QueuedFix> {
    val all = if (queue.any { it.sameFix(fix) }) queue else queue + fix
    return all.sortedBy { it.timeMs }.takeLast(max)
}

/** A report's `extra_locations`: the queue without the report's own [location], oldest first; `null`
 * for none (the key left out). */
fun extraLocations(queue: List<QueuedFix>, location: QueuedFix?): List<QueuedFix>? =
    queue.filterNot { location != null && it.sameFix(location) }
        .sortedBy { it.timeMs }
        .takeLast(ZONE_QUEUE_MAX)
        .ifEmpty { null }

/**
 * The queue after a report (§4.5: every reported fix also feeds the queue when its sync fails):
 * taken by the server ([accepted]) - what it carried ([sent], the extras and the location) leaves,
 * whatever was queued meanwhile stays; not taken - the report's [location] joins the queue, so a
 * crossing's fix made in a tunnel arrives with the next report, in order. A report while location
 * is off ([locationOff]) empties it: nothing kept from before the parent turned it off is sent.
 */
fun queueAfterReport(queue: List<QueuedFix>, sent: List<QueuedFix>, location: QueuedFix?, accepted: Boolean, locationOff: Boolean): List<QueuedFix> = when {
    locationOff -> emptyList()
    accepted -> queue.filterNot { q -> sent.any { it.sameFix(q) } || (location != null && location.sameFix(q)) }
    location != null -> queueWith(queue, location)
    else -> queue
}

/** What the significant-motion sensor says since our last own fix (§4.5): the one-shot trigger
 * fired ([MOVED]; also "don't know", e.g. a new process), it hasn't ([STILL]), or the phone has
 * none ([NO_SENSOR]: the heartbeat always fixes, today's behaviour). */
enum class Motion { MOVED, STILL, NO_SENSOR }

/** The heartbeat's call at a sync (§4.4). */
enum class Heartbeat {
    /** The interval hasn't passed since the last heartbeat: the cached fix, as before. */
    NOT_DUE,

    /** Due: a fresh fix of our own. */
    FIX,

    /** Due, but a kept fix (passive or a crossing's) is younger than the interval: report it,
     * no LocationManager call. */
    KEPT,

    /** Due, the phone hasn't moved since our last fix and that was under 4 h ago: report the
     * cached fix, no LocationManager call. */
    STILL,
}

/**
 * The Regularly heartbeat (decision 11, §4.4): due when [intervalMs] passed since the last
 * heartbeat ([lastBeatMs], wall clock; the clock set back counts as due). Due, it takes no fix when
 * a kept fix is younger than the interval ([keptFixAgeMs], `null` = none) or when [motion] says the
 * phone hasn't moved and our last own fix ([lastOwnFixMs]) is under [STILL_SKIP_MAX_MS] old. Each due
 * call counts as a heartbeat (the caller stamps it), so a skip waits a whole interval, never the
 * backstop's 9-minute floor.
 */
fun heartbeatDue(nowMs: Long, lastBeatMs: Long, intervalMs: Long, motion: Motion, lastOwnFixMs: Long, keptFixAgeMs: Long?): Heartbeat {
    val since = nowMs - lastBeatMs
    if (since in 0 until intervalMs) return Heartbeat.NOT_DUE
    if (keptFixAgeMs != null && keptFixAgeMs in 0 until intervalMs) return Heartbeat.KEPT
    val sinceOwn = nowMs - lastOwnFixMs
    if (motion == Motion.STILL && sinceOwn in 0 until STILL_SKIP_MAX_MS) return Heartbeat.STILL
    return Heartbeat.FIX
}

/** Why a fix is or isn't kept. */
enum class FixVerdict { KEEP, MOCK, INACCURATE, NOT_NEWER }

/**
 * A fix the platform hands us (§4.3, decision 14): a mock fix (`Location.isMock`) counts for
 * nothing in a release build ([acceptMock] false) - [FixVerdict.MOCK], counted by the caller; the
 * debug build takes it (the emulator's test provider). [requireBetter] (the passive feed): also
 * refused without an accuracy, less accurate than [floorM], or not newer than the cached fix
 * ([cachedTimeMs], `null` = none) - it then only replaces the cached fix, it is never sent on its
 * own (the trail stays as dense as the parent's interval).
 */
fun fixVerdict(
    isMock: Boolean,
    acceptMock: Boolean,
    requireBetter: Boolean = false,
    accuracyM: Float? = null,
    timeMs: Long = 0,
    cachedTimeMs: Long? = null,
    floorM: Float = PASSIVE_ACCURACY_FLOOR_M,
): FixVerdict = when {
    isMock && !acceptMock -> FixVerdict.MOCK
    !requireBetter -> FixVerdict.KEEP
    accuracyM == null || accuracyM.isNaN() || accuracyM > floorM -> FixVerdict.INACCURATE
    cachedTimeMs != null && timeMs <= cachedTimeMs -> FixVerdict.NOT_NEWER
    else -> FixVerdict.KEEP
}

/** The passive feed's filter (§4.3): [fixVerdict] with the accuracy floor and "newer than the
 * cached fix". */
fun passiveFixWorth(cachedTimeMs: Long?, isMock: Boolean, accuracyM: Float?, timeMs: Long, acceptMock: Boolean, floorM: Float = PASSIVE_ACCURACY_FLOOR_M): FixVerdict =
    fixVerdict(isMock, acceptMock, requireBetter = true, accuracyM = accuracyM, timeMs = timeMs, cachedTimeMs = cachedTimeMs, floorM = floorM)
