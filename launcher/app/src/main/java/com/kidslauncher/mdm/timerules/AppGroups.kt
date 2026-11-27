package com.kidslauncher.mdm.timerules

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

/*
 * Design 139 §4.1 (docs/design/139-app-groups.md, revision 5): an app group's on-screen time, folded per package from
 * Android's usage events - pure, no Android imports, unit-tested in AppGroupsTest. The Android side
 * (`AppGroupUsage`, `ScreenTimeTracker`) reads the events with `queryEvents(from, to)`, keeps them in memory for one
 * fold and hands them here; only the fold's state ([FoldCarry]) and the minutes per group stay.
 */

/** A read ends this long before now (QA H1): Android stamps an event before it stores it from a background handler. */
const val SETTLE_MS = 2_000L

/** The kill rule's pair window (QA L1): another package's RESUMED in the same ms or up to this long after a STOPPED of
 * a package that is on ends it. */
const val KILL_PAIR_MS = 50L

/** The first fold's lookback (QA M3), read with the count off. */
const val FIRST_FOLD_LOOKBACK_MS = 3L * 60 * 60_000L

/** A fall of the wall-minus-elapsed offset larger than this is a backward clock step (QA L4); smaller is two reads'
 * noise. */
const val CLOCK_STEP_NOISE_MS = 50L

/** The keys of the events applied this long back are kept in memory, for a backward clock step (QA L4). */
const val APPLIED_KEYS_MS = 10L * 60_000L

/** The usage event kinds the fold reads (`UsageEvents.Event`'s public constants, API 28/29); the reader drops the rest. */
enum class UsageKind {
    RESUMED,
    PAUSED,
    STOPPED,
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
}

/** One usage event, in the order `queryEvents` returned it (never re-sorted, QA L5). [cls] is the activity's class
 * name (`Event.getClassName`), `null` for the device events. */
data class UsageEv(val timeMs: Long, val pkg: String, val cls: String?, val kind: UsageKind) {
    /** The key a backward clock step skips an applied event by (QA L4). */
    val key: String get() = "$timeMs|$pkg|$cls|$kind"
}

/** A package that isn't off: on, or on-paused ([paused]) by the PAUSED of class [pausedClass] (QA M1). */
@Serializable
data class OnPackage(val pkg: String, val paused: Boolean = false, val pausedClass: String? = null)

/** A STOPPED of an on package at [atMs] whose kill pair window (to [atMs] + [KILL_PAIR_MS], inclusive) wasn't wholly
 * before the read's `to` (QA L6): still counted as on, settled by a later fold. */
@Serializable
data class PendingStop(val pkg: String, val atMs: Long)

/** The fold's state at its last point (§4.1 "carry"): every package that isn't off (any package, QA M3), the device
 * state, and the pending STOPPEDs. Time counts while [screenOn] and [keyguardHidden]. Persisted with [FoldPoint]. */
@Serializable
data class FoldCarry(
    val on: List<OnPackage> = emptyList(),
    val screenOn: Boolean = false,
    val keyguardHidden: Boolean = false,
    val pending: List<PendingStop> = emptyList(),
)

/** The last folded point, persisted in the ledger (§4.1): wall time (the read's `to`), the elapsed realtime it maps
 * to, the boot count, the wall-minus-elapsed offset (QA L4), and [carry] at that point. */
@Serializable
data class FoldPoint(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val offsetMs: Long,
    val carry: FoldCarry = FoldCarry(),
)

/** A group as the fold sees it: its id and apps. */
data class FoldGroup(val id: Long, val apps: Set<String>)

/**
 * One fold's read (§4.1 "The read window", "The first fold", "A backward clock step", "A boot-count change").
 * - [queryFrom]..[to]: `queryEvents(queryFrom, to)` - `begin` inclusive, `end` exclusive.
 * - [spanFrom]: time counts from here ([queryFrom] + [SETTLE_MS] after a backward step, else [queryFrom]).
 * - [toElapsed]: the elapsed realtime [to] maps to (now's elapsed less [SETTLE_MS]).
 * - [first]: no last point - the 3 h lookback with the count off, from an empty [FoldCarry].
 * - [stepBackMs]: a backward clock step's size (0 = none); applied events are then skipped by key.
 * - [rebooted]: the boot count changed since the point - the state resets at the read's DEVICE_SHUTDOWN, or at
 *   [spanFrom] when the read holds none (QA L3).
 * - [capMs]: at most this much time is added per group by this fold: the elapsed length of the span in one boot; after
 *   a reboot the trusted time between the point and [to] when the clock is checked, else 0.
 */
data class ReadPlan(
    val queryFrom: Long,
    val spanFrom: Long,
    val to: Long,
    val toElapsed: Long,
    val first: Boolean,
    val stepBackMs: Long,
    val rebooted: Boolean,
    val capMs: Long,
)

/**
 * The read for a fold at [nowWall] (the system wall clock, which stamps the events) and [nowElapsed] in boot
 * [bootCount], from the last [point] (`null`: a first fold). [checkedClock]: the decisions' clock is checked (server or
 * satellite), for the cap after a reboot. `null` when `to` isn't after the read's start: nothing to read, nothing
 * changes.
 */
fun planRead(point: FoldPoint?, nowWall: Long, nowElapsed: Long, bootCount: Int, checkedClock: Boolean): ReadPlan? =
    TODO("design 139 §4.1")

/**
 * The fold's result: each group's on-screen spans in wall time (merged, inside `[spanFrom, to)`), the state at `to`,
 * and the keys of the events applied (for the next backward clock step, QA L4).
 */
data class FoldOut(
    val spans: Map<Long, List<LongRange>>,
    val carry: FoldCarry,
    val appliedKeys: List<Pair<Long, String>>,
)

/**
 * The pure fold (§4.1): [events] of `[plan.queryFrom, plan.to)` in Android's order over [carry] (the state at the
 * point; ignored on a first fold, which starts from an empty carry), for [groups]. [skip]: keys of events already
 * applied (a backward clock step). [live]: the fold runs while the screen is interactive and the keyguard unlocked (the
 * gate), so a state at `to` that says "not counting" turns to counting at `to` (the live check). The spans count only
 * when [count] (false on a first fold and during the setup hold: the state moves, nothing is added).
 */
fun groupScreenMs(
    events: List<UsageEv>,
    plan: ReadPlan,
    carry: FoldCarry,
    groups: List<FoldGroup>,
    live: Boolean,
    count: Boolean,
    skip: Set<String> = emptySet(),
): FoldOut = TODO("design 139 §4.1")

/**
 * [ledger] (already observed at [clocks]) with [out] applied (§4.1 "The clocks"): each group's spans mapped to elapsed
 * realtime through `plan.to`/`plan.toElapsed`, the part before the start of the ledger's day (the trusted local
 * midnight, in [zone]) dropped, the sum capped at `plan.capMs`, added to [ScreenTimeLedger.groupUsedMs] when [count];
 * and the new [FoldPoint] at `plan.to` with `out.carry` and [bootCount].
 */
fun applyFold(
    ledger: ScreenTimeLedger,
    plan: ReadPlan,
    out: FoldOut,
    clocks: Clocks,
    zone: ZoneId,
    bootCount: Int,
    count: Boolean,
): ScreenTimeLedger = TODO("design 139 §4.1")

/** The trusted local midnight that starts [day] in [zone], epoch ms. */
internal fun dayStartMs(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
