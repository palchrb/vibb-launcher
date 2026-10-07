package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.COMMAND_LISTENER_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.BackstopAlarm
import com.kidslauncher.mdm.push.LastByteInterceptor
import com.kidslauncher.mdm.push.PushState
import com.kidslauncher.mdm.push.SSE_DROP_WAKELOCK_MS
import com.kidslauncher.mdm.push.SSE_READ_TIMEOUT_MS
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.push.sseDropWakeLockDue
import com.kidslauncher.mdm.push.sseStale
import com.kidslauncher.mdm.push.syncOnSseReopen
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val LOG_TAG = "CommandListenerService"
private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L
private const val NOT_ENROLLED_RETRY_DELAY_MS = 30_000L
private const val EXTRA_SYNC_REASON = "reason"

/**
 * The process anchor (handy step 7, design 07 §2 and the decisions after QA review): an always-on
 * foreground service of type `specialUse` ("parental control enforcement" - no 6 h daily cap, and
 * allowed to start from BOOT_COMPLETED on Android 15, unlike `dataSync`). It keeps the process
 * alive (the screen on/off/unlock signals moved to the process-wide receiver in
 * `lock/PinLockRuntime` in step 10), and every background sync runs inside it
 * ([SyncRunner], with a wake lock and timeouts).
 *
 * Sync nudges arrive over the SSE stream only (design 19 removed FCM): a long-lived connection to
 * `/api/devices/commands/stream` through the embedded tailnet's SOCKS proxy, held whenever this
 * service runs. Every event is a content-free nudge. The server's keepalive comes every 240 s by
 * default, so a 300 s read timeout notices a silently dead stream while the phone is awake. Deep
 * sleep stops both that watchdog and the Handler that runs the reconnect backoff, so (design 19,
 * SSE hardening):
 * - every body read is stamped ([LastByteInterceptor], elapsed realtime); [checkStream] - on every
 *   sync request (the backstop alarm's included) and at screen-on/unlock - reconnects a stream
 *   that is down, and one that has been silent for [com.kidslauncher.mdm.push.SSE_STALE_MS]
 *   (marked down first, like any drop);
 * - the up -> down edge takes a ~30 s wake lock (at most every 10 min, released at the next open),
 *   so the 5 s and 10 s retries run even in deep sleep - after every server restart;
 * - every reopen after a drop syncs ([syncOnSseReopen]): a nudge sent while no stream was
 *   subscribed is lost - at most once per 10 min, except after a deaf stream (last byte >= 300 s
 *   ago: a read timeout or a stale stream), which always syncs.
 * The backstop alarm (15 min while the stream is down) remains the last resort (QA #8).
 *
 * The periodic backstop is [BackstopAlarm] (a while-idle alarm on elapsed realtime), not a timer
 * here: `Handler` time stops in deep sleep. One sync also runs whenever this service starts
 * (after unlock, boot, an update), catching nudges missed while the process was down.
 *
 * Never direct-boot-aware: before the first unlock nothing here may run (CE storage, tsnet, the
 * BFU lockout incidents in CLAUDE.md).
 */
class CommandListenerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var eventSource: EventSource? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopped = false
    private val connectRunnable = Runnable { connect() }
    private var firstStart = true
    /** Elapsed realtime of the last reopen's sync (`null`: none in this process). */
    private var lastReopenSyncAt: Long? = null
    /** Elapsed realtime of the current stream's last bytes (headers or body), stamped by its
     * [LastByteInterceptor] - one per connect, so a cancelled stream can't stamp the next one. */
    private var lastByte: AtomicLong? = null
    /** The dropped stream's last bytes, for the reopen's sync rule (`null`: none recorded). */
    private var lastByteBeforeDrop: Long? = null
    private var dropWakeLock: PowerManager.WakeLock? = null
    /** When the drop wake lock was last taken (elapsed realtime). */
    private var dropWakeLockAt: Long? = null

    private fun buildClient(stamp: AtomicLong): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // The server sends a keepalive comment every SSE_KEEPALIVE_SECS (240 s by default,
            // never more); five minutes of silence means the stream is dead - reconnect.
            .readTimeout(SSE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // The keepalives never reach the listener: stamp every read here (design 19 hole 1).
            .addNetworkInterceptor(LastByteInterceptor { stamp.set(SystemClock.elapsedRealtime()) })
        // Fresh per connect: the server is only reachable through the embedded tailnet's proxy,
        // which may come up after this service (CLAUDE.md, the "by lazy" staleness bug).
        TsnetClient.proxy()?.let { builder.proxy(it) }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // First, unconditionally (ForegroundServiceDidNotStartInTimeException otherwise).
        startForeground(COMMAND_LISTENER_NOTIFICATION_ID, buildNotification())
        running = this
        // The screen on/off/unlock signals (screen time, time rules, the Play window, handy's PIN
        // lock) live in PinLockRuntime since step 10 - registered for the whole process from
        // Application, not for this service's lifetime (QA 10 #4).
        connect()
        BackstopAlarm.schedule(applicationContext)
        // The sync at start runs from onStartCommand (always delivered after onCreate), so a cold
        // start through requestSync runs one sync, not two.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val start = firstStart
        firstStart = false
        if (intent?.action != ACTION_SYNC && start) {
            // One sync at every process start: catches nudges missed while we were down.
            SyncRunner.runInService(applicationContext, "start")
        }
        if (intent?.action == ACTION_DOWNLOADS) {
            // Catalog downloads (design 13) run while this foreground service holds the process.
            AppDownloads.runInService(applicationContext, intent.getStringExtra(EXTRA_SYNC_REASON) ?: "request")
        }
        if (intent?.action == ACTION_SYNC) {
            val reason = intent.getStringExtra(EXTRA_SYNC_REASON) ?: "request"
            SyncRunner.runInService(applicationContext, reason, fromRequest = true)
            // The backstop also restarts a stalled SSE reconnect loop (Handler time stops in
            // deep sleep), and a stream that went silent while it slept.
            checkStream(reason, restartAttempt = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        if (running === this) running = null
        handler.removeCallbacksAndMessages(null)
        stopSse()
        releaseDropWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(getString(R.string.notification_listener_title))
            .setContentText(getString(R.string.notification_listener_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun stopSse() {
        handler.removeCallbacks(connectRunnable)
        eventSource?.cancel()
        eventSource = null
        PushState.sseConnected = false
    }

    /**
     * Main thread. A stream that is down is reconnected now (with [restartAttempt] also one whose
     * connect attempt is still in flight - the backstop, as before); one that is up but silent for
     * [com.kidslauncher.mdm.push.SSE_STALE_MS] is marked down first ([markDown]: the reopen then
     * syncs, the wake lock and the earlier backstop apply) and reconnected.
     */
    private fun checkStream(reason: String, restartAttempt: Boolean) {
        if (stopped) return
        if (!PushState.sseConnected) {
            if (eventSource != null && !restartAttempt) return
            handler.removeCallbacks(connectRunnable)
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            connect()
            return
        }
        val since = SystemClock.elapsedRealtime() - (lastByte?.get() ?: return)
        if (!sseStale(since)) return
        Log.w(LOG_TAG, "Command stream silent for ${since / 1000} s ($reason) - reconnecting")
        val stale = eventSource
        eventSource = null
        stale?.cancel()
        markDown()
        handler.removeCallbacks(connectRunnable)
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        connect()
    }

    private fun connect() {
        if (stopped) return
        eventSource?.cancel()
        eventSource = null

        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceToken = mdm.deviceToken()
        if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            handler.postDelayed(connectRunnable, NOT_ENROLLED_RETRY_DELAY_MS)
            return
        }

        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val request = Request.Builder()
            .url("${base}api/devices/commands/stream")
            .header("Authorization", "Bearer $deviceToken")
            .build()

        val stamp = AtomicLong(SystemClock.elapsedRealtime())
        lastByte = stamp
        eventSource = EventSources.createFactory(buildClient(stamp)).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.i(LOG_TAG, "Command stream connected")
                    handler.post {
                        if (eventSource !== this@CommandListenerService.eventSource || stopped) return@post
                        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                        val wasDown = !PushState.sseConnected
                        PushState.sseConnected = true
                        releaseDropWakeLock()
                        // Catch nudges missed while the stream was down or deaf (design 19,
                        // qa-19-code #2); the sync re-arms the backstop.
                        val now = SystemClock.elapsedRealtime()
                        val sinceSync = lastReopenSyncAt?.let { now - it }
                        val sinceLastByte = lastByteBeforeDrop?.let { now - it }
                        if (wasDown && syncOnSseReopen(sinceSync, sinceLastByte)) {
                            SyncRunner.request(applicationContext, "sse_open")
                            lastReopenSyncAt = now
                        }
                        lastByteBeforeDrop = null
                    }
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.i(LOG_TAG, "Command stream nudge received, syncing early")
                    SyncRunner.request(applicationContext, "sse")
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.i(LOG_TAG, "Command stream closed, reconnecting")
                    handler.post { streamDown(eventSource) }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    Log.w(LOG_TAG, "Command stream connection failed, reconnecting", t)
                    handler.post { streamDown(eventSource) }
                }
            },
        )
    }

    /** Main thread. Ignores callbacks of a stream we already replaced or stopped. */
    private fun streamDown(source: EventSource) {
        if (source !== eventSource) return
        eventSource = null
        markDown()
        scheduleReconnect()
    }

    /**
     * Main thread. The bookkeeping of a drop, on the up -> down edge only: the old stream's last
     * bytes kept for the reopen's sync rule, the wake lock for the first retries
     * (hole 3), and the backstop moved earlier - only ever earlier (15 min): a flapping stream must
     * not keep pushing it out until it never fires.
     */
    private fun markDown() {
        val wasUp = PushState.sseConnected
        PushState.sseConnected = false
        if (!wasUp) return
        lastByteBeforeDrop = lastByte?.get()
        takeDropWakeLock()
        BackstopAlarm.schedule(applicationContext, afterSync = false)
    }

    private fun takeDropWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (!sseDropWakeLockDue(dropWakeLockAt, now)) return
        try {
            val lock = dropWakeLock ?: getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidslauncher:sse_reconnect")
                ?.apply { setReferenceCounted(false) }
                ?.also { dropWakeLock = it }
            lock?.acquire(SSE_DROP_WAKELOCK_MS)
            dropWakeLockAt = now
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't take the reconnect wake lock", e)
        }
    }

    private fun releaseDropWakeLock() {
        try {
            dropWakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't release the reconnect wake lock", e)
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        handler.removeCallbacks(connectRunnable)
        handler.postDelayed(connectRunnable, reconnectDelayMs)
        // Exponential backoff so a server that's genuinely down isn't hammered.
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    companion object {
        const val ACTION_SYNC = "com.kidslauncher.mdm.action.SYNC"
        const val ACTION_DOWNLOADS = "com.kidslauncher.mdm.action.DOWNLOADS"

        /** The live instance, for [checkStream]. Same process only. */
        @Volatile
        private var running: CommandListenerService? = null

        /**
         * At screen-on/unlock (`PinLockRuntime`'s process-wide receiver, design 19 QA #2d): costs
         * nothing, and that is when a lift or a lock matters - reconnects a stream that is down
         * (unless a connect attempt is in flight) or silent too long. A no-op while the anchor
         * isn't running.
         */
        fun checkStream(reason: String) {
            val service = running ?: return
            service.handler.post { service.checkStream(reason, restartAttempt = false) }
        }

        /** Starts the anchor (a no-op if it runs). Safe to call repeatedly. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CommandListenerService::class.java))
        }

        /** Starts the anchor if needed and has it run a sync ([SyncRunner]). A device owner may
         * start a foreground service from the background. */
        fun requestSync(context: Context, reason: String): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CommandListenerService::class.java).setAction(ACTION_SYNC).putExtra(EXTRA_SYNC_REASON, reason),
            )
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't start the anchor service for a sync", e)
            false
        }

        /** Starts the anchor if needed and has it run the download runner ([AppDownloads]): the
         * foreground service keeps the process's network in Doze (design 13 QA #5). */
        fun requestDownloads(context: Context, reason: String): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CommandListenerService::class.java).setAction(ACTION_DOWNLOADS).putExtra(EXTRA_SYNC_REASON, reason),
            )
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't start the anchor service for the downloads", e)
            false
        }
    }
}
