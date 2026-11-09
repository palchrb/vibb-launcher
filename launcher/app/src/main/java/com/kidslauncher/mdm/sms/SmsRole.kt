package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RoleAction
import com.kidslauncher.mdm.server.KNOWN_SMS_PACKAGES

/*
 * Who should be the phone's default SMS app (design 77 D1) - pure, tested in SmsRoleTest; AppEnforcer applies it with
 * DevicePolicyManager.setDefaultSmsApplication (device owner, no prompt) and checks RoleManager afterwards, since the
 * call returns silently when it fails (design 09 A1). Shaped like the dialer's ([com.kidslauncher.mdm.calls.dialerRoleAction]).
 */

/** `RoleManager.ROLE_SMS`, literal to stay Android-free. */
const val ROLE_SMS = "android.app.role.SMS"

/** The engine's capability in the status report: the server's per-channel `sms_mode` and the SMS keys (design 77 §7). */
const val SMS_ENGINE_CAPABILITY = "sms_engine_v1"

/**
 * D1: "Styrt av Vibb" takes the role; "Ikke styrt" hands back a role we took ([takenByUs]); "Av" and unknown rules
 * ([mode] `null`) keep what is held and never take it - `DISALLOW_SMS` stops everything in "Av", the history stays, and
 * a hidden Google Messages never gets a role it can't use. The calls channel never touches it, nor the other way round.
 */
fun smsRoleAction(mode: ChannelMode?, roleHeld: Boolean, takenByUs: Boolean): RoleAction = when {
    mode == ChannelMode.MANAGED -> if (roleHeld) RoleAction.NONE else RoleAction.TAKE
    mode == ChannelMode.UNMANAGED && roleHeld && takenByUs -> RoleAction.RELEASE
    else -> RoleAction.NONE
}

/**
 * Whom the role goes back to (design 09 A6): the holder before we took it, if it is still installed and isn't us,
 * else the first installed of [KNOWN_SMS_PACKAGES] (Google Messages first), else nobody (`null`: the role stays ours
 * and the error is reported).
 */
fun smsRoleReturnTo(previous: String?, ownPackage: String, installed: Set<String>): String? =
    previous?.takeIf { it != ownPackage && it in installed }
        ?: KNOWN_SMS_PACKAGES.firstOrNull { it in installed && it != ownPackage }

/**
 * `DISALLOW_CONFIG_DEFAULT_APPS` while either of our roles is held for a channel we manage - so neither the kid nor
 * another app's role request takes the phone or SMS app away (task 14). Cleared before either role changes.
 */
fun lockDefaultAppsFor(callsManaged: Boolean, dialerHeld: Boolean, smsMode: ChannelMode?, smsRoleHeld: Boolean): Boolean =
    (callsManaged && dialerHeld) || (smsMode == ChannelMode.MANAGED && smsRoleHeld)

/** The SMS permissions every other app is denied in "Styrt av Vibb" (design 77 §2.5, §8.3): a message the engine
 * holds or blocks must not reach anyone else. */
val SMS_PERMISSIONS = setOf(
    "android.permission.RECEIVE_SMS",
    "android.permission.RECEIVE_MMS",
    "android.permission.RECEIVE_WAP_PUSH",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
)

/** Google Play services - a system app holding RECEIVE_SMS (§8.6). */
const val PLAY_SERVICES = "com.google.android.gms"

/**
 * §8.6, the user 2026-11-09: in "Styrt av Vibb" Google Play services loses its SMS access (its SMS User Consent API
 * could show a stranger's text, its Retriever hands a message to an app). Step 0 check: what breaks on the Jelly Star
 * when GMS's RECEIVE_SMS is denied (phone-number verification, Find My Device, Play's account checks) - if something
 * essential does, this switch goes off and the fallback comes back to the user.
 */
const val DENY_PLAY_SERVICES_SMS = true

/**
 * The packages whose [SMS_PERMISSIONS] are denied while SMS is "Styrt av Vibb" ([managed]): every third-party app in
 * [requested] that asks for one (never us), and Play services when [denyPlayServices] and it asks for one. Out of
 * "Styrt av Vibb" each goes back to DEFAULT (no revoke - a revoke kills the app). Returns package -> its requested SMS
 * permissions.
 */
fun smsPermissionTargets(
    requested: Map<String, Collection<String>>,
    playServicesRequested: Collection<String>?,
    ownPackage: String,
    denyPlayServices: Boolean = DENY_PLAY_SERVICES_SMS,
): Map<String, List<String>> {
    val targets = requested.filterKeys { it != ownPackage }
        .mapValues { (_, perms) -> perms.filter { it in SMS_PERMISSIONS } }
        .filterValues { it.isNotEmpty() }
        .toMutableMap()
    if (denyPlayServices && playServicesRequested != null) {
        playServicesRequested.filter { it in SMS_PERMISSIONS }.takeIf { it.isNotEmpty() }?.let { targets[PLAY_SERVICES] = it }
    }
    return targets
}

/**
 * D16 "The push, at once": a hold asks for a sync so the row is posted now, but at most one a minute (QA R2-M4) - fifty
 * holds are one sync. [lastRequestAt] is the last request's elapsed-realtime instant (`null` = none yet). Returns the
 * delay before the next request (0 = now).
 */
fun heldSyncDelay(lastRequestAt: Long?, now: Long): Long =
    if (lastRequestAt == null || now - lastRequestAt >= HELD_SYNC_INTERVAL_MS || now < lastRequestAt) 0 else lastRequestAt + HELD_SYNC_INTERVAL_MS - now

const val HELD_SYNC_INTERVAL_MS = 60_000L
