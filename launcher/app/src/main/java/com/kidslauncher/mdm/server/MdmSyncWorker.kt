package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.badges.BadgeStore
import com.kidslauncher.mdm.calls.ContactPhotos
import com.kidslauncher.mdm.ui.LauncherLocales
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
import com.kidslauncher.mdm.server.dto.TrackedAppUpdate
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.server.dto.LocationPolicy
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.key
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.play.PLAY_POLICY_CAPABILITY
import com.kidslauncher.mdm.play.catalogUpdateBlockedByPlay
import com.kidslauncher.mdm.push.FCM_PUSH_CAPABILITY
import com.kidslauncher.mdm.push.FcmSupport
import com.kidslauncher.mdm.push.PushState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

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
        is FreshDecode.Ok -> when (judgeFresh(fetched.policy, cached, policyEverApplied, mdm.callsManagedLast(), mdm.timePolicySeen())) {
            FreshVerdict.REJECT_SUSPECT -> {
                Log.w(LOG_TAG, "Ignoring a server policy without an allowlist, call_policy or time_policy on a managed phone")
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
        // The parent's language choice, switched when Home is next in front (LauncherLocales).
        LauncherLocales.remember(context, freshPolicy.launcherUi)
        // The parent's app names and icons (design 14): a change re-renders Home and the drawer.
        com.kidslauncher.mdm.appdisplay.AppDisplay.refresh(context)
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
        // The blocked-domain log is a per-phone opt-in (off by default, cleanup 2026-10-06): off
        // also drops whatever is still queued.
        BlockedEventLog.setEnabled(context, freshPolicy.dnsLogEnabled)
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
    val lock = TimeRulesRuntime.currentLock(context, decision.policy, overrideActive)
    val reason = lock.reason
    val previousReason = mdm.lockReason()
    mdm.lockReason(reason)
    mdm.lockKey(lock.key())
    // Derives the same lock from the same policy and suspends apps while it's on.
    AppEnforcer.apply(context, decision.policy, fromSync = true)
    // A lock that began with this policy (a lift ended early, a new rule): show it now, also over
    // an app (not over a call), then re-arm the boundary alarm and the screen-time timer.
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).post {
        if (previousReason == LockReason.NONE && reason != LockReason.NONE && !OngoingCalls.hasLiveCall) {
            LockActivity.start(appContext)
        }
        TimeRulesRuntime.recheck(appContext)
    }

    // A `ring`/`locate` command means the admin explicitly wants to know where the device is right
    // now, worth the cost of an active GPS/network fix - every other sync (the background chain,
    // push-triggered syncs, and manual "Sync now") follows the parent's location policy instead
    // (off / on request / every N minutes, see locationAction), so location isn't forcing an
    // active fetch (visible location indicator, slower sync) on every single cycle.
    val forceFreshLocation = freshPolicy?.pendingCommand?.command in setOf("ring", "locate")
    val locationPolicy = decision.policy?.let { it.locationPolicy ?: LEGACY_LOCATION_POLICY }
    val location = currentLocationReport(context, dpm, admin, forceFreshLocation, locationPolicy)

    // Catalog downloads (design 13): the list and the queue before the report, so it carries the
    // queue; the downloads themselves run outside the sync (AppDownloads). The switch is the
    // enforced policy's - the override and the pause don't lift it (it guards data, not the kid).
    AppDownloads.wifiOnly = decision.policy?.appUpdatesWifiOnly == true
    checkForTrackedAppUpdates(context, api)

    // FCM (handy step 7): get or renew the token so this report carries it, and decide the
    // transport from the enforced policy's `push` (the anchor service follows it after the sync).
    try {
        FcmSupport.maintainToken(context, decision.policy?.push)
        FcmSupport.decide(context, decision.policy?.push)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Push upkeep failed", e)
    }

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
                location = location.report,
                policyState = policyState(freshOutcome, cached, policyEverApplied),
                restrictionsPaused = RestrictionsPause.isActive(),
                capabilities = listOf(
                    CALL_POLICY_CAPABILITY, TIME_RULES_CAPABILITY, FCM_PUSH_CAPABILITY, PLAY_POLICY_CAPABILITY,
                    com.kidslauncher.mdm.lock.PIN_LOCK_CAPABILITY, KIOSK_ESCAPES_CAPABILITY,
                    com.kidslauncher.mdm.lock.BOOT_COVER_CAPABILITY,
                ),
                callState = CallStateReport.build(context),
                notificationListenerEnabled = BadgeStore.accessGranted(context),
                timeState = TimeRulesRuntime.report(context, decision.policy, reason),
                push = PushState.report(context, FcmSupport.configured, FcmSupport.gmsAvailable(context)),
                installMode = PlayRuntime.installModeReport(context),
                playWindowActive = decision.policy?.allowlist != null && PlayRuntime.updateWindowActive(context),
                playStoreSuspendable = PlayRuntime.storeSuspendable(context),
                lockState = com.kidslauncher.mdm.lock.PinLockRuntime.report(context),
                screenTimeoutSeconds = ScreenTimeout.currentSeconds(context),
                backupServiceEnabled = BackupService.reportedState(context),
                ringerMode = ringerModeName(QuickControls.ringerMode(context)),
                interruptionFilter = interruptionFilterName(QuickControls.interruptionFilter(context)),
                updateFence = UpdateFence.report(context, decision.policy),
                notificationCancels = com.kidslauncher.mdm.badges.NotificationRuleRuntime.report(),
                appDownloads = try {
                    AppDownloads.report(context)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Download report failed", e)
                    null
                },
                bootCover = try {
                    com.kidslauncher.mdm.lock.BootCover.report(context)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Boot cover report failed", e)
                    null
                },
            )
        )
        // The report just landed, so this doesn't need to stay pending - if it was never used,
        // this is a harmless false->false write.
        mdm.offlineOverrideUsedPendingReport(false)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Status report failed", e)
    }

    // `locate` is answered after the report that carries the fix, with the fix's accuracy and age.
    freshPolicy?.pendingCommand?.takeIf { it.command == "locate" }?.let { command ->
        val (ok, message) = locateResultMessage(location.action, location.accuracyMeters, location.ageSeconds)
        reportCommandResult(api, command.id, ok, message)
    }

    // Crashes since the last sync (hash + short trace, no personal data) for the device page.
    com.kidslauncher.mdm.crash.CrashReports.upload(context, api)
    // Retention (cleanup 2026-10-06): blocked calls older than 30 days leave the call log.
    com.kidslauncher.mdm.calls.BlockedCallLog.pruneIfDue(context)

    // After enforcement and the report: photos are cosmetic and may take a moment to download.
    if (freshPolicy != null) ContactPhotos.sync(context, api)
    // Wallpapers likewise (design 08): download the allowed photos, delete the rest, and put the
    // shown one on the system wallpaper right away if it changed. Cosmetic - never fails a sync.
    if (freshPolicy != null) {
        try {
            com.kidslauncher.mdm.ui.wallpaper.WallpaperStore.sync(context, api)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MdmSyncWorker", "Wallpaper sync failed", e)
        }
    }

    reportBlockedDnsEvents(context, api)
    // Last of all (handy step 11): committing our own update gets this process killed seconds
    // later. Offline too - the APK was downloaded and checked when the server advertised it.
    commitPendingSelfUpdateIfDue(context, api)

    return freshPolicy != null
}

/** Status-report capability: the update fence, the pending self-update and the notification rule
 * (handy step 11). */
const val KIOSK_ESCAPES_CAPABILITY = "kiosk_escapes_v1"

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
 * Find My Device's remote-command dispatch - ring/stop_ring/lock/wipe, or `locate` (nothing here:
 * the sync takes a fresh fix for the status report - bypassing the throttle - and answers the
 * command after the report with the fix's accuracy and age; the PWA's "Update location now"
 * queues it). No result is ever reported for `wipe` - the device is gone by the time it would
 * report back.
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
            // Handy's PIN lock when it's active (step 10): LOCKED and shown, then the screen off.
            val ours = com.kidslauncher.mdm.lock.PinLockRuntime.lockNow(context)
            val ok = LocateCommands.lock(dpm, admin)
            val message = when {
                !ok -> "failed to lock"
                ours -> "locked (handy lock)"
                else -> "locked (Android)"
            }
            reportCommandResult(api, pending.id, ok, message)
        }

        "wipe" -> LocateCommands.wipe(dpm, admin)

        // Answered after the status report that carries the fresh fix (performMdmSync).
        "locate" -> {}

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

/** What the sync did about location: the report (if any), and for `locate` the fix's details. */
private class LocationOutcome(
    val action: LocationAction,
    val report: LocationReport?,
    val accuracyMeters: Float?,
    val ageSeconds: Long?,
)

/** [policy] `null` = no policy (a never-managed phone): the old behaviour. */
private suspend fun currentLocationReport(
    context: Context,
    dpm: DevicePolicyManager,
    admin: ComponentName,
    forceFresh: Boolean,
    policy: LocationPolicy?,
): LocationOutcome {
    if (!dpm.isDeviceOwnerApp(context.packageName)) return LocationOutcome(LocationAction.NONE, null, null, null)
    val sinceLastFresh = System.currentTimeMillis() - LauncherPreferences.mdm().lastActiveLocationFetchAtMs()
    val action = locationAction(policy, forceFresh, sinceLastFresh)
    val location = LocateCommands.currentLocation(context, dpm, admin, action)
        ?: return LocationOutcome(action, null, null, null)
    val accuracy = if (location.hasAccuracy()) location.accuracy else null
    return LocationOutcome(
        action,
        LocationReport(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = accuracy,
            capturedAt = Instant.ofEpochMilli(location.time).toString(),
        ),
        accuracy,
        (System.currentTimeMillis() - location.time) / 1000,
    )
}

/**
 * The list of apps tracked server-side (see kid-phone-server's `handlers::tracked_apps`) that are
 * scoped to this device (or the launcher itself, see [TrackedAppUpdate.isLauncher] - always
 * included regardless of scoping), turned into the download queue (design 13 §4): a release that
 * isn't installed, isn't backing off after a failure, isn't installed by Play and isn't between
 * commit and result is wanted; [AppDownloads.reconcile] makes the records exactly that and the
 * runner downloads them outside the sync - Wi-Fi only when the parent says so, never roaming,
 * resumable - and installs them (a catalog app) or keeps our own update pending
 * ([handleLauncherUpdate]; [commitPendingSelfUpdateIfDue], the sync's very last step, commits it
 * in the update window). Without a list (a failed fetch) only installed releases' records and
 * files without a record are swept (QA #4). One app's state never affects another's, or the rest
 * of the sync.
 */
// Generous for a slow install, short enough that an abandoned attempt (process died mid-install,
// AppInstallReceiver's callback somehow never fired) doesn't block retries for long - see
// TrackedAppUpdateState.recordAttemptStarted's own doc comment for the actual bug this guards
// against. Since design 13 the attempt covers only commit -> AppInstallReceiver (the download is
// AppDownloads' record).
internal const val INSTALL_ATTEMPT_TIMEOUT_MS = 10 * 60 * 1000L

// A release that failed to install once is retried automatically after this window rather than
// being skipped forever - see TrackedAppUpdateState's own doc comment for the incident that
// motivated this (a release stuck failed from the since-fixed overlapping-install race showed zero
// notification and zero server-side visibility indefinitely, since nothing ever cleared
// lastFailedTag short of the upstream release itself changing).
private const val FAILED_RETRY_BACKOFF_MS = 60 * 60 * 1000L

private suspend fun checkForTrackedAppUpdates(context: Context, api: MdmApi) {
    val updates = try {
        api.getTrackedAppUpdates().body()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Tracked app update check failed", e)
        null
    }
    if (updates == null) {
        // No list: nothing is dropped for not being advertised (QA #4); waiting downloads go on.
        try {
            AppDownloads.sweepWithoutList(context)
            AppDownloads.request(context, "sync")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Download sweep failed", e)
        }
        return
    }

    val (launcherUpdates, otherUpdates) = updates.partition { it.isLauncher }
    val state = TrackedAppUpdateState.load()
    val wanted = mutableListOf<WantedDownload>()
    val now = System.currentTimeMillis()
    for (update in otherUpdates) {
        val key = update.id.toString()
        // One source per package (handy step 7): an app Play installed is Play's to update
        // (other signature; Android 14 update ownership). Switching source = uninstall first.
        val installedFrom = update.packageName.takeIf { it.isNotBlank() }
            ?.let { installerOf(context.packageManager, it) }
        if (catalogUpdateBlockedByPlay(installedFrom)) {
            Log.w(LOG_TAG, "Skipping ${update.name}: the installed copy comes from Play")
            reportInstallFailure(api, update.id)
            continue
        }
        val known = state[key]
        if (update.releaseTag == known?.lastInstalledTag) {
            continue
        }
        val failedAt = known?.lastFailedAtMs
        if (update.releaseTag == known?.lastFailedTag &&
            failedAt != null &&
            now - failedAt < FAILED_RETRY_BACKOFF_MS
        ) {
            continue
        }
        val attemptStartedAt = known?.attemptStartedAtMs
        if (attemptStartedAt != null && now - attemptStartedAt < INSTALL_ATTEMPT_TIMEOUT_MS) {
            // Committed, waiting for AppInstallReceiver's result - don't fire a second, overlapping
            // attempt for the same target. Confirmed live: checking a second app while the first
            // was still installing caused the first to restart from this exact redundant
            // re-attempt, and the second app's own attempt never completed.
            Log.i(LOG_TAG, "Skipping ${update.name} - an install attempt is already in flight")
            continue
        }
        wanted += update.wanted()
    }
    // The launcher's own update: downloaded by the runner too, committed only in the update
    // window (step 11).
    handleLauncherUpdate(context, launcherUpdates.firstOrNull())?.let { wanted += it.wanted() }
    try {
        AppDownloads.reconcile(context, wanted)
        AppDownloads.request(context, "sync")
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Queueing the downloads failed", e)
    }
}

private fun TrackedAppUpdate.wanted() = WantedDownload(
    appId = id,
    tag = releaseTag,
    // The install notification says what the kid sees: the parent's name when there is one (design 14).
    name = com.kidslauncher.mdm.appdisplay.AppDisplay.label(packageName.takeIf { it.isNotBlank() }) ?: name,
    isLauncher = isLauncher,
    downloadUrl = downloadUrl,
    sha256 = sha256,
)

/**
 * The launcher's own row (handy step 11, [launcherUpdateStep]): the APK is downloaded once (by
 * [AppDownloads], design 13) into `noBackupFilesDir` with its SHA-256 and kept as
 * [PendingSelfUpdate] until [commitPendingSelfUpdateIfDue] finds the update window - not
 * re-fetched every sync, replaced by a newer tag, dropped when the release is withdrawn or
 * installed. [update] `null` = the list arrived without the launcher. Returns the release to
 * download, if any.
 */
private fun handleLauncherUpdate(context: Context, update: TrackedAppUpdate?): TrackedAppUpdate? {
    val state = TrackedAppUpdateState.load()
    val pendingEntry = TrackedAppUpdateState.pendingEntry()
    val key = update?.id?.toString() ?: pendingEntry?.first ?: return null
    val known = state[key]
    // A pending APK on another row (the server's launcher row changed) is stale.
    val pending = pendingEntry?.second?.takeIf { pendingEntry.first == key }
    if (pendingEntry != null && pendingEntry.first != key) SelfUpdate.dropPending(context)
    val now = System.currentTimeMillis()
    val attemptStartedAt = known?.attemptStartedAtMs
    // The server's launcher row names another package (a debug build on a dev phone, the release
    // APK on the server): never ours to install, never downloaded.
    val rowPackage = update?.packageName?.takeIf { it.isNotBlank() }
    if (rowPackage != null && rowPackage != context.packageName) {
        Log.i(LOG_TAG, "The launcher row is $rowPackage, this build is ${context.packageName} - not installing it")
        if (pending != null) SelfUpdate.dropPending(context)
        return null
    }
    val step = launcherUpdateStep(
        refusedTag = known?.refusedTag,
        advertisedTag = update?.releaseTag,
        lastInstalledTag = known?.lastInstalledTag,
        lastFailedTag = known?.lastFailedTag,
        lastFailedAtMs = known?.lastFailedAtMs,
        attemptInFlight = attemptStartedAt != null && now - attemptStartedAt < INSTALL_ATTEMPT_TIMEOUT_MS,
        pending = pending,
        pendingFileOk = SelfUpdate.fileOk(context, pending),
        nowMs = now,
        failedBackoffMs = FAILED_RETRY_BACKOFF_MS,
    )
    return when (step) {
        LauncherUpdateStep.NOTHING, LauncherUpdateStep.KEEP_PENDING -> null
        LauncherUpdateStep.DROP_PENDING -> {
            Log.i(LOG_TAG, "Dropping the pending launcher update ${pending?.releaseTag} (installed or withdrawn)")
            SelfUpdate.dropPending(context)
            null
        }
        LauncherUpdateStep.REPLACE_PENDING -> {
            Log.i(LOG_TAG, "Replacing the pending launcher update ${pending?.releaseTag} with ${update?.releaseTag}")
            SelfUpdate.dropPending(context)
            update
        }
        LauncherUpdateStep.DOWNLOAD -> update
    }
}

/**
 * Commits the pending launcher update when the window gate passes ([updateWindowDecision]: night,
 * screen off 30 s, no call, no emergency - or overdue), after checking the file's size and SHA-256
 * and that it is our package and newer ([pendingApkCheck]); the update fence goes up right before
 * `commit()` ([UpdateFence.fenceBeforeCommit]). Inside `performMdmSync`, so under [syncMutex].
 */
private suspend fun commitPendingSelfUpdateIfDue(context: Context, api: MdmApi) {
    val (key, pending) = TrackedAppUpdateState.pendingEntry() ?: return
    val known = TrackedAppUpdateState.load()[key]
    val now = System.currentTimeMillis()
    val attemptStartedAt = known?.attemptStartedAtMs
    if (attemptStartedAt != null && now - attemptStartedAt < INSTALL_ATTEMPT_TIMEOUT_MS) return
    // A transient failure of this release keeps its APK and waits out the backoff; a refused one
    // is never committed (qa-11-code #4).
    if (!pendingCommitAllowed(pending.releaseTag, known?.lastFailedTag, known?.lastFailedAtMs, known?.refusedTag, now, FAILED_RETRY_BACKOFF_MS)) {
        if (known?.refusedTag == pending.releaseTag) SelfUpdate.dropPending(context)
        return
    }
    val decision = updateWindowDecision(SelfUpdate.windowInputs(context, pending))
    if (decision is UpdateWindowDecision.Wait) {
        waitForWindow(context, pending, decision)
        return
    }
    SelfUpdate.lastWait = null
    val file = SelfUpdate.file(context, pending)
    val archive = try {
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } catch (e: Exception) {
        null
    }
    val check = pendingApkCheck(
        pending = pending,
        fileSize = file.takeIf { it.isFile }?.length(),
        fileSha256 = try {
            file.takeIf { it.isFile }?.let { SelfUpdate.sha256(it) }
        } catch (e: Exception) {
            null
        },
        archivePackage = archive?.packageName,
        archiveVersionCode = archive?.longVersionCode,
        ownPackage = context.packageName,
        ownVersionCode = BuildConfig.VERSION_CODE.toLong(),
        ourSigners = SelfUpdate.ourSigners(context),
        archiveSigners = SelfUpdate.signers(archive?.signingInfo),
    )
    val appId = key.toLongOrNull()
    when {
        check == PendingApkCheck.OK -> {}
        check == PendingApkCheck.MISSING || check == PendingApkCheck.CORRUPT -> {
            Log.w(LOG_TAG, "Pending launcher ${pending.releaseTag}: ${check.name} - downloading again at the next sync")
            SelfUpdate.dropPending(context)
            return
        }
        check == PendingApkCheck.SAME_VERSION -> {
            Log.i(LOG_TAG, "Pending launcher ${pending.releaseTag} is the running version - nothing to install")
            TrackedAppUpdateState.recordInstalled(context, key, pending.releaseTag)
            SelfUpdate.dropPending(context)
            return
        }
        check.deterministic -> {
            // The same APK would be refused again: never fenced, downloaded or tried again until
            // the server advertises another release (qa-11-code #4).
            Log.w(LOG_TAG, "Pending launcher ${pending.releaseTag} refused before installing: ${check.name}")
            SelfUpdate.dropPending(context)
            TrackedAppUpdateState.recordRefused(context, key, pending.releaseTag)
            appId?.let {
                notifyAppInstallResult(context, it, pending.name, success = false)
                reportInstallFailure(api, it)
            }
            return
        }
    }
    Log.i(LOG_TAG, "Committing launcher ${pending.releaseTag} (update window)")
    TrackedAppUpdateState.recordAttemptStarted(context, key)
    appId?.let { notifyAppInstalling(context, it, pending.name) }
    // Waits for a catalog install's session copy in progress; from here on the download runner
    // opens no session in this process (design 13 QA #3).
    val started = AppDownloads.installMutex.withLock {
        SelfUpdate.committedInThisProcess = true
        AppInstaller.installSilently(
        context, file, key, pending.name, isLauncher = true, releaseTag = pending.releaseTag,
        beforeCommit = { sessionId ->
            // The archive parse, the hash and the copy into the session took seconds: a wake or a
            // call in between cancels the commit (qa-11-code #2) - only then the fence goes up.
            val again = updateWindowDecision(SelfUpdate.windowInputs(context, pending))
            if (again is UpdateWindowDecision.Wait) {
                waitForWindow(context, pending, again)
                false
            } else {
                UpdateFence.fenceBeforeCommit(context, sessionId, pending.releaseTag)
                true
            }
        },
        commitFailed = { UpdateFence.commitFailed(context) },
        deleteOnFailure = false,
        )
    }
    when (started) {
        InstallStart.COMMITTED -> {}
        InstallStart.DEFERRED -> {
            // Nothing happened: the APK stays pending, no backoff.
            SelfUpdate.committedInThisProcess = false
            TrackedAppUpdateState.clearAttempt(context, key)
            appId?.let { notifyAppInstallResult(context, it, pending.name, success = true) }
        }
        InstallStart.FAILED -> {
            // Couldn't start (no space for the session copy, commit() threw): keep the verified APK
            // and back off an hour (qa-11-code #4) - no new download.
            SelfUpdate.committedInThisProcess = false
            TrackedAppUpdateState.recordFailed(context, key, pending.releaseTag, keepPending = true)
            appId?.let { notifyAppInstallResult(context, it, pending.name, success = false) }
        }
    }
}

/** The gate said wait: remember why (status report) and, with the screen off, look again when it
 * could pass. */
private fun waitForWindow(context: Context, pending: PendingSelfUpdate, decision: UpdateWindowDecision.Wait) {
    SelfUpdate.lastWait = decision.reason
    Log.i(LOG_TAG, "Launcher ${pending.releaseTag} waits: ${decision.reason.wire}")
    val recheck = decision.recheckInMs
    if (recheck != null && SelfUpdate.screenOffForMs(context) != null) SelfUpdate.armAlarm(context, recheck)
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
                    installer = installerOf(pm, packageName),
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        .distinctBy { it.packageName }
}

/** Who installed [packageName] (`com.android.vending` = Play), or `null` if unknown. */
private fun installerOf(pm: PackageManager, packageName: String): String? = try {
    pm.getInstallSourceInfo(packageName).installingPackageName
} catch (e: Exception) {
    null
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

/** Caches an accepted policy, its [LastEnforcedPlan], the "a policy has been applied" flag, the
 * "this phone has had time rules" flag ([judgeFresh]) and the
 * call prefs ([callPrefsUpdate]: `calls_managed_last` only from an explicit `managed`, and the last
 * managed call rules) in one synchronous `commit()` - they must never disagree, and the generated
 * preference setters only `apply()` asynchronously (see CLAUDE.md on writes racing a process death). */
private fun storeAcceptedPolicy(context: Context, policy: PolicyResponse) {
    val keys = LauncherPreferences.mdm().keys()
    val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(keys.kidModePolicy(), ServerJson.encodeToString(PolicyResponse.serializer(), policy))
        .putBoolean(keys.policyEverApplied(), true)
        .putString(keys.lastEnforcedPlan(), LastEnforcedPlan.encode(LastEnforcedPlan.of(policy)))
    if (policy.timePolicy != null) editor.putBoolean(keys.timePolicySeen(), true)
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
    val raw = LauncherPreferences.mdm().kidModePolicy()
    // One decode per cached string (design 16c): the start-up path - the time-rule re-check, the
    // screen-time timer, the boundary alarm, Home - decoded the same JSON several times on the main
    // thread before the lock was up. The DTOs are immutable, so the copy is shared.
    cachedPolicyMemo?.let { memo -> if (memo.raw == raw) return memo.decoded }
    val cached = decodeCached(raw)
    if (cached is CachedPolicy.Corrupt) {
        Log.w(LOG_TAG, "Cached policy doesn't decode: ${cached.error}")
    }
    cachedPolicyMemo = CachedPolicyMemo(raw, cached)
    return cached
}

private class CachedPolicyMemo(val raw: String?, val decoded: CachedPolicy)

@Volatile
private var cachedPolicyMemo: CachedPolicyMemo? = null

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
 * Re-checks the time-rule lock against the last-cached policy and the device's own clock - no
 * network call, so it works offline and doesn't wait for the next sync. Called by
 * [com.kidslauncher.mdm.timerules.TimeRulesRuntime.recheck] (the boundary alarm, time/zone change,
 * boot, screen on, the budget running out) and by Home/the lock screen when they come to the
 * front. When the lock changed - its reason, rules, usable apps or calls ([key]) - or the last
 * apply didn't enforce it (failed, process died, none ran yet: QA step 4 #10), [AppEnforcer.apply]
 * runs again in the background: the lock suspends every app but ours, the system dialer and the
 * lock's usable apps, and its end releases them (qa-security P0 #3). Returns the new reason if it
 * changed, `null` otherwise.
 */
fun reevaluateLockReasonFromCache(context: Context): LockReason? {
    val mdm = LauncherPreferences.mdm()
    val decision = currentPolicyDecision()
    val lock = TimeRulesRuntime.currentLock(
        context,
        decision.policy,
        OfflineOverride.isActive() || RestrictionsPause.isActive(),
    )
    val reason = lock.reason
    val key = lock.key()
    val changed = mdm.lockReason() != reason
    val keyChanged = mdm.lockKey() != key
    // Play (handy step 7): install mode starting/ending, the update window opening at screen-off
    // or closing at screen-on re-apply the plan the same way.
    val play = PlayRuntime.state(context)
    val enforcedPlay = AppEnforcer.lastEnforcedPlayState
    val playChanged = enforcedPlay?.key() != play.key()
    if (!changed && !keyChanged && AppEnforcer.lastEnforcedLockKey == key && !playChanged) return null
    if (changed) mdm.lockReason(reason)
    if (keyChanged) mdm.lockKey(key)
    val installModeEnded = enforcedPlay?.installMode == true && !play.installMode
    val appContext = context.applicationContext
    scheduleScope.launch {
        try {
            AppEnforcer.apply(appContext, currentPolicyDecision().policy)
            // The Play state may have moved while that apply ran (screen on right after screen
            // off in the update window): apply again until what's enforced is what's current,
            // so the window really ends at once on screen-on.
            var tries = 0
            while (tries++ < 3 && AppEnforcer.lastEnforcedPlayState?.key() != PlayRuntime.state(appContext).key()) {
                AppEnforcer.apply(appContext, currentPolicyDecision().policy)
            }
            if (installModeEnded) PlayRuntime.onInstallModeEnded(appContext)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Re-applying enforcement after a lock change failed", e)
        }
    }
    return if (changed) reason else null
}

// The periodic backstop sync used to be driven by a WorkManager OneTimeWorkRequest chain (each
// run rescheduling the next with a 5-minute delay) - confirmed live that this could go
// unexpectedly quiet for hours on an idle phone with the screen off, most likely Android's Doze/
// battery-optimization deferring the underlying JobScheduler dispatch, which WorkManager itself
// isn't exempt from. CommandListenerService already pays the cost of an always-on foreground
// service (exempt from Doze by design, that's the entire point of a foreground service) to hold
// its SSE connection open - it now also drives this periodic sync directly off its own timer
// instead, so there's no second, Doze-vulnerable scheduling mechanism to keep reliable.
