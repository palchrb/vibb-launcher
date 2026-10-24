package com.kidslauncher.mdm.zones

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.server.LocateCommands
import java.util.concurrent.Executors

private const val LOG_TAG = "ZoneWatch"

/** The passive feed asks for at most one fix a minute: enough to keep the cached fix fresh. */
private const val PASSIVE_INTERVAL_MS = 60_000L

/**
 * The passive provider's fixes (design 64 §4.3): while Regularly is on, as device owner, every fix
 * someone else made - the fence's own poll, Maps, the system - reaches us for free (it wakes
 * nothing and turns on no radio). [passiveFixWorth] decides: not a mock fix in a release build
 * (counted), accuracy 200 m or better, newer than the cached fix - then it **replaces the cached
 * fix only** (`LocateCommands.keepFix`). It is never sent on its own: it reaches the server as the
 * next heartbeat's fix (`Heartbeat.KEPT`) or a crossing's, so the trail stays as dense as the
 * parent's interval. Registered from [ZoneWatch.apply], removed when Regularly ends and when the
 * anchor service stops.
 */
object PassiveFixes {
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: LocationListener? = null

    @Synchronized
    fun update(context: Context, on: Boolean) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        if (!on) {
            listener?.let { l ->
                try {
                    lm.removeUpdates(l)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't stop the passive feed: ${e.javaClass.simpleName}")
                }
            }
            listener = null
            return
        }
        if (listener != null) return
        val app = context.applicationContext
        val l = LocationListener { location -> offer(app, location) }
        try {
            lm.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                LocationRequest.Builder(PASSIVE_INTERVAL_MS).setMinUpdateIntervalMillis(PASSIVE_INTERVAL_MS).build(),
                executor,
                l,
            )
            listener = l
            Log.i(LOG_TAG, "Passive feed on")
        } catch (e: SecurityException) {
            // No location grant yet: the next sync's apply tries again.
            Log.w(LOG_TAG, "The passive feed was refused: SecurityException")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The passive feed failed: ${e.javaClass.simpleName}")
        }
    }

    fun stop(context: Context) = update(context, on = false)

    private fun offer(context: Context, location: Location) {
        try {
            val verdict = passiveFixWorth(
                cachedTimeMs = LocateCommands.cachedFixTimeMs(),
                isMock = location.isMock,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                timeMs = location.time,
                acceptMock = BuildConfig.DEBUG,
            )
            when (verdict) {
                FixVerdict.MOCK -> ZoneWatch.countMock(context, location.time)
                FixVerdict.KEEP -> LocateCommands.keepFix(context, location)
                FixVerdict.INACCURATE, FixVerdict.NOT_NEWER -> {}
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A passive fix failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * The significant-motion sensor (decision 11, §4.5): a one-shot wake-up trigger in the sensor hub
 * that needs no permission - armed after each fix of our own ([arm]), it fires once the phone moves
 * and disarms itself. The heartbeat skips its fix while it hasn't fired ([motion] = STILL; at most
 * 4 h in a row, `heartbeatDue`). No sensor (the emulator) = [Motion.NO_SENSOR]: the heartbeat always
 * fixes, today's behaviour. A new process doesn't know, so it counts as moved until its first fix.
 * The sensor service delivers one-shot sensors only to a UID that isn't idle: the anchor's
 * foreground service keeps ours active.
 */
object MotionGate {
    @Volatile
    private var moved = true
    private var listener: TriggerEventListener? = null

    private fun sensor(context: Context): Sensor? =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    fun hasSensor(context: Context): Boolean = sensor(context) != null

    fun motion(context: Context): Motion = when {
        sensor(context) == null -> Motion.NO_SENSOR
        moved -> Motion.MOVED
        else -> Motion.STILL
    }

    /** After a fix of our own while Regularly: still from here until the trigger fires. */
    @Synchronized
    fun arm(context: Context) {
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sensor(context) ?: return
        moved = false
        if (listener != null) return
        val l = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                moved = true
                synchronized(MotionGate) { listener = null }
            }
        }
        val armed = try {
            manager.requestTriggerSensor(l, sensor)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the motion sensor: ${e.javaClass.simpleName}")
            false
        }
        if (armed) listener = l else moved = true
    }

    /** Regularly ended: disarmed, and unknown again. */
    @Synchronized
    fun stop(context: Context) {
        val l = listener ?: return
        try {
            val manager = context.getSystemService(SensorManager::class.java)
            sensor(context)?.let { manager?.cancelTriggerSensor(l, it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't disarm the motion sensor: ${e.javaClass.simpleName}")
        }
        listener = null
        moved = true
    }
}
