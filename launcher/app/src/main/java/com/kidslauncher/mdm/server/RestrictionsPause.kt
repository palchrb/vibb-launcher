package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.kidslauncher.mdm.preferences.LauncherPreferences

/** How long one "pause all restrictions" lasts. Re-entering the PIN starts a new window. */
const val RESTRICTIONS_PAUSE_DURATION_MS = 2 * 60 * 60 * 1000L

/** Where a time-limited override window started, by every clock we check. */
data class WindowStart(
    /** Wall-clock end of the window (`System.currentTimeMillis()` at start + duration). */
    val untilWallMs: Long,
    /** `SystemClock.elapsedRealtime()` at start: can't be changed by the user. */
    val elapsedStartMs: Long,
    /** `Settings.Global.BOOT_COUNT` at start (elapsed time restarts on every boot). */
    val bootCount: Int,
)

/**
 * Pure check behind [RestrictionsPause.isActive] and `OfflineOverride.isActive`: the window is
 * over as soon as *either* clock says [durationMs] has passed. The wall clock alone could be set
 * back again and again to stretch a window indefinitely; elapsed realtime can't be changed, but
 * restarts on reboot, so a different boot count ends the window too. A window saved by an older
 * build (no elapsed start, boot count -1) is over.
 */
fun timedWindowActive(
    start: WindowStart,
    nowWallMs: Long,
    nowElapsedMs: Long,
    nowBootCount: Int,
    durationMs: Long,
): Boolean {
    if (start.bootCount < 0 || start.bootCount != nowBootCount) return false
    val elapsed = nowElapsedMs - start.elapsedStartMs
    if (elapsed < 0 || elapsed >= durationMs) return false
    return nowWallMs < start.untilWallMs && start.untilWallMs - nowWallMs <= durationMs
}

/** The clocks [timedWindowActive] needs, read from the platform. [init] runs in
 * `Application.onCreate`; before that (never in practice) every window counts as over. */
object BootClock {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** `Settings.Global.BOOT_COUNT`; -1 if unknown, which [timedWindowActive] treats as over. */
    fun bootCount(): Int {
        val context = appContext ?: return -1
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }
    }

    fun windowStart(durationMs: Long) = WindowStart(
        untilWallMs = System.currentTimeMillis() + durationMs,
        elapsedStartMs = SystemClock.elapsedRealtime(),
        bootCount = bootCount(),
    )

    fun isActive(start: WindowStart, durationMs: Long): Boolean =
        timedWindowActive(start, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(), durationMs)
}

/**
 * The Settings "pause all restrictions" kill-switch: an escape hatch for a broken policy or
 * launcher update. Upstream's switch was open to anyone in Settings when no PIN was configured,
 * never expired and was never reported. Here it can only be turned on after entering the device's
 * offline-override PIN (so it's unavailable while the server has no PIN set), it ends by itself
 * after [RESTRICTIONS_PAUSE_DURATION_MS] by the wall clock *and* by elapsed time since boot (see
 * [timedWindowActive]), and every status report says whether it's on. [AppEnforcer] and the lock
 * decision treat it exactly like the offline override.
 */
object RestrictionsPause {

    /** Self-clears once expired, so a stale flag can't linger. */
    fun isActive(): Boolean {
        val mdm = LauncherPreferences.mdm()
        if (!mdm.restrictionsPaused()) return false
        val start = WindowStart(
            mdm.restrictionsPausedUntil(),
            mdm.restrictionsPausedElapsedStart(),
            mdm.restrictionsPausedBoot(),
        )
        if (BootClock.isActive(start, RESTRICTIONS_PAUSE_DURATION_MS)) return true
        clear()
        return false
    }

    /** Call only after [OfflineOverride.verifyPin] succeeded. */
    fun start() {
        val mdm = LauncherPreferences.mdm()
        val start = BootClock.windowStart(RESTRICTIONS_PAUSE_DURATION_MS)
        mdm.restrictionsPausedUntil(start.untilWallMs)
        mdm.restrictionsPausedElapsedStart(start.elapsedStartMs)
        mdm.restrictionsPausedBoot(start.bootCount)
        mdm.restrictionsPaused(true)
    }

    fun clear() {
        val mdm = LauncherPreferences.mdm()
        mdm.restrictionsPaused(false)
        mdm.restrictionsPausedUntil(0)
    }
}
