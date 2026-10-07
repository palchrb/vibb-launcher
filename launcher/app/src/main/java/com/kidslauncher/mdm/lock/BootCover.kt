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
 *   service): enabled when wanted, for the next boot's BFU window;
 * - this process's start after the unlock: disabled before Home is brought to the front, so the
 *   typed HOME start resolves to HomeActivity only (the cover hands over by itself too).
 * A crash reboot sends no shutdown: the cover stays off and the boot falls back to A+B.
 */
object BootCover {
    private const val PREFS = "boot_cover"
    private const val KEY_WANTED = "wanted"

    @Volatile
    private var receiverRegistered = false

    private fun component(context: Context) = BootCoverActivity.component(context)

    /** From `AppEnforcer.apply` (background thread). */
    fun applyPolicy(context: Context, dpm: DevicePolicyManager, admin: ComponentName, switchOn: Boolean, managed: Boolean) {
        val app = context.applicationContext
        val wanted = bootCoverWanted(switchOn, managed, deviceOwner = true)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).commit()
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
        if (isEnabled(app)) setEnabled(app, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted(app)), "main process after unlock")
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
            setEnabled(app, bootCoverEnabled(CoverEvent.SHUTDOWN, wanted(app) && deviceOwner), "shutdown")
        }
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

    private fun setEnabled(context: Context, enabled: Boolean?, why: String) {
        enabled ?: return
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component(context)) == state) return
            pm.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
            Log.i(LOG_TAG, "Boot cover ${if (enabled) "enabled" else "disabled"} ($why)")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't change the boot cover ($why)", e)
        }
    }
}
