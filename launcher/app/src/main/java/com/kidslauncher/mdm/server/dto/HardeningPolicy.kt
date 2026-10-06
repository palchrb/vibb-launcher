package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * `PolicyResponse.hardening` - see kid-phone-server's `Hardening` (migration `0023_hardening`).
 * Our server always sends every field explicitly; a missing object or field (an upstream or older
 * server) means that switch's default ([com.kidslauncher.mdm.server.HardeningRestriction.defaultOn]),
 * so a server that doesn't know about a switch fails toward the restriction, not away from it.
 * Applied only while the phone is managed, and never lifted by the override or pause
 * ([com.kidslauncher.mdm.server.hardeningPlan]).
 */
@Serializable
data class HardeningPolicy(
    val disallowFactoryReset: Boolean? = null,
    val disallowAddUser: Boolean? = null,
    val disallowModifyAccounts: Boolean? = null,
    val disallowConfigVpn: Boolean? = null,
    val disallowUsbFileTransfer: Boolean? = null,
    val disallowDebuggingFeatures: Boolean? = null,
    val disallowSafeBoot: Boolean? = null,
    val lockLocation: Boolean? = null,
    val disallowAirplaneMode: Boolean? = null,
    val disallowConfigLocale: Boolean? = null,
)
