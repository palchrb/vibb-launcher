package me.vibb.music.bridge

/*
 * The launcher's managed config for the music app (design 21 §2): written by the device owner,
 * read here. Pure; BridgeTest.
 */

import me.vibb.music.logic.ControlSlots
import me.vibb.music.logic.controlSlots
import me.vibb.music.model.Entry

/** The launcher's two build names (release, debug). */
val LAUNCHER_PACKAGES = listOf("me.vibb.launcher", "me.vibb.launcher.debug")

/** The bridge service's class in the launcher (its Kotlin namespace stays `com.kidslauncher.mdm`). */
const val BRIDGE_SERVICE = "com.kidslauncher.mdm.music.MusicBridgeService"

/** The launcher's nudge: carries nothing, only starts the sync. */
const val ACTION_BRIDGE_CHANGED = "me.vibb.music.action.BRIDGE_CHANGED"

/** `reportState`'s Bundle keys (bridge_contract.json, compared with the launcher's). */
const val REPORT_KEY = "report"
const val HELD_FILES_KEY = "held_files"
const val HELD_SHA256_KEY = "held_sha256"

/** `setNowPlaying`'s Bundle keys. */
object NowPlayingKeys {
    const val ENTRY = "entry"
    const val ITEM = "item"
    const val POSITION_MS = "position_ms"
    const val DURATION_MS = "duration_ms"
    const val PLAYING = "playing"
    const val ART = "art"

    /**
     * Design 50 §1.1 (appended): which pair the lock tile shows - [CONTROLS_SKIP] (15 s back / 30 s on) or
     * [CONTROLS_MUSIC] (previous / next) - the decision (`controlSlots`), never the kind, so the launcher needs no
     * kind logic.
     */
    const val CONTROLS = "controls"
    const val CONTROLS_SKIP = "skip"
    const val CONTROLS_MUSIC = "music"
}

/** The `controls` word of [NowPlayingKeys] for [entry] (design 50 §1.1): `controlSlots`' decision. */
fun nowPlayingControls(entry: Entry): String =
    if (controlSlots(entry) == ControlSlots.SKIP) NowPlayingKeys.CONTROLS_SKIP else NowPlayingKeys.CONTROLS_MUSIC

/** The managed config's keys. */
object RestrictionKeys {
    const val V = "v"
    const val MANAGED = "managed"
    const val LAUNCHER_PACKAGE = "launcher_package"
    const val LANGUAGE = "language"
    const val MOBILE_DATA = "mobile_data"
    const val LIBRARY_VERSION = "library_version"
    const val STORYTEL_GENERATION = "storytel_generation"

    /** The parent's volume cap in force (design 26a §5.3): 1..99 %, 0 = none. */
    const val VOLUME_CAP_PCT = "volume_cap_pct"

    /** Design 30 §1.3: the streaming switch (a launcher from design 30 step 2 on writes it). */
    const val STREAM_MOBILE_DATA = "stream_mobile_data"

    /** Design 30 §1.3: the downloads switch, Storytel books included. */
    const val DOWNLOAD_MOBILE_DATA = "download_mobile_data"

    /** Design 30 §1.8: the parent's music storage limit in GB, 0 = none. */
    const val STORAGE_LIMIT_GB = "storage_limit_gb"
}

/** The limit without a word from the launcher (an older one): 2 GB (design 30 §1.3, §1.8). */
const val DEFAULT_STORAGE_LIMIT_GB = 2

/** The largest limit read; anything above is junk, read as [DEFAULT_STORAGE_LIMIT_GB] (the launcher's bound). */
const val MAX_STORAGE_LIMIT_GB = 1000

data class BridgeConfig(
    /** The launcher manages this phone (v1 config present). */
    val managed: Boolean,
    /** The launcher to bind: one of [LAUNCHER_PACKAGES] (and signed like this app). */
    val launcherPackage: String?,
    /** "nb", "en" or "" (follow the phone). */
    val language: String,
    /** May an item that isn't on the phone stream off Wi-Fi (design 30 §1.1)? Never while roaming. */
    val streamMobileData: Boolean,
    /** May the download job run off Wi-Fi, Storytel books included (design 30 §1.1)? Never while roaming. */
    val downloadMobileData: Boolean,
    /** The library's version; "" = nothing ticked on this phone (an empty library). */
    val libraryVersion: String?,
    val storytelGeneration: Long,
    /**
     * The parent's volume cap in force, 1..99 % (the launcher's `capPctInForce`: lifted by its
     * override and pause), or null: a Sonos room's volume our own sets never exceed (design 26a §5.3).
     */
    val volumeCapPct: Int? = null,
    /** The parent's music storage limit in GB (design 30 §1.8), null = no limit. */
    val storageLimitGb: Int? = DEFAULT_STORAGE_LIMIT_GB,
) {
    companion object {
        val UNMANAGED = BridgeConfig(false, null, "", false, false, null, 0)
    }
}

/** The config from the restrictions' values (`RestrictionsManager.getApplicationRestrictions`). */
fun bridgeConfig(values: Map<String, Any?>): BridgeConfig {
    if ((values[RestrictionKeys.V] as? Int) != 1 || values[RestrictionKeys.MANAGED] != true) return BridgeConfig.UNMANAGED
    return BridgeConfig(
        managed = true,
        launcherPackage = (values[RestrictionKeys.LAUNCHER_PACKAGE] as? String)?.takeIf { it in LAUNCHER_PACKAGES },
        language = (values[RestrictionKeys.LANGUAGE] as? String)?.takeIf { it in setOf("nb", "en") }.orEmpty(),
        streamMobileData = mobileSwitch(values, RestrictionKeys.STREAM_MOBILE_DATA),
        downloadMobileData = mobileSwitch(values, RestrictionKeys.DOWNLOAD_MOBILE_DATA),
        libraryVersion = values[RestrictionKeys.LIBRARY_VERSION] as? String,
        storytelGeneration = (values[RestrictionKeys.STORYTEL_GENERATION] as? Int)?.toLong() ?: 0,
        volumeCapPct = (values[RestrictionKeys.VOLUME_CAP_PCT] as? Int)?.takeIf { it in 1..99 },
        storageLimitGb = storageLimit(values),
    )
}

/**
 * One of design 30's two switches ([key]): when the launcher wrote it, `true` is on and anything else
 * off (as every restriction); when it didn't (a launcher from before design 30), the one old switch
 * `mobile_data`, which such a launcher sets for both (design 30 §1.3).
 */
fun mobileSwitch(values: Map<String, Any?>, key: String): Boolean =
    if (key in values) values[key] == true else values[RestrictionKeys.MOBILE_DATA] == true

/**
 * The parent's storage limit in GB (design 30 §1.3): an Int 1..[MAX_STORAGE_LIMIT_GB] is that many,
 * 0 is no limit (null); absent (an older launcher) or anything else is [DEFAULT_STORAGE_LIMIT_GB].
 */
fun storageLimit(values: Map<String, Any?>): Int? = when (val gb = values[RestrictionKeys.STORAGE_LIMIT_GB]) {
    is Int -> when (gb) {
        0 -> null
        in 1..MAX_STORAGE_LIMIT_GB -> gb
        else -> DEFAULT_STORAGE_LIMIT_GB
    }
    else -> DEFAULT_STORAGE_LIMIT_GB
}

/**
 * May the app bind [pkg]? Only a launcher build name, and only signed with this app's own
 * certificate ([sameSigner], `PackageManager.hasSigningCertificate`) - another signer is refused
 * (21 §2), on top of the launcher's signature permission.
 */
fun launcherAllowed(pkg: String?, sameSigner: (String) -> Boolean): Boolean =
    pkg != null && pkg in LAUNCHER_PACKAGES && sameSigner(pkg)

/** The library the app holds should be emptied: managed, and the launcher says nothing is ticked. */
fun nothingTicked(config: BridgeConfig): Boolean = config.managed && config.libraryVersion == ""

/** An empty `v: 1` library (nothing ticked on this phone). */
const val EMPTY_LIBRARY = """{"v":1,"version":"","categories":[],"entries":[],"files":[],"covers":[]}"""

/** The listings to ask the launcher for: its version differs from the one applied (any version - hashes have no order). */
fun listingsWanted(launcherVersions: Map<Long, String?>, applied: Map<Long, String>): List<Long> =
    launcherVersions.filter { (id, v) -> v != null && applied[id] != v }.keys.sorted()
