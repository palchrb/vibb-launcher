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
 * The passive provider's fixes (design 64 §4.3): while the zone watch is on (Regularly with zones,
 * as device owner - `ZoneWatch.zonesOn`), every fix someone else made - the fence's own poll, Maps,
 * the system - reaches us for free (it wakes nothing and turns on no radio). [passiveFixWorth]
 * decides: not a mock fix in a release build (counted), accuracy 200 m or better, newer than the
 * newest fix the phone knows - then it goes to the **kept slot** (`LocateCommands.keepFix`), never
 * the cached (reported) fix (qa-design-64-step2-code Q-1). A sync between heartbeats re-sends the
 * reported fix as before; only a due heartbeat (`Heartbeat.KEPT`) or a crossing's sync promotes the
 * kept one, so the trail stays as dense as the parent's interval. Registered from [ZoneWatch.apply],
 * removed when the watch ends and when the anchor service stops.
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
                cachedTimeMs = LocateCommands.newestKnown()?.timeMs,
                isMock = location.isMock,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                timeMs = location.time,
                acceptMock = BuildConfig.DEBUG,
            )
            when (verdict) {
                FixVerdict.MOCK -> ZoneWatch.countMock(context, location)
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
 * that needs no permission. It fires once the phone moves and disarms itself.
 * Only while the zone watch is on (`ZoneWatch.zonesOn`, qa-design-64-step2-code Q-2).
 * - **The heartbeat's flag**: armed after each **genuinely fresh** fix of our own ([arm]; never
 *   after a fallback to the cached or an old last-known fix, Q-6); the heartbeat skips its fix
 *   while it hasn't fired ([motion] = STILL; at most 4 h in a row, `heartbeatDue`). A new process
 *   doesn't know, so it counts as moved until its first fix.
 * - **The moving series**: while the policy has zones ([watch] with `zones`) the trigger is kept
 *   armed - re-armed at every fire - and each fire starts or feeds [ZoneSeries].
 * No sensor (the emulator) = [Motion.NO_SENSOR]: the heartbeat always fixes, today's behaviour,
 * and there is no series. The sensor service delivers one-shot sensors only to a UID that isn't
 * idle: the anchor's foreground service keeps ours active.
 */
object MotionGate {
    @Volatile
    private var moved = true

    @Volatile
    private var keepArmed = false

    @Volatile
    private var app: Context? = null
    private var listener: TriggerEventListener? = null

    private fun sensor(context: Context): Sensor? =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    fun hasSensor(context: Context): Boolean = sensor(context) != null

    fun motion(context: Context): Motion = when {
        sensor(context) == null -> Motion.NO_SENSOR
        moved -> Motion.MOVED
        else -> Motion.STILL
    }

    /** Regularly as device owner: with [zones] the trigger stays armed for the series. */
    fun watch(context: Context, zones: Boolean) {
        keepArmed = zones
        if (zones) ensureArmed(context)
    }

    /** After a fresh fix of our own with zones: still from here until the trigger fires. One
     * monitor with [fired]'s, so a fire can't land between `moved = false` and the arm (Q-6). */
    @Synchronized
    fun arm(context: Context) {
        if (sensor(context) == null) return
        moved = false
        ensureArmed(context)
    }

    @Synchronized
    private fun ensureArmed(context: Context) {
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sensor(context) ?: return
        app = context.applicationContext
        if (listener != null) return
        val l = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) = fired()
        }
        val armed = try {
            manager.requestTriggerSensor(l, sensor)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the motion sensor: ${e.javaClass.simpleName}")
            false
        }
        if (armed) listener = l else moved = true
    }

    /** The trigger fired (it disarmed itself): moved; with zones the series hears it and the
     * trigger is armed again. */
    private fun fired() {
        val context = synchronized(this) {
            moved = true
            listener = null
            app
        } ?: return
        if (!keepArmed) return
        try {
            ZoneSeries.onMotion(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The moving series failed to start: ${e.javaClass.simpleName}")
        }
        ensureArmed(context)
    }

    /** Regularly ended: disarmed, and unknown again. */
    @Synchronized
    fun stop(context: Context) {
        keepArmed = false
        val l = listener
        listener = null
        moved = true
        if (l == null) return
        try {
            val manager = context.getSystemService(SensorManager::class.java)
            sensor(context)?.let { manager?.cancelTriggerSensor(l, it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't disarm the motion sensor: ${e.javaClass.simpleName}")
        }
    }
}
