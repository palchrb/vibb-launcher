package com.kidslauncher.mdm.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The round "Mobilnett" (design 94, design 95 revision 3): data roaming locked or the phone's choice, "Bare Vibb" (the
 * VPN's sink shape on cellular) and the mobile-network settings lock. The pure rules live here; the Android side is
 * [MobileNetworkRuntime] and [KidVpnService]. Pinned by the shared contract `mobile_network.json`
 * (`MobileNetworkTest`).
 */

/** Design 94: the policy's `data_roaming` is enforced and `data_roaming` is in every report. */
const val DATA_ROAMING_CAPABILITY = "data_roaming_v1"

/** Design 95 rev. 3: the policy's `apps_mobile` (Bare Vibb) and `mobile_settings_locked` are enforced, and
 * `mobile_data` is in every report. */
const val MOBILE_DATA_CAPABILITY = "mobile_data_v1"

/**
 * Design 94 §3: `DISALLOW_DATA_ROAMING` is set. An older server (`null`) and `"phone"` leave it to the phone; `"off"`,
 * and any value this build doesn't know (`"eea"` of a later round), lock it - fail closed. The pause, "Åpne for
 * oppsett" and the offline override ([overrideActive]) lift it, as every restriction.
 */
fun dataRoamingLocked(value: String?, overrideActive: Boolean): Boolean = when (value) {
    null, "phone" -> false
    else -> !overrideActive
}

/** Design 95 rev. 3 item 3: `DISALLOW_CONFIG_MOBILE_NETWORKS` while the parent's switch is on, lifted by the pause. An
 * older server's missing key (`null`) sets nothing. */
fun mobileSettingsLocked(value: Boolean?, overrideActive: Boolean): Boolean = value == true && !overrideActive

/** The parent's "Mobildata for apper: Bare Vibb" in force: `"vibb"`, and fail closed on a value this build doesn't
 * know; `"on"` and an older server's `null` don't. Lifted by the pause (D3). */
fun parentSinkOn(appsMobile: String?, overrideActive: Boolean): Boolean = when (appsMobile) {
    null, "on" -> false
    else -> !overrideActive
}

/** What the VPN's shape is decided from, besides the network: the parent's settings with the lifts applied. */
data class VpnInputs(
    /** The Web filter is on (the policy's `vpn_filter_enabled`, no lift running). */
    val filterOn: Boolean,
    /** [parentSinkOn]. */
    val parentSink: Boolean,
    /** [dataRoamingLocked]: with a roaming data session up, that session outlived the lock (design 94 §2.4.1). */
    val roamingLocked: Boolean,
)

/** The best non-VPN network as the launcher sees it: cellular (not Wi-Fi or Ethernet), and roaming
 * (no `NET_CAPABILITY_NOT_ROAMING`). `null` = none. */
data class BestNet(val cellular: Boolean, val roaming: Boolean)

/** Why the sink shape is wanted now; [wire] is the report's `mobile_data.sink`. */
enum class SinkReason(val wire: String) {
    OFF("off"),

    /** The parent's Bare Vibb, on cellular. */
    PARENT("parent"),

    /** Design 95 §6 item 2 (d): a roaming data session that outlived the roaming lock - only Vibb uses it until Android
     * tears it down. With the lock on, roaming data that is up can only be such a session: a new one is refused. */
    ROAMING("roaming"),
}

/** [KidVpnService]'s shapes (design 95 §2.2). [wire] is the persisted decision. */
enum class VpnShape(val wire: String) {
    /** No VPN: the filter is off and no sink reason can arise. */
    NONE("none"),

    /** Today's DNS filter: one route to the fake DNS server. */
    FILTER("filter"),

    /** Everything but Vibb into the tunnel: DNS `REFUSED`, TCP reset, the rest dropped; lockdown on. */
    SINK("sink"),

    /** No routes, no DNS server: nothing changes for apps, but the service and its network watcher stay up so the sink
     * can start the moment Wi-Fi drops (the filter off, the parent's Bare Vibb on). */
    PASS("pass"),
    ;

    companion object {
        fun of(wire: String?): VpnShape? = entries.firstOrNull { it.wire == wire }
    }
}

fun sinkReason(inputs: VpnInputs, net: BestNet?): SinkReason = when {
    net?.cellular != true -> SinkReason.OFF
    inputs.parentSink -> SinkReason.PARENT
    inputs.roamingLocked && net.roaming -> SinkReason.ROAMING
    else -> SinkReason.OFF
}

/** The one shape rule: the sink whenever a reason holds, else the filter when it is on, else the pass shape while the
 * parent's Bare Vibb can still start a sink, else no VPN. */
fun vpnShape(inputs: VpnInputs, net: BestNet?): VpnShape = when {
    sinkReason(inputs, net) != SinkReason.OFF -> VpnShape.SINK
    inputs.filterOn -> VpnShape.FILTER
    inputs.parentSink -> VpnShape.PASS
    else -> VpnShape.NONE
}

/**
 * `apply()`'s decision ([vpnShape] with its own reading of the network). When it can't see a network but the shape
 * last decided ([persisted], by [KidVpnService]'s watcher) is the sink and a sink reason is still configured, it keeps
 * the sink: `apply()` never writes lockdown off on a doubt about the network, only on a change of the inputs (a pause,
 * the parent's choice) or a network it can see.
 */
fun applyShape(inputs: VpnInputs, net: BestNet?, persisted: VpnShape?): VpnShape =
    if (net == null && persisted == VpnShape.SINK && (inputs.parentSink || inputs.roamingLocked)) {
        VpnShape.SINK
    } else {
        vpnShape(inputs, net)
    }

/** What `setAlwaysOnVpnPackage` is given: our package or none, and lockdown. The lockdown allow list stays empty (D4:
 * neither the music app nor Play services goes around the sink); the VPN's owner is exempt by itself. */
data class AlwaysOn(val ownPackage: Boolean, val lockdown: Boolean)

/**
 * Design 95 §2.2, the single owner of always-on: every writer ([KidVpnService]'s shape switch, `apply()`, the cold
 * start) writes exactly this for the shape it decided and persisted, so nothing writes lockdown off while the decided
 * shape is the sink. Lockdown only in the sink: with only the filter's route it would cut every other app off
 * (`AppEnforcer`'s note, measured again in design 95 §2.3 c1).
 */
fun alwaysOnFor(shape: VpnShape): AlwaysOn = when (shape) {
    VpnShape.NONE -> AlwaysOn(ownPackage = false, lockdown = false)
    VpnShape.SINK -> AlwaysOn(ownPackage = true, lockdown = true)
    VpnShape.FILTER, VpnShape.PASS -> AlwaysOn(ownPackage = true, lockdown = false)
}

/** Design 95 §2.2: `DISALLOW_CONFIG_VPN` with any shape - the filter (security review S-L11), the sink and the pass
 * shape that stands ready for it - so Settings can't switch lockdown off or disconnect the VPN. */
fun vpnConfigLocked(shape: VpnShape): Boolean = shape != VpnShape.NONE

/**
 * Design 94 §2.4.2, the relock: the subscriptions to clear and set the roaming restriction again for - those that
 * read roaming on while it should be locked (only an absent per-SIM key can, on a multi-SIM phone), at most once per
 * subscription per boot ([alreadyRelocked]).
 */
fun dataRoamingRelock(locked: Boolean, readings: Map<Int, Boolean?>, alreadyRelocked: Set<Int>): Set<Int> =
    if (!locked) emptySet() else readings.filter { (sub, on) -> on == true && sub !in alreadyRelocked }.keys

/** Design 94 §5: `data_roaming` in every report of a launcher with [DATA_ROAMING_CAPABILITY]. */
@Serializable
data class DataRoamingReport(
    /** `hasUserRestriction(DISALLOW_DATA_ROAMING)`, as Android has it now. */
    val locked: Boolean,
    /** `isNetworkRoaming()` on the default data SIM; `false` with no SIM or no service. */
    val roaming: Boolean,
    /** The network's country, only while [roaming]; never a home location. */
    val country: String? = null,
    /** The network's MCC+MNC, only while [roaming] or on a 901 (ship or plane) network. */
    val network: String? = null,
    /** The data SIM's `isDataRoamingEnabled()`, `null` when unreadable. */
    val enabled: Boolean? = null,
    /** Roaming data is in use now: the best network is cellular without `NOT_ROAMING`. */
    @SerialName("data_active") val dataActive: Boolean,
    /** `getActiveModemCount()` (design 95 §6 item 3), `null` when unreadable. */
    val modems: Int? = null,
)

/** Design 95 rev. 3: `mobile_data` in every report of a launcher with [MOBILE_DATA_CAPABILITY]. */
@Serializable
data class MobileDataReport(
    /** Android's own mobile-data switch on the default data SIM (`isDataEnabled()`), `null` when unreadable. */
    val enabled: Boolean?,
    /** [SinkReason.wire]: why only Vibb uses mobile data now. */
    val sink: String,
    /** `hasUserRestriction(DISALLOW_CONFIG_MOBILE_NETWORKS)`, as Android has it now. */
    @SerialName("settings_locked") val settingsLocked: Boolean,
)

/** Design 94 §5: `country` and `network` only while roaming, `network` also on a 901 network; `""` reads as unknown. */
fun roamingPlace(roaming: Boolean, country: String?, network: String?): Pair<String?, String?> {
    val net = network?.takeIf { it.isNotEmpty() }
    return Pair(
        country?.lowercase()?.takeIf { roaming && it.isNotEmpty() },
        net?.takeIf { roaming || it.startsWith("901") },
    )
}
