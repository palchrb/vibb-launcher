package com.kidslauncher.mdm.logins

/** The login role's package now, from the last accepted policy (`null` while off or unreadable): the lists keep it off
 * Home and the drawer in every state, a pause included (design 102 §4.1, [loginRoleListable]). */
fun loginRolePackageNow(): String? = try {
    val policy = com.kidslauncher.mdm.server.currentPolicyDecision().policy
    loginRolePackage(loginRoleOf(policy?.loginBrowser, policy?.allowlist))
} catch (e: Exception) {
    null
}
