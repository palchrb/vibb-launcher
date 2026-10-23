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
 * `adb shell svc power reboot`. In android mode (design 59 F3) the locked boot arms it as well
 * ([atLockedBoot], also the fallback for a shutdown broadcast this cached process never got) and
 * the cover waits for this process's hand-over ([coverWaitStep]).
 * [report] is the status report's `boot_cover`.
 */
object BootCover {
    private const val PREFS = "boot_cover"
    private const val KEY_WANTED = "wanted"
    private const val KEY_ARMED_AT = "armed_at_ms"
    private const val KEY_LAUNCHER_HANDOVER_AT = "launcher_handover_at_ms"
    private const val KEY_SWITCH = "switch_on"

    @Volatile
    private var receiverRegistered = false

    /** F3C-6: this process's first start after the unlock ran ([init]) - a late `LOCKED_BOOT_COMPLETED` (the component
     * factory can run the unlocked setup while `isUserUnlocked` still reads false) starts no cover over our Home. */
    @Volatile
    var tookOver = false
        private set

    /** `ACTION_SHUTDOWN` arrived: no more Home starts (qa-16b-code #3) - with the cover armed a
     * typed HOME start could resolve to it. */
    @Volatile
    var shuttingDown = false
        private set

    private fun component(context: Context) = BootCoverActivity.component(context)

    /**
     * From `AppEnforcer.apply` (background thread), after the PIN lock's configure: [androidGate] is "Android's PIN is
     * the gate" as that apply left it ([AndroidLock.gateNow]: the face, or our own credential on a secure phone - the
     * rollback's escrow included, F3C-4) - Android's lock implies the cover (F3). An apply without the parent's choice
     * ([parentChoice] false: the last-enforced fallback, no policy yet) changes nothing here: it can't tell the mode, and
     * a fallback must not turn the cover off for the next reboot (F3C-4).
     */
    fun applyPolicy(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        switchOn: Boolean,
        managed: Boolean,
        androidGate: Boolean,
        parentChoice: Boolean,
    ) {
        if (!parentChoice) return
        val app = context.applicationContext
        val wanted = bootCoverWanted(switchOn, managed, deviceOwner = true, androidFace = androidGate)
        val switchWas = prefs(app).getBoolean(KEY_SWITCH, false)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).putBoolean(KEY_SWITCH, switchOn).commit()
        // The switch off clears a tripped crash guard: on again = one more try (qa-16b-code #2). F3C-3: so does an
        // update since the trip or the switch's off -> on edge, in every mode (android mode keeps `wanted` true).
        if (!wanted || coverGuardClearDue(BootCoverGuard.read(app), com.kidslauncher.mdm.BuildConfig.VERSION_CODE.toLong(), switchWas, switchOn)) {
            BootCoverGuard.clear(app)
        }
        // F3: the device-protected marker the cover and the locked boot read (no CE before the PIN).
        BootCoverGuard.setAndroidMode(app, bootCoverAndroidMarker(wanted, androidGate))
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

    /** From `PinLockRuntime.init` (main thread, unlocked): the shutdown receiver, and the hand-over of a cover that is
     * still enabled (before the boot's Home start) - at every CE start, in every mode, whatever a call does, so that
     * once unlocked one HOME PPA of ours resolves, HomeActivity's (f3-run B2). Not the chooser's cause: AOSP's
     * `findPersistentPreferredActivity` returns the first PPA whose component matches, so two of ours can't make it -
     * the run's three-entry chooser means no PPA matched (qa-f3-round2-code F3R-1/F3R-2; the device recipe in design
     * 59 "F3"). A call keeps only `BootHome`'s Home start back, never the take-over: a cover in front then starts our
     * Home itself ([coverWaitStep]'s START_HOME, in a call too - nothing is over it while it is resumed). */
    fun init(context: Context) {
        val app = context.applicationContext
        tookOver = true
        cancelRetry(app)
        if (!receiverRegistered) {
            receiverRegistered = true
            try {
                app.registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN), Context.RECEIVER_NOT_EXPORTED)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't register for shutdown", e)
            }
        }
        takeOver(app)
    }

    /** Disables a cover still enabled (the hand-over; our typed Home start then resolves to HomeActivity only).
     * Idempotent. Main thread, right before a boot Home start ([init], `BootHome.callMayHaveEnded`). */
    fun takeOver(context: Context) {
        val app = context.applicationContext
        if (isEnabled(app)) {
            setEnabled(app, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted(app)), "main process after unlock")
            prefs(app).edit().putLong(KEY_LAUNCHER_HANDOVER_AT, System.currentTimeMillis()).apply()
        }
    }

    /** The locked boot's retry ([LockedBootCover.RETRY]): `BootCallReceiver`'s explicit action. */
    const val ACTION_COVER_RETRY = "me.vibb.launcher.action.BOOT_COVER_RETRY"

    private const val RETRY_REQUEST_CODE = 59

    /**
     * Design 59 F3: `LOCKED_BOOT_COMPLETED` and its retry alarm (main process, before the PIN, from `BootCallReceiver`;
     * device-protected storage only - nothing here may read CE). In android mode ([BootCoverGuard.androidMode]) the
     * cover is enabled and started as the typed HOME - before the first unlock only direct-boot-aware HOMEs resolve, so
     * within our package that is the cover alone - so a boot without the shutdown's arm (`adb reboot`, a crash, a hard
     * reset, a shutdown broadcast our cached process never got) has it behind Android's keyguard too, and the PIN's
     * dismissal reveals it, not the stock launcher. In android mode the cover is the BFU face over the keyguard while
     * its pad gate holds (design 59d step F: showWhenLocked at runtime, it asks for Android's PIN pad), else behind it
     * (Android's lock screen with the restart line is the face). Decided now ([coverAtLockedBoot], f3-run B1): nothing once
     * unlocked, after our take-over ([tookOver]) or with a tripped crash guard - a boot the shutdown armed included
     * (F3R-2: the typed start brings the cover over whatever the boot started, the chooser too; already in front, it
     * is a new intent to its singleTask); a live call is retried by an alarm (delivered to a frozen process, unlike a
     * runtime receiver) - until the call ends, the unlock or our take-over, whichever comes first: no cap, the unlock
     * bounds it, and the outcome while it waits is the old stock launcher. Main thread. Never throws.
     */
    fun atLockedBoot(context: Context) {
        try {
            val app = context.applicationContext
            when (lockedBootStep(app)) {
                LockedBootCover.NONE -> cancelRetry(app)
                LockedBootCover.RETRY -> retryLater(app)
                LockedBootCover.START -> {
                    cancelRetry(app)
                    setEnabled(app, true, "locked boot (android mode)")
                    app.startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .setPackage(app.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    // F3R-4: logged, never decided on (B1's race) - how often the start comes with the screen on.
                    val interactive = try {
                        app.getSystemService(android.os.PowerManager::class.java)?.isInteractive
                    } catch (e: Exception) {
                        null
                    }
                    Log.i(LOG_TAG, "Boot cover started at the locked boot (android mode, interactive $interactive)")
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover at the locked boot failed: ${e.javaClass.simpleName}")
        }
    }

    /** [coverAtLockedBoot] with this moment's facts - device-protected storage, binder reads, memory only. */
    private fun lockedBootStep(app: Context): LockedBootCover {
        logHomeResolution(app)
        return coverAtLockedBoot(
            androidMarker = BootCoverGuard.androidMode(app),
            unlocked = app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true,
            guardTripped = coverGuardTripped(BootCoverGuard.read(app)),
            tookOver = tookOver,
            callLive = BootHome.callLive(app),
        )
    }

    /** What the boot's HOME resolves to now - logged only (F3R-2: no decision on it; the device recipe for a chooser
     * boot greps it, design 59 "F3"). */
    private fun logHomeResolution(app: Context) {
        try {
            val home = app.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo
            Log.i(LOG_TAG, "Locked boot: HOME resolves to ${home?.packageName}/${home?.name}")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Locked boot: HOME unreadable: ${e.javaClass.simpleName}")
        }
    }

    /** The retry's PendingIntent; with [create] false only an existing one (`FLAG_NO_CREATE`, F3R-6), else `null`. */
    private fun retryIntent(app: Context, create: Boolean): android.app.PendingIntent? = android.app.PendingIntent.getBroadcast(
        app,
        RETRY_REQUEST_CODE,
        Intent(app, com.kidslauncher.mdm.calls.BootCallReceiver::class.java).setAction(ACTION_COVER_RETRY),
        android.app.PendingIntent.FLAG_IMMUTABLE or
            if (create) android.app.PendingIntent.FLAG_UPDATE_CURRENT else android.app.PendingIntent.FLAG_NO_CREATE,
    )

    /** f3-run B1: an alarm to our manifest receiver, never a runtime receiver - a cached, frozen BFU process gets it.
     * Exact while it may (`USE_EXACT_ALARM`, the pattern of `TimeRuleAlarm`/`Torch`), else inexact. */
    private fun retryLater(app: Context) {
        try {
            val alarms = app.getSystemService(android.app.AlarmManager::class.java) ?: return
            val at = android.os.SystemClock.elapsedRealtime() + COVER_LOCKED_BOOT_RETRY_MS
            val intent = retryIntent(app, create = true) ?: return
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            }
            Log.i(LOG_TAG, "Boot cover waits: a call is live - again in ${COVER_LOCKED_BOOT_RETRY_MS} ms")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover couldn't arm its retry: ${e.javaClass.simpleName}")
        }
    }

    /** Cancels a pending retry - creates no PendingIntent at every CE start (F3R-6). */
    private fun cancelRetry(app: Context) {
        try {
            val intent = retryIntent(app, create = false) ?: return
            app.getSystemService(android.app.AlarmManager::class.java)?.cancel(intent)
            intent.cancel()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover couldn't cancel its retry: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The process's one `ACTION_SHUTDOWN` receiver (design 38 §4.2, QA M3): the cover's synchronous
     * writes first, exactly as before, then design 59's restart line ([AndroidLock.onShutdown]), then the
     * phone's shutdown for the parent
     * ([com.kidslauncher.mdm.server.PowerEvents.onShutdown]: its record committed, then `goAsync`
     * for the POST). Each part in its own `try`, so a throw in one never skips the other (QA N5).
     */
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            val app = context.applicationContext
            try {
                coverAtShutdown(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Boot cover shutdown writes failed", e)
            }
            try {
                // Design 59 §3.2: as Android's face, the restart line for Android's lock screen before the first unlock.
                AndroidLock.onShutdown(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The restart line failed: ${e.javaClass.simpleName}")
            }
            try {
                com.kidslauncher.mdm.server.PowerEvents.onShutdown(app, intent, this)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The shutdown record failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun coverAtShutdown(app: Context) {
        // Design 32 step 2 (M4): a clean reboot has no keyguard - the cover's goes off now,
        // synchronously, and is re-armed at the boot's first lock resume with the screen on.
        KeyguardCover.onShutdown(app)
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
