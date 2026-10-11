package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * The last relock's findings (design 24b §1.7): what the phone reset when a parent-PIN window
 * (override, pause) locked again, and what it couldn't undo. Sent in every status report until a
 * newer relock replaces it. [trigger] `timer | lock_again | command | sync | boot | screen_on |
 * settings`; [windows] `override`, `pause` or both; the lists are valid package names, at most 8
 * each; [failures] names from the fixed step set (`RelockFailure`). No defaults: every key is always
 * sent (the literal is pinned in PolicyResponseCompatTest and on the server). Also how
 * WindowRelock stores it.
 */
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
    val accessibilityEnabled: List<String>,
    val adminsEnabled: List<String>,
    val appsDisabled: List<String>,
    val notificationListeners: List<String>,
    val ownNotificationsOff: Boolean,
    val failures: List<String>,
)
