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
    if ((values["v"] as? Int) != 1 || values["managed"] != true) return BridgeConfig.UNMANAGED
    return BridgeConfig(
        managed = true,
        launcherPackage = (values["launcher_package"] as? String)?.takeIf { it in LAUNCHER_PACKAGES },
        language = (values["language"] as? String)?.takeIf { it in setOf("nb", "en") }.orEmpty(),
        mobileData = values["mobile_data"] == true,
        libraryVersion = values["library_version"] as? String,
        storytelGeneration = (values["storytel_generation"] as? Long) ?: (values["storytel_generation"] as? Int)?.toLong() ?: 0,
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
