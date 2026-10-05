package com.kidslauncher.mdm.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.kidslauncher.mdm.server.CommandListenerService
import com.kidslauncher.mdm.server.performBrowserHistorySync
import com.kidslauncher.mdm.server.performJournalSync
import com.kidslauncher.mdm.server.performMdmSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_TAG = "SyncRunner"

/**
 * Runs the background syncs (policy + status + app updates, journal, browser history) for every
 * trigger - an FCM or SSE nudge, the backstop alarm, process start - inside the anchor
 * foreground service (decision after QA review: no separate dataSync service; a dataSync FGS has
 * a 6 h daily budget and may not start from BOOT_COMPLETED on Android 15).
 *
 * [request] takes a timed partial wake lock at once (QA #7: neither an FGS nor
 * `onReceive`/`onMessageReceived` keeps the CPU up), then starts the anchor with
 * [CommandListenerService.ACTION_SYNC] - which also brings the anchor back if it died (QA #8:
 * screen time only counts while it runs). The service calls [runInService]. Requests during a run
 * collapse into one more run ([SyncCoalescer]); each part has a timeout, and the wake lock ends
 * with the last run or after [SYNC_WAKELOCK_MS].
 *
 * The Settings "Sync now" button still calls [performMdmSync] directly (it shows the result);
 * `syncMutex` keeps the two from overlapping.
 */
object SyncRunner {
    private val coalescer = SyncCoalescer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Requests whose service start hasn't reached [runInService] yet - the wake lock stays
     * held for them even when a run ends in between. */
    private var pending = 0

    fun request(context: Context, reason: String) {
        val app = context.applicationContext
        synchronized(this) { pending++ }
        acquireWakeLock(app)
        CommandListenerService.requestSync(app, reason)
    }

    /** From [CommandListenerService] only (main thread). [fromRequest]: delivered for [request]. */
    fun runInService(context: Context, reason: String, fromRequest: Boolean = false) {
        val start = synchronized(this) {
            if (fromRequest && pending > 0) pending--
            coalescer.request(reason)
        }
        if (start) launchRun(context.applicationContext)
    }

    private fun launchRun(context: Context) {
        acquireWakeLock(context)
        scope.launch {
            val reasons = synchronized(this@SyncRunner) { coalescer.takeReasons() }
            Log.i(LOG_TAG, "Sync for $reasons")
            try {
                coroutineScope {
                    // Own coroutines: a slow media upload mustn't delay the policy.
                    launch { withTimeoutOrNull(SIDE_SYNC_TIMEOUT_MS) { performJournalSync(context) } }
                    launch { withTimeoutOrNull(SIDE_SYNC_TIMEOUT_MS) { performBrowserHistorySync(context) } }
                    if (withTimeoutOrNull(SYNC_TIMEOUT_MS) { performMdmSync(context) } == null) {
                        Log.w(LOG_TAG, "Policy sync timed out")
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Sync failed", e)
            }
            try {
                CommandListenerService.onSyncFinished(context)
                BackstopAlarm.schedule(context)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "After-sync work failed", e)
            }
            val (again, idle) = synchronized(this@SyncRunner) {
                val again = coalescer.finished()
                again to (!again && pending == 0)
            }
            if (again) launchRun(context) else if (idle) releaseWakeLock()
        }
    }

    @Synchronized
    private fun acquireWakeLock(context: Context) {
        try {
            val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidslauncher:sync")
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
            lock?.acquire(SYNC_WAKELOCK_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't take the sync wake lock", e)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't release the sync wake lock", e)
        }
    }
}
