package com.kidslauncher.mdm.calls

/*
 * Who should be the phone's default dialer - pure, tested in DialerRoleTest. AppEnforcer applies
 * the answer with DevicePolicyManager.setDefaultDialerApplication (device owner only).
 */

enum class RoleAction { TAKE, RELEASE, NONE }

/**
 * While calls are managed (or the rules are unknown) we must hold the dialer role: only the
 * default dialer's screening and in-call services see every call. Unmanaged, we hand it back to
 * the system dialer - but only if we took it ([takenByUs]), so a parent who chose our dialer by
 * hand keeps it, and an old server (no `call_policy`) never leaves the phone with our dialer.
 * The override and pause keep the role: they never open calls.
 */
fun dialerRoleAction(state: CallPolicyState, roleHeld: Boolean, takenByUs: Boolean): RoleAction = when {
    state.managed -> if (roleHeld) RoleAction.NONE else RoleAction.TAKE
    roleHeld && takenByUs -> RoleAction.RELEASE
    else -> RoleAction.NONE
}

/** How often HomeActivity may show the system's "make this the default phone app" prompt when the
 * device-owner call didn't work (the parent accepts it on the phone). */
const val ROLE_PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L

fun shouldPromptForRole(state: CallPolicyState, roleHeld: Boolean, nowMs: Long, lastPromptMs: Long): Boolean =
    state.managed && !roleHeld && (nowMs - lastPromptMs >= ROLE_PROMPT_INTERVAL_MS || nowMs < lastPromptMs)

/**
 * `DISALLOW_CONFIG_DEFAULT_APPS` once our roles are in place while calls are managed, so the kid
 * can't switch the default phone app back in Settings (task 14). [rolesHeld]: the dialer AND the
 * call-redirection role - not before, because the restriction may also block the system's role
 * prompt we fall back to [device check]. Cleared before the dialer role is handed back.
 */
fun lockDefaultApps(state: CallPolicyState, rolesHeld: Boolean): Boolean = state.managed && rolesHeld
