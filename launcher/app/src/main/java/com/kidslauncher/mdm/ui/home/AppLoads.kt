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
 * Whether an `AppEnforcer.apply` holds the app-list loads now (the provisioning storm's follow-up, the coordinator's
 * jelly36 run: on a loaded emulator the enforcer's per-package calls came more than 300 ms apart, so most packages
 * opened a burst of their own - 59 loads in two minutes). [heldSince] is the apply's start (`null` = no apply runs);
 * a hold ends with the apply, or [capMs] after its start.
 */
fun appLoadHeld(now: Long, heldSince: Long?, capMs: Long = APP_LOAD_HOLD_CAP_MS): Boolean =
    heldSince != null && now - heldSince < capMs

/**
 * The hold's state: `AppEnforcer.apply` calls [hold] at its start and [release] at its end (in a `finally`). A nested
 * hold (`apply` is `@Synchronized`, so only a reentrant call) keeps the first start.
 */
object AppLoadHold {
    @Volatile
    var heldSince: Long? = null
        private set
    private var depth = 0

    @Synchronized
    fun hold(nowElapsed: Long) {
        if (depth++ == 0) heldSince = nowElapsed
    }

    @Synchronized
    fun release() {
        if (depth > 0 && --depth == 0) heldSince = null
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
 * [APP_LOAD_HOLD_POLL_MS] - and loads at once when the hold ends (released, or its cap). Returns when the channel
 * closes (tests; the app's never does), after loading a burst still pending.
 */
suspend fun runAppLoads(
    next: suspend (timeoutMs: Long?) -> AppLoadNext,
    now: () -> Long,
    heldSince: () -> Long? = { null },
    load: suspend () -> Unit,
) {
    var firstOfProcess = true
    while (true) {
        if (next(null) == AppLoadNext.CLOSED) return
        if (firstOfProcess) {
            firstOfProcess = false
            load()
            continue
        }
        // When the consumer took it: a request sent during the last load waited for that load's end.
        val firstAt = now()
        var lastAt = firstAt
        var wasHeld = false
        while (true) {
            val t = now()
            val since = heldSince()
            val held = appLoadHeld(t, since)
            // A hold that ended (released, or its cap) loads the burst at once.
            if (!held && (wasHeld || appLoadDue(t, firstAt, lastAt))) break
            val wait = if (held && since != null) {
                wasHeld = true
                minOf(APP_LOAD_HOLD_POLL_MS, since + APP_LOAD_HOLD_CAP_MS - t)
            } else {
                appLoadWaitMs(t, firstAt, lastAt)
            }
            when (next(wait)) {
                AppLoadNext.REQUEST -> lastAt = now()
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
