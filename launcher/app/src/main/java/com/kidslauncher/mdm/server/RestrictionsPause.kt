package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** How long one "pause all restrictions" lasts. Re-entering the PIN starts a new window. The server's
 * `RESTRICTIONS_PAUSE_MAX_MS` is the same, both pinned by `restrictions_pause.json` (RestrictionsPauseTest). */
const val RESTRICTIONS_PAUSE_DURATION_MS = 2 * 60 * 60 * 1000L

/** Design 114 §6.1: the setup hold's ceiling (4 hours) - the pause started at a fresh enrolment. The server's
 * `SETUP_HOLD_MAX_MS` is the same, both pinned by `restrictions_pause.json`'s `setup_hold_max_ms`. */
const val SETUP_HOLD_DURATION_MS = 4 * 60 * 60 * 1000L

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
 * never expired and was never reported. Here it can only be turned on through the parent PIN's
 * gate ([ParentPin], so it's unavailable while the server has no PIN set), it ends by itself
 * after [RESTRICTIONS_PAUSE_DURATION_MS] by the wall clock *and* by elapsed time since boot (see
 * [timedWindowActive]), and every status report says whether it's on. [AppEnforcer] and the lock
 * decision treat it exactly like the offline override.
 *
 * Its two starters: the parent PIN's gate ([start]), or the setup hold at a fresh enrolment ([startHold], design 114
 * §6.1 - only `Enrollment.enroll`, pinned by `ParentPinCallersTest`). The hold is the same pause with a longer end
 * ([SETUP_HOLD_DURATION_MS]).
 */
object RestrictionsPause {

    /** The pause's length: the hold's ceiling while it is the setup hold, else 2 hours. */
    fun durationMs(): Long = pauseDurationMs(LauncherPreferences.mdm().restrictionsPausedHold())

    /** Self-clears once expired, so a stale flag can't linger. */
    fun isActive(): Boolean {
        val mdm = LauncherPreferences.mdm()
        if (!mdm.restrictionsPaused()) return false
        if (BootClock.isActive(windowStart(), durationMs())) return true
        clear()
        return false
    }

    private fun windowStart(): WindowStart {
        val mdm = LauncherPreferences.mdm()
        return WindowStart(mdm.restrictionsPausedUntil(), mdm.restrictionsPausedElapsedStart(), mdm.restrictionsPausedBoot())
    }

    /** Design 139 §4.1 (revision 6, gate F2): the wall time the setup hold last ended in this process, written before
     * its flag clears, so the first app-group fold after it counts from here. */
    @Volatile
    var holdEndedWallMs: Long = Long.MIN_VALUE
        private set

    private fun noteHoldEnd() {
        val mdm = LauncherPreferences.mdm()
        if (mdm.restrictionsPaused() && mdm.restrictionsPausedHold()) holdEndedWallMs = System.currentTimeMillis()
    }

    /** Design 114 §6.1: the pause is on and is the setup hold. */
    fun holdActive(): Boolean = isActive() && LauncherPreferences.mdm().restrictionsPausedHold()

    /** The hold's wall-clock end, `null` outside it (the report's `hold_until_ms`). */
    fun holdUntilMs(): Long? = if (holdActive()) LauncherPreferences.mdm().restrictionsPausedUntil() else null

    /** The hold's time left by the elapsed clock, `null` outside it (the report's `hold_left_ms`, QA L6). */
    fun holdLeftMs(): Long? = if (holdActive()) {
        holdLeftMs(windowStart(), SystemClock.elapsedRealtime())
    } else {
        null
    }

    /** Starts the pause from [start] and re-applies at once - called only by [ParentPin.check]
     * after a correct PIN and its committed `unlocked` event (design 24 §2), and by the parent
     * app's `start_pause` (design 24b's fix round). Any thread. */
    fun start(context: Context, start: WindowStart) {
        val mdm = LauncherPreferences.mdm()
        noteHoldEnd()
        // Design 24b: the relock marker first - the pause locks again by itself when it ends.
        WindowRelock.opened(context, WindowKind.PAUSE, start) {
            mdm.restrictionsPausedUntil(start.untilWallMs)
            mdm.restrictionsPausedElapsedStart(start.elapsedStartMs)
            mdm.restrictionsPausedBoot(start.bootCount)
            // Design 114 (QA M6): a PIN pause is never a hold, so it never inherits the hold's length.
            mdm.restrictionsPausedHold(false)
            mdm.restrictionsPaused(true)
        }
        reapply(context)
    }

    /**
     * Design 114 §6.1.2: the setup hold, started only by `Enrollment.enroll` on a fresh phone (no parent PIN: the basis
     * is the factory reset and the parent's one-shot code). In order: POST_NOTIFICATIONS self-granted before the window
     * opens (QA M4: otherwise the window's "Lås igjen" notification is posted before any grant), the window opened with
     * its start snapshot's screen lock unknown (QA M1: never a credential removed at the relock), the flag and the pause
     * written in the same lift, then the apply. Background thread.
     */
    fun startHold(context: Context) {
        val app = context.applicationContext
        val mdm = LauncherPreferences.mdm()
        val start = BootClock.windowStart(SETUP_HOLD_DURATION_MS)
        try {
            val dpm = app.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (dpm != null && dpm.isDeviceOwnerApp(app.packageName)) {
                val admin = android.content.ComponentName(app, MdmDeviceAdminReceiver::class.java)
                QuickControls.fixOwnPermission(app, dpm, admin, android.Manifest.permission.POST_NOTIFICATIONS)
                // Code QA L1: fine location before the start snapshot reads the Wi-Fi proxies (no apply has run yet).
                for (permission in OWN_LOCATION_PERMISSIONS) QuickControls.selfGrantPermission(app, dpm, admin, permission)
            }
        } catch (e: Exception) {
            Log.w("RestrictionsPause", "The hold's notification grant failed: ${e.javaClass.simpleName}")
        }
        WindowRelock.opened(app, WindowKind.PAUSE, start) {
            mdm.restrictionsPausedUntil(start.untilWallMs)
            mdm.restrictionsPausedElapsedStart(start.elapsedStartMs)
            mdm.restrictionsPausedBoot(start.bootCount)
            mdm.restrictionsPausedHold(true)
            mdm.restrictionsPaused(true)
        }
        Log.i("RestrictionsPause", "The setup hold started (4 h at most)")
        reapply(app)
    }

    /**
     * Re-runs enforcement right away, offline, after the pause changed - rather than waiting for
     * the next sync. On a background coroutine: AppEnforcer.apply() can (re)start KidVpnService,
     * whose onCreate() reads the blocklist from disk synchronously - on the main thread that froze
     * the UI for seconds (an ANR). Ending a pause with no usable cached policy re-locks to the
     * last-enforced plan.
     */
    fun reapply(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // apply() releases everything itself while the pause is active.
                AppEnforcer.apply(app, currentPolicyDecision())
                reevaluateLockReasonFromCache(app)
                com.kidslauncher.mdm.timerules.TimeRuleAlarm.schedule(app)
                // Design r13 §2.2: the pause makes the anchor's notice plain "Vibb One" (and its end the time left).
                com.kidslauncher.mdm.timerules.AnchorRefresh.request(app)
            } catch (e: Exception) {
                Log.w("RestrictionsPause", "Re-applying after a pause change failed", e)
            }
        }
    }

    fun clear() {
        val mdm = LauncherPreferences.mdm()
        val wasOn = mdm.restrictionsPaused()
        noteHoldEnd()
        mdm.restrictionsPaused(false)
        mdm.restrictionsPausedUntil(0)
        mdm.restrictionsPausedHold(false)
        // Design 102 §3.3 (R2-M4): a Wi-Fi sign-in window opened in the pause closes at its end.
        if (wasOn) com.kidslauncher.mdm.portal.PortalRuntime.pauseEnded()
    }
}

/** [RestrictionsPause.durationMs]'s rule: the hold's ceiling while [hold], else 2 hours. Pure. */
fun pauseDurationMs(hold: Boolean): Long = if (hold) SETUP_HOLD_DURATION_MS else RESTRICTIONS_PAUSE_DURATION_MS

/** The hold's time left by the elapsed clock at [nowElapsedMs] (never below 0). Pure. */
fun holdLeftMs(start: WindowStart, nowElapsedMs: Long): Long =
    (SETUP_HOLD_DURATION_MS - (nowElapsedMs - start.elapsedStartMs)).coerceIn(0, SETUP_HOLD_DURATION_MS)
