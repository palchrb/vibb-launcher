package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/status` - a best-effort heartbeat; a failed send must never affect
 * the local lock decision, only the admin site's visibility into it. [policyState] is `"ok"` or
 * why the phone isn't enforcing the server's current policy (see
 * [com.kidslauncher.mdm.server.policyState]); [restrictionsPaused] is the PIN-gated Settings
 * kill-switch ([com.kidslauncher.mdm.server.RestrictionsPause]). The server shows both as
 * warnings on the device page. [capabilities] says what this launcher enforces (`call_policy_v1`:
 * the server's `call_policy`); [callState] is what it did about calls. */
@Serializable
data class StatusReportRequest(
    val lockReason: String,
    val kioskEngaged: Boolean,
    val installedApps: List<InstalledApp>? = null,
    val appVersion: String? = null,
    val appVersionCode: Int? = null,
    val offlineOverrideUsed: Boolean = false,
    val location: LocationReport? = null,
    val policyState: String? = null,
    val restrictionsPaused: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val callState: CallState? = null,
    /** Our notification listener (app badges) has access - see `BadgeListenerService`. */
    val notificationListenerEnabled: Boolean? = null,
    /** Time rules and screen time right now (handy step 6) - see [TimeState]. */
    val timeState: TimeState? = null,
    /** Play install mode is on (handy step 7) - the parent opened Play with the PIN. */
    val installMode: InstallModeReport? = null,
    /** The nightly Play update window is in force (Play Store unsuspended, screen off). */
    val playWindowActive: Boolean = false,
    /** `false` when the platform refused to suspend the Play Store (step 9 B4); `null` = not tried. */
    val playStoreSuspendable: Boolean? = null,
    /** Handy's own PIN lock (step 10) - see [LockStateReport]. */
    val lockState: LockStateReport? = null,
    /** The screen timeout the phone has now (read back from Settings.System), seconds; `null` =
     * unreadable. See [com.kidslauncher.mdm.server.ScreenTimeout]. */
    val screenTimeoutSeconds: Int? = null,
    /** Android's backup service (backup to the Google account) is on; `null` = not device owner or
     * unreadable (left out of the JSON, like an older launcher). Kept off while managed - see
     * [com.kidslauncher.mdm.server.BackupService]. */
    val backupServiceEnabled: Boolean? = null,
    /** The ringer mode (design 18): `normal`/`vibrate`/`silent`; `null` = unreadable (left out).
     * Shown on the device page so the parent sees why a call wasn't heard. */
    val ringerMode: String? = null,
    /** Do Not Disturb (design 18): the interruption filter `all`/`priority`/`none`/`alarms`;
     * `null` = unknown (left out). */
    val interruptionFilter: String? = null,
    /** The update fence and the pending self-update (handy step 11) - see [UpdateFenceReport]. */
    val updateFence: UpdateFenceReport? = null,
    /** What the notification rule removed since the last report (handy step 11) - see
     * [NotificationCancelsReport]. */
    val notificationCancels: NotificationCancelsReport? = null,
    /** Catalog downloads (design 13) - see [AppDownloadsReport]. */
    val appDownloads: AppDownloadsReport? = null,
    /** The boot cover (design 16b) - see [BootCoverReport]. */
    val bootCover: BootCoverReport? = null,
    /** Vibb music (design 21 §1.3, capability `music_v1`): the music app's last report and the
     * bridge; null without a music app. */
    val musicState: kotlinx.serialization.json.JsonObject? = null,
    /** Design 19b: the launcher is on Android's power-save allowlist
     * (`PowerManager.isIgnoringBatteryOptimizations`); `null` = unreadable (left out). */
    val batteryOptimizationExempt: Boolean? = null,
    /** Design 24b: the last relock of a parent-PIN window (an override or a pause that ended) -
     * see [RelockReport]; `null` before the first one (left out). */
    val relock: RelockReport? = null,
    /** Design 24b §3: boots the launcher didn't see (safe mode, a start cut short) - see
     * [UnseenBootsReport]; `null` before the first count (left out). */
    val unseenBoots: UnseenBootsReport? = null,
    /** Design 24b's fix round (QA High 1): a device admin or accessibility service of an app
     * outside the allowlist - the phone refuses the parent PIN meanwhile; `null` = none (left out). */
    val foreignAccess: ForeignAccessReport? = null,
    /** QA of the launcher wave, Low 4: whether a remote wipe would work now ([remoteWipeReady]):
     * on Android 14 the admin's new `<wipe-data />` policy counts only after the phone restarts
     * once. `null` = not device owner or unreadable (left out). */
    val remoteWipeReady: Boolean? = null,
    /** The device admin's declared policies Android hasn't granted yet ([AdminPolicies]): on some
     * versions a policy an update added counts only after the phone restarts once. Empty = all
     * granted; `null` = not device owner or unreadable (left out). */
    val adminPoliciesPending: List<String>? = null,
    /** Design 24b (fix of 2026-10-12): every block in `relock_blocked` now, from a relock or the
     * install check outside windows - see [RelockBlockReport]; an empty list = none; `null` = not
     * device owner (left out). */
    val relockBlocks: List<RelockBlockReport>? = null,
    /** How many blocks there are in all: [relockBlocks] stops at `RELOCK_BLOCKS_MAX`, and the PWA
     * says "and N more" (QA of cmd-expiry, Low 2). Sent with [relockBlocks]. */
    val relockBlocksTotal: Int? = null,
    /** Design 34 P12: the phone's clock format is the policy's `launcher_ui.clock_format` (true), it
     * should be but isn't - no "Modify system settings" access, or the write was refused - (false);
     * `null` (left out) for "Follow the phone" or no policy ([com.kidslauncher.mdm.server.ClockFormatSetting.report]). */
    val clockFormatApplied: Boolean? = null,
    /** Design 36 §4.2 (capability `dns_custom_v1`): which of the parent's DNS servers the phone
     * forwards to - see [DnsUpstreamReport]; `null` (left out) while the Web filter is off or no
     * custom server is configured. Sent from design 36 step 2 on. */
    val dnsUpstream: DnsUpstreamReport? = null,
    /** Design 35 §2.5: the battery, for the parent's "Battery low" event and the PWA's battery
     * line - see [BatteryReport]. Not set yet (design 35 step 3 reads it); `null` = left out. */
    val battery: BatteryReport? = null,
)

/** `StatusReportRequest.dnsUpstream` (no defaults: every field is always sent): the [rev] of the
 * servers in use, [inUse] `custom` or `backup`, since when (wall clock), and with `backup` why:
 * `bootstrap`, `connect`, `tls`, `http`, `refused` or `timeout`. Never an address. Pinned by
 * `dns_upstream_status.json`, byte-identical with the server's `testdata/`. */
@Serializable
data class DnsUpstreamReport(
    val rev: Long,
    val inUse: String,
    val sinceMs: Long?,
    val reason: String?,
)

/**
 * `StatusReportRequest.battery` (design 35 §2.5; no defaults: every field is always sent): the
 * level in percent (0..100; the server drops the reading otherwise), whether the phone is charging
 * (plugged in, or charging/full), and Android's own "battery low" (`EXTRA_BATTERY_LOW`).
 */
@Serializable
data class BatteryReport(val level: Int, val charging: Boolean, val low: Boolean)

/** `StatusReportRequest.foreignAccess`: the packages (valid names, at most 8 each, sorted) of the
 * device admins and accessibility services found. Both keys always sent. */
@Serializable
data class ForeignAccessReport(val admins: List<String>, val accessibility: List<String>)

/** `StatusReportRequest.unseenBoots`: how many boots the launcher never saw (it only goes up) and
 * when it last noticed one (wall clock; `null` = never). */
@Serializable
data class UnseenBootsReport(val count: Int, val lastAtMs: Long?)

/**
 * `StatusReportRequest.bootCover` (design 16b, qa-16b-code #5; no defaults: every field is always
 * sent): whether the phone takes the server switch ([wanted]), whether the cover's crash guard
 * tripped (sticky until the switch goes off and on), when it was last armed at a shutdown, last
 * shown at a start, and who handed over after the unlock ("cover" or "launcher").
 */
@Serializable
data class BootCoverReport(
    val wanted: Boolean,
    val tripped: Boolean,
    val lastArmedAtMs: Long?,
    val lastShownAtMs: Long?,
    val lastHandover: String?,
    val lastHandoverAtMs: Long?,
)

/**
 * `StatusReportRequest.appDownloads` (no defaults: every field is always sent): a full snapshot of
 * the downloads the phone wants (at most 20, so no stale rows on the server), whether "App updates
 * only on Wi-Fi" is on as the phone applies it, and its network now ("unmetered", "metered",
 * "roaming", "none").
 */
@Serializable
data class AppDownloadsReport(
    val wifiOnly: Boolean,
    val network: String,
    val entries: List<AppDownloadEntry>,
)

/**
 * One download: [state] "waiting_wifi", "waiting_network", "waiting_roaming", "waiting_space",
 * "downloading" or "installing"; [bytes] on the phone, [total] once known; [sinceMs] when the phone
 * first saw the release; [anyNetworkAtMs] our own update's grace end (`null` for catalog apps).
 */
@Serializable
data class AppDownloadEntry(
    val trackedAppId: Long,
    val releaseTag: String,
    val state: String,
    val bytes: Long,
    val total: Long?,
    val sinceMs: Long?,
    val anyNetworkAtMs: Long?,
)

/**
 * `StatusReportRequest.updateFence` (no defaults: every field is always sent): whether the server's switch is on as the phone sees it, the
 * fence's [state] ("none", "planned", "fenced"), the Home packages the platform refused to
 * suspend (the live fence's, else the last one's), why the last fence ended ("orphan" = a lost
 * record swept), whether we held ROLE_HOME (a partial fence otherwise), and
 * the downloaded launcher update waiting for the night window ([pendingTag], since
 * [pendingSinceMs], [waitingFor] = the gate's last reason: "call", "emergency", "screen_on",
 * "screen_off_short", "outside_window").
 */
@Serializable
data class UpdateFenceReport(
    val enabled: Boolean,
    val state: String,
    val unsuspendable: List<String>,
    val lastRelease: String?,
    val homeRoleHeld: Boolean?,
    val pendingTag: String?,
    val pendingSinceMs: Long?,
    val waitingFor: String?,
    /** The last fence's start and end (wall clock; qa-11-code #5): the card ages "installing
     * right now" out by them. */
    val lastFencedAtMs: Long?,
    val lastReleasedAtMs: Long?,
)

/**
 * `StatusReportRequest.notificationCancels`: whether the rule is [active] now and, per (package,
 * channel id), how many notifications it cancelled or snoozed since the last report - at most 20
 * entries, ids capped at 64 characters, [dropped] = entries left out. Never titles, text, keys or
 * tags (privacy).
 */
@Serializable
data class NotificationCancelsReport(
    val active: Boolean,
    val entries: List<NotificationCancelEntry>,
    val dropped: Int,
)

@Serializable
data class NotificationCancelEntry(
    val packageName: String,
    val channel: String?,
    val cancelled: Int,
    val snoozed: Int,
)

/**
 * `StatusReportRequest.lockState`: whether handy's PIN lock is [active] (else [inactive] says why:
 * "no_pin", "unmanaged", "android_credential", "keyguard_not_disabled", "crash_guard"; "bad_hash"
 * keeps it active - only the parent code opens it), [locked] now, wrong PINs in a row, the end of
 * the wait (wall clock), and how often it stepped aside for a call/the emergency dialer/an alarm
 * since the last report. Never unlock times or PIN material (privacy).
 */
@Serializable
data class LockStateReport(
    val active: Boolean,
    val inactive: String?,
    val locked: Boolean,
    val failures: Int,
    val backoffUntilMs: Long?,
    val exemptYields: Int,
    /** Design 17 (QA #11): allowed VoIP apps whose last ring had no full-screen intent - Android
     * dropped it (no USE_FULL_SCREEN_INTENT), so their calls can't ring over the lock. Package
     * names only; always sent. */
    val voipFsiDenied: List<String>,
)

/** `StatusReportRequest.installMode`: wall-clock end of the Play install mode window. */
@Serializable
data class InstallModeReport(val untilMs: Long)

/**
 * `StatusReportRequest.timeState`, shown on the server's device page. [day] is the local date
 * being counted, [budgetMinutes] today's budget including [extraMinutes] from the parent's
 * "+ screen time" (`null` = unlimited), [activeRuleId]/[activeRuleName] the strongest active rule,
 * [liftsActive] the lift ids in force, [lockReason] a [com.kidslauncher.mdm.server.LockReason] name,
 * [zone] the zone [day] and the rules' windows are counted in.
 */
@Serializable
data class TimeState(
    val day: String?,
    val usedMinutes: Int,
    val budgetMinutes: Int?,
    val extraMinutes: Int,
    val activeRuleId: Long?,
    val activeRuleName: String?,
    val callsBlocked: Boolean,
    val lockReason: String,
    val liftsActive: List<Long>,
    /** The screen-time record couldn't be read today, so it counts as used up (QA step 6 #6). */
    val ledgerUnreadable: Boolean = false,
    /** The phone's IANA time zone (design 34 P13; the id of the zone the snapshot was counted in,
     * [com.kidslauncher.mdm.timerules.TimeRulesRuntime.Snapshot.zone]): the server shows a rule's
     * end ("until 07:00") only when it is Phone Setup's zone. `null` = left out of the JSON (an
     * older report's shape; the runtime always sets it). */
    val zone: String? = null,
)

/**
 * `StatusReportRequest.callState` - shown on the server's Calls & SMS page (kid-phone-server's
 * `handlers::calls::call_warnings`). No defaults on purpose: every field is always sent.
 * [state] is "unmanaged", "managed" or "fail_closed". Times are ISO-8601 (UTC).
 * [lastEmergencyCallAt]/[callbackWindowUntil] report an emergency call and the window in which
 * anyone may call back (QA blocker 2) so the parent is told. [callLogReadable] false means the
 * callback window can't open from the call log (QA step 2 #7), shown as a warning. [bootPolicy] is
 * the device-protected copy of the call rules used before the first unlock (task 15): "ok",
 * "write_failed", "unreadable" (it wouldn't make the same decisions) or "unknown".
 */
@Serializable
data class CallState(
    val state: String,
    val dialerRoleHeld: Boolean,
    val redirectionRoleHeld: Boolean,
    /** Security review L7: `ROLE_CALL_SCREENING` - the incoming-call filter when we aren't the
     * phone app. The server warns when calls are managed and neither role filters them. */
    val screeningRoleHeld: Boolean,
    val defaultDialer: String?,
    val systemDialer: String?,
    val smsRestricted: Boolean,
    val outgoingRestricted: Boolean,
    val defaultSmsPackage: String?,
    val lastError: String?,
    val lastEmergencyCallAt: String?,
    val callbackWindowUntil: String?,
    val callLogReadable: Boolean,
    val bootPolicy: String,
    /** The in-call screen couldn't be brought up over the PIN lock for a ringing call (step 10,
     * QA 10 #8) - the last time, ISO-8601; `null` = never. */
    val inCallUiFailedAt: String? = null,
)
