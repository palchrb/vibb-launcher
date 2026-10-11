package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.server.dto.ForeignAccessReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val LOG_TAG = "ForeignAccess"

/**
 * What the parent PIN can't be typed next to (the fix round of design 24b, QA High 1, the user's
 * "both"): an active device admin other than ours, or an enabled accessibility service, of a
 * non-system app outside the allowlist (relock-blocked apps count as outside). A device admin can't
 * be hidden, suspended or uninstalled by the device owner, and an accessibility service can claim
 * `isAccessibilityTool` and read the keypad. `null` = nothing, or apps unmanaged ([allowlist]
 * `null`: calls-only phones). [isSystem] answers `null` for a package that isn't installed - an
 * uninstalled package reads nothing, so it is skipped.
 */
fun foreignAccess(
    admins: Collection<String>,
    accessibility: Collection<String>,
    allowlist: Collection<String>?,
    ownPackage: String,
    isSystem: (String) -> Boolean?,
): ForeignAccessReport? {
    if (allowlist == null) return null
    fun foreign(pkg: String) = pkg != ownPackage && pkg !in allowlist && isSystem(pkg) == false
    val report = ForeignAccessReport(
        admins = reportPackages(admins.filter(::foreign)),
        accessibility = reportPackages(accessibility.filter(::foreign)),
    )
    return report.takeIf { it.admins.isNotEmpty() || it.accessibility.isNotEmpty() }
}

/**
 * The backstop (QA 24b High 1 (b)): while [current] finds something, the phone refuses the parent
 * PIN in all five entries ([ParentPin.status] before the keypad, [ParentPin.check] before the
 * verify) and the dialogs point to the parent app, whose device page shows the steps and can start
 * a pause without the PIN (`start_pause`). Checked after a relock, at every sync (the status
 * report's `foreign_access`) and at a process start; a new finding there asks for a sync, so the
 * server hears of it at once. The PIN works again as soon as a check finds nothing.
 */
object ForeignAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The live check. Device owner only (nothing is managed otherwise). May throw. */
    fun current(context: Context): ForeignAccessReport? {
        val app = context.applicationContext
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return null
        if (!dpm.isDeviceOwnerApp(app.packageName)) return null
        val allowlist = effectiveAllowlist(currentPolicyDecision().policy?.allowlist, WindowRelock.blockedPackages(app))
            ?: return null
        val accessibility = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
        return foreignAccess(
            admins = dpm.activeAdmins.orEmpty().map { it.packageName },
            accessibility = accessibility,
            allowlist = allowlist,
            ownPackage = app.packageName,
            isSystem = { pkg -> systemOrNull(app, pkg) },
        )
    }

    /** [current], remembered; a failed check answers with the last finding (it fails closed to
     * what was seen). Main thread allowed: a few binder calls, no hashing. */
    fun blocksParentPin(context: Context): Boolean = latest(context) != null

    /** For the status report: the live finding, remembered. */
    fun report(context: Context): ForeignAccessReport? = latest(context)

    /** After a relock and at a process start: a new finding asks for a sync. */
    fun checkInBackground(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val before = WindowRelock.store(app).foreignAccess()
            val now = latest(app)
            if (now != null && now != before) {
                Log.w(LOG_TAG, "Foreign access: $now - the parent PIN is refused")
                try {
                    com.kidslauncher.mdm.push.SyncRunner.request(app, "foreign_access")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't request a sync", e)
                }
            }
        }
    }

    private fun latest(context: Context): ForeignAccessReport? {
        val store = WindowRelock.store(context.applicationContext)
        return try {
            current(context).also { if (it != store.foreignAccess()) store.saveForeignAccess(it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Foreign-access check failed - the last finding stands", e)
            store.foreignAccess()
        }
    }

    private fun systemOrNull(app: Context, pkg: String): Boolean? = try {
        val info = app.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) null else info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
