package com.kidslauncher.mdm.server

/*
 * Our own runtime permissions that a role (dialer) grants us too (handy step 9, B2 with
 * qa-09-design.md #6). When the role is handed back, the platform revokes what the role granted -
 * which kills our process (HOME, kiosk, the call path) - but it skips POLICY_FIXED grants. So
 * every such permission we request is granted by the device owner (POLICY_FIXED) before the role
 * changes, decided on the DPM grant state: once the role has granted a permission,
 * checkSelfPermission says "granted" and the old self-grant skipped it, leaving it role-owned.
 * Pure, tested in OwnPermissionsTest.
 */

/** `DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED`. */
const val GRANT_STATE_GRANTED = 1

/**
 * Runtime permissions the dialer role grants its holder (roles.xml ROLE_DIALER: phone, call log,
 * contacts, SMS, microphone, camera, notifications) - only the ones that can matter to us are
 * listed; [ownPermissionsToFix] intersects with what our manifest requests.
 */
val ROLE_GRANTED_PERMISSIONS = setOf(
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.CALL_PHONE",
    "android.permission.ANSWER_PHONE_CALLS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.POST_NOTIFICATIONS",
)

/** The permissions to fix: those we request that a role would otherwise own. */
fun ownPermissionsToFix(requested: Collection<String>): Set<String> = requested.filterTo(sortedSetOf()) { it in ROLE_GRANTED_PERMISSIONS }

/**
 * Whether to (re)grant [dpmGrantState] by policy - only the DPM state counts; [selfGranted]
 * (`checkSelfPermission`) is deliberately ignored, it's true for a role-owned grant too.
 */
@Suppress("UNUSED_PARAMETER")
fun shouldFixOwnPermission(dpmGrantState: Int, selfGranted: Boolean): Boolean = dpmGrantState != GRANT_STATE_GRANTED

/**
 * After the dialer role is handed back (calls unmanaged, role not held), the permissions we fixed
 * go back to DEFAULT (qa-09-code #4): that clears POLICY_FIXED without revoking (no kill), so the
 * user can revoke them again. Only when we fixed them ([fixedByUs]) - other self-grants (camera
 * for the QR scanner) are left alone on phones whose calls were never managed.
 */
fun shouldResetOwnPermissions(callsManaged: Boolean, dialerRoleHeld: Boolean, fixedByUs: Boolean): Boolean =
    fixedByUs && !callsManaged && !dialerRoleHeld

/**
 * Bring Home to front after a role change in kiosk (B2), only when the role really changed hands
 * in this pass - never on every apply of a TAKE/RELEASE that keeps failing - and never during a
 * call (it would cover the in-call screen, an emergency call too) (qa-09-code #3).
 */
fun bringHomeAfterRoleChange(heldBefore: Boolean, heldAfter: Boolean, kioskOn: Boolean, inCall: Boolean): Boolean =
    heldBefore != heldAfter && kioskOn && !inCall

/** `DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA`. */
const val KEYGUARD_DISABLE_SECURE_CAMERA = 2

/**
 * The keyguard-disabled features to set (B2): the secure-keyguard camera off while the phone is
 * managed, ORed with whatever else is set (never clearing other bits); our bit cleared again when
 * unmanaged. Only helps with a screen lock: without a PIN the camera gesture opens the normal
 * camera, which only the runbook (gesture off before enrolling) stops.
 */
fun keyguardDisabledFeatures(current: Int, managed: Boolean): Int =
    if (managed) current or KEYGUARD_DISABLE_SECURE_CAMERA else current and KEYGUARD_DISABLE_SECURE_CAMERA.inv()
