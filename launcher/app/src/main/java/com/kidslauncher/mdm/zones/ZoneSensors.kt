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
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.places.PlacesDebugHooks
import com.kidslauncher.mdm.server.LocateCommands
import com.kidslauncher.mdm.server.TrustedClock
import java.util.concurrent.Executors

private const val LOG_TAG = "ZoneWatch"

/** The passive feed asks for at most one fix a minute: enough to keep the cached fix fresh. */
private const val PASSIVE_INTERVAL_MS = 60_000L

/**
 * The passive provider's fixes (design 64 §4.3, design 125 D3): every fix someone else made - the fence's own poll,
 * Maps, the system - reaches us for free (it wakes nothing and turns on no radio). On every Regularly phone as device
 * owner (`ZoneWatch.stillOn`, design 125 D3): a `gps` fix that passes design 125's D1 teaches the Wi-Fi places
 * ([com.kidslauncher.mdm.places.WifiPlaces.offerPassive]). With zones ([keep], `ZoneWatch.zonesOn`) also
 * [passiveFixWorth]: not a mock fix in a release build (counted), accuracy 200 m or better, newer than the newest fix
 * the phone knows - then it goes to the **kept slot** (`LocateCommands.keepFix`), never the cached (reported) fix
 * (qa-design-64-step2-code Q-1). A sync between heartbeats re-sends the reported fix as before; only a due heartbeat
 * (`Heartbeat.KEPT`) or a crossing's sync promotes the kept one, so the trail stays as dense as the parent's interval.
 * Registered from [ZoneWatch.apply], removed when the still rule ends and when the anchor service stops.
 */
object PassiveFixes {
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: LocationListener? = null

    @Volatile
    private var keep = false

    @Synchronized
    fun update(context: Context, on: Boolean, keep: Boolean = false) {
        this.keep = on && keep
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
        com.kidslauncher.mdm.places.WifiPlaces.offerPassive(context, location)
        if (!keep) return
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
            // Another app's fix taken in silence left the PWA's newest position unexplained in the log (2026-11-23);
            // never the coordinates.
            if (verdict == FixVerdict.KEEP) {
                val ageS = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000
                val sig = com.kidslauncher.mdm.places.WifiPlaces.signal(location)
                Log.i(LOG_TAG, "Passive fix kept provider=${location.provider ?: "-"} sig=$sig age=${ageS}s")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A passive fix failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * The significant-motion sensor (design 64 decision 11 §4.5, design 124 D2-D4, D7-D8): a one-shot
 * wake-up trigger in the sensor hub that needs no permission. It fires once the phone moves and
 * disarms itself. On every Regularly phone as device owner (`ZoneWatch.stillOn`), zones or not.
 * - **The heartbeat's flags** ([MotionFlags], under this object's monitor): armed at the first sync
 *   of every process ([watch]), after each genuinely fresh fix of ours and after every heartbeat try
 *   ([afterTry]); the heartbeat skips its fix while it hasn't fired (`STILL`, at most 4 h after the
 *   last fresh fix) and backs off after a failed try while it hasn't (`QUIET`). A new process, a
 *   refused registration, a stopped anchor and Off count as moved.
 * - **Since when it is still** ([StillOrigin], kept in `ZoneWatch`'s prefs): every fire, arm, mark and disarm moves it,
 *   and our fresh fixes after [afterTry] ([afterFreshFix]); the report's `still_since_age_ms` ([reportedStillSinceAge]).
 * - **The moving series**: while the policy has zones ([watch] with `zones`) the trigger is kept
 *   armed - re-armed at every fire - and each fire starts or feeds [ZoneSeries]. Without zones a
 *   fire only marks the flags; the next try arms it again.
 * No sensor (the emulator) or one distrusted (D7, `ZoneWatch.motionTrusted`) = [Motion.NO_SENSOR]:
 * the heartbeat always fixes, today's behaviour, and there is no series. The sensor service
 * delivers one-shot sensors only to a UID that isn't idle: the anchor's foreground service keeps
 * ours active, and without it [snapshot] says `MOVED` (D4).
 */
object MotionGate {
    /** Guarded by this object's monitor. */
    private var flags = MotionFlags(movedAtElapsedMs = SystemClock.elapsedRealtime())

    @Volatile
    private var keepArmed = false

    @Volatile
    private var app: Context? = null
    private var listener: TriggerEventListener? = null
    private var armedHere = false

    /** Debug builds only: the emulator gate's made-up trigger is armed (design 134 §7, [PlacesDebugHooks]). */
    private var fakeArmed = false

    /** When the phone went still ([StillOrigin], the owner's report 2026-11-27): read from `ZoneWatch`'s prefs once per
     * process ([origin]), written at every change. Guarded by this object's monitor. */
    private var stillOrigin: StillOrigin? = null

    /** A mark before this process read the origin (no context yet): applied when it does. */
    private var markedBeforeLoad: Long? = null

    private fun origin(context: Context): StillOrigin = stillOrigin ?: run {
        val kept = (ZoneWatch.stillOrigin(context) ?: StillOrigin()).afterRestart(TrustedClock.nowMs())
        val loaded = markedBeforeLoad?.let { kept.marked(it) } ?: kept
        markedBeforeLoad = null
        stillOrigin = loaded
        loaded
    }

    private fun moveOrigin(context: Context, step: (StillOrigin) -> StillOrigin) {
        val before = origin(context)
        val after = step(before)
        if (after == before) return
        stillOrigin = after
        ZoneWatch.saveStillOrigin(context, after)
    }

    /** `zone_watch.still_since_age_ms` for a report that says [still] ([StillOrigin.reportedAgeMs]); every report marks
     * the origin alive ([StillOrigin.alive]), what a new process measures its gap from. */
    @Synchronized
    fun reportedStillSinceAge(context: Context, still: Boolean): Long? {
        val now = TrustedClock.nowMs()
        moveOrigin(context.applicationContext) { it.alive(now) }
        return origin(context.applicationContext).reportedAgeMs(still, now)
    }

    /** A genuinely fresh fix of ours at [fixMs] (trusted clock, `trustedTimeMs`), after [afterTry]: [moving], where it lies
     * against the last fresh fix ([placeAgainst]) and whether the gate had no trusted sensor ([noSensor]); with a fire
     * during the attempt (the count moved from [firesAtStart]) the fire's time stands. */
    @Synchronized
    fun afterFreshFix(context: Context, firesAtStart: Int, fixMs: Long, moving: Boolean, place: Place, noSensor: Boolean) {
        if (flags.fires != firesAtStart) return
        moveOrigin(context.applicationContext) { it.afterFreshFix(fixMs, moving, place, noSensor) }
    }

    private fun sensor(context: Context): Sensor? =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    /** The phone has the sensor - or, in a debug build only, the emulator gate's made-up trigger stands in for it. */
    fun hasSensor(context: Context): Boolean = sensor(context) != null || PlacesDebugHooks.motionSensor()

    /** What an attempt reads before its LocationManager call, in one go (M1). */
    @Synchronized
    fun snapshot(context: Context): MotionSnapshot {
        val trusted = hasSensor(context) && ZoneWatch.motionTrusted(context)
        val motion = flags.motion(trusted, com.kidslauncher.mdm.server.CommandListenerService.isRunning)
        return MotionSnapshot(motion, flags.fires, flags.stillSinceMs, flags.movedAtElapsedMs, mismatched = flags.mismatchSinceFresh)
    }

    /** Design 134 D16 (revision 3): the cap try's scan clearly isn't the vouch's place - `STILL` ends
     * ([MotionFlags.scanMismatch]); with a fire during the attempt nothing changes. */
    @Synchronized
    fun scanMismatch(context: Context, firesAtStart: Int, nowMs: Long) {
        if (flags.fires == firesAtStart) moveOrigin(context.applicationContext) { it.mismatched(TrustedClock.nowMs()) }
        flags = flags.scanMismatch(firesAtStart, nowMs)
    }

    /** Regularly as device owner (D2): armed now if it isn't - at every process's first sync -, and
     * with [zones] kept armed for the series. */
    fun watch(context: Context, zones: Boolean) {
        keepArmed = zones
        ensureArmed(context, if (armedHere) "sync" else "start")
    }

    /** After one of our own attempts ([MotionFlags.afterTry]): a genuinely fresh fix ([freshFixMs])
     * or the heartbeat's try ([heartbeat]) arms the trigger again. One monitor with [fired]'s. True
     * when no fire came during the attempt (the count held). [moving]: the fresh fix was on the move
     * ([fixMoving]) - no `STILL` or `QUIET` until one at rest. [withheld]: design 134 D4's inconclusive try - the flags
     * stay, the trigger is armed again as after any heartbeat try. */
    fun afterTry(context: Context, firesAtStart: Int, freshFixMs: Long?, heartbeat: Boolean, nowMs: Long, moving: Boolean, withheld: Boolean = false): Boolean {
        val held = synchronized(this) {
            flags = flags.afterTry(firesAtStart, freshFixMs, heartbeat, nowMs, moving, withheld)
            flags.fires == firesAtStart
        }
        if (freshFixMs != null || heartbeat) ensureArmed(context, if (freshFixMs != null) "fresh" else "try")
        return held
    }

    @Synchronized
    private fun ensureArmed(context: Context, reason: String) {
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        app = context.applicationContext
        if (listener != null || fakeArmed) return
        val sensor = sensor(context)
        if (sensor == null) {
            // Design 134 §7: the debug build's made-up trigger, armed and fired as the real one; a release build's
            // twin arms nothing.
            if (PlacesDebugHooks.armTrigger { fired() }) {
                fakeArmed = true
                armedHere = true
                moveOrigin(context.applicationContext) { it.armed(TrustedClock.nowMs()) }
                Log.i(LOG_TAG, "Motion trigger armed ($reason)")
            }
            return
        }
        val l = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) = fired()
        }
        val armed = try {
            manager.requestTriggerSensor(l, sensor)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the motion sensor: ${e.javaClass.simpleName}")
            false
        }
        armedHere = true
        if (armed) {
            listener = l
            moveOrigin(context.applicationContext) { it.armed(TrustedClock.nowMs()) }
            Log.i(LOG_TAG, "Motion trigger armed ($reason)")
        } else {
            // Nothing watches: nothing may vouch.
            flags = flags.moved(SystemClock.elapsedRealtime())
            moveOrigin(context.applicationContext) { it.unwatched() }
            Log.w(LOG_TAG, "Motion trigger refused")
        }
    }

    /** The trigger fired (it disarmed itself): moved; with zones the series hears it and the
     * trigger is armed again. */
    private fun fired() {
        val context = synchronized(this) {
            flags = flags.moved(SystemClock.elapsedRealtime())
            listener = null
            fakeArmed = false
            app?.also { a -> moveOrigin(a) { it.fired(TrustedClock.nowMs()) } }
        }
        Log.i(LOG_TAG, "Motion trigger fired")
        // Design 125 D11: the teaching fix 2 min after a fire (its own conditions are checked when it runs).
        context?.let { com.kidslauncher.mdm.places.WifiPlaces.armTeach(it) }
        if (context == null || !keepArmed) return
        // Off the sensor callback's main thread (F-4): the series reads the policy and prefs.
        ZoneSeries.launch {
            ZoneSeries.onMotion(context)
            ensureArmed(context, "series")
        }
    }

    /** Evidence the phone moved that the sensor may have missed - a fence crossing, a series' side
     * change (F-3) - or that nothing watched: Off, the anchor stopped (D2, D4). No `still` until the
     * next fresh fix. */
    @Synchronized
    fun markMoved() {
        flags = flags.moved(SystemClock.elapsedRealtime())
        val now = TrustedClock.nowMs()
        val a = app
        if (a != null) moveOrigin(a) { it.marked(now) } else markedBeforeLoad = now
    }

    /** Regularly ended: disarmed, and moved. */
    @Synchronized
    fun stop(context: Context) {
        keepArmed = false
        disarm(context)
    }

    /** The anchor service stopped (D4, QA L3): our UID may go idle and the registration may never
     * deliver - disarmed and moved; the next sync's [watch] registers it again. */
    @Synchronized
    fun anchorStopped(context: Context) = disarm(context)

    @Synchronized
    private fun disarm(context: Context) {
        val l = listener
        listener = null
        flags = flags.moved(SystemClock.elapsedRealtime())
        moveOrigin(context.applicationContext) { it.unwatched() }
        if (fakeArmed) PlacesDebugHooks.disarmTrigger()
        fakeArmed = false
        if (l == null) return
        try {
            val manager = context.getSystemService(SensorManager::class.java)
            sensor(context)?.let { manager?.cancelTriggerSensor(l, it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't disarm the motion sensor: ${e.javaClass.simpleName}")
        }
    }
}
