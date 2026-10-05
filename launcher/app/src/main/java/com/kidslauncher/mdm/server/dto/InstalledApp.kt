package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One entry in a status report's self-reported app list - lets the admin site's unified Apps
 * list show real installed apps instead of asking a parent to type package names.
 * [preinstalled] mirrors `ApplicationInfo.FLAG_SYSTEM` (already checked in
 * [com.kidslauncher.mdm.server.controllablePackages], just not previously reported anywhere) -
 * it's what lets the admin UI tell "can be suspended but never actually uninstalled" apps apart
 * from ones it pushed itself. */
@Serializable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val preinstalled: Boolean,
    /** `InstallSourceInfo.installingPackageName` (`com.android.vending` = Play) - the server keeps
     * one source per package (handy step 7): a Play-installed app isn't also pushed from the
     * catalog. `null` if unknown. */
    val installer: String? = null,
)
