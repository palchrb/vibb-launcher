package com.kidslauncher.mdm.server.dto

import android.os.BatteryManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Body for `POST /api/devices/status` - a best-effort heartbeat; a failed send must never affect
 * the local lock decision, only the admin site's visibility into it. [policyState] is `"ok"` or
 * why the phone isn't enforcing the server's current policy (see
 * [com.kidslauncher.mdm.server.policyState]); [restrictionsPaused] is the PIN-gated Settings
 * kill-switch ([com.kidslauncher.mdm.server.RestrictionsPause]). The server shows both as
 * warnings on the device page. [capabilities] is [com.kidslauncher.mdm.server.STATUS_CAPABILITIES]; [callState] is
 * what it did about calls. */
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
     * unreadable (left out of the JSON). Kept off while managed - see
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
    /** Vibb music (design 21 §1.3): the music app's last report and the
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
    /** Design 36 §4.2: which of the parent's DNS servers the phone
     * forwards to, and since design 67 whether DNS can be reached (provider mode too) - see
     * [DnsUpstreamReport]; `null` (left out) unless the filter's tunnel is up
     * (`KidVpnService.dnsUpstreamReport`). */
    val dnsUpstream: DnsUpstreamReport? = null,
    /** Design 35 §2.5: the battery, for the parent's "Battery low" event and the PWA's battery
     * line - see [BatteryReport] (`BatteryStatus.report`); `null` = unreadable, left out. */
    val battery: BatteryReport? = null,
    /** "Modify system settings" (PLAN fix backlog, 2026-10-24): `Settings.System.canWrite` - the
     * app-op the touch sounds row and the clock format need ([com.kidslauncher.mdm.server.WriteSettingsAccess]);
     * `null` = unreadable, left out. The server names Settings' row while it is `false`. */
    val writeSettingsGranted: Boolean? = null,
    /** Security review S-L10's fix round: the phone has been on the tailnet (`tsnet_node_joined`,
     * [com.kidslauncher.mdm.server.TsnetClient.joined]) - the server's Advanced page offers Leave Tailnet only then.
     * The shared contract `leave_tailnet.json`'s `report`. */
    val tailnetJoined: Boolean? = null,
    /** Design 64 step 2: the fixes taken or kept since the last report the server took and not sent
     * as [location] (a crossing's fix and its confirming one, a report that didn't get through),
     * oldest first, at most 20 ([com.kidslauncher.mdm.zones.ZONE_QUEUE_MAX]); `null` = none (left
     * out). The server stores every one, then judges the zones once. Pinned by
     * `zone_status_report.json` (`ZoneReportTest`). */
    val extraLocations: List<LocationReport>? = null,
    /** Design 64 step 2: the phone's zone watch - see [ZoneWatchReport];
     * `null` = not device owner (left out). */
    val zoneWatch: ZoneWatchReport? = null,
    /** Design 69 §4.2: which calendars the phone has - see [CalendarReport]; `null` = not
     * read yet (left out). Never an event, a title, a count or a time. Pinned by `calendar_status_report.json`
     * (`CalendarReportTest`). */
    val calendar: CalendarReport? = null,
    /** Design 76: the certificate check - the stored flag, this sync's chain as seen
     * and the outside sightings ([com.kidslauncher.mdm.server.ServerTlsReport]). Pinned by `server_tls_report.json`
     * (`ServerPinsTest`). */
    val serverTls: com.kidslauncher.mdm.server.ServerTlsReport? = null,
    /** Design 77 §7: what the message engine did - see [SmsState]; `null` = the engine
     * couldn't be read (left out). No content. Pinned by `sms_contract.json` (`SmsContractTest`). */
    val smsState: SmsState? = null,
    /** Design 94 §5: the roaming lock, where the phone roams, whether roaming data is
     * in use; `null` = telephony unreadable (left out). Pinned by `mobile_network.json` (`MobileNetworkTest`). */
    val dataRoaming: com.kidslauncher.mdm.server.DataRoamingReport? = null,
    /** Design 95 rev. 3: Android's mobile-data switch, why only Vibb uses cellular, the
     * mobile-network settings lock. Pinned by `mobile_network.json`. */
    val mobileData: com.kidslauncher.mdm.server.MobileDataReport? = null,
    /** Design 116 §3.1 (S-M2): the web filter's state at report time - `up`, `down` (with the period's length and id) or
     * `off`; `null` when it couldn't be read (left out). A full-report field. Pinned by `mobile_network.json`'s
     * `vpn_filter.reports` (`MobileNetworkTest`). */
    val vpnFilter: com.kidslauncher.mdm.server.VpnFilterReport? = null,
    /** Design 98 §5.1: Flymodus, the assistant role, Wi-Fi in airplane mode and the
     * periods. A full-report field (not in `status_heartbeat_fields`). Pinned by `airplane_mode.json`
     * (`AirplaneModeTest`). */
    val airplaneMode: com.kidslauncher.mdm.assistant.AirplaneModeReport? = null,
    /** Design 99 §6.1: Snarveier - posted now, hidden by the kid, the panel's keys. A
     * full-report field (not in `status_heartbeat_fields`). Pinned by `shortcuts.json` (`ShortcutsTest`). */
    val shortcuts: com.kidslauncher.mdm.shortcuts.ShortcutsReport? = null,
    /** Design 102 §6.2: the Wi-Fi sign-in window's handler, today's use and the last
     * sessions. A full-report field. Pinned by `login_windows.json` (`LoginWindowsTest`). */
    val captivePortal: com.kidslauncher.mdm.portal.CaptivePortalReport? = null,
    /** Design 102 §6.2: Chrome's login role on the phone. A full-report field. */
    val loginBrowser: com.kidslauncher.mdm.portal.LoginBrowserReport? = null,
    /** Design 107 §2: the app-wide switch and Vibb One's channels turned off;
     * `null` = unreadable (left out). A full-report field (not in `status_heartbeat_fields`). Pinned by
     * `notification_channels.json` (`NotificationChannelsTest`). */
    val notificationChannels: com.kidslauncher.mdm.notify.NotificationChannelsReport? = null,
    /** The apps with notification access besides Vibb One, each `off` while Vibb has it hidden or suspended; `null` =
     * unreadable (left out). A full-report field (not in `status_heartbeat_fields`). Pinned by
     * `notification_listeners.json` (`SystemReadersTest`). */
    val notificationListeners: List<com.kidslauncher.mdm.server.NotificationListenerReport>? = null,
    /** Design 115 §2.8: why the phone holds its app-push queue (`{"held": "no_network" | "none"}`), while UP is on and
     * the phone unlocked; left out otherwise. A full-report field. Pinned by `unified_push.json`'s `report` rows
     * (`UnifiedPushContractTest`). */
    val unifiedPush: com.kidslauncher.mdm.push.up.UpReport? = null,
    /** Design 110 §7.5: the map links' target, its state, the two steps and what reaches Vibb One. A full-report
     * field. Pinned by `map_links/contract.json`'s `report` rows (`MapLinksContractTest`). */
    val mapLinks: com.kidslauncher.mdm.map.MapLinksReport? = null,
    /** Design 114 §4.1: the setup readings no other field carries, and the hold's end while it lasts; built by
     * [com.kidslauncher.mdm.setup.SetupReport.json] (a reading Android refused is `null`, one that doesn't apply left
     * out). A full-report field. Pinned by `setup_steps.json`'s `report` rows (`SetupStepsTest`). */
    val setup: kotlinx.serialization.json.JsonObject? = null,
)

/**
 * `StatusReportRequest.calendar` (design 69 §4.2): whether the phone can read its calendar provider ([readable]:
 * `false` = no permission or the provider threw, then [calendars] is empty), each calendar it has, and the ids of the
 * parent's rules this launcher had to drop ([rulesSkipped]).
 */
@Serializable
data class CalendarReport(
    val readable: Boolean,
    val calendars: List<CalendarReportRow>,
    val rulesSkipped: List<Long>,
    /** Design 72 §4: each calendar link as this phone applied it; empty (left out) without one. Pinned by
     * `calendar_feeds.json`'s `report` (`CalendarFeedsTest`). */
    val feeds: List<CalendarFeedReportRow> = emptyList(),
)

/** One calendar link: its uid, the set's rev written last (`null` before the first), the series the provider
 * refused at the last pass, the series whose zone this phone's tz database doesn't know. */
@Serializable
data class CalendarFeedReportRow(
    val id: String,
    val appliedRev: String?,
    val refused: Int,
    val unknownZone: Int,
) {
    override fun toString() = "CalendarFeedReportRow(refused=$refused)"
}

/** One calendar: its key (16 hex, `day/CalendarKey.kt`), the sync app's account type, the display name (a label
 * only), the feed's host ([source], never its path, query, user or port; `null` = none - left out) and
 * `CALENDAR_ACCESS_LEVEL`. Never the account name. */
@Serializable
data class CalendarReportRow(
    val key: String,
    val accountType: String,
    val name: String,
    val source: String? = null,
    val accessLevel: Int,
) {
    override fun toString() = "CalendarReportRow($key)"
}

/**
 * `StatusReportRequest.zoneWatch` (design 64 step 2; no defaults: every field is always sent): the
 * platform fences registered now ([fences]; the server's 30-min floor on the Regularly interval
 * waits for `fences > 0`), whether the phone has a significant-motion sensor (without one the
 * heartbeat never skips), the system Location switch, the background-location grant, and the
 * mock fixes a release build ignored since the launcher's data was created - a running count, so a
 * lost report loses none (the server logs each rise). Counts and switches only, never a place.
 * [still] (qa-design-64-step2-code Q-3; `false` is left out): this report re-sends the cached fix
 * while the motion sensor says the phone hasn't moved since it (`reportStill`), so the server
 * counts "Here now" from this report - the fix's own time stays.
 */
@Serializable
data class ZoneWatchReport(
    val fences: Int,
    val motionSensor: Boolean,
    val locationOn: Boolean,
    val backgroundLocation: Boolean,
    val mockIgnored: Long,
    /** Design 131: the attempts in which Wi-Fi and the satellites disagreed, since the launcher's data was created - a
     * running count, at most one an attempt (the server writes a quiet security-log line from its rises). */
    val wifiDisagreed: Long,
    val still: Boolean = false,
    /** Design 138 D3: the connected network while the policy has zones (a named PSK/SAE network, unmetered, a readable
     * BSSID) - what the parent's Confirm binds to; left out otherwise. */
    val wifi: ZoneWifiReport? = null,
    /** Design 138 D7: the confirmations this phone struck that the policy still carries; left out when none. */
    val wifiStruck: List<Long> = emptyList(),
)

/** `ZoneWatchReport.wifi` (design 138 D3): the network's name, the BSSID and the AP's multi-link address when known. */
@Serializable
data class ZoneWifiReport(
    val ssid: String,
    val bssid: String,
    val mld: String? = null,
)

/** `StatusReportRequest.dnsUpstream` (no defaults: every field is always sent): the [rev] of the
 * servers in use, [inUse] `custom` or `backup`, since when (wall clock), and with `backup` why:
 * `bootstrap`, `connect`, `tls`, `http`, `refused` or `timeout`; in provider mode `rev` 0,
 * `provider`, no since and no reason (design 67 §3.2). In both, "DNS can't be reached" (wall
 * clock): ongoing [unreachableSinceMs] with no until, the last ended one both, none neither
 * ([outageTimes]). In provider mode [provider] is the one that answered last (`quad9`,
 * `cloudflare`; left out before any). Never an address. Pinned by `dns_upstream_status.json`,
 * in `contracts/core/`. */
@Serializable
data class DnsUpstreamReport(
    val rev: Long,
    val inUse: String,
    val sinceMs: Long?,
    val reason: String?,
    val unreachableSinceMs: Long?,
    val unreachableUntilMs: Long?,
    val provider: String? = null,
)

/**
 * `StatusReportRequest.battery` (design 35 §2.5; no defaults: every field is always sent): the
 * level in percent (0..100; the server drops the reading otherwise), whether the phone is charging
 * (plugged in, or charging/full), and Android's own "battery low" (`EXTRA_BATTERY_LOW`).
 */
@Serializable
data class BatteryReport(val level: Int, val charging: Boolean, val low: Boolean) {
    companion object {
        /**
         * The reading from the sticky `ACTION_BATTERY_CHANGED`'s extras (design 35 §2.5): the level
         * in percent, rounded and clamped to 0..100; charging when plugged in or the status is
         * charging/full; [low] is `EXTRA_BATTERY_LOW` as it is. `null` when the level or the scale is
         * missing (`-1`, a scale of 0 or less).
         */
        fun of(level: Int, scale: Int, status: Int, plugged: Int, low: Boolean): BatteryReport? {
            if (level < 0 || scale <= 0) return null
            return BatteryReport(
                level = (level * 100.0 / scale).roundToInt().coerceIn(0, 100),
                charging = plugged != 0 ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                low = low,
            )
        }
    }
}

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
 * `StatusReportRequest.lockState`: whether the lock (the glance as Android's face) is [active] (else [inactive] says
 * why: "no_pin", "unmanaged", "no_android_pin", "crash_guard" - design 132), [locked] now, and how often it stepped
 * aside for a call/the emergency dialer/an alarm since the last report; design 59's gate ([keyguard], [pinRev],
 * [resetToken], [keyguardRelocks]). Never unlock times or PIN material (privacy). Design 132: no `failures` or
 * `backoff_until_ms` (our keypad's wait went; Android's Gatekeeper holds its own).
 */
@Serializable
data class LockStateReport(
    val active: Boolean,
    val inactive: String?,
    val locked: Boolean,
    val exemptYields: Int,
    /** Design 17 (QA #11): allowed VoIP apps whose last ring had no full-screen intent - Android
     * dropped it (no USE_FULL_SCREEN_INTENT), so their calls can't ring over the lock. Package
     * names only; always sent. */
    val voipFsiDenied: List<String>,
    /** Design r7 §1.1.3: the apps whose ring the lock accepted while LOCKED (or that rang while the
     * phone locked) in the last 7 days, newest first, at most 8. Package names and times only;
     * always sent (`[]`). */
    val voipRang: List<VoipRang>,
    /** Design 59 §1: the gate - always `"android"` since design 132. Always sent (no default). */
    val keyguard: String,
    /** The server's `kid_lock.pin_rev` Android's PIN was set from. `null` = unknown (changed on the phone), not set
     * by us, or no Child PIN. Never PIN material. */
    val pinRev: Long?,
    /** Our reset token is active (Vibb One can change or clear Android's PIN). */
    val resetToken: Boolean,
    /** Times Vibb One locked Android's keyguard under its glance since the last report (59 §3.3). */
    val keyguardRelocks: Int,
)

/** One `lock_state.voip_rang` entry: `{"package": ..., "last_at_ms": ...}` (the server's keys). */
@Serializable
data class VoipRang(
    @SerialName("package") val packageName: String,
    val lastAtMs: Long,
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
    /** How far the phone's own clock is from the decisions' clock (wall minus trusted): 0 within five minutes, then
     * five-minute steps ([com.kidslauncher.mdm.server.reportedSkew]). `null` = left out (the runtime always sets it). */
    val clockSkewMs: Long? = null,
    /** How the decisions' clock knows its time: `server`, `gnss`, `carried` or `none` ([com.kidslauncher.mdm.server.ClockTrust]). */
    val clockTrust: String? = null,
    /** Since the last server or satellite time, in whole hours; `null` (left out) when there never was one. */
    val unanchoredMs: Long? = null,
    /** The phone's storage is full: the last write of the screen-time record or the policy's cache failed
     * ([com.kidslauncher.mdm.server.StorageWrites]). The count keeps enforcing; a restart loses the time used since
     * storage filled. Left out while false. */
    val storageFull: Boolean = false,
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
    /** Android's blocked-numbers list (`calls/BlockedNumbers.kt`, `contracts/core/blocked_numbers.json`): readable at all,
     * the rows the rule clears still left (a delete failed), the rows it keeps (the kid's own), what was cleared by class
     * since the install, and the last clearing (ISO-8601). Never a number. */
    val blockedReadable: Boolean? = null,
    val blockedLeft: Int? = null,
    val blockedKept: Int? = null,
    val blockedClearedContact: Int? = null,
    val blockedClearedEmergency: Int? = null,
    val blockedClearedAt: String? = null,
    /** Cleared by the first sweep after the phone became managed (the parent's switch): counted apart, never logged. */
    val blockedClearedInitial: Int? = null,
)

/** [CallState] with [report]'s `blocked_*` keys. */
fun CallState.withBlocked(report: com.kidslauncher.mdm.calls.BlockedReport): CallState = copy(
    blockedReadable = report.readable,
    blockedLeft = report.left,
    blockedKept = report.kept,
    blockedClearedContact = report.cleared[com.kidslauncher.mdm.calls.BlockedClass.CONTACT] ?: 0,
    blockedClearedEmergency = report.cleared[com.kidslauncher.mdm.calls.BlockedClass.EMERGENCY] ?: 0,
    blockedClearedAt = report.lastClearedMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
    blockedClearedInitial = report.clearedInitial,
)
