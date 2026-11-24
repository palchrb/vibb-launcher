package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.timerules.Clocks
import okhttp3.Interceptor
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private const val LOG_TAG = "TrustedClock"
private const val PREFS = "trusted_clock"
private const val KEY_STATE = "state"

/** The server says its own clock is off: its answers give no anchor. */
const val CLOCK_HEADER = "Vibb-Clock"

/** The status report's three clock fields (`time_state`). */
data class ClockReport(val skewMs: Long, val trust: ClockTrust, val unanchoredMs: Long?)

/**
 * The one clock the phone's decisions read (time rules keep their own clock): the pure rules are [readClock] and
 * [takeAnchor]; this keeps their state in device-protected storage (read in direct boot too) and feeds them the server's
 * answers ([interceptor]) and the satellite time ([readGnss]). Decisions call [nowMs], [clocks] or [localNow] instead of
 * `System.currentTimeMillis()` (`TrustedClockScanTest`). Before [init] - JVM tests, a process that never ran
 * `Application.onCreate` - it is the wall clock.
 */
object TrustedClock {
    @Volatile
    private var app: Context? = null

    private var state: TrustedClockState? = null

    /** Elapsed realtime of the last checkpoint written (at most once a minute between shutdowns). */
    private var checkpointElapsedMs: Long = Long.MIN_VALUE

    fun init(context: Context) {
        app = context.applicationContext
        // The first read of this boot makes the carried anchor now (design: before anything can set the wall clock).
        nowMs()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): TrustedClockState {
        state?.let { return it }
        val loaded = try {
            prefs(context).getString(KEY_STATE, null)?.let { ServerJson.decodeFromString(TrustedClockState.serializer(), it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Clock state unreadable: starting over")
            null
        } ?: TrustedClockState()
        state = loaded
        return loaded
    }

    private fun save(context: Context, next: TrustedClockState) {
        state = next
        try {
            prefs(context).edit().putString(KEY_STATE, ServerJson.encodeToString(TrustedClockState.serializer(), next)).commit()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't keep the clock state: ${e.javaClass.simpleName}")
        }
    }

    /** The trusted time now and how it is known. */
    @Synchronized
    fun read(): ClockRead {
        val wall = System.currentTimeMillis()
        val context = app ?: return ClockRead(wall, ClockTrust.NONE, null)
        val read = readClock(load(context), wall, SystemClock.elapsedRealtime(), BootClock.bootCount())
        read.state?.let {
            Log.i(LOG_TAG, "New boot: time carried over")
            save(context, it)
        }
        return read
    }

    fun nowMs(): Long = read().ms

    /** The clocks the time rules take: trusted time, elapsed realtime, the boot count and the trust. */
    fun clocks(): Clocks {
        val read = read()
        return Clocks(read.ms, SystemClock.elapsedRealtime(), BootClock.bootCount(), read.trust)
    }

    fun localNow(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs()), zone)

    /** Wall minus trusted: how far the phone's own clock is off. */
    fun skewMs(): Long = System.currentTimeMillis() - nowMs()

    /** Considers [anchor]; the state kept when it changed. */
    @Synchronized
    private fun offer(anchor: ClockAnchor) {
        val context = app ?: return
        val taken = takeAnchor(load(context), anchor, System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())
        taken.disagreementMs?.let {
            if (kotlin.math.abs(it) > ANCHOR_DISAGREEMENT_LOG_MS) Log.w(LOG_TAG, "A ${anchor.kind} anchor is ${it / 1000} s from the one in force")
        }
        save(context, taken.state)
        checkpointElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * Anchors on the server's answers (policy, status report and the rest of the API): the `Date` of a 2xx or 304 over a
     * connection the phone trusts ([anchorConnection]), around the request's own elapsed times. Added to the API
     * client after the base's interceptors.
     */
    fun interceptor(path: ServerPath): Interceptor = Interceptor { chain ->
        val e0 = SystemClock.elapsedRealtime()
        val response = chain.proceed(chain.request())
        val e1 = SystemClock.elapsedRealtime()
        try {
            if (response.isSuccessful || response.code == 304) {
                val anchor = serverAnchor(
                    dateMs = response.headers.getDate("Date")?.time,
                    e0Ms = e0,
                    e1Ms = e1,
                    bootCount = BootClock.bootCount(),
                    trustedConnection = anchorConnection(
                        pinConnection(path, response.handshake),
                        com.kidslauncher.mdm.BuildConfig.DEBUG,
                        pinsEnforced = ServerPinStore.enforce(),
                    ),
                    clockOff = response.header(CLOCK_HEADER) == "off",
                )
                anchor?.let(::offer)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the answer's time: ${e.javaClass.simpleName}")
        }
        response
    }

    /** A checked time from the location hardware, when a fix gave one (it throws until then): after a location request
     * and each sync. Never while the last such fix is a made-up one (a mock location app), nor when it can't be read. */
    @android.annotation.SuppressLint("MissingPermission")
    fun readGnss(context: Context) {
        try {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val last = context.getSystemService(android.location.LocationManager::class.java)
                ?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            if (last == null || last.isMock) return
            val gnss = SystemClock.currentGnssTimeClock().millis()
            offer(ClockAnchor(ClockTrust.GNSS.wire, gnss, SystemClock.elapsedRealtime(), BootClock.bootCount()))
        } catch (e: java.time.DateTimeException) {
            // No fix has given a time yet.
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the satellite time: ${e.javaClass.simpleName}")
        }
    }

    /** Keeps the trusted time and the wall clock's offset, for the next boot: at the time rules' snapshots (at most once
     * a minute) and at shutdown ([force]). */
    @Synchronized
    fun checkpoint(force: Boolean = false) {
        val context = app ?: return
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && checkpointElapsedMs != Long.MIN_VALUE && elapsed - checkpointElapsedMs in 0 until 60_000L) return
        val before = load(context)
        val wall = System.currentTimeMillis()
        val read = readClock(before, wall, elapsed, BootClock.bootCount())
        val next = checkpointAt(read.state ?: before, read, wall)
        if (next != before) save(context, next)
        checkpointElapsedMs = elapsed
    }

    /** The wall clock was set (`TIME_SET`): only the offset changes; a change over five minutes asks for a sync, for a
     * fresh anchor and the parent's report. */
    fun onTimeSet(context: Context) {
        val before = synchronized(this) {
            val app = app ?: return
            val state = load(app)
            // A carried time that started behind the checkpoint takes the boot's first forward set (the clock lost its
            // time while off, and automatic time puts it right).
            timeSetAnchor(state, System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())?.let {
                Log.i(LOG_TAG, "The carried time follows the wall clock's first set in this boot")
                save(app, it)
            }
            state.checkpointSkewMs
        }
        val skew = skewMs()
        checkpoint(force = true)
        if (skewChangeAsksSync(before, skew)) {
            Log.i(LOG_TAG, "The wall clock moved: asking for a sync")
            try {
                com.kidslauncher.mdm.push.SyncRunner.request(context, "clock")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't ask for a sync: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The status report's clock fields; the reported step is kept for the next report's hysteresis. */
    @Synchronized
    fun report(): ClockReport {
        val wall = System.currentTimeMillis()
        val context = app ?: return ClockReport(0, ClockTrust.NONE, null)
        val read = read()
        val before = load(context)
        val skew = if (read.trust == ClockTrust.NONE) 0L else reportedSkew(wall - read.ms, before.reportedSkewMs)
        if (skew != before.reportedSkewMs) save(context, before.copy(reportedSkewMs = skew))
        return ClockReport(skew, read.trust, unanchoredMs(before, read.ms))
    }
}
