package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.MIN_LOCATION_INTERVAL_MINUTES
import com.kidslauncher.mdm.server.dto.LocationPolicy

/*
 * When the phone syncs on its own (handy step 7, design 07 §2/§3). Pure, unit-tested in
 * SyncScheduleTest.
 *
 * Nudges (FCM or SSE) bring changes within seconds; the backstop alarm only catches what a
 * nudge missed. It is a while-idle alarm on elapsed realtime (`BackstopAlarm`), not a Handler
 * timer: uptime stops in deep sleep, which is why the old 5-minute timer only ran because the
 * 15 s SSE keepalive kept waking the phone.
 */

/** The normal backstop period. */
const val BACKSTOP_INTERVAL_MS = 30 * 60 * 1000L

/** While the transport is SSE but the stream is down, nothing else would bring a change. */
const val BACKSTOP_SSE_DOWN_INTERVAL_MS = 15 * 60 * 1000L

/** Android lets an app's while-idle alarms fire about once per 9 minutes in Doze; asking for
 * less only bunches them up. */
const val MIN_BACKSTOP_MS = 9 * 60 * 1000L

/**
 * Delay until the next backstop sync: 30 minutes; 15 while on SSE with the stream down; never
 * later than the next location fix the parent's "interval" policy wants
 * ([sinceLastFreshLocationMs] = wall time since the last active fix, negative = the clock went
 * back - counts as due). Never below [MIN_BACKSTOP_MS].
 */
fun backstopDelayMs(
    transport: PushTransport,
    sseConnected: Boolean,
    locationPolicy: LocationPolicy?,
    sinceLastFreshLocationMs: Long,
): Long {
    var delay = if (transport == PushTransport.SSE && !sseConnected) BACKSTOP_SSE_DOWN_INTERVAL_MS else BACKSTOP_INTERVAL_MS
    if (locationPolicy?.mode == "interval") {
        val intervalMs = locationPolicy.intervalMinutes.coerceAtLeast(MIN_LOCATION_INTERVAL_MINUTES) * 60_000L
        val untilDue = if (sinceLastFreshLocationMs < 0) 0L else intervalMs - sinceLastFreshLocationMs
        delay = minOf(delay, untilDue)
    }
    return delay.coerceAtLeast(MIN_BACKSTOP_MS)
}

/** The SSE stream's read timeout: the server sends a keepalive comment every 120 s
 * (`SSE_KEEPALIVE_SECS`), so 300 s of silence means the stream is dead - reconnect. */
const val SSE_READ_TIMEOUT_MS = 300_000L

/** Hard limits for one sync run (policy + status + app updates; the journal and browser
 * history run beside it). The wake lock is released when the run ends, at the latest after
 * [SYNC_WAKELOCK_MS]. */
const val SYNC_TIMEOUT_MS = 150_000L
const val SIDE_SYNC_TIMEOUT_MS = 120_000L
const val SYNC_WAKELOCK_MS = 180_000L

/**
 * Coalesces sync requests (nudges, the backstop alarm, process start): at most one run at a
 * time, and requests that arrive during a run collapse into exactly one more run afterwards -
 * so a burst of nudges costs two syncs, never a queue of them, and a change that lands while a
 * run is already past its policy fetch is still picked up. Not thread-safe; the caller
 * synchronises.
 */
class SyncCoalescer {
    private var running = false
    private var again = false
    private val reasons = linkedSetOf<String>()

    /** Returns true if the caller must start a run now (with [takeReasons]). */
    fun request(reason: String): Boolean {
        reasons += reason
        if (running) {
            again = true
            return false
        }
        running = true
        return true
    }

    /** The reasons collected for the run that is starting. */
    fun takeReasons(): List<String> = reasons.toList().also { reasons.clear() }

    /** Called when a run ended. Returns true if the caller must start another run at once. */
    fun finished(): Boolean {
        if (again) {
            again = false
            return true
        }
        running = false
        return false
    }

    val isRunning: Boolean get() = running
}
