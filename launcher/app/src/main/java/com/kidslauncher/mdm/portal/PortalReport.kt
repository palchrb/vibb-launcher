package com.kidslauncher.mdm.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Design 102 §6.2: the status report's `captive_portal`, a full-report field. Every
 * field without a default, so each one is sent; pinned by `login_windows.json`'s `report` (`LoginWindowsTest`). */
@Serializable
data class CaptivePortalReport(
    /** Who handles Android's sign-in intent: `vibb`, `system` (Part A off) or `other`. */
    val handler: String,
    /** The phone has a parent code (Advanced's warning when it hasn't). */
    val parentCodeSet: Boolean,
    /** The last 10 openings, oldest first. */
    val sessions: List<PortalSession>,
)

/** One opening of the window (§6.2). */
@Serializable
data class PortalSession(
    val openedMs: Long,
    val closedMs: Long?,
    /** The SSID when Android gave it, else `null`. */
    val network: String?,
    val firstHost: String?,
    /** `validated`, `passes`, `closed`, `closed_by_vibb`, `timeout` or `lost`. */
    val outcome: String,
    /** The registrable domains of the main frames, in order, at most 20; [visitedCount] the true count. */
    val visited: List<String>,
    val visitedCount: Int,
    val blockedHosts: List<String>,
    val blockedFrames: List<String>,
    /** `system` (the notification), `vibb` (the Wi-Fi page or Home) or `revisit`. */
    val start: String,
    /** `code`, `grant`, `pause` or `none`. */
    val approved: String,
)

/** Design 102 §6.2: the status report's `login_browser`. */
@Serializable
data class LoginBrowserReport(
    @SerialName("package") val packageName: String?,
    /** `on`, `off`, `absent` or `failed`. */
    val state: String,
    val versionName: String?,
    val writtenMs: Long?,
    /** With `failed`: `package`, `write`, `readback`, `absent` or `role` (design 119: Vibb One isn't the browser). */
    val reason: String?,
    /** When Chrome's data was cleared (D13), once. */
    val clearedMs: Long?,
    /** Design 119 §7 (no defaults: every key is encoded, the contract's `report`): Vibb One holds Android's browser
     * role; `null` while the link gate is off. */
    val browserRole: Boolean?,
    /** The hosts the link gate (or Meldinger) stopped: the last 10 of 7 days, a host only. */
    val refused: List<com.kidslauncher.mdm.logins.RefusedHost>,
    /** The last login window: its end and whether Vibb's session held, no host. */
    val lastWindow: com.kidslauncher.mdm.logins.LastWindow?,
)
