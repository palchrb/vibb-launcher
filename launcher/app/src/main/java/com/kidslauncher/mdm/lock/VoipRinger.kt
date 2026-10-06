package com.kidslauncher.mdm.lock

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

private const val LOG_TAG = "VoipRinger"

/**
 * The lock's own ring for a VoIP call (design 17, QA #1): while LOCKED the status bar flags that
 * keep the shade closed (DISABLE_NOTIFICATION_ALERTS - never lifted) also mute every notification
 * sound, and Element's ringtone is only its notification sound. So the lock plays the phone's
 * default ringtone and vibrates, as the ringer mode and Do Not Disturb allow ([ringPlan]), from the
 * ring's start while LOCKED until it ends; the power button silences it. Main thread.
 */
object VoipRinger {
    private var ringtone: Ringtone? = null
    private var vibrating = false

    fun start(context: Context) {
        val app = context.applicationContext
        stop(app)
        val plan = try {
            val audio = app.getSystemService(AudioManager::class.java)
            val notifications = app.getSystemService(NotificationManager::class.java)
            val policy = notifications?.notificationPolicy
            val anyone = policy != null &&
                policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 &&
                policy.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY
            ringPlan(audio?.ringerMode ?: RINGER_MODE_NORMAL, notifications?.currentInterruptionFilter ?: INTERRUPTION_FILTER_ALL, anyone)
        } catch (e: Exception) {
            RingPlan(sound = true, vibrate = true)
        }
        if (plan.sound) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE) ?: Settings.System.DEFAULT_RINGTONE_URI
                ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't play the ringtone", e)
            }
        }
        if (plan.vibrate) {
            try {
                vibrator(app)?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0L, 1_000L, 1_000L), 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE),
                )
                vibrating = true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't vibrate", e)
            }
        }
    }

    /** The ring ended, was answered or declined - or the power button was pressed. */
    fun stop(context: Context) {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't stop the ringtone", e)
        }
        ringtone = null
        if (vibrating) {
            try {
                vibrator(context.applicationContext)?.cancel()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't stop vibrating", e)
            }
        }
        vibrating = false
    }

    private fun vibrator(context: Context): Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
}
