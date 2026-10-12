package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * The last relock's findings (design 24b §1.7): what the phone reset when a parent-PIN window
 * (override, pause) locked again, and what it couldn't undo. Sent in every status report until a
 * newer relock replaces it. [trigger] `timer | lock_again | command | sync | boot | screen_on |
 * settings`; [windows] `override`, `pause` or both; the lists are valid package names, at most 8
 * each; [blockedSigners] covers [blocked]; [failures] names from the fixed step set (`RelockFailure`). No defaults: every key is always
 * sent (the literal is pinned in PolicyResponseCompatTest and on the server). Also how
 * WindowRelock stores it.
 */
/**
 * One entry of the status report's `relock_blocks` (fix of 2026-10-12): a package in
 * `relock_blocked` now - hidden whatever the allowlist until "Allow these apps" - with its signing
 * key now ([signer], SHA-256 lower-case hex, `null` = unreadable), why ([reason]: `outside_play |
 * other_key | catalog_key | unknown`), where it was set ([found]: `relock | install`) and when
 * ([atMs], `null` = unknown). Every key always sent; pinned in PolicyResponseCompatTest and on the
 * server.
 */
@Serializable
data class RelockBlockReport(
    val packageName: String,
    val signer: String?,
    val reason: String,
    val found: String,
    val atMs: Long?,
)

@Serializable
data class RelockReport(
    val atMs: Long,
    val trigger: String,
    val windows: List<String>,
    val proxiesCleared: Int,
    val proxyLeft: Boolean,
    val caCertsRemoved: Int,
    val credentialRemoved: Boolean,
    val credentialLeft: Boolean,
    val blocked: List<String>,
    /** Each blocked app's signing key (SHA-256 of its first certificate, lower-case hex), for the
     * PWA's "Allow these apps" (fix round QA Medium 2). */
    val blockedSigners: Map<String, String>,
    val accessibilityEnabled: List<String>,
    val adminsEnabled: List<String>,
    val appsDisabled: List<String>,
    val notificationListeners: List<String>,
    val ownNotificationsOff: Boolean,
    val failures: List<String>,
)
