package com.kidslauncher.mdm.ui.home

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The provisioning storm (PLAN "Provisioning storm: debounce the app-list reload", qa-home-round-code Q-4): at
 * `set-device-owner` the enforcer hides and suspends dozens of packages one by one, and every one of them sends a
 * `LauncherApps` callback on the main thread, each asking for an app-list load. The conflated channel kept one load
 * running and one pending, so the loads ran back to back for the whole storm and Home rendered every list on the main
 * thread - two "no focused window" ANRs on the emulator (docs/testing/2026-10-24-home-round-run.md). Now the channel's
 * one consumer loads once per burst: after [APP_LOAD_QUIET_MS] with no new request, or at the latest
 * [APP_LOAD_MAX_WAIT_MS] after the consumer took the burst's first request - a request made while a load runs is taken
 * when that load ends, so it waits for the rest of that load as well (qa-appload-debounce-code Q-2). The process's
 * first request loads at once, so Home's cold start never waits for the quiet period.
 */

/** A burst ends after this long with no new request. */
const val APP_LOAD_QUIET_MS = 300L

/** A burst that never goes quiet still loads this long after the consumer took its first request (a request made
 * during a load counts from that load's end). */
const val APP_LOAD_MAX_WAIT_MS = 2_000L

/**
 * Whether the pending burst loads now: [quietMs] since its last request ([lastEventAt]), or [maxWaitMs] since the
 * consumer took its first ([firstPendingAt]). Times are one monotonic clock (elapsed realtime).
 */
fun appLoadDue(
    now: Long,
    firstPendingAt: Long,
    lastEventAt: Long,
    quietMs: Long = APP_LOAD_QUIET_MS,
    maxWaitMs: Long = APP_LOAD_MAX_WAIT_MS,
): Boolean = now - lastEventAt >= quietMs || now - firstPendingAt >= maxWaitMs

/** How long the consumer waits for another request before [appLoadDue] says load; 0 when it is due. */
fun appLoadWaitMs(
    now: Long,
    firstPendingAt: Long,
    lastEventAt: Long,
    quietMs: Long = APP_LOAD_QUIET_MS,
    maxWaitMs: Long = APP_LOAD_MAX_WAIT_MS,
): Long = (minOf(lastEventAt + quietMs, firstPendingAt + maxWaitMs) - now).coerceAtLeast(0L)

/** How long an `AppEnforcer.apply` may hold the app-list loads at most (one that hangs). */
const val APP_LOAD_HOLD_CAP_MS = 10_000L

/** How often a burst that waits for a hold looks again whether it was released. */
const val APP_LOAD_HOLD_POLL_MS = 100L

/**
 * Whether an `AppEnforcer.apply` holds the burst that began at [burstSince] now (the provisioning storm's follow-up,
 * the coordinator's jelly36 run: on a loaded emulator the enforcer's per-package calls came more than 300 ms apart, so
 * most packages opened a burst of their own - 59 loads in two minutes). [heldSince] is the running apply's start
 * (`null` = no apply runs). A hold ends with the apply, [capMs] after the apply's start, and - whatever chain of
 * applies follows one another on `apply`'s lock - [capMs] after the burst's start (qa-appload-hold-code Q-1): a burst
 * waits at most 10 s.
 */
fun appLoadHeld(now: Long, heldSince: Long?, burstSince: Long = now, capMs: Long = APP_LOAD_HOLD_CAP_MS): Boolean =
    heldSince != null && now - heldSince < capMs && now - burstSince < capMs

/**
 * How long a held burst waits before it looks again ([appLoadHeld]'s pair, as [appLoadWaitMs] is [appLoadDue]'s): at
 * most [pollMs], never past either cap; 0 when not held - so "held" always means a wait > 0 and the loop never spins.
 */
fun appLoadHoldWaitMs(
    now: Long,
    heldSince: Long?,
    burstSince: Long = now,
    capMs: Long = APP_LOAD_HOLD_CAP_MS,
    pollMs: Long = APP_LOAD_HOLD_POLL_MS,
): Long {
    if (heldSince == null || !appLoadHeld(now, heldSince, burstSince, capMs)) return 0L
    return minOf(pollMs, heldSince + capMs - now, burstSince + capMs - now)
}

/*
 * The trail of a pass (the apply hold's follow-up, PLAN "Hold the app-list loads while AppEnforcer.apply runs" ->
 * Follow-up; `docs/testing/2026-10-24-home-round-run.md`, second follow-up): on a loaded phone the system delivers the
 * pass's `PACKAGE_CHANGED` broadcasts 1-3 s apart for up to a minute after its last DPM call, so with the 300 ms quiet
 * period each straggler opened a burst of its own (31 loads in the run). A callback about a package the pass touched -
 * the running pass's, or one released within [APP_LOAD_TRAIL_WINDOW_MS] - is the pass's trail and waits
 * [APP_LOAD_TRAIL_QUIET_MS] of quiet, at most [APP_LOAD_TRAIL_MAX_WAIT_MS]: the stragglers fold into one or two loads.
 * Any other request in the burst (an install, a window's edge, another package) keeps 300 ms / 2 s from its own arrival.
 */

/** A burst of the pass's trail alone ends after this long with no new request. */
const val APP_LOAD_TRAIL_QUIET_MS = 3_000L

/** A trail burst that never goes quiet still loads this long after the consumer took its first request. */
const val APP_LOAD_TRAIL_MAX_WAIT_MS = 30_000L

/** How long after a pass's release a callback about a package it touched still counts as its trail. */
const val APP_LOAD_TRAIL_WINDOW_MS = 120_000L

/** The packages the released passes touched, and when the last of them released its hold (elapsed). */
data class AppLoadTrail(val packages: Set<String>, val releasedAt: Long)

/**
 * Whether a callback about [packages] at [now] trails a pass: every one of them touched by the running pass
 * ([touchedNow]) or by passes released less than [windowMs] ago ([trail]). No package (a callback about none, or a
 * request not about packages) is no trail.
 */
fun appLoadTrails(
    packages: Collection<String>,
    now: Long,
    touchedNow: Set<String>,
    trail: AppLoadTrail?,
    windowMs: Long = APP_LOAD_TRAIL_WINDOW_MS,
): Boolean {
    if (packages.isEmpty()) return false
    val recent = trail?.takeIf { now - it.releasedAt in 0 until windowMs }?.packages.orEmpty()
    return packages.all { it in touchedNow || it in recent }
}

/** The trail after a pass released at [now] having touched [touched]: joined with an earlier trail still inside its
 * window (a chain of passes - the provisioning storm's five), the window counting from this release; `null` when
 * nothing is left to trail. */
fun appLoadTrailAfterRelease(
    touched: Set<String>,
    now: Long,
    previous: AppLoadTrail?,
    windowMs: Long = APP_LOAD_TRAIL_WINDOW_MS,
): AppLoadTrail? {
    val kept = previous?.takeIf { now - it.releasedAt in 0 until windowMs }?.packages.orEmpty()
    val all = kept + touched
    return if (all.isEmpty()) null else AppLoadTrail(all, now)
}

/** Whether the burst loads now: a trail-only burst ([normalAt] null) by [APP_LOAD_TRAIL_QUIET_MS] /
 * [APP_LOAD_TRAIL_MAX_WAIT_MS] from its first request ([firstAt]); once another request joined it at [normalAt], also
 * by [appLoadDue]'s 300 ms / 2 s counted from that one. */
fun appLoadBurstDue(now: Long, firstAt: Long, lastAt: Long, normalAt: Long?): Boolean =
    appLoadDue(now, firstAt, lastAt, APP_LOAD_TRAIL_QUIET_MS, APP_LOAD_TRAIL_MAX_WAIT_MS) ||
        (normalAt != null && appLoadDue(now, normalAt, lastAt))

/** [appLoadBurstDue]'s wait (0 exactly when it is due). */
fun appLoadBurstWaitMs(now: Long, firstAt: Long, lastAt: Long, normalAt: Long?): Long {
    val trail = appLoadWaitMs(now, firstAt, lastAt, APP_LOAD_TRAIL_QUIET_MS, APP_LOAD_TRAIL_MAX_WAIT_MS)
    return if (normalAt == null) trail else minOf(trail, appLoadWaitMs(now, normalAt, lastAt))
}

/**
 * The hold's state: `AppEnforcer.apply` calls [hold] at its start and [release] at its end (in a `finally`). A nested
 * hold (`apply` is `@Synchronized`, so only a reentrant call) keeps the first start. The pass names each package it
 * changes ([touch]); its release keeps them as the trail ([AppLoadTrail]), and every load request is classed by
 * [noteRequest] - the consumer takes "a request that isn't the trail came" with [takeNormal].
 */
object AppLoadHold {
    @Volatile
    var heldSince: Long? = null
        private set
    private var depth = 0
    private val touchedNow = mutableSetOf<String>()
    private var trail: AppLoadTrail? = null
    private val normalPending = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    fun hold(nowElapsed: Long) {
        if (depth++ == 0) heldSince = nowElapsed
    }

    @Synchronized
    fun release(nowElapsed: Long) {
        if (depth > 0 && --depth == 0) {
            heldSince = null
            trail = appLoadTrailAfterRelease(touchedNow.toSet(), nowElapsed, trail)
            touchedNow.clear()
        }
    }

    /** The running pass suspends, unsuspends, hides or unhides [packageName]; outside a pass it is no trail. */
    @Synchronized
    fun touch(packageName: String) {
        if (depth > 0) touchedNow += packageName
    }

    /** Whether a callback about [packages] at [nowElapsed] is a pass's trail ([appLoadTrails]). */
    @Synchronized
    fun trails(packages: Collection<String>, nowElapsed: Long): Boolean = appLoadTrails(packages, nowElapsed, touchedNow, trail)

    /** A load request about [packages] (`null`: not about packages - always a normal request): recorded for the
     * consumer unless it is the trail. Returns whether it trails. */
    fun noteRequest(packages: Collection<String>?, nowElapsed: Long): Boolean {
        val trails = packages != null && trails(packages, nowElapsed)
        if (!trails) normalPending.set(true)
        return trails
    }

    /** The consumer: whether a request other than the trail came since it last asked (and forget it). */
    fun takeNormal(): Boolean = normalPending.getAndSet(false)

    /** Tests only: the process-wide object back at rest. */
    @Synchronized
    internal fun resetForTest() {
        depth = 0
        heldSince = null
        touchedNow.clear()
        trail = null
        normalPending.set(false)
    }
}

/** What waiting for the next load request gave. */
enum class AppLoadNext { REQUEST, TIMEOUT, CLOSED }

/**
 * The next request from [this] (the conflated `appLoads` channel): within [timeoutMs], or for as long as it takes when
 * `null`. A request a timeout races may be lost, harmlessly: the burst's load still follows, and it reads the apps
 * after that request was sent.
 */
suspend fun ReceiveChannel<Unit>.nextAppLoad(timeoutMs: Long?): AppLoadNext {
    val result = if (timeoutMs == null) receiveCatching() else withTimeoutOrNull(timeoutMs) { receiveCatching() }
    return when {
        result == null -> AppLoadNext.TIMEOUT
        result.isClosed -> AppLoadNext.CLOSED
        else -> AppLoadNext.REQUEST
    }
}

/**
 * The `appLoads` consumer: the process's first request loads at once; every later one opens a burst that loads once
 * when [appLoadDue] says so, each request inside it moving the quiet period on. [next] waits for a request (see
 * [nextAppLoad]), [now] is elapsed realtime, [load] is the whole load (the same work as before the debounce). While
 * [heldSince] says an `AppEnforcer.apply` runs ([appLoadHeld]) a burst waits for its end - looking again every
 * [APP_LOAD_HOLD_POLL_MS] ([appLoadHoldWaitMs]) - and the hold's end counts as a request (qa-appload-hold-code Q-2): the
 * debounce goes on from there, so the callbacks the apply's last packages send after it ended join the same load - one
 * load per apply. A burst of the pass's trail alone ([takeNormal] false for all its requests) waits by
 * [appLoadBurstDue]'s 3 s / 30 s; a request that isn't the trail brings back 300 ms / 2 s from its arrival. Returns when
 * the channel closes (tests; the app's never does), after loading a burst still pending.
 */
suspend fun runAppLoads(
    next: suspend (timeoutMs: Long?) -> AppLoadNext,
    now: () -> Long,
    takeNormal: () -> Boolean = { true },
    heldSince: () -> Long? = { null },
    load: suspend () -> Unit,
) {
    var firstOfProcess = true
    while (true) {
        if (next(null) == AppLoadNext.CLOSED) return
        if (firstOfProcess) {
            firstOfProcess = false
            takeNormal()
            load()
            continue
        }
        // When the consumer took it: a request sent during the last load waited for that load's end.
        val burstAt = now()
        var firstAt = burstAt
        var lastAt = burstAt
        // When the burst's first request that isn't a pass's trail came; null while it is the trail alone.
        var normalAt: Long? = if (takeNormal()) burstAt else null
        var wasHeld = false
        while (true) {
            val t = now()
            val holdWait = appLoadHoldWaitMs(t, heldSince(), burstAt)
            val wait = if (holdWait > 0) {
                wasHeld = true
                holdWait
            } else {
                // The hold's end is a request: the debounce starts over from it (Q-2).
                if (wasHeld) {
                    wasHeld = false
                    firstAt = t
                    lastAt = t
                    if (normalAt != null) normalAt = t
                }
                if (appLoadBurstDue(t, firstAt, lastAt, normalAt)) break
                appLoadBurstWaitMs(t, firstAt, lastAt, normalAt)
            }
            when (next(wait)) {
                AppLoadNext.REQUEST -> {
                    lastAt = now()
                    if (normalAt == null && takeNormal()) normalAt = lastAt
                }
                AppLoadNext.TIMEOUT -> Unit
                AppLoadNext.CLOSED -> {
                    load()
                    return
                }
            }
        }
        load()
    }
}
