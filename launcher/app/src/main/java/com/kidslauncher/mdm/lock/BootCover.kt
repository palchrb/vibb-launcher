package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log

private const val LOG_TAG = "BootCover"

/**
 * The boot cover's main-process side (design 16b, QA #7-#9): whether the cover is wanted (server
 * switch `boot_cover` on a managed phone - CE prefs `boot_cover`), its persistent preferred
 * activity, and its component state ([bootCoverEnabled]):
 * - every apply: switch off -> disabled; on -> its PPA is added next to HomeActivity's (a distinct
 *   filter with [BOOT_COVER_CATEGORY]; nothing is ever cleared - that would drop the HOME pin);
 * - `ACTION_SHUTDOWN` (only to runtime receivers - this process is alive through the anchor
 *   service): enabled when wanted and the cover's crash guard hasn't tripped ([BootCoverGuard];
 *   sticky until the switch goes off, which clears it), written synchronously; from then on
 *   [HomeFront] starts no Home ([shuttingDown]);
 * - this process's start after the unlock: disabled before Home is brought to the front, so the
 *   typed HOME start resolves to HomeActivity only (the cover hands over by itself too).
 * A crash reboot sends no shutdown: the cover stays off and the boot falls back to A+B. Neither
 * does `adb reboot` (init's `sys.powerctl`, not ShutdownThread): test with the power menu or
 * `adb shell svc power reboot`. [report] is the status report's `boot_cover`.
 */
object BootCover {
    private const val PREFS = "boot_cover"
    private const val KEY_WANTED = "wanted"
    private const val KEY_ARMED_AT = "armed_at_ms"
    private const val KEY_LAUNCHER_HANDOVER_AT = "launcher_handover_at_ms"

    @Volatile
    private var receiverRegistered = false

    /** `ACTION_SHUTDOWN` arrived: no more Home starts (qa-16b-code #3) - with the cover armed a
     * typed HOME start could resolve to it. */
    @Volatile
    var shuttingDown = false
        private set

    private fun component(context: Context) = BootCoverActivity.component(context)

    /** From `AppEnforcer.apply` (background thread). */
    fun applyPolicy(context: Context, dpm: DevicePolicyManager, admin: ComponentName, switchOn: Boolean, managed: Boolean) {
        val app = context.applicationContext
        val wanted = bootCoverWanted(switchOn, managed, deviceOwner = true)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).commit()
        // The switch off clears a tripped crash guard: on again = one more try (qa-16b-code #2).
        if (!wanted) BootCoverGuard.clear(app)
        if (wanted) {
            try {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(BOOT_COVER_CATEGORY)
                }
                dpm.addPersistentPreferredActivity(admin, filter, component(app))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to set the boot cover's persistent preferred activity", e)
            }
        }
        setEnabled(app, bootCoverEnabled(CoverEvent.POLICY, wanted), "policy")
    }

    /** From `PinLockRuntime.init` (main thread, unlocked): the shutdown receiver, and the hand-over
     * of a cover that is still enabled (before the boot's Home start). */
    fun init(context: Context) {
        val app = context.applicationContext
        if (!receiverRegistered) {
            receiverRegistered = true
            try {
                app.registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN), Context.RECEIVER_NOT_EXPORTED)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't register for shutdown", e)
            }
        }
        if (isEnabled(app)) {
            setEnabled(app, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted(app)), "main process after unlock")
            prefs(app).edit().putLong(KEY_LAUNCHER_HANDOVER_AT, System.currentTimeMillis()).apply()
        }
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            val app = context.applicationContext
            val deviceOwner = try {
                app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true
            } catch (e: Exception) {
                false
            }
            shuttingDown = true
            val tripped = coverGuardTripped(BootCoverGuard.read(app))
            val armed = bootCoverEnabled(CoverEvent.SHUTDOWN, wanted(app) && deviceOwner, tripped)
            // SYNCHRONOUS: written before the receiver returns - an OEM shutdown path may not flush
            // pending package settings (qa-16b-code #3).
            setEnabled(app, armed, "shutdown", synchronous = true)
            if (armed == true) prefs(app).edit().putLong(KEY_ARMED_AT, System.currentTimeMillis()).commit()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `StatusReportRequest.bootCover` (status report, qa-16b-code #5). */
    fun report(context: Context): com.kidslauncher.mdm.server.dto.BootCoverReport {
        val app = context.applicationContext
        val record = BootCoverGuard.read(app)
        val launcherAt = prefs(app).getLong(KEY_LAUNCHER_HANDOVER_AT, 0L).takeIf { it > 0L }
        val coverAt = record?.handedOverAtMs
        val (by, at) = when {
            coverAt != null && (launcherAt == null || coverAt >= launcherAt) -> "cover" to coverAt
            launcherAt != null -> "launcher" to launcherAt
            else -> null to null
        }
        return com.kidslauncher.mdm.server.dto.BootCoverReport(
            wanted = wanted(app),
            tripped = coverGuardTripped(record),
            lastArmedAtMs = prefs(app).getLong(KEY_ARMED_AT, 0L).takeIf { it > 0L },
            lastShownAtMs = record?.shownAtMs,
            lastHandover = by,
            lastHandoverAtMs = at,
        )
    }

    private fun wanted(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)
    } catch (e: Exception) {
        false
    }

    private fun isEnabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Exception) {
        false
    }

    private fun setEnabled(context: Context, enabled: Boolean?, why: String, synchronous: Boolean = false) {
        enabled ?: return
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component(context)) == state) return
            val flags = if (synchronous) PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS else PackageManager.DONT_KILL_APP
            pm.setComponentEnabledSetting(component(context), state, flags)
            Log.i(LOG_TAG, "Boot cover ${if (enabled) "enabled" else "disabled"} ($why)")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't change the boot cover ($why)", e)
        }
    }
}
