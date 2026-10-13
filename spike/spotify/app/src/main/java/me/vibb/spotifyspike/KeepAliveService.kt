// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * A mediaPlayback foreground service, like Vibb's playback service. It runs while anything holds
 * it: the main screen's Keep alive checkbox (the App Remote runs) or a loaded Web Playback player
 * ([WebPlayer], item 10), whose WebView it owns - stopping the service destroys the player. With
 * the Web Playback screen's option on it also holds a partial wake lock (the Jelly Star compares
 * the page's polling under real Doze with and without one). Main thread only.
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_spike)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(holders.sorted().joinToString())
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!running) SpikeLog.log("keep-alive foreground service started (${holders.sorted().joinToString()})")
        running = true
        applyWakeLock()
        return START_NOT_STICKY
    }

    /** Holds or releases the partial wake lock to match the option. */
    fun applyWakeLock() {
        val want = Prefs.wakeLock(this)
        val held = wakeLock?.isHeld == true
        if (want && !held) {
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibbspike:player")
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_MAX_MS)
            wakeLock = lock
            SpikeLog.log("wake lock: held (partial, at most ${WAKE_LOCK_MAX_MS / 3_600_000} h)")
        } else if (!want && held) {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
            SpikeLog.log("wake lock: released")
        }
        wakeLock = null
    }

    override fun onDestroy() {
        running = false
        instance = null
        releaseWakeLock()
        WebPlayer.destroy("keep-alive service stopped")
        holders.clear()
        SpikeLog.log("keep-alive foreground service stopped")
        super.onDestroy()
    }

    companion object {
        const val HOLDER_CHECKBOX = "keep-alive checkbox"
        const val HOLDER_WEB_PLAYER = "web player"
        private const val CHANNEL = "keep_alive"
        private const val ID = 1
        private const val WAKE_LOCK_MAX_MS = 12 * 3_600_000L
        private val holders = mutableSetOf<String>()
        private var instance: KeepAliveService? = null

        @Volatile
        var running = false
            private set

        fun isHeldBy(holder: String) = holder in holders

        fun acquire(context: Context, holder: String) {
            holders += holder
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        }

        fun release(context: Context, holder: String) {
            if (!holders.remove(holder)) return
            if (holders.isEmpty()) {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } else {
                SpikeLog.log("keep-alive service stays for: ${holders.sorted().joinToString()}")
            }
        }

        /** The wake-lock option changed: apply it now if the service runs. */
        fun wakeLockChanged() {
            instance?.applyWakeLock()
        }
    }
}
