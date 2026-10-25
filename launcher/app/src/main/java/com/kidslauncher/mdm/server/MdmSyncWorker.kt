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
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallStateReport
import com.kidslauncher.mdm.calls.callPrefsUpdate
import com.kidslauncher.mdm.notifyAppInstallResult
import com.kidslauncher.mdm.notifyAppInstalling
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.KidLock
import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.server.dto.PendingCommand
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.TrackedAppUpdate
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.server.dto.LocationPolicy
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.zones.FreshKind
import com.kidslauncher.mdm.zones.Heartbeat
import com.kidslauncher.mdm.zones.MotionGate
import com.kidslauncher.mdm.zones.QueuedFix
import com.kidslauncher.mdm.zones.ZoneWatch
import com.kidslauncher.mdm.zones.extraLocations
import com.kidslauncher.mdm.zones.Motion
import com.kidslauncher.mdm.zones.freshOwnFix
import com.kidslauncher.mdm.zones.heartbeatDue
import com.kidslauncher.mdm.zones.reportStill
import com.kidslauncher.mdm.zones.syncLocationAction
import com.kidslauncher.mdm.zones.toQueued
import com.kidslauncher.mdm.zones.toReport
import com.kidslauncher.mdm.timerules.key
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.play.catalogUpdateBlockedByPlay
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val deviceToken = DeviceToken.get()
    if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
        return false
    }

    // The embedded tailnet connection (see CLAUDE.md) is kicked off eagerly from
    // Application.onCreate() - this is a retry-until-connected backstop for whenever that hasn't
    // succeeded yet (no auth key configured at startup, transient failure, ...), so createMdmApi
    // below can pick up TsnetClient's SOCKS5 proxy. No-ops if already connected or no key is set.
    TsnetClient.connectFromPreferences(context)
    // Design 38 (QA N1): the shutdown's prepared client follows the base URL, the token and the proxy.
    PowerEvents.prepare(context)

    val api = createMdmApi(serverUrl, deviceToken)
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    val cached = cachedPolicy()
    val policyEverApplied = mdm.policyEverApplied()

    // Only a fresh policy that decodes and passes judgeFresh is acted on - see PolicyGate.kt.
    // Everything in the `freshPolicy != null` block (cache, PIN hash, commands, uninstalls) is
    // skipped for a rejected or undecodable one, exactly as if the server were unreachable.
    var freshPolicy: PolicyResponse? = null
    // The command this sync ran (not one delivered again that ran before - security review L12).
    var ranCommand: PendingCommand? = null
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
                // Design 24b §2: the parent PIN's hash goes into the vault; nothing caches it.
                freshPolicy = ParentPin.acceptPolicy(context, fetched.policy)
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
        // The lock's glance page (design 25): which apps' messages it shows, and whether with text.
        com.kidslauncher.mdm.badges.GlanceStore.refreshPolicy(freshPolicy.lockGlance, fresh = true)
        // Round 7 §3: the parent's "Wake the screen for a notification" (memory, read by the lock).
        com.kidslauncher.mdm.lock.PinLockRuntime.noticeWakeSwitch(freshPolicy.launcherUi)
        // Real server contact just succeeded - the offline override's whole job (bridging the gap
        // until the device can hear from the server again) is done, so let real policy reassert
        // immediately rather than waiting out the rest of its time window.
        OfflineOverride.clear()
        // The provider and the parent's own servers were committed with the policy
        // (storeAcceptedPolicy); the running VPN takes the servers from memory (design 36 §5.1).
        DnsFilterEngine.setCustomUpstream(customUpstreamFrom(freshPolicy.dnsUpstream))
        // The blocked-domain log is a per-phone opt-in (off by default, cleanup 2026-10-06): off
        // also drops whatever is still queued.
        BlockedEventLog.setEnabled(context, freshPolicy.dnsLogEnabled)
        // Only actually re-fetches the (potentially 100k+ domain) full list if the version token
        // changed - see DnsFilterEngine's doc comment.
        // Spotify's hosts pass the filter while this phone plays Spotify (design 26c §6.2); the
        // version above moves with the binding, so the delivered list follows too.
        DnsFilterEngine.setSpotify(com.kidslauncher.mdm.music.musicSpotify(freshPolicy.music))
        DnsFilterEngine.refreshIfNeeded(context, api, freshPolicy.dnsFilterVersion)
        // Only ever dispatched off a genuinely fresh fetch, never the cached fallback below - the
        // cached policy blob can still hold a `pendingCommand` from a past cycle that's already
        // been delivered and consumed server-side, and replaying it from cache while offline would
        // re-run an old command (harmless for ring, not for lock/wipe).
        ranCommand = dispatchPendingCommand(context, api, dpm, admin, freshPolicy.pendingCommand, freshPolicy.kidLock)
        // Design 59 §1: `set_kid_pin`'s Child PIN goes no further than its command - the policy
        // applied and held in memory from here on has none, like the cached one.
        freshPolicy = cacheablePolicy(freshPolicy)
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
    AppEnforcer.apply(context, decision, fromSync = true)
    // Design 64 step 2: the zone watch follows the enforced policy - the fences at the first sync of
    // every process and on every change of the zones, the passive feed and the motion sensor while
    // Regularly. The pause changes nothing (decision 12). Never throws.
    ZoneWatch.apply(context, decision.policy)
    // Design 24b: a parent-PIN window that ended (this sync ends an override) locks again now,
    // before the status report, so the report carries the relock.
    try {
        WindowRelock.relockInSync(context)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Relock in the sync failed", e)
    }
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
    // Design 64 step 2: a zone fence's crossing and its confirming alarm ask for a fresh fix too.
    // Design 64 §4.4: which path - the parent's "locate now" and a crossing's confirming fix are
    // the precise one (GPS first), a crossing's first fix the balanced one.
    // Everything new of step 2 hangs on the zone watch being on (qa-design-64-step2-code Q-2):
    // without zones the location step is exactly today's.
    val zonesOn = ZoneWatch.zonesOn(context, decision.policy)
    val zoneFix = ZoneWatch.takeFreshWanted()?.takeIf { zonesOn }
    val commandFix = ranCommand?.command in setOf("ring", "locate")
    val forceFreshLocation = commandFix || zoneFix != null
    val preciseFix = commandFix || zoneFix == FreshKind.PRECISE
    val locationPolicy = decision.policy?.let { it.locationPolicy ?: LEGACY_LOCATION_POLICY }
    // 2026-10-25: the shutdown's last position goes by this policy (never "Off"), and the battery's
    // low edge takes one balanced fix in this sync (LowBatteryFix) - or reports a fix of its own
    // under 2 minutes old (qa-last-position-code Q-7).
    PowerEvents.noteLocationPolicy(locationPolicy)
    val lowBatteryFix = LowBatteryFix.take(
        context,
        deviceOwner = dpm.isDeviceOwnerApp(context.packageName),
        policy = locationPolicy,
        systemLocationOn = QuickControls.isLocationEnabled(context),
        ownFixAgeMs = LocateCommands.cachedQueued()?.let { System.currentTimeMillis() - it.timeMs },
    )
    val location = currentLocationReport(context, dpm, admin, forceFreshLocation, preciseFix, zoneFix != null, zonesOn, locationPolicy, lowBatteryFix)
    val locationOff = locationPolicy?.mode == "off"
    // Location Off, or no zones watched: the queue is emptied before the report, which carries no
    // extras - nothing kept from before the switch is sent (Q-5).
    val queueOff = locationOff || !zonesOn
    if (queueOff) ZoneWatch.clearQueue(context)
    // The fixes that waited for a report (a crossing's in a tunnel), without this report's own.
    val extraFixes = if (queueOff) null else extraLocations(ZoneWatch.queue(context), location.fix)

    // Catalog downloads (design 13): the list and the queue before the report, so it carries the
    // queue; the downloads themselves run outside the sync (AppDownloads). The switch is the
    // enforced policy's - the override and the pause don't lift it (it guards data, not the kid).
    AppDownloads.wifiOnly = decision.policy?.appUpdatesWifiOnly == true
    checkForTrackedAppUpdates(context, api)

    // Best-effort - a failed report must never affect the lock decision above.
    var zoneQueueDone = false
    try {
        // The current blocks and how many in all (design 24b fix, QA of cmd-expiry Low 2).
        val blocks = WindowRelock.reportBlocks(context)
        val lockState = com.kidslauncher.mdm.lock.PinLockRuntime.report(context)
        val sent = api.sendStatus(
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
                capabilities = STATUS_CAPABILITIES,
                callState = CallStateReport.build(context),
                notificationListenerEnabled = BadgeStore.accessGranted(context),
                timeState = TimeRulesRuntime.report(context, decision.policy, reason),
                installMode = PlayRuntime.installModeReport(context),
                playWindowActive = decision.policy?.allowlist != null && PlayRuntime.updateWindowActive(context),
                playStoreSuspendable = PlayRuntime.storeSuspendable(context),
                lockState = lockState,
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
                musicState = try {
                    com.kidslauncher.mdm.music.MusicStatus.report(context)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Music report failed", e)
                    null
                },
                batteryOptimizationExempt = BatteryExemption.exempt(context),
                relock = WindowRelock.lastReport(context),
                unseenBoots = BootWitness.report(context),
                foreignAccess = ForeignAccess.report(context),
                relockBlocks = blocks?.first,
                relockBlocksTotal = blocks?.second,
                remoteWipeReady = LocateCommands.remoteWipeReady(context),
                adminPoliciesPending = AdminPolicies.pending(context),
                clockFormatApplied = ClockFormatSetting.report(context, decision.policy),
                // Design 36 §4.2: only while the VPN runs with the parent's own server.
                dnsUpstream = KidVpnService.dnsUpstreamReport(),
                battery = BatteryStatus.report(context).also { LowBatteryFix.observe(context, it) },
                writeSettingsGranted = WriteSettingsAccess.granted(context),
                extraLocations = extraFixes?.map { it.toReport() },
                zoneWatch = ZoneWatch.report(context, still = location.still),
            )
        )
        // Design 64 step 2: what the server took leaves the queue; a report it didn't take leaves
        // its fix there for the next one.
        afterZoneReport(context, extraFixes, location.fix, sent.isSuccessful, queueOff)
        zoneQueueDone = true
        // Design 59 (qa-59-step4-code N2): the relocks are told only by a report the server took.
        if (sent.isSuccessful) com.kidslauncher.mdm.lock.AndroidLock.relocksReported(lockState.keyguardRelocks)
        // The report just landed, so this doesn't need to stay pending - if it was never used,
        // this is a harmless false->false write.
        mdm.offlineOverrideUsedPendingReport(false)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Status report failed", e)
        if (!zoneQueueDone) afterZoneReport(context, extraFixes, location.fix, accepted = false, locationOff = queueOff)
    }

    // `locate` is answered after the report that carries the fix, with the fix's accuracy and age.
    ranCommand?.takeIf { it.command == "locate" }?.let { command ->
        val (ok, message) = locateResultMessage(location.action, location.accuracyMeters, location.ageSeconds)
        CommandAnswers.result(context, api, command, ok, message)
    }

    // Crashes since the last sync (hash + short trace, no personal data) for the device page.
    com.kidslauncher.mdm.crash.CrashReports.upload(context, api)
    // Parent-PIN uses, wrong tries and lockouts (design 24): with the sync, never on their own.
    ParentPinEvents.upload(context, api)
    // The phone turned off and on (design 38): the start and any kept shutdown, until accepted.
    PowerEvents.upload(context, api)
    // The kid's request for more time (design r13 §2.4): a pending one, until its 30 minutes are over.
    com.kidslauncher.mdm.timerules.TimeRequests.upload(context, api)
    // Command receipts and results that didn't get through when they were made (security review L12).
    CommandAnswers.flush(context, api)
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

    // Vibb music (design 21 §3): the library, listings, covers and own files for the music app, its
    // managed config and the bridge's nudge. Never fails a sync.
    try {
        com.kidslauncher.mdm.music.MusicSync.sync(context, decision.policy, fetch = freshPolicy != null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Music sync failed", e)
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
 * The remote commands: design 24b's `end_windows`/`allow_blocked`/`start_pause` (each followed by
 * one more sync),
 * and Find My Device's ring/stop_ring/lock/wipe, or `locate` (nothing here:
 * the sync takes a fresh fix for the status report - bypassing the throttle - and answers the
 * command after the report with the fix's accuracy and age; the PWA's "Update location now"
 * queues it). A `wipe` that works never reports - the phone reboots into the reset; one the
 * platform refuses reports the failure (security review M8).
 *
 * Security review L12: every command is confirmed (receipt) before it runs - a wipe's only answer
 * when it works -, and a command the server delivers again (our receipt didn't arrive) is
 * confirmed again but never run twice ([CommandAnswers.begin]). Returns the command it ran, without
 * `set_kid_pin`'s PIN.
 *
 * Design 59 §3.5: `set_kid_pin` is checked against [kidLock] - the same fresh answer's, whose hash the PIN must
 * match (a replayed or stale command's PIN doesn't) - and runs before this sync's apply.
 */
private suspend fun dispatchPendingCommand(
    context: Context,
    api: MdmApi,
    dpm: DevicePolicyManager,
    admin: ComponentName,
    pending: PendingCommand?,
    kidLock: KidLock?,
): PendingCommand? {
    if (pending == null || !dpm.isDeviceOwnerApp(context.packageName)) return null

    val fresh = CommandAnswers.begin(context, pending)
    CommandAnswers.receipt(context, api, pending)
    if (!fresh) {
        Log.i(LOG_TAG, "Command ${pending.id} (${pending.command}) delivered again - confirmed, not run twice")
        return null
    }

    when (pending.command) {
        "ring" -> {
            LocateCommands.ring(context)
            reportCommandResult(context, api, pending, success = true, message = "ringing")
        }

        "stop_ring" -> {
            LocateCommands.stopRingAndRestore(context)
            reportCommandResult(context, api, pending, success = true, message = "stopped")
        }

        "lock" -> {
            // Handy's PIN lock when it's active (step 10): LOCKED and shown, then the screen off.
            val ours = com.kidslauncher.mdm.lock.PinLockRuntime.lockNow(context)
            val ok = LocateCommands.lock(dpm, admin)
            reportCommandResult(context, api, pending, ok, lockAnswer(ok, ours, com.kidslauncher.mdm.lock.AndroidLock.gateNow(context)))
        }

        // Design 59 §3.5: the Child PIN for Android's lock - never stored, cached or logged; the answer names no PIN.
        com.kidslauncher.mdm.lock.SET_KID_PIN_COMMAND -> {
            val answer = com.kidslauncher.mdm.lock.AndroidLock.applyPinCommand(context, kidLock, pending.pin)
            reportCommandResult(context, api, pending, answer.success, answer.message)
        }

        // Only reached when the wipe didn't take the phone down at once: a refusal or a call that
        // returned is a failure the parent sees (security review M8, QA Low 3).
        "wipe" -> {
            val (outcome, message) = LocateCommands.wipe(dpm)
            reportCommandResult(context, api, pending, success = wipeSucceeded(outcome), message = message)
        }

        // Design 24b: "Lock the phone again now" - the pause or override and Play install mode
        // end, and the phone locks again (the relock right after this sync's apply, or the worker).
        "end_windows" -> {
            val ended = WindowRelock.lockAgain(context, RelockTrigger.COMMAND)
            val installMode = com.kidslauncher.mdm.play.PlayRuntime.installModeActive(context)
            if (installMode) com.kidslauncher.mdm.play.PlayRuntime.endInstallMode(context)
            reportCommandResult(context, api, pending, success = true, message = endWindowsResult(ended, installMode))
            // A command queued behind it (a lock, a locate) follows within seconds (QA #8).
            requestFollowUpSync(context)
        }

        // Design 24b's fix round (QA High 1): the parent app's pause - the same 2 hours as the
        // Settings switch, locking again by itself, without the PIN on a phone that may have an
        // app able to read it.
        "start_pause" -> {
            RestrictionsPause.start(context, BootClock.windowStart(RESTRICTIONS_PAUSE_DURATION_MS))
            reportCommandResult(context, api, pending, success = true, message = "paused for 2 hours")
            requestFollowUpSync(context)
        }

        // Design 24b: "Allow these apps" - the blocks the PWA listed go (QA of cmd-expiry, Low 1);
        // this sync's apply shows them.
        "allow_blocked" -> {
            val listed = pending.allow?.associate { it.packageName to it.signer?.lowercase() }
            val count = WindowRelock.allowBlocked(context, listed)
            reportCommandResult(context, api, pending, success = true, message = "allowed: $count apps")
            requestFollowUpSync(context)
        }

        // Answered after the status report that carries the fresh fix (performMdmSync).
        "locate" -> {}

        // Design 37: the parent's Install Now - this sync goes on to the list (download, settle)
        // and, last, the commit; the answer comes from the settle or a failure.
        INSTALL_NOW_COMMAND -> {
            startInstallNow(context, api, pending)
            // A command queued behind it follows within seconds (QA L7).
            requestFollowUpSync(context)
        }

        else -> {
            Log.w(LOG_TAG, "Unknown pending command: ${pending.command}")
            reportCommandResult(context, api, pending, success = false, message = "unknown command - update the launcher")
        }
    }
    return if (pending.pin != null) pending.copy(pin = null) else pending
}

/**
 * The remote `lock`'s answer (the server's `locate::reword` words): the screen didn't lock; Android's PIN is the gate
 * (design 59 §3.9, [androidGate]: our LOCKED as its face, or our credential alone - `lockNow` asks for the Child PIN);
 * our own lock; or Android's alone (no Child PIN).
 */
internal fun lockAnswer(ok: Boolean, ours: Boolean, androidGate: Boolean): String = when {
    !ok -> "failed to lock"
    androidGate -> "locked (android pin)"
    ours -> "locked (handy lock)"
    else -> "locked (Android)"
}

/**
 * Install Now received (design 37 §4.2): a record of another command is answered first - with its
 * own failure, else "superseded" (failed, QA L5) -, then this one is stored with the running
 * versionCode and versionName; the ordinary gate's last wait is forgotten (QA L4) and a download
 * waiting for Wi-Fi is gated again (the override is read live, [AppDownloads]' `gate`).
 */
private suspend fun startInstallNow(context: Context, api: MdmApi, pending: PendingCommand) {
    val receipt = installNowOnReceipt(
        InstallNow.load(context), pending.id, pending.requestedAt, pending.releaseTag, BuildConfig.VERSION_CODE.toLong(),
        BuildConfig.VERSION_NAME,
    )
    receipt.answerOld?.let { (old, message) ->
        CommandAnswers.result(
            context, api, PendingCommand(old.commandId, INSTALL_NOW_COMMAND, old.requestedAt),
            success = false, message = message,
        )
    }
    Log.i(LOG_TAG, "Install Now ${pending.id} (row showed ${pending.releaseTag})")
    InstallNow.store(context, receipt.store)
    SelfUpdate.lastWait = null
    try {
        AppDownloads.request(context, "install_now")
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't start the downloads for Install Now", e)
    }
}

/**
 * Install Now's answer when there is one ([installNowOutcome]): from the new process after the
 * update ("installed"), a failure recorded anywhere ([InstallNow.fail]), or what the list says.
 * The answer waits in the outbox when the server can't be reached.
 */
private suspend fun settleInstallNow(context: Context, api: MdmApi, row: LauncherRow) {
    val record = InstallNow.load(context) ?: return
    val (ok, message) = installNowOutcome(record, BuildConfig.VERSION_CODE.toLong(), row, BuildConfig.VERSION_NAME)
        .answer(BuildConfig.VERSION_NAME) ?: return
    Log.i(LOG_TAG, "Install Now ${record.commandId}: $message")
    CommandAnswers.result(context, api, PendingCommand(record.commandId, INSTALL_NOW_COMMAND, record.requestedAt), ok, message)
    InstallNow.clearCommand(context, record.commandId)
}

private fun requestFollowUpSync(context: Context) {
    try {
        com.kidslauncher.mdm.push.SyncRunner.request(context, "command_followup")
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't request the follow-up sync", e)
    }
}

/** What a command did - kept for the next sync if it doesn't get through (security review L12). */
private suspend fun reportCommandResult(context: Context, api: MdmApi, command: PendingCommand, success: Boolean, message: String?) =
    CommandAnswers.result(context, api, command, success, message)

/** The zone queue after the status report (design 64 step 2, `queueAfterReport`); never throws. */
private fun afterZoneReport(context: Context, extras: List<QueuedFix>?, fix: QueuedFix?, accepted: Boolean, locationOff: Boolean) {
    try {
        ZoneWatch.afterReport(context, extras.orEmpty(), fix, accepted, locationOff)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Zone queue update failed", e)
    }
}

/** What the sync did about location: the report (if any) and its fix as the zone queue keeps it,
 * for `locate` the fix's details, and [still] - `zone_watch.still` (`reportStill`, Q-3). */
private class LocationOutcome(
    val action: LocationAction,
    val fix: QueuedFix?,
    val accuracyMeters: Float?,
    val ageSeconds: Long?,
    val still: Boolean = false,
) {
    val report: LocationReport? get() = fix?.toReport()
}

/**
 * [policy] `null` = no policy (a never-managed phone): the old behaviour. The action is the pure
 * [syncLocationAction]: **without zones** ([zonesOn] false) exactly today's `locationAction` - no
 * heartbeat, no kept fix, no motion gate, the precise path for every fresh fix
 * (qa-design-64-step2-code Q-2). With zones (design 64 step 2): Regularly without a command is the
 * heartbeat ([heartbeatDue]) - a fresh fix only when due, no kept fix not yet reported is younger
 * than the interval and the motion sensor doesn't say the phone stayed put (at most 4 h of that),
 * else the cached fix, no LocationManager call; every due call and fresh fix stamps the beat. A
 * crossing's sync ([crossing], [forceFresh] through `ZoneWatch.takeFreshWanted`) and
 * `locate`/`ring` always fix - [precise] (`locate`/`ring`, a crossing's confirm) GPS first, a
 * crossing's first fix and the heartbeat `FRESH_BALANCED`. The kept slot becomes the reported fix
 * only at a due `KEPT` or a crossing's sync (Q-1); the motion gate arms only after a genuinely
 * fresh fix (Q-6).
 */
private suspend fun currentLocationReport(
    context: Context,
    dpm: DevicePolicyManager,
    admin: ComponentName,
    forceFresh: Boolean,
    precise: Boolean,
    crossing: Boolean,
    zonesOn: Boolean,
    policy: LocationPolicy?,
    lowBatteryFix: LowFix,
): LocationOutcome {
    if (!dpm.isDeviceOwnerApp(context.packageName)) return LocationOutcome(LocationAction.NONE, null, null, null)
    val now = System.currentTimeMillis()
    val lastOwnFix = LauncherPreferences.mdm().lastActiveLocationFetchAtMs()
    val motion = if (zonesOn) MotionGate.motion(context) else Motion.NO_SENSOR
    val kept = if (zonesOn) LocateCommands.keptUnreported() else null
    // The still skip and `still` count from the last genuinely fresh own fix (F-3).
    val lastFreshFix = if (zonesOn) ZoneWatch.lastFreshFixMs(context) else lastOwnFix
    val sync = syncLocationAction(
        policy = policy,
        zonesOn = zonesOn,
        forced = forceFresh,
        precise = precise,
        nowMs = now,
        lastOwnFixMs = lastOwnFix,
        lastBeatMs = if (zonesOn) ZoneWatch.lastBeatMs(context) else 0L,
        motion = motion,
        keptFixAgeMs = kept?.let { now - it.timeMs },
        lastFreshFixMs = lastFreshFix,
    )
    val action = withLowBatteryFix(sync.action, lowBatteryFix)
    if (zonesOn) {
        val beat = sync.beat
        if (beat != null && beat != Heartbeat.NOT_DUE) Log.i(LOG_TAG, "Location heartbeat: $beat")
        if (action.fresh || (beat != null && beat != Heartbeat.NOT_DUE)) ZoneWatch.stampBeat(context, now)
        // The kept fix (passive, a series check's) becomes the reported one only here (Q-1).
        if (beat == Heartbeat.KEPT || crossing) LocateCommands.promoteKept(context)
    }
    val location = LocateCommands.currentLocation(context, dpm, admin, action)
        ?: return LocationOutcome(action, null, null, null)
    // A genuinely fresh fix of our own (not the cached or an old last-known fallback): the motion
    // sensor counts "moved" from here (Q-6).
    if (zonesOn && action.fresh && freshOwnFix(location.time, now)) {
        ZoneWatch.stampFreshFix(context, location.time)
        MotionGate.arm(context)
    }
    val accuracy = if (location.hasAccuracy()) location.accuracy else null
    return LocationOutcome(
        action,
        location.toQueued(LocateCommands.providerOf(location)),
        accuracy,
        (System.currentTimeMillis() - location.time) / 1000,
        still = reportStill(zonesOn, action, motion, now, lastFreshFix),
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
        // Install Now: the new process answers "installed" also without a list (tsnet before the
        // unlock); nothing else is known.
        settleInstallNow(context, api, LauncherRow.NO_LIST)
        return
    }

    // Design 24b (fix round QA Medium 2): the catalog's pinned signers feed the relock's check.
    WindowRelock.rememberCatalogPins(context, updates)
    val (launcherUpdates, otherUpdates) = updates.partition { it.isLauncher }
    val state = TrackedAppUpdateState.load()
    val wanted = mutableListOf<WantedDownload>()
    // Security review M10: a catalog app the server has no package or signing key for is never
    // downloaded - there would be nothing to check its APK against. Reported as refused.
    val unpinned = mutableMapOf<Long, String>()
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
            // Only while Android still has it (the user's emulator, 2026-10-12: an app uninstalled
            // by hand was never installed again). Gone, or older than what we installed: forget
            // the record and queue it like a new release - pins, refusals and backoffs apply.
            val phone = update.packageName.takeIf { it.isNotBlank() }
                ?.let { phonePackage(context.packageManager, it) } ?: PhonePackage.Unknown
            if (catalogReleaseStillInstalled(phone, known?.lastInstalledVersionCode)) continue
            Log.i(LOG_TAG, "${update.name} ${update.releaseTag} is no longer on the phone ($phone) - installing it again")
            TrackedAppUpdateState.forgetInstalled(context, key)
        }
        if (!catalogPinned(update.packageName, update.signerSha256)) {
            Log.w(LOG_TAG, "Skipping ${update.name}: the server has no package and signing key to check it against")
            unpinned[update.id] = update.releaseTag
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
    val launcher = handleLauncherUpdate(context, launcherUpdates.firstOrNull())
    launcher.download?.let { wanted += it.wanted() }
    try {
        AppDownloads.reconcile(context, wanted, otherUpdates.associate { it.id to it.releaseTag }, unpinned)
        AppDownloads.request(context, "sync")
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Queueing the downloads failed", e)
    }
    settleInstallNow(context, api, launcher.row)
}

/** [handleLauncherUpdate]'s answer: the release to download, and what the row says for Install
 * Now's settle (design 37). */
private class LauncherHandled(val download: TrackedAppUpdate?, val row: LauncherRow)

private fun TrackedAppUpdate.wanted() = WantedDownload(
    appId = id,
    tag = releaseTag,
    // The install notification says what the kid sees: the parent's name when there is one (design 14).
    name = com.kidslauncher.mdm.appdisplay.AppDisplay.label(packageName.takeIf { it.isNotBlank() }) ?: name,
    isLauncher = isLauncher,
    downloadUrl = downloadUrl,
    sha256 = sha256,
    packageName = packageName,
    signerSha256 = normalizedSigner(signerSha256),
)

/**
 * The launcher's own row (handy step 11, [launcherUpdateStep]): the APK is downloaded once (by
 * [AppDownloads], design 13) into `noBackupFilesDir` with its SHA-256 and kept as
 * [PendingSelfUpdate] until [commitPendingSelfUpdateIfDue] finds the update window - not
 * re-fetched every sync, replaced by a newer tag, dropped when the release is withdrawn or
 * installed. [update] `null` = the list arrived without the launcher. Returns the release to
 * download, if any.
 */
private fun handleLauncherUpdate(context: Context, update: TrackedAppUpdate?): LauncherHandled {
    val state = TrackedAppUpdateState.load()
    val pendingEntry = TrackedAppUpdateState.pendingEntry()
    val key = update?.id?.toString() ?: pendingEntry?.first ?: return LauncherHandled(null, LauncherRow.NOT_OFFERED)
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
        return LauncherHandled(null, LauncherRow.NOT_OFFERED)
    }
    // Install Now skips the hour after a failure (the tap is the retry); a refusal still holds.
    val installNow = InstallNow.active(context) != null
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
        failedBackoffMs = if (installNow) 0 else FAILED_RETRY_BACKOFF_MS,
        runningVersionCode = BuildConfig.VERSION_CODE.toLong(),
        runningVersionName = BuildConfig.VERSION_NAME,
    )
    val row = launcherRow(true, update?.releaseTag, known?.lastInstalledTag, known?.refusedTag, step)
    val download = when (step) {
        LauncherUpdateStep.NOTHING, LauncherUpdateStep.KEEP_PENDING -> null
        LauncherUpdateStep.ALREADY_RUNNING -> {
            // update is non-null here: a withdrawn row never reaches this step.
            Log.i(LOG_TAG, "The launcher row ${update?.releaseTag} is not ahead of the running build ${BuildConfig.VERSION_NAME} - recorded as installed")
            if (pending != null) SelfUpdate.dropPending(context)
            if (update != null) TrackedAppUpdateState.recordInstalled(context, key, update.releaseTag)
            null
        }
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
    return LauncherHandled(download, row)
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
    // Install Now (design 37): no backoff (the tap is the retry), no night window, no screen-off -
    // only a call or an emergency holds it. A refused release is still never committed.
    val installNow = InstallNow.active(context) != null
    // A transient failure of this release keeps its APK and waits out the backoff; a refused one
    // is never committed (qa-11-code #4).
    val allowed = if (installNow) {
        known?.refusedTag != pending.releaseTag
    } else {
        pendingCommitAllowed(pending.releaseTag, known?.lastFailedTag, known?.lastFailedAtMs, known?.refusedTag, now, FAILED_RETRY_BACKOFF_MS)
    }
    if (!allowed) {
        if (known?.refusedTag == pending.releaseTag) SelfUpdate.dropPending(context)
        return
    }
    val decision = commitDecision(context, pending, installNow)
    if (installNow) {
        // A call that never ends fails the command after 6 h (qa-37-design R-L4).
        val onCall = decision is UpdateWindowDecision.Wait && decision.reason == UpdateWaitReason.CALL
        if (InstallNow.callWait(context, onCall, android.os.SystemClock.elapsedRealtime())) return
    }
    if (decision is UpdateWindowDecision.Wait) {
        waitForWindow(context, pending, decision, installNow)
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
        archiveVersionName = archive?.versionName,
        ownVersionName = BuildConfig.VERSION_NAME,
    )
    val appId = key.toLongOrNull()
    when {
        check == PendingApkCheck.OK -> {}
        check == PendingApkCheck.MISSING || check == PendingApkCheck.CORRUPT -> {
            Log.w(LOG_TAG, "Pending launcher ${pending.releaseTag}: ${check.name} - downloading again at the next sync")
            SelfUpdate.dropPending(context)
            // Install Now (qa-37-design R-L2): a file that changed after its own hash check is
            // answered; a missing one is downloaded again at once.
            if (installNow) {
                if (check == PendingApkCheck.CORRUPT) InstallNow.fail(context, INSTALL_NOW_DOWNLOAD_CORRUPT)
                else requestInstallNowSync(context, "install_now_redownload")
            }
            return
        }
        check == PendingApkCheck.SAME_VERSION -> {
            Log.i(LOG_TAG, "Pending launcher ${pending.releaseTag} is the running version - nothing to install")
            TrackedAppUpdateState.recordInstalled(context, key, pending.releaseTag)
            SelfUpdate.dropPending(context)
            // The settle answers "already running" (R-L2).
            if (installNow) requestInstallNowSync(context, "install_now_running")
            return
        }
        check.deterministic -> {
            // The same APK would be refused again: never fenced, downloaded or tried again until
            // the server advertises another release (qa-11-code #4).
            Log.w(LOG_TAG, "Pending launcher ${pending.releaseTag} refused before installing: ${check.name}")
            SelfUpdate.dropPending(context)
            TrackedAppUpdateState.recordRefused(context, key, pending.releaseTag)
            if (installNow) InstallNow.fail(context, check.installNowAnswer)
            appId?.let {
                notifyAppInstallResult(context, it, pending.name, success = false)
                reportInstallFailure(api, it)
            }
            return
        }
    }
    Log.i(LOG_TAG, "Committing launcher ${pending.releaseTag} (${if (installNow) "Install Now" else "update window"})")
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
            val again = commitDecision(context, pending, installNow)
            if (again is UpdateWindowDecision.Wait) {
                waitForWindow(context, pending, again, installNow)
                false
            } else {
                UpdateFence.fenceBeforeCommit(context, sessionId, pending.releaseTag)
                true
            }
        },
        commitFailed = { UpdateFence.commitFailed(context) },
        deleteOnFailure = false,
        // Our own package only (pendingApkCheck checked it; the session holds it to that too).
        expectedPackage = context.packageName,
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
            if (installNow) InstallNow.fail(context, INSTALL_NOW_INSTALL_FAILED_START)
        }
    }
}

/** The commit gate: Install Now's ([installNowDecision]) or the night window's. */
private fun commitDecision(context: Context, pending: PendingSelfUpdate, installNow: Boolean): UpdateWindowDecision {
    val inputs = SelfUpdate.windowInputs(context, pending)
    return if (installNow) installNowDecision(inputs.liveCall, inputs.emergency) else updateWindowDecision(inputs)
}

private fun requestInstallNowSync(context: Context, reason: String) {
    try {
        com.kidslauncher.mdm.push.SyncRunner.request(context, reason)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't ask for Install Now's sync", e)
    }
}

/** The gate said wait: remember why (status report) and, with the screen off - or for Install
 * Now, whatever the screen (design 37 §4.2) -, look again when it could pass. */
private fun waitForWindow(context: Context, pending: PendingSelfUpdate, decision: UpdateWindowDecision.Wait, installNow: Boolean) {
    val changed = SelfUpdate.lastWait != decision.reason
    SelfUpdate.lastWait = decision.reason
    // Install Now (qa-37-code L1): this sync's report went out before the commit step, saying
    // "installing" - one more (coalesced) sync carries the wait's own word to the row.
    if (installNow && changed) requestInstallNowSync(context, "install_now_wait")
    Log.i(LOG_TAG, "Launcher ${pending.releaseTag} waits: ${decision.reason.wire}")
    val recheck = decision.recheckInMs
    if (recheck != null && (installNow || SelfUpdate.screenOffForMs(context) != null)) SelfUpdate.armAlarm(context, recheck)
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
/** [PhonePackage] for [packageName]: `MATCH_UNINSTALLED_PACKAGES` so a hidden app counts, then
 * `FLAG_INSTALLED` for this user; only `NameNotFoundException` says "missing". */
private fun phonePackage(pm: PackageManager, packageName: String): PhonePackage = try {
    val info = pm.getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()),
    )
    val installed = info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
    when (installed) {
        false -> PhonePackage.Missing
        true -> PhonePackage.Installed(info.longVersionCode)
        null -> PhonePackage.Unknown
    }
} catch (e: PackageManager.NameNotFoundException) {
    PhonePackage.Missing
} catch (e: Exception) {
    PhonePackage.Unknown
}

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
 * "this phone has had time rules" flag ([judgeFresh]), the DNS upstream prefs (the provider and
 * `dns_upstream_custom`, design 36 §5.1) and the
 * call prefs ([callPrefsUpdate]: `calls_managed_last` only from an explicit `managed`, and the last
 * managed call rules) in one synchronous `commit()` - they must never disagree, and the generated
 * preference setters only `apply()` asynchronously (see CLAUDE.md on writes racing a process death). */
private fun storeAcceptedPolicy(context: Context, policy: PolicyResponse): Unit = synchronized(POLICY_CACHE_LOCK) {
    val keys = LauncherPreferences.mdm().keys()
    val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putString(keys.kidModePolicy(), ServerJson.encodeToString(PolicyResponse.serializer(), policyToCache(policy)))
        .putBoolean(keys.policyEverApplied(), true)
        .putString(keys.lastEnforcedPlan(), LastEnforcedPlan.encode(LastEnforcedPlan.of(policy)))
    if (policy.timePolicy != null) editor.putBoolean(keys.timePolicySeen(), true)
    // What KidVpnService forwards to (it never fetches policy itself): the provider, and the
    // parent's own servers or none (design 36 §5.1) - with the policy, so they never disagree.
    editor.putString(keys.dnsUpstreamProvider(), policy.dnsUpstreamProvider)
    val custom = customUpstreamPref(policy.dnsUpstream)
    if (custom != null) editor.putString(keys.dnsUpstreamCustom(), custom) else editor.remove(keys.dnsUpstreamCustom())
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

/** The cached policy blob is written by the sync and once by the vault's migration
 * ([redactCachedPolicy]) - never both at once (design 24b §2). */
internal val POLICY_CACHE_LOCK = Any()

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
fun reevaluateLockReasonFromCache(context: Context, snapshot: TimeRulesRuntime.Snapshot? = null): LockReason? {
    val mdm = LauncherPreferences.mdm()
    // Design r13 §1.4: `recheck` hands in its one snapshot (the same policy and override state); everyone else gets one here.
    val lock = snapshot?.lock ?: TimeRulesRuntime.currentLock(
        context,
        currentPolicyDecision().policy,
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
            AppEnforcer.apply(appContext, currentPolicyDecision())
            // The Play state may have moved while that apply ran (screen on right after screen
            // off in the update window): apply again until what's enforced is what's current,
            // so the window really ends at once on screen-on.
            var tries = 0
            while (tries++ < 3 && AppEnforcer.lastEnforcedPlayState?.key() != PlayRuntime.state(appContext).key()) {
                AppEnforcer.apply(appContext, currentPolicyDecision())
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
