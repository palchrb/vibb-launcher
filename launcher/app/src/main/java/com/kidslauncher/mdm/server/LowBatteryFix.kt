package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.LocationPolicy
import java.util.concurrent.atomic.AtomicBoolean

/*
 * A last position at low battery (2026-10-25, PLAN "A last position at shutdown and at low
 * battery"): at Android's `ACTION_BATTERY_LOW` (design 35's receiver, `BatteryStatus.watch`) the
 * phone takes one active fix - the balanced path the heartbeat uses (`LocationAction.FRESH_BALANCED`:
 * the fused provider at balanced power for at most 15 s, GPS for at most 15 s when it gives
 * nothing, so 30 s at most), through design 64's mock rule like every fix - in the sync that edge
 * asks for, and reports it in that sync's status report, so a dying phone leaves a fresh position.
 * One per low-battery episode: not again until the battery was charged up to Android's okay level
 * (`ACTION_BATTERY_OKAY`, or a report's reading discharging and not low). A fix of the phone's own
 * younger than [LOW_FIX_RECENT_MS] is reported instead of a new one (qa-last-position-code Q-7).
 * Never under location "Off", never with the phone's Location switch off (this fix doesn't switch
 * it on), only as the device owner. No policy key, no event: the battery event stays as it is.
 */

private const val LOG_TAG = "LowBatteryFix"

/** A fix of the phone's own younger than this makes the low edge report it rather than take a new
 * one (Q-7: a heartbeat or a series check a minute before the edge). */
const val LOW_FIX_RECENT_MS = 2 * 60_000L

/** What the low edge's sync does about location: nothing, a new balanced fix, or the recent fix it
 * holds reported as it is. */
enum class LowFix { NONE, FIX, CACHED }

/** This sync takes the low-battery fix: the edge came, none was taken in this episode, the phone
 * is the device owner with a location policy that isn't "Off" ([policy] `null` = no policy), and
 * its Location switch is on. */
fun lowBatteryFixDue(
    edgeSeen: Boolean,
    episodeTaken: Boolean,
    deviceOwner: Boolean,
    policy: LocationPolicy?,
    systemLocationOn: Boolean,
): Boolean = edgeSeen && !episodeTaken && deviceOwner && policy != null && policy.mode != "off" && systemLocationOn

/** A due low edge: [LowFix.CACHED] when the phone's own newest fix is younger than
 * [LOW_FIX_RECENT_MS] ([ownFixAgeMs] `null` = none; negative - the clock went back - is not
 * recent), else [LowFix.FIX]. */
fun lowFixKind(ownFixAgeMs: Long?): LowFix =
    if (ownFixAgeMs != null && ownFixAgeMs in 0 until LOW_FIX_RECENT_MS) LowFix.CACHED else LowFix.FIX

/** The sync's location step with the low edge: the balanced path for [LowFix.FIX] unless the sync
 * takes a fix anyway (a `locate` keeps its precise one); [LowFix.CACHED] reports the held fix where
 * the policy alone would report none ("Only when asked"). */
fun withLowBatteryFix(action: LocationAction, low: LowFix): LocationAction = when {
    low == LowFix.FIX && !action.fresh -> LocationAction.FRESH_BALANCED
    low == LowFix.CACHED && action == LocationAction.NONE -> LocationAction.CACHED
    else -> action
}

/** The episode after a report's battery reading: over once the phone discharges and Android no
 * longer says low - it was charged up to its okay level (`EXTRA_BATTERY_LOW` stays set while
 * charging until `ACTION_BATTERY_OKAY`, at the low level + 5 %). */
fun lowEpisodeAfterReading(taken: Boolean, battery: BatteryReport?): Boolean =
    taken && !(battery != null && !battery.low && !battery.charging)

/** The episode after a battery broadcast: `ACTION_BATTERY_OKAY` ends it, anything else keeps it. */
fun lowEpisodeAfterEdge(action: String?, taken: Boolean): Boolean =
    taken && action != Intent.ACTION_BATTERY_OKAY

/** Where the episode is kept: CE default prefs in the app, a field in tests. */
interface LowEpisodeFlag {
    fun taken(): Boolean

    fun set(taken: Boolean)
}

/**
 * The episode's steps over [flag] - the edge in memory (the sync it requests takes it), the
 * episode in [flag], written before the fix is asked for (Q-2: every read and write of the flag
 * goes through the pure rules here, tested with a field).
 */
class LowBatteryEpisode(private val flag: LowEpisodeFlag) {
    private val edge = AtomicBoolean(false)

    /** A battery broadcast: LOW marks the edge, OKAY ends the episode (and any edge). */
    fun onEdge(action: String?) {
        when (action) {
            Intent.ACTION_BATTERY_LOW -> edge.set(true)
            Intent.ACTION_BATTERY_OKAY -> edge.set(false)
        }
        val taken = flag.taken()
        val after = lowEpisodeAfterEdge(action, taken)
        if (after != taken) flag.set(after)
    }

    /** The sync's location step: takes the edge; a due one marks the episode first and is a new
     * fix or, with a recent one held ([ownFixAgeMs]), that one ([lowFixKind]). */
    fun take(
        deviceOwner: Boolean,
        policy: LocationPolicy?,
        systemLocationOn: Boolean,
        ownFixAgeMs: Long?,
        log: (String) -> Unit = {},
    ): LowFix {
        if (!edge.getAndSet(false)) return LowFix.NONE
        val taken = flag.taken()
        if (!lowBatteryFixDue(true, taken, deviceOwner, policy, systemLocationOn)) {
            log("Battery low: no fix (taken $taken, owner $deviceOwner, mode ${policy?.mode}, location $systemLocationOn)")
            return LowFix.NONE
        }
        flag.set(true)
        val kind = lowFixKind(ownFixAgeMs)
        log(if (kind == LowFix.FIX) "Battery low: one fix (balanced)" else "Battery low: a fix under 2 min old, reported")
        return kind
    }

    /** Every status report's battery reading ([lowEpisodeAfterReading]). */
    fun observe(battery: BatteryReport?) {
        val taken = flag.taken()
        val after = lowEpisodeAfterReading(taken, battery)
        if (after != taken) flag.set(after)
    }
}

/** The glue: one [LowBatteryEpisode] over CE default prefs (`commit()`); every entry caught. */
object LowBatteryFix {
    private const val TAKEN_KEY = "battery_low_fix_taken"

    @Volatile
    private var app: Context? = null

    private val episode = LowBatteryEpisode(
        object : LowEpisodeFlag {
            override fun taken(): Boolean = app?.let { prefs(it).getBoolean(TAKEN_KEY, false) } ?: false

            override fun set(taken: Boolean) {
                val context = app ?: return
                val edit = prefs(context).edit()
                if (taken) edit.putBoolean(TAKEN_KEY, true) else edit.remove(TAKEN_KEY)
                if (!edit.commit()) Log.w(LOG_TAG, "Couldn't save the low-battery episode")
            }
        },
    )

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun bind(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /** `BatteryStatus`' receiver, before its sync request. Never throws. */
    fun onBatteryEdge(context: Context, action: String?) {
        try {
            bind(context)
            episode.onEdge(action)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't note the battery edge: ${e.javaClass.simpleName}")
        }
    }

    /** The sync's location step (off the main thread): what it does about location. Never throws. */
    fun take(context: Context, deviceOwner: Boolean, policy: LocationPolicy?, systemLocationOn: Boolean, ownFixAgeMs: Long?): LowFix = try {
        bind(context)
        episode.take(deviceOwner, policy, systemLocationOn, ownFixAgeMs) { Log.i(LOG_TAG, it) }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't decide the low-battery fix: ${e.javaClass.simpleName}")
        LowFix.NONE
    }

    /** Every status report's battery reading. Never throws. */
    fun observe(context: Context, battery: BatteryReport?) {
        try {
            bind(context)
            episode.observe(battery)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the low-battery episode: ${e.javaClass.simpleName}")
        }
    }
}
