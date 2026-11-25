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

/** Design 124 D5 amended 2026-11-25 (code QA M1): past the cap a failed try keeps the vouch, but at most this long since
 * the vouch (the last fresh fix or its renewal) - a sensor that misses a fire and tries that all fail can't vouch for
 * good. Then `QUIET`: the PWA shows the fix's own age. Provisional: the owner may change it. */
const val STILL_CEILING_MS = 24 * 60 * 60_000L

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
 * "interval" (the server sends zones only then). Idempotent - remove ours,
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
 * managed, Regularly **with zones**. The fences, the passive feed, the series and the queue hang
 * on this; the heartbeat and the motion gate on [stillWatchActive].
 */
fun zoneWatchActive(owner: Boolean, managed: Boolean, policy: com.kidslauncher.mdm.server.dto.LocationPolicy?): Boolean =
    stillWatchActive(owner, managed, policy) && policy?.zones.orEmpty().isNotEmpty()

/**
 * Design 124 D1: the still rule is on - device owner, managed, Regularly, zones or not. The heartbeat
 * ([heartbeatDue]), the beat's stamp and the backstop counting from it ([backstopAnchorMs]), the
 * motion gate, `still` ([reportStill]) and the balanced path hang on this; without it the location
 * step is today's `locationAction` ([syncLocationAction]).
 */
fun stillWatchActive(owner: Boolean, managed: Boolean, policy: com.kidslauncher.mdm.server.dto.LocationPolicy?): Boolean =
    owner && managed && policy?.mode == "interval"

/** The location step's action and, on a Regularly phone, the heartbeat's verdict (`null` = no
 * heartbeat ran) and the back-off it counted with (for the log line). */
data class SyncLocation(val action: com.kidslauncher.mdm.server.LocationAction, val beat: Heartbeat?, val backoffMs: Long = 0)

/**
 * The sync's location action (design 64 §4.4, design 124 D1). Without the still rule ([stillOn]
 * false: not Regularly, not owner or not managed) it is exactly `locationAction(policy, forced,
 * now - lastOwnFix)`. Regularly: a forced fix is the precise path for [precise] (`locate`, a
 * crossing's confirm) and the balanced one for a crossing's first fix; else [heartbeatDue] decides,
 * a `FIX` taking the balanced path. [lastOwnFixMs] is our last attempt (the back-off's last try),
 * [lastFreshFixMs] our last genuinely fresh fix (the still skip's 4 h, F-3), [stillSinceMs]
 * [MotionFlags.stillSinceMs], [capFails] the failed tries since the vouch's 4 h ran out ([stillCapFailsAfter]).
 */
fun syncLocationAction(
    policy: com.kidslauncher.mdm.server.dto.LocationPolicy?,
    stillOn: Boolean,
    forced: Boolean,
    precise: Boolean,
    nowMs: Long,
    lastOwnFixMs: Long,
    lastBeatMs: Long,
    motion: Motion,
    keptFixAgeMs: Long?,
    lastFreshFixMs: Long,
    stillSinceMs: Long = 0,
    capFails: Int = 0,
): SyncLocation {
    val today = com.kidslauncher.mdm.server.locationAction(policy, forced, nowMs - lastOwnFixMs)
    if (!stillOn || policy == null || policy.mode != "interval") return SyncLocation(today, null)
    if (forced) {
        val fresh = if (precise) com.kidslauncher.mdm.server.LocationAction.FRESH else com.kidslauncher.mdm.server.LocationAction.FRESH_BALANCED
        return SyncLocation(fresh, null)
    }
    val intervalMs = policy.intervalMinutes.coerceAtLeast(com.kidslauncher.mdm.server.MIN_LOCATION_INTERVAL_MINUTES) * 60_000L
    val beat = heartbeatDue(nowMs, lastBeatMs, intervalMs, motion, lastFreshFixMs, keptFixAgeMs, lastOwnFixMs, stillSinceMs, capFails)
    val action = if (beat == Heartbeat.FIX) com.kidslauncher.mdm.server.LocationAction.FRESH_BALANCED else com.kidslauncher.mdm.server.LocationAction.CACHED
    val backoffMs = if (motion == Motion.STILL && capFails > 0) capRetryMs(intervalMs, capFails) else stillBackoffMs(intervalMs, lastOwnFixMs, stillSinceMs)
    return SyncLocation(action, beat, backoffMs)
}

/** What the backstop counts the next fix from: the beat on a Regularly phone (a skip waits a whole
 * interval), our last own fix otherwise. */
fun backstopAnchorMs(stillOn: Boolean, lastBeatMs: Long, lastOwnFixMs: Long): Long = if (stillOn) lastBeatMs else lastOwnFixMs

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
    /** The fix's elapsed realtime and boot ([fixAgeMs]); unknown for a fix kept from before. */
    val elapsedMs: Long? = null,
    val bootCount: Int = -1,
    /** Design 131: what confirmed a Wi-Fi fix (`gnss`, `link`); carried by every re-send, never upgraded. */
    val check: String? = null,
    /** A satellite fix's satellites (the fix's extras; `null` unknown): a still vouch needs teaching quality. */
    val satellites: Int? = null,
) {
    fun sameFix(other: QueuedFix) = timeMs == other.timeMs && latitude == other.latitude && longitude == other.longitude
}

/** [fix]'s age at elapsed realtime [nowElapsedMs] in boot [nowBoot], for the server's `age_ms` (it stamps the fix at
 * receipt minus this, whatever the phone's wall clock says); `null` for a fix of another boot or with no elapsed time. */
fun fixAgeMs(fix: QueuedFix, nowElapsedMs: Long, nowBoot: Int): Long? =
    fix.elapsedMs?.takeIf { fix.bootCount >= 0 && fix.bootCount == nowBoot }?.let { (nowElapsedMs - it).coerceAtLeast(0) }

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

/** What the significant-motion sensor says (§4.5, design 124 D4): it fired since our last own fix
 * attempt, or we don't know (a new process, no anchor, a refused registration) - [MOVED]; it hasn't
 * since the last attempt but has since the last genuinely fresh fix, which that attempt didn't
 * replace - [STILL_SINCE_TRY]; it hasn't since the last fresh fix - [STILL]; the phone has none or
 * distrusts it (D7) - [NO_SENSOR]: the heartbeat always fixes, today's behaviour. */
enum class Motion { MOVED, STILL_SINCE_TRY, STILL, NO_SENSOR }

/** The heartbeat's call at a sync (§4.4). */
enum class Heartbeat {
    /** The interval hasn't passed since the last heartbeat: the cached fix, as before. */
    NOT_DUE,

    /** Due: a fresh fix of our own. */
    FIX,

    /** Due, but a kept fix (passive or a crossing's) is younger than the interval: report it,
     * no LocationManager call. */
    KEPT,

    /** Due, the phone hasn't moved since our last fresh fix and that was under 4 h ago - or the 4 h ran
     * out, the tries since failed, the next one isn't due ([capRetryMs]) and the vouch is under
     * [STILL_CEILING_MS] old: report the cached fix as still current, no LocationManager call. */
    STILL,

    /** Due, the sensor fired since our last fresh fix but not since our last try (which failed) and the
     * back-off ([stillBackoffMs]) hasn't passed - or it hasn't fired, the vouch is past [STILL_CEILING_MS]
     * and the next try isn't due: the cached fix with its own age - no `still`, no LocationManager call
     * (design 124 D5). */
    QUIET,
}

/**
 * The Regularly heartbeat (design 64 decision 11, design 124 D5): due when [intervalMs] passed since
 * the last heartbeat ([lastBeatMs], wall clock; the clock set back counts as due). Due, it takes no
 * fix when a kept fix **not yet reported** is younger than the interval ([keptFixAgeMs]: the kept
 * slot's fix, zones only; our own reported fixes never count, Q-4) - `KEPT`; when [motion] says the
 * phone hasn't moved since our last genuinely fresh fix ([lastFreshFixMs]) and that is under
 * [STILL_SKIP_MAX_MS] old - `STILL`. Past the 4 h with no fire (amended 2026-11-25): a failed try is
 * no evidence of a move, so after [capFails] failed tries `STILL` goes on until the next one is due
 * ([capRetryMs] from [lastTryMs]) - then `FIX`; the first try at the cap is due at once; past
 * [STILL_CEILING_MS] from the vouch the waits are `QUIET` (code QA M1). A try killed mid-attempt stamped
 * [lastTryMs] but counted nothing: the next waits the same step again (code QA L5). When it fired
 * since the fresh fix but not since our last try ([lastTryMs], `STILL_SINCE_TRY`) and the back-off
 * ([stillBackoffMs] from [stillSinceMs]) hasn't passed since it - `QUIET`. Each due call counts as a
 * heartbeat (the caller stamps it), so a skip waits a whole interval, never the backstop's 9-minute
 * floor. A move (a fire) always gives a try at the next due beat.
 */
fun heartbeatDue(
    nowMs: Long,
    lastBeatMs: Long,
    intervalMs: Long,
    motion: Motion,
    lastFreshFixMs: Long,
    keptFixAgeMs: Long?,
    lastTryMs: Long,
    stillSinceMs: Long,
    capFails: Int = 0,
): Heartbeat {
    val since = nowMs - lastBeatMs
    if (since in 0 until intervalMs) return Heartbeat.NOT_DUE
    if (keptFixAgeMs != null && keptFixAgeMs in 0 until intervalMs) return Heartbeat.KEPT
    if (stillSinceOwnFix(motion, nowMs, lastFreshFixMs)) return Heartbeat.STILL
    if (motion == Motion.STILL) {
        val waiting = capFails > 0 && nowMs >= lastFreshFixMs && nowMs - lastTryMs in 0 until capRetryMs(intervalMs, capFails)
        return when {
            !waiting -> Heartbeat.FIX
            nowMs - lastFreshFixMs < STILL_CEILING_MS -> Heartbeat.STILL
            else -> Heartbeat.QUIET
        }
    }
    if (motion == Motion.STILL_SINCE_TRY && nowMs - lastTryMs in 0 until stillBackoffMs(intervalMs, lastTryMs, stillSinceMs)) return Heartbeat.QUIET
    return Heartbeat.FIX
}

/**
 * Design 124 D5, amended 2026-11-25: how long `STILL` waits after the [fails]-th failed try since the
 * vouch's 4 h ran out (no fire meanwhile) before the next try - the next two beats (one interval
 * each), then 1 h, 2 h, and every [STILL_SKIP_MAX_MS] after; never under the interval. A cap try
 * failed at 15:32 on a 10-min interval tries again at 15:42, 15:52, 16:52, 18:52, then every 4 h.
 */
fun capRetryMs(intervalMs: Long, fails: Int): Long = when {
    fails <= 2 -> intervalMs
    fails == 3 -> 60 * 60_000L
    fails == 4 -> 2 * 60 * 60_000L
    else -> STILL_SKIP_MAX_MS
}.coerceAtLeast(intervalMs)

/**
 * The failed tries since the vouch's 4 h ran out, after an attempt ([fails] before it; CE prefs
 * `zone_watch` `still_cap_fails_v1`): a genuinely fresh fix ([fresh]) or a renewal ([renewed]) of the
 * vouch - none; the heartbeat's own `FIX` while [motion] was `STILL` (so a try at or past the cap) that
 * failed with no fire during it ([held]) - one more; else as before. A fire needs a fresh fix before
 * the motion is `STILL` again, so it starts the count over too.
 */
fun stillCapFailsAfter(fails: Int, beat: Heartbeat?, motion: Motion, fresh: Boolean, renewed: Boolean, held: Boolean): Int = when {
    fresh || renewed -> 0
    beat == Heartbeat.FIX && motion == Motion.STILL && held -> fails + 1
    else -> fails
}

/**
 * The phone may say its reported fix is still current ([reportStill], the heartbeat's `STILL`): the
 * sensor hasn't fired since the vouch ([stillFromMs], the last fresh fix or its renewal) and the vouch
 * is under 4 h old ([stillSinceOwnFix]) - or older but under [STILL_CEILING_MS], the tries since failed
 * ([capFails] > 0; amended 2026-11-25: a failed try is no evidence of a move) and the fix it re-sends is
 * the vouch itself ([cachedIsVouch]: never a promoted kept fix or a fallback, code QA L4).
 */
fun stillVouched(motion: Motion, nowMs: Long, stillFromMs: Long, capFails: Int, cachedIsVouch: Boolean = true): Boolean =
    stillSinceOwnFix(motion, nowMs, stillFromMs) ||
        (motion == Motion.STILL && capFails > 0 && cachedIsVouch && nowMs - stillFromMs in 0 until STILL_CEILING_MS)

/**
 * Code QA H1: what a cap try (the heartbeat's `FIX` while the motion is `STILL`) takes from its attempt -
 * the fix it asked for only when genuinely fresh ([fixTimeMs] at [requestedAtMs], [freshOwnFix]) and not
 * one the Wi-Fi places call not fresh ([notFresh]: an unchecked or fallback learned fix, a cell fix), or
 * the renewed vouch ([renewed]); `null` [fixTimeMs] = none. Anything else - and never a last-known
 * fallback - leaves the vouch the cached fix.
 */
fun capTryTakes(fixTimeMs: Long?, requestedAtMs: Long, notFresh: Boolean, renewed: Boolean): Boolean =
    renewed || (fixTimeMs != null && !notFresh && freshOwnFix(fixTimeMs, requestedAtMs))

/**
 * Code QA H1: a cap try that took nothing ([capTryTakes] false) re-sends the vouch with `still` when the
 * cached fix is the vouch ([cachedTimeMs] = [vouchTimeMs], the last fresh fix's time), no fire came during
 * the attempt ([held]) and [stillVouched] holds with the count after it ([vouched]); else it reports no fix
 * (P-L3: an old fix is never sent without `still`).
 */
fun capTryResends(cachedTimeMs: Long?, vouchTimeMs: Long, held: Boolean, vouched: Boolean): Boolean =
    held && vouched && cachedTimeMs == vouchTimeMs

/**
 * Design 124 D5: how long the heartbeat waits after a failed try once the sensor fired since the last
 * fresh fix (`STILL_SINCE_TRY`; past the cap while `STILL` it is [capRetryMs] since 2026-11-25) - as
 * long as it had already been still at that try ([lastTryMs] - [stillSinceMs]), at least the interval,
 * at most [STILL_SKIP_MAX_MS]. After a move at 20:10, failures from 20:30 try at 20:30, 21:00, 21:30,
 * 22:30, 00:30, 04:30.
 */
fun stillBackoffMs(intervalMs: Long, lastTryMs: Long, stillSinceMs: Long): Long =
    (lastTryMs - stillSinceMs).coerceAtMost(STILL_SKIP_MAX_MS).coerceAtLeast(intervalMs)

/** The motion sensor hasn't fired since our last genuinely fresh fix ([lastFreshFixMs]), and that
 * fix is under [STILL_SKIP_MAX_MS] old: the phone is still where the reported fix says. */
fun stillSinceOwnFix(motion: Motion, nowMs: Long, lastFreshFixMs: Long): Boolean =
    motion == Motion.STILL && nowMs - lastFreshFixMs in 0 until STILL_SKIP_MAX_MS

/**
 * The report's `zone_watch.still` (qa-design-64-step2-code Q-3, design 124 D1): on a Regularly phone,
 * zones or not, a sync that re-sends the cached fix ([action] `CACHED`) while [stillVouched] says
 * the phone hasn't moved tells the server the fix is still current, so "Here now" and "Hasn't moved
 * since" count from this report - the fix's own time is never rewritten. A fresh fix is current
 * anyway; never without the still rule ([stillOn]).
 */
fun reportStill(
    stillOn: Boolean,
    action: com.kidslauncher.mdm.server.LocationAction,
    motion: Motion,
    nowMs: Long,
    lastFreshFixMs: Long,
    capFails: Int = 0,
    cachedIsVouch: Boolean = true,
): Boolean = stillOn && action == com.kidslauncher.mdm.server.LocationAction.CACHED &&
    stillVouched(motion, nowMs, lastFreshFixMs, capFails, cachedIsVouch)

/**
 * The motion gate's state (design 124 D3), in memory only: a new process starts moved.
 * [movedSinceFresh]: the trigger fired (or we don't know) since our last genuinely fresh fix;
 * [movedSinceTry]: since our last heartbeat try; [fires] counts every fire and mark, so an attempt
 * compares it with the count read before its LocationManager call (M1: a fire during the attempt is
 * never erased); [stillSinceMs]: when the phone last became still - a fresh fix's time, or the first
 * failed try after a move -, what [stillBackoffMs] counts from; [movingAtFresh]: the last fresh fix
 * says the phone was moving ([fixMoving]) - a car the sensor may not hear, so no `STILL` or `QUIET`
 * until a fresh fix at rest; [movedAtElapsedMs]: when (elapsed realtime) the last fire or mark came - design 125 D9
 * uses no scan result from before it.
 */
data class MotionFlags(
    val movedSinceFresh: Boolean = true,
    val movedSinceTry: Boolean = true,
    val fires: Int = 0,
    val stillSinceMs: Long = 0,
    val movingAtFresh: Boolean = false,
    val movedAtElapsedMs: Long = 0,
) {
    /** A fire, or other evidence of a move (a crossing, a side change, Off, the anchor gone), at [atElapsedMs]. */
    fun moved(atElapsedMs: Long): MotionFlags =
        copy(movedSinceFresh = true, movedSinceTry = true, fires = fires + 1, movedAtElapsedMs = atElapsedMs)

    /**
     * After one of our own attempts begun at [firesAtStart]: with a fire since, nothing clears. A
     * genuinely fresh fix ([freshFixMs], its time; `null` = none) clears both, whatever asked for it,
     * and [moving] says whether it was taken on the move. A failure clears [movedSinceTry] only after
     * the heartbeat's own `FIX` ([heartbeat]; L7: a failed `locate`, low-battery or crossing fix
     * clears nothing) - the phone still from [nowMs] when it had moved before.
     */
    fun afterTry(firesAtStart: Int, freshFixMs: Long?, heartbeat: Boolean, nowMs: Long, moving: Boolean = false): MotionFlags = when {
        fires != firesAtStart -> this
        freshFixMs != null -> copy(movedSinceFresh = false, movedSinceTry = false, stillSinceMs = freshFixMs, movingAtFresh = moving)
        !heartbeat || !movedSinceTry -> this
        else -> copy(movedSinceTry = false, stillSinceMs = nowMs)
    }

    /** The [Motion]: [sensor] - the phone has one and trusts it (D7); [anchor] - the anchor service
     * runs, which keeps our UID out of the idle state the sensor service skips (D4, D8). A last fresh
     * fix on the move is `MOVED` whatever the trigger said: every due beat fixes. */
    fun motion(sensor: Boolean, anchor: Boolean): Motion = when {
        !sensor -> Motion.NO_SENSOR
        !anchor || movedSinceTry || movingAtFresh -> Motion.MOVED
        movedSinceFresh -> Motion.STILL_SINCE_TRY
        else -> Motion.STILL
    }
}

/** A fresh fix this fast is on the move (design 124, the moving car): walking pace is about 1.4 m/s. */
const val MOVING_SPEED_MPS = 1.5f

/** Without a speed, a fresh fix further than this and both accuracies from the last one is on the move. */
const val MOVING_SLACK_M = 200.0

/**
 * Design 124, the moving car: Android counts "sitting in a moving car" as significant motion, but a
 * vendor's sensor may miss a smooth drive once re-armed mid-trip, and `STILL` would then vouch for a
 * fix on the road for up to 4 h. A genuinely fresh fix ([fix]) says the phone is moving when its speed
 * ([speedMps], `null` = none) is over [MOVING_SPEED_MPS]; without a speed, when it lies further than
 * [MOVING_SLACK_M] and both accuracies from the previous fresh fix ([previousFresh]) taken at most
 * [windowMs] (the interval) before it.
 */
fun fixMoving(speedMps: Float?, fix: QueuedFix, previousFresh: QueuedFix?, windowMs: Long): Boolean {
    if (speedMps != null && speedMps.isFinite()) return speedMps > MOVING_SPEED_MPS
    val previous = previousFresh ?: return false
    if (fix.timeMs - previous.timeMs !in 0..windowMs) return false
    fun accuracy(q: QueuedFix) = q.accuracy?.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val slack = MOVING_SLACK_M + accuracy(previous) + accuracy(fix)
    return distanceM(previous.latitude, previous.longitude, fix.latitude, fix.longitude) > slack
}


/** What an attempt reads before its LocationManager call, together (M1): the motion, the fire count
 * it compares after, [MotionFlags.stillSinceMs] for the back-off and [MotionFlags.movedAtElapsedMs] for design 125's
 * scan rule. */
data class MotionSnapshot(val motion: Motion, val fires: Int, val stillSinceMs: Long, val movedAtElapsedMs: Long = 0)

/** A sensor caught lying is distrusted this long, and two contradictions count within it (D7). */
const val MOTION_DISTRUST_MS = 7 * 24 * 60 * 60_000L

/** A contradiction needs both fixes at least this accurate (D7): a cell fix proves nothing. */
const val CONTRADICTION_ACCURACY_MAX_M = 100f

/** ... and them further apart than both accuracies and this. */
const val CONTRADICTION_SLACK_M = 200.0

/**
 * Design 124 D7: a genuinely fresh own fix ([fresh]) taken while the gate said `STILL` contradicts
 * the sensor when it lies further from the vouched fix ([vouched], the reported one) than both
 * accuracies and [CONTRADICTION_SLACK_M], both accuracies known and within
 * [CONTRADICTION_ACCURACY_MAX_M].
 */
fun motionContradiction(vouched: QueuedFix?, fresh: QueuedFix): Boolean {
    fun good(accuracy: Float?) = accuracy?.takeIf { it.isFinite() && it >= 0f && it <= CONTRADICTION_ACCURACY_MAX_M }
    val old = vouched ?: return false
    val a = good(old.accuracy) ?: return false
    val b = good(fresh.accuracy) ?: return false
    return distanceM(old.latitude, old.longitude, fresh.latitude, fresh.longitude) > a + b + CONTRADICTION_SLACK_M
}

/** The sensor's record after a contradiction (D7): the instants of the last 7 days', and at the
 * second the distrust's end ([distrustUntilMs]; 0 = none) - the list then starts over. */
data class MotionTrust(val contradictions: List<Long>, val distrustUntilMs: Long)

fun trustAfterContradiction(recent: List<Long>, nowMs: Long): MotionTrust {
    val kept = recent.filter { nowMs - it in 0 until MOTION_DISTRUST_MS } + nowMs
    return if (kept.size >= 2) MotionTrust(emptyList(), nowMs + MOTION_DISTRUST_MS) else MotionTrust(kept, 0)
}

/** Distrusted until [untilMs] - never longer than [MOTION_DISTRUST_MS] from now, whatever the clock did. */
fun motionDistrusted(untilMs: Long, nowMs: Long): Boolean = untilMs - nowMs in 1..MOTION_DISTRUST_MS

/** Design 124 D9: the balanced fix is enough - its accuracy known and within
 * [PASSIVE_ACCURACY_FLOOR_M] (a rural cell fix can be kilometres); else GPS is asked once. */
fun balancedEnough(accuracyM: Float?): Boolean = accuracyM != null && accuracyM.isFinite() && accuracyM <= PASSIVE_ACCURACY_FLOOR_M

/** D9's keep-the-better: the GPS fix ([gpsM]) replaces the balanced one ([balancedM]) only with an
 * accuracy of its own better than the balanced one's (a missing accuracy is the worst). */
fun gpsBetter(balancedM: Float?, gpsM: Float?): Boolean =
    gpsM != null && gpsM.isFinite() && (balancedM == null || !balancedM.isFinite() || gpsM < balancedM)

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

/**
 * After a series ends, the motion sensor starts no new one for this long (qa-design-64-step2-code
 * F-2, the coordinator's decision); a fence crossing still starts one at once. Provisional: the
 * Jelly Star walk's measurement decides it (design 64 §5).
 */
const val SERIES_COOLDOWN_MS = 20 * 60_000L

/** At most this many series checks a local calendar day (F-2); past it only the fences and the
 * heartbeat run. Provisional, as [SERIES_COOLDOWN_MS]. */
const val SERIES_CHECKS_PER_DAY = 60

/** The series checks used on [day] (a local date, `yyyy-mm-dd`). */
@kotlinx.serialization.Serializable
data class CheckBudget(val day: String, val used: Int)

/** Series checks left [today] ([SERIES_CHECKS_PER_DAY] on a new day). */
fun checksLeft(budget: CheckBudget?, today: String): Int =
    if (budget?.day == today) (SERIES_CHECKS_PER_DAY - budget.used).coerceAtLeast(0) else SERIES_CHECKS_PER_DAY

/** The budget after one more check [today]. */
fun budgetAfterCheck(budget: CheckBudget?, today: String): CheckBudget =
    if (budget?.day == today) budget.copy(used = budget.used + 1) else CheckBudget(today, 1)

/**
 * Whether a series may start now (F-2): never with no check left today; a fence crossing
 * ([fromFence]) at once; a motion fire only [SERIES_COOLDOWN_MS] after the last series ended
 * ([lastEndedAtMs], elapsed; `null` = none in this process, a negative gap - a reboot - counts as
 * past it).
 */
fun seriesMayStart(fromFence: Boolean, nowMs: Long, lastEndedAtMs: Long?, checksLeft: Int): Boolean = when {
    checksLeft <= 0 -> false
    fromFence || lastEndedAtMs == null -> true
    else -> nowMs - lastEndedAtMs !in 0 until SERIES_COOLDOWN_MS
}

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
enum class SeriesEnd { HOUR, COUNT, STILL, BUDGET }

/**
 * At a check's alarm (§4.5): the series ends after [SERIES_MAX_MS], after [SERIES_MAX_CHECKS]
 * checks, or when motion stopped - no new fire within twice the last delay (the sensor re-arms at
 * each fire while zones are watched, so a moving phone keeps firing); and at once with no check
 * left today ([checksLeft], F-2's budget).
 */
fun seriesEnd(series: CheckSeries, nowMs: Long, checksLeft: Int = SERIES_CHECKS_PER_DAY): SeriesEnd? = when {
    checksLeft <= 0 -> SeriesEnd.BUDGET
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
    /** Nothing the server could use changed: kept in the kept slot only (a due heartbeat's `KEPT`
     * reports it). */
    KEEP,

    /** Surely on the other side of a boundary than [previous]: sent now, and a confirming GPS fix
     * 2 min later. */
    SIDE_CHANGED,
}

/**
 * A fix's side of [zone] by the server's own rule (decision 2): inside when `d + a <= r`, outside
 * when `d - a >= r`, `null` (unsure - it counts for neither side) otherwise and always without an
 * accuracy. Design 125b C10, a guard: a coarse fix ([COARSE_PROVIDERS], a cell's) is never inside and outside only
 * when `d - r >= max(3 x a, 20 km)` - the server's `zones::coarse_side` - so no later path starts a crossing sync on a
 * near cell fix (today only the balanced fused path reaches here, which a cell fix never takes).
 */
fun sureSide(fix: QueuedFix, zone: PolicyZone): Boolean? {
    val accuracy = fix.accuracy?.takeIf { it.isFinite() && it >= 0f } ?: return null
    val d = distanceM(fix.latitude, fix.longitude, zone.latitude, zone.longitude)
    if (fix.provider in COARSE_PROVIDERS) {
        return if (d - zone.radiusM >= maxOf(3.0 * accuracy, COARSE_OUTSIDE_MIN_M)) false else null
    }
    return when {
        d + accuracy <= zone.radiusM -> true
        d - accuracy >= zone.radiusM -> false
        else -> null
    }
}

/** Design 125b C10: the providers of a coarse fix (the server's `zones::COARSE_PROVIDERS`). */
val COARSE_PROVIDERS = setOf("vibb_cell", "vibb_apple_cell")

/** Design 125b C10: a coarse fix is surely outside only this far past a zone's edge at the least. */
const val COARSE_OUTSIDE_MIN_M = 20_000.0

/**
 * A check's [fix] against the reported fix ([previous]; `null` = none) - qa-design-64-step2-code
 * F-1: sent only when the server can use it - the new fix [sureSide] of some zone, and on the
 * other side than [previous] (its sure side, or for an unsure one its plain distance against the
 * radius) - [CheckVerdict.SIDE_CHANGED]. An unsure fix, one without accuracy or no previous fix
 * never sends: [CheckVerdict.KEEP].
 */
fun checkVerdict(previous: QueuedFix?, fix: QueuedFix, zones: List<PolicyZone>): CheckVerdict {
    if (previous == null) return CheckVerdict.KEEP
    fun before(z: PolicyZone) = sureSide(previous, z)
        ?: (distanceM(previous.latitude, previous.longitude, z.latitude, z.longitude) <= z.radiusM)
    val changed = zones.any { z -> sureSide(fix, z)?.let { it != before(z) } == true }
    return if (changed) CheckVerdict.SIDE_CHANGED else CheckVerdict.KEEP
}
