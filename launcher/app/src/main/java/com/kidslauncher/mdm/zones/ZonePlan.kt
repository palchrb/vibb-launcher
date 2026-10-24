package com.kidslauncher.mdm.zones

import com.kidslauncher.mdm.server.dto.PolicyZone
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Safe zones on the phone (design 64 step 2, `docs/design/64-safe-zones.md` §4) - the pure rules,
 * no Android imports, tested in ZonePlanTest. The phone never judges a zone (decision 1): the
 * platform's fences and the significant-motion sensor are only reasons to take or keep a fix, and
 * every fix goes to the server, whose two-fix rule decides "arrived" and "left". The distances
 * here (the moving series, §4.5) only decide *when* to take a fix and whether one is worth sending
 * now; they never decide a crossing, and the phone keeps no copy of `zone_vectors.json`.
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
 * "entering" within [FENCE_ECHO_WINDOW_MS] of the registration ([sinceRegisteredMs], elapsed). The
 * record (and its stamp) is written before the first add (qa-design-64-step2-code Q-7), so no
 * record (`null`) or a stamp from before a reboot (negative) is within the window too: an
 * "entering" then is the registration's own echo. */
enum class FenceVerdict { CROSSING, NOT_OURS, ECHO }

fun fenceVerdict(zoneId: Long, policyZones: List<PolicyZone>, entering: Boolean, sinceRegisteredMs: Long?): FenceVerdict = when {
    policyZones.none { it.id == zoneId } -> FenceVerdict.NOT_OURS
    entering && (sinceRegisteredMs == null || sinceRegisteredMs < FENCE_ECHO_WINDOW_MS) -> FenceVerdict.ECHO
    else -> FenceVerdict.CROSSING
}

/**
 * The zone watch is on (qa-design-64-step2-code Q-2, the coordinator's decision): device owner,
 * managed, Regularly **with zones**. Everything new of step 2 - the passive feed, the motion gate,
 * the heartbeat's skips, the backstop counting from the beat, the series, the balanced path -
 * hangs on this; without zones the phone does exactly what it did before ([syncLocationAction]).
 */
fun zoneWatchActive(owner: Boolean, managed: Boolean, policy: com.kidslauncher.mdm.server.dto.LocationPolicy?): Boolean =
    owner && managed && policy != null && policy.mode == "interval" && policy.zones.isNotEmpty()

/** The location step's action and, with zones, the heartbeat's verdict (`null` = no heartbeat ran). */
data class SyncLocation(val action: com.kidslauncher.mdm.server.LocationAction, val beat: Heartbeat?)

/**
 * The sync's location action (§4.4). **Without zones** ([zonesOn] false) it is exactly today's
 * `locationAction(policy, forced, now - lastOwnFix)` - nothing of step 2 changes it. With zones: a
 * forced fix is the precise path for [precise] (`locate`/`ring`, a crossing's confirm) and the
 * balanced one for a crossing's first fix; else [heartbeatDue] decides, a `FIX` taking the
 * balanced path.
 */
fun syncLocationAction(
    policy: com.kidslauncher.mdm.server.dto.LocationPolicy?,
    zonesOn: Boolean,
    forced: Boolean,
    precise: Boolean,
    nowMs: Long,
    lastOwnFixMs: Long,
    lastBeatMs: Long,
    motion: Motion,
    keptFixAgeMs: Long?,
): SyncLocation {
    val today = com.kidslauncher.mdm.server.locationAction(policy, forced, nowMs - lastOwnFixMs)
    if (!zonesOn || policy == null || policy.mode != "interval") return SyncLocation(today, null)
    val p = policy
    if (forced) {
        val fresh = if (precise) com.kidslauncher.mdm.server.LocationAction.FRESH else com.kidslauncher.mdm.server.LocationAction.FRESH_BALANCED
        return SyncLocation(fresh, null)
    }
    val intervalMs = p.intervalMinutes.coerceAtLeast(com.kidslauncher.mdm.server.MIN_LOCATION_INTERVAL_MINUTES) * 60_000L
    val beat = heartbeatDue(nowMs, lastBeatMs, intervalMs, motion, lastOwnFixMs, keptFixAgeMs)
    val action = if (beat == Heartbeat.FIX) com.kidslauncher.mdm.server.LocationAction.FRESH_BALANCED else com.kidslauncher.mdm.server.LocationAction.CACHED
    return SyncLocation(action, beat)
}

/** What the backstop counts the next fix from: the beat with zones (a skip waits a whole
 * interval), our last own fix without - today's. */
fun backstopAnchorMs(zonesOn: Boolean, lastBeatMs: Long, lastOwnFixMs: Long): Long = if (zonesOn) lastBeatMs else lastOwnFixMs

/** A fix younger than this at the request is one LocationManager just made, not an older
 * last-known or cached one (Q-6). */
const val FRESH_FIX_SLACK_MS = 60_000L

/** The fix a fresh request returned is genuinely new (taken at or after the request, less
 * [FRESH_FIX_SLACK_MS]) - only then may the motion gate say "still from here" (Q-6). */
fun freshOwnFix(fixTimeMs: Long, requestedAtMs: Long): Boolean = fixTimeMs >= requestedAtMs - FRESH_FIX_SLACK_MS

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
 * crossing's fix made in a tunnel arrives with the next report, in order. [locationOff] (location
 * Off, or no zones watched) empties it; the sync also clears it and sends no extras before such a
 * report (Q-5), so nothing kept from before the parent turned it off is ever sent.
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
 * The Regularly heartbeat with zones (decision 11, §4.4): due when [intervalMs] passed since the
 * last heartbeat ([lastBeatMs], wall clock; the clock set back counts as due). Due, it takes no fix
 * when a kept fix **not yet reported** is younger than the interval ([keptFixAgeMs]: the kept slot's
 * fix - a passive fix or a series check's -, `null` when there is none or it isn't newer than the
 * reported one; our own reported fixes never count, Q-4) or when [motion] says the
 * phone hasn't moved and our last own fix ([lastOwnFixMs]) is under [STILL_SKIP_MAX_MS] old. Each due
 * call counts as a heartbeat (the caller stamps it), so a skip waits a whole interval, never the
 * backstop's 9-minute floor.
 */
fun heartbeatDue(nowMs: Long, lastBeatMs: Long, intervalMs: Long, motion: Motion, lastOwnFixMs: Long, keptFixAgeMs: Long?): Heartbeat {
    val since = nowMs - lastBeatMs
    if (since in 0 until intervalMs) return Heartbeat.NOT_DUE
    if (keptFixAgeMs != null && keptFixAgeMs in 0 until intervalMs) return Heartbeat.KEPT
    if (stillSinceOwnFix(motion, nowMs, lastOwnFixMs)) return Heartbeat.STILL
    return Heartbeat.FIX
}

/** The motion sensor hasn't fired since our last own fix ([lastOwnFixMs]), and that fix is under
 * [STILL_SKIP_MAX_MS] old: the phone is still where the reported fix says. */
fun stillSinceOwnFix(motion: Motion, nowMs: Long, lastOwnFixMs: Long): Boolean =
    motion == Motion.STILL && nowMs - lastOwnFixMs in 0 until STILL_SKIP_MAX_MS

/**
 * The report's `zone_watch.still` (qa-design-64-step2-code Q-3): with zones, a sync that re-sends
 * the cached fix ([action] `CACHED`) while [stillSinceOwnFix] says the phone hasn't moved tells the
 * server the fix is still current, so "Here now" counts from this report, not from the fix's own
 * time - the fix's time is never rewritten. A fresh fix is current anyway; without zones never.
 */
fun reportStill(zonesOn: Boolean, action: com.kidslauncher.mdm.server.LocationAction, motion: Motion, nowMs: Long, lastOwnFixMs: Long): Boolean =
    zonesOn && action == com.kidslauncher.mdm.server.LocationAction.CACHED && stillSinceOwnFix(motion, nowMs, lastOwnFixMs)

/** A mock fix's key for the count (Q-9): its time and place, so the same fix read again through
 * another provider or the passive feed counts once. */
fun mockKey(timeMs: Long, latitude: Double, longitude: Double): String = "$timeMs,$latitude,$longitude"

/** How many counted mock fixes' keys the phone remembers (Q-9). */
const val MOCK_KEYS_KEPT = 8

/** The mock count's step (Q-9): [key] already counted - `null` (nothing to count); else the keys
 * to keep, the newest [MOCK_KEYS_KEPT]. */
fun mockKeysAfter(recent: List<String>, key: String): List<String>? =
    if (key in recent) null else (recent + key).takeLast(MOCK_KEYS_KEPT)

/** Why a fix is or isn't kept. */
enum class FixVerdict { KEEP, MOCK, INACCURATE, NOT_NEWER }

/**
 * A fix the platform hands us (§4.3, decision 14): a mock fix (`Location.isMock`) counts for
 * nothing in a release build ([acceptMock] false) - [FixVerdict.MOCK], counted by the caller; the
 * debug build takes it (the emulator's test provider). [requireBetter] (the passive feed): also
 * refused without an accuracy, less accurate than [floorM], or not newer than the newest fix the
 * phone knows ([cachedTimeMs], `null` = none) - it then goes to the kept slot, never the cached
 * (reported) fix: only a due heartbeat (`KEPT`) or a crossing promotes it, so the trail stays as
 * dense as the parent's interval (Q-1).
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

// --- The moving series (§4.5): after a significant-motion fire, cheap checks timed by the distance ---

/** The mean Earth radius (IUGG), the server's `zones::EARTH_RADIUS_M`. */
const val EARTH_RADIUS_M = 6_371_008.8

/** A check comes at least this long after the last (§4.5). */
const val CHECK_MIN_DELAY_MS = 3 * 60_000L

/** ... and at most this long - the fence's own poll interval. */
const val CHECK_MAX_DELAY_MS = 30 * 60_000L

/** The speed the next check assumes: a car in town. */
const val CHECK_SPEED_MPS = 15.0

/** A series ends this long after it began, whatever the motion. */
const val SERIES_MAX_MS = 60 * 60_000L

/** ... and after this many checks (60 min / 3 min). */
const val SERIES_MAX_CHECKS = 20

/** The haversine distance in metres - only to time a check and to tell whether a fix is worth
 * sending now; the server alone decides a crossing (its rule counts accuracy and two fixes). */
fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lng2 - lng1)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h).coerceAtMost(1.0))
}

/** How far [fix] is from [zone]'s boundary, inside or out. */
fun boundaryDistanceM(fix: QueuedFix, zone: PolicyZone): Double =
    abs(distanceM(fix.latitude, fix.longitude, zone.latitude, zone.longitude) - zone.radiusM)

/**
 * When the series' next check is due, from now (§4.5): the time the phone needs at
 * [CHECK_SPEED_MPS] to reach the nearest zone boundary from the cached fix - the fix's accuracy
 * taken off the distance (it may be anywhere in its circle) and its age off the time (it may have
 * moved since) - within [CHECK_MIN_DELAY_MS]..[CHECK_MAX_DELAY_MS]. Far from every zone the checks
 * slow to the fence's own 30 min, inside a zone they time the way out the same. No zones: `null`,
 * no series. No cached fix: the shortest delay (where the phone is, is unknown).
 */
fun nextCheckDelay(cached: QueuedFix?, zones: List<PolicyZone>, nowMs: Long): Long? {
    if (zones.isEmpty()) return null
    if (cached == null) return CHECK_MIN_DELAY_MS
    val nearest = zones.minOf { boundaryDistanceM(cached, it) }
    val reach = (nearest - (cached.accuracy ?: 0f).coerceAtLeast(0f)).coerceAtLeast(0.0)
    val travelMs = (reach / CHECK_SPEED_MPS * 1000).toLong()
    val ageMs = (nowMs - cached.timeMs).coerceAtLeast(0)
    return (travelMs - ageMs).coerceIn(CHECK_MIN_DELAY_MS, CHECK_MAX_DELAY_MS)
}

/** A running series (memory, elapsed realtime): when it began, the last motion fire, the checks
 * done and the delay the next one was given. */
data class CheckSeries(val startedAtMs: Long, val lastMotionAtMs: Long, val checks: Int, val lastDelayMs: Long)

/** A motion fire: a new series whose first check is [firstDelayMs] away, or the running one told
 * the phone still moves. */
fun seriesOnMotion(series: CheckSeries?, nowMs: Long, firstDelayMs: Long): CheckSeries =
    series?.copy(lastMotionAtMs = nowMs) ?: CheckSeries(nowMs, nowMs, 0, firstDelayMs)

/** Why a series ends at a check's time - `null` when the check runs. */
enum class SeriesEnd { HOUR, COUNT, STILL }

/**
 * At a check's alarm (§4.5): the series ends after [SERIES_MAX_MS], after [SERIES_MAX_CHECKS]
 * checks, or when motion stopped - no new fire within twice the last delay (the sensor re-arms at
 * each fire while zones are watched, so a moving phone keeps firing).
 */
fun seriesEnd(series: CheckSeries, nowMs: Long): SeriesEnd? = when {
    nowMs - series.startedAtMs >= SERIES_MAX_MS -> SeriesEnd.HOUR
    series.checks >= SERIES_MAX_CHECKS -> SeriesEnd.COUNT
    nowMs - series.lastMotionAtMs > 2 * series.lastDelayMs -> SeriesEnd.STILL
    else -> null
}

/** After a check: one more, the next one [nextDelayMs] away. */
fun seriesAfterCheck(series: CheckSeries, nextDelayMs: Long): CheckSeries =
    series.copy(checks = series.checks + 1, lastDelayMs = nextDelayMs)

/** What a check's fix is worth now (§4.5, the note's rule). */
enum class CheckVerdict {
    /** Nothing changed: kept in the kept slot only (a due heartbeat's `KEPT` reports it). */
    KEEP,

    /** Within its accuracy of a boundary: sent now, so the server sees it in time. */
    NEAR_BOUNDARY,

    /** On the other side of a boundary than [previous]: sent now, and a confirming GPS fix 2 min later. */
    SIDE_CHANGED,
}

/**
 * A check's [fix] against the fix before it ([previous], the cached one; `null` = none): another
 * side of any zone's boundary (plain distance against the radius - a hint, never the server's
 * judgement) is [CheckVerdict.SIDE_CHANGED]; within the fix's accuracy of a boundary
 * [CheckVerdict.NEAR_BOUNDARY]; else [CheckVerdict.KEEP].
 */
fun checkVerdict(previous: QueuedFix?, fix: QueuedFix, zones: List<PolicyZone>): CheckVerdict {
    fun inside(f: QueuedFix, z: PolicyZone) = distanceM(f.latitude, f.longitude, z.latitude, z.longitude) <= z.radiusM
    if (previous != null && zones.any { inside(previous, it) != inside(fix, it) }) return CheckVerdict.SIDE_CHANGED
    val accuracy = (fix.accuracy ?: 0f).coerceAtLeast(0f)
    if (zones.any { boundaryDistanceM(fix, it) <= accuracy }) return CheckVerdict.NEAR_BOUNDARY
    return CheckVerdict.KEEP
}
