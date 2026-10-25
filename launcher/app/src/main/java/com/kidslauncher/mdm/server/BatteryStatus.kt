package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.dto.BatteryReport

private const val LOG_TAG = "BatteryStatus"

/** Design 35 §2.5: the report carries `battery` ([BatteryStatus]); the server keys off the field. */
const val BATTERY_CAPABILITY = "battery_v1"

/** [SyncRunner.request]'s reason for Android's battery-low and battery-okay edges. */
const val BATTERY_SYNC_REASON = "battery"

/** Design 35 §2.5 (step 3): the battery in the status report, and a report at once on Android's
 * battery-low edge, so the parent's "Battery low" arrives within seconds. Capability `battery_v1`. */
object BatteryStatus {
    /** The reading at every status report, from the sticky `ACTION_BATTERY_CHANGED` (no receiver is
     * registered for it); `null` - left out of the report - when there is none or it can't be read. */
    fun report(context: Context): BatteryReport? = try {
        ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let {
            BatteryReport.of(
                level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                low = it.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false),
            )
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Battery unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * From `Application.initRest`, once per process: `ACTION_BATTERY_LOW`/`ACTION_BATTERY_OKAY`
     * (neither reaches a manifest receiver since Android 8; a not-exported receiver still gets the
     * system's broadcasts) request a sync, whose report carries the new `low` - and, at the low
     * edge, one fresh position (`LowBatteryFix`, 2026-10-25). While the process
     * isn't running (killed, or before the first unlock - `initRest` runs after it) the edge is
     * lost, and the next report (the backstop, a nudge, process start) carries the sticky state.
     * Everything is caught: a receiver fault must never take the home process (and the call path)
     * down.
     */
    fun watch(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 2026-10-25: the low edge asks that sync for one fix (LowBatteryFix); it catches.
                LowBatteryFix.onBatteryEdge(app, intent?.action)
                try {
                    SyncRunner.request(app, BATTERY_SYNC_REASON)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't request a sync: ${e.javaClass.simpleName}")
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the battery: ${e.javaClass.simpleName}")
        }
    }
}
