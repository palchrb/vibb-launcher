package com.kidslauncher.mdm.play

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kidslauncher.mdm.INSTALL_MODE_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_INSTALL_MODE
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.dto.InstallModeReport
import com.kidslauncher.mdm.timerules.TimeRuleAlarm
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.ui.HomeActivity
import java.text.DateFormat
import java.time.Instant
import java.time.LocalTime
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val LOG_TAG = "PlayRuntime"
private const val PREFS = "play_state"
private const val UNTIL = "install_mode_until"
private const val ELAPSED = "install_mode_elapsed_start"
private const val BOOT = "install_mode_boot"
private const val ACTION_END_INSTALL_MODE = "com.kidslauncher.mdm.action.END_INSTALL_MODE"

/**
 * The Android side of [PlayPolicy.kt]'s rules (handy step 7): install mode's window (CE prefs
 * `play_state`, `commit()`), the nightly update window from the clock and the screen, and the
 * edges the time-rule alarm must wake for. [AppEnforcer.apply] takes [state]; a change of
 * [PlayState.key] makes `reevaluateLockReasonFromCache` re-apply (screen on/off, the boundary
 * alarm), like a time-rule lock change.
 */
object PlayRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installModeStart(context: Context): WindowStart? {
        val p = prefs(context)
        if (!p.contains(UNTIL)) return null
        return WindowStart(p.getLong(UNTIL, 0L), p.getLong(ELAPSED, 0L), p.getInt(BOOT, -1))
    }

    /** Ends by itself after 15 minutes by every clock, and on reboot. */
    fun installModeActive(context: Context): Boolean =
        installModeActive(installModeStart(context), System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())

    fun installModeUntil(context: Context): Long? =
        installModeStart(context)?.untilWallMs?.takeIf { installModeActive(context) }

    fun installModeReport(context: Context): InstallModeReport? = installModeUntil(context)?.let { InstallModeReport(it) }

    private fun minuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    private fun screenInteractive(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isInteractive != false
    } catch (e: Exception) {
        true
    }

    fun updateWindowActive(context: Context): Boolean = updateWindowActive(minuteOfDay(), screenInteractive(context))

    fun state(context: Context): PlayState = PlayState(
        installMode = installModeActive(context),
        updateWindow = updateWindowActive(context),
    )

    private fun playStoreInstalled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PLAY_STORE, PackageManager.MATCH_UNINSTALLED_PACKAGES).enabled
    } catch (e: Exception) {
        false
    }

    /** The next install-mode end or update-window edge, for the boundary alarm; `null` when the
     * phone isn't managed or has no Play Store (no wakeups for nothing). */
    fun nextEdge(context: Context): Instant? {
        val managed = currentPolicyDecision().policy?.allowlist != null
        val edges = mutableListOf<Instant>()
        installModeUntil(context)?.let { edges += Instant.ofEpochMilli(it) }
        if (managed && playStoreInstalled(context)) {
            val minutes = DEFAULT_UPDATE_WINDOW.minutesToNextEdge(minuteOfDay())
            val startOfMinute = System.currentTimeMillis() / 60_000L * 60_000L
            edges += Instant.ofEpochMilli(startOfMinute + minutes * 60_000L)
        }
        return edges.minOrNull()
    }

    // Install mode

    private fun overrideActive() = OfflineOverride.isActive() || RestrictionsPause.isActive()

    /** Whether install mode may start now (the PIN is asked afterwards, by the caller). */
    fun canStart(context: Context): InstallModeStart {
        val policy = currentPolicyDecision().policy
        val lock = TimeRulesRuntime.currentLock(context, policy, overrideActive())
        return canStartInstallMode(
            appsManaged = policy?.allowlist != null && !overrideActive(),
            pinConfigured = OfflineOverride.isConfigured(),
            pinLockedOut = OfflineOverride.isLockedOut(),
            timeLocked = lock.locked,
            playStoreInstalled = playStoreInstalled(context),
        )
    }

    /** Call only after the PIN was verified and [canStart] said OK. Pins and unsuspends the Play
     * Store (in the background), then opens it. */
    fun startInstallMode(context: Context) {
        val app = context.applicationContext
        val start = BootClock.windowStart(INSTALL_MODE_DURATION_MS)
        prefs(app).edit()
            .putLong(UNTIL, start.untilWallMs)
            .putLong(ELAPSED, start.elapsedStartMs)
            .putInt(BOOT, start.bootCount)
            .commit()
        Log.i(LOG_TAG, "Install mode on until ${Date(start.untilWallMs)}")
        showNotification(app, start.untilWallMs)
        scope.launch {
            // Never let an exception reach the crash handler (it would end the call path too).
            try {
                AppEnforcer.apply(app, currentPolicyDecision().policy)
                TimeRuleAlarm.schedule(app)
                val launch = app.packageManager.getLaunchIntentForPackage(PLAY_STORE)
                if (launch != null) app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // The server logs it (status `install_mode`).
                SyncRunner.request(app, "install_mode")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Starting install mode failed", e)
            }
        }
    }

    /** "End now" (Settings or the notification). Expiry and reboot end it by themselves. */
    fun endInstallMode(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().clear().commit()
        scope.launch {
            try {
                AppEnforcer.apply(app, currentPolicyDecision().policy)
                onInstallModeEnded(app)
                TimeRuleAlarm.schedule(app)
                SyncRunner.request(app, "install_mode_end")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Ending install mode failed", e)
            }
        }
    }

    /** Ends install mode without touching the screen (a time-rule lock began; the lock screen
     * comes up by itself). Any thread. */
    fun cancelInstallMode(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().clear().commit()
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(INSTALL_MODE_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the install-mode notification", e)
        }
    }

    /**
     * After an apply that no longer pins the Play Store: tidy up and bring Home to the front
     * (AOSP finishes tasks that lock task no longer allows - a device check). Background thread;
     * a device owner that is HOME may start activities from the background.
     */
    fun onInstallModeEnded(context: Context) {
        val app = context.applicationContext
        if (!installModeActive(app)) prefs(app).edit().clear().commit()
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(INSTALL_MODE_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the install-mode notification", e)
        }
        try {
            app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't bring Home to the front", e)
        }
    }

    private fun showNotification(context: Context, untilMs: Long) {
        try {
            val end = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, InstallModeReceiver::class.java).setAction(ACTION_END_INSTALL_MODE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(untilMs))
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_INSTALL_MODE)
                .setSmallIcon(R.drawable.baseline_settings_24)
                .setContentTitle(context.getString(R.string.notification_install_mode_title, time))
                .setContentText(context.getString(R.string.notification_install_mode_text))
                .setOngoing(true)
                .setSilent(true)
                .setTimeoutAfter(INSTALL_MODE_DURATION_MS)
                .addAction(0, context.getString(R.string.notification_install_mode_end), end)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(INSTALL_MODE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't show the install-mode notification", e)
        }
    }
}

/** "End now" on the install-mode notification - our own explicit PendingIntent only. */
class InstallModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_END_INSTALL_MODE) return
        PlayRuntime.endInstallMode(context)
    }
}
