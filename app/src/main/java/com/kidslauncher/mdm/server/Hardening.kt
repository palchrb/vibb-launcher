package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.HardeningPolicy

/*
 * Phone hardening (design docs/design/04-hardening.md in the handy workspace): which Android user
 * restrictions to set. Pure, no Android imports (HardeningTest); AppEnforcer maps each
 * [HardeningRestriction] to its `UserManager` constant and applies the plan.
 */

/** One server switch. [defaultOn] applies when the server doesn't send the switch. */
enum class HardeningRestriction(val defaultOn: Boolean) {
    /** `DISALLOW_FACTORY_RESET` - Settings only; a recovery-mode wipe can't be blocked. */
    FACTORY_RESET(true),
    /** `DISALLOW_ADD_USER`. */
    ADD_USER(true),
    /** `DISALLOW_MODIFY_ACCOUNTS`. */
    MODIFY_ACCOUNTS(true),
    /** `DISALLOW_CONFIG_VPN` - set after the always-on VPN, see AppEnforcer. */
    CONFIG_VPN(true),
    /** `DISALLOW_USB_FILE_TRANSFER` - MTP/PTP, not adb. */
    USB_FILE_TRANSFER(true),
    /** `DISALLOW_DEBUGGING_FEATURES` - no adb, so no adb recovery for a broken launcher either. */
    DEBUGGING_FEATURES(true),
    /** `DISALLOW_SAFE_BOOT` - off by default until checked on the phone. Safe mode bypasses the
     * launcher but repairs nothing (restrictions and suspension persist there). */
    SAFE_BOOT(false),
    /** `DISALLOW_CONFIG_LOCATION`, with location turned on first (Find my device). */
    CONFIG_LOCATION(true),
    /** `DISALLOW_AIRPLANE_MODE` - off by default: airplane mode stays allowed (travel). */
    AIRPLANE_MODE(false),
    /** `DISALLOW_SET_WALLPAPER` (design 08 §3): only the launcher (device owner, exempt) sets
     * the wallpaper the parent allowed and the kid picked. **Launcher-only** - there is no server
     * switch ([value] is always null), so it is simply on while managed and cleared when
     * unmanaged (after WallpaperApplier.resetIfOurs put navy back); like every hardening
     * restriction the override and the pause don't lift it. */
    SET_WALLPAPER(true),
}

data class HardeningPlan(
    /** Every [HardeningRestriction], set (`true`) or cleared (`false`). */
    val restrictions: Map<HardeningRestriction, Boolean>,
) {
    /** Turn location on before locking its setting. */
    val forceLocationOn: Boolean get() = restrictions[HardeningRestriction.CONFIG_LOCATION] == true

    fun isSet(restriction: HardeningRestriction): Boolean = restrictions[restriction] == true
}

private fun HardeningPolicy?.value(restriction: HardeningRestriction): Boolean? = when (restriction) {
    HardeningRestriction.FACTORY_RESET -> this?.disallowFactoryReset
    HardeningRestriction.ADD_USER -> this?.disallowAddUser
    HardeningRestriction.MODIFY_ACCOUNTS -> this?.disallowModifyAccounts
    HardeningRestriction.CONFIG_VPN -> this?.disallowConfigVpn
    HardeningRestriction.USB_FILE_TRANSFER -> this?.disallowUsbFileTransfer
    HardeningRestriction.DEBUGGING_FEATURES -> this?.disallowDebuggingFeatures
    HardeningRestriction.SAFE_BOOT -> this?.disallowSafeBoot
    HardeningRestriction.CONFIG_LOCATION -> this?.lockLocation
    HardeningRestriction.AIRPLANE_MODE -> this?.disallowAirplaneMode
    HardeningRestriction.SET_WALLPAPER -> null
}

/**
 * [managed] ([hardeningManaged]): each restriction as the server's switch says, or its default
 * when the switch is missing. Not managed (never had a policy, or the server unmanaged the phone):
 * everything cleared, so setup and adb provisioning work. There is deliberately no
 * `overrideActive` parameter: the offline override and the pause never lift these - only an
 * explicit server value does (the second exception to the "every restriction is liftable" rule,
 * after the call rules).
 */
fun hardeningPlan(policy: HardeningPolicy?, managed: Boolean): HardeningPlan =
    HardeningPlan(
        HardeningRestriction.entries.associateWith { restriction ->
            managed && (policy.value(restriction) ?: restriction.defaultOn)
        }
    )

/** Managed for hardening = the enforced policy has an allowlist (fresh, cached or the fallback
 * plan - an override doesn't change which policy is enforced) or calls are managed. */
fun hardeningManaged(allowlist: List<String>?, callsManaged: Boolean): Boolean =
    allowlist != null || callsManaged
