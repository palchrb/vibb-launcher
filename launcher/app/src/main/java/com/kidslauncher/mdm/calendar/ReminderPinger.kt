package com.kidslauncher.mdm.calendar

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.day.GlanceReminder
import com.kidslauncher.mdm.day.PingQuiet
import com.kidslauncher.mdm.day.PingSound
import com.kidslauncher.mdm.day.keptPings
import com.kidslauncher.mdm.day.nextPingCheck
import com.kidslauncher.mdm.day.reminderNow
import com.kidslauncher.mdm.day.reminderPing
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import java.time.LocalDate
import java.time.ZoneId

private const val LOG_TAG = "ReminderPing"
private const val ACTION_CHECK = "com.kidslauncher.mdm.action.REMINDER_PING"
private const val PREFS = "reminder_ping"
private const val KEY_PINGED = "pinged"

/** How long the alarm's broadcast stays open for the sound (a notification sound is a second or two). */
private const val SOUND_HOLD_MS = 4_000L

/** The player is let go after this - the sound has ended long before. */
private const val SOUND_RELEASE_MS = 10_000L

/**
 * The quiet row plus a ping (`lock_reminders: "ping"`, `day/ReminderPing`): the phone's notification sound once as a
 * reminder's row comes up, with the screen off too. Unlike the row itself (no alarm: the glance is only seen with the
 * screen on, design 69 §5), the sound needs one: an exact alarm at the next instant a row may come up
 * ([nextPingCheck]; else the next local midnight while the ping is chosen), re-armed at every check - the alarm's, and
 * every calendar change (the provider, the policy; `CalendarStore`'s listeners, which also run at process start). No
 * notification, nothing in the shade, never a vibration: `RingtoneManager`'s default notification sound with
 * `USAGE_NOTIFICATION`, only on ringer mode NORMAL, without Do Not Disturb, outside a call and while no time rule
 * (bedtime included) is in force. Each reminder (event + start) has its moment once ([KEY_PINGED], kept a day).
 */
object ReminderPinger {
    private val main = Handler(Looper.getMainLooper())
    private var playing: Ringtone? = null
    private val release = Runnable { stopSound() }

    /** Process start (`Application`, after `CalendarStore.init`): every calendar change checks and re-arms. */
    fun init(context: Context) {
        val app = context.applicationContext
        CalendarStore.addListener { check(app) }
    }

    /**
     * Today's reminder now (off the main thread), then on the main thread the decision, the sound, the next alarm.
     * [done] runs once the sound has had its time (the alarm's broadcast stays open until then).
     */
    fun check(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        CalendarStore.executeOnWorker {
            var up: GlanceReminder? = null
            var next: Long? = null
            val policy = CalendarStore.policy()
            try {
                if (policy.lockPing) {
                    val now = System.currentTimeMillis()
                    CalendarStore.todayBlocking(app, LocalDate.now())?.let { today ->
                        up = reminderNow(today, policy, now)
                        next = nextPingCheck(today, policy, now)
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't read today's reminders: ${e.javaClass.simpleName}")
            }
            main.post {
                var hold = 0L
                try {
                    val now = System.currentTimeMillis()
                    val pinged = readPinged(app)
                    val decision = if (pinged == null) null else reminderPing(up, policy, quietNow(app), pinged)
                    if (decision?.key != null && writePinged(app, keptPings(pinged!! + decision.key, now))) {
                        if (decision.sound == PingSound.PLAY && play(app)) hold = SOUND_HOLD_MS
                        Log.i(LOG_TAG, "Reminder up: ${decision.sound}")
                    }
                    arm(app, if (policy.lockPing) next ?: nextMidnight(now) else null)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Reminder ping check failed: ${e.javaClass.simpleName}")
                }
                if (done != null) main.postDelayed(done, hold)
            }
        }
    }

    /** Main thread: the phone's state as the row comes up. */
    private fun quietNow(context: Context): PingQuiet {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val ruleOn = try {
            TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy).lock.rules.isNotEmpty()
        } catch (e: Exception) {
            // Unknown is quiet: no sound rather than one under a rule.
            true
        }
        return PingQuiet(
            ringerNormal = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
            interruptionsAll = notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL,
            inCall = audio == null || audio.mode != AudioManager.MODE_NORMAL,
            ruleOn = ruleOn,
        )
    }

    /** The default notification sound, once; `false` when there is none or it can't play. */
    private fun play(context: Context): Boolean = try {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = uri?.let { RingtoneManager.getRingtone(context, it) }
        if (ringtone == null) {
            false
        } else {
            stopSound()
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.isLooping = false
            ringtone.play()
            playing = ringtone
            main.postDelayed(release, SOUND_RELEASE_MS)
            true
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't play the reminder sound: ${e.javaClass.simpleName}")
        false
    }

    private fun stopSound() {
        main.removeCallbacks(release)
        try {
            playing?.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't stop the reminder sound: ${e.javaClass.simpleName}")
        }
        playing = null
    }

    /** `null` = unreadable (before the first unlock): no decision, nothing consumed. */
    private fun readPinged(context: Context): Set<String>? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PINGED, emptySet())?.toSet() ?: emptySet()
    } catch (e: Exception) {
        null
    }

    private fun writePinged(context: Context, keys: Set<String>): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_PINGED, keys).commit()
    } catch (e: Exception) {
        false
    }

    private fun nextMidnight(now: Long): Long {
        val zone = ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Only our own alarm reaches the non-exported [ReminderPingReceiver] (as `TimeRuleAlarm`, security review S-L7). */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderPingReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A second past [at], so the check lands inside the row's time; `null` cancels. */
    private fun arm(context: Context, at: Long?) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pendingIntent(context)
            if (at == null) {
                alarms.cancel(pi)
                return
            }
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 1_000L, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 1_000L, pi)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the reminder ping: ${e.javaClass.simpleName}")
        }
    }

    internal fun onAlarm(context: Context, intent: Intent, done: () -> Unit) {
        if (intent.action == ACTION_CHECK) check(context, done) else done()
    }
}

/** The ping's alarm: not exported, reached only through [ReminderPinger]'s own PendingIntent. */
class ReminderPingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ReminderPinger.onAlarm(context, intent) { pending.finish() }
    }
}
