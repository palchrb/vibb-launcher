package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable

/*
 * The phone's own clock for decisions (time rules keep their own clock) - pure, unit-tested in TrustedClockTest against
 * `clock_rules.json`. The Android side is [TrustedClock]. Within a boot the time is an anchor plus elapsed realtime,
 * which nothing on the phone can set; an anchor comes from the server's answers over a connection the phone already
 * trusts, or from the satellite time a location fix gave. Across a reboot the last known offset is carried.
 */

/** How the phone knows its time now, strongest first. The wire word is the status report's `clock_trust`. */
enum class ClockTrust(val wire: String, val rank: Int) {
    /** A server answer's `Date` over a trusted connection, plus elapsed realtime since. */
    SERVER("server", 3),

    /** The satellite time of a location fix, plus elapsed realtime since. */
    GNSS("gnss", 2),

    /** After a reboot, before either: the last known offset carried over. */
    CARRIED("carried", 1),

    /** Never anchored (before the enrolment's first sync): the wall clock. */
    NONE("none", 0),
    ;

    companion object {
        fun of(wire: String?): ClockTrust = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/** A known time [ms] at elapsed realtime [elapsedMs] in boot [bootCount], learned from [kind] (a [ClockTrust] wire). */
@Serializable
data class ClockAnchor(val kind: String, val ms: Long, val elapsedMs: Long, val bootCount: Int) {
    val trust: ClockTrust get() = ClockTrust.of(kind)
}

/**
 * What the phone keeps (device-protected storage): the [anchor] in force, the last [checkpointMs] of the trusted time
 * with the wall clock's offset from it then ([checkpointSkewMs], wall minus trusted), the trusted time of the last
 * server or satellite anchor ([lastStrongMs]) and the offset last reported ([reportedSkewMs], for the steps'
 * hysteresis).
 */
@Serializable
data class TrustedClockState(
    val anchor: ClockAnchor? = null,
    val checkpointMs: Long? = null,
    val checkpointSkewMs: Long = 0,
    val lastStrongMs: Long? = null,
    val reportedSkewMs: Long? = null,
    /** The last satellite anchor (any boot; only this boot's counts): a server anchor too far from it is refused. */
    val gnss: ClockAnchor? = null,
    /** The carried anchor of this boot started behind the checkpoint (the wall clock was behind it: a clock that lost
     * its time while off): the boot's first forward wall-clock set is taken once ([timeSetAnchor]). */
    val carriedOpen: Boolean = false,
)

/** The time now ([ms]) and how it is known ([trust]); [state] is the state to keep when it changed (a carried anchor
 * made at the first read of a boot), else `null`. */
data class ClockRead(val ms: Long, val trust: ClockTrust, val state: TrustedClockState?)

/** An answer whose round trip took longer than this gives no anchor. */
const val SERVER_ANCHOR_MAX_RTT_MS = 30_000L

/** `Date` has a one-second resolution: the middle of its second. */
const val DATE_RESOLUTION_HALF_MS = 500L

/** Two anchors further apart than this are logged. */
const val ANCHOR_DISAGREEMENT_LOG_MS = 2 * 60_000L

/** The offset from the wall clock the report rounds to (and below which it says 0). */
const val SKEW_STEP_MS = 5 * 60_000L

/** A reported step changes only once the raw offset is this far past the step's edge. */
const val SKEW_HYSTERESIS_MS = 60_000L

/** A change of the wall clock's offset larger than this (`TIME_SET`) asks for a sync. */
const val SKEW_CHANGE_SYNC_MS = 5 * 60_000L

/** The time since the last strong anchor is reported in whole hours. */
const val UNANCHORED_UNIT_MS = 60 * 60_000L

/** An anchor still counts in this boot: the same boot count and elapsed realtime not behind it. */
fun anchorInBoot(anchor: ClockAnchor?, elapsedMs: Long, bootCount: Int): Boolean =
    anchor != null && anchor.bootCount == bootCount && elapsedMs >= anchor.elapsedMs

/** [anchor]'s time at elapsed realtime [elapsedMs]. */
fun anchoredMs(anchor: ClockAnchor, elapsedMs: Long): Long = anchor.ms + (elapsedMs - anchor.elapsedMs)

/**
 * The trusted time now. An anchor of this boot gives anchor plus elapsed, whatever the wall clock says. Without one, a
 * phone that was ever anchored carries its time over a reboot: `max(wall - last offset, last checkpoint)` at this
 * elapsed, kept as a [ClockTrust.CARRIED] anchor - from then on a wall clock set in this boot moves nothing. A phone
 * never anchored reads the wall clock.
 */
fun readClock(state: TrustedClockState, wallMs: Long, elapsedMs: Long, bootCount: Int): ClockRead {
    val anchor = state.anchor
    if (anchor != null && anchorInBoot(anchor, elapsedMs, bootCount)) {
        return ClockRead(anchoredMs(anchor, elapsedMs), anchor.trust, null)
    }
    val checkpoint = state.checkpointMs ?: return ClockRead(wallMs, ClockTrust.NONE, null)
    val fromWall = wallMs - state.checkpointSkewMs
    val carried = maxOf(fromWall, checkpoint)
    val next = state.copy(
        anchor = ClockAnchor(ClockTrust.CARRIED.wire, carried, elapsedMs, bootCount),
        carriedOpen = fromWall < checkpoint,
    )
    return ClockRead(carried, ClockTrust.CARRIED, next)
}

/**
 * A wall-clock set (`TIME_SET`) on a carried time that started behind the checkpoint ([TrustedClockState.carriedOpen]):
 * the clock lost its time while the phone was off, and automatic time is putting it right. The boot's first set that
 * moves the time forward is taken - `wall - last offset` as the new carried anchor - and the carried time is then fixed
 * for the boot. Anything else (another trust, a set backwards, a second set) changes nothing: `null`.
 */
fun timeSetAnchor(state: TrustedClockState, wallMs: Long, elapsedMs: Long, bootCount: Int): TrustedClockState? {
    val anchor = state.anchor ?: return null
    if (!state.carriedOpen || anchor.trust != ClockTrust.CARRIED || !anchorInBoot(anchor, elapsedMs, bootCount)) return null
    val fromWall = wallMs - state.checkpointSkewMs
    if (fromWall <= anchoredMs(anchor, elapsedMs)) return null
    return state.copy(anchor = ClockAnchor(ClockTrust.CARRIED.wire, fromWall, elapsedMs, bootCount), carriedOpen = false)
}

/**
 * An answer from the server as an anchor: its `Date` ([dateMs]) at the middle of the round trip from elapsed [e0Ms] to
 * [e1Ms], plus half of `Date`'s second. None without a `Date`, over a connection the phone doesn't already trust
 * ([trustedConnection]), when the server says its own clock is off ([clockOff]), or after a round trip over
 * [SERVER_ANCHOR_MAX_RTT_MS].
 */
fun serverAnchor(
    dateMs: Long?,
    e0Ms: Long,
    e1Ms: Long,
    bootCount: Int,
    trustedConnection: Boolean,
    clockOff: Boolean,
): ClockAnchor? {
    if (dateMs == null || !trustedConnection || clockOff) return null
    val rtt = e1Ms - e0Ms
    if (rtt < 0 || rtt > SERVER_ANCHOR_MAX_RTT_MS) return null
    return ClockAnchor(ClockTrust.SERVER.wire, dateMs + DATE_RESOLUTION_HALF_MS + rtt / 2, e1Ms, bootCount)
}

/** A connection whose answers may anchor: TLS that passes the built-in check, the tailnet, TLS Android validated while
 * the phone's certificate check isn't enforced ([pinsEnforced] false: a server whose issuer isn't in the built-in set,
 * or the parent's off - with it on, such a connection never gets this far), or a debug build's plain http to the
 * emulator's host (never in a release build). */
fun anchorConnection(connection: PinConnection, debug: Boolean, pinsEnforced: Boolean): Boolean = when (connection) {
    PinConnection.PASSED, PinConnection.TAILNET -> true
    PinConnection.NONE -> debug
    PinConnection.FAILED -> !pinsEnforced
}

/** A server anchor and this boot's satellite one further apart than this: the server's is refused. */
const val SERVER_GNSS_MAX_APART_MS = 2 * 60_000L

/** [taken] the state with [anchor] considered; [disagreementMs] how far it is from the anchor in force (null without
 * one), for the log. */
data class AnchorTaken(val state: TrustedClockState, val taken: Boolean, val disagreementMs: Long?)

/**
 * Considers a new [anchor] (taken at its own elapsed time). It replaces the one in force when that one is from another
 * boot, or is of the same or a weaker kind; a weaker one is only compared. A server or satellite anchor, taken or not,
 * marks the time as checked ([TrustedClockState.lastStrongMs]). The checkpoint follows. A server anchor more than
 * [SERVER_GNSS_MAX_APART_MS] from this boot's satellite anchor is refused outright: nothing changes.
 */
fun takeAnchor(state: TrustedClockState, anchor: ClockAnchor, wallMs: Long, elapsedMs: Long, bootCount: Int): AnchorTaken {
    val current = state.anchor?.takeIf { anchorInBoot(it, elapsedMs, bootCount) }
    val disagreement = current?.let { anchoredMs(it, anchor.elapsedMs) - anchor.ms }
    val gnss = state.gnss?.takeIf { anchorInBoot(it, anchor.elapsedMs, bootCount) }
    if (anchor.trust == ClockTrust.SERVER && gnss != null &&
        kotlin.math.abs(anchoredMs(gnss, anchor.elapsedMs) - anchor.ms) > SERVER_GNSS_MAX_APART_MS
    ) {
        return AnchorTaken(state, false, disagreement)
    }
    val replace = current == null || anchor.trust.rank >= current.trust.rank
    val inForce = if (replace) anchor else current!!
    val nowMs = anchoredMs(inForce, elapsedMs)
    val strong = anchor.trust == ClockTrust.SERVER || anchor.trust == ClockTrust.GNSS
    val next = state.copy(
        anchor = inForce,
        checkpointMs = nowMs,
        checkpointSkewMs = wallMs - nowMs,
        lastStrongMs = if (strong) nowMs else state.lastStrongMs,
        gnss = if (anchor.trust == ClockTrust.GNSS) anchor else state.gnss,
        carriedOpen = state.carriedOpen && !replace,
    )
    return AnchorTaken(next, replace, disagreement)
}

/** The checkpoint at a read: the trusted time and the wall clock's offset from it. Only with a trust to carry. */
fun checkpointAt(state: TrustedClockState, read: ClockRead, wallMs: Long): TrustedClockState = when {
    read.trust == ClockTrust.NONE -> state
    // While the carried time is open the wall clock's offset is unknown: a checkpoint that runs after automatic time set
    // the clock but before its `TIME_SET` arrives would otherwise make that set look like no move (gate 6bf (j)).
    state.carriedOpen && read.trust == ClockTrust.CARRIED -> state.copy(checkpointMs = read.ms)
    else -> state.copy(checkpointMs = read.ms, checkpointSkewMs = wallMs - read.ms)
}

/** The band of offsets a reported step stands for: 0 is `[-5, 5]` min, a step `s` is `[s, s + 5)` min on its side. */
private fun band(step: Long): LongRange = when {
    step == 0L -> -SKEW_STEP_MS..SKEW_STEP_MS
    step > 0 -> step until step + SKEW_STEP_MS
    else -> (step - SKEW_STEP_MS + 1)..step
}

/** The raw offset as a step: 0 within 5 minutes, else whole 5-minute steps towards zero, on its side. */
fun skewStep(rawMs: Long): Long =
    if (kotlin.math.abs(rawMs) <= SKEW_STEP_MS) 0L else (if (rawMs < 0) -1L else 1L) * (kotlin.math.abs(rawMs) / SKEW_STEP_MS) * SKEW_STEP_MS

/**
 * The status report's `clock_skew_ms` (wall minus trusted): [skewStep]'s step, but a [previous] step changes only once
 * the raw offset is [SKEW_HYSTERESIS_MS] past its band - so a heartbeat's hash stays the same while the clock wobbles
 * on an edge.
 */
fun reportedSkew(rawMs: Long, previous: Long?): Long {
    previous ?: return skewStep(rawMs)
    val b = band(previous)
    val outside = rawMs < b.first - SKEW_HYSTERESIS_MS || rawMs > b.last + SKEW_HYSTERESIS_MS
    return if (outside) skewStep(rawMs) else previous
}

/** The report's `unanchored_ms`: the time since the last server or satellite anchor, in whole hours; null when there
 * never was one. */
fun unanchoredMs(state: TrustedClockState, nowMs: Long): Long? =
    state.lastStrongMs?.let { ((nowMs - it).coerceAtLeast(0) / UNANCHORED_UNIT_MS) * UNANCHORED_UNIT_MS }

/** A wall-clock change (`TIME_SET`) moved the offset by more than [SKEW_CHANGE_SYNC_MS]: a sync gets a fresh anchor and
 * tells the parent. */
fun skewChangeAsksSync(previousSkewMs: Long, skewMs: Long): Boolean =
    kotlin.math.abs(skewMs - previousSkewMs) > SKEW_CHANGE_SYNC_MS

/** One edge for the boundary alarm: the instant [edgeMs] in the clock its decision used, and that clock's now. */
data class AlarmEdge(val edgeMs: Long, val clockNowMs: Long)

/**
 * The boundary alarm's elapsed-realtime deadline: each edge converted with its own clock (`edge - clock now + elapsed
 * now`), the minimum taken after, a second late so the evaluation lands inside the new period; a deadline at or before
 * now is floored to now plus a second, so an alarm can't find its own edge still ahead and loop. `null` without edges.
 */
fun alarmDeadline(edges: List<AlarmEdge>, elapsedNowMs: Long): Long? =
    edges.minOfOrNull { elapsedNowMs + (it.edgeMs - it.clockNowMs) }
        ?.let { maxOf(it, elapsedNowMs) + 1_000L }
