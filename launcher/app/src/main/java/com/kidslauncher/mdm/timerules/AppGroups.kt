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

/** A point older than this starts a first fold instead (code QA L1): the read stays bounded, and a day's gap holds no
 * time of today's that a lookback misses (every package goes off at a screen off). */
const val STALE_POINT_MS = 24L * 60 * 60_000L

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
fun planRead(point: FoldPoint?, nowWall: Long, nowElapsed: Long, bootCount: Int, checkedClock: Boolean): ReadPlan? {
    val to = nowWall - SETTLE_MS
    val toElapsed = nowElapsed - SETTLE_MS
    val rebooted = point != null && point.bootCount != bootCount
    // QA M3: no carry - the lookback fills it with the count off. Code QA L1, L2: so does a point over a day old (an
    // unbounded read), or one the clock is behind after a reboot (no read could ever reach past it).
    if (point == null || to - point.wallMs > STALE_POINT_MS || (rebooted && to <= point.wallMs)) {
        val from = to - FIRST_FOLD_LOOKBACK_MS
        return ReadPlan(from, from, to, toElapsed, first = true, stepBackMs = 0, rebooted = false, capMs = 0)
    }
    // QA L4: the offset is comparable within one boot only.
    val fall = if (rebooted) 0L else point.offsetMs - (nowWall - nowElapsed)
    val stepBack = if (fall > CLOCK_STEP_NOISE_MS) fall else 0L
    val spanFrom = point.wallMs - stepBack
    if (to <= spanFrom) return null
    val queryFrom = if (stepBack > 0) spanFrom - SETTLE_MS else spanFrom
    val cap = when {
        !rebooted -> toElapsed - point.elapsedMs
        checkedClock -> to - point.wallMs
        else -> 0L
    }.coerceAtLeast(0)
    return ReadPlan(queryFrom, spanFrom, to, toElapsed, first = false, stepBackMs = stepBack, rebooted = rebooted, capMs = cap)
}

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
): FoldOut {
    val read = events.filter { it.timeMs >= plan.queryFrom && it.timeMs < plan.to && it.key !in skip }
    // QA L3: a new boot resets the state at the read's DEVICE_SHUTDOWN (the event does it), else at the read's start.
    val start = when {
        plan.first || (plan.rebooted && read.none { it.kind == UsageKind.DEVICE_SHUTDOWN }) -> FoldCarry()
        else -> carry
    }
    val on = LinkedHashMap<String, OnPackage>()
    start.on.forEach { on[it.pkg] = it }
    var screenOn = start.screenOn
    var keyguardHidden = start.keyguardHidden
    val pending = start.pending.filter { it.pkg in on && on[it.pkg]?.paused == false }.toMutableList()

    val spans = groups.associate { it.id to mutableListOf<LongRange>() }
    var cursor = plan.spanFrom
    fun countTo(t: Long) {
        val end = minOf(t, plan.to)
        if (end > cursor && count && screenOn && keyguardHidden) {
            for (g in groups) {
                if (g.apps.none { it in on }) continue
                val list = spans.getValue(g.id)
                val last = list.lastOrNull()
                if (last != null && last.last + 1 == cursor) list[list.size - 1] = last.first until end else list += cursor until end
            }
        }
        if (end > cursor) cursor = end
    }
    fun allOff() {
        on.clear()
        pending.clear()
    }

    // QA L6: the pending STOPPEDs first, dated at the STOPPED - in this read that is its start.
    for (stop in pending.toList()) {
        when (pairOf(read, -1, stop.pkg, stop.atMs, plan.to)) {
            Partner.YES -> {
                on.remove(stop.pkg)
                pending.remove(stop)
            }
            Partner.NO -> pending.remove(stop)
            Partner.OPEN -> {}
        }
    }

    val applied = ArrayList<Pair<Long, String>>(read.size)
    read.forEachIndexed { i, e ->
        countTo(e.timeMs)
        applied += e.timeMs to e.key
        // A later event of a package resolves its pending STOPPED; the event applies as usual.
        pending.removeAll { it.pkg == e.pkg }
        when (e.kind) {
            UsageKind.RESUMED -> on[e.pkg] = OnPackage(e.pkg)
            UsageKind.PAUSED -> on[e.pkg] = OnPackage(e.pkg, paused = true, pausedClass = e.cls)
            UsageKind.STOPPED -> {
                val state = on[e.pkg]
                when {
                    state == null -> {}
                    // QA M1: an on-paused package ends only at the STOPPED of the PAUSED's class.
                    state.paused -> if (state.pausedClass == e.cls) on.remove(e.pkg)
                    // The kill rule (QA L1).
                    else -> when (pairOf(read, i, e.pkg, e.timeMs, plan.to)) {
                        Partner.YES -> on.remove(e.pkg)
                        Partner.NO -> {}
                        Partner.OPEN -> pending += PendingStop(e.pkg, e.timeMs)
                    }
                }
            }
            UsageKind.SCREEN_INTERACTIVE -> screenOn = true
            UsageKind.SCREEN_NON_INTERACTIVE -> {
                screenOn = false
                allOff()
            }
            UsageKind.KEYGUARD_SHOWN -> {
                keyguardHidden = false
                allOff()
            }
            UsageKind.KEYGUARD_HIDDEN -> keyguardHidden = true
            UsageKind.DEVICE_SHUTDOWN -> {
                screenOn = false
                keyguardHidden = false
                allOff()
            }
            // QA H2: stamped at the storage unlock, maybe after the first RESUMEDs - it changes nothing.
            UsageKind.DEVICE_STARTUP -> {}
        }
    }
    countTo(plan.to)
    // The live check (QA H2, M2): the fold runs only while the screen is on and unlocked, so a missed device event
    // can't stop the count for good.
    if (live) {
        screenOn = true
        keyguardHidden = true
    }
    return FoldOut(
        spans = if (count) spans.filterValues { it.isNotEmpty() } else emptyMap(),
        carry = FoldCarry(on.values.toList(), screenOn, keyguardHidden, pending.toList()),
        appliedKeys = applied,
    )
}

/** The kill rule's answer for a STOPPED of [pkg] at [atMs]: a partner came, none can come, or the window is still open. */
private enum class Partner { YES, NO, OPEN }

/**
 * Whether another package's RESUMED pairs with the STOPPED of [pkg] at [atMs] (QA L1): in the same ms before it in
 * report order ([index], -1 for a pending STOPPED from an earlier read), or after it up to [KILL_PAIR_MS] (inclusive),
 * before any later event of [pkg]. [Partner.OPEN] while the window reaches [to] (QA L6).
 */
private fun pairOf(read: List<UsageEv>, index: Int, pkg: String, atMs: Long, to: Long): Partner {
    fun partner(e: UsageEv) = e.kind == UsageKind.RESUMED && e.pkg != pkg
    if (index > 0) {
        var j = index - 1
        while (j >= 0 && read[j].timeMs == atMs) {
            if (partner(read[j])) return Partner.YES
            j--
        }
    }
    val windowEnd = atMs + KILL_PAIR_MS
    for (j in index + 1 until read.size) {
        val e = read[j]
        if (e.timeMs > windowEnd) return Partner.NO
        if (e.pkg == pkg) return Partner.NO
        if (partner(e) && e.timeMs >= atMs) return Partner.YES
    }
    return if (windowEnd < to) Partner.NO else Partner.OPEN
}

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
): ScreenTimeLedger {
    val point = FoldPoint(plan.to, plan.toElapsed, bootCount, plan.to - plan.toElapsed, out.carry)
    if (!count) return ledger.copy(foldPoint = point)
    val dayStart = ledger.date()?.let { dayStartMs(it, zone) } ?: Long.MIN_VALUE
    // A wall time of the read on the decisions' clock, through the elapsed realtime it maps to.
    fun trusted(t: Long): Long = clocks.wallMs - (clocks.elapsedMs - (plan.toElapsed - (plan.to - t)))
    val used = ledger.groupUsedMs.toMutableMap()
    for ((id, ranges) in out.spans) {
        val ms = ranges.sumOf { r ->
            val end = trusted(r.last + 1)
            (end - maxOf(trusted(r.first), dayStart)).coerceAtLeast(0)
        }.coerceAtMost(plan.capMs)
        if (ms > 0) used[id] = (used[id] ?: 0L) + ms
    }
    return ledger.copy(groupUsedMs = used, foldPoint = point)
}

/** Code QA L7: [applyFold] onto [observed] only while its point is still the one the read started [from] - a read that
 * raced a cleared or newer point is dropped (`null`), so `from` and `carry` stay as they were. */
fun applyFoldIfCurrent(
    observed: ScreenTimeLedger,
    from: FoldPoint?,
    plan: ReadPlan,
    out: FoldOut,
    clocks: Clocks,
    zone: ZoneId,
    bootCount: Int,
    count: Boolean,
): ScreenTimeLedger? =
    if (observed.foldPoint != from) null else applyFold(observed, plan, out, clocks, zone, bootCount, count)

/** The trusted local midnight that starts [day] in [zone], epoch ms. */
internal fun dayStartMs(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
