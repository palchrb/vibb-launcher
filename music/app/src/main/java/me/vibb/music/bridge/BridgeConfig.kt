package me.vibb.music.bridge

/*
 * The launcher's managed config for the music app (design 21 §2): written by the device owner,
 * read here. Pure; BridgeTest.
 */

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
}

/** The managed config's keys. */
object RestrictionKeys {
    const val V = "v"
    const val MANAGED = "managed"
    const val LAUNCHER_PACKAGE = "launcher_package"
    const val LANGUAGE = "language"
    const val MOBILE_DATA = "mobile_data"
    const val LIBRARY_VERSION = "library_version"
    const val STORYTEL_GENERATION = "storytel_generation"
}

data class BridgeConfig(
    /** The launcher manages this phone (v1 config present). */
    val managed: Boolean,
    /** The launcher to bind: one of [LAUNCHER_PACKAGES] (and signed like this app). */
    val launcherPackage: String?,
    /** "nb", "en" or "" (follow the phone). */
    val language: String,
    val mobileData: Boolean,
    /** The library's version; "" = nothing ticked on this phone (an empty library). */
    val libraryVersion: String?,
    val storytelGeneration: Long,
) {
    companion object {
        val UNMANAGED = BridgeConfig(false, null, "", false, null, 0)
    }
}

/** The config from the restrictions' values (`RestrictionsManager.getApplicationRestrictions`). */
fun bridgeConfig(values: Map<String, Any?>): BridgeConfig {
    if ((values[RestrictionKeys.V] as? Int) != 1 || values[RestrictionKeys.MANAGED] != true) return BridgeConfig.UNMANAGED
    return BridgeConfig(
        managed = true,
        launcherPackage = (values[RestrictionKeys.LAUNCHER_PACKAGE] as? String)?.takeIf { it in LAUNCHER_PACKAGES },
        language = (values[RestrictionKeys.LANGUAGE] as? String)?.takeIf { it in setOf("nb", "en") }.orEmpty(),
        mobileData = values[RestrictionKeys.MOBILE_DATA] == true,
        libraryVersion = values[RestrictionKeys.LIBRARY_VERSION] as? String,
        storytelGeneration = (values[RestrictionKeys.STORYTEL_GENERATION] as? Int)?.toLong() ?: 0,
    )
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
