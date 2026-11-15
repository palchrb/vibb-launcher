package com.kidslauncher.mdm.shortcuts

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Snarveier's foreground service (design 99 §2.1, §2.4): its one notification is the panel - only a colorized
 * foreground-service notification stays at the top of the shade and escapes Android 16's grouping (§1, S4/S5). It holds
 * no receivers, no wake lock and does no work: [ShortcutsRuntime] reads, builds and posts.
 *
 * One owner (Q99-L4): only [ShortcutsRuntime] starts it, and only the service stops itself. `onStartCommand` always
 * answers with `startForeground` first (a `startForegroundService` must be), then asks the runtime again and stops when
 * the panel isn't wanted. Nothing calls `stopService`, so a stop can never land before the answer. `START_NOT_STICKY`:
 * after a process death `Application.initRest` decides again.
 */
class ShortcutsService : Service() {
    companion object {
        /** The running instance (main thread writes). */
        @Volatile
        var instance: ShortcutsService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(SHORTCUTS_NOTIFICATION_ID, ShortcutsRuntime.notificationForStart(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w("Shortcuts", "startForeground failed: ${e.javaClass.simpleName}")
        }
        ShortcutsRuntime.serviceStarted()
        if (!ShortcutsRuntime.panel.shown) stopNow("not wanted at start")
        return START_NOT_STICKY
    }

    /** The panel isn't wanted (main thread): the notification removed with the service. */
    fun stopNow(why: String) {
        Log.i("Shortcuts", "Panel removed: $why")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        ShortcutsRuntime.serviceGone()
        super.onDestroy()
    }
}
