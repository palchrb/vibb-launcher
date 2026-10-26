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
            kind to try {
                val mode = context.getSystemService(AppOpsManager::class.java)!!.unsafeCheckOpRawNoThrow(kind.op, app!!.uid, pkg)
                val exempt = kind == SpecialAccess.EXACT_ALARM &&
                    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg) == true
                accessState(mode, pm.checkPermission(kind.permission, pkg) == PackageManager.PERMISSION_GRANTED, exempt, app.targetSdkVersion, kind)
            } catch (e: Exception) {
                AccessState.UNKNOWN
            }
        }
    }

    /** [info]'s `installed_apps[].access`: `camera` for a third-party app but ours that requests it (unreadable grant
     * state: `requested`), then [states]. */
    fun report(context: Context, info: PackageInfo): Map<String, String>? {
        val requested = info.requestedPermissions.orEmpty()
        val thirdParty = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0
        val camera = if (thirdParty && info.packageName != context.packageName && CAMERA_PERMISSION in requested) {
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
}
