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
 * [APP_LOAD_MAX_WAIT_MS] after the burst's first request. The process's first request loads at once, so Home's cold
 * start never waits for the quiet period.
 */

/** A burst ends after this long with no new request. */
const val APP_LOAD_QUIET_MS = 300L

/** A burst that never goes quiet still loads this long after its first request. */
const val APP_LOAD_MAX_WAIT_MS = 2_000L

/**
 * Whether the pending burst loads now: [quietMs] since its last request ([lastEventAt]), or [maxWaitMs] since its first
 * ([firstPendingAt]). Times are one monotonic clock (elapsed realtime).
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
 * [nextAppLoad]), [now] is elapsed realtime, [load] is the whole load (the same work as before the debounce). Returns
 * when the channel closes (tests; the app's never does), after loading a burst still pending.
 */
suspend fun runAppLoads(
    next: suspend (timeoutMs: Long?) -> AppLoadNext,
    now: () -> Long,
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
        val firstAt = now()
        var lastAt = firstAt
        while (!appLoadDue(now(), firstAt, lastAt)) {
            when (next(appLoadWaitMs(now(), firstAt, lastAt))) {
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
