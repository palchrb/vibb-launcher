package com.kidslauncher.mdm.logins

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.assistant.AssistantSetup
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.currentPolicyDecision

private const val LOG_TAG = "BrowserRoleStep"
private const val PREFS = "browser_role_step"
private const val KEY_UNTIL = "role_step_until"
private const val KEY_ELAPSED = "role_step_elapsed"
private const val KEY_BOOT = "role_step_boot"

/** The role dialog's window: 5 minutes at most. */
private const val ROLE_STEP_MS = 300_000L

/**
 * Design 119 §4.1: the setup step `vibb_browser` ("Nettleser") - Android's own request for the browser role, the
 * on-phone path when the repair (M1) didn't give Vibb One the role. `DISALLOW_CONFIG_DEFAULT_APPS` refuses a role
 * request, so it is lifted while the dialog is open (M5), the way design 98's pick window is: stored by every clock
 * ([BootClock]), only while the phone is open for setup (a pause or the setup hold), ended at the dialog's return.
 */
object BrowserRoleStep {
    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun start(context: Context): WindowStart? {
        val p = prefs(context)
        if (!p.contains(KEY_UNTIL)) return null
        return WindowStart(p.getLong(KEY_UNTIL, 0L), p.getLong(KEY_ELAPSED, 0L), p.getInt(KEY_BOOT, -1))
    }

    /** The dialog's window is open: the restriction stays lifted (`AppEnforcer`'s default-apps lock reads this). */
    fun isOpen(context: Context): Boolean {
        val start = start(context) ?: return false
        return BootClock.isActive(start, ROLE_STEP_MS) && AssistantSetup.setupWindowOpen()
    }

    /** Android's role request, while Vibb One doesn't hold the role. */
    fun requestIntents(context: Context): List<Intent> {
        val rm = context.getSystemService(RoleManager::class.java) ?: return emptyList()
        return listOfNotNull(
            runCatching {
                rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                    .takeIf { rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER) }
            }.getOrNull(),
        )
    }

    /** Before the dialog opens (background thread): the window stored, then an apply lifts the restriction. Returns
     * whether it opened. */
    fun open(context: Context): Boolean {
        if (!AssistantSetup.setupWindowOpen()) return false
        val start = BootClock.windowStart(ROLE_STEP_MS)
        prefs(context).edit()
            .putLong(KEY_UNTIL, start.untilWallMs).putLong(KEY_ELAPSED, start.elapsedStartMs).putInt(KEY_BOOT, start.bootCount)
            .commit()
        Log.i(LOG_TAG, "The browser role's window is open")
        AppEnforcer.apply(context, currentPolicyDecision())
        return true
    }

    /** The dialog answered (any thread): the window goes and the restriction comes back with an apply. */
    fun end(context: Context) {
        if (start(context) == null) return
        prefs(context).edit().remove(KEY_UNTIL).remove(KEY_ELAPSED).remove(KEY_BOOT).commit()
        Log.i(LOG_TAG, "The browser role's window ends")
        val app = context.applicationContext
        Thread { runCatching { AppEnforcer.apply(app, currentPolicyDecision()) } }.start()
    }
}
