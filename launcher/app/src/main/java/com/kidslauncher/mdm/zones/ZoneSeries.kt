package com.kidslauncher.mdm.zones

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.LocateCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val LOG_TAG = "ZoneWatch"
private const val ACTION_CHECK = "com.kidslauncher.mdm.action.ZONE_CHECK"

/** The series' next check; not the fences' 8, the confirm's 9, BackstopAlarm's 7, the update
 * fence's 11, the self-update's 12 or the torch's 41. */
private const val CHECK_REQUEST_CODE = 10

/** A check holds the CPU at most this long: the balanced fix's 15 s and the bookkeeping. */
private const val CHECK_WAKELOCK_MS = 45_000L

/**
 * The moving series (design 64 §4.5): a significant-motion fire while the policy has zones - not
 * within [SERIES_COOLDOWN_MS] of the last series' end - or a fence crossing (at once) starts cheap
 * checks - each one balanced fused fix (`LocateCommands.seriesFix`: Wi-Fi/cell, no GPS) - every
 * [nextCheckDelay] (the time to the nearest boundary at 15 m/s, 3..30 min), at most
 * [SERIES_CHECKS_PER_DAY] a local day ([seriesMayStart], the budget in `ZoneWatch`;
 * qa-design-64-step2-code F-2). A fix the server can use ([checkVerdict]: surely on the other side
 * of a boundary than the reported fix, F-1) is sent now - promoted to the reported fix and queued,
 * so it rides the report the sync it asks for makes, with the crossing's confirming GPS fix 2 min
 * later - else it only goes to the kept slot (a due heartbeat's `KEPT` reports it, Q-1). The fused
 * fix also passes the platform fence, which judges every fused fix. The series ends after an hour,
 * 20 checks, the day's budget, or when the sensor fired no more within twice the last delay
 * ([seriesEnd]). The phone decides nothing: the server's rule on the fixes does. State in memory
 * only (a new process starts no series until the next fire); every motion fire is handled on the
 * series' IO scope, never the sensor callback's main thread (F-4).
 */
object ZoneSeries {
    private var series: CheckSeries? = null

    /** When the last series ended (elapsed), for the cooldown; `null` = none in this process. */
    private var lastEndedAtMs: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [block] on the series' IO scope, every exception caught - the motion fire's work (F-4). */
    fun launch(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The moving series failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        CHECK_REQUEST_CODE,
        Intent(context, ZoneCheckReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A motion fire (from [MotionGate]) or a fence crossing ([fromFence]): tell the running series,
     * or start one when [seriesMayStart] lets it. Off the main thread ([launch]). */
    fun onMotion(context: Context, fromFence: Boolean = false) {
        val app = context.applicationContext
        val zones = ZoneWatch.watchedZones()
        val first = nextCheckDelay(LocateCommands.newestKnown(), zones, System.currentTimeMillis()) ?: return
        val now = SystemClock.elapsedRealtime()
        val left = ZoneWatch.checksLeftToday(app)
        val started = synchronized(this) {
            val before = series
            if (before == null && !seriesMayStart(fromFence, now, lastEndedAtMs, left)) return
            series = seriesOnMotion(before, now, first)
            before == null
        }
        if (started) {
            Log.i(LOG_TAG, "Moving series: started, first check in ${first / 1000} s")
            arm(app, first)
        }
    }

    /** The check's alarm ([ZoneCheckReceiver]). */
    internal fun onAlarm(context: Context) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val running = synchronized(this) { series } ?: return
        val zones = ZoneWatch.watchedZones()
        val end = if (zones.isEmpty()) SeriesEnd.COUNT else seriesEnd(running, now, ZoneWatch.checksLeftToday(app))
        if (end != null) {
            synchronized(this) {
                series = null
                lastEndedAtMs = now
            }
            Log.i(LOG_TAG, "Moving series: ended ($end) after ${running.checks} check(s)")
            return
        }
        ZoneWatch.spendCheck(app)
        val wakeLock = wakeLock(app)
        scope.launch {
            try {
                check(app, zones)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A series check failed: ${e.javaClass.simpleName}")
            } finally {
                val next = nextCheckDelay(LocateCommands.newestKnown(), zones, System.currentTimeMillis()) ?: CHECK_MAX_DELAY_MS
                val still = synchronized(this@ZoneSeries) {
                    series?.let { series = seriesAfterCheck(it, next) } != null
                }
                if (still) arm(app, next)
                try {
                    wakeLock?.takeIf { it.isHeld }?.release()
                } catch (e: Exception) {
                    // Released by its timeout.
                }
            }
        }
    }

    private suspend fun check(context: Context, zones: List<com.kidslauncher.mdm.server.dto.PolicyZone>) {
        // Compared with the reported fix: the server knows that one's side.
        val previous = LocateCommands.cachedQueued()
        val requestedAt = System.currentTimeMillis()
        val location = LocateCommands.seriesFix(context) ?: return
        val fix = location.toQueued(LocateCommands.providerOf(location))
        val verdict = checkVerdict(previous, fix, zones)
        // Kept for the heartbeat (Q-1); a side change is reported now, so it becomes the reported
        // fix at once.
        LocateCommands.keepFix(context, location)
        val fresh = freshOwnFix(location.time, requestedAt)
        if (fresh) ZoneWatch.stampFreshFix(context, location.time)
        Log.i(LOG_TAG, "Series check: $verdict")
        if (verdict == CheckVerdict.KEEP) {
            if (fresh) MotionGate.arm(context)
            return
        }
        // A side change is motion (F-3): no `still` until the confirm's fix arms the gate again.
        MotionGate.markMoved()
        LocateCommands.promoteKept(context)
        ZoneWatch.enqueue(context, fix)
        SyncRunner.request(context, "zone_check")
        ZoneWatch.armConfirm(context)
    }

    private fun arm(context: Context, delayMs: Long) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + delayMs
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the series check: ${e.javaClass.simpleName}")
        }
    }

    private fun wakeLock(context: Context): PowerManager.WakeLock? = try {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidslauncher:zone_check")
            ?.apply { setReferenceCounted(false); acquire(CHECK_WAKELOCK_MS) }
    } catch (e: Exception) {
        null
    }

    /** No zones watched any more (Regularly ended, the last zone deleted, not managed): the series
     * and its alarm go. */
    fun stop(context: Context) {
        val had = synchronized(this) {
            series.also {
                series = null
                if (it != null) lastEndedAtMs = SystemClock.elapsedRealtime()
            }
        } != null
        if (!had) return
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context.applicationContext))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the series check: ${e.javaClass.simpleName}")
        }
    }
}

/** The series' check alarm - our own explicit PendingIntent only (not exported). */
class ZoneCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        ZoneSeries.onAlarm(context)
    }
}
