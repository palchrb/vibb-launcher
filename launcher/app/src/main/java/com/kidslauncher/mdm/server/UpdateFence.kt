package com.kidslauncher.mdm.server

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.lock.LockTaskChrome
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.UpdateFenceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val LOG_TAG = "UpdateFence"
private const val ACTION_CHECK = "com.kidslauncher.mdm.action.UPDATE_FENCE_CHECK"

/** Not TimeRuleAlarm's (0), BackstopAlarm's (7) or SelfUpdate's (12) request code. */
private const val ALARM_REQUEST_CODE = 11

/**
 * The update fence's glue (handy step 11; the rules are the pure [fencePlan], [runFence],
 * [fenceRelease] and [runRelease] in UpdateFencePlan.kt). Fencing and every check take
 * [AppEnforcer]'s lock (the same monitor as `apply()`), so never call them on the main thread.
 * The status bar goes only through [LockTaskChrome] ([fenced] is its input); nothing here calls
 * `setStatusBarDisabled`. Release code is never removed (see UpdateFencePlan.kt).
 */
object UpdateFence {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A fence record exists - read at process start, kept by fence/release. */
    @Volatile
    var fenced: Boolean = false
        private set

    /** The session this process committed (or is committing) under the fence. */
    @Volatile
    private var committingSession: Int? = null

    /** Home or the lock screen came to the front in this process (or the screen went off). */
    @Volatile
    private var frontSeen = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(UPDATE_FENCE_PREFS, Context.MODE_PRIVATE)

    private fun summaryPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(UPDATE_FENCE_LAST_PREFS, Context.MODE_PRIVATE)

    /** The last fence's summary (qa-11-code #5), `null` if there never was one. */
    fun lastSummary(context: Context): FenceSummary? = try {
        decodeFenceSummary(summaryPrefs(context).all)
    } catch (e: Exception) {
        null
    }

    private fun writeSummary(context: Context, summary: FenceSummary) {
        try {
            val editor = summaryPrefs(context).edit().clear()
            for ((key, value) in encodeFenceSummary(summary)) {
                when (value) {
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't keep the fence summary", e)
        }
    }

    fun record(context: Context): FenceRecord? = try {
        decodeFenceRecord(prefs(context).all)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Fence record unreadable", e)
        null
    }

    /** Application.initRest, before PinLockRuntime.init (its chrome refresh reads [fenced]). One
     * small prefs read here; the check itself runs in the background. */
    fun init(context: Context) {
        val app = context.applicationContext
        fenced = record(app) != null
        if (fenced) {
            Log.i(LOG_TAG, "A fence is recorded at process start - checking")
            checkAsync(app, "process_start")
        } else {
            // No record: a lost or corrupt one must not strand suspended HOME apps (qa-11-code #1).
            scope.launch {
                try {
                    synchronized(AppEnforcer) { if (record(app) == null) sweepOrphansLocked(app, "process_start") }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Orphan sweep at process start failed", e)
                }
            }
        }
    }

    /** What `apply()` must not unsuspend while the fence is up. */
    fun heldPackages(context: Context): Set<String> = if (!fenced) emptySet() else record(context)?.toRelease.orEmpty()

    /** Home or the lock screen resumed, or the screen went off (finding 7). Cheap when no fence. */
    fun onFront(context: Context) {
        frontSeen = true
        if (fenced) checkAsync(context.applicationContext, "front")
    }

    fun checkAsync(
        context: Context,
        trigger: String,
        installResult: FenceInstallResult = FenceInstallResult.NONE,
        then: (FenceVerdict?) -> Unit = {},
    ) {
        val app = context.applicationContext
        scope.launch {
            val verdict = try {
                check(app, trigger, installResult)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Fence check ($trigger) failed", e)
                null
            }
            then(verdict)
        }
    }

    /**
     * The release rule with this phone's inputs; releases or re-arms the backstop alarm. From
     * [AppEnforcer.apply] pass its [policy] and [managed] ([inApply]: the pass decides the
     * controllable packages itself); elsewhere the cached policy decides. Background thread.
     */
    fun check(
        context: Context,
        trigger: String,
        installResult: FenceInstallResult = FenceInstallResult.NONE,
        policy: PolicyResponse? = null,
        managed: Boolean? = null,
        inApply: Boolean = false,
    ): FenceVerdict = synchronized(AppEnforcer) {
        val app = context.applicationContext
        val record = record(app)
        if (record == null) {
            fenced = false
            return@synchronized FenceVerdict.NoFence
        }
        val enforced = policy ?: if (managed == null) currentPolicyDecision().policy else null
        val isManaged = managed ?: run {
            CallPolicyStore.ensureLoaded(app)
            enforced?.allowlist != null || CallPolicyStore.state.managed
        }
        val now = FenceCheck(
            ownLastUpdateMs = ownLastUpdateMs(app),
            bootCount = BootClock.bootCount(),
            nowElapsedMs = SystemClock.elapsedRealtime(),
            nowWallMs = System.currentTimeMillis(),
            session = sessionState(app, record.sessionId),
            installResult = installResult,
            committingHere = record.sessionId != null && committingSession == record.sessionId,
            frontReady = frontSeen || !interactive(app),
            processAgeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
            managed = isManaged && isDeviceOwner(app),
            switchOn = enforced?.updateFence == true,
        )
        val verdict = fenceRelease(record, now)
        when (verdict) {
            is FenceVerdict.Release -> release(app, record, verdict.reason, inApply)
            is FenceVerdict.Keep -> {
                fenced = true
                Log.i(LOG_TAG, "Fence kept ($trigger): ${verdict.reason.wire}, next check in ${verdict.recheckInMs / 1000} s")
                scheduleAlarm(app, verdict.recheckInMs + 1_000L)
            }
            FenceVerdict.NoFence -> {}
        }
        verdict
    }

    /** [AppEnforcer.apply]'s hook (it holds the lock): the check with apply's own policy, then the
     * packages this pass must not unsuspend. Never throws. */
    fun duringApply(context: Context, policy: PolicyResponse?, managed: Boolean): Set<String> {
        if (!fenced && record(context) == null) {
            try {
                sweepOrphansLocked(context.applicationContext, "apply")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Orphan sweep in apply failed", e)
            }
            return emptySet()
        }
        return try {
            when (check(context, "apply", policy = policy, managed = managed, inApply = true)) {
                is FenceVerdict.Keep -> heldPackages(context)
                else -> emptySet()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Fence check in apply failed", e)
            heldPackages(context)
        }
    }

    /**
     * Right before the self-update's `session.commit()` (AppInstaller, under the sync mutex):
     * suspends every other HOME-capable package ([fencePlan]) with the record written first, and
     * disables the status bar through [LockTaskChrome]. Returns whether a fence is up; `false` =
     * the update goes in unfenced (switch off, unmanaged, not device owner, nothing readable).
     * Never throws.
     */
    fun fenceBeforeCommit(context: Context, sessionId: Int, releaseTag: String): Boolean = try {
        synchronized(AppEnforcer) { fenceLocked(context.applicationContext, sessionId, releaseTag) }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Fencing failed - the update goes in unfenced", e)
        false
    }

    private fun fenceLocked(app: Context, sessionId: Int, releaseTag: String): Boolean {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(app.packageName)) return false
        val policy = currentPolicyDecision().policy
        CallPolicyStore.ensureLoaded(app)
        val managed = policy?.allowlist != null || CallPolicyStore.state.managed
        if (policy?.updateFence != true || !managed) {
            Log.i(LOG_TAG, "No fence for $releaseTag (switch ${policy?.updateFence == true}, managed $managed)")
            return false
        }
        val boot = BootClock.bootCount()
        val ownUpdate = ownLastUpdateMs(app)
        if (boot < 0 || ownUpdate < 0) {
            Log.w(LOG_TAG, "No fence: boot count or our own update time unreadable")
            return false
        }
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        // A leftover record (it should have been released) is released before the new fence.
        record(app)?.let { release(app, it, FenceReleaseReason.SUPERSEDED, inApply = false) }

        val pm = app.packageManager
        val candidates = homeCandidates(pm)
        val plan = fencePlan(
            homeCandidates = candidates,
            ownPackage = app.packageName,
            protected = protectedPackages(app, dpm, admin),
            controllable = controllablePackages(pm).toSet(),
            alreadySuspended = candidates.map { it.packageName }.filterTo(mutableSetOf()) { suspended(pm, it) },
            // Design 16d: never the recents provider - the gestures keep working during the update.
            recentsPackage = AppEnforcer.systemRecentsPackage(app),
        )
        val planned = FenceRecord(
            version = UPDATE_FENCE_V1,
            stage = FenceStage.PLANNED,
            planned = plan.suspend,
            suspended = emptySet(),
            sessionId = sessionId,
            ownLastUpdateMs = ownUpdate,
            bootCount = boot,
            startedWallMs = System.currentTimeMillis(),
            startedElapsedMs = SystemClock.elapsedRealtime(),
            releaseTag = releaseTag,
        )
        committingSession = sessionId
        frontSeen = false
        val active = runFence(platform(app, dpm, admin), planned)
        if (active == null) {
            committingSession = null
            Log.w(LOG_TAG, "No fence: the record couldn't be written - nothing suspended")
            return false
        }
        fenced = true
        val roleHeld = homeRoleHeld(app)
        writeSummary(
            app,
            FenceSummary(
                reason = null,
                unsuspendable = active.unsuspendable,
                homeRoleHeld = roleHeld,
                fencedAtMs = active.startedWallMs,
                releasedAtMs = null,
                releaseTag = releaseTag,
            ),
        )
        Log.i(
            LOG_TAG,
            "Fenced for $releaseTag (session $sessionId): suspended ${active.suspended}, refused ${active.unsuspendable}, " +
                "skipped ${plan.skipped}, home role ours $roleHeld",
        )
        LockTaskChrome.fenceChanged(app)
        scheduleAlarm(app, FENCE_MAX_MS + 5_000L)
        return true
    }

    /** The commit threw after the fence went up: release at once (the session is abandoned). */
    fun commitFailed(context: Context) {
        try {
            synchronized(AppEnforcer) {
                record(context)?.let { release(context.applicationContext, it, FenceReleaseReason.COMMIT_FAILED, inApply = false) }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Release after a failed commit failed", e)
        }
    }

    /**
     * A PackageInstaller result (any app's): a result for the fence's own session is the input,
     * any other only triggers the check. Returns whether it was the fence's session. Background.
     */
    fun onInstallResult(context: Context, sessionId: Int, status: Int): Boolean {
        val record = record(context) ?: return false
        val ours = record.sessionId != null && record.sessionId == sessionId
        val result = when {
            !ours -> FenceInstallResult.NONE
            status == PackageInstaller.STATUS_SUCCESS -> FenceInstallResult.SUCCESS
            status == PackageInstaller.STATUS_PENDING_USER_ACTION -> FenceInstallResult.PENDING_USER_ACTION
            else -> FenceInstallResult.FAILURE
        }
        check(context, "install_result", result)
        return ours
    }

    private fun release(app: Context, record: FenceRecord, reason: FenceReleaseReason, inApply: Boolean) {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        val controllableNow = try {
            controllablePackages(app.packageManager).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val outcome = runRelease(platform(app, dpm, admin), record, controllableNow)
        fenced = false
        committingSession = null
        val before = lastSummary(app)
        writeSummary(
            app,
            (before ?: FenceSummary(unsuspendable = record.unsuspendable, fencedAtMs = record.startedWallMs, releaseTag = record.releaseTag))
                .copy(reason = reason.wire, releasedAtMs = System.currentTimeMillis()),
        )
        cancelAlarm(app)
        Log.i(
            LOG_TAG,
            "Fence released (${reason.wire}): unsuspended ${outcome.unsuspended}, left to apply ${outcome.leftToApply}, refused ${outcome.refused}",
        )
        try {
            LockTaskChrome.fenceChanged(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Status bar refresh after the release failed", e)
        }
        if (outcome.leftToApply.isNotEmpty() && !inApply) {
            scope.launch {
                try {
                    AppEnforcer.apply(app, currentPolicyDecision().policy)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Apply after the release failed", e)
                }
            }
        }
    }

    /**
     * qa-11-code #1: with no record, unsuspends every HOME package only a fence can have suspended
     * ([orphanFenceTargets]). Cheap when there's nothing to do (one HOME query and a suspended
     * check per candidate); the protected set is only resolved when a candidate qualifies. Holds
     * [AppEnforcer]'s lock (the caller takes it).
     */
    private fun sweepOrphansLocked(app: Context, trigger: String) {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(app.packageName)) return
        val pm = app.packageManager
        val candidates = homeCandidates(pm)
        val suspendedHomes = candidates.map { it.packageName }.filterTo(mutableSetOf()) { pkg ->
            try {
                pm.isPackageSuspended(pkg)
            } catch (e: Exception) {
                false
            }
        }
        if (suspendedHomes.isEmpty()) return
        val controllable = controllablePackages(pm).toSet()
        if ((suspendedHomes - controllable).isEmpty()) return
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        val targets = orphanFenceTargets(
            candidates, app.packageName, protectedPackages(app, dpm, admin), controllable, suspendedHomes,
            recentsPackage = AppEnforcer.systemRecentsPackage(app),
        )
        if (targets.isEmpty()) return
        val refused = try {
            dpm.setPackagesSuspended(admin, targets.toTypedArray(), false).orEmpty().toSet()
        } catch (e: Exception) {
            targets
        }
        Log.w(LOG_TAG, "Orphaned fence ($trigger): no record, unsuspended ${targets - refused}, refused $refused")
        writeSummary(
            app,
            (lastSummary(app) ?: FenceSummary()).copy(reason = FenceReleaseReason.ORPHAN.wire, releasedAtMs = System.currentTimeMillis()),
        )
        try {
            LockTaskChrome.fenceChanged(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Status bar refresh after the orphan sweep failed", e)
        }
    }

    private fun platform(app: Context, dpm: DevicePolicyManager?, admin: ComponentName) = object : FencePlatform {
        override fun write(record: FenceRecord): Boolean {
            val editor = prefs(app).edit().clear()
            for ((key, value) in encodeFenceRecord(record)) {
                when (value) {
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            return editor.commit()
        }

        override fun clear(): Boolean = prefs(app).edit().clear().commit()

        override fun suspend(packages: Set<String>): Set<String> =
            dpm!!.setPackagesSuspended(admin, packages.toTypedArray(), true).orEmpty().toSet()

        override fun unsuspend(packages: Set<String>): Set<String> =
            dpm!!.setPackagesSuspended(admin, packages.toTypedArray(), false).orEmpty().toSet()
    }

    // ---- what the phone says ------------------------------------------------------------------

    private fun homeCandidates(pm: PackageManager): List<HomeCandidate> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val flags = activity.applicationInfo?.flags ?: 0
                HomeCandidate(
                    packageName = activity.packageName,
                    system = flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    persistent = flags and ApplicationInfo.FLAG_PERSISTENT != 0,
                    priority = info.priority,
                )
            }

    /** qa-11-design.md #6: everything a fence must leave alone on this phone. */
    private fun protectedPackages(app: Context, dpm: DevicePolicyManager, admin: ComponentName): Set<String> {
        val pm = app.packageManager
        val settings = try {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        val defaultDialer = try {
            app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (e: Exception) {
            null
        }
        val lockTaskPackages = try {
            dpm.getLockTaskPackages(admin).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val helpers = runCatching { AppEnforcer.resolvePinLockHelpers(app) }.getOrDefault(emptySet()) +
            runCatching { AppEnforcer.resolveLockTaskHelpers(app) }.getOrDefault(emptySet())
        return setOfNotNull(settings, defaultDialer, systemDialerPackage(app)) +
            inputMethodPackages(app) + lockTaskPackages + helpers + com.kidslauncher.mdm.play.PLAY_NEVER_RESTRICT
    }

    private fun suspended(pm: PackageManager, pkg: String): Boolean = try {
        pm.isPackageSuspended(pkg)
    } catch (e: Exception) {
        // Unknown: treated as suspended, so the fence never claims (and later releases) it.
        true
    }

    private fun ownLastUpdateMs(app: Context): Long = try {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        -1L
    }

    private fun sessionState(app: Context, sessionId: Int?): FenceSession {
        if (sessionId == null) return FenceSession.NONE
        return try {
            val info = app.packageManager.packageInstaller.getSessionInfo(sessionId) ?: return FenceSession.GONE
            if (info.isCommitted) FenceSession.COMMITTED else FenceSession.UNCOMMITTED
        } catch (e: Exception) {
            FenceSession.UNKNOWN
        }
    }

    private fun interactive(app: Context): Boolean = try {
        app.getSystemService(PowerManager::class.java)?.isInteractive != false
    } catch (e: Exception) {
        true
    }

    private fun isDeviceOwner(app: Context): Boolean = try {
        app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true
    } catch (e: Exception) {
        false
    }

    private fun homeRoleHeld(app: Context): Boolean? = try {
        app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME)
    } catch (e: Exception) {
        null
    }

    // ---- backstop alarm -----------------------------------------------------------------------

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        ALARM_REQUEST_CODE,
        Intent(app, UpdateFenceReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleAlarm(app: Context, inMs: Long) {
        try {
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + inMs.coerceAtLeast(1_000L)
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(app))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(app))
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the fence backstop", e)
        }
    }

    private fun cancelAlarm(app: Context) {
        try {
            app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the fence backstop", e)
        }
    }

    // ---- status report ------------------------------------------------------------------------

    fun report(context: Context, policy: PolicyResponse?): UpdateFenceReport {
        val app = context.applicationContext
        val record = record(app)
        val summary = lastSummary(app)
        val pending = TrackedAppUpdateState.pendingEntry()?.second
        return UpdateFenceReport(
            enabled = policy?.updateFence == true,
            state = when {
                record == null -> "none"
                record.stage == FenceStage.ACTIVE -> "fenced"
                else -> "planned"
            },
            // The live record's refusals, else the last fence's (qa-11-code #5).
            unsuspendable = (record?.unsuspendable ?: summary?.unsuspendable).orEmpty().sorted().take(20),
            lastRelease = summary?.reason,
            homeRoleHeld = summary?.homeRoleHeld ?: homeRoleHeld(app),
            pendingTag = pending?.releaseTag,
            pendingSinceMs = pending?.downloadedAtMs,
            waitingFor = if (pending != null) SelfUpdate.lastWait?.wire else null,
            lastFencedAtMs = summary?.fencedAtMs,
            lastReleasedAtMs = summary?.releasedAtMs,
        )
    }
}

/** The fence's backstop alarm - only our own explicit PendingIntent reaches it. */
class UpdateFenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val pending = goAsync()
        UpdateFence.checkAsync(context.applicationContext, "alarm") { pending.finish() }
    }
}
