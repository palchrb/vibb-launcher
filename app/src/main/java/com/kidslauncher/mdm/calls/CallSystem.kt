package com.kidslauncher.mdm.calls

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.CallLog
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.timedWindowActive
import java.time.Instant

private const val LOG_TAG = "CallSystem"

/**
 * The thin Android side of the call path: platform lookups, the call log, placing a call and a
 * few prefs. Every decision is in the pure files of this package.
 */
object CallSystem {

    /** `TelephonyManager.isEmergencyNumber`, or `null` when it throws (no telephony, or the
     * service isn't ready) - [Emergency] then falls back to its static list where allowed. */
    fun platformEmergency(context: Context): (String) -> Boolean? = { number ->
        try {
            context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "isEmergencyNumber failed", e)
            null
        }
    }

    fun isEmergencyOutgoing(context: Context, raw: String?): Boolean =
        Emergency.isEmergencyOutgoing(raw, CallPolicyStore.defaultCc, platformEmergency(context))

    fun roleHeld(context: Context, role: String): Boolean = try {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true
    } catch (e: Exception) {
        false
    }

    fun dialerRoleHeld(context: Context) = roleHeld(context, RoleManager.ROLE_DIALER)

    fun redirectionRoleHeld(context: Context) = roleHeld(context, RoleManager.ROLE_CALL_REDIRECTION)

    fun defaultDialer(context: Context): String? = try {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    } catch (e: Exception) {
        null
    }

    fun defaultSmsPackage(context: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (e: Exception) {
        null
    }

    /**
     * Places a call through Telecom (emergency numbers too - the platform's recommendation for a
     * default dialer). Our redirection and in-call services still check it. Returns false (and
     * says so) if Telecom refuses, e.g. while outgoing calls are restricted.
     */
    fun placeCall(context: Context, number: String): Boolean = try {
        context.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", number, null), Bundle())
        true
    } catch (e: Exception) {
        Log.w(LOG_TAG, "placeCall failed", e)
        Toast.makeText(context, R.string.calls_could_not_call, Toast.LENGTH_LONG).show()
        false
    }

    /** Outgoing calls in the system call log since [sinceMs] (needs READ_CALL_LOG, which the
     * dialer role grants; empty if we can't read it - see [callLogReadable]). */
    fun recentOutgoingCalls(context: Context, sinceMs: Long): List<LoggedCall> =
        readOutgoingCalls(context, sinceMs).orEmpty()

    /** Whether the call log can be read. If not, the callback window can only open from our own
     * record, which we rarely have (the preloaded dialer shows emergency calls) - reported to the
     * server as a warning (QA step 2 #7). */
    fun callLogReadable(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            readOutgoingCalls(context, System.currentTimeMillis()) != null

    /** `null` when the call log can't be read. */
    private fun readOutgoingCalls(context: Context, sinceMs: Long): List<LoggedCall>? = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), sinceMs.toString()),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(LoggedCall(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the call log", e)
        null
    }

    /** See [callbackWindowUntil]. Reads the call log, so not for every call - only when a call
     * would otherwise be blocked. Not before the first unlock: the call log is credential-encrypted
     * and its provider may be unavailable or block then, past the screening budget (task 15) - only
     * our own device-protected record counts until the user unlocks. */
    fun callbackWindowUntil(context: Context, nowMs: Long = System.currentTimeMillis()): Long? =
        callbackWindowUntil(
            nowMs,
            callLogForWindow(CallPolicyStore.userUnlocked(context)) {
                recentOutgoingCalls(context, nowMs - 2 * CALLBACK_WINDOW_MS)
            },
            CallPrefs.recordedWindowUntil(context),
            platformEmergency(context),
        )

    /** The last outgoing call to a platform-confirmed emergency number in the last week (connected
     * or not), for the status report - the parent is told about every emergency call. */
    fun lastEmergencyCallMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long? {
        val platform = platformEmergency(context)
        val fromLog = recentOutgoingCalls(context, nowMs - 7 * 24 * CALLBACK_WINDOW_MS)
            .filter { Emergency.platformConfirms(it.number, platform) }
            .maxOfOrNull { it.startMs }
        return listOfNotNull(fromLog, CallPrefs.recordedEmergencyAtMs(context)).maxOrNull()
    }

    fun isoOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
}

/**
 * Small call-path prefs, written with `commit()` (a call can end with the process being killed).
 * Plain SharedPreferences keys rather than LauncherPreferences entries, so the call services
 * don't depend on Application.onCreate having initialised that class. The emergency-call record
 * lives in device-protected storage (timestamps only), so the callback window also works before
 * the first unlock after a reboot (task 15); the rest is credential-encrypted and only used
 * unlocked.
 */
object CallPrefs {
    private const val DIALER_ROLE_TAKEN_BY_US = "mdm.calls.dialer_role_taken_by_us"
    private const val EMERGENCY_UNTIL_WALL_MS = "mdm.calls.emergency_window_until_wall_ms"
    private const val EMERGENCY_ELAPSED_START_MS = "mdm.calls.emergency_window_elapsed_start_ms"
    private const val EMERGENCY_BOOT = "mdm.calls.emergency_window_boot"
    private const val ROLE_PROMPT_LAST_MS = "mdm.calls.role_prompt_last_ms"
    private const val LAST_ERROR = "mdm.calls.last_error"

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** Device-protected: readable before the first unlock. */
    private fun bootPrefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences("call_boot_state", Context.MODE_PRIVATE)

    fun dialerRoleTakenByUs(context: Context) = prefs(context).getBoolean(DIALER_ROLE_TAKEN_BY_US, false)

    fun dialerRoleTakenByUs(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(DIALER_ROLE_TAKEN_BY_US, value).commit()
    }

    /**
     * Records "a platform-confirmed emergency call is connected now": the callback window then
     * lasts [CALLBACK_WINDOW_MS] by the wall clock AND by elapsed realtime in this boot
     * ([timedWindowActive]), so a clock change or a reboot can't reopen it (QA step 2 #4).
     */
    fun recordEmergencyConnected(context: Context) {
        val start = WindowStart(
            untilWallMs = System.currentTimeMillis() + CALLBACK_WINDOW_MS,
            elapsedStartMs = SystemClock.elapsedRealtime(),
            bootCount = bootCount(context),
        )
        bootPrefs(context).edit()
            .putLong(EMERGENCY_UNTIL_WALL_MS, start.untilWallMs)
            .putLong(EMERGENCY_ELAPSED_START_MS, start.elapsedStartMs)
            .putInt(EMERGENCY_BOOT, start.bootCount)
            .commit()
    }

    private fun recordedWindow(context: Context): WindowStart? {
        val prefs = bootPrefs(context)
        val until = prefs.getLong(EMERGENCY_UNTIL_WALL_MS, 0).takeIf { it > 0 } ?: return null
        return WindowStart(until, prefs.getLong(EMERGENCY_ELAPSED_START_MS, 0), prefs.getInt(EMERGENCY_BOOT, -1))
    }

    /** The recorded window's end, if it is still open by every clock. */
    fun recordedWindowUntil(context: Context): Long? {
        val window = recordedWindow(context) ?: return null
        val open = timedWindowActive(
            window, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context), CALLBACK_WINDOW_MS,
        )
        return if (open) window.untilWallMs else null
    }

    /** When the recorded emergency call connected (for the status report only). */
    fun recordedEmergencyAtMs(context: Context): Long? = recordedWindow(context)?.let { it.untilWallMs - CALLBACK_WINDOW_MS }

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }

    fun rolePromptLastMs(context: Context) = prefs(context).getLong(ROLE_PROMPT_LAST_MS, 0)

    fun rolePromptLastMs(context: Context, value: Long) {
        prefs(context).edit().putLong(ROLE_PROMPT_LAST_MS, value).commit()
    }

    /** The last problem applying the call rules (reported to the server), or null. */
    fun lastError(context: Context): String? = prefs(context).getString(LAST_ERROR, null)

    fun lastError(context: Context, value: String?) {
        if (value == lastError(context)) return
        prefs(context).edit().apply { if (value == null) remove(LAST_ERROR) else putString(LAST_ERROR, value) }.commit()
    }
}
