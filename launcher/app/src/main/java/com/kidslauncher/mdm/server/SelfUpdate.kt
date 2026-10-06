package com.kidslauncher.mdm.server

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.push.SyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime

private const val LOG_TAG = "SelfUpdate"
private const val DIR = "self_update"
private const val ACTION_WINDOW = "com.kidslauncher.mdm.action.SELF_UPDATE_WINDOW"

/** Not TimeRuleAlarm's (0), BackstopAlarm's (7) or UpdateFence's (11). */
private const val ALARM_REQUEST_CODE = 12

/** `TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED` and its extras (system API). */
private const val ACTION_ECBM_CHANGED = "android.intent.action.EMERGENCY_CALLBACK_MODE_CHANGED"
private const val EXTRA_IN_ECBM = "android.telephony.extra.PHONE_IN_ECM_STATE"
private const val EXTRA_IN_ECBM_LEGACY = "phoneinECMState"

/** Any outgoing emergency call this recent holds the update back (connected or not). */
private const val RECENT_EMERGENCY_CALL_MS = 10 * 60_000L

/**
 * The launcher's own update between download and commit (handy step 11; the rules are the pure
 * SelfUpdatePlan.kt): the pending APK lives in `noBackupFilesDir/self_update` (not the
 * OS-reclaimable cache), the screen state feeds the window gate, and a while-idle alarm wakes a
 * sync when the gate could pass with the screen still off. The download runs in `AppDownloads`
 * (design 13: Wi-Fi only for 3 days when the parent's switch is on, resumable), which moves the
 * finished file in here; the commit runs inside `performMdmSync` (MdmSyncWorker.kt), under its
 * mutex and `AppDownloads.installMutex`.
 */
object SelfUpdate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Elapsed realtime of the last SCREEN_OFF in this process (`null` = the screen is on). */
    @Volatile
    private var screenOffSinceElapsed: Long? = null

    /** A SCREEN_ON/OFF arrived in this process: from then on only a SCREEN_OFF starts the count
     * (qa-11-code #3 - not the process start, while a late SCREEN_OFF is still queued). */
    @Volatile
    private var screenEventSeen = false

    /** This process committed the launcher's own update (its result, if it ever arrives here,
     * is a pre-kill one - Home never left). */
    @Volatile
    var committedInThisProcess = false

    /** The gate's last reason to wait (status report), `null` = none yet / committed. */
    @Volatile
    var lastWait: UpdateWaitReason? = null

    /** Application.initRest: the screen state, a wake-up for a pending update, stale files. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (!interactive(app)) {
            // Off since before this process: counted from the process start (conservative).
            screenOffSinceElapsed = Process.getStartElapsedRealtime()
        }
        scope.launch {
            try {
                cleanup(app)
                if (screenOffSinceElapsed != null) armIfPending(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Self-update init failed", e)
            }
        }
    }

    /** PinLockRuntime's screen receiver (main thread). */
    fun onScreenOff(context: Context) {
        screenEventSeen = true
        screenOffSinceElapsed = SystemClock.elapsedRealtime()
        val app = context.applicationContext
        scope.launch {
            try {
                armIfPending(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't arm the update window check", e)
            }
        }
    }

    /** PinLockRuntime's screen receiver (main thread): nothing goes in while someone uses the phone. */
    fun onScreenOn(context: Context) {
        screenEventSeen = true
        screenOffSinceElapsed = null
        cancelAlarm(context.applicationContext)
    }

    /** A download just became the pending update: with the screen off, look again when the
     * gate could pass. Background thread. */
    fun onPendingStored(context: Context) {
        if (screenOffForMs(context) != null) armIfPending(context.applicationContext)
    }

    /** How long the screen has been off, `null` while on. */
    fun screenOffForMs(context: Context): Long? {
        if (interactive(context)) return null
        return com.kidslauncher.mdm.server.screenOffForMs(
            screenOffSinceElapsed, screenEventSeen, Process.getStartElapsedRealtime(), SystemClock.elapsedRealtime(),
        )
    }

    // ---- signing certificates (qa-11-code #4) -------------------------------------------------

    /** SHA-256 hex of every certificate in [info] (the signers, else the lineage); `null` = none. */
    fun signers(info: android.content.pm.SigningInfo?): Set<String>? {
        info ?: return null
        val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return certs?.mapTo(mutableSetOf()) { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.takeIf { it.isNotEmpty() }
    }

    /** Ours; empty when they can't be read (then not checked). */
    fun ourSigners(context: Context): Set<String> = try {
        signers(
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            ).signingInfo,
        ).orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    // ---- files ----------------------------------------------------------------------------------

    private fun dir(context: Context): File = File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    /** A fresh file for a download (unique per attempt). */
    fun newFile(context: Context): File = File(dir(context), "launcher_${System.nanoTime()}.apk")

    fun file(context: Context, pending: PendingSelfUpdate): File = File(dir(context), pending.fileName)

    /** The pending file exists with the size it was downloaded with (no hashing - that's for the commit). */
    fun fileOk(context: Context, pending: PendingSelfUpdate?): Boolean {
        if (pending == null) return false
        val file = file(context, pending)
        return file.isFile && file.length() == pending.sizeBytes
    }

    /** Drops the pending update and its file. */
    fun dropPending(context: Context) {
        val pending = TrackedAppUpdateState.pendingEntry()?.second
        TrackedAppUpdateState.dropPending(context)
        pending?.let { file(context, it).delete() }
        lastWait = null
    }

    /** Deletes every file in the directory that isn't the pending update's (a finished install's,
     * a dropped one's). Never the file of an install in flight - it is still the pending one.
     * Synchronized with the download runner moving a finished download in (design 13). */
    @Synchronized
    fun cleanup(context: Context) {
        val keep = TrackedAppUpdateState.pendingEntry()?.second?.fileName
        dir(context).listFiles().orEmpty().filter { it.name != keep }.forEach {
            Log.i(LOG_TAG, "Removing ${it.name}")
            it.delete()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- the gate's inputs ----------------------------------------------------------------------

    fun windowInputs(context: Context, pending: PendingSelfUpdate): UpdateWindowInputs = UpdateWindowInputs(
        now = ZonedDateTime.now(),
        screenOffForMs = screenOffForMs(context),
        // A VoIP call over the lock counts too (design 17 QA #4): the update would drop its pin.
        liveCall = OngoingCalls.hasLiveCall || telecomInCall(context) != false || com.kidslauncher.mdm.lock.VoipCalls.liveCall,
        emergency = emergencyRecent(context),
        pendingForMs = System.currentTimeMillis() - pending.downloadedAtMs,
        overdueMs = overdueMs,
    )

    private val overdueMs: Long = if (com.kidslauncher.mdm.BuildConfig.DEBUG) DEBUG_UPDATE_OVERDUE_MS else UPDATE_OVERDUE_MS

    /** Telecom's view; without READ_PHONE_STATE (calls unmanaged) the audio mode answers, like
     * the PIN lock's re-front check. `null` = couldn't tell (counts as a call). */
    fun telecomInCall(context: Context): Boolean? = try {
        context.getSystemService(TelecomManager::class.java)?.isInCall
    } catch (e: SecurityException) {
        try {
            context.getSystemService(AudioManager::class.java)?.mode?.let {
                it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION || it == AudioManager.MODE_RINGTONE
            }
        } catch (e2: Exception) {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** The lock's emergency flow, the callback window after an emergency call, any emergency call
     * in the last 10 minutes, or emergency-callback mode (ECBM). Background thread (call log). */
    private fun emergencyRecent(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (PinLockRuntime.emergencyFlowActive) return true
        val window = try {
            CallSystem.callbackWindowUntil(context, now)
        } catch (e: Exception) {
            null
        }
        if (window != null && window > now) return true
        val lastEmergency = try {
            CallSystem.lastEmergencyCallMs(context, now)
        } catch (e: Exception) {
            null
        }
        if (lastEmergency != null && now - lastEmergency in 0 until RECENT_EMERGENCY_CALL_MS) return true
        // ECBM: the platform's sticky broadcast (its constants are system API - literal strings;
        // best effort, the call log and our own record above cover the usual case).
        return try {
            val sticky = ContextCompat.registerReceiver(
                context, null, IntentFilter(ACTION_ECBM_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
            sticky?.getBooleanExtra(EXTRA_IN_ECBM, false) == true || sticky?.getBooleanExtra(EXTRA_IN_ECBM_LEGACY, false) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun interactive(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isInteractive != false
    } catch (e: Exception) {
        true
    }

    // ---- the window alarm -----------------------------------------------------------------------

    private fun armIfPending(app: Context) {
        val pending = TrackedAppUpdateState.pendingEntry()?.second ?: return
        if (screenOffSinceElapsed == null) return
        armAlarm(app, commitCheckDelayMs(ZonedDateTime.now(), System.currentTimeMillis() - pending.downloadedAtMs, overdueMs))
    }

    /** A sync in [inMs] (the screen is off and the gate could pass then). */
    fun armAlarm(context: Context, inMs: Long) {
        val app = context.applicationContext
        try {
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + inMs.coerceAtLeast(UPDATE_SCREEN_OFF_MS)
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(app))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(app))
            }
            Log.i(LOG_TAG, "Update window check in ${inMs / 1000} s")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the update window check", e)
        }
    }

    private fun cancelAlarm(app: Context) {
        try {
            app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the update window check", e)
        }
    }

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        ALARM_REQUEST_CODE,
        Intent(app, SelfUpdateReceiver::class.java).setAction(ACTION_WINDOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---- after the update -----------------------------------------------------------------------

    /**
     * MY_PACKAGE_REPLACED, or a failed self-update in a restarted process (qa-11-design.md #9):
     * Home to the front, **then** the PIN lock on top when LOCKED - only on a managed phone and
     * never over a call ([bringHomeAfterUpdate]). Main thread.
     */
    fun bringHomeToFront(context: Context, why: String) {
        val app = context.applicationContext
        val decision = currentPolicyDecision().policy
        val bring = bringHomeAfterUpdate(
            appsManaged = decision?.allowlist != null,
            kioskOn = com.kidslauncher.mdm.preferences.LauncherPreferences.mdm().kioskEnabled(),
            liveCall = OngoingCalls.hasLiveCall || com.kidslauncher.mdm.lock.VoipCalls.liveCall,
            telecomInCall = telecomInCall(app),
            pinLockActive = PinLockRuntime.activeOrStored(app),
        )
        if (!bring) {
            Log.i(LOG_TAG, "Home stays where it is after $why")
            return
        }
        // A typed HOME start (design 16, QA #1); Home's onResume roots lock task with the kiosk on
        // and shows the lock when LOCKED - showIfLocked is the backstop.
        com.kidslauncher.mdm.lock.HomeFront.bring(app, "after $why")
        PinLockRuntime.showIfLocked(app)
    }
}

/** The update window alarm - only our own explicit PendingIntent reaches it. */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WINDOW) return
        // The sync commits the pending update if the gate passes (performMdmSync's last step).
        SyncRunner.request(context.applicationContext, "self_update_window")
    }
}
