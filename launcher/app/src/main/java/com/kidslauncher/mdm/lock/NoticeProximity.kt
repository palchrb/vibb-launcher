package com.kidslauncher.mdm.lock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.PowerManager
import android.util.Log

/**
 * Launcher round 7 §3.1.3: one proximity read before a notice wake - a phone in a pocket stays dark.
 * No permission. One `TYPE_PROXIMITY` event within [PROXIMITY_BUDGET_MS]: [proximityNear] says near
 * or far; no sensor, no answer in time or any failure is `null` (the wake goes on). A short partial
 * wake lock keeps the CPU up for the read (the screen is off). The answer comes once, on [handler]'s
 * thread (main).
 */
object NoticeProximity {
    private const val LOG_TAG = "NoticeProximity"

    fun read(context: Context, handler: Handler, answer: (near: Boolean?) -> Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = try {
            sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        } catch (e: Exception) {
            null
        }
        if (sensors == null || sensor == null) {
            answer(null)
            return
        }
        val wake = try {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibb:notice_proximity")
                ?.apply { acquire(PROXIMITY_BUDGET_MS + 500L) }
        } catch (e: Exception) {
            null
        }
        var done = false
        var timeout: Runnable? = null
        lateinit var listener: SensorEventListener
        fun finish(near: Boolean?) {
            if (done) return
            done = true
            timeout?.let(handler::removeCallbacks)
            try {
                sensors.unregisterListener(listener)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't stop the proximity read: ${e.javaClass.simpleName}")
            }
            try {
                if (wake?.isHeld == true) wake.release()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't release the wake lock: ${e.javaClass.simpleName}")
            }
            answer(near)
        }
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull() ?: return
                finish(proximityNear(value, sensor.maximumRange))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        val registered = try {
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } catch (e: Exception) {
            false
        }
        if (!registered) {
            finish(null)
            return
        }
        timeout = Runnable { finish(null) }.also { handler.postDelayed(it, PROXIMITY_BUDGET_MS) }
    }
}
