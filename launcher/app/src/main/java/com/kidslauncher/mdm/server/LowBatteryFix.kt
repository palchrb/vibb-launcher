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
 * One per low-battery episode: not again until the battery was charged above Android's low level
 * (`ACTION_BATTERY_OKAY`, or a report's reading discharging and not low). Never under location
 * "Off", never with the phone's Location switch off (this fix doesn't switch it on), only as the
 * device owner. No policy key, no event: the battery event stays as it is.
 */

private const val LOG_TAG = "LowBatteryFix"

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

/** The sync's location step with the low-battery fix: the balanced path, unless the sync takes a
 * fix anyway (a `locate` keeps its precise one). */
fun withLowBatteryFix(action: LocationAction, due: Boolean): LocationAction =
    if (due && !action.fresh) LocationAction.FRESH_BALANCED else action

/** The episode after a report's battery reading: over once the phone discharges and Android says
 * it isn't low - it was charged above the low level (while charging Android never says low). */
fun lowEpisodeAfterReading(taken: Boolean, battery: BatteryReport?): Boolean =
    taken && !(battery != null && !battery.low && !battery.charging)

/** The glue: the edge in memory (the sync it requests takes it), the episode in CE default prefs
 * (`commit()`), written before the fix is asked for. */
object LowBatteryFix {
    private const val TAKEN_KEY = "battery_low_fix_taken"

    private val edge = AtomicBoolean(false)

    /** `BatteryStatus`' receiver, before its sync request: LOW marks the edge, OKAY ends the
     * episode. Never throws. */
    fun onBatteryEdge(context: Context, action: String?) {
        try {
            when (action) {
                Intent.ACTION_BATTERY_LOW -> edge.set(true)
                Intent.ACTION_BATTERY_OKAY -> {
                    edge.set(false)
                    endEpisode(context)
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't note the battery edge: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The sync's location step (off the main thread): takes the edge and answers whether this sync
     * takes the fix ([lowBatteryFixDue]); the episode is marked first, so a crash or a failed fix
     * never asks again in it. Never throws.
     */
    fun take(context: Context, deviceOwner: Boolean, policy: LocationPolicy?, systemLocationOn: Boolean): Boolean {
        if (!edge.getAndSet(false)) return false
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val taken = prefs.getBoolean(TAKEN_KEY, false)
            val due = lowBatteryFixDue(true, taken, deviceOwner, policy, systemLocationOn)
            if (due) prefs.edit().putBoolean(TAKEN_KEY, true).commit()
            Log.i(LOG_TAG, if (due) "Battery low: one fix (balanced)" else "Battery low: no fix (taken $taken, owner $deviceOwner, mode ${policy?.mode}, location $systemLocationOn)")
            due
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't decide the low-battery fix: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Every status report's battery reading ([lowEpisodeAfterReading]). Never throws. */
    fun observe(context: Context, battery: BatteryReport?) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val taken = prefs.getBoolean(TAKEN_KEY, false)
            if (taken && !lowEpisodeAfterReading(taken, battery)) endEpisode(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the low-battery episode: ${e.javaClass.simpleName}")
        }
    }

    private fun endEpisode(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.contains(TAKEN_KEY)) prefs.edit().remove(TAKEN_KEY).commit()
    }
}
