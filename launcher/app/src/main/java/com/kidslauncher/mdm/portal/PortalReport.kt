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
    val today: PortalToday,
    /** The last 10 openings, oldest first. */
    val sessions: List<PortalSession>,
)

/** Today's code-free openings and their milliseconds, and the grants used today. */
@Serializable
data class PortalToday(
    val openings: Int,
    val ms: Long,
    val grantUsed: Int,
)

/** One opening of the window (§6.2). */
@Serializable
data class PortalSession(
    val openedMs: Long,
    val closedMs: Long?,
    /** The SSID when Android gave it, else `null`. */
    val network: String?,
    val firstHost: String?,
    /** `validated`, `passes`, `closed`, `closed_by_vibb`, `timeout`, `budget` or `lost`. */
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
    /** With `failed`: `package`, `write`, `readback` or `absent`. */
    val reason: String?,
    /** When Chrome's data was cleared (D13), once. */
    val clearedMs: Long?,
)
