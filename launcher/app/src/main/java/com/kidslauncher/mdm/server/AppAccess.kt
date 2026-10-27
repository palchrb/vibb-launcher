package com.kidslauncher.mdm.server

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager

/*
 * Design 68 §2.2-§2.3: the special accesses a device owner can't grant ("approve on the phone"). Each is an app-op whose
 * permission a third-party app holds only through it, and only Android's page for that app sets it - in a pause, from
 * the PIN-gated Settings' "App access" rows. The launcher reads another app's op itself (`unsafeCheckOpRawNoThrow`
 * needs no GET_APP_OPS_STATS: only ACCESS_RESTRICTED_SETTINGS is read-restricted, and QUERY_ALL_PACKAGES covers the
 * package's visibility); a reading that throws is `unknown`. Pure parts tested in AppAccessTest.
 */

/** The five accesses, with their wire key (`installed_apps[].access`), permission and app-op. */
enum class SpecialAccess(val key: String, val permission: String, val op: String) {
    MANAGE_MEDIA("manage_media", "android.permission.MANAGE_MEDIA", "android:manage_media"),
    EXACT_ALARM("exact_alarm", "android.permission.SCHEDULE_EXACT_ALARM", "android:schedule_exact_alarm"),
    WRITE_SETTINGS("write_settings", "android.permission.WRITE_SETTINGS", "android:write_settings"),
    USAGE_ACCESS("usage_access", "android.permission.PACKAGE_USAGE_STATS", "android:get_usage_stats"),
    FULL_SCREEN("full_screen", "android.permission.USE_FULL_SCREEN_INTENT", "android:use_full_screen_intent"),
}

/** An access as read: the wire value. */
enum class AccessState(val wire: String) { GRANTED("granted"), DENIED("denied"), UNKNOWN("unknown") }

/** `USE_EXACT_ALARM` (API 33, `normal`): an app that declares it sets exact alarms without the op - no `exact_alarm` row. */
const val USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM"

/** The accesses an app wants: the ones its manifest declares ([requested]), `exact_alarm` not when it declares
 * [USE_EXACT_ALARM]. */
fun declaredAccesses(requested: Collection<String>): List<SpecialAccess> = SpecialAccess.entries.filter {
    it.permission in requested && !(it == SpecialAccess.EXACT_ALARM && USE_EXACT_ALARM in requested)
}

/**
 * One access from its raw app-op [mode] (§2.3): ALLOWED granted, IGNORED/ERRORED denied; DEFAULT is what
 * `PackageManager.checkPermission` says ([hasPermission]) - a privileged, preinstalled or role app holds the permission
 * with the op at DEFAULT, as the platform's `PermissionChecker` reads it. `exact_alarm` is also granted when the app is
 * on the device-idle allowlist ([ignoresBatteryOpt]: AlarmManagerService exempts it) and at DEFAULT for a target below
 * 33. Any other mode is unknown.
 */
fun accessState(mode: Int, hasPermission: Boolean, ignoresBatteryOpt: Boolean, targetSdk: Int, kind: SpecialAccess): AccessState {
    val exactAlarm = kind == SpecialAccess.EXACT_ALARM
    if (exactAlarm && ignoresBatteryOpt) return AccessState.GRANTED
    return when (mode) {
        AppOpsManager.MODE_ALLOWED -> AccessState.GRANTED
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> AccessState.DENIED
        AppOpsManager.MODE_DEFAULT ->
            if (hasPermission || (exactAlarm && targetSdk < 33)) AccessState.GRANTED else AccessState.DENIED
        else -> AccessState.UNKNOWN
    }
}

/** One access's inputs as read from Android: the raw app-op mode, `checkPermission`, the battery exemption, the target. */
data class AccessReading(val mode: Int, val hasPermission: Boolean, val ignoresBatteryOpt: Boolean, val targetSdk: Int)

/** [accessState] of what [read] returns; a read that throws (another app's op, its uid, a service) is unknown. */
fun readAccess(kind: SpecialAccess, read: () -> AccessReading): AccessState = try {
    val r = read()
    accessState(r.mode, r.hasPermission, r.ignoresBatteryOpt, r.targetSdk, kind)
} catch (e: Exception) {
    AccessState.UNKNOWN
}

/** `access.camera` for a camera target ([cameraGrants]): the grant state DENIED is `admin_denied`. */
fun cameraReport(grantState: Int): String = if (grantState == GRANT_STATE_DENIED) "admin_denied" else "requested"

/** `installed_apps[].access` (§3.3): `camera` (only for the denial's targets), then each declared access; `null` when
 * there is nothing to say. */
fun appAccessReport(camera: String?, states: List<Pair<SpecialAccess, AccessState>>): Map<String, String>? {
    val report = LinkedHashMap<String, String>()
    camera?.let { report["camera"] = it }
    for ((kind, state) in states) report[kind.key] = state.wire
    return report.takeIf { it.isNotEmpty() }
}

// ---- the PIN-gated Settings' "App access" rows (§4.2) ---------------------------------------------------------------

/**
 * Whether an app gets "App access" rows - the report's rule too ([AppAccessReader.report]): a third-party app only (a system
 * app's privileged grants say nothing a parent can change - E4's M3: Camera, Clock, Messages and Phone each read "Approved -
 * pause first"), allowed - in the effective allowlist ([allowed]: relock blocks out), any while apps are unmanaged (`null`) -,
 * not ours, and not suspended by the last plan ([suspended]).
 */
fun accessEligible(packageName: String, system: Boolean, allowed: Collection<String>?, suspended: Set<String>, ownPackage: String): Boolean =
    !system && packageName != ownPackage && packageName !in suspended && (allowed == null || packageName in allowed)

/** One row: an app and one access it declares, in any state (so the parent can take an approval back). */
data class AccessRow(val packageName: String, val label: String, val kind: SpecialAccess, val state: AccessState) {
    val key: String get() = "app_access:$packageName:${kind.key}"
}

/** The rows: by the app's name, then the accesses in [SpecialAccess]' order; every state kept. */
fun accessRows(apps: List<Pair<String, String>>, states: Map<String, List<Pair<SpecialAccess, AccessState>>>): List<AccessRow> =
    apps.sortedBy { (_, label) -> label.lowercase() }.flatMap { (pkg, label) ->
        states[pkg].orEmpty().sortedBy { it.first.ordinal }.map { (kind, state) -> AccessRow(pkg, label, kind, state) }
    }

/** Android's pages for one app's access, in order: the app's own page (`package:` data), then the plain action (the
 * list of apps). Each is made explicit to an unsuspended system page by `systemPageIntent`, so neither resolves
 * outside a pause on a managed phone. `(action, data)`. */
fun accessPageActions(kind: SpecialAccess, packageName: String): List<Pair<String, String?>> {
    val action = when (kind) {
        SpecialAccess.MANAGE_MEDIA -> "android.settings.REQUEST_MANAGE_MEDIA"
        SpecialAccess.EXACT_ALARM -> "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"
        SpecialAccess.WRITE_SETTINGS -> "android.settings.action.MANAGE_WRITE_SETTINGS"
        // The SDK says "Input: Nothing"; Settings' AppUsageAccessSettingsActivity takes `package:`.
        SpecialAccess.USAGE_ACCESS -> "android.settings.USAGE_ACCESS_SETTINGS"
        SpecialAccess.FULL_SCREEN -> "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"
    }
    return listOf(action to "package:$packageName", action to null)
}

/** A row's summary. */
enum class AccessRowText { APPROVED, APPROVED_PAUSE_FIRST, OPEN, UNKNOWN_OPEN, PAUSE_FIRST, WAIT, NO_PAGE }

/** [state] as read, [access] = `systemPageAccess` for the row's pages: an approval says how to change it, anything else
 * says how to get to the page (the write-settings row's states). */
fun accessRowText(state: AccessState, access: SystemPageAccess): AccessRowText = when {
    state == AccessState.GRANTED -> if (access == SystemPageAccess.CAN_OPEN) AccessRowText.APPROVED else AccessRowText.APPROVED_PAUSE_FIRST
    access == SystemPageAccess.CAN_OPEN -> if (state == AccessState.UNKNOWN) AccessRowText.UNKNOWN_OPEN else AccessRowText.OPEN
    access == SystemPageAccess.PAUSE_FIRST -> AccessRowText.PAUSE_FIRST
    access == SystemPageAccess.WAIT -> AccessRowText.WAIT
    else -> AccessRowText.NO_PAGE
}

/** The summary's string (pinned by test). [AccessRowText.OPEN] takes the app's name. For `exact_alarm` it also says to
 * open the app once in the pause: Etar asks for the battery exemption at its first start, which can't resolve with
 * Android's Settings hidden, and crashes (E4's M2). */
fun accessRowTextRes(text: AccessRowText, kind: SpecialAccess): Int = when (text) {
    AccessRowText.APPROVED -> com.kidslauncher.mdm.R.string.app_access_approved
    AccessRowText.APPROVED_PAUSE_FIRST -> com.kidslauncher.mdm.R.string.app_access_approved_pause_first
    AccessRowText.OPEN -> if (kind == SpecialAccess.EXACT_ALARM) {
        com.kidslauncher.mdm.R.string.app_access_open_exact_alarm
    } else {
        com.kidslauncher.mdm.R.string.app_access_open
    }
    AccessRowText.UNKNOWN_OPEN -> com.kidslauncher.mdm.R.string.app_access_unknown
    AccessRowText.PAUSE_FIRST -> com.kidslauncher.mdm.R.string.settings_mdm_write_settings_pause_first
    AccessRowText.WAIT -> com.kidslauncher.mdm.R.string.app_access_wait
    AccessRowText.NO_PAGE -> com.kidslauncher.mdm.R.string.app_access_no_page
}

/** The access's name in a row's title ("Immich: Media management"). */
fun accessNameRes(kind: SpecialAccess): Int = when (kind) {
    SpecialAccess.MANAGE_MEDIA -> com.kidslauncher.mdm.R.string.app_access_manage_media
    SpecialAccess.EXACT_ALARM -> com.kidslauncher.mdm.R.string.app_access_exact_alarm
    SpecialAccess.WRITE_SETTINGS -> com.kidslauncher.mdm.R.string.settings_mdm_write_settings
    SpecialAccess.USAGE_ACCESS -> com.kidslauncher.mdm.R.string.app_access_usage_access
    SpecialAccess.FULL_SCREEN -> com.kidslauncher.mdm.R.string.app_access_full_screen
}

object AppAccessReader {
    /** Each access [info] (read with `GET_PERMISSIONS`) declares, read now; a reading that throws is unknown. The raw
     * read is deprecated in API 36 (every by-name variant is), still fine on 34-36 (§2.3). */
    @Suppress("DEPRECATION")
    fun states(context: Context, info: PackageInfo): List<Pair<SpecialAccess, AccessState>> {
        val kinds = declaredAccesses(info.requestedPermissions.orEmpty().toList())
        if (kinds.isEmpty()) return emptyList()
        val pm = context.packageManager
        val pkg = info.packageName
        val app = info.applicationInfo
        return kinds.map { kind ->
            kind to readAccess(kind) {
                AccessReading(
                    mode = context.getSystemService(AppOpsManager::class.java)!!.unsafeCheckOpRawNoThrow(kind.op, app!!.uid, pkg),
                    hasPermission = pm.checkPermission(kind.permission, pkg) == PackageManager.PERMISSION_GRANTED,
                    ignoresBatteryOpt = kind == SpecialAccess.EXACT_ALARM &&
                        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg) == true,
                    targetSdk = app.targetSdkVersion,
                )
            }
        }
    }

    /** [info]'s `installed_apps[].access`, for a third-party app but ours only (a system app's privileged grants say
     * nothing a parent can change - the code QA's L2): `camera` when it requests it (unreadable grant state:
     * `requested`), then [states]. */
    fun report(context: Context, info: PackageInfo): Map<String, String>? {
        val requested = info.requestedPermissions.orEmpty()
        val thirdParty = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0
        if (!thirdParty || info.packageName == context.packageName) return null
        val camera = if (CAMERA_PERMISSION in requested) {
            cameraReport(
                try {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)!!
                    dpm.getPermissionGrantState(ComponentName(context, MdmDeviceAdminReceiver::class.java), info.packageName, CAMERA_PERMISSION)
                } catch (e: Exception) {
                    GRANT_STATE_DEFAULT
                },
            )
        } else {
            null
        }
        return appAccessReport(camera, states(context, info))
    }

    /** A row as shown: its access read now, its pages, whether they open ([systemPageAccess]). */
    class RowModel(val row: AccessRow, val pages: List<android.content.Intent>, val text: AccessRowText)

    /** The "App access" rows now: the allowed apps' declared accesses ([accessEligible], [accessRows]), each with its
     * pages and summary. [pageMissing]: rows whose every page threw ActivityNotFoundException on the last tap. Off the
     * main thread; never throws (nothing readable = no rows). */
    fun rows(context: Context, pageMissing: Set<String>): List<RowModel> = try {
        val pm = context.packageManager
        val allowed = effectiveAllowlist(currentPolicyDecision().policy?.allowlist, WindowRelock.blockedPackages(context))
        val suspended = AppEnforcer.lastPlanSuspend.orEmpty()
        val infos = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .filter {
                val system = (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                accessEligible(it.packageName, system, allowed, suspended, context.packageName) &&
                    declaredAccesses(it.requestedPermissions.orEmpty().toList()).isNotEmpty()
            }
        val labels = infos.map { it.packageName to (it.applicationInfo?.let { app -> pm.getApplicationLabel(app).toString() } ?: it.packageName) }
        val windowOpen = RestrictionsPause.isActive() || OfflineOverride.isActive()
        accessRows(labels, infos.associate { it.packageName to states(context, it) }).map { row ->
            val pages = accessPageActions(row.kind, row.packageName).mapNotNull { (action, data) ->
                systemPageIntent(context, android.content.Intent(action).apply { data?.let { setData(android.net.Uri.parse(it)) } })
            }
            val access = systemPageAccess(pages.isNotEmpty(), windowOpen, AppEnforcer.lastEnforcedOverride == true, row.key in pageMissing)
            RowModel(row, pages, accessRowText(row.state, access))
        }
    } catch (e: Exception) {
        android.util.Log.w("AppAccess", "Couldn't read the app access rows", e)
        emptyList()
    }

    /** One row's access read again (on return from Android's page). */
    fun stateNow(context: Context, row: AccessRow): AccessState? = try {
        val info = context.packageManager.getPackageInfo(row.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        states(context, info).firstOrNull { it.first == row.kind }?.second
    } catch (e: Exception) {
        null
    }
}
