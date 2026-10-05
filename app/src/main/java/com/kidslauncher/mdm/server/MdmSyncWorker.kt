package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.calls.CALL_POLICY_CAPABILITY
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallStateReport
import com.kidslauncher.mdm.calls.callPrefsUpdate
import com.kidslauncher.mdm.notifyAppInstallResult
import com.kidslauncher.mdm.notifyAppInstalling
import com.kidslauncher.mdm.server.dto.CommandResultRequest
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.server.dto.PendingCommand
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import java.io.File
import java.time.Instant
import java.util.Calendar

private const val LOG_TAG = "MdmSyncWorker"

// CommandListenerService's periodic timer, its SSE push-nudge handler, and the Settings screen's
// "Sync now" button each independently call performMdmSync with no coordination between them -
// confirmed live that two overlapping calls processing the same pending tracked-app update (most
// often the launcher's own self-update, which is in every device's batch whenever a new build's
// published) race on the shared per-app cache file: one call's AppInstallReceiver cleanup (delete
// on failure) can delete the file a second, still-in-flight call just wrote, or corrupt it
// mid-write - producing exactly the INSTALL_PARSE_FAILED_NO_CERTIFICATES / FileNotFoundException
// failures seen in logcat. withLock (not tryLock-and-skip) so a sync that lands while another's
// already running queues and still completes, rather than silently no-oping - the trade-off is an
// occasional redundant back-to-back sync when two triggers land close together, which is cheap
// compared to a corrupted install.
private val syncMutex = Mutex()

/**
 * Combined heartbeat + policy sync: policy fetch/cache/evaluate, app allowlist + kiosk
 * enforcement, best-effort status report. Shared by [CommandListenerService]'s periodic timer
 * and push-nudge handling, and the Settings screen's "Sync now" dev action, so all three go
 * through the exact same logic.
 *
 * Returns true only if the server was actually reached this cycle (a fresh policy fetch
 * succeeded) - every sub-step below (status report, update check) already fails silently and
 * falls back to cached state on its own, so this is the one signal that reflects whether real
 * network contact happened, for callers like the "Sync now" button that want to tell the user
 * the truth about whether it worked.
 */
suspend fun performMdmSync(context: Context): Boolean = syncMutex.withLock {
    val mdm = LauncherPreferences.mdm()
    val serverUrl = mdm.serverUrl()
    val deviceToken = mdm.deviceToken()
    if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
        return false
    }

    // The embedded tailnet connection (see CLAUDE.md) is kicked off eagerly from
    // Application.onCreate() - this is a retry-until-connected backstop for whenever that hasn't
    // succeeded yet (no auth key configured at startup, transient failure, ...), so createMdmApi
    // below can pick up TsnetClient's SOCKS5 proxy. No-ops if already connected or no key is set.
    TsnetClient.connectFromPreferences(context)

    val api = createMdmApi(serverUrl, deviceToken)
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    val cached = cachedPolicy()
    val policyEverApplied = mdm.policyEverApplied()

    // Only a fresh policy that decodes and passes judgeFresh is acted on - see PolicyGate.kt.
    // Everything in the `freshPolicy != null` block (cache, PIN hash, commands, uninstalls) is
    // skipped for a rejected or undecodable one, exactly as if the server were unreachable.
    var freshPolicy: PolicyResponse? = null
    val freshOutcome = when (val fetched = fetchPolicy(api)) {
        null -> FreshOutcome.UNREACHABLE
        is FreshDecode.Failed -> if (fetched === POLICY_SERVER_ERROR) FreshOutcome.SERVER_ERROR else {
            Log.w(LOG_TAG, "Server policy doesn't decode, keeping the current one: ${fetched.error}")
            FreshOutcome.DECODE_FAILED
        }
        is FreshDecode.Ok -> when (judgeFresh(fetched.policy, cached, policyEverApplied, mdm.callsManagedLast())) {
            FreshVerdict.REJECT_SUSPECT -> {
                Log.w(LOG_TAG, "Ignoring a server policy without an allowlist or call_policy on a managed phone")
                FreshOutcome.REJECTED_SUSPECT
            }
            FreshVerdict.ACCEPT -> {
                freshPolicy = fetched.policy
                FreshOutcome.ACCEPTED
            }
        }
    }

    if (freshPolicy != null) {
        storeAcceptedPolicy(context, freshPolicy)
        // The call services read the rules from memory, never per call.
        CallPolicyStore.refresh(context)
        // Real server contact just succeeded - the offline override's whole job (bridging the gap
        // until the device can hear from the server again) is done, so let real policy reassert
        // immediately rather than waiting out the rest of its time window.
        OfflineOverride.clear()
        // Cache the hash+salt into their own preference slots (not just inside the serialized
        // kid_mode_policy blob) - this is what lets OfflineOverride verify a locally-entered PIN
        // with zero network at all, which is the entire point of the offline failsafe.
        mdm.overridePinHash(freshPolicy.overridePinHash)
        mdm.overridePinSalt(freshPolicy.overridePinSalt)
        // KidVpnService reads this cached value directly (it never talks to the network itself for
        // policy) - see DnsFilterEngine.resolveUpstream.
        mdm.dnsUpstreamProvider(freshPolicy.dnsUpstreamProvider)
        // Only actually re-fetches the (potentially 100k+ domain) full list if the version token
        // changed - see DnsFilterEngine's doc comment.
        DnsFilterEngine.refreshIfNeeded(context, api, freshPolicy.dnsFilterVersion)
        // Only ever dispatched off a genuinely fresh fetch, never the cached fallback below - the
        // cached policy blob can still hold a `pendingCommand` from a past cycle that's already
        // been delivered and consumed server-side, and replaying it from cache while offline would
        // re-run an old command (harmless for ring, not for lock/wipe).
        dispatchPendingCommand(context, api, dpm, admin, freshPolicy.pendingCommand)
        // Same "only off a genuinely fresh fetch" reasoning as the pending-command dispatch above -
        // the server clears an entry once a status report confirms the package is gone, so acting
        // on a stale cached list while offline would just be redundant, not actively harmful, but
        // there's no reason to.
        freshPolicy.packagesToUninstall.forEach { AppInstaller.uninstallSilently(context, it) }
    }

    val overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()
    val decision = choosePolicy(freshPolicy, cached, policyEverApplied, lastEnforcedPlan())
    if (decision is PolicyToApply.Fallback) {
        Log.w(LOG_TAG, "No usable cached policy - enforcing the last-enforced plan")
    }
    val reason = KidModeEnforcer.lockReasonNow(decision.policy, overrideActive, Calendar.getInstance())
    mdm.lockReason(reason)
    // Derives the same lock from the same policy and suspends apps while it's on.
    AppEnforcer.apply(context, decision.policy)

    // A `ring`/`locate` command means the admin explicitly wants to know where the device is right
    // now, worth the cost of an active GPS/network fix - every other sync (the background chain,
    // push-triggered syncs, and manual "Sync now") just reads whatever's cached/throttled instead,
    // so location isn't forcing an active fetch (visible location indicator, slower sync) on every
    // single cycle.
    val forceFreshLocation = freshPolicy?.pendingCommand?.command in setOf("ring", "locate")

    // Best-effort - a failed report must never affect the lock decision above.
    try {
        api.sendStatus(
            StatusReportRequest(
                lockReason = reason.name,
                kioskEngaged = mdm.kioskEnabled(),
                installedApps = collectInstalledApps(context),
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                offlineOverrideUsed = mdm.offlineOverrideUsedPendingReport(),
                location = currentLocationReport(context, dpm, admin, forceFreshLocation),
                policyState = policyState(freshOutcome, cached, policyEverApplied),
                restrictionsPaused = RestrictionsPause.isActive(),
                capabilities = listOf(CALL_POLICY_CAPABILITY),
                callState = CallStateReport.build(context),
            )
        )
        // The report just landed, so this doesn't need to stay pending - if it was never used,
        // this is a harmless false->false write.
        mdm.offlineOverrideUsedPendingReport(false)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Status report failed", e)
    }

    checkForTrackedAppUpdates(context, api)
    reportBlockedDnsEvents(context, api)

    return freshPolicy != null
}

/** Drains whatever [KidVpnService] has queued via [BlockedEventLog] since the last successful
 * report - best-effort, same as the status report above; only clears the queue once the server
 * call actually succeeds, so a failed report doesn't silently lose events. */
private suspend fun reportBlockedDnsEvents(context: Context, api: MdmApi) {
    val events = BlockedEventLog.drain(context)
    if (events.isEmpty()) return
    try {
        api.sendDnsEvents(events)
        BlockedEventLog.clearReported(context, events.size)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Blocked-DNS-event report failed", e)
    }
}

/**
 * Find My Device's remote-command dispatch - ring/stop_ring/lock/wipe, or `locate` (a no-op here;
 * a location reading is already attached to every status report regardless, via
 * [currentLocationReport] below, so `locate` exists purely as a way for the admin site to nudge an
 * out-of-cycle report sooner, not a distinct on-device action). No result is ever reported for
 * `wipe` - the device is gone by the time it would report back.
 */
private suspend fun dispatchPendingCommand(
    context: Context,
    api: MdmApi,
    dpm: DevicePolicyManager,
    admin: ComponentName,
    pending: PendingCommand?,
) {
    if (pending == null || !dpm.isDeviceOwnerApp(context.packageName)) return

    when (pending.command) {
        "ring" -> {
            LocateCommands.ring(context)
            reportCommandResult(api, pending.id, success = true, message = "ringing")
        }

        "stop_ring" -> {
            LocateCommands.stopRingAndRestore(context)
            reportCommandResult(api, pending.id, success = true, message = "stopped")
        }

        "lock" -> {
            val ok = LocateCommands.lock(dpm, admin)
            reportCommandResult(api, pending.id, ok, if (ok) "locked" else "failed to lock")
        }

        "wipe" -> LocateCommands.wipe(dpm, admin)

        "locate" -> reportCommandResult(api, pending.id, success = true, message = "attached to next report")

        else -> Log.w(LOG_TAG, "Unknown pending command: ${pending.command}")
    }
}

private suspend fun reportCommandResult(api: MdmApi, commandId: Long, success: Boolean, message: String?) {
    try {
        api.sendCommandResult(CommandResultRequest(commandId, success, message))
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Failed to report command result for id=$commandId", e)
    }
}

private suspend fun currentLocationReport(
    context: Context,
    dpm: DevicePolicyManager,
    admin: ComponentName,
    forceFresh: Boolean,
): LocationReport? {
    if (!dpm.isDeviceOwnerApp(context.packageName)) return null
    val location = LocateCommands.currentLocation(context, dpm, admin, forceFresh) ?: return null
    return LocationReport(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        capturedAt = Instant.ofEpochMilli(location.time).toString(),
    )
}

/**
 * Downloads and silently installs a newer release for every app tracked server-side (see
 * kid-phone-server's `handlers::tracked_apps`) that's actually scoped to this device (or is the
 * launcher itself, see [TrackedAppUpdate.isLauncher] - always included regardless of scoping) -
 * either from a GitHub repo's Releases (e.g. Tailscale) or manually uploaded by an admin. The
 * launcher's own self-update goes through this exact same path now too, since it's just another
 * tracked app server-side - so there's no special-cased launcher-update code here at all. The one
 * real place self-update still differs is [AppInstallReceiver] skipping its cache-file cleanup on
 * success, since installing over yourself risks the process dying before that line runs. One
 * app's failure never affects another's, or the rest of the sync - *except* the launcher's own
 * self-update, which is why it's always processed last (see the reordering below): installing an
 * update over the running app can get this process SIGKILLed the moment [AppInstaller] commits
 * that session, and everything after that point in this function (any other app still queued in
 * this loop, [reportBlockedDnsEvents] back in [performMdmSync]) would simply never run this cycle.
 * A committed [android.content.pm.PackageInstaller] session is handled by the OS from that point
 * on regardless of whether this process survives, so every other app's install is safe to have
 * already been kicked off first - it isn't reverted just because we don't stick around to see the
 * result.
 */
// Generous for a slow download+install over a poor connection, short enough that a genuinely
// abandoned attempt (process died mid-download, AppInstallReceiver's callback somehow never
// fired) doesn't block retries for long - see TrackedAppUpdateState.recordAttemptStarted's own
// doc comment for the actual bug this guards against.
private const val INSTALL_ATTEMPT_TIMEOUT_MS = 10 * 60 * 1000L

// A release that failed to install once is retried automatically after this window rather than
// being skipped forever - see TrackedAppUpdateState's own doc comment for the incident that
// motivated this (a release stuck failed from the since-fixed overlapping-install race showed zero
// notification and zero server-side visibility indefinitely, since nothing ever cleared
// lastFailedTag short of the upstream release itself changing).
private const val FAILED_RETRY_BACKOFF_MS = 60 * 60 * 1000L

private suspend fun checkForTrackedAppUpdates(context: Context, api: MdmApi) {
    val updates = try {
        api.getTrackedAppUpdates().body() ?: return
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Tracked app update check failed", e)
        return
    }

    val (launcherUpdates, otherUpdates) = updates.partition { it.isLauncher }
    val state = TrackedAppUpdateState.load()
    for (update in otherUpdates + launcherUpdates) {
        val key = update.id.toString()
        val known = state[key]
        if (update.releaseTag == known?.lastInstalledTag) {
            continue
        }
        val failedAt = known?.lastFailedAtMs
        if (update.releaseTag == known?.lastFailedTag &&
            failedAt != null &&
            System.currentTimeMillis() - failedAt < FAILED_RETRY_BACKOFF_MS
        ) {
            continue
        }
        val attemptStartedAt = known?.attemptStartedAtMs
        if (attemptStartedAt != null &&
            System.currentTimeMillis() - attemptStartedAt < INSTALL_ATTEMPT_TIMEOUT_MS
        ) {
            // A previous cycle already started this app's download+install and it hasn't resolved
            // yet (installSilently's commit() returns long before the real result arrives) - don't
            // fire a second, overlapping attempt for the same target. Confirmed live: checking a
            // second app while the first was still installing caused the first to restart from
            // this exact redundant re-attempt, and the second app's own attempt never completed.
            Log.i(LOG_TAG, "Skipping ${update.name} - an install attempt is already in flight")
            continue
        }

        TrackedAppUpdateState.recordAttemptStarted(context, key)
        // Shown for the whole download+install span, not just the install step - cancelled by
        // AppInstallReceiver once the real PackageInstaller result comes back, or explicitly here
        // on a download failure (AppInstallReceiver never runs in that case, since installSilently
        // is never reached).
        notifyAppInstalling(context, update.id, update.name)
        try {
            val response = api.downloadTrackedApp(update.downloadUrl)
            val body = response.body()
            if (body == null) {
                notifyAppInstallResult(context, update.id, update.name, success = false)
                TrackedAppUpdateState.clearAttempt(context, key)
                reportInstallFailure(api, update.id)
                continue
            }
            // Unique per attempt (not just per app id) - defense in depth alongside the syncMutex
            // above: even a future caller that bypasses the mutex can't have two downloads corrupt
            // or delete each other's file if they never share a path. context.cacheDir is
            // OS-reclaimable under storage pressure, so a launcher self-update's file (deliberately
            // left behind on success - see AppInstallReceiver) doesn't need explicit cleanup here.
            val apkFile = File(context.cacheDir, "tracked_app_${key}_${System.nanoTime()}.apk")
            copyWithProgressReports(body, apkFile, api, update.id)
            Log.i(LOG_TAG, "Downloaded ${update.packageName} ${update.releaseTag}, installing")
            AppInstaller.installSilently(
                context, apkFile, key, update.name, update.isLauncher, update.releaseTag
            )
            // Deliberately does NOT clear the in-flight marker here - installSilently's commit()
            // is async, so this attempt is still unresolved until AppInstallReceiver's
            // recordInstalled/recordFailed lands (or the timeout above reclaims it if that
            // callback never fires).
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Update check failed for ${update.packageName}", e)
            notifyAppInstallResult(context, update.id, update.name, success = false)
            TrackedAppUpdateState.clearAttempt(context, key)
            reportInstallFailure(api, update.id)
        }
    }
}

/** Best-effort visibility for the admin site - a download-level failure here doesn't mark
 * [TrackedAppUpdateState.recordFailed] (a transient network hiccup should still retry next cycle,
 * see [TrackedAppUpdateState.clearAttempt]'s own doc comment), so this alone is what lets the
 * server show "Install failed" instead of the row just quietly staying "Not installed". */
private suspend fun reportInstallFailure(api: MdmApi, trackedAppId: Long) {
    try {
        api.reportInstallProgress(InstallProgressReport(trackedAppId, percent = 0, failed = true))
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Failed to report install failure", e)
    }
}

/** Copies [body]'s bytes to [apkFile] while reporting download progress to the server on every
 * 5% crossing (not every chunk - a large APK over a slow connection could otherwise fire dozens
 * of requests a second). Best-effort: a failed progress report is logged and ignored, never
 * allowed to interrupt the actual download it's reporting on. */
private suspend fun copyWithProgressReports(
    body: ResponseBody,
    apkFile: File,
    api: MdmApi,
    trackedAppId: Long,
) {
    val contentLength = body.contentLength()
    var lastReportedPercent = -1
    body.byteStream().use { input ->
        apkFile.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                bytesRead += read
                if (contentLength <= 0) continue
                val percent = ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                if (percent >= lastReportedPercent + 5) {
                    lastReportedPercent = percent
                    try {
                        api.reportInstallProgress(InstallProgressReport(trackedAppId, percent))
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to report install progress", e)
                    }
                }
            }
        }
    }
}

/**
 * Reports {packageName, label} for every app [AppEnforcer] is actually willing to suspend/hide,
 * so the admin site's allowlist checkboxes exactly match what checking one of them can affect -
 * see [controllablePackages] for why this is neither the launcher's own `Application.apps` list
 * (excludes already-hidden apps, a permanent lockout) nor a raw unfiltered PackageManager query
 * (would include core OS packages unsafe to ever suspend).
 */
private fun collectInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return controllablePackages(pm)
        .filter { it != context.packageName }
        .mapNotNull { packageName ->
            try {
                // Same MATCH_UNINSTALLED_PACKAGES requirement as controllablePackages() - flags=0
                // throws NameNotFoundException for a hidden package just like it gets silently
                // excluded from getInstalledApplications(0), which would otherwise drop any
                // currently-unchecked app right back out of this report.
                val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                InstalledApp(
                    packageName = packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    preinstalled = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        .distinctBy { it.packageName }
}

/** A 5xx from the policy endpoint: the server is up but couldn't build this device's policy
 * (kid-phone-server answers 500 rather than a default) - reported as `server_error`. */
private val POLICY_SERVER_ERROR = FreshDecode.Failed("server error")

/** `null` when the server couldn't be reached (or answered a non-5xx error), [POLICY_SERVER_ERROR]
 * (compared by identity) on a 5xx, otherwise the decoded body or why it didn't decode. Doesn't
 * cache anything - only an accepted policy is cached, see [storeAcceptedPolicy]. */
private suspend fun fetchPolicy(api: MdmApi): FreshDecode? {
    return try {
        val response = api.getPolicy()
        if (!response.isSuccessful) {
            Log.w(LOG_TAG, "Policy fetch returned HTTP ${response.code()}, keeping the current policy")
            response.errorBody()?.close()
            return if (response.code() >= 500) POLICY_SERVER_ERROR else null
        }
        decodeFresh(response.body()?.use { it.string() })
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Policy fetch failed, falling back to cache", e)
        null
    }
}

/** Caches an accepted policy, its [LastEnforcedPlan], the "a policy has been applied" flag and the
 * call prefs ([callPrefsUpdate]: `calls_managed_last` only from an explicit `managed`, and the last
 * managed call rules) in one synchronous `commit()` - they must never disagree, and the generated
 * preference setters only `apply()` asynchronously (see CLAUDE.md on writes racing a process death). */
private fun storeAcceptedPolicy(context: Context, policy: PolicyResponse) {
    val keys = LauncherPreferences.mdm().keys()
    val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(keys.kidModePolicy(), ServerJson.encodeToString(PolicyResponse.serializer(), policy))
        .putBoolean(keys.policyEverApplied(), true)
        .putString(keys.lastEnforcedPlan(), LastEnforcedPlan.encode(LastEnforcedPlan.of(policy)))
    callPrefsUpdate(policy)?.let { update ->
        editor.putBoolean(keys.callsManagedLast(), update.callsManagedLast)
        if (update.lastCallRules != null) {
            editor.putString(keys.lastCallRules(), update.lastCallRules)
        } else {
            editor.remove(keys.lastCallRules())
        }
    }
    val ok = editor.commit()
    if (!ok) Log.w(LOG_TAG, "Failed to cache the accepted policy")
}

/**
 * The last accepted policy, straight from the local cache - no network call. Feed it to
 * [choosePolicy] (or use [currentPolicyDecision]): a [CachedPolicy.Corrupt] (or
 * [CachedPolicy.Absent] on a phone that has had a policy) means "enforce the last-enforced plan",
 * never "no restrictions".
 */
fun cachedPolicy(): CachedPolicy {
    val cached = decodeCached(LauncherPreferences.mdm().kidModePolicy())
    if (cached is CachedPolicy.Corrupt) {
        Log.w(LOG_TAG, "Cached policy doesn't decode: ${cached.error}")
    }
    return cached
}

/** The [LastEnforcedPlan] stored with the last accepted policy, or `null` if missing/unreadable. */
fun lastEnforcedPlan(): LastEnforcedPlan? = LastEnforcedPlan.decode(LauncherPreferences.mdm().lastEnforcedPlan())

/** What to enforce right now without a network call: the cache, the last-enforced plan, or
 * (never-managed phone) nothing. */
fun currentPolicyDecision(): PolicyToApply =
    choosePolicy(null, cachedPolicy(), LauncherPreferences.mdm().policyEverApplied(), lastEnforcedPlan())

/** Re-applies enforcement off the main thread after the schedule lock changed - see
 * [reevaluateLockReasonFromCache]. */
private val scheduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * Re-checks the bedtime/screen-time lock decision against the last-cached policy and the
 * device's own clock - no network call, so it works offline and doesn't wait for the next sync.
 * The home screen and lock screen call this on a local timer while visible, and
 * [CommandListenerService] every minute and on screen-on, so the schedule engages and releases
 * promptly on both edges, not just whenever a sync happens to land. When the decision changes,
 * [AppEnforcer.apply] runs again in the background: the lock suspends every app but ours and the
 * system dialer, and its end releases them (qa-security P0 #3). Returns the new reason if it
 * changed, `null` otherwise.
 */
fun reevaluateLockReasonFromCache(context: Context): LockReason? {
    val mdm = LauncherPreferences.mdm()
    val decision = currentPolicyDecision()
    val reason = KidModeEnforcer.lockReasonNow(
        decision.policy,
        OfflineOverride.isActive() || RestrictionsPause.isActive(),
        Calendar.getInstance(),
    )
    if (mdm.lockReason() == reason) return null
    mdm.lockReason(reason)
    val appContext = context.applicationContext
    scheduleScope.launch {
        try {
            AppEnforcer.apply(appContext, currentPolicyDecision().policy)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Re-applying enforcement after a schedule change failed", e)
        }
    }
    return reason
}

// The periodic backstop sync used to be driven by a WorkManager OneTimeWorkRequest chain (each
// run rescheduling the next with a 5-minute delay) - confirmed live that this could go
// unexpectedly quiet for hours on an idle phone with the screen off, most likely Android's Doze/
// battery-optimization deferring the underlying JobScheduler dispatch, which WorkManager itself
// isn't exempt from. CommandListenerService already pays the cost of an always-on foreground
// service (exempt from Doze by design, that's the entire point of a foreground service) to hold
// its SSE connection open - it now also drives this periodic sync directly off its own timer
// instead, so there's no second, Doze-vulnerable scheduling mechanism to keep reliable.
