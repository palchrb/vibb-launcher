package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.util.Log

private const val LOG_TAG = "BackupService"

/**
 * Android's backup to Google kept off once the phone has a Google account (Play as an app source,
 * docs/setup/google-account.md at the monorepo root). AOSP switches the backup service off when a
 * device owner is set (`DevicePolicyManagerService.setDeviceOwner`), and only a device or profile
 * owner can switch it on again (`DevicePolicyManager`'s backup-service setter, API 26+). So
 * [enforce] runs on every [AppEnforcer.apply] while the phone is managed ([hardeningManaged]): it
 * reads the state and, if anything turned it on, calls `setBackupServiceEnabled(admin, false)` -
 * the only call of that setter in this codebase (BackupServiceInvariantsTest). The override and
 * the pause don't lift it ([backupServiceAction] has no such input), and there is no server switch.
 * The state is reported as `backup_service_enabled` ([reportedState]).
 *
 * Every call here is a binder call into system_server: never on the main thread (both functions
 * refuse it), and every exception is caught and logged - a failure must never stop the rest of
 * `apply()` or the status report.
 */
object BackupService {

    /** See the class doc. [managed] = [hardeningManaged] for the enforced policy. */
    fun enforce(dpm: DevicePolicyManager, admin: ComponentName, managed: Boolean) {
        if (onMainThread()) {
            Log.w(LOG_TAG, "Not checking the backup service on the main thread")
            return
        }
        try {
            val enabled = if (managed) dpm.isBackupServiceEnabled(admin) else null
            when (backupServiceAction(managed, enabled)) {
                BackupServiceAction.NONE -> Unit
                BackupServiceAction.TURN_OFF -> {
                    dpm.setBackupServiceEnabled(admin, false)
                    Log.i(LOG_TAG, "The backup service was on - switched it off")
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't check or switch off the backup service", e)
        }
    }

    /** For the status report: whether the backup service is on now; `null` when we aren't device
     * owner, it can't be read, or we're asked on the main thread. */
    fun reportedState(context: Context): Boolean? {
        if (onMainThread()) return null
        return try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return null
            if (!dpm.isDeviceOwnerApp(context.packageName)) return null
            dpm.isBackupServiceEnabled(ComponentName(context, MdmDeviceAdminReceiver::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the backup service state", e)
            null
        }
    }

    private fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}
