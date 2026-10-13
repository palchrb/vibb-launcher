package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.lock.KeyguardCover
import java.util.concurrent.Executors

private const val LOG_TAG = "ScreenTimeout"
const val SCREEN_TIMEOUT_MIN_SECONDS = 15
const val SCREEN_TIMEOUT_MAX_SECONDS = 600
const val SCREEN_TIMEOUT_DEFAULT_SECONDS = 60

/** What [ScreenTimeout.apply] does - see [screenTimeoutAction]. */
sealed interface ScreenTimeoutAction {
    /** Set `SCREEN_OFF_TIMEOUT` to [millis], keep it there, block the Settings control. */
    data class Enforce(val millis: Long) : ScreenTimeoutAction
    /** Stop enforcing, lift the Settings block and put back the value the phone had before we
     * first enforced one ([previousToRestore]). */
    data object Release : ScreenTimeoutAction
}

/**
 * Auto-lock (emulator run 2026-10-06): the parent's `screen_timeout_seconds`, only while the
 * phone is managed ([managed] = [hardeningManaged]: an enforced allowlist or managed calls - a
 * policy without either, no policy at all, or an unenrolled phone release it,
 * qa-fixround-2026-10-06 #3). Out-of-range values are clamped to 15 s..10 min (never "never"), a
 * non-positive one is the 1-minute default. Also released: no value (an older server, or a
 * last-enforced plan saved before this field existed) and the offline override / pause (the
 * standing rule: every restriction is liftable).
 */
fun screenTimeoutAction(managed: Boolean, policySeconds: Int?, overrideActive: Boolean): ScreenTimeoutAction = when {
    !managed || overrideActive || policySeconds == null -> ScreenTimeoutAction.Release
    policySeconds <= 0 -> ScreenTimeoutAction.Enforce(SCREEN_TIMEOUT_DEFAULT_SECONDS * 1000L)
    else -> ScreenTimeoutAction.Enforce(policySeconds.coerceIn(SCREEN_TIMEOUT_MIN_SECONDS, SCREEN_TIMEOUT_MAX_SECONDS) * 1000L)
}

/** The value to remember before the first enforce: the phone's own, unless one is remembered
 * already (a later enforce must not overwrite it with our own value). */
fun previousToRemember(remembered: Long?, current: Long?): Long? = remembered ?: current

/** On release: the remembered value, if any and not ours already in place. */
fun previousToRestore(remembered: Long?, current: Long?): Long? = remembered?.takeIf { it != current }

/**
 * Applies the parent's screen timeout as device owner with
 * `DevicePolicyManager.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, ms)` -
 * SCREEN_OFF_TIMEOUT is on the platform's allowlist for device/profile owners (AOSP
 * `DevicePolicyManager.java`, `SystemSettingsWhitelist`; `DevicePolicyManagerService.setSystemSetting`
 * checks DO/PO and the allowlist and writes with a cleared calling identity), API 28+, unchanged
 * through 36. Kept enforced: `DISALLOW_CONFIG_SCREEN_TIMEOUT` hides the control in Settings (the
 * DO's own write isn't affected - it bypasses user restrictions), a [ContentObserver] puts the
 * value back whenever anything else changes it, and every [AppEnforcer.apply] re-checks it.
 * Handy's PIN lock locks on every screen-off, so this is the lock delay too. The effective
 * timeout can't exceed a `setMaximumTimeToLock` - none, except under design 32 step 2's keyguard cover
 * (debug switch), where it equals this setting (H1): every write of the setting goes through
 * [KeyguardCover.followTimeout] (T in the safe order), and the observer also runs in Release then, so
 * T follows the phone's own value. This stays the only writer of `SCREEN_OFF_TIMEOUT`.
 */
object ScreenTimeout {
    @Volatile
    private var targetMs: Long? = null
    @Volatile
    private var observer: ContentObserver? = null
    private val worker = Executors.newSingleThreadExecutor()

    fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName, action: ScreenTimeoutAction) {
        when (action) {
            ScreenTimeoutAction.Release -> {
                targetMs = null
                setRestriction(dpm, admin, false)
                val prefs = prefs(context)
                val remembered = if (prefs.contains(KEY_PREVIOUS)) prefs.getLong(KEY_PREVIOUS, 0L) else null
                previousToRestore(remembered, currentMs(context))?.let { previous ->
                    if (!write(context, dpm, admin, previous, "put back to")) return
                }
                if (remembered != null) prefs.edit().remove(KEY_PREVIOUS).commit()
            }
            is ScreenTimeoutAction.Enforce -> {
                val prefs = prefs(context)
                if (!prefs.contains(KEY_PREVIOUS)) {
                    previousToRemember(null, currentMs(context))?.let { prefs.edit().putLong(KEY_PREVIOUS, it).commit() }
                }
                targetMs = action.millis
                ensureObserver(context.applicationContext)
                enforce(context, dpm, admin, action.millis)
                setRestriction(dpm, admin, true)
            }
        }
        // Design 32 step 2 (H1): under the cover T follows the setting in Release too - the observer
        // runs whenever the cover is on, and T is re-checked against the value in force now.
        if (KeyguardCover.coverOn) {
            ensureObserver(context.applicationContext)
            KeyguardCover.timeoutChanged(context, dpm, admin)
        }
    }

    /** CE prefs: the phone's own timeout from before we first enforced one. */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("screen_timeout_state", Context.MODE_PRIVATE)

    private const val KEY_PREVIOUS = "previous_ms"

    /** The value in force now, seconds - for the status report. */
    fun currentSeconds(context: Context): Int? = currentMs(context)?.let { (it / 1000L).toInt() }

    /** The screen timeout in force now, ms (design 32 step 2: T under the cover). */
    fun effectiveMs(context: Context): Long? = currentMs(context)

    private fun currentMs(context: Context): Long? = try {
        Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (e: Exception) {
        null
    }

    private fun enforce(context: Context, dpm: DevicePolicyManager, admin: ComponentName, millis: Long) {
        if (currentMs(context) == millis) return
        write(context, dpm, admin, millis, "set to")
    }

    /** The setting's only write - through the keyguard cover's T ([KeyguardCover.followTimeout]), which
     * without the cover just writes it. */
    private fun write(context: Context, dpm: DevicePolicyManager, admin: ComponentName, millis: Long, what: String): Boolean =
        KeyguardCover.followTimeout(context, dpm, admin, currentMs(context), millis) {
            try {
                dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, millis.toString())
                Log.i(LOG_TAG, "Screen timeout $what ${millis / 1000} s")
                true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't change the screen timeout ($what ${millis / 1000} s)", e)
                false
            }
        }

    private fun setRestriction(dpm: DevicePolicyManager, admin: ComponentName, on: Boolean) {
        try {
            if (on) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't ${if (on) "set" else "clear"} DISALLOW_CONFIG_SCREEN_TIMEOUT", e)
        }
    }

    /** Once per process: puts the target back whenever the setting changes (off the main thread);
     * released, under the keyguard cover, T follows it (design 32 step 2). */
    @Synchronized
    private fun ensureObserver(context: Context) {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val target = targetMs
                // Released: only the keyguard cover's T follows the phone's own value (design 32 step 2).
                if (target == null && !KeyguardCover.coverOn) return
                worker.execute {
                    try {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@execute
                        if (!dpm.isDeviceOwnerApp(context.packageName)) return@execute
                        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
                        if (target == null) {
                            if (targetMs == null) KeyguardCover.timeoutChanged(context, dpm, admin)
                        } else if (targetMs == target) {
                            enforce(context, dpm, admin, target)
                        }
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Re-applying the screen timeout failed", e)
                    }
                }
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, created,
            )
            observer = created
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the screen timeout", e)
        }
    }
}
