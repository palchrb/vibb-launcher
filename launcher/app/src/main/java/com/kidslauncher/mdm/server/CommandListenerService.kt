package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.COMMAND_LISTENER_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.BackstopAlarm
import com.kidslauncher.mdm.push.FcmSupport
import com.kidslauncher.mdm.push.PushState
import com.kidslauncher.mdm.push.PushTransport
import com.kidslauncher.mdm.push.SSE_READ_TIMEOUT_MS
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.push.syncOnSseReopen
import java.util.concurrent.TimeUnit
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
 * Sync nudges arrive one of two ways ([com.kidslauncher.mdm.push.decidePushTransport]):
 * - **FCM** (Play services' one shared connection): [com.kidslauncher.mdm.push.KidFcmService];
 *   this service then holds no network connection and sets no timers - an idle FGS costs no
 *   wakeups.
 * - **SSE** (fallback whenever FCM isn't proven to work): a long-lived connection to
 *   `/api/devices/commands/stream` through the embedded tailnet's SOCKS proxy. Every event is a
 *   content-free nudge. The server's keepalive comes every 120 s, so a 300 s read timeout notices
 *   a silently dead stream. The reconnect loop runs on a Handler, which stalls in deep sleep; the
 *   backstop alarm (15 min while the stream is down) reconnects too (QA #8).
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
    private var sseWanted = false
    private val connectRunnable = Runnable { connect() }
    private var firstStart = true
    /** Elapsed realtime when the stream went down (`null`: never up in this process). */
    private var sseDownSince: Long? = null

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // The server sends a keepalive comment every SSE_KEEPALIVE_SECS (120 s by default);
            // five minutes of silence means the stream is dead - reconnect.
            .readTimeout(SSE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
        // Firebase is initialised when the anchor starts (decision after QA review) - never before
        // the first unlock: this service isn't direct-boot-aware.
        FcmSupport.ensureInitialized(applicationContext)
        reevaluateTransport()
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
            // deep sleep).
            if (sseWanted && !PushState.sseConnected) {
                handler.removeCallbacks(connectRunnable)
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                connect()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        if (running === this) running = null
        handler.removeCallbacksAndMessages(null)
        stopSse()
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

    /** Main thread. Starts or stops the SSE stream to match the transport decision. */
    private fun reevaluateTransport() {
        if (stopped) return
        val decision = try {
            FcmSupport.decide(applicationContext, currentPolicyDecision().policy?.push)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Transport decision failed - using SSE", e)
            null
        }
        val wantSse = decision == null || decision.transport == PushTransport.SSE
        if (wantSse == sseWanted) return
        sseWanted = wantSse
        if (wantSse) {
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            connect()
        } else {
            Log.i(LOG_TAG, "FCM works for this phone - closing the command stream")
            stopSse()
        }
    }

    private fun stopSse() {
        handler.removeCallbacks(connectRunnable)
        eventSource?.cancel()
        eventSource = null
        PushState.sseConnected = false
    }

    private fun connect() {
        if (stopped || !sseWanted) return
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

        eventSource = EventSources.createFactory(buildClient()).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.i(LOG_TAG, "Command stream connected")
                    handler.post {
                        if (eventSource !== this@CommandListenerService.eventSource || !sseWanted) return@post
                        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                        val wasDown = !PushState.sseConnected
                        PushState.sseConnected = true
                        // Catch nudges missed while the stream was down - only if it was down long
                        // enough to miss one (QA step 7 #5); the sync re-arms the backstop.
                        val downFor = sseDownSince?.let { android.os.SystemClock.elapsedRealtime() - it }
                        if (wasDown && syncOnSseReopen(downFor)) SyncRunner.request(applicationContext, "sse_open")
                        sseDownSince = null
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
        val wasUp = PushState.sseConnected
        PushState.sseConnected = false
        if (wasUp) sseDownSince = android.os.SystemClock.elapsedRealtime()
        // Only on the up -> down edge, and only ever earlier (15 min): a flapping stream must not
        // keep pushing the backstop out until it never fires.
        if (wasUp) BackstopAlarm.schedule(applicationContext, afterSync = false)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (stopped || !sseWanted) return
        handler.removeCallbacks(connectRunnable)
        handler.postDelayed(connectRunnable, reconnectDelayMs)
        // Exponential backoff so a server that's genuinely down isn't hammered.
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    companion object {
        const val ACTION_SYNC = "com.kidslauncher.mdm.action.SYNC"
        const val ACTION_DOWNLOADS = "com.kidslauncher.mdm.action.DOWNLOADS"

        /** The live instance, for [onSyncFinished]. Same process only. */
        @Volatile
        private var running: CommandListenerService? = null

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

        /** After every sync: the policy's `push` may have changed the transport. */
        fun onSyncFinished(context: Context) {
            val service = running ?: return
            service.handler.post { service.reevaluateTransport() }
        }
    }
}
