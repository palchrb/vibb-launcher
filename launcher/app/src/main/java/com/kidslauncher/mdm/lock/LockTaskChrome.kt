package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.ChromeWrite
import com.kidslauncher.mdm.server.LockTaskSetting
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.StatusBarLatch
import com.kidslauncher.mdm.server.UpdateFence
import com.kidslauncher.mdm.server.chromeWriteOrder
import com.kidslauncher.mdm.server.lockTaskWhileLocked

private const val LOG_TAG = "LockTaskChrome"

/**
 * The one place that sets lock-task packages and features, the status bar and
 * `DISALLOW_CREATE_WINDOWS` (handy step 10). [AppEnforcer.apply] hands over the plan
 * ([applyPlan]); [PinLockRuntime] calls [refresh] when LOCKED begins or ends - fast single DPM
 * calls outside apply()'s lock, both synchronized here, and both through the pure
 * [lockTaskWhileLocked], so neither can undo the other. Since step 11 the update fence is an
 * input too ([UpdateFence.fenced], [fenceChanged]): the status bar is off while LOCKED or fenced.
 * Since design 17 a VoIP call's pinned package ([VoipCalls.pinnedPackage]) is one more input of the
 * kiosk-off lock list; [VoipCalls] calls [refresh] whenever it changes.
 * Debug builds only: `LockTaskDebug.adjust` puts the design 16d emulator override on top of the
 * computed setting right before the writes (src/debug, docs/testing/emulator.md §6e); in release it
 * returns the setting unchanged (src/release) - this object stays the only writer either way.
 * Design 16d decision 1: [healStatusBar] flips SYSTEM_INFO for a moment and [endStatusBarHeal]
 * writes the computed features back, so SystemUI gets lock task's status-bar flags again
 * ([statusBarHealFeatures]) - the same pass, only its lock-task features write differs.
 */
object LockTaskChrome {

    private data class Plan(val kioskPackages: Set<String>?, val features: Int, val restrictCreateWindows: Boolean)

    /** The last plan from apply() in this process (`null` until the first apply). */
    private var plan: Plan? = null
    @Volatile
    private var helpers: Set<String>? = null
    /** The permission controller, pinned next to a VoIP call's app (design 17); resolved off the
     * main thread at init. */
    @Volatile
    private var voipHelpers: Set<String>? = null
    private val statusBar = StatusBarLatch()

    /** A status-bar heal's flip is up ([healStatusBar]) until a pass writes the computed features. */
    @Volatile
    private var healFlip: HealFlip? = null

    /** [applyPlan]'s helper lookups are numbered: resolved outside the monitor, only the newest is
     * kept (qa-16c-code #1). */
    private val helperTickets = java.util.concurrent.atomic.AtomicLong()
    private var helpersTicket = 0L

    /** The last LOCKED pass with the kiosk off had no kiosk-off helpers cached (it pinned our
     * package alone): a pass that may resolve them should follow ([PinLockRuntime]). */
    @Volatile
    var helpersMissing = false
        private set

    /** Whether the kiosk is on as far as we know (the plan, else the last pinned state). */
    val kioskOn: Boolean
        get() = plan?.let { it.kioskPackages != null } ?: LauncherPreferences.mdm().kioskEnabled()

    fun applyPlan(
        context: Context,
        kioskPackages: Set<String>?,
        features: Int,
        restrictCreateWindows: Boolean,
        pinLockHelpers: () -> Set<String>,
    ) {
        // Resolved on apply's background thread whenever the kiosk is off, so the screen-off fast
        // path never has to query PackageManager - and before taking the monitor (qa-16c-code #1):
        // a LOCKED pass the lock waits for must never wait for PackageManager work.
        val ticket = helperTickets.incrementAndGet()
        val resolved = if (kioskPackages == null) runCatching(pinLockHelpers).getOrNull() else null
        synchronized(this) {
            plan = Plan(kioskPackages, features, restrictCreateWindows)
            if (resolved != null && ticket > helpersTicket) {
                helpers = resolved
                helpersTicket = ticket
            }
            applyNow(context)
        }
    }

    /** Resolves the kiosk-off lock helpers ahead of the first screen-off (background thread). */
    fun prefetchHelpers(context: Context) {
        if (voipHelpers == null) {
            runCatching { com.kidslauncher.mdm.server.AppEnforcer.resolveVoipHelpers(context) }.getOrNull()
                ?.let { resolved -> synchronized(this) { if (voipHelpers == null) voipHelpers = resolved } }
        }
        if (helpers != null) return
        val resolved = runCatching { com.kidslauncher.mdm.server.AppEnforcer.resolvePinLockHelpers(context) }.getOrNull() ?: return
        synchronized(this) { if (helpers == null) helpers = resolved }
    }

    /** A VoIP call's package to keep pinned while LOCKED with the kiosk off, plus its helpers
     * (cached - resolved outside the monitor, [prefetchHelpers]/[refresh]). */
    private fun voipPackages(): Set<String> {
        val pkg = VoipCalls.pinnedPackage ?: return emptySet()
        return setOf(pkg) + voipHelpers.orEmpty()
    }

    /**
     * After a LOCKED/not-LOCKED change, or a VoIP pin change. A LOCKED pass runs on the main thread
     * right after the lock's start, so the lock resumes only once the chrome is in place
     * (qa-16c-code #1) - then [resolveMissing] is false: no PackageManager work, a missing helper
     * set is left out ([helpersMissing]). Any other caller may resolve them first - outside the
     * monitor. Every pass reads the lock state under the monitor, so the newest one wins.
     */
    fun refresh(context: Context, resolveMissing: Boolean = true) {
        // Only what a LOCKED kiosk-off pass pins (the helpers, a VoIP call's permission controller).
        if (resolveMissing && PinLockRuntime.chromeLocked && !kioskOn) prefetchHelpers(context)
        synchronized(this) {
            if (plan == null) {
                // No apply in this process yet: the platform state is the base. The features are
                // only ever reduced from it while locked; an unlock asks for an apply to restore them.
                applyFallback(context)
            } else {
                applyNow(context)
            }
        }
    }

    /**
     * Design 16d decision 1: a status-bar heal's first write - this pass's lock-task features with
     * SYSTEM_INFO flipped ([statusBarHealFeatures]), only while something is pinned. The caller
     * waits [STATUS_BAR_HEAL_FLIP_MS] outside the monitor and then calls [endStatusBarHeal].
     * Returns whether the flip is up. Background thread.
     */
    fun healStatusBar(context: Context, trigger: HealTrigger): Boolean = synchronized(this) {
        if (plan == null) applyFallback(context, trigger) else applyNow(context, trigger)
    }

    /** The heal's second write: a normal pass, unless one has written the features since. */
    fun endStatusBarHeal(context: Context) {
        if (healFlip != null) refresh(context)
    }

    /** The update fence went up or was released (qa-11-design.md #4): the next pass writes the
     * status bar whatever the latch remembers. Any thread. */
    fun fenceChanged(context: Context) {
        statusBar.invalidate()
        refresh(context)
    }

    private fun admin(context: Context) = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)?.takeIf { it.isDeviceOwnerApp(context.packageName) }

    /** The kiosk-off helpers for a LOCKED pass - the cache only, never resolved under the monitor. */
    private fun lockHelpers(locked: Boolean, kioskOn: Boolean): Set<String> {
        val wanted = locked && !kioskOn
        val cached = helpers
        helpersMissing = wanted && cached == null
        return if (wanted) cached.orEmpty() else emptySet()
    }

    /** A pass from the plan; with [heal] only a heal's flip ([healStatusBar]) - whether it is up. */
    private fun applyNow(context: Context, heal: HealTrigger? = null): Boolean {
        val dpm = dpm(context) ?: return false
        val current = plan ?: return false
        val locked = PinLockRuntime.chromeLocked
        val kioskOn = current.kioskPackages != null
        val setting = lockTaskWhileLocked(
            current.kioskPackages, current.features, current.restrictCreateWindows, locked, context.packageName,
            lockHelpers(locked, kioskOn),
            fenced = UpdateFence.fenced,
            voipPackages = if (locked && !kioskOn) voipPackages() else emptySet(),
        )
        return apply(context, dpm, setting, heal, kioskOn = kioskOn, locked = locked)
    }

    /** A pass without a plan, from the platform's state ([healBase]: a heal's flip reads as its original). */
    private fun applyFallback(context: Context, heal: HealTrigger? = null): Boolean {
        val dpm = dpm(context) ?: return false
        val admin = admin(context)
        val locked = PinLockRuntime.chromeLocked
        val kiosk = LauncherPreferences.mdm().kioskEnabled()
        val packages = try {
            dpm.getLockTaskPackages(admin).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val features = try {
            healBase(dpm.getLockTaskFeatures(admin), healFlip)
        } catch (e: Exception) {
            0
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
            lockHelpers = lockHelpers(locked, kiosk),
            fenced = UpdateFence.fenced,
            voipPackages = if (locked && !kiosk) voipPackages() else emptySet(),
        )
        return apply(context, dpm, setting, heal, kioskOn = kiosk, locked = locked)
    }

    /**
     * The writes in [chromeWriteOrder]: a LOCKED pass blocks the shade and overlays first. With
     * [heal], only the heal's flip of the lock-task features ([writeHealFlip]); returns whether it
     * is up (`false` for every other pass).
     */
    private fun apply(context: Context, dpm: DevicePolicyManager, computed: LockTaskSetting, heal: HealTrigger?, kioskOn: Boolean, locked: Boolean): Boolean {
        val setting = LockTaskDebug.adjust(context, computed)
        val admin = admin(context)
        if (heal != null) return writeHealFlip(dpm, admin, setting, heal)
        for (write in chromeWriteOrder(locked)) {
            when (write) {
                ChromeWrite.STATUS_BAR -> writeStatusBar(dpm, admin, setting.statusBarDisabled)
                ChromeWrite.CREATE_WINDOWS -> writeCreateWindows(dpm, admin, setting.createWindowsBlocked)
                ChromeWrite.LOCK_TASK -> if (writeLockTask(dpm, admin, setting, kioskOn)) healFlip = null
            }
        }
        return false
    }

    /** Whether the features were written and verified (a heal's flip is gone then). */
    private fun writeLockTask(dpm: DevicePolicyManager, admin: ComponentName, setting: LockTaskSetting, kioskOn: Boolean): Boolean {
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
            return true
        }
        return false
    }

    /**
     * The heal's flip: only setLockTaskFeatures - the packages, the status bar and
     * `DISALLOW_CREATE_WINDOWS` stay as the last pass left them, and KEYGUARD stays set (only
     * SYSTEM_INFO changes). Nothing pinned: no heal.
     */
    private fun writeHealFlip(dpm: DevicePolicyManager, admin: ComponentName, setting: LockTaskSetting, trigger: HealTrigger): Boolean {
        val flipped = statusBarHealFeatures(setting) ?: return false
        return try {
            dpm.setLockTaskFeatures(admin, flipped)
            healFlip = HealFlip(setting.features, flipped)
            Log.i(LOG_TAG, "Status bar heal (${trigger.label}): lock-task features ${setting.features} -> $flipped for $STATUS_BAR_HEAL_FLIP_MS ms")
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Status bar heal (${trigger.label}) failed", e)
            false
        }
    }

    private fun writeStatusBar(dpm: DevicePolicyManager, admin: ComponentName, disabled: Boolean) {
        statusBar.toWrite(disabled)?.let { wanted ->
            try {
                // Blocks the shade and quick settings outside lock task: the lock's backstop and
                // the update fence's shade block.
                if (dpm.setStatusBarDisabled(admin, wanted)) {
                    statusBar.written(wanted)
                } else {
                    Log.w(LOG_TAG, "The platform refused setStatusBarDisabled($wanted)")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to set the status bar state", e)
            }
        }
    }

    private fun writeCreateWindows(dpm: DevicePolicyManager, admin: ComponentName, blocked: Boolean) {
        try {
            if (blocked) {
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
