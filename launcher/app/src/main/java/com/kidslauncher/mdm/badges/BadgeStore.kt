package com.kidslauncher.mdm.badges

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The current unread counts per package, as [BadgeListenerService] last computed them (in memory
 * only - Android re-binds the listener after a restart and it recounts). Listeners run on the
 * main thread.
 */
object BadgeStore {
    @Volatile
    var counts: Map<String, Int> = emptyMap()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun update(newCounts: Map<String, Int>) {
        if (newCounts == counts) return
        counts = newCounts
        main.post { listeners.forEach { it() } }
    }

    /**
     * Whether our listener has notification access. There is no device-owner API to grant it: the parent allows it in
     * the setup flow (design 114, row 4). Reported to the server, which counts it as a setup step while it's missing.
     */
    fun accessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
