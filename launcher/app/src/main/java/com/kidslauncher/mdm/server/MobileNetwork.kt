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

/** Design 97 §3.2: the policy's `abroad` is enforced, the report carries `data_roaming.where` and `mobile_data.kid`, and
 * the kid's Mobildata row shows with `quick_controls_mask` bit 32. */
const val MOBILE_NETWORK_CAPABILITY = "mobile_network_v2"

/** Design 97 §3.1: "I utlandet" as this launcher enforces it. [LEGACY] is an older server's `"phone"`: revision 3's rule,
 * no lock and no abroad sink. */
enum class AbroadChoice(val wire: String) {
    /** Ingen data: Android's roaming locked off, and the abroad sink for what the lock can't cover. */
    OFF("off"),

    /** Bare Vibb: abroad only Vibb uses mobile data (the abroad sink). */
    VIBB("vibb"),

    /** Som hjemme: mobile data abroad as at home. */
    HOME("home"),
    LEGACY("legacy"),
}

/** Design 97 §3.1, failing closed: the policy's `abroad` when this build knows it, `OFF` for any other value; with no
 * `abroad` (an older server) revision 3's `data_roaming` - `"phone"` or nothing is [AbroadChoice.LEGACY], anything else
 * `OFF`. */
fun abroadChoice(abroad: String?, dataRoaming: String?): AbroadChoice = when (abroad) {
    "off" -> AbroadChoice.OFF
    "vibb" -> AbroadChoice.VIBB
    "home" -> AbroadChoice.HOME
    null -> if (dataRoaming == null || dataRoaming == "phone") AbroadChoice.LEGACY else AbroadChoice.OFF
    else -> AbroadChoice.OFF
}

/** Design 97 §4.2: `DISALLOW_DATA_ROAMING` under Ingen data, lifted by the pause, "Åpne for oppsett" and the offline
 * override ([lift]). */
fun roamingLocked(choice: AbroadChoice, lift: Boolean): Boolean = choice == AbroadChoice.OFF && !lift

/** Design 97 §4.2: the abroad sink can arise - Ingen data or Bare Vibb, no lift. It also keeps a persisted sink on a
 * doubt about the network. */
fun abroadArmed(choice: AbroadChoice, lift: Boolean): Boolean =
    (choice == AbroadChoice.OFF || choice == AbroadChoice.VIBB) && !lift

/** Design 97 §4.1: where the phone is, on the default data SIM. */
enum class Where(val wire: String) {
    HOME("home"),
    ABROAD("abroad"),

    /** Airplane mode, no registered network, or out of service: neither home nor abroad. */
    UNKNOWN("unknown"),
}

/**
 * Design 97 §4.1 (Q97-H1, Q97-L3): `UNKNOWN` in airplane mode, with no registered network ([network], the network's
 * MCC+MNC, isn't 5-6 digits) or out of service ([inService] `false`: the network's name and the roaming flag can be stale
 * then - measured on the emulator, a cellular network kept up while out of service read `roaming=false` with the old
 * operator; `null`, unreadable, doesn't count); `ABROAD` on a 901 (ship or plane) network, roaming flag or not; with
 * both the SIM's and the network's country ISO known, `ABROAD` when they differ and `HOME` when they are equal (multi-MCC
 * countries, national roaming), roaming flag or not (code QA M2: carrier config can clear the flag for a partner network
 * abroad); otherwise `ABROAD` when roaming on another MCC than the SIM's (neither known fails toward abroad), else `HOME`.
 */
fun whereOf(
    airplane: Boolean,
    roaming: Boolean,
    network: String?,
    simOperator: String?,
    netCc: String?,
    simCc: String?,
    inService: Boolean? = null,
): Where {
    val net = network?.takeIf { it.length in 5..6 && it.all(Char::isDigit) }
    if (airplane || net == null || inService == false) return Where.UNKNOWN
    if (net.startsWith("901")) return Where.ABROAD
    val netCountry = netCc?.lowercase()?.takeIf { it.isNotEmpty() }
    val simCountry = simCc?.lowercase()?.takeIf { it.isNotEmpty() }
    // Code QA M2: the country decides when both are known, whatever the roaming flag says - carrier config can clear the
    // flag for a partner network abroad.
    if (netCountry != null && simCountry != null) return if (netCountry == simCountry) Where.HOME else Where.ABROAD
    if (!roaming) return Where.HOME
    val simMcc = simOperator?.take(3)?.takeIf { it.length == 3 && it.all(Char::isDigit) }
    return if (simMcc == net.take(3)) Where.HOME else Where.ABROAD
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
    /** [roamingLocked]: with a roaming data session up, that session outlived the lock (design 94 §2.4.1). */
    val roamingLocked: Boolean,
    /** Design 97: [abroadArmed]. */
    val abroadArmed: Boolean = false,
    /** Design 97 §4.3: the kid's Mobildata switch is Off while its row is shown (bit 32). No lift touches it (D4). */
    val kidOff: Boolean = false,
) {
    /** A configured sink reason that doesn't need the network to be named: what keeps a persisted sink on a doubt and
     * the pass shape up (design 97 §4.2, Q97-M2). */
    val anyArmed: Boolean get() = parentSink || abroadArmed || roamingLocked || kidOff
}

/** The best non-VPN network as the launcher sees it: cellular (not Wi-Fi or Ethernet), roaming (no
 * `NET_CAPABILITY_NOT_ROAMING`), and design 97's [abroad] ([whereOf] is `ABROAD`) and [unsure] (a cellular network while
 * [whereOf] is `UNKNOWN`: a doubt, as no network is - [applyShape]). `null` = none. */
data class BestNet(val cellular: Boolean, val roaming: Boolean, val abroad: Boolean = false, val unsure: Boolean = false)

/** Why the sink shape is wanted now; [wire] is the report's `mobile_data.sink`. */
enum class SinkReason(val wire: String) {
    OFF("off"),

    /** The parent's Bare Vibb, on cellular. */
    PARENT("parent"),

    /** Design 97: abroad under Ingen data or Bare Vibb - under Ingen data a 901 network Android doesn't count as roaming
     * (the lock can't cover it) or a session from before the lock. */
    ABROAD("abroad"),

    /** Design 95 §6 item 2 (d): a roaming data session that outlived the roaming lock - only Vibb uses it until Android
     * tears it down. With the lock on, roaming data that is up can only be such a session: a new one is refused. Since
     * design 97 only a national-roaming one, as the abroad reason comes first. */
    ROAMING("roaming"),

    /** Design 97 §4.3: the kid's own Off. */
    KID("kid"),
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

/** Design 97 §4.2: on cellular only, the first reason that holds - the one order [applyShape] and [reportedSink] use.
 * Code QA L8: a roaming network that can't be placed ([BestNet.unsure]) counts as abroad while the abroad sink is armed -
 * fail closed until telephony reads again. */
fun sinkReason(inputs: VpnInputs, net: BestNet?): SinkReason = when {
    net?.cellular != true -> SinkReason.OFF
    inputs.parentSink -> SinkReason.PARENT
    inputs.abroadArmed && (net.abroad || (net.unsure && net.roaming)) -> SinkReason.ABROAD
    inputs.roamingLocked && net.roaming -> SinkReason.ROAMING
    inputs.kidOff -> SinkReason.KID
    else -> SinkReason.OFF
}

/** The one shape rule: the sink whenever a reason holds, else the filter when it is on, else the pass shape while the
 * parent's Bare Vibb can still start a sink, else no VPN. */
fun vpnShape(inputs: VpnInputs, net: BestNet?): VpnShape = when {
    sinkReason(inputs, net) != SinkReason.OFF -> VpnShape.SINK
    inputs.filterOn -> VpnShape.FILTER
    // Code QA M2 (design 95 rev. 2's rule): any reason that can arise keeps the watcher up - a roaming session that
    // outlived the lock survives a stay on Wi-Fi ("mobile data always active") and is sunk the moment Wi-Fi drops; design
    // 97 adds abroad and the kid's Off.
    inputs.anyArmed -> VpnShape.PASS
    else -> VpnShape.NONE
}

/**
 * `apply()`'s decision ([vpnShape] with its own reading of the network). When it can't see a network, or sees a cellular
 * one it can't place ([BestNet.unsure], design 97: out of service), but the shape last decided ([persisted], by
 * [KidVpnService]'s watcher) is the sink and a sink reason is still configured, it keeps the sink: `apply()` never writes
 * lockdown off on a doubt about the network, only on a change of the inputs (a pause, the parent's choice) or a network
 * it can see and place.
 */
fun applyShape(inputs: VpnInputs, net: BestNet?, persisted: VpnShape?): VpnShape =
    if ((net == null || net.unsure) && persisted == VpnShape.SINK && inputs.anyArmed) {
        VpnShape.SINK
    } else {
        vpnShape(inputs, net)
    }

/**
 * [KidVpnService]'s decision (code QA H1): the same rule as `apply()`'s ([applyShape]), with the network its watcher saw
 * last ([watched]) or, before the first callback, the one it reads now ([live]). So a start with no network yet (a
 * boot, airplane mode) keeps the sink [persisted] by the last decision instead of writing lockdown off.
 */
fun serviceShape(inputs: VpnInputs, watched: BestNet?, live: BestNet?, persisted: VpnShape?): VpnShape =
    applyShape(inputs, watched ?: live, persisted)

/** The sink's reason worth remembering for [reportedSink]'s `held`: the sink decided with a cellular network seen and
 * placed; `null` otherwise (keep what is stored). */
fun sinkReasonToHold(inputs: VpnInputs, net: BestNet?, shape: VpnShape): SinkReason? =
    if (shape == VpnShape.SINK && net != null && !net.unsure) sinkReason(inputs, net).takeIf { it != SinkReason.OFF } else null

/** How often the watcher reads telephony again for the same network with the same capabilities (design 97 code QA L7). */
const val TELEPHONY_REREAD_MS = 10_000L

/** Code QA L7: the watcher reads telephony ([MobileNetworkRuntime.bestNetOf]) for a new network or a change in what
 * [BestNet] is made of ([sameCaps] false), and otherwise at most every [TELEPHONY_REREAD_MS] - a cellular network's
 * frequent bandwidth and signal updates don't each cost the binder calls, while a country change without a capability
 * change is still seen within that time. */
fun readsTelephony(sameCaps: Boolean, sinceLastReadMs: Long): Boolean = !sameCaps || sinceLastReadMs !in 0 until TELEPHONY_REREAD_MS

/** The always-on decision written before `establish(wanted)`: lockdown off first when leaving for a non-sink shape;
 * nothing before the sink's tunnel (design 95 §2.2). */
fun alwaysOnBeforeEstablish(wanted: VpnShape): VpnShape? = wanted.takeIf { it != VpnShape.SINK }

/** The always-on decision written after `establish(wanted)`: the sink's lockdown whether or not its tunnel came up -
 * code QA M1: a failed switch to the sink fails closed (lockdown over the old tunnel or none cuts every other app), and
 * the decision `sink` is recorded for every later reader. */
fun alwaysOnAfterEstablish(wanted: VpnShape): VpnShape? = wanted.takeIf { it == VpnShape.SINK }

/** A failed `establish()` toward the sink is retried by the service itself (5 s doubling to 5 min). */
fun retriesFailedEstablish(wanted: VpnShape, established: Boolean): Boolean = !established && wanted == VpnShape.SINK

/** [reason] is still configured: what keeps a held sink naming it (design 97 code QA L1). */
fun SinkReason.stillArmed(inputs: VpnInputs): Boolean = when (this) {
    SinkReason.OFF -> false
    SinkReason.PARENT -> inputs.parentSink
    SinkReason.ABROAD -> inputs.abroadArmed
    SinkReason.ROAMING -> inputs.roamingLocked
    SinkReason.KID -> inputs.kidOff
}

/**
 * The report's `mobile_data.sink` (code QA L7): with no network seen (or one that can't be placed), a sink that the last
 * decision holds ([persisted]) names the reason it was decided for with a network seen ([held], design 97 code QA L1:
 * a kid's Off at home stays `kid` through a tunnel, not the first armed `abroad`) while that reason is still configured;
 * else its configured reason in [sinkReason]'s order (design 97 Q97-M2); otherwise [sinkReason].
 */
fun reportedSink(inputs: VpnInputs, net: BestNet?, persisted: VpnShape?, held: SinkReason? = null): SinkReason {
    if ((net != null && !net.unsure) || persisted != VpnShape.SINK) return sinkReason(inputs, net)
    if (held != null && held.stillArmed(inputs)) return held
    return when {
        inputs.parentSink -> SinkReason.PARENT
        inputs.abroadArmed -> SinkReason.ABROAD
        inputs.roamingLocked -> SinkReason.ROAMING
        inputs.kidOff -> SinkReason.KID
        else -> SinkReason.OFF
    }
}

/** Design 97 §4.3: the kid's Mobildata row. */
enum class KidMobileRow(val wire: String) {
    /** Bit 32 off: no row. */
    HIDDEN("hidden"),

    /** The parent decides now: Off, 50 %, "Bestemt av mamma og pappa". */
    PARENT("parent"),

    /** Android's own mobile data is off: Off, 50 %, "Mobildata er slått av på telefonen". */
    PHONE_OFF("phone_off"),
    ON("on"),

    /** The kid's Off: "Bare Vibb One bruker mobildata. Wi-Fi virker som før." */
    OFF("off"),
}

/**
 * Design 97 §4.3's table: no row without bit 32 ([shown]); the parent decides while no [lift] runs and the parent's Bare
 * Vibb is chosen ([parentVibb], unlifted) or the phone is abroad under Ingen data or Bare Vibb; then Android's mobile data
 * off ([dataEnabled] `false`); else the kid's own switch.
 */
fun kidMobileRow(
    shown: Boolean,
    kidOff: Boolean,
    lift: Boolean,
    parentVibb: Boolean,
    where: Where,
    choice: AbroadChoice,
    dataEnabled: Boolean?,
): KidMobileRow = when {
    !shown -> KidMobileRow.HIDDEN
    !lift && (parentVibb || (where == Where.ABROAD && (choice == AbroadChoice.OFF || choice == AbroadChoice.VIBB))) ->
        KidMobileRow.PARENT
    dataEnabled == false -> KidMobileRow.PHONE_OFF
    kidOff -> KidMobileRow.OFF
    else -> KidMobileRow.ON
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

/** How often one subscription is relocked per process at most (code QA L5: a wait that timed out may try once more). */
const val RELOCK_TRIES = 2

/**
 * Design 94 §2.4.2, the relock: the subscriptions to clear and set the roaming restriction again for - those that
 * read roaming on while it should be locked (only an absent per-SIM key can, on a multi-SIM phone), each at most
 * [RELOCK_TRIES] times per process ([tries]; a relock whose clear was applied in time counts as all of them).
 */
fun dataRoamingRelock(locked: Boolean, readings: Map<Int, Boolean?>, tries: Map<Int, Int>): Set<Int> =
    if (!locked) emptySet() else readings.filter { (sub, on) -> on == true && (tries[sub] ?: 0) < RELOCK_TRIES }.keys

/** Design 94 §5: `data_roaming` in every report of a launcher with [DATA_ROAMING_CAPABILITY]. */
@Serializable
data class DataRoamingReport(
    /** `hasUserRestriction(DISALLOW_DATA_ROAMING)`, as Android has it now. */
    val locked: Boolean,
    /** `isNetworkRoaming()` on the default data SIM; `false` with no SIM or no service. */
    val roaming: Boolean,
    /** The network's country, only while [roaming] or abroad ([roamingPlace]); never a home location. */
    val country: String? = null,
    /** The network's MCC+MNC, only while [roaming], abroad or on a 901 (ship or plane) network. */
    val network: String? = null,
    /** The data SIM's `isDataRoamingEnabled()`, `null` when unreadable. */
    val enabled: Boolean? = null,
    /** Roaming data is in use now: the best network is cellular without `NOT_ROAMING`. */
    @SerialName("data_active") val dataActive: Boolean,
    /** `getActiveModemCount()` (design 95 §6 item 3), `null` when unreadable. */
    val modems: Int? = null,
    /** Design 97 §4.1: [Where.wire], [whereOf] on the default data SIM. Always sent. */
    @SerialName("where") val whereIs: String,
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
    /** Design 97 §3.2: the kid's own switch, `"on"` or `"off"`; `null` while its row is hidden. Always sent. */
    val kid: String?,
)

/** Design 94 §5: `country` and `network` only while roaming or abroad ([away]: design 97 code QA M2, a foreign network
 * Android doesn't flag as roaming is named too, so the notice names its country), `network` also on a 901 network; `""`
 * reads as unknown. Never a home location. */
fun roamingPlace(away: Boolean, country: String?, network: String?): Pair<String?, String?> {
    val net = network?.takeIf { it.isNotEmpty() }
    return Pair(
        country?.lowercase()?.takeIf { away && it.isNotEmpty() },
        net?.takeIf { away || it.startsWith("901") },
    )
}
