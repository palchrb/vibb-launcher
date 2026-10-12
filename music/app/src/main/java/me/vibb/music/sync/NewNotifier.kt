package me.vibb.music.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.vibb.music.R
import me.vibb.music.data.Snapshot
import me.vibb.music.ui.MainActivity

/**
 * The one "N nye i Musikk" notification (design 21 §4.5): while the Nytt tray holds N playable
 * items, LOW, silent, clearable, not ongoing, `setNumber(N)` (what the launcher's badge counts);
 * a tap opens Nytt; cancelled when the tray empties. A swipe only hides it: it comes back when the
 * count changes. Whether it shows is read from the system (so a reboot or a late notification
 * permission posts it again), and a swipe is recorded by its delete intent (qa-21-step2-code L5).
 */
object NewNotifier {
    const val CHANNEL = "news"
    private const val ID = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_news), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The tray's items that can play now on [snap]'s renderer (the bell's count too). */
    fun count(snap: Snapshot): Int {
        val visible = snap.entries.associate { e -> e.id to snap.visible(e).map { it.key }.toSet() }
        return snap.news.count { it.key in visible[it.entryId].orEmpty() }
    }

    /** The notification counts what plays on the phone (design 26b §3.2: a Storytel book once downloaded). */
    fun update(context: Context, snap: Snapshot) {
        val n = count(snap.forRenderer(false))
        val prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (n == 0) {
            nm.cancel(ID)
            prefs.edit().remove(SWIPED).apply()
            return
        }
        val showing = nm.activeNotifications.firstOrNull { it.id == ID }
        if (showing != null && showing.notification.number == n) return
        if (showing == null && prefs.getInt(SWIPED, -1) == n) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NEWS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.resources.getQuantityString(R.plurals.news_notification, n, n)
        val swiped = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, Dismissed::class.java).putExtra(SWIPED, n),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.music_glyph_music_note)
            .setContentTitle(text)
            .setNumber(n)
            .setSilent(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setDeleteIntent(swiped)
            .build()
        runCatching { nm.notify(ID, notification) } // without POST_NOTIFICATIONS it isn't shown
        prefs.edit().remove(SWIPED).apply()
    }

    private const val SWIPED = "news_swiped"

    /** The kid swiped the notification away at this count: it stays hidden until the count changes. */
    class Dismissed : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            context.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putInt(SWIPED, intent.getIntExtra(SWIPED, -1)).apply()
        }
    }
}
