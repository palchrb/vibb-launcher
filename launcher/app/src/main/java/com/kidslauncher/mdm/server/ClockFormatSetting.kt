package com.kidslauncher.mdm.server

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.server.dto.LauncherUi
import com.kidslauncher.mdm.server.dto.PolicyResponse

private const val LOG_TAG = "ClockFormat"

/** "Follow the phone": the launcher writes nothing (and clears only its own earlier value). */
const val CLOCK_FORMAT_SYSTEM = "system"

/** The policy's `launcher_ui.clock_format`, read leniently: "24" or "12", anything else "system". */
fun clockFormatChoice(raw: String?): String = if (raw == "24" || raw == "12") raw else CLOCK_FORMAT_SYSTEM

/** What [ClockFormatSetting.apply] does with `Settings.System.TIME_12_24` - see [clockFormatWrite]. */
sealed interface ClockFormatWrite {
    data object Keep : ClockFormatWrite
    /** Write [value] ("24"/"12"). */
    data class Put(val value: String) : ClockFormatWrite
    /** Put the setting back to unset (the locale's default): it still holds the value we wrote. */
    data object Clear : ClockFormatWrite
    /** Forget our earlier write without touching the setting: someone else changed it since. */
    data object Forget : ClockFormatWrite
}

/**
 * Design 34 P12: "24"/"12" writes `TIME_12_24` when it differs. "Follow the phone" writes nothing,
 * except clearing the value this launcher wrote earlier ([written], CE prefs) once - only while the
 * setting still holds it; a value someone set by hand since stays.
 */
fun clockFormatWrite(choice: String, current: String?, written: String?): ClockFormatWrite = when {
    choice != CLOCK_FORMAT_SYSTEM -> if (current == choice) ClockFormatWrite.Keep else ClockFormatWrite.Put(choice)
    written == null -> ClockFormatWrite.Keep
    current == written -> ClockFormatWrite.Clear
    else -> ClockFormatWrite.Forget
}

/**
 * The status report's `clock_format_applied`: `null` for "Follow the phone" (nothing to apply),
 * else whether the phone's `TIME_12_24` is the choice - `false` covers a missing "Modify system
 * settings" access and a write the system refused (read back, not trusted from the write).
 */
fun clockFormatApplied(choice: String, current: String?): Boolean? =
    if (choice == CLOCK_FORMAT_SYSTEM) null else current == choice

/**
 * Writes the parent's clock format into `Settings.System.TIME_12_24` (design 34 P12), so the status
 * bar and Vibb's own clocks ([com.kidslauncher.mdm.ui.ClockFormat]) show the same. A device owner
 * can't write it (`setSystemSetting` allows only brightness and the screen-off timeout); an app may,
 * with the "Modify system settings" app-op (`WRITE_SETTINGS`, declared in the manifest), which the
 * device owner can't grant: `adb shell appops set me.vibb.launcher WRITE_SETTINGS allow` at
 * provisioning, or the parent in Android Settings during a pause. Without it nothing is written and
 * the status says `false` (the PWA warns). Whether SettingsProvider refuses the write while our
 * `DISALLOW_CONFIG_DATE_TIME` is on is a device check; a refusal reads back as `false` too, and the
 * restriction is never lifted for it. Not a restriction: the override and the pause change nothing.
 * Runs from every [AppEnforcer.apply] (background, under its lock); a policy without `launcher_ui`
 * (none yet, an older server, the last-enforced fallback) leaves the setting alone.
 */
object ClockFormatSetting {
    private const val PREFS = "clock_format_state"
    /** The value this launcher last wrote ("24"/"12"); absent = nothing of ours is in place. */
    private const val KEY_WRITTEN = "clock_written"

    fun apply(context: Context, ui: LauncherUi?) {
        ui ?: return
        try {
            val choice = clockFormatChoice(ui.clockFormat)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val written = prefs.getString(KEY_WRITTEN, null)
            when (val write = clockFormatWrite(choice, current(context), written)) {
                ClockFormatWrite.Keep -> Unit
                ClockFormatWrite.Forget -> prefs.edit().remove(KEY_WRITTEN).commit()
                is ClockFormatWrite.Put -> if (put(context, write.value)) {
                    prefs.edit().putString(KEY_WRITTEN, write.value).commit()
                }
                ClockFormatWrite.Clear -> if (put(context, null)) prefs.edit().remove(KEY_WRITTEN).commit()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't apply the clock format", e)
        }
    }

    /** `clock_format_applied` for the status report, from the policy in force. */
    fun report(context: Context, policy: PolicyResponse?): Boolean? = try {
        val ui = policy?.launcherUi
        if (ui == null) null else clockFormatApplied(clockFormatChoice(ui.clockFormat), current(context))
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the clock format", e)
        null
    }

    private fun current(context: Context): String? =
        Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)

    /** Writes [value] (null = unset) when Android lets us; true only when the setting reads back so. */
    private fun put(context: Context, value: String?): Boolean {
        if (!Settings.System.canWrite(context)) {
            Log.i(LOG_TAG, "No \"Modify system settings\" access - the clock format stays the phone's")
            return false
        }
        return try {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, value)
            val ok = current(context) == value
            if (ok) Log.i(LOG_TAG, "Clock format set to ${value ?: "the phone's default"}")
            else Log.w(LOG_TAG, "The system kept its clock format (wanted ${value ?: "unset"})")
            ok
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't write the clock format", e)
            false
        }
    }
}
