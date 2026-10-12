package me.vibb.music.logic

/*
 * When the app may use the network (design 21 §4.5/§4.6, 21b §6): the launcher's design 13 gate
 * (launcher `server/AppDownloadPlan.kt` `downloadGate`), ported pure. A network with internet and a
 * physical transport, never roaming, NOT_METERED unless the parent's mobile-data switch is on.
 * Streams and downloads follow the same rule; downloads also wait while storage is low.
 */

/**
 * What the default network offers (`ConnectivityManager.getNetworkCapabilities(activeNetwork)`):
 * through the launcher's VPN its capabilities carry the underlying transport and meteredness.
 */
data class NetCaps(
    val internet: Boolean,
    val notMetered: Boolean,
    val notRoaming: Boolean,
    /** Wi-Fi, cellular or ethernet among the transports. */
    val physical: Boolean,
)

/** Why downloads wait - the status report's `waiting` (21b §4.3); [LOGIN] for a Storytel entry (design 26b §4.5). */
enum class Waiting(val wire: String) { WIFI("wifi"), STORAGE("storage"), ROAMING("roaming"), LOGIN("login") }

/** May an item that isn't on the phone be streamed now? */
fun streamAllowed(caps: NetCaps?, mobileData: Boolean): Boolean =
    caps != null && caps.internet && caps.physical && caps.notRoaming && (mobileData || caps.notMetered)

/** Null when downloads may run now, else what they wait for. */
fun downloadWaiting(caps: NetCaps?, mobileData: Boolean, storageLow: Boolean): Waiting? = when {
    storageLow -> Waiting.STORAGE
    caps != null && caps.internet && caps.physical && !caps.notRoaming -> Waiting.ROAMING
    !streamAllowed(caps, mobileData) -> Waiting.WIFI
    else -> null
}

/** How an item plays right now. */
enum class Playability { LOCAL, STREAM, NEEDS_NETWORK, GONE }

/**
 * Playback never waits for the network (vibb `STALE_OK`): a copy on the phone plays as it is; an
 * item without one streams only through the gate; a withdrawn item without a copy can't play.
 */
fun playability(hasCopy: Boolean, hasUrl: Boolean, caps: NetCaps?, mobileData: Boolean): Playability = when {
    hasCopy -> Playability.LOCAL
    !hasUrl -> Playability.GONE
    streamAllowed(caps, mobileData) -> Playability.STREAM
    else -> Playability.NEEDS_NETWORK
}
