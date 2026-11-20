package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.timerules.RuleLifts
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.decideTimeLock
import com.kidslauncher.mdm.timerules.inForceAt
import com.kidslauncher.mdm.timerules.localAt
import com.kidslauncher.mdm.timerules.nextBoundary
import com.kidslauncher.mdm.timerules.untilInstants
import java.time.Instant
import java.time.ZoneId

/*
 * The music app's next suspension (design 54 R2, R3): the restriction `suspend_at_s` the music app sets the speaker's
 * own sleep timer from. Pure, unit-tested in MusicSuspendEdgeTest.
 */

/** The walk's bounds: at most this many boundaries, and no further ahead than [MUSIC_EDGE_HORIZON_MS]. */
const val MUSIC_EDGE_MAX_STEPS = 64
const val MUSIC_EDGE_HORIZON_MS = 8 * 24 * 3_600_000L

/** A rule lift with a known end: [ruleId] `null` lifts every rule; it lifts at an instant before [untilMs]. */
data class TimedLift(val ruleId: Long?, val untilMs: Long)

/** The lifts of [lifts] still lifting at [atMs]: one whose end is at or before [atMs] no longer lifts. */
fun ruleLiftsAt(lifts: List<TimedLift>, atMs: Long): RuleLifts {
    val live = lifts.filter { it.untilMs > atMs }
    return RuleLifts(all = live.any { it.ruleId == null }, ruleIds = live.mapNotNull { it.ruleId }.toSet())
}

/**
 * The first instant after [nowMs] at which [musicPkg] becomes suspended by a time rule, in epoch seconds (rounded up,
 * an Int: the bridge's store holds no Long, R1.10), 0 = none. It walks [nextBoundary] forward from [nowMs], with
 * [extra] (the lift and phone-pause expiries, the override's and pause's ends), the policy's `until_ms` instants and
 * [overrideEndMs]. At each boundary `b` it decides the lock with `policy.inForceAt(b)`, the lifts [liftsAt] gives for
 * `b`, the override while [overrideEndMs] is after `b`, and no budget: the budget's SCREEN_TIME lock keeps the music
 * app usable (R1.11), so it never gives an edge. The first `b` locked without [musicPkg] among the usable apps is the
 * edge. The walk stops after [MUSIC_EDGE_MAX_STEPS] boundaries or [MUSIC_EDGE_HORIZON_MS], whichever comes first.
 * 0 too when the music app is suspended already at [nowMs].
 */
fun musicSuspendEdgeS(
    policy: TimePolicy?,
    nowMs: Long,
    zone: ZoneId,
    musicPkg: String,
    liftsAt: (Long) -> RuleLifts,
    overrideEndMs: Long,
    extra: Collection<Instant>,
): Int {
    if (policy == null) return 0
    fun suspendedAt(atMs: Long): Boolean {
        val lock = decideTimeLock(policy.inForceAt(atMs), localAt(Instant.ofEpochMilli(atMs), zone), overrideEndMs > atMs, liftsAt(atMs))
        return lock.locked && musicPkg !in lock.usableApps
    }
    if (suspendedAt(nowMs)) return 0
    val instants = extra + untilInstants(policy, nowMs) + listOfNotNull(overrideEndMs.takeIf { it > nowMs }?.let { Instant.ofEpochMilli(it) })
    val horizonMs = nowMs + MUSIC_EDGE_HORIZON_MS
    var at = Instant.ofEpochMilli(nowMs)
    repeat(MUSIC_EDGE_MAX_STEPS) {
        val b = nextBoundary(policy, at, zone, instants) ?: return 0
        val bMs = b.toEpochMilli()
        if (bMs > horizonMs) return 0
        if (suspendedAt(bMs)) return Math.floorDiv(bMs + 999, 1000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        at = b
    }
    return 0
}
