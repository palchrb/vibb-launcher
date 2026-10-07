package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.MIN_LOCATION_INTERVAL_MINUTES
import com.kidslauncher.mdm.server.dto.LocationPolicy

/*
 * When the phone syncs on its own (handy step 7, design 07 §2/§3; design 19: the SSE stream is
 * the only nudge). Pure, unit-tested in SyncScheduleTest.
 *
 * Nudges over the SSE stream bring changes within seconds; the backstop alarm only catches what
 * a nudge missed. It is a while-idle alarm on elapsed realtime (`BackstopAlarm`), not a Handler
 * timer: uptime stops in deep sleep, which is why the old 5-minute timer only ran because the
 * 15 s SSE keepalive kept waking the phone.
 */

/** The normal backstop period. */
const val BACKSTOP_INTERVAL_MS = 30 * 60 * 1000L

/** While the stream is down, nothing else would bring a change. */
const val BACKSTOP_SSE_DOWN_INTERVAL_MS = 15 * 60 * 1000L

/** Android lets an app's while-idle alarms fire about once per 9 minutes in Doze; asking for
 * less only bunches them up. */
const val MIN_BACKSTOP_MS = 9 * 60 * 1000L

/**
 * Delay until the next backstop sync: 30 minutes; 15 while the stream is down; never later than
 * the next location fix the parent's "interval" policy wants ([sinceLastFreshLocationMs] = wall
 * time since the last active fix, negative = the clock went back - counts as due). Never below
 * [MIN_BACKSTOP_MS].
 */
fun backstopDelayMs(
    sseConnected: Boolean,
    locationPolicy: LocationPolicy?,
    sinceLastFreshLocationMs: Long,
): Long {
    var delay = if (!sseConnected) BACKSTOP_SSE_DOWN_INTERVAL_MS else BACKSTOP_INTERVAL_MS
    if (locationPolicy?.mode == "interval") {
        val intervalMs = locationPolicy.intervalMinutes.coerceAtLeast(MIN_LOCATION_INTERVAL_MINUTES) * 60_000L
        val untilDue = if (sinceLastFreshLocationMs < 0) 0L else intervalMs - sinceLastFreshLocationMs
        delay = minOf(delay, untilDue)
    }
    return delay.coerceAtLeast(MIN_BACKSTOP_MS)
}

/** The SSE stream's read timeout: the server sends a keepalive comment every `SSE_KEEPALIVE_SECS`
 * (240 s by default since design 19, never more), so 300 s of silence means the stream is dead -
 * reconnect. Launchers already shipped use the same 300 s. */
const val SSE_READ_TIMEOUT_MS = 300_000L

/**
 * A stream down this long (from noticing the drop to the reopen) may have missed a nudge (QA step
 * 7 #5); a quicker reconnect costs no sync.
 */
const val SSE_GAP_SYNC_MS = 150_000L

/**
 * Whether reopening the SSE stream must sync (design 19 QA #3, one rule for every reconnect):
 * when it was down >= [SSE_GAP_SYNC_MS] ([downForMs], from noticing the drop), or when the old
 * stream's last byte is >= [SSE_READ_TIMEOUT_MS] ago ([sinceLastByteMs], elapsed realtime - it
 * counts deep sleep). A read timeout always meets the second (Okio's watchdog and SO_TIMEOUT
 * count awake time only, so the stream was deaf at least that long), and so does a staleness
 * reconnect ([SSE_STALE_MS]); a quick reconnect of a live stream meets neither (its last byte is
 * at most one keepalive, 240 s, plus the backoff ago). Unknown (`null`, negative) = sync.
 */
fun syncOnSseReopen(downForMs: Long?, sinceLastByteMs: Long?): Boolean =
    downForMs == null || downForMs < 0 || downForMs >= SSE_GAP_SYNC_MS ||
        sinceLastByteMs == null || sinceLastByteMs < 0 || sinceLastByteMs >= SSE_READ_TIMEOUT_MS

/**
 * An open stream with nothing received for this long (elapsed realtime) is dead, whatever the
 * read timeout says (design 19 hole 1): about two of the server's longest keepalives (QA #2c), so a
 * keepalive that woke the phone but wasn't read before it slept again forces no reconnect. The
 * read timeout's watchdog stops in deep sleep, so a silently dead stream (a Pi restart without a
 * FIN reaching us) would otherwise count as up for hours, and the backstop reconnects only a
 * stream that is down. Checked by the backstop's sync request and at screen-on/unlock.
 */
const val SSE_STALE_MS = 480_000L

fun sseStale(sinceLastByteMs: Long): Boolean = sinceLastByteMs >= SSE_STALE_MS

/**
 * Design 19 hole 3: the reconnect backoff runs on a Handler (uptime only), so after a drop in deep
 * sleep - every server restart - the 5 s and 10 s retries would wait for the next backstop (15
 * min). The up->down edge takes a partial wake lock this long, released when the stream opens...
 */
const val SSE_DROP_WAKELOCK_MS = 30_000L

/** ...at most once per this long, so a flapping stream can't keep the phone awake. */
const val SSE_DROP_WAKELOCK_EVERY_MS = 10 * 60_000L

/** [lastAtMs]: when the drop wake lock was last taken (elapsed realtime, `null` = never). */
fun sseDropWakeLockDue(lastAtMs: Long?, nowMs: Long): Boolean =
    lastAtMs == null || nowMs < lastAtMs || nowMs - lastAtMs >= SSE_DROP_WAKELOCK_EVERY_MS

/** Hard limits for one sync run (policy, status, the app list - since design 13 the APK downloads
 * run outside the sync, in `AppDownloads`). The wake lock is released when the run ends, at the
 * latest after [SYNC_WAKELOCK_MS]. */
const val SYNC_TIMEOUT_MS = 10 * 60_000L
const val SYNC_WAKELOCK_MS = 11 * 60_000L

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
