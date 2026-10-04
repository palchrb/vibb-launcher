package com.kidslauncher.mdm.server.dto

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
 * [com.kidslauncher.mdm.ui.quickcontrols.QuickControlsActivity].
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
 * them.
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
)
