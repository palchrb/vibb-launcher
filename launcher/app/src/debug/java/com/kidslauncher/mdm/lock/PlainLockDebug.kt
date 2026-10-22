package com.kidslauncher.mdm.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.badges.GlanceLevel
import com.kidslauncher.mdm.badges.GlanceMessageRow
import com.kidslauncher.mdm.badges.GlanceMissedRow
import com.kidslauncher.mdm.badges.GlanceRow
import com.kidslauncher.mdm.badges.GlanceStore
import com.kidslauncher.mdm.badges.GlanceSubtitle
import com.kidslauncher.mdm.badges.glanceSubtitle
import com.kidslauncher.mdm.calls.MissedCallNotifier
import com.kidslauncher.mdm.calls.PhoneBookActivity

/**
 * Debug build only (design 59c, the plain Android lock prototype): the switch, in its own CE prefs file, set by
 * `PlainLockDebugReceiver`. With it on and Android's lock as the gate ([AndroidLock.faceOn]):
 * - `PinLockRuntime.show` starts no glance - Android's own lock screen is the face;
 * - `KeyguardCover` writes [PLAIN_KEYGUARD_FEATURES] (other apps' notifications redacted, ours public);
 * - while LOCKED, every row the glance would show ([GlanceStore.rows]) is posted as one notification of ours on
 *   [CHANNEL], with a public version carrying the same text, so Android's lock screen shows it; all of them go at the
 *   unlock (they are the lock's, not the shade's);
 * - our missed-call notification is VISIBILITY_SECRET (the curated row stands for it).
 * The release build has a `PlainLockDebug` whose [enabled] is always false and whose [start] does nothing.
 */
internal object PlainLockDebug {
    private const val PREFS = "plain_lock_debug"
    private const val KEY_ON = "on"
    const val CHANNEL = "launcher:plain_lock_rows_high"
    private const val NOTE_TAG = "plain_row"
    const val LOG_TAG = "PlainLock"

    private const val KEY_MODE = "mode"

    @Volatile
    private var cached: Boolean? = null

    /** `redact` (others' rows "content hidden" on Android's lock) or `cancel` (the listener cancels a ticked app's
     * notification once our row stands for it - the original is gone from the shade too). */
    @Volatile
    private var cachedMode: String? = null

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** The ids posted by the last refresh (main thread). */
    private val posted = mutableSetOf<Int>()

    /** `cancel` mode: the message rows whose originals we cancelled, with their content intents (main thread). */
    private val held = LinkedHashMap<Int, Pair<GlanceMessageRow, PendingIntent?>>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = cached ?: prefs(context).getBoolean(KEY_ON, false).also { cached = it }

    fun mode(context: Context): String = cachedMode ?: (prefs(context).getString(KEY_MODE, null) ?: "redact").also { cachedMode = it }

    /** Stores the switch, synchronously. */
    fun store(context: Context, on: Boolean): Boolean {
        val ok = prefs(context).edit().putBoolean(KEY_ON, on).commit()
        cached = on
        return ok
    }

    fun storeMode(context: Context, mode: String): Boolean {
        val ok = prefs(context).edit().putString(KEY_MODE, mode).commit()
        cachedMode = mode
        return ok
    }

    /** `Application.initRest`: the rows follow the glance's inputs and the lock's mode. */
    fun start(context: Context) {
        app = context.applicationContext
        main.post {
            GlanceStore.addListener(::refresh)
            MissedCallNotifier.addListener(::refresh)
            PinLockRuntime.addModeListener(::refresh)
            refresh()
        }
    }

    /**
     * Main thread. `redact`: posts the rows while plain, faced and LOCKED; else takes ours away. `cancel`: while plain
     * and faced, every message row is held (ours, in the shade too, until tapped or swiped), and the listener cancels
     * the ticked app's notifications behind it (never ongoing, foreground-service, full-screen or call ones - the
     * glance's candidate rule already leaves those out); missed-call rows as in `redact`.
     */
    fun refresh() {
        val context = app ?: return
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val plain = enabled(context) && AndroidLock.faceOn
            val cancelMode = plain && mode(context) == "cancel"
            val locked = PinLockRuntime.mode == LockMode.LOCKED
            val rows = if (plain) GlanceStore.rows(MissedCallNotifier.current) else emptyList()
            // A held row the kid tapped or swiped (ours no longer active) goes.
            if (held.isNotEmpty()) {
                val active = nm.activeNotifications.filter { it.tag == NOTE_TAG }.map { it.id }.toSet()
                held.keys.retainAll { it in active || it !in posted }
            }
            if (!cancelMode) held.clear()
            val shown = mutableListOf<Triple<Int, GlanceRow, PendingIntent?>>()
            for (row in rows) {
                val id = idOf(row)
                if (cancelMode && row is GlanceMessageRow) {
                    held[id] = row to (GlanceStore.contentIntent(row.notificationKey) ?: held[id]?.second)
                    cancelOriginals(row)
                } else if (locked) {
                    shown += Triple(id, row, null)
                }
            }
            for ((id, pair) in held) if (shown.none { it.first == id }) shown += Triple(id, pair.first, pair.second)
            if (shown.isNotEmpty()) ensureChannel(context, nm)
            val now = mutableSetOf<Int>()
            for ((id, row, intent) in shown) {
                now += id
                nm.notify(NOTE_TAG, id, build(context, row, intent))
            }
            for (id in posted - now) nm.cancel(NOTE_TAG, id)
            posted.clear()
            posted += now
            Log.i(LOG_TAG, "rows: ${shown.size} posted, ${held.size} held (plain=$plain, mode=${mode(context)}, faceOn=${AndroidLock.faceOn}, lock=${PinLockRuntime.mode})")
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "rows failed: ${e.javaClass.simpleName}")
        }
    }

    /** `cancel` mode: the listener cancels the conversation's notifications - the glance's candidates only. */
    private fun cancelOriginals(row: GlanceMessageRow) {
        val listener = com.kidslauncher.mdm.badges.NotificationRuleRuntime.connectedListener() ?: return
        val active = try {
            listener.activeNotifications.orEmpty()
        } catch (e: Exception) {
            return
        }
        var n = 0
        for (sbn in active) {
            if (sbn.packageName != row.packageName) continue
            val notification = sbn.notification ?: continue
            val conversation = notification.shortcutId?.takeIf { it.isNotEmpty() } ?: sbn.key
            if (conversation != row.conversation.conversationKey) continue
            if (sbn.isOngoing || !sbn.isClearable || notification.fullScreenIntent != null) continue
            if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
            if (notification.category == Notification.CATEGORY_CALL) continue
            try {
                listener.cancelNotification(sbn.key)
                n++
            } catch (e: Exception) {
                Log.w(LOG_TAG, "cancel failed: ${e.javaClass.simpleName}")
            }
        }
        // A group summary left with no child (Messages keeps one) would still show as the app's redacted row.
        val left = try {
            listener.activeNotifications.orEmpty().filter { it.packageName == row.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        for (summary in left.filter { (it.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY != 0 }) {
            val children = left.count { it.groupKey == summary.groupKey && (it.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY == 0 }
            if (children == 0 && summary.isClearable && !summary.isOngoing) {
                try {
                    listener.cancelNotification(summary.key)
                    n++
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "cancel failed: ${e.javaClass.simpleName}")
                }
            }
        }
        if (n > 0) Log.i(LOG_TAG, "cancelled $n of ${row.packageName}")
    }

    private fun idOf(row: GlanceRow): Int = when (row) {
        is GlanceMessageRow -> ("m:" + row.packageName + "/" + row.conversation.conversationKey).hashCode()
        is GlanceMissedRow -> ("c:" + (row.number ?: "plain")).hashCode()
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Låseskjermen (prototype)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun build(context: Context, row: GlanceRow, heldIntent: PendingIntent?): Notification {
        val res = context.resources
        val (title, text, icon, intent) = when (row) {
            is GlanceMessageRow -> {
                val label = AppDisplay.shownLabel(context, row.packageName)
                val messages = res.getQuantityString(R.plurals.glance_messages, row.count, row.count)
                val missed = context.getString(R.string.glance_missed)
                val subtitle = when (glanceSubtitle(row.level, row.missedCall, row.count, row.text != null)) {
                    GlanceSubtitle.MISSED_CALL -> "$missed · $label"
                    GlanceSubtitle.MISSED_CALL_AND_COUNT -> "$missed · $messages"
                    GlanceSubtitle.NOTICES -> res.getQuantityString(R.plurals.glance_notices, row.count, row.count)
                    GlanceSubtitle.TEXT -> row.text ?: "$label · $messages"
                    GlanceSubtitle.COUNT -> "$label · $messages"
                }
                val open = heldIntent ?: GlanceStore.contentIntent(row.notificationKey)
                    ?: context.packageManager.getLaunchIntentForPackage(row.packageName)?.let {
                        PendingIntent.getActivity(context, row.packageName.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    }
                Row(row.title.takeIf { row.level >= GlanceLevel.SENDER } ?: label, subtitle, appIcon(context, row.packageName), open)
            }
            is GlanceMissedRow -> {
                val name = row.name ?: row.number
                val title = name ?: res.getQuantityString(R.plurals.glance_missed_many, row.count, row.count)
                val text = if (name == null) null else if (row.count == 1) context.getString(R.string.glance_missed) else res.getQuantityString(R.plurals.glance_missed_many, row.count, row.count)
                val open = PendingIntent.getActivity(
                    context, ("c:" + row.number).hashCode(), PhoneBookActivity.missedCallsIntent(context, row.number),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                Row(title, text, initialDisc(context, name), open)
            }
        }
        val accent = ContextCompat.getColor(context, R.color.kid_accent)
        val small = if (row is GlanceMissedRow) android.R.drawable.stat_notify_missed_call else android.R.drawable.stat_notify_chat
        fun base() = Notification.Builder(context, CHANNEL)
            .setSmallIcon(small)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(icon)
            .setWhen(row.timeMs)
            .setShowWhen(row.timeMs > 0)
            .setCategory(if (row is GlanceMissedRow) Notification.CATEGORY_MISSED_CALL else Notification.CATEGORY_MESSAGE)
            // Its own group: Android 16 would bundle an app's ungrouped notifications into one collapsed summary.
            .setGroup("plain_row_" + idOf(row))
        val public = base().build()
        return base()
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { intent?.let(::setContentIntent) }
            .build()
    }

    private data class Row(val title: String, val text: String?, val icon: Bitmap?, val intent: PendingIntent?)

    private fun appIcon(context: Context, packageName: String): Bitmap? = try {
        context.packageManager.getApplicationIcon(packageName).toBitmap(144, 144)
    } catch (e: Exception) {
        null
    }

    /** The glance's avatar style: a soft peach disc with the dark initial (no photo in the prototype). */
    private fun initialDisc(context: Context, name: String?): Bitmap {
        val size = 144
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8B49A.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val initial = name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
        if (initial != null) {
            paint.color = 0xFF1B1B28.toInt()
            paint.textSize = size * 0.45f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(initial, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        } else {
            context.getDrawable(android.R.drawable.stat_notify_missed_call)?.let {
                it.setTint(0xFF1B1B28.toInt())
                it.setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
                it.draw(canvas)
            }
        }
        return bitmap
    }

    /**
     * The lock wallpaper (FLAG_LOCK only): on - the night ground with the vibb mark drawn at runtime (the launcher
     * icon's foreground vector, no new asset), so SystemUI's face and the bouncer's backdrop are in our colours; off -
     * design 08's default lock fill (navy). Background thread. Returns whether the set worked.
     */
    fun setLockWallpaper(context: Context, on: Boolean): Boolean = try {
        val wm = WallpaperManager.getInstance(context)
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        if (on) {
            canvas.drawColor(ContextCompat.getColor(context, R.color.kid_ground))
            ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let { mark ->
                // The vector is 108 dp with the mark at ~58 % of it: a mark about 40 % of the width, a bit below
                // the middle (SystemUI's clock sits at the top, the notifications under it).
                val side = (w * 0.7f).toInt()
                val left = (w - side) / 2
                val top = (h * 0.62f).toInt() - side / 2
                mark.setBounds(left, top, left + side, top + side)
                mark.alpha = 140
                mark.draw(canvas)
            }
        } else {
            canvas.drawColor(com.kidslauncher.mdm.ui.wallpaper.NAVY_ARGB)
        }
        val ok = wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK) != 0
        bitmap.recycle()
        Log.i(LOG_TAG, "lock wallpaper ${if (on) "night + mark" else "navy"}: $ok")
        ok
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "lock wallpaper failed: ${e.javaClass.simpleName}")
        false
    }
}
