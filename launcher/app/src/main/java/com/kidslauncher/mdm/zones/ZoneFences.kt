package com.kidslauncher.mdm.zones

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.dto.PolicyZone

private const val LOG_TAG = "ZoneWatch"
private const val ACTION_ZONE = "com.kidslauncher.mdm.action.ZONE"
private const val ZONE_SCHEME = "vibb-zone"

/** The fences' PendingIntents share a request code; the data URI (`vibb-zone:<id>`) makes each
 * zone's its own. */
private const val FENCE_REQUEST_CODE = 8

/**
 * One platform fence per zone (design 64 §2, §4.2): `LocationManager.addProximityAlert` - AOSP's
 * own `GeofenceManager`, no Play services, no cap of ours or Android's; the system polls a hidden
 * balanced fused fix at most every 30 min (`LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS`,
 * which a device owner can't change) and judges every fused fix any app causes, and fires our
 * receiver with its wake lock. Its judgement is only a reason to take a fix ([ZoneWatch.onFence]).
 *
 * The plan is the pure [zoneFencePlan]: at the first sync of every process and on every change of
 * the zones, ours are removed and the current set added; nothing otherwise. The PendingIntent must
 * be mutable (AOSP fills in `KEY_PROXIMITY_ENTERING`) and targeted: explicit to our non-exported
 * [ZoneAlertReceiver], so only system_server holds it and nothing else can send to it.
 */
object ZoneFences {
    /** This process registered (or decided there was nothing to register) at least once. */
    private var registeredHere = false

    private fun pendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        FENCE_REQUEST_CODE,
        Intent(context, ZoneAlertReceiver::class.java).setAction(ACTION_ZONE).setData(Uri.parse("$ZONE_SCHEME:$id")),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Synchronized
    fun sync(context: Context, zones: List<PolicyZone>, watching: Boolean) {
        val fresh = !registeredHere
        // Only a watching pass counts as this process's registration: an unmanaged phone's release
        // (`ZoneWatch.release`) before the first sync must not skip the re-registration after it.
        if (watching) registeredHere = true
        val stored = ZoneWatch.fenceRecord(context)
        val plan = zoneFencePlan(zones, watching, stored, fresh)
        if (plan.isEmpty) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (id in plan.remove) {
            val pi = pendingIntent(context, id)
            try {
                lm.removeProximityAlert(pi)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't remove the fence of zone $id: ${e.javaClass.simpleName}")
            }
            pi.cancel()
        }
        if (plan.add.isEmpty()) {
            ZoneWatch.saveFenceRecord(context, null)
            Log.i(LOG_TAG, "Fences: ${plan.remove.size} removed, none watched")
            return
        }
        grantLocation(context)
        val added = mutableListOf<Long>()
        for (zone in plan.add) {
            try {
                lm.addProximityAlert(zone.latitude, zone.longitude, zone.radiusM.toFloat(), -1L, pendingIntent(context, zone.id))
                added += zone.id
            } catch (e: SecurityException) {
                // Reported through zone_watch (fences, background_location); tried again at the
                // next sync, never in a loop.
                Log.w(LOG_TAG, "The fence of zone ${zone.id} was refused: SecurityException")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The fence of zone ${zone.id} failed: ${e.javaClass.simpleName}")
            }
        }
        // A record that isn't the whole set has no fingerprint, so the next sync plans again.
        val fingerprint = if (added.size == plan.add.size) fenceFingerprint(plan.add) else ""
        ZoneWatch.saveFenceRecord(context, FenceRecord(fingerprint, added, SystemClock.elapsedRealtime()))
        Log.i(LOG_TAG, "Fences: ${plan.remove.size} removed, ${added.size} of ${plan.add.size} added")
    }

    /** The three location permissions self-granted, as `LocateCommands.currentLocation` does (the
     * fences need fine location, their background delivery the background grant). */
    private fun grantLocation(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        for (permission in listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )) {
            QuickControls.selfGrantPermission(context, dpm, admin, permission)
        }
    }
}

/** A fence's broadcast - only system_server holds the PendingIntent (explicit, not exported). */
class ZoneAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ZONE) return
        val data = intent.data?.takeIf { it.scheme == ZONE_SCHEME } ?: return
        val id = data.schemeSpecificPart?.toLongOrNull() ?: return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        try {
            ZoneWatch.onFence(context.applicationContext, id, entering)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Fence broadcast failed", e)
        }
    }
}
