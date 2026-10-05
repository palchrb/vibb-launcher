package com.kidslauncher.mdm.server.dto

import com.kidslauncher.mdm.timerules.TimePolicy
import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/devices/policy`. [allowlist] null means unmanaged ("no restriction");
 * `[]` means nothing is allowed (see [com.kidslauncher.mdm.server.computeEnforcementPlan]). Minute
 * fields are minutes-since-midnight, same representation [com.kidslauncher.mdm.server.KidModeEnforcer]
 * already expects - null or a matching start/end means "no restriction" for that window.
 * [kioskDesired] is the server-authoritative kiosk switch: the admin site sets it, the device
 * applies it automatically on its next sync - there is no on-device way to change it.
 * [lockTaskFeatures] is the raw bitmask for `DevicePolicyManager.setLockTaskFeatures` (0 = every
 * system-chrome feature disabled while pinned, matching this app's previous hardcoded behavior).
 * [overridePinHash]/[overridePinSalt] back the offline override PIN (see [com.kidslauncher.mdm.ui.LockActivity])
 * - both null means no PIN is configured for this device.
 * [quickControlsMask] is the raw bitmask for which switches show up on the launcher's
 * swipe-left-from-home "Quick Controls" screen (1 = WiFi, 2 = Bluetooth, 4 = brightness) - see
 * [com.kidslauncher.mdm.ui.kidsettings.KidSettingsActivity].
 * [pendingCommand] is Find My Device's remote-command queue (ring/lock/wipe) - see
 * [com.kidslauncher.mdm.server.LocateCommands] and [MdmSyncWorker]'s dispatch of it.
 * [dnsFilterVersion]/[dnsUpstreamProvider] are the on-device DNS filtering fields - see
 * [com.kidslauncher.mdm.server.DnsFilterEngine]. The standalone-Tailscale-app fields
 * (`requireTailscale`/`tailscaleExitNodeId`) and the DoT-to-Pi Private DNS field
 * (`forcePrivateDnsToPi`) that used to live here are gone along with the code that read them - see
 * this repo's CLAUDE.md for the on-device-filtering/embedded-tsnet migration this was part of.
 * [vpnFilterEnabled] is a per-device admin toggle for [com.kidslauncher.mdm.server.KidVpnService]
 * itself (not a blocklist/domain setting) - see [com.kidslauncher.mdm.server.AppEnforcer.applyVpnRestrictions].
 * Defaults true; a parent can turn off ad/content filtering for a specific kid's device entirely.
 * [packagesToUninstall] are packages the admin unchecked in the "Apps to install" list while they
 * were still on the device - [MdmSyncWorker] uninstalls each silently (Device Owner privilege, no
 * confirmation dialog) on every sync where this is non-empty; the server clears an entry once a
 * later status report confirms the package is actually gone, not on any client-side acknowledgement.
 * [callPolicy] is the calls & SMS rules ([CallPolicy]); `null` only from a server that predates
 * them. [hardening] is the phone-hardening switches ([HardeningPolicy]); `null` (older server)
 * means every switch's default. [launcherUi] is the parent's language and home-grid choice
 * ([LauncherUi]); `null` (older server) means the defaults. [timePolicy] is the named time rules,
 * daily budget and the parent's lifts (handy step 6); `null` from an older server, whose fixed
 * windows above are then converted ([com.kidslauncher.mdm.server.KidModeEnforcer.timePolicyOf]).
 * Once a phone has had one, a response without it is rejected ([com.kidslauncher.mdm.server.judgeFresh]).
 * The fixed windows are still sent (frozen) for older launchers. [locationPolicy]: when location
 * goes into the status report; `null` (older server) keeps the old every-sync behaviour.
 * [push]: FCM on the server and whether it works for this phone (handy step 7).
 */
@Serializable
data class PolicyResponse(
    val allowlist: List<String>? = null,
    val weekdayStartMinutes: Int? = null,
    val weekdayEndMinutes: Int? = null,
    val weekendStartMinutes: Int? = null,
    val weekendEndMinutes: Int? = null,
    val bedtimeStartMinutes: Int? = null,
    val bedtimeEndMinutes: Int? = null,
    val kioskDesired: Boolean = false,
    val lockTaskFeatures: Long = 0,
    val overridePinHash: String? = null,
    val overridePinSalt: String? = null,
    val quickControlsMask: Long = 0,
    val pendingCommand: PendingCommand? = null,
    val vpnFilterEnabled: Boolean = true,
    val dnsFilterVersion: String? = null,
    val dnsUpstreamProvider: String = "cloudflare",
    val packagesToUninstall: List<String> = emptyList(),
    val callPolicy: CallPolicy? = null,
    val hardening: HardeningPolicy? = null,
    val launcherUi: LauncherUi? = null,
    val timePolicy: TimePolicy? = null,
    val locationPolicy: LocationPolicy? = null,
    /** How sync nudges reach this phone (handy step 7) - see [PushPolicy]; `null` from a server
     * without FCM support, which means the SSE stream. */
    val push: PushPolicy? = null,
    /** `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` in kiosk (handy step 9) - our server
     * always sends it; the per-device off switch is the remote kill switch (QA 09 #4). Missing
     * (a server without migration 0029) = **off** (qa-09-code #7): such a server has no off
     * switch, and without the bit kiosk keeps AOSP's system-dialer exemption - emergency and
     * the kill switch win over the extra lockdown. */
    val blockActivityStart: Boolean = false,
)

/**
 * `PolicyResponse.push`: [fcmEnabled] the server has an FCM service account; [fcmOk] its test
 * nudges to [fcmTokenHash]'s token were acknowledged by our syncs (FCM health, decided on the
 * server); [fcmTokenHash] the first 16 hex chars of SHA-256 over the token it stores (`null` = it
 * knows none). See [com.kidslauncher.mdm.push.decidePushTransport].
 */
@Serializable
data class PushPolicy(
    val fcmEnabled: Boolean = false,
    val fcmOk: Boolean = false,
    val fcmTokenHash: String? = null,
)

/** `PolicyResponse.locationPolicy`: [mode] "off", "on_request" or "interval" (every
 * [intervalMinutes]); see [com.kidslauncher.mdm.server.locationAction]. */
@Serializable
data class LocationPolicy(
    val mode: String = "on_request",
    val intervalMinutes: Int = 30,
)

/** `PolicyResponse.launcherUi` - see kid-phone-server's `LauncherUi`. [language] is "system", "nb"
 * or "en" ([com.kidslauncher.mdm.ui.resolveLauncherLocale]); [homeColumns] 3 or 4
 * ([com.kidslauncher.mdm.ui.home.gridColumns]). Anything else falls back to the default. */
@Serializable
data class LauncherUi(
    val language: String = "system",
    val homeColumns: Int = 3,
    /** The wallpapers the parent allows on this phone, in order (design 08-ui-polish.md);
     * missing from an older server = only the built-in navy
     * ([com.kidslauncher.mdm.ui.wallpaper.effectiveWallpaper]). */
    val wallpapers: List<PolicyWallpaper> = emptyList(),
)

/** One entry of `launcher_ui.wallpapers` - see kid-phone-server's `PolicyWallpaper`. Checked by
 * [com.kidslauncher.mdm.ui.wallpaper.parseWallpapers]; anything it can't use is left out. */
@Serializable
data class PolicyWallpaper(
    val id: Long = 0,
    /** "color", "gradient" or "image". */
    val kind: String = "",
    /** "#RRGGBB": one for a colour, two for a gradient. */
    val colors: List<String> = emptyList(),
    /** SHA-256 of the image (`GET api/devices/wallpapers/{hash}`). */
    val image: String? = null,
    val label: String = "",
    /** "navy", "forest", ... for the built-ins (labelled in the kid's language). */
    val builtinKey: String? = null,
    /** An image also goes on the lock screen; otherwise the lock screen gets navy (QA 08 #1). */
    val lockScreen: Boolean = false,
)
