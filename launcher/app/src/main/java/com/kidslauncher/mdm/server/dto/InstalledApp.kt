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
    /** `PackageInfo.longVersionCode` (2026-10-25, PLAN "Installed versionCode per catalog app in the status report"):
     * the server's "<app> updated on <phone>" for every catalog app. `null` (left out) if unreadable - an older
     * server ignores it, an older launcher never sends it. */
    val versionCode: Long? = null,
    /** `PackageInfo.versionName`, trimmed and cut to [INSTALLED_VERSION_NAME_MAX] characters ([installedVersionName]);
     * `null` (left out) when there is none. */
    val versionName: String? = null,
)

/** The longest versionName a report carries (the server clips to the same). */
const val INSTALLED_VERSION_NAME_MAX = 60

/** [raw] (`PackageInfo.versionName`) as the report carries it: trimmed, at most [INSTALLED_VERSION_NAME_MAX] code
 * points (never half a surrogate pair), `null` for none or blank. */
fun installedVersionName(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.codePointCount(0, trimmed.length) <= INSTALLED_VERSION_NAME_MAX) return trimmed
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, INSTALLED_VERSION_NAME_MAX))
}
