package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockTaskSetting
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.lockTaskWhileLocked

private const val LOG_TAG = "LockTaskChrome"

/**
 * The one place that sets lock-task packages and features, the status-bar backstop and
 * `DISALLOW_CREATE_WINDOWS` (handy step 10). [AppEnforcer.apply] hands over the plan
 * ([applyPlan]); [PinLockRuntime] calls [refresh] when LOCKED begins or ends - fast single DPM
 * calls outside apply()'s lock, both synchronized here, and both through the pure
 * [lockTaskWhileLocked], so neither can undo the other.
 */
object LockTaskChrome {

    private data class Plan(val kioskPackages: Set<String>?, val features: Int, val restrictCreateWindows: Boolean)

    /** The last plan from apply() in this process (`null` until the first apply). */
    private var plan: Plan? = null
    @Volatile
    private var helpers: Set<String>? = null
    private var appliedStatusBar: Boolean? = null

    /** Whether the kiosk is on as far as we know (the plan, else the last pinned state). */
    val kioskOn: Boolean
        get() = plan?.let { it.kioskPackages != null } ?: LauncherPreferences.mdm().kioskEnabled()

    @Synchronized
    fun applyPlan(
        context: Context,
        kioskPackages: Set<String>?,
        features: Int,
        restrictCreateWindows: Boolean,
        pinLockHelpers: () -> Set<String>,
    ) {
        plan = Plan(kioskPackages, features, restrictCreateWindows)
        // Resolved on apply's background thread whenever the kiosk is off, so the screen-off fast
        // path never has to query PackageManager.
        if (kioskPackages == null) helpers = runCatching(pinLockHelpers).getOrNull() ?: helpers
        applyNow(context)
    }

    /** Resolves the kiosk-off lock helpers ahead of the first screen-off (background thread). */
    fun prefetchHelpers(context: Context) {
        if (helpers != null) return
        val resolved = runCatching { com.kidslauncher.mdm.server.AppEnforcer.resolvePinLockHelpers(context) }.getOrNull() ?: return
        synchronized(this) { if (helpers == null) helpers = resolved }
    }

    /** After a LOCKED/not-LOCKED change. Main thread; a few binder calls. */
    @Synchronized
    fun refresh(context: Context) {
        val current = plan
        if (current == null) {
            // No apply in this process yet: the platform state is the base. The features are
            // only ever reduced from it while locked; an unlock asks for an apply to restore them.
            applyFallback(context)
            return
        }
        applyNow(context)
    }

    private fun admin(context: Context) = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)?.takeIf { it.isDeviceOwnerApp(context.packageName) }

    private fun applyNow(context: Context) {
        val dpm = dpm(context) ?: return
        val current = plan ?: return
        val locked = PinLockRuntime.chromeLocked
        val lockHelpers = if (locked && current.kioskPackages == null) {
            helpers ?: runCatching { com.kidslauncher.mdm.server.AppEnforcer.resolvePinLockHelpers(context) }
                .getOrDefault(emptySet()).also { helpers = it }
        } else {
            emptySet()
        }
        val setting = lockTaskWhileLocked(
            current.kioskPackages, current.features, current.restrictCreateWindows, locked, context.packageName, lockHelpers,
        )
        apply(context, dpm, setting, kioskOn = current.kioskPackages != null)
    }

    private fun applyFallback(context: Context) {
        val dpm = dpm(context) ?: return
        val admin = admin(context)
        val locked = PinLockRuntime.chromeLocked
        val kiosk = LauncherPreferences.mdm().kioskEnabled()
        val packages = try {
            dpm.getLockTaskPackages(admin).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val features = try {
            dpm.getLockTaskFeatures(admin)
        } catch (e: Exception) {
            0
        }
        val lockHelpers = if (locked && !kiosk) {
            runCatching { com.kidslauncher.mdm.server.AppEnforcer.resolvePinLockHelpers(context) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val createWindows = try {
            dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CREATE_WINDOWS)
        } catch (e: Exception) {
            false
        }
        val setting = lockTaskWhileLocked(
            if (kiosk) packages else null,
            features,
            // Unlocking without a plan keeps whatever is set; the apply that follows corrects it.
            restrictCreateWindows = createWindows,
            locked = locked,
            ownPackage = context.packageName,
            lockHelpers = lockHelpers,
        )
        apply(context, dpm, setting, kioskOn = kiosk)
    }

    private fun apply(context: Context, dpm: DevicePolicyManager, setting: LockTaskSetting, kioskOn: Boolean) {
        val admin = admin(context)
        val mdm = LauncherPreferences.mdm()
        val packages = setting.packages
        if (packages == null) {
            try {
                dpm.setLockTaskPackages(admin, emptyArray())
                mdm.kioskEnabled(false)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to unpin kiosk state", e)
            }
        } else if (!applyLockTaskFeaturesVerified(dpm, admin, setting.features)) {
            // Order matters (the boot-deadlock incident in CLAUDE.md): setLockTaskFeatures, which
            // carries the keyguard bit, must be verified *before* setLockTaskPackages ever pins
            // the device; a failure skips pinning this cycle - apply() re-runs and retries.
            // Without it the PIN lock can't enter lock task with the kiosk off and is only a
            // re-front until the next apply.
            Log.w(LOG_TAG, "Refusing to pin this cycle - lock task features never verified")
        } else {
            try {
                dpm.setLockTaskPackages(admin, packages.toTypedArray())
                mdm.kioskEnabled(kioskOn)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to pin lock-task packages", e)
            }
        }
        if (appliedStatusBar != setting.statusBarDisabled) {
            try {
                // Blocks the shade and quick settings outside lock task only - a backstop.
                if (dpm.setStatusBarDisabled(admin, setting.statusBarDisabled)) appliedStatusBar = setting.statusBarDisabled
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to set the status bar state", e)
            }
        }
        try {
            if (setting.createWindowsBlocked) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set DISALLOW_CREATE_WINDOWS", e)
        }
    }

    /**
     * setLockTaskFeatures is a cheap Binder call - one immediate retry after a failed
     * write-then-verify is trivial insurance against a transient platform hiccup. Reads back
     * [DevicePolicyManager.getLockTaskFeatures] rather than trusting the setter not to throw, since
     * a silent mismatch is exactly what would otherwise let a device get pinned without the
     * keyguard bit actually applied.
     */
    private fun applyLockTaskFeaturesVerified(dpm: DevicePolicyManager, admin: ComponentName, features: Int): Boolean {
        repeat(2) { attempt ->
            try {
                dpm.setLockTaskFeatures(admin, features)
                if (dpm.getLockTaskFeatures(admin) == features) return true
                Log.w(LOG_TAG, "setLockTaskFeatures didn't verify on attempt ${attempt + 1} (wanted $features, got ${dpm.getLockTaskFeatures(admin)})")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "setLockTaskFeatures threw on attempt ${attempt + 1}", e)
            }
        }
        return false
    }

    /** Whether apply() has handed over a plan in this process. */
    val hasPlan: Boolean get() = plan != null
}
