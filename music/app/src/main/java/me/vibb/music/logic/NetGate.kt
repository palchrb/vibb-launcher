package me.vibb.music.logic

/*
 * When the app may use the network (design 21 §4.5/§4.6, 21b §6): the launcher's design 13 gate
 * (launcher `server/AppDownloadPlan.kt` `downloadGate`), ported pure. A network with internet and a
 * physical transport, never roaming, NOT_METERED unless the parent's mobile-data switch is on.
 * Design 30 §1.1: two switches with the same formula - "streaming may use mobile data" for a stream
 * (an item that isn't on the phone), "downloads may use mobile data" for the download job (Storytel
 * books included). Downloads also wait while storage is low. A copy plays anywhere.
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

private fun networkAllows(caps: NetCaps?, mobileData: Boolean): Boolean =
    caps != null && caps.internet && caps.physical && caps.notRoaming && (mobileData || caps.notMetered)

/** May an item that isn't on the phone be streamed now ([streamMobileData]: the streaming switch)? */
fun streamAllowed(caps: NetCaps?, streamMobileData: Boolean): Boolean = networkAllows(caps, streamMobileData)

/** May the download job fetch now ([downloadMobileData]: the downloads switch)? Storage aside. */
fun downloadAllowed(caps: NetCaps?, downloadMobileData: Boolean): Boolean = networkAllows(caps, downloadMobileData)

/**
 * Must the sync stop a running download job (qa-30-s3a-code L1)? When the network doesn't allow
 * downloads now: the parent turned the downloads switch off while the phone is on mobile data. A job
 * is checked only between items and its running constraint is the old one, so a several-hundred-MB
 * book would finish over mobile data. On Wi-Fi a running job is never interrupted.
 */
fun stopRunningDownload(caps: NetCaps?, downloadMobileData: Boolean): Boolean = !downloadAllowed(caps, downloadMobileData)

/** Null when downloads may run now, else what they wait for. */
fun downloadWaiting(caps: NetCaps?, downloadMobileData: Boolean, storageLow: Boolean): Waiting? = when {
    storageLow -> Waiting.STORAGE
    caps != null && caps.internet && caps.physical && !caps.notRoaming -> Waiting.ROAMING
    !downloadAllowed(caps, downloadMobileData) -> Waiting.WIFI
    else -> null
}

/** How an item plays right now. */
enum class Playability { LOCAL, STREAM, NEEDS_NETWORK, GONE }

/**
 * Playback never waits for the network (vibb `STALE_OK`): a copy on the phone plays as it is; an
 * item without one streams only through the gate; a withdrawn item without a copy can't play.
 */
fun playability(hasCopy: Boolean, hasUrl: Boolean, caps: NetCaps?, streamMobileData: Boolean): Playability = when {
    hasCopy -> Playability.LOCAL
    !hasUrl -> Playability.GONE
    streamAllowed(caps, streamMobileData) -> Playability.STREAM
    else -> Playability.NEEDS_NETWORK
}
