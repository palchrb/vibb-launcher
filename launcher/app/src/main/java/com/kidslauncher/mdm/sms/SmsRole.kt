package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RoleAction
import com.kidslauncher.mdm.server.GRANT_STATE_DEFAULT
import com.kidslauncher.mdm.server.GRANT_STATE_DENIED
import com.kidslauncher.mdm.server.GRANT_STATE_GRANTED
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

/** Design 80 D13: the phone honours `call_policy.sms_groups` and reports `sms_state.own_learned`. */
const val SMS_GROUPS_CAPABILITY = "sms_groups_v1"

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

/**
 * Whether the other apps' SMS permissions are denied for the SMS channel's [mode] (`null` = the rules unknown): in
 * "Styrt av Vibb", and with the rules unknown - D15 keeps the role ours and `DISALLOW_SMS` off then, so every message
 * still reaches the platform's `SMS_RECEIVED` fan-out, and "the other apps' permission denials still hold" (QA M1). In
 * "Av" `DISALLOW_SMS` stops everything; "Ikke styrt" lets every app work.
 */
fun smsPermissionsDenied(mode: ChannelMode?): Boolean = mode == ChannelMode.MANAGED || mode == null

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

/** One grant-state write of the SMS permission pass (`DevicePolicyManager.PERMISSION_GRANT_STATE_*`). */
data class GrantWrite(val packageName: String, val permission: String, val state: Int)

/** [smsPermissionPass]'s answer: the writes in order, and the record to keep ("package|permission"). */
data class SmsPermissionPass(val writes: List<GrantWrite>, val record: Set<String>)

/**
 * The other apps' SMS permissions for [targets] (package -> its requested SMS permissions), QA L8: `DEFAULT` after
 * `DENIED` only clears the policy fix - a grant the app had stays revoked. So a denial ([deny] for the package)
 * remembers each permission that was granted before ([granted], read before the write; one already in [recorded] stays
 * remembered), and leaving the denial writes `GRANTED` then `DEFAULT` for those - the grant back, the policy fix gone -
 * and only `DEFAULT` for the rest. The record holds the denied ones only.
 */
fun smsPermissionPass(
    targets: Map<String, List<String>>,
    deny: (String) -> Boolean,
    granted: (String, String) -> Boolean,
    recorded: Set<String>,
): SmsPermissionPass {
    val writes = mutableListOf<GrantWrite>()
    val record = mutableSetOf<String>()
    for ((pkg, permissions) in targets) {
        for (permission in permissions) {
            val key = "$pkg|$permission"
            if (deny(pkg)) {
                if (key in recorded || granted(pkg, permission)) record += key
                writes += GrantWrite(pkg, permission, GRANT_STATE_DENIED)
            } else {
                if (key in recorded) writes += GrantWrite(pkg, permission, GRANT_STATE_GRANTED)
                writes += GrantWrite(pkg, permission, GRANT_STATE_DEFAULT)
            }
        }
    }
    return SmsPermissionPass(writes, record)
}

/**
 * D16 "The push, at once": a hold asks for a sync so the row is posted now, but at most one a minute (QA R2-M4) - fifty
 * holds are one sync. [lastRequestAt] is the last request's elapsed-realtime instant (`null` = none yet). Returns the
 * delay before the next request (0 = now).
 */
fun heldSyncDelay(lastRequestAt: Long?, now: Long): Long =
    if (lastRequestAt == null || now - lastRequestAt >= HELD_SYNC_INTERVAL_MS || now < lastRequestAt) 0 else lastRequestAt + HELD_SYNC_INTERVAL_MS - now

const val HELD_SYNC_INTERVAL_MS = 60_000L

/** The notification's Reply (QA L1): both ids in the data URI, which a PendingIntent's fill-in can't change. The ids are
 * a ULID and a UUID - nothing to escape. */
fun replyData(conversationId: String, clientId: String): String = "$REPLY_PREFIX$conversationId/$clientId"

/** The conversation and `clientId` of a Reply's data URI ([replyData]); `null` for anything else. */
fun replyIds(data: String?): Pair<String, String>? {
    val rest = data?.removePrefix(REPLY_PREFIX)?.takeIf { it != data } ?: return null
    val parts = rest.split('/')
    if (parts.size != 2 || parts.any { it.isEmpty() || !it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' } }) return null
    return parts[0] to parts[1]
}

private const val REPLY_PREFIX = "vibb-sms://reply/"

/**
 * D7's budget for the receive path (classify, judge, commit, `finish()`): an `SMS_DELIVER` is a foreground broadcast,
 * about 10 s before an ANR kills the Home, phone and Device Owner process. QA L7: every receive is timed from the
 * broadcast's arrival; one over this is recorded (its time only) and counted. What keeps it short: the receivers'
 * own executor, the rules from memory, and engine-thread transactions of one statement or one row (no walks).
 */
const val RECEIVE_BUDGET_MS = 3_000L

fun overReceiveBudget(tookMs: Long): Boolean = tookMs > RECEIVE_BUDGET_MS
